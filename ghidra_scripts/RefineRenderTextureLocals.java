// @category Tutorial
// Entry-SP slots verified from the normal BMP/path/texture call sites.
// Structures and unions are imported from tutorial-types/render-texture.h.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.SourceType;
import java.util.*;

public class RefineRenderTextureLocals extends GhidraScript {
    private DataType type(String name, int size) {
        DataType found = null;
        Iterator<DataType> all = currentProgram.getDataTypeManager().getAllDataTypes();
        while (all.hasNext()) {
            DataType t = all.next();
            // ImportNrTypes publishes the canonical definitions at root;
            // old header-category copies are not the active ABI definitions.
            if (t.getPathName().equals("/" + name) && t.getLength() == size) {
                if (found != null) throw new IllegalStateException("Ambiguous type " + name);
                found = t;
            }
        }
        if (found == null) throw new IllegalStateException("Missing type " + name);
        return found;
    }
    private void slot(long rva, int offset, String name, String typeName, int size) throws Exception {
        DataType t = type(typeName, size);
        Function f = getFunctionAt(toAddr(rva + 0x100000L));
        if (f == null) throw new IllegalStateException("Missing exact function entry");
        for (Variable v : f.getLocalVariables()) {
            if (v.isStackVariable() && v.getStackOffset() < offset + size &&
                    v.getStackOffset() + v.getLength() > offset) f.removeVariable(v);
        }
        f.getStackFrame().createVariable(name, offset, t, SourceType.USER_DEFINED);
        println(f.getName() + " " + name + " " + offset + " " + size);
    }
    @Override public void run() throws Exception {
        type("TutorialRenderTexture",72);
        slot(0x193dc90L,-0xc0,"uInfoOrLog","TutorialBmpInfoOrLog",40);
        slot(0x193dc90L,-0x96,"stFileHeader","TutorialBmpFileHeaderBytes",14);
        slot(0x193dc90L,-0x80,"uLoggerOrSource","TutorialBmpLoggerOrSource",24);
        slot(0x193dc90L,-0xd8,"stLogger","TutorialLoggerHandle",16);
        slot(0x190dd78L,-0x60,"stRelativePath","TutorialRuntimeString",24);
        slot(0x190a758L,-0x50,"stBasePath","TutorialRuntimeString",24);
        slot(0x19df804L,-0x80,"stJoinedPath","TutorialRuntimeString",24);
        slot(0x1909e38L,-0x1f0,"stResourcePath","TutorialRuntimeString",24);
        slot(0x1989a10L,-0x80,"stLogger","TutorialLoggerHandle",16);
        slot(0x1989a10L,-0x70,"stLogSource","TutorialLogSourceWords",24);
        slot(0x19063b0L,-0x58,"stSurfaceInput","TutorialRenderSurfaceHandle",16);
        slot(0x19052f4L,-0x60,"stTextureResult","TutorialRenderTextureHandle",16);
        slot(0x198aea0L,-0x68,"stGlesTextureInput","TutorialRenderTextureHandle",16);
        slot(0x198aea0L,-0x78,"stGlesSurfaceInput","TutorialRenderSurfaceHandle",16);
        slot(0x198aea0L,-0x88,"stAlternateTextureInput","TutorialSharedHandle",16);
        slot(0x198aea0L,-0x98,"stAlternateSurfaceInput","TutorialRenderSurfaceHandle",16);
    }
}
