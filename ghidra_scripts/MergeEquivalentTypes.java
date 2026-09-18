import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeManager;

/** Merge an importer-created duplicate only when Ghidra proves equivalence.
 * Args: duplicatePath canonicalPath [duplicatePath canonicalPath ...].
 * Apply leaf types before parents. This script defines no layouts and refuses
 * structurally different types; their canonical headers must be fixed first.
 */
public class MergeEquivalentTypes extends GhidraScript {
    @Override public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length == 0 || args.length % 2 != 0)
            throw new IllegalArgumentException("duplicatePath canonicalPath pairs required");
        DataTypeManager manager = currentProgram.getDataTypeManager();
        for (int i = 0; i < args.length; i += 2) {
            DataType duplicate = manager.getDataType(args[i]);
            DataType canonical = manager.getDataType(args[i + 1]);
            if (duplicate == null || canonical == null || duplicate == canonical)
                throw new IllegalArgumentException("missing or identical types: " + args[i]);
            if (duplicate.getLength() != canonical.getLength() ||
                    !duplicate.isEquivalent(canonical))
                throw new IllegalArgumentException("not equivalent: " + args[i]);
            int bytes = canonical.getLength();
            manager.replaceDataType(duplicate, canonical, true);
            println("merged=" + args[i] + " canonical=" + args[i + 1] + " bytes=" + bytes);
        }
    }
}
