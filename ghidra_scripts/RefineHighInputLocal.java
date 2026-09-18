import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.SourceType;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Split and type one local SSA value used as an instruction's high-P-code
 * input. Args: entryVA siteVA opcode inputIndex space:offset:size importedType
 * newName. InspectHighAt must be run immediately beforehand; this script is for
 * values whose useful pointer type is established by a following CAST/PTRSUB
 * but whose merged local still decompiles as undefined8. */
public class RefineHighInputLocal extends GhidraScript {
    @Override public void run() throws Exception {
        String[] a = getScriptArgs();
        if (a.length != 7) throw new IllegalArgumentException(
                "entryVA siteVA opcode inputIndex space:offset:size importedType newName");
        Function f = getFunctionAt(toAddr(Long.decode(a[0])));
        if (f == null || !f.getBody().contains(toAddr(Long.decode(a[1]))))
            throw new IllegalArgumentException("missing entry or site outside body");
        int inputIndex = Integer.decode(a[3]);
        String[] storage = a[4].split(":");
        if (storage.length != 3) throw new IllegalArgumentException("bad varnode storage");
        long offset = Long.decode(storage[1]);
        int size = Integer.decode(storage[2]);
        String requestedType = a[5].replace("%20", " ");
        DataType type = null;
        Iterator<DataType> types = currentProgram.getDataTypeManager().getAllDataTypes();
        while (types.hasNext()) {
            DataType candidate = types.next();
            if (!(requestedType.startsWith("/") ? candidate.getPathName().equals(requestedType)
                    : candidate.getName().equals(requestedType))) continue;
            if (type != null) throw new IllegalArgumentException("ambiguous type");
            type = candidate;
        }
        if (type == null || type.getLength() != size)
            throw new IllegalArgumentException("missing type or width mismatch");
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
                if (!op.getMnemonic().equals(a[2]) || inputIndex >= op.getNumInputs()) continue;
                Varnode input = op.getInput(inputIndex);
                if (input.getSize() != size || input.getOffset() != offset ||
                        !input.getAddress().getAddressSpace().getName().equals(storage[0])) continue;
                if (selected != null) throw new IllegalArgumentException("ambiguous input");
                selected = input;
            }
            if (selected == null || !(selected.getHigh() instanceof HighLocal))
                throw new IllegalArgumentException("missing input or not a high local");
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
            HighFunctionDBUtil.updateDBVariable(symbol, a[6], type, SourceType.USER_DEFINED);
            println("saved=" + a[6] + " type=" + type + " merge=" + selected.getMergeGroup());
        } finally { di.dispose(); }
    }
}
