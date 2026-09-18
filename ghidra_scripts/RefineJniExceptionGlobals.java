import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.CodeUnit;
import ghidra.program.model.symbol.SourceType;

/** Bind independently checked JNI exception relocations to imported types. */
public class RefineJniExceptionGlobals extends GhidraScript {
    private DataType canonical(String name, int size) {
        DataType selected = null;
        var types = currentProgram.getDataTypeManager().getAllDataTypes();
        while (types.hasNext()) {
            var type = types.next();
            if (!type.getName().equals(name)) continue;
            if (selected != null) throw new IllegalStateException("ambiguous " + name);
            selected = type;
        }
        if (selected == null || selected.getLength() != size)
            throw new IllegalStateException("missing/wrong type " + name);
        return selected;
    }
    private void bind(long va, String typeName, int size, String name) throws Exception {
        var type = canonical(typeName, size);
        var a = toAddr(va);
        clearListing(a, a.add(size - 1));
        createData(a, type);
        createLabel(a, name, true, SourceType.USER_DEFINED);
    }
    @Override public void run() throws Exception {
        // Adapt the expected image base if your program differs. The example
        // offsets below are pinned to one analyzed binary; re-check them first.
        if (currentProgram.getImageBase().getOffset() != 0x100000L)
            throw new IllegalStateException("unexpected image base; adjust the pinned offsets");
        long[][] pointers = {{0x282ed10L,0x28a3f88L},{0x282ed18L,0x9bd158L},
            {0x282ed20L,0x28a3c58L},{0x282edf0L,0x26f41ccL},
            {0x282edf8L,0x2406f30L},{0x282ee00L,0x26f4264L}};
        for (long[] pair : pointers)
            if (currentProgram.getMemory().getLong(toAddr(pair[0])) != pair[1])
                throw new IllegalStateException("relocation mismatch");
        bind(0x282ed10L,"TutorialJniInitializationExceptionTypeInfo",24,"g_stJniInitializationTypeInfo");
        bind(0x282edf0L,"TutorialJniInitializationExceptionVtable",24,"g_pJniInitializationVtablePrefix");
        var f = getFunctionAt(toAddr(0x2401e10L));
        if (f == null) throw new IllegalStateException("missing constructor entry");
        var units = currentProgram.getListing().getCodeUnits(f.getBody(), true);
        while (units.hasNext()) {
            var unit = units.next();
            for (int kind : new int[]{CodeUnit.PRE_COMMENT,CodeUnit.EOL_COMMENT,CodeUnit.POST_COMMENT}) {
                String old = unit.getComment(kind);
                if (old != null && (old.contains("try {") || old.contains("catch(type#")))
                    unit.setComment(kind, null);
            }
        }
        setEOLComment(toAddr(0x2381f90L),"Runtime-error GOT page; slot28acdb0 ->table28a3b58, address point+16");
        setEOLComment(toAddr(0x2381fb4L),"Allocate length+25:24-byte prefix and trailing NUL");
        setEOLComment(toAddr(0x2381fbcL),"Message characters start at prefix+24; bytes20..23 untouched");
        setEOLComment(toAddr(0x2381ff0L),"Allocation-only cleanup: preserve unwind object, clean partial base, resume");
        setEOLComment(toAddr(0x2401e24L),"Derived InitializationException vtable address point282edf0/ELF272edf0");
        setEOLComment(toAddr(0x2401e2cL),"Replace only receiver vptr after successful runtime_error construction");
        println("JNI exception RTTI/vtable bound; relocations checked; constructor has no LSDA");
    }
}
