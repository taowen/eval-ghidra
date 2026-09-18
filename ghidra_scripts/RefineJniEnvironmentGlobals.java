import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.CodeUnit;
import ghidra.program.model.symbol.SourceType;

/** Bind JNI globals to the canonical environment/emutls headers. */
public class RefineJniEnvironmentGlobals extends GhidraScript {
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
    private void bind(long va, DataType type, String name) throws Exception {
        var address = toAddr(va);
        clearListing(address, address.add(type.getLength() - 1));
        createData(address, type);
        createLabel(address, name, true, SourceType.USER_DEFINED);
        println(name + " " + address + " " + type.getLength());
    }
    @Override public void run() throws Exception {
        // Adapt the expected image base if your program differs. The example
        // offsets below are pinned to one analyzed binary; re-check them first.
        if (currentProgram.getImageBase().getOffset() != 0x100000L)
            throw new IllegalStateException("unexpected image base; adjust the pinned offsets");
        // A legacy category copy has identical fields but blocks unambiguous
        // lookup. The live emutls allocator and exception descriptor already
        // use the root canonical type. Merge only after equivalence is proved.
        var dtm = currentProgram.getDataTypeManager();
        var rootControl = dtm.getDataType("/TutorialEmutlsControl");
        var legacyControl = dtm.getDataType("/tutorial-types/runtime.h/TutorialEmutlsControl");
        if (legacyControl != null) {
            if (rootControl == null || rootControl.getLength() != 32 ||
                    !rootControl.isEquivalent(legacyControl))
                throw new IllegalStateException("non-equivalent emutls duplicate");
            dtm.replaceDataType(legacyControl, rootControl, true);
            println("merged equivalent legacy emutls category into live canonical type");
        }
        var pointer = canonical("TutorialJavaVmPointer", 8);
        var control = canonical("TutorialEmutlsControl", 32);
        long[] descriptors = {0x28e50c8L, 0x28e50e8L};
        long[][] words = {{24,8,0,0}, {1,1,0,0}};
        for (int i=0; i<descriptors.length; ++i)
            for (int j=0; j<4; ++j)
                if (currentProgram.getMemory().getLong(toAddr(descriptors[i]+j*8)) != words[i][j])
                    throw new IllegalStateException("unexpected JNI emutls descriptor");
        bind(0x29d3d48L, pointer, "g_pJniJavaVm");
        bind(0x29d35f0L, pointer, "g_pPrimaryJavaVm");
        bind(0x29d3d40L, ghidra.program.model.data.UnsignedCharDataType.dataType,
                "g_bJniVmInitialized");
        bind(descriptors[0], control, "g_stJniEnvironmentControl");
        bind(descriptors[1], control, "g_stJniEnvironmentGuardControl");
        // Imported legacy try/catch annotations use impossible one-megabyte
        // regions. Independent FDE/LSDA now supplies the real ranges.
        for (long entry : new long[]{0x2710b60L,0x2401f88L,0x23d1dc4L,
                0x2401ce8L,0x2401d1cL,0x2401e3cL}) {
            var f = getFunctionAt(toAddr(entry));
            if (f == null) throw new IllegalStateException("missing true entry");
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
        setEOLComment(toAddr(0x2710b64L), "CPU LSE capability page: byte29d8a70/ELF28d8a70");
        setEOLComment(toAddr(0x2710b70L), "Atomic byte CAS acquire/release; W0 receives OLD byte");
        setEOLComment(toAddr(0x2710b7cL), "Acquire-exclusive byte load, mismatch returns without store");
        setEOLComment(toAddr(0x2710b88L), "Release-exclusive byte store; retry only on failed reservation");
        println("JNI globals bound; descriptor identities checked; stale EH comments removed");
    }
}
