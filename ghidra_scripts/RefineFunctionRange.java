import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.listing.Function;

/** Apply an independently verified contiguous AArch64 function range.
 * Arguments: entry VA, exclusive end VA. No embedded addresses/signatures.
 * Refuses to steal any byte from another function or shrink the current body.
 */
public class RefineFunctionRange extends GhidraScript {
    @Override public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 2) throw new IllegalArgumentException("entryVA exclusiveEndVA");
        Address entry = toAddr(Long.decode(args[0]));
        Address end = toAddr(Long.decode(args[1]));
        Function f = getFunctionAt(entry);
        if (f == null || end.compareTo(entry) <= 0 || (end.subtract(entry) & 3) != 0)
            throw new IllegalArgumentException("invalid AArch64 function interval");
        AddressSet body = new AddressSet(entry, end.subtract(1));
        if (!body.contains(f.getBody())) throw new IllegalArgumentException("would shrink body");
        for (Address a = entry; a.compareTo(end) < 0; a = a.add(4)) {
            monitor.checkCancelled();
            Function owner = getFunctionContaining(a);
            if (owner != null && !owner.equals(f))
                throw new IllegalArgumentException("conflicting function " + owner.getName() + " at " + a);
        }
        for (Address a = entry; a.compareTo(end) < 0; a = a.add(4)) {
            if (getInstructionAt(a) == null && !disassemble(a))
                throw new IllegalArgumentException("cannot disassemble " + a);
        }
        println("before=" + f.getBody());
        f.setBody(body);
        println("after=" + f.getBody());
    }
}
