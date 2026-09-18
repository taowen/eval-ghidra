import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.DataType;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.listing.CodeUnit;

/** Bind state-control data using the imported canonical module only. */
public class RefineStateLifecycleGlobals extends GhidraScript {
    private DataType canonical(String name, int size) {
        // Existing outer/inner globals both use the root canonical handle.
        // The legacy imported-category copy has a different control type;
        // never select that copy by enumeration order.
        if (name.equals("TutorialSharedHandle")) {
            var type = currentProgram.getDataTypeManager().getDataType("/TutorialSharedHandle");
            if (type == null || type.getLength() != size)
                throw new IllegalStateException("missing root canonical handle");
            return type;
        }
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
        long[][] tables = {
            {0x27a6b58L,0x1a0f344L,0x1a0f354L,0x1a0f384L,0x2381c70L,0x1a0f390L},
            {0x27a6b90L,0x1a0f394L,0x1a0f3a4L,0x1a0f3d4L,0x2381c70L,0x1a0f3d8L},
            {0x27a6bc8L,0x1a0f3dcL,0x1a0f3ecL,0x1a0f41cL,0x2381c70L,0x1a0f460L}
        };
        for (long[] table : tables) for (int i = 1; i < table.length; ++i)
            if (currentProgram.getMemory().getLong(toAddr(table[0] + (i - 1) * 8)) != table[i])
                throw new IllegalStateException("wrong table target");
        bind(0x27a6b58L,"TutorialSharedControlVtable",40,"g_stStateFlagControlVtable");
        bind(0x27a6b90L,"TutorialSharedControlVtable",40,"g_stInnerStateControlVtable");
        bind(0x27a6bc8L,"TutorialSharedControlVtable",40,"g_stStateBufferPairControlVtable");
        bind(0x290ddd0L,"TutorialSharedHandle",16,"g_stStateFlagHandle");
        bind(0x290dde0L,"TutorialSharedHandle",16,"g_stInnerStateHandle");
        bind(0x290ddf0L,"TutorialSharedHandle",16,"g_stStateBufferPairHandle");
        canonical("TutorialStateFlagNode",40);
        canonical("TutorialStateFlagControl",64);
        canonical("TutorialStateBufferPairControl",72);
        // All these complete FDEs independently have no LSDA. Remove only
        // legacy comment text; do not alter actual code, flow or function bodies.
        long[] entries = {0x1a0f344L,0x1a0f354L,0x1a0f384L,0x1a0f390L,
            0x1a0f394L,0x1a0f3a4L,0x1a0f3dcL,0x1a0f3ecL,0x1a0f41cL,
            0x1a0f460L,0x19e6998L,0x1a09c2cL,0x1a0f464L};
        for (long entry : entries) {
            var f = getFunctionAt(toAddr(entry));
            if (f == null) throw new IllegalStateException("missing entry");
            var units = currentProgram.getListing().getCodeUnits(f.getBody(), true);
            while (units.hasNext()) {
                var unit = units.next();
                for (int kind : new int[]{CodeUnit.PRE_COMMENT,CodeUnit.EOL_COMMENT,CodeUnit.POST_COMMENT}) {
                    String old = unit.getComment(kind);
                    if (old != null && (old.contains("try {") || old.contains("catch(")))
                        unit.setComment(kind, null);
                }
            }
        }
        println("table slots verified; canonical state globals bound; stale EH comments removed");
    }
}
