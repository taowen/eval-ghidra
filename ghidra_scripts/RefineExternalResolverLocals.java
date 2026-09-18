// @category Tutorial
// Entry-SP slots verified from resolver acquire and helper-cache Listings.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.symbol.SourceType;
import java.util.Iterator;

public class RefineExternalResolverLocals extends GhidraScript {
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
        Function ensure = function(0x1a99dd8L);
        slot(ensure, -0x80, "stLogger", "TutorialLoggerHandle", 16);
        slot(ensure, -0x6c, "bSelectedSecondContext", "uchar", 1);
        slot(ensure, -0x68, "pszLogMessage", "pointer", 8);
        slot(ensure, -0x60, "uHelperOrLogSource",
                "TutorialExternalResolverAcquireScratch", 24);
        slot(ensure, -0x48, "lSavedCanary", "long", 8);

        Function acquire = function(0x1aea6e4L);
        slot(acquire, -0x88, "pszSelectedHelper", "pointer", 8);
        slot(acquire, -0x80, "stLogger", "TutorialLoggerHandle", 16);
        slot(acquire, -0x70, "uHelperOrLogSource",
                "TutorialExternalResolverAcquireScratch", 24);
        slot(acquire, -0x58, "lSavedCanary", "long", 8);

        for (long address : new long[] {0x1a9b7acL, 0x1acc44cL}) {
            Function createSurface = function(address);
            slot(createSurface, -0xa8, "stDiagnosticLogger", "TutorialLoggerHandle", 16);
            slot(createSurface, -0x98, "stSourceSharedContext", "TutorialRenderSurfaceHandle", 16);
            slot(createSurface, -0x88, "pszDiagnosticMessage", "pointer", 8);
            slot(createSurface, -0x80, "stSurfaceOrLogScratch",
                    "TutorialExternalResolverAcquireScratch", 24);
            slot(createSurface, -0x60, "stContextWords",
                    "TutorialRenderSurfaceContextWords", 16);
            slot(createSurface, -0x50, "bContextScratch", "uchar", 1);
            slot(createSurface, -0x48, "lSavedCanary", "long", 8);
        }

        for (long address : new long[] {0x1a9bd60L, 0x1acc950L}) {
            Function startThread = function(address);
            slot(startThread, -0x88, "stDiagnosticLogger", "TutorialLoggerHandle", 16);
            slot(startThread, -0x78, "pszDiagnosticMessage", "pointer", 8);
            slot(startThread, -0x70, "stThreadOrLogScratch",
                    "TutorialExternalResolverAcquireScratch", 24);
            slot(startThread, -0x58, "stHelperLock", "TutorialNativeUniqueLock", 16);
            slot(startThread, -0x48, "lSavedCanary", "long", 8);
        }

        Function stopThread = function(0x1a9ad50L);
        slot(stopThread, -0x68, "stDiagnosticLogger", "TutorialLoggerHandle", 16);
        slot(stopThread, -0x58, "pszDiagnosticMessage", "pointer", 8);
        slot(stopThread, -0x50, "stSourceOrLogScratch",
                "TutorialExternalResolverAcquireScratch", 24);
        slot(stopThread, -0x38, "lSavedCanary", "long", 8);

        Function releaseSurface = function(0x1a9b1a0L);
        slot(releaseSurface, -0x68, "stDiagnosticLogger", "TutorialLoggerHandle", 16);
        slot(releaseSurface, -0x58, "pszDiagnosticMessage", "pointer", 8);
        slot(releaseSurface, -0x50, "stLogSource", "TutorialLogSourceWords", 24);
        slot(releaseSurface, -0x38, "lSavedCanary", "long", 8);
    }
}
