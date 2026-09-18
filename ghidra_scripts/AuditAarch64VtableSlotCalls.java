import ghidra.app.script.GhidraScript;
import ghidra.program.model.mem.MemoryBlock;

/**
 * Read-only inventory of the canonical AArch64 virtual-call sequence:
 *
 *   ldr xN, [xN, #slot]
 *   blr xN
 *
 * The first argument is the byte slot, for example 0x18.  An optional second
 * argument restricts results to the canonical four-instruction sequence whose
 * X0 receiver was loaded from that object field, for example 0x2d8 for
 * RenderMainOwner::hardware_policy.  Without it this remains an instruction
 * inventory, not receiver-provenance proof.
 */
public class AuditAarch64VtableSlotCalls extends GhidraScript {
    @Override public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("vtable byte slot [receiver field] required");
        }
        long slot = Long.decode(args[0]);
        long receiverField = args.length == 2 ? Long.decode(args[1]) : -1;
        if ((slot & 7) != 0 || slot < 0 || slot > 0x7ff8) {
            throw new IllegalArgumentException("slot must fit an unsigned scaled LDR X offset");
        }
        String wanted = "0x" + Long.toHexString(slot);
        int wantedImmediate = (int)(slot >>> 3);
        int candidates = 0;
        int calls = 0;
        var memory = currentProgram.getMemory();
        for (MemoryBlock block : memory.getBlocks()) {
            if (!block.isExecute() || !block.isInitialized()) continue;
            var address = block.getStart();
            var last = block.getEnd().subtract(4);
            while (address.compareTo(last) <= 0) {
                monitor.checkCancelled();
                int word = memory.getInt(address);
                if ((word & 0xffc00000) == 0xf9400000 &&
                        ((word >>> 10) & 0xfff) == wantedImmediate) {
                    int target = word & 31;
                    int base = (word >>> 5) & 31;
                    if (target == base && target != 31) {
                        candidates++;
                        int next = memory.getInt(address.add(4));
                        if ((next & 0xfffffc1f) == 0xd63f0000 &&
                                ((next >>> 5) & 31) == target) {
                            if (receiverField >= 0) {
                                boolean provenanceMatches =
                                        address.compareTo(block.getStart().add(8)) >= 0;
                                int vptrLoad = provenanceMatches
                                        ? memory.getInt(address.subtract(4)) : 0;
                                int receiverLoad = provenanceMatches
                                        ? memory.getInt(address.subtract(8)) : 0;
                                // ldr x<target>, [x0, #0]
                                provenanceMatches &=
                                        vptrLoad == (0xf9400000 | target);
                                // ldr x0, [xN, #receiverField]
                                provenanceMatches &= (receiverField & 7) == 0 &&
                                        receiverField <= 0x7ff8 &&
                                        (receiverLoad & 0xfffffc1f) ==
                                        (0xf9400000 | ((int)(receiverField >>> 3) << 10));
                                if (!provenanceMatches) {
                                    address = address.add(4);
                                    continue;
                                }
                            }
                            calls++;
                            var function = getFunctionContaining(address);
                            var instruction = getInstructionAt(address);
                            var nextInstruction = getInstructionAt(address.add(4));
                            println(address + " " +
                                    (function == null ? "<no-function>" : function.getName()) +
                                    " | " + instruction + " | " + nextInstruction);
                        }
                    }
                }
                address = address.add(4);
            }
        }
        println("SUMMARY slot=" + wanted +
                (receiverField < 0 ? "" : " receiver_field=0x" +
                        Long.toHexString(receiverField)) +
                " candidates=" + candidates + " calls=" + calls);
    }
}
