import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.Function;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.SourceType;
import java.util.Iterator;

/** Inspect one instruction's high P-code, then bind one verified union edge.
 * Args: entryVA siteVA [sequenceTime edge unionName fieldName]
 * edge=-1 means output; otherwise it is the zero-based input index.
 * No embedded layouts/signatures. Re-inspect after each mutation.
 */
public class RefineUnionFacet extends GhidraScript {
    private DataType unionParent(DataType type) {
        if (type instanceof Pointer) type = ((Pointer) type).getDataType();
        if (type instanceof PartialUnion) type = ((PartialUnion) type).getParent();
        return type;
    }

    @Override public void run() throws Exception {
        String[] a = getScriptArgs();
        if (a.length != 2 && a.length != 6) throw new IllegalArgumentException(
                "entryVA siteVA [sequenceTime edge unionName fieldName]");
        Function f = getFunctionAt(toAddr(Long.decode(a[0])));
        Address site = toAddr(Long.decode(a[1]));
        if (f == null || !f.getBody().contains(site))
            throw new IllegalArgumentException("not an entry or site outside function");
        DecompInterface di = new DecompInterface();
        try {
            di.openProgram(currentProgram);
            HighFunction hf = di.decompileFunction(f, 120, monitor).getHighFunction();
            if (hf == null) throw new IllegalStateException("decompilation failed");
            Iterator<PcodeOpAST> ops = hf.getPcodeOps(site);
            PcodeOpAST selected = null;
            while (ops.hasNext()) {
                PcodeOpAST op = ops.next();
                println("op " + op.getSeqnum().getTime() + " " + op);
                for (int edge = -1; edge < op.getNumInputs(); edge++) {
                    Varnode v = edge < 0 ? op.getOutput() : op.getInput(edge);
                    if (v != null && v.getHigh() != null)
                        println("  edge " + edge + " " + v + " type="
                                + v.getHigh().getDataType().getPathName());
                }
                if (a.length == 6 && op.getSeqnum().getTime() == Integer.decode(a[2])) {
                    if (selected != null) throw new IllegalArgumentException("ambiguous operation");
                    selected = op;
                }
            }
            if (a.length == 2) return;
            if (selected == null || selected.getOpcode() == PcodeOp.INDIRECT ||
                    selected.getOpcode() == PcodeOp.MULTIEQUAL)
                throw new IllegalArgumentException("missing operation or synthetic edge; inspect a real use");
            int edge = Integer.decode(a[3]);
            if (edge < -1 || edge >= selected.getNumInputs())
                throw new IllegalArgumentException("invalid edge");
            Varnode value = edge == -1 ? selected.getOutput() : selected.getInput(edge);
            if (value == null || value.getHigh() == null)
                throw new IllegalArgumentException("edge has no high type");
            DataType dt = value.getHigh().getDataType();
            DataType parent = unionParent(dt);
            if (!(parent instanceof Union) || !parent.getName().equals(a[4]))
                throw new IllegalArgumentException("edge type is not expected union: " + dt);
            int field = -1;
            for (DataTypeComponent c : ((Union) parent).getComponents())
                if (a[5].equals(c.getFieldName())) field = c.getOrdinal();
            if (field < 0) throw new IllegalArgumentException("missing union field");
            // SUBPIECE's partial-union resolution is attached to its output edge.
            int hashEdge = selected.getOpcode() == PcodeOp.SUBPIECE && edge == 0
                    && !(dt instanceof Pointer) ? -1 : edge;
            DynamicHash hash = new DynamicHash(selected, hashEdge, hf);
            if (hash.getHash() == 0 || Address.NO_ADDRESS.equals(hash.getAddress()))
                throw new IllegalArgumentException("unhashable edge; inspect a surviving use");
            HighFunctionDBUtil.writeUnionFacet(f, dt, field, hash.getAddress(), hash.getHash(),
                    SourceType.USER_DEFINED);
            println("facet_written=" + parent.getName() + "." + a[5]
                    + " anchor=" + hash.getAddress() + " hash=" + Long.toHexString(hash.getHash()));
        } finally { di.dispose(); }
    }
}
