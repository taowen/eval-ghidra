import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.LocalVariableImpl;
import ghidra.program.model.listing.VariableStorage;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.SourceType;
import java.util.Iterator;

/** Bind an allocator result after raw/high P-code inspection.
 * Args: entryVA callsiteVA importedAllocationType name. Requires the pinned
 * operator-new target and a constant size equal to the canonical layout.
 * Splits the selected result from other allocations/global publication;
 * defines no object layout, signature, code or control-flow override. */
public class RefineAllocatedLocal extends GhidraScript {
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
                if (op.getOpcode() != PcodeOp.CALL || op.getOutput() == null) continue;
                if (op.getOutput().getSize() != type.getLength()) continue;
                if (op.getNumInputs() != 2 || op.getInput(0).getOffset() != 0x270e350L ||
                    !op.getInput(1).isConstant() || op.getInput(1).getOffset() != pointee.getLength())
                    throw new IllegalArgumentException("wrong allocator target/size");
                if (match != null) throw new IllegalArgumentException("ambiguous allocator result");
                match = (VarnodeAST)op.getOutput();
            }
            if (match == null) throw new IllegalArgumentException("allocator result not found");
            if (match.getHigh() instanceof HighGlobal) {
                // A result merged with later global publication must remain
                // a local allocation. updateDBVariable would rewrite the
                // global's symbol/type rather than create this local.
                DynamicEntry entry = DynamicEntry.build(match);
                long first = entry.getPCAdress().subtract(f.getEntryPoint());
                if (first < 0 || first > Integer.MAX_VALUE)
                    throw new IllegalStateException("anchor outside function");
                // DynamicEntry.getStorage() uses the enclosing global
                // symbol's16-byte handle size. This selected result is8 bytes.
                var storage = new VariableStorage(currentProgram,
                        AddressSpace.HASH_SPACE.getAddress(entry.getHash()), match.getSize());
                f.addLocalVariable(new LocalVariableImpl(a[3], (int)first,
                        type, storage, currentProgram), SourceType.USER_DEFINED);
                println("saved dynamic=" + a[3] + " " + storage);
                return;
            }
            HighVariable split = hf.splitOutMergeGroup(match.getHigh(), match);
            if (split.getSymbol() == null) throw new IllegalStateException("split has no symbol");
            HighFunctionDBUtil.updateDBVariable(split.getSymbol(), a[3], type, SourceType.USER_DEFINED);
            println("saved=" + a[3] + " type=" + type.getName());
        } finally { decompiler.dispose(); }
    }
}
