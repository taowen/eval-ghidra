import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Parameter;

/** Read-only inspection of all functions using a named calling convention. */
public class InspectFunctionAbi extends GhidraScript {
    @Override public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 1) throw new IllegalArgumentException("callingConvention");
        println("default=" + currentProgram.getCompilerSpec()
                .getDefaultCallingConvention().getName());
        int count = 0;
        FunctionIterator functions = currentProgram.getFunctionManager().getFunctions(true);
        while (functions.hasNext()) {
            Function f = functions.next();
            if (!args[0].equals(f.getCallingConventionName())) continue;
            count++;
            println("function=" + f.getName() + " entry=" + f.getEntryPoint()
                    + " formal_return=" + f.getReturn().getFormalDataType().getName()
                    + " size=" + f.getReturn().getFormalDataType().getLength()
                    + " indirect=" + f.getReturn().isForcedIndirect()
                    + " custom=" + f.hasCustomVariableStorage());
            for (Parameter p : f.getParameters()) {
                println("  parameter=" + p.getName() + " type=" + p.getDataType()
                        + " storage=" + p.getVariableStorage() + " auto=" + p.isAutoParameter());
            }
        }
        println("matching_functions=" + count);
    }
}
