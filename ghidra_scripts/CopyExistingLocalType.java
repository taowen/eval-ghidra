import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.symbol.SourceType;
import java.util.HashMap;
import java.util.Map;

/** Copy an already imported local type to exact existing local symbols.
 * Args: entryVA sourceLocal targetLocalsCsv. Keeps each target's storage and
 * first-use offset; does not resolve a high variable by register alone. */
public class CopyExistingLocalType extends GhidraScript {
    @Override public void run() throws Exception {
        String[] a = getScriptArgs();
        if (a.length != 3) throw new IllegalArgumentException("entryVA sourceLocal targetLocalsCsv");
        Function f = getFunctionAt(toAddr(Long.decode(a[0])));
        if (f == null) throw new IllegalArgumentException("not a function entry");
        Map<String, Variable> locals = new HashMap<>();
        for (Variable v : f.getLocalVariables()) locals.put(v.getName(), v);
        Variable source = locals.get(a[1]);
        if (source == null) throw new IllegalArgumentException("missing source local");
        for (String name : a[2].split(",")) {
            Variable target = locals.get(name);
            if (target == null) throw new IllegalArgumentException("missing target " + name);
            if (source.getDataType().getLength() != target.getLength())
                throw new IllegalArgumentException("width mismatch " + name);
        }
        for (String name : a[2].split(",")) {
            Variable target = locals.get(name);
            println(name + " storage=" + target.getVariableStorage() + " firstUse="
                + target.getFirstUseOffset() + " old=" + target.getDataType());
            target.setDataType(source.getDataType(), SourceType.USER_DEFINED);
            println("new=" + target.getDataType());
        }
    }
}
