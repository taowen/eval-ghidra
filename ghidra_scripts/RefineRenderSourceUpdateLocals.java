// @category Tutorial
// Entry-SP slots verified from the source update queue Listings.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.symbol.SourceType;
import java.util.Iterator;

public class RefineRenderSourceUpdateLocals extends GhidraScript {
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
        Function wait = function(0x19f8ad4L);
        slot(wait, -0x78, "stDiagnosticLogger", "TutorialLoggerHandle", 16);
        slot(wait, -0x68, "pszDiagnosticMessage", "pointer", 8);
        slot(wait, -0x60, "uWaitOrLogScratch", "TutorialRenderSourceWaitScratch", 24);
        slot(wait, -0x48, "lSavedCanary", "long", 8);

        Function requeue = function(0x19f9190L);
        slot(requeue, -0xa0, "qwPendingCountForLog", "ulonglong", 8);
        slot(requeue, -0x98, "stAppendDiagnosticLogger", "TutorialLoggerHandle", 16);
        slot(requeue, -0x88, "uSwapchainOrLookupLogger",
                "TutorialRenderSourceRequeueHandle", 16);
        slot(requeue, -0x78, "qwRequestedSourceKey", "ulonglong", 8);
        slot(requeue, -0x70, "stDiagnosticSource", "TutorialLogSourceWords", 24);
        slot(requeue, -0x58, "lSavedCanary", "long", 8);
    }
}
