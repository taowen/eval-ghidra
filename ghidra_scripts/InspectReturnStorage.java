import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.lang.PrototypeModel;
import ghidra.program.model.lang.BasicCompilerSpec;
import ghidra.program.database.SpecExtension;
import ghidra.program.model.listing.VariableStorage;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;

/** Read-only ABI diagnostic. Arguments are imported return and input type names.
 * No signatures, type definitions, compiler specifications or program functions
 * are changed. The input is a pointer to the named imported type. */
public class InspectReturnStorage extends GhidraScript {
    private DataType resolve(String name) {
        DataType found = null;
        Iterator<DataType> types = currentProgram.getDataTypeManager().getAllDataTypes();
        while (types.hasNext()) {
            DataType candidate = types.next();
            if (!candidate.getName().equals(name)) continue;
            if (found != null) throw new IllegalArgumentException("ambiguous type " + name);
            found = candidate;
        }
        if (found == null) throw new IllegalArgumentException("missing type " + name);
        return found;
    }
    @Override public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException("returnType inputPointeeType [candidatePrototypeXml]");
        DataType result = resolve(args[0]);
        DataType input = new PointerDataType(resolve(args[1]), currentProgram.getDataTypeManager());
        println("return=" + result.getName() + " size=" + result.getLength());
        println("input=" + input.getName());
        PrototypeModel model = currentProgram.getCompilerSpec().getDefaultCallingConvention();
        println("default_convention=" + model.getName());
        printStorage(model, result, input);
        if (args.length == 3) {
            String document = Files.readString(Paths.get(args[2]));
            SpecExtension.DocInfo info = new SpecExtension(currentProgram)
                    .testExtensionDocument(document);
            // Parse into a detached compiler-spec copy. Never install this
            // extension, assign it to a function, or modify program options.
            BasicCompilerSpec copy = new BasicCompilerSpec(
                    (BasicCompilerSpec) currentProgram.getCompilerSpec());
            PrototypeModel candidate = (PrototypeModel) SpecExtension.parseExtension(
                    info.getOptionName(), document, copy, false);
            println("detached_candidate=" + candidate.getName());
            printStorage(candidate, result, input);
            println("program_default_after=" + currentProgram.getCompilerSpec()
                    .getDefaultCallingConvention().getName());
            println("candidate_installed=" + (currentProgram.getCompilerSpec()
                    .getCallingConvention(candidate.getName()) != null));
        }
    }
    private void printStorage(PrototypeModel model, DataType result, DataType input) {
        VariableStorage[] storage = model.getStorageLocations(currentProgram,
                new DataType[] {result, input}, true);
        for (int i = 0; i < storage.length; i++) {
            println("ordinal=" + i + " storage=" + storage[i]
                    + " forced_indirect=" + storage[i].isForcedIndirect()
                    + " auto=" + storage[i].isAutoStorage());
        }
    }
}
