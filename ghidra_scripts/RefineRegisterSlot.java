import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.DataType;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.LocalVariableImpl;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.listing.VariableStorage;
import ghidra.program.model.pcode.Varnode;
import ghidra.program.model.symbol.SourceType;
import java.util.Iterator;

/** Anchor a local to an independently checked instruction and register.
 * Arguments: entryVA register firstUseVA importedType name [obsoleteNamesCsv].
 * importedType accepts an exact /category/type path to disambiguate names.
 * Use %20 for spaces in importedType: the plugin splits script arguments on
 * whitespace before invoking this script. No embedded function facts or type
 * definitions. */
public class RefineRegisterSlot extends GhidraScript {
    @Override public void run() throws Exception {
        String[] a = getScriptArgs();
        if (a.length < 5 || a.length > 6) throw new IllegalArgumentException(
                "entryVA register firstUseVA importedType name [obsoleteNamesCsv]");
        Function f = getFunctionAt(toAddr(Long.decode(a[0])));
        if (f == null) throw new IllegalArgumentException("not an entry");
        Register reg = currentProgram.getLanguage().getRegister(a[1]);
        if (reg == null) throw new IllegalArgumentException("unknown register");
        long offset = toAddr(Long.decode(a[2])).subtract(f.getEntryPoint());
        if (offset < 0 || offset > Integer.MAX_VALUE ||
                !f.getBody().contains(toAddr(Long.decode(a[2]))))
            throw new IllegalArgumentException("first use outside function");
        String requestedType = a[3].replace("%20", " ");
        DataType type = null;
        Iterator<DataType> types = currentProgram.getDataTypeManager().getAllDataTypes();
        while (types.hasNext()) {
            DataType t = types.next();
            if (!(requestedType.startsWith("/") ? t.getPathName().equals(requestedType)
                    : t.getName().equals(requestedType))) continue;
            if (type != null) throw new IllegalArgumentException("ambiguous type");
            type = t;
        }
        if (type == null || type.getLength() <= 0 || type.getLength() > reg.getNumBytes())
            throw new IllegalArgumentException("missing type or wrong register width");
        java.util.Set<String> obsolete = new java.util.HashSet<>();
        obsolete.add(a[4]);
        if (a.length == 6) for (String n : a[5].split(",")) obsolete.add(n);
        for (Variable v : f.getLocalVariables()) if (obsolete.contains(v.getName())) {
            println("remove " + v.getName()); f.removeVariable(v);
        }
        VariableStorage storage = new VariableStorage(currentProgram,
                new Varnode(reg.getAddress(), type.getLength()));
        f.addLocalVariable(new LocalVariableImpl(a[4], (int)offset, type, storage,
                currentProgram), SourceType.USER_DEFINED);
        println("added " + a[4] + " " + type.getName() + " " + reg.getName()
                + " firstUse=" + offset);
    }
}
