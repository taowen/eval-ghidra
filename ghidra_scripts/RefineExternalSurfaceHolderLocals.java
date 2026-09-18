// @category Tutorial
// Entry-SP slots verified from the external holder/payload Listings.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.symbol.SourceType;
import java.util.Iterator;

public class RefineExternalSurfaceHolderLocals extends GhidraScript {
    private DataType type(String name, int size) {
        DataType found = null;
        Iterator<DataType> all = currentProgram.getDataTypeManager().getAllDataTypes();
        while (all.hasNext()) {
            DataType candidate = all.next();
            if (candidate.getPathName().equals("/" + name) && candidate.getLength() == size) {
                if (found != null) throw new IllegalStateException("Ambiguous type " + name);
                found = candidate;
            }
        }
        if (found == null) throw new IllegalStateException("Missing type " + name + " size=" + size);
        return found;
    }

    private Function function(long address) {
        Function result = getFunctionAt(toAddr(address));
        if (result == null) throw new IllegalStateException(
                "Missing exact function entry 0x" + Long.toHexString(address));
        return result;
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
        function.getStackFrame().createVariable(name, offset, dataType, SourceType.USER_DEFINED);
        println(function.getName() + " " + name + " " + offset + " " + size);
    }

    @Override
    public void run() throws Exception {
        Function initializeHolder = function(0x1ae9b84L);
        slot(initializeHolder, -0x9c, "bSkipModeForLog", "uchar", 1);
        slot(initializeHolder, -0x98, "uLoggerOrText",
                "TutorialExternalHolderLoggerOrTextScratch", 16);
        slot(initializeHolder, -0x88, "uSwapchainOrLogger",
                "TutorialExternalHolderLookupOrLogger", 16);
        slot(initializeHolder, -0x78, "pszDiagnosticMessage", "pointer", 8);
        slot(initializeHolder, -0x70, "uSourceOrLog",
                "TutorialExternalHolderCreateOrLogScratch", 24);
        slot(initializeHolder, -0x58, "lSavedCanary", "long", 8);

        Function initializeSwapchain = function(0x19f07a8L);
        slot(initializeSwapchain, -0x88, "stDiagnosticLogger", "TutorialLoggerHandle", 16);
        slot(initializeSwapchain, -0x78, "pSwapchainForLog", "pointer", 8);
        slot(initializeSwapchain, -0x70, "uHolderOrLogSource",
                "TutorialExternalHolderCreateOrLogScratch", 24);
        slot(initializeSwapchain, -0x58, "lSavedCanary", "long", 8);

        Function frameCallback = function(0x1ae4334L);
        slot(frameCallback, -0x90, "stFinalDiagnosticLogger", "TutorialLoggerHandle", 16);
        slot(frameCallback, -0x80, "pHolderForLog", "pointer", 8);
        slot(frameCallback, -0x78, "uSwapchainOrLogger",
                "TutorialExternalFrameLookupOrLogger", 16);
        slot(frameCallback, -0x68, "pszDiagnosticMessage", "pointer", 8);
        slot(frameCallback, -0x60, "stLogSource", "TutorialLogSourceWords", 24);
        slot(frameCallback, -0x48, "lSavedCanary", "long", 8);

        Function stopSurfaceTexture = function(0x1ae5c98L);
        slot(stopSurfaceTexture, -0x58, "stDiagnosticLogger", "TutorialLoggerHandle", 16);
        slot(stopSurfaceTexture, -0x48, "pszDiagnosticMessage", "pointer", 8);
        slot(stopSurfaceTexture, -0x40, "stLogSource", "TutorialLogSourceWords", 24);
        slot(stopSurfaceTexture, -0x28, "lSavedCanary", "long", 8);
    }
}
