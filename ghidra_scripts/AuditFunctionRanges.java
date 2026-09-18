import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;

/** Read-only check of externally evidenced contiguous AArch64 FDE ranges.
 * Arguments: entryVA exclusiveEndVA [entryVA exclusiveEndVA ...].
 * Run after signature imports/auto-analysis: noreturn propagation can remove
 * exception landing pads from a previously restored function body.
 * Passing proves exact body/instruction coverage, not semantic readiness.
 */
public class AuditFunctionRanges extends GhidraScript {
    @Override public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length == 0 || (args.length & 1) != 0)
            throw new IllegalArgumentException("entryVA exclusiveEndVA pairs required");
        int failures = 0;
        for (int i = 0; i < args.length; i += 2) {
            Address entry = toAddr(Long.decode(args[i]));
            Address end = toAddr(Long.decode(args[i + 1]));
            if ((entry.getOffset() & 3) != 0 || end.compareTo(entry) <= 0
                    || (end.subtract(entry) & 3) != 0)
                throw new IllegalArgumentException("invalid AArch64 interval");
            AddressSet expected = new AddressSet(entry, end.subtract(1));
            Function f = getFunctionAt(entry);
            boolean valid = f != null && expected.equals(f.getBody());
            int instructions = 0;
            for (Address a = entry; a.compareTo(end) < 0; a = a.add(4)) {
                monitor.checkCancelled();
                Instruction insn = getInstructionAt(a);
                Function owner = getFunctionContaining(a);
                if (insn == null || insn.getLength() != 4 || f == null
                        || !f.equals(owner)) {
                    valid = false;
                    println("MISMATCH " + a + " instruction=" + insn
                            + " owner=" + (owner == null ? "none" : owner.getName()));
                } else {
                    instructions++;
                }
            }
            println((valid ? "PASS " : "FAIL ") + entry + ".." + end
                    + " instructions=" + instructions + "/" + end.subtract(entry) / 4
                    + " body=" + (f == null ? "none" : f.getBody()));
            if (!valid) failures++;
        }
        if (failures != 0) throw new Exception("function range failures=" + failures);
        println("PASS exact ranges=" + args.length / 2 + "; semantic readiness not assessed");
    }
}
