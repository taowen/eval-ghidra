import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.symbol.SourceType;
import java.util.Iterator;

/** Replace overlapping stack locals with one type already imported from a
 * type-header module. Arguments: function VA, signed offset, name, type name/path,
 * optional expected byte size (checked before any locals are removed).
 * No embedded function signatures or structure definitions. */
public class RefineStackSlot extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 4 && args.length != 5) throw new IllegalArgumentException(
                "functionVA signedStackOffset name importedType [expectedBytes]");
        Function function = getFunctionAt(toAddr(Long.decode(args[0])));
        if (function == null) throw new IllegalArgumentException("not a function entry");
        DataType type = null;
        // A bare name denotes the canonical root type. Older imports may have
        // retained category-scoped copies such as
        // /tutorial-types/runtime.h/TutorialLoggerHandle; those are not an ambiguity when
        // the root type exists. An explicit /path still selects that exact type.
        if (!args[3].startsWith("/")) {
            type = currentProgram.getDataTypeManager().getDataType("/" + args[3]);
        }
        Iterator<DataType> types = currentProgram.getDataTypeManager().getAllDataTypes();
        while (types.hasNext()) {
            DataType candidate = types.next();
            if (args[3].startsWith("/") && candidate.getPathName().equals(args[3])) {
                if (type != null) throw new IllegalArgumentException("ambiguous type");
                type = candidate;
            } else if (type == null && candidate.getName().equals(args[3])) {
                type = candidate;
            }
        }
        if (type == null || type.getLength() <= 0)
            throw new IllegalArgumentException("missing fixed-size imported type");
        if (args.length == 5 && type.getLength() != Integer.decode(args[4]))
            throw new IllegalArgumentException("imported type size " + type.getLength()
                    + " does not match expected " + args[4]);
        int start = Integer.decode(args[1]);
        int end = start + type.getLength();
        if (end > 0) throw new IllegalArgumentException("not a local stack slot");
        for (Variable variable : function.getLocalVariables()) {
            if (!variable.isStackVariable()) continue;
            int offset = variable.getStackOffset();
            if (offset < end && offset + variable.getLength() > start) {
                println("remove " + variable.getName() + " " + offset);
                function.removeVariable(variable);
            }
        }
        function.getStackFrame().createVariable(args[2], start, type, SourceType.USER_DEFINED);
        println("created " + args[2] + " " + type.getPathName() + " size=" + type.getLength());
    }
}
