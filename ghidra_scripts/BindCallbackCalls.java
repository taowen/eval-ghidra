import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.pcode.HighFunctionDBUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bind evidenced indirect call sites to their canonical imported callback
 * typedef. The header owns both the ABI and the RVA annotations; this script
 * never embeds an alternate prototype. Argument: canonical type-header path.
 */
public class BindCallbackCalls extends GhidraScript {
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 1) throw new IllegalArgumentException("type-header path");
        Matcher annotations = Pattern.compile(
            "@tutorial_callback_callsite\\s+(0x[0-9a-fA-F]+)\\s+(0x[0-9a-fA-F]+)\\s+(\\w+)")
            .matcher(Files.readString(Path.of(args[0])));
        while (annotations.find()) {
            Address entry = currentProgram.getImageBase().add(Long.decode(annotations.group(1)));
            Address site = currentProgram.getImageBase().add(Long.decode(annotations.group(2)));
            Function function = getFunctionAt(entry);
            Instruction instruction = getInstructionAt(site);
            if (function == null || !function.getBody().contains(site) || instruction == null
                    || !instruction.getFlowType().isCall() || !instruction.getFlowType().isComputed())
                throw new IllegalArgumentException("not an indirect call in the specified function: " + site);
            DataType type = currentProgram.getDataTypeManager().getDataType("/" + annotations.group(3));
            while (type instanceof TypeDef) type = ((TypeDef) type).getBaseDataType();
            if (type instanceof Pointer) type = ((Pointer) type).getDataType();
            if (!(type instanceof FunctionDefinition))
                throw new IllegalArgumentException("missing imported callback: " + annotations.group(3));
            HighFunctionDBUtil.writeOverride(function, site, (FunctionDefinition) type);
            println("bound " + site + " " + ((FunctionDefinition) type).getPrototypeString());
        }
    }
}
