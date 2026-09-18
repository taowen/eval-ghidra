import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Variable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Remove explicitly identified stale local type/name locks, never parameters.
 * Args: function VA, comma-separated exact local names. No embedded types. */
public class RemoveFunctionLocals extends GhidraScript {
    @Override public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 2) throw new IllegalArgumentException("functionVA namesCSV");
        Function function = getFunctionAt(toAddr(Long.decode(args[0])));
        if (function == null) throw new IllegalArgumentException("not function entry");
        Set<String> names = new HashSet<>(Arrays.asList(args[1].split(",")));
        for (Variable variable : function.getLocalVariables()) {
            if (names.remove(variable.getName())) {
                println("remove " + variable.getName() + " " + variable.getVariableStorage());
                function.removeVariable(variable);
            }
        }
        if (!names.isEmpty()) throw new IllegalArgumentException("missing locals: " + names);
    }
}
