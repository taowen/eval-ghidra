import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.program.model.data.Array;
import ghidra.program.model.data.Composite;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.TypeDef;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.HighSymbol;
import ghidra.program.model.pcode.Varnode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/** Read-only comparison of inferred stack aggregates with committed locals.
 * Args: functionVA [functionVA ...], or --name-regex fullNameRegex.
 * Addresses are Ghidra VAs. Selects only true entries; never changes types,
 * variables, signatures, function ranges, or decompiler options.
 * REVIEW findings are not safe-to-apply fixes: lifetime reuse, aliasing and
 * callee ABI still need Listing review, possibly a union in the owning header.
 */
public class AuditInferredStackTypes extends GhidraScript {
    private DataType baseType(DataType type) {
        while (type instanceof TypeDef) type = ((TypeDef) type).getBaseDataType();
        return type;
    }

    private String span(long start, int size) {
        return "[" + start + "," + (start + size) + ")";
    }

    @Override public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length == 0) throw new IllegalArgumentException(
                "functionVA [functionVA ...] OR --name-regex fullNameRegex");
        List<Function> selected = new ArrayList<>();
        if (args[0].equals("--name-regex")) {
            if (args.length != 2) throw new IllegalArgumentException("one full-name regex required");
            Pattern pattern = Pattern.compile(args[1]);
            Iterator<Function> functions = currentProgram.getFunctionManager().getFunctions(true);
            while (functions.hasNext()) {
                Function function = functions.next();
                if (pattern.matcher(function.getName()).matches()) selected.add(function);
            }
        } else {
            for (String arg : args) {
                Function function = getFunctionAt(toAddr(Long.decode(arg)));
                if (function == null) throw new IllegalArgumentException("not a function entry: " + arg);
                if (!selected.contains(function)) selected.add(function);
            }
        }
        if (selected.isEmpty()) throw new IllegalArgumentException("no matching functions");
        println("READ_ONLY stack aggregate audit: selected=" + selected.size());
        println("REVIEW is a mismatch, not proof of a correct inferred type or safe replacement.");
        int scanned = 0, aggregates = 0, matched = 0, review = 0, unsupported = 0, failed = 0;
        DecompInterface decompiler = new DecompInterface();
        try {
            if (!decompiler.openProgram(currentProgram)) throw new IllegalStateException("cannot open program");
            for (Function function : selected) {
                monitor.checkCancelled();
                println("FUNCTION " + function.getEntryPoint() + " " + function.getName());
                HighFunction high = decompiler.decompileFunction(function, 60, monitor).getHighFunction();
                scanned++;
                if (high == null) { println("  FAILED decompilation"); failed++; continue; }
                Iterator<HighSymbol> symbols = high.getLocalSymbolMap().getSymbols();
                while (symbols.hasNext()) {
                    HighSymbol symbol = symbols.next();
                    if (symbol.isParameter() || symbol.isGlobal()) continue;
                    DataType type = symbol.getDataType(), base = baseType(type);
                    if (!(base instanceof Composite) && !(base instanceof Array)) continue;
                    Varnode[] storage = symbol.getStorage().getVarnodes();
                    boolean hasStack = false;
                    for (Varnode node : storage) hasStack |= node.getAddress().isStackAddress();
                    if (!hasStack) continue;
                    aggregates++;
                    if (storage.length != 1 || type.getLength() <= 0) {
                        println("  UNSUPPORTED " + symbol.getName() + " storage=" + symbol.getStorage());
                        unsupported++;
                        continue;
                    }
                    long start = storage[0].getOffset(), end = start + type.getLength();
                    if (start >= 0 || end > 0) {
                        println("  UNSUPPORTED nonlocal span " + symbol.getName());
                        unsupported++;
                        continue;
                    }
                    List<Variable> overlapping = new ArrayList<>();
                    for (Variable local : function.getLocalVariables()) {
                        if (!local.isStackVariable()) continue;
                        long localStart = local.getStackOffset();
                        if (localStart < end && localStart + local.getLength() > start) overlapping.add(local);
                    }
                    boolean exact = overlapping.size() == 1;
                    if (exact) {
                        Variable local = overlapping.get(0);
                        exact = local.getStackOffset() == start && local.getLength() == type.getLength()
                                && local.getDataType().isEquivalent(type);
                    }
                    println("  " + (exact ? "MATCH" : "REVIEW") + " " + symbol.getName()
                            + " inferred=" + type.getPathName() + " span=" + span(start, type.getLength())
                            + " highStorageBytes=" + symbol.getStorage().size());
                    if (exact) matched++; else review++;
                    if (!exact) for (Variable local : overlapping) println("    DB " + local.getName()
                            + " type=" + local.getDataType().getPathName()
                            + " span=" + span(local.getStackOffset(), local.getLength())
                            + " source=" + local.getSource());
                    if (!exact && overlapping.isEmpty()) println("    DB no overlapping local");
                }
            }
        } finally { decompiler.dispose(); }
        println("SUMMARY selected=" + selected.size() + " scanned=" + scanned + " aggregates=" + aggregates
                + " matched=" + matched + " review=" + review + " unsupported=" + unsupported + " failed=" + failed);
        println("Scope: inferred stack aggregates only. MATCH does not prove ABI, lifetime, function range or readiness.");
        if (failed != 0) throw new IllegalStateException("incomplete audit: decompilation failed");
    }
}
