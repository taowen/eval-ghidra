import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.LocalVariableImpl;
import ghidra.program.model.pcode.DynamicEntry;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.HighSymbol;
import ghidra.program.model.symbol.SourceType;
import java.util.Iterator;

/** Bind an already inferred high-local type/name to its SSA hash instead of
 * a register shared by multiple values at one instruction (e.g. post-index
 * loads). Args: entryVA currentHighName newName. No signatures/type definitions.
 * Inspect raw/high P-code first; this is not a substitute for recovering types. */
public class RefineDynamicLocal extends GhidraScript {
    @Override public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 3) throw new IllegalArgumentException("entryVA currentHighName newName");
        Function function = getFunctionAt(toAddr(Long.decode(args[0])));
        if (function == null) throw new IllegalArgumentException("not a function entry");
        DecompInterface decompiler = new DecompInterface();
        try {
            decompiler.openProgram(currentProgram);
            HighFunction high = decompiler.decompileFunction(function, 60, monitor).getHighFunction();
            if (high == null) throw new IllegalStateException("decompilation failed");
            Iterator<HighSymbol> symbols = high.getLocalSymbolMap().getSymbols();
            HighSymbol selected = null;
            while (symbols.hasNext()) {
                HighSymbol symbol = symbols.next();
                if (!symbol.getName().equals(args[1])) continue;
                if (selected != null) throw new IllegalArgumentException("ambiguous local");
                selected = symbol;
            }
            if (selected == null || selected.isParameter() || selected.isGlobal())
                throw new IllegalArgumentException("missing local or not a local");
            DynamicEntry entry = DynamicEntry.build(selected.getHighVariable().getRepresentative());
            long first = entry.getPCAdress().subtract(function.getEntryPoint());
            if (first < 0 || first > Integer.MAX_VALUE)
                throw new IllegalArgumentException("dynamic anchor outside function");
            function.addLocalVariable(new LocalVariableImpl(args[2], (int)first,
                    selected.getDataType(), entry.getStorage(), currentProgram), SourceType.USER_DEFINED);
            println(args[2] + " type=" + selected.getDataType() + " " + entry.getStorage() + " first=" + first);
        } finally { decompiler.dispose(); }
    }
}
