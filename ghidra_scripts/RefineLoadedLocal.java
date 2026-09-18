import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.SourceType;
import java.util.Iterator;

/** Bind a LOAD result separately from merged call results, after raw/high
 * p-code inspection. Args: entryVA siteVA importedPointeeType name.
 * Uses the canonical imported type; defines no object layout or signature. */
public class RefineLoadedLocal extends GhidraScript {
    @Override public void run() throws Exception {
        String[] a = getScriptArgs();
        if (a.length != 4) throw new IllegalArgumentException("bad arguments");
        Function f = getFunctionAt(toAddr(Long.decode(a[0])));
        DataType pointee = null;
        Iterator<DataType> types = currentProgram.getDataTypeManager().getAllDataTypes();
        while (types.hasNext()) {
            DataType t = types.next();
            if (!t.getName().equals(a[2])) continue;
            if (pointee != null) throw new IllegalArgumentException("ambiguous type");
            pointee = t;
        }
        if (f == null || pointee == null) throw new IllegalArgumentException("missing function/type");
        DataType type = new PointerDataType(pointee, currentProgram.getDataTypeManager());
        DecompInterface decompiler = new DecompInterface();
        try {
            decompiler.openProgram(currentProgram);
            HighFunction hf = decompiler.decompileFunction(f, 60, monitor).getHighFunction();
            if (hf == null) throw new IllegalStateException("decompile failed");
            VarnodeAST match = null;
            Iterator<PcodeOpAST> ops = hf.getPcodeOps(toAddr(Long.decode(a[1])));
            while (ops.hasNext()) {
                PcodeOpAST op = ops.next();
                if (op.getOpcode() != PcodeOp.LOAD || op.getOutput() == null) continue;
                if (op.getOutput().getSize() != type.getLength()) continue;
                if (match != null) throw new IllegalArgumentException("ambiguous LOAD");
                match = (VarnodeAST)op.getOutput();
            }
            if (match == null) throw new IllegalArgumentException("LOAD not found");
            HighVariable split = hf.splitOutMergeGroup(match.getHigh(), match);
            if (split.getSymbol() == null) throw new IllegalStateException("split has no symbol");
            HighFunctionDBUtil.updateDBVariable(split.getSymbol(), a[3], type, SourceType.USER_DEFINED);
            println("saved=" + a[3] + " type=" + type.getName());
        } finally { decompiler.dispose(); }
    }
}
