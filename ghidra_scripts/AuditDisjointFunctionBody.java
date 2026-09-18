import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.listing.Function;

/** Audit one independently verified function whose body has disjoint code
 * ranges around literal/data islands. Arguments: entryVA, then one or more
 * startVA/exclusiveEndVA pairs. */
public class AuditDisjointFunctionBody extends GhidraScript {
    @Override public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 3 || (args.length & 1) == 0)
            throw new IllegalArgumentException(
                    "entryVA startVA exclusiveEndVA [startVA exclusiveEndVA ...]");
        Address entry = toAddr(Long.decode(args[0]));
        Function function = getFunctionAt(entry);
        if (function == null) throw new IllegalArgumentException("missing function " + entry);
        AddressSet expected = new AddressSet();
        int instructions = 0;
        for (int i = 1; i < args.length; i += 2) {
            Address start = toAddr(Long.decode(args[i]));
            Address end = toAddr(Long.decode(args[i + 1]));
            if (end.compareTo(start) <= 0 || (end.subtract(start) & 3) != 0)
                throw new IllegalArgumentException("invalid AArch64 range");
            expected.add(start, end.subtract(1));
            for (Address address = start; address.compareTo(end) < 0;
                    address = address.add(4)) {
                monitor.checkCancelled();
                if (getInstructionAt(address) == null)
                    throw new Exception("missing instruction " + address);
                Function owner = getFunctionContaining(address);
                if (!function.equals(owner))
                    throw new Exception("wrong owner " + address + " -> " + owner);
                instructions++;
            }
        }
        if (!function.getBody().equals(expected))
            throw new Exception("body mismatch actual=" + function.getBody()
                    + " expected=" + expected);
        println("PASS " + function.getName() + " body=" + expected
                + " instructions=" + instructions);
    }
}
