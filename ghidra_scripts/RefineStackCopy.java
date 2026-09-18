import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.SourceType;
import java.util.Iterator;

/** Split/type a high COPY from a known reused stack slot.
 * Args: entryVA siteVA signedStackOffset importedType name [obsoleteNamesCsv].
 * Used only after raw/high p-code establishes the COPY and merge groups. */
public class RefineStackCopy extends GhidraScript {
    @Override public void run() throws Exception {
        String[] a = getScriptArgs();
        if (a.length < 5 || a.length > 6) throw new IllegalArgumentException("bad arguments");
        Function f = getFunctionAt(toAddr(Long.decode(a[0])));
        Address site = toAddr(Long.decode(a[1]));
        long offset = Long.decode(a[2]);
        DataType type = null;
        Iterator<DataType> types = currentProgram.getDataTypeManager().getAllDataTypes();
        while (types.hasNext()) {
            DataType t = types.next();
            if (!t.getName().equals(a[3])) continue;
            if (type != null) throw new IllegalArgumentException("ambiguous type");
            type = t;
        }
        if (f == null || type == null) throw new IllegalArgumentException("missing function/type");
        if (a.length == 6) {
            java.util.Set<String> obsolete = new java.util.HashSet<>(
                    java.util.Arrays.asList(a[5].split(",")));
            for (Variable v : f.getLocalVariables()) if (obsolete.contains(v.getName())) {
                println("remove " + v.getName()); f.removeVariable(v);
            }
        }
        DecompInterface decompiler = new DecompInterface();
        try {
            decompiler.openProgram(currentProgram);
            HighFunction hf = decompiler.decompileFunction(f, 60, monitor).getHighFunction();
            if (hf == null) throw new IllegalStateException("decompile failed");
            VarnodeAST match = null;
            Iterator<PcodeOpAST> ops = hf.getPcodeOps(site);
            while (ops.hasNext()) {
                PcodeOpAST op = ops.next();
                if (op.getOpcode() != PcodeOp.COPY || op.getNumInputs() != 1) continue;
                Varnode input = op.getInput(0);
                if (!input.getAddress().isStackAddress() || input.getOffset() != offset) continue;
                if (op.getOutput() == null || op.getOutput().getSize() != type.getLength()) continue;
                if (match != null) throw new IllegalArgumentException("ambiguous COPY");
                match = (VarnodeAST)op.getOutput();
            }
            if (match == null) throw new IllegalArgumentException("COPY not found");
            HighVariable before = match.getHigh();
            println("before=" + before.getName() + " merge=" + match.getMergeGroup());
            HighVariable split = hf.splitOutMergeGroup(before, match);
            if (split.getSymbol() == null) throw new IllegalStateException("split has no symbol");
            HighFunctionDBUtil.updateDBVariable(split.getSymbol(), a[4], type, SourceType.USER_DEFINED);
            println("saved=" + a[4] + " type=" + type.getName());
        } finally { decompiler.dispose(); }
    }
}
