import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.pcode.*;
import java.util.Iterator;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.TreeSet;

/** Read-only raw/high p-code evidence at an instruction. */
public class InspectHighAt extends GhidraScript {
    private String at(PcodeOp op) {
        if (op == null) return "input";
        return op.getMnemonic() + "@" + op.getSeqnum().getTarget()
                + ":" + op.getSeqnum().getTime();
    }

    private String block(PcodeOp op) {
        if (op == null || op.getParent() == null) return "entry";
        PcodeBlockBasic b = op.getParent();
        StringBuilder result = new StringBuilder("B").append(b.getIndex())
                .append('[').append(b.getStart()).append(',').append(b.getStop()).append(']')
                .append(" in=");
        for (int i = 0; i < b.getInSize(); ++i) {
            if (i != 0) result.append(',');
            result.append('B').append(b.getIn(i).getIndex());
        }
        result.append(" out=");
        for (int i = 0; i < b.getOutSize(); ++i) {
            if (i != 0) result.append(',');
            result.append('B').append(b.getOut(i).getIndex());
        }
        return result.toString();
    }

    private void printInstance(Varnode v) {
        println("    instance=" + v + " pc=" + v.getPCAddress()
                + " merge=" + v.getMergeGroup() + " def=" + at(v.getDef())
                + " block=" + block(v.getDef()));
        Iterator<PcodeOp> uses = v.getDescendants();
        int count = 0;
        while (uses.hasNext()) {
            PcodeOp use = uses.next();
            StringBuilder edges = new StringBuilder();
            for (int i = 0; i < use.getNumInputs(); ++i) {
                if (use.getInput(i) != v) continue;
                if (edges.length() != 0) edges.append(',');
                edges.append(i);
            }
            println("      use=" + at(use) + " input=" + edges
                    + " block=" + block(use));
            ++count;
        }
        println("      use_count=" + count);
    }

    @Override public void run() throws Exception {
        String[] a = getScriptArgs();
        if (a.length != 2 && a.length != 4) throw new IllegalArgumentException(
                "entryVA instructionVA [opcode space:offset:size]");
        Function f = getFunctionAt(toAddr(Long.decode(a[0])));
        Address site = toAddr(Long.decode(a[1]));
        String selectedOpcode = a.length == 4 ? a[2] : null;
        String[] selectedStorage = a.length == 4 ? a[3].split(":") : null;
        if (selectedStorage != null && selectedStorage.length != 3)
            throw new IllegalArgumentException("bad selected storage");
        if (f == null || !f.getBody().contains(site) || getInstructionAt(site) == null)
            throw new IllegalArgumentException("missing entry/instruction or site outside body");
        for (PcodeOp op : getInstructionAt(site).getPcode()) println("raw " + op);
        DecompInterface decompiler = new DecompInterface();
        try {
            // Match RefineHighLocal's options: different simplification
            // settings can change unique-space varnodes between inspection
            // and the immediately following refinement.
            decompiler.setOptions(new DecompileOptions());
            decompiler.openProgram(currentProgram);
            HighFunction hf = decompiler.decompileFunction(f, 60, monitor).getHighFunction();
            if (hf == null) throw new IllegalStateException("decompile failed");
            Set<HighVariable> expanded = Collections.newSetFromMap(new IdentityHashMap<>());
            Iterator<PcodeOpAST> ops = hf.getPcodeOps(site);
            while (ops.hasNext()) {
                PcodeOpAST op = ops.next();
                VarnodeAST output = (VarnodeAST)op.getOutput();
                if (selectedOpcode != null) {
                    if (!op.getMnemonic().equals(selectedOpcode) || output == null ||
                            !output.getAddress().getAddressSpace().getName().equals(selectedStorage[0]) ||
                            output.getOffset() != Long.decode(selectedStorage[1]) ||
                            output.getSize() != Integer.decode(selectedStorage[2])) continue;
                }
                println("high " + op);
                for (int i = 0; i < op.getNumInputs(); ++i) {
                    Varnode input = op.getInput(i);
                    if (input == null || input.getHigh() == null) continue;
                    HighVariable h = input.getHigh();
                    println("  input[" + i + "] name=" + h.getName()
                            + " class=" + h.getClass().getSimpleName()
                            + " type=" + h.getDataType()
                            + " varnode=" + input
                            + " merge=" + input.getMergeGroup());
                }
                if (output == null || output.getHigh() == null) continue;
                HighVariable h = output.getHigh();
                println("  name=" + h.getName() + " class=" + h.getClass().getSimpleName()
                        + " type=" + h.getDataType()
                        + " merge=" + output.getMergeGroup());
                if (!(h instanceof HighLocal)) {
                    println("  instance_count=" + h.getInstances().length
                            + " detail_skipped=nonlocal");
                    continue;
                }
                if (!expanded.add(h)) {
                    println("  detail_skipped=already_expanded");
                    continue;
                }
                Set<Short> groups = new TreeSet<>();
                Map<Short, Set<Integer>> groupBlocks = new TreeMap<>();
                for (Varnode v : h.getInstances()) {
                    groups.add(v.getMergeGroup());
                    Set<Integer> blocks = groupBlocks.computeIfAbsent(v.getMergeGroup(),
                            ignored -> new TreeSet<>());
                    if (v.getDef() != null && v.getDef().getParent() != null)
                        blocks.add(v.getDef().getParent().getIndex());
                    Iterator<PcodeOp> uses = v.getDescendants();
                    while (uses.hasNext()) {
                        PcodeOp use = uses.next();
                        if (use.getParent() != null) blocks.add(use.getParent().getIndex());
                    }
                    printInstance(v);
                }
                println("  merge_groups=" + groups + " group_blocks=" + groupBlocks
                        + " split_eligible=" + (groups.size() > 1)
                        + " same_group_multi_block="
                        + (groups.size() == 1 && groupBlocks.values().iterator().next().size() > 1));
            }
        } finally { decompiler.dispose(); }
    }
}
