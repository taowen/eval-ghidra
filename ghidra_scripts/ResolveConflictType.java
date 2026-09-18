// @category Tutorial
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeManager;

/** Migrate every reference from one explicitly named .conflict type to the
 * verified canonical root type. Args: duplicateName canonicalName.
 * This is cleanup for parser-created recursive duplicates; it never guesses
 * by leaf name and refuses size mismatches.
 */
public class ResolveConflictType extends GhidraScript {
    @Override public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 2)
            throw new IllegalArgumentException("duplicateName canonicalName");
        DataTypeManager manager = currentProgram.getDataTypeManager();
        DataType duplicate = manager.getDataType("/" + args[0]);
        DataType canonical = manager.getDataType("/" + args[1]);
        if (duplicate == null || canonical == null)
            throw new IllegalArgumentException("missing duplicate or canonical type");
        if (duplicate == canonical)
            throw new IllegalArgumentException("duplicate equals canonical");
        if (duplicate.getLength() != canonical.getLength())
            throw new IllegalArgumentException("size mismatch "
                    + duplicate.getLength() + " != " + canonical.getLength());
        println("duplicate=" + duplicate.getPathName() + " size=" + duplicate.getLength());
        println("canonical=" + canonical.getPathName() + " size=" + canonical.getLength());
        DataType migrated = manager.replaceDataType(duplicate, canonical, true);
        if (migrated == null)
            throw new IllegalStateException("replaceDataType returned null");
        println("migrated=" + migrated.getPathName());
    }
}
