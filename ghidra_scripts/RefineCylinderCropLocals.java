// Recover live math/string/handle stack objects for the normal cylinder path.
// Offsets are relative to entry SP, derived from the full pinned Listing.
// This is analysis metadata only; no SDK container implementation is generated.
// @category Tutorial
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.SourceType;
import java.util.*;

public class RefineCylinderCropLocals extends GhidraScript {
    private DataType named(String name, int size) throws Exception {
        Iterator<DataType> it = currentProgram.getDataTypeManager().getAllDataTypes();
        while (it.hasNext()) {
            DataType type = it.next();
            if (type.getName().equals(name) && type.getLength() == size) return type;
        }
        throw new IllegalStateException("Missing canonical type " + name + " size " + size);
    }
    private void stack(long rva, int offset, String name, DataType type) throws Exception {
        Function fn = getFunctionAt(toAddr(rva + 0x100000L));
        if (fn == null) throw new IllegalStateException("Not an entry " + Long.toHexString(rva));
        int end = offset + type.getLength();
        for (Variable old : fn.getLocalVariables()) {
            if (!old.isStackVariable()) continue;
            int start = old.getStackOffset();
            if (start < end && start + old.getLength() > offset) fn.removeVariable(old);
        }
        fn.getStackFrame().createVariable(name, offset, type, SourceType.USER_DEFINED);
        println(fn.getName() + " " + name + " @" + offset + " size=" + type.getLength());
    }
    private DataType floats(int count) {
        return new ArrayDataType(FloatDataType.dataType, count, 4);
    }
    @Override public void run() throws Exception {
        DataType string = named("TutorialRuntimeString",24);
        DataType handle = named("TutorialLoggerHandle",16);
        DataType source = named("TutorialLogSourceWords",24);
        DataType vector = named("TutorialCylinderPointVector",24);
        DataType scratch = named("TutorialCylinderPrepareScratch",64);
        // calculate: frame1c0, FP=entrySP-50. FP-20/-30/-40/-4c.
        stack(0x1cd854cL,-0x70,"afQueryHomogeneous",floats(4));
        stack(0x1cd854cL,-0x80,"afUnprojected",floats(3));
        stack(0x1cd854cL,-0x90,"afCylinderDirection",floats(3));
        stack(0x1cd854cL,-0x9c,"afIntersection",floats(3));
        stack(0x1cd854cL,-0xc8,"stLogger",handle);
        stack(0x1cd854cL,-0xb8,"stLogSource",source);
        int[] tags = {0xe0,0xf8,0x110,0x128,0x140,0x158,0x170,0x188,0x1a0,0x1b8};
        for (int i=0;i<tags.length;i++) stack(0x1cd854cL,-tags[i],"stDebugTag"+i,string);
        // prepare: frame1d0, SP90 holds rel, FP-a0 reuses inverse for log source.
        stack(0x1cd935cL,-0x140,"afRelative",floats(16));
        stack(0x1cd935cL,-0x100,"uInverseOrLogSource",scratch);
        stack(0x1cd935cL,-0x150,"afClip",floats(4));
        stack(0x1cd935cL,-0x168,"stTransformedPoints",vector);
        stack(0x1cd935cL,-0x178,"stLogger",handle);
        // compose: framef0, SP20 is the complete64B inverse, not120B.
        stack(0x1cd9198L,-0xd0,"afInverseTexture",floats(16));
        stack(0x1cd9198L,-0xe8,"stDebugTag",string);
        // intersection: framed0, roots atSP20 and coefficients atSP28.
        stack(0x1cda2d4L,-0xb0,"afRootsMinusPlus",floats(2));
        stack(0x1cda2d4L,-0xa8,"afTwiceBA",floats(2));
        stack(0x1cda2d4L,-0x88,"afPointMinus",floats(3));
        stack(0x1cda2d4L,-0x78,"afPointPlus",floats(3));
        stack(0x1cda2d4L,-0xc0,"stLogger",handle);
        stack(0x1cda2d4L,-0xa0,"stLogSource",source);
    }
}
