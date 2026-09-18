// @category Tutorial
// Entry-SP slots verified from the external-swapchain construction Listings.
// Types are imported from tutorial-types/swapchain-external.h.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.symbol.SourceType;
import java.util.Iterator;

public class RefineExternalBlitConstructorLocals extends GhidraScript {
    private DataType type(String name, int size) {
        DataType found = null;
        Iterator<DataType> all = currentProgram.getDataTypeManager().getAllDataTypes();
        while (all.hasNext()) {
            DataType candidate = all.next();
            if (candidate.getPathName().equals("/" + name) && candidate.getLength() == size) {
                if (found != null) {
                    throw new IllegalStateException("Ambiguous type " + name);
                }
                found = candidate;
            }
        }
        if (found == null) {
            throw new IllegalStateException("Missing type " + name + " size=" + size);
        }
        return found;
    }

    private void slot(Function function, int offset, String name, String typeName, int size)
            throws Exception {
        DataType dataType = type(typeName, size);
        for (Variable variable : function.getLocalVariables()) {
            if (variable.isStackVariable()
                    && variable.getStackOffset() < offset + size
                    && variable.getStackOffset() + variable.getLength() > offset) {
                function.removeVariable(variable);
            }
        }
        function.getStackFrame().createVariable(
                name, offset, dataType, SourceType.USER_DEFINED);
        println(function.getName() + " " + name + " " + offset + " " + size);
    }

    private Function function(long address) {
        Function result = getFunctionAt(toAddr(address));
        if (result == null) {
            throw new IllegalStateException("Missing exact function entry 0x" +
                    Long.toHexString(address));
        }
        return result;
    }

    @Override
    public void run() throws Exception {
        Function constructor = function(0x19f0f8cL);
        slot(constructor, -0x68, "stLogger", "TutorialLoggerHandle", 16);
        slot(constructor, -0x58, "pszLogMessage", "pointer", 8);
        slot(constructor, -0x50, "stLogSource", "TutorialLogSourceWords", 24);
        slot(constructor, -0x38, "lSavedCanary", "long", 8);

        Function allocator = function(0x19eda78L);
        slot(allocator, -0xc0, "qwTextureName", "ulonglong", 8);
        slot(allocator, -0xb8, "stLogger", "TutorialLoggerHandle", 16);
        slot(allocator, -0xa8, "stSurface", "TutorialRenderSurfaceHandle", 16);
        slot(allocator, -0x98, "stTexture", "TutorialRenderTextureHandle", 16);
        slot(allocator, -0x84, "nAllocationMode", "int", 4);
        slot(allocator, -0x80, "stLogSource", "TutorialLogSourceWords", 24);
        slot(allocator, -0x68, "lSavedCanary", "long", 8);

        Function baseInitializer = function(0x19edf7cL);
        slot(baseInitializer, -0xa8, "lThreadId", "long", 8);
        slot(baseInitializer, -0xa0, "stLogger", "TutorialLoggerHandle", 16);
        slot(baseInitializer, -0x90, "stInitScratch",
                "TutorialExternalBaseInitializeScratch", 64);
        slot(baseInitializer, -0x48, "lSavedCanary", "long", 8);

        Function blitInitializer = function(0x19eefb4L);
        slot(blitInitializer, -0xe8, "qwTextureName", "ulonglong", 8);
        slot(blitInitializer, -0xe0, "uThreadOrLogger",
                "TutorialExternalBlitInitializeThreadOrLogger", 16);
        slot(blitInitializer, -0xd0, "stSurface", "TutorialRenderSurfaceHandle", 16);
        slot(blitInitializer, -0xc0, "uLoggerOrTexture",
                "TutorialExternalBlitInitializeHandle", 16);
        slot(blitInitializer, -0xb0, "stBlitScratch",
                "TutorialExternalBlitInitializeScratch", 64);
        slot(blitInitializer, -0x68, "lSavedCanary", "long", 8);
    }
}
