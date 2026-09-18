import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.LocalVariableImpl;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.SourceType;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Split and persist one already typed high-local merge group, after inspecting
 * raw/high P-code. Args: entryVA siteVA opcode space:offset:size newName
 * [dynamic [obsoleteNamesCsv]]. The optional dynamic mode binds the selected
 * SSA value when the ordinary register/first-use annotation fails to survive.
 * The output varnode is an SSA anchor, not a physical-register type override.
 * Re-decompile after every use; never supply a stale temporary variable name.
 */
public class RefineHighLocal extends GhidraScript {
    @Override public void run() throws Exception {
        String[] a = getScriptArgs();
        if (a.length < 5 || a.length > 7 ||
                (a.length > 5 && !a[5].equals("dynamic")))
            throw new IllegalArgumentException(
                    "entryVA siteVA opcode space:offset:size newName [dynamic [obsoleteNamesCsv]]");
        Function f = getFunctionAt(toAddr(Long.decode(a[0])));
        if (f == null || !f.getBody().contains(toAddr(Long.decode(a[1]))))
            throw new IllegalArgumentException("missing entry or site outside body");
        String[] storage = a[3].split(":");
        if (storage.length != 3) throw new IllegalArgumentException("bad varnode storage");
        long offset = Long.decode(storage[1]);
        int size = Integer.decode(storage[2]);
        DecompInterface di = new DecompInterface();
        try {
            di.setOptions(new DecompileOptions());
            di.openProgram(currentProgram);
            HighFunction hf = di.decompileFunction(f, 120, monitor).getHighFunction();
            if (hf == null) throw new IllegalStateException("decompilation failed");
            Varnode selected = null;
            Iterator<PcodeOpAST> ops = hf.getPcodeOps(toAddr(Long.decode(a[1])));
            while (ops.hasNext()) {
                PcodeOpAST op = ops.next();
                Varnode v = op.getOutput();
                if (!op.getMnemonic().equals(a[2]) || v == null || v.getSize() != size ||
                        v.getOffset() != offset ||
                        !v.getAddress().getAddressSpace().getName().equals(storage[0])) continue;
                if (selected != null) throw new IllegalArgumentException("ambiguous output");
                selected = v;
            }
            if (selected == null || !(selected.getHigh() instanceof HighLocal))
                throw new IllegalArgumentException("missing output or not a high local");
            HighVariable old = selected.getHigh();
            if (old.getDataType().getLength() != size)
                throw new IllegalArgumentException("high type width differs from selected value");
            short selectedGroup = selected.getMergeGroup();
            Set<Short> groups = new HashSet<>();
            int beforeCount = 0;
            for (Varnode instance : old.getInstances()) {
                groups.add(instance.getMergeGroup());
                ++beforeCount;
                println("instance=" + instance + " pc=" + instance.getPCAddress()
                        + " group=" + instance.getMergeGroup());
            }
            println("before=" + old.getName() + " selected_group=" + selectedGroup
                    + " groups=" + groups);
            // Ghidra's official Split Out As New Variable action is only enabled
            // when another forced merge group exists. splitOutMergeGroup returns
            // the original high unchanged otherwise, which must never be reported
            // as a successful refinement of one branch/lifetime.
            if (groups.size() < 2)
                throw new IllegalStateException(
                        "single merge group; split would rename/retype the whole high variable");
            HighVariable split = hf.splitOutMergeGroup(old, selected);
            if (split == old || split.getInstances().length == 0 ||
                    split.getInstances().length >= beforeCount)
                throw new IllegalStateException("split did not isolate a strict subset");
            for (Varnode instance : split.getInstances())
                if (instance.getMergeGroup() != selectedGroup)
                    throw new IllegalStateException("split contains a non-target merge group");
            HighSymbol symbol = split.getSymbol();
            if (symbol == null || symbol.isParameter() || symbol.isGlobal())
                throw new IllegalStateException("split has no local symbol");
            if (a.length == 5) {
                HighFunctionDBUtil.updateDBVariable(symbol, a[4], split.getDataType(),
                        SourceType.USER_DEFINED);
            } else {
                DynamicEntry entry = DynamicEntry.build(selected);
                long first = entry.getPCAdress().subtract(f.getEntryPoint());
                if (entry.getHash() == 0 || first < 0 || first > Integer.MAX_VALUE ||
                        !f.getBody().contains(entry.getPCAdress()))
                    throw new IllegalStateException("invalid dynamic anchor");
                // Only remove names explicitly identified by the caller as
                // failed earlier bindings; unrelated annotations stay intact.
                if (a.length == 7) {
                    java.util.Set<String> obsolete = new java.util.HashSet<>(
                            java.util.Arrays.asList(a[6].split(",")));
                    for (Variable variable : f.getLocalVariables())
                        if (obsolete.contains(variable.getName())) {
                            println("remove " + variable.getName() + " " + variable.getVariableStorage());
                            f.removeVariable(variable);
                        }
                }
                f.addLocalVariable(new LocalVariableImpl(a[4], (int)first,
                        split.getDataType(), entry.getStorage(), currentProgram), SourceType.USER_DEFINED);
                println("dynamic=" + entry.getStorage() + " anchor=" + entry.getPCAdress());
            }
            println("saved=" + a[4] + " type=" + split.getDataType());
        } finally { di.dispose(); }
    }
}
