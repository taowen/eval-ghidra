import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.DataType;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.listing.CodeUnit;

/** Bind command-state data using the imported canonical module only. */
public class RefineRenderCommandGlobals extends GhidraScript {
    private DataType canonical(String name, int size) {
        DataType result = null;
        var types = currentProgram.getDataTypeManager().getAllDataTypes();
        while (types.hasNext()) {
            var type = types.next();
            if (!type.getName().equals(name)) continue;
            if (result != null) throw new IllegalStateException("ambiguous " + name);
            result = type;
        }
        if (result == null || result.getLength() != size)
            throw new IllegalStateException("missing/wrong canonical " + name);
        return result;
    }
    private void bind(long va, String type, int size, String name) throws Exception {
        var address = toAddr(va);
        var dataType = canonical(type, size);
        clearListing(address, address.add(size - 1));
        createData(address, dataType);
        createLabel(address, name, true, SourceType.USER_DEFINED);
        println(name + " " + address + " " + dataType.getLength());
    }
    @Override public void run() throws Exception {
        // Adapt the expected image base if your program differs. The example
        // offsets below are pinned to one analyzed binary; re-check them first.
        if (currentProgram.getImageBase().getOffset() != 0x100000L)
            throw new IllegalStateException("unexpected image base; adjust the pinned offsets");
        long[] targets = {0x1a20d04L, 0x1a20d14L, 0x1a20d44L, 0x2381c70L, 0x1a20d4cL};
        for (int i = 0; i < targets.length; ++i) {
            long actual = currentProgram.getMemory().getLong(toAddr(0x27a78c8L + i * 8));
            if (actual != targets[i]) throw new IllegalStateException("wrong table slot " + i);
        }
        bind(0x290de28L, "TutorialRenderCommandHandle", 16, "g_stRenderCommandHandle");
        bind(0x27a78c8L, "TutorialSharedControlVtable", 40, "g_stRenderCommandControlVtable");
        // These legacy imported try comments contradict independently decoded
        // no-LSDA FDEs or the initializer's single cleanup region. Code/flow stays intact.
        long[] entries = {0x1a20a4cL,0x1a20c0cL,0x1a20d04L,0x1a20d14L,
                          0x1a20d44L,0x1a20d4cL,0x1a21994L,0x19ce57cL};
        for (long entry : entries) {
            var f = getFunctionAt(toAddr(entry));
            if (f == null) throw new IllegalStateException("missing entry");
            var units = currentProgram.getListing().getCodeUnits(f.getBody(), true);
            while (units.hasNext()) {
                var unit = units.next();
                for (int kind : new int[]{CodeUnit.PRE_COMMENT,CodeUnit.EOL_COMMENT,CodeUnit.POST_COMMENT}) {
                    String old = unit.getComment(kind);
                    if (old != null && (old.contains("try {") || old.contains("catch(type#")))
                        unit.setComment(kind, null);
                }
            }
        }
        println("table slots verified; canonical command globals bound; stale EH comments removed");
    }
}
