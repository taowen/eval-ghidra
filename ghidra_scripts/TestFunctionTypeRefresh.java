import ghidra.app.script.GhidraScript;
import ghidra.app.util.cparser.C.CParser;
import ghidra.program.model.data.*;
import java.lang.reflect.Method;
import java.util.*;

/** Tests the importer's reference migration in an isolated type archive.
 * Never edits the active Program. Uses its pointer-size organization only. */
public class TestFunctionTypeRefresh extends GhidraScript {
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }

    public void run() throws Exception {
        Method retire = ImportNrTypes.class.getDeclaredMethod(
                "retireFunctionDefinitions", DataTypeManager.class);
        Method restore = ImportNrTypes.class.getDeclaredMethod(
                "restoreFunctionReferences", DataTypeManager.class, List.class, Set.class);
        retire.setAccessible(true);
        restore.setAccessible(true);
        StandAloneDataTypeManager m = new StandAloneDataTypeManager(
                "tutorial-import-reference-test", currentProgram.getDataTypeManager().getDataOrganization());
        int tx = m.startTransaction("isolated fixture");
        try {
            CParser parser = new CParser(m, true, null);
            parser.parse("typedef void (*Handler)(void); struct Slot { Handler callback; unsigned long cookie; }; void work(void); int foreign(int value);");
            Structure slot = (Structure)m.getDataType("/Slot");
            int size = slot.getLength();
            for (int pass = 0; pass < 3; pass++) {
                Object old = retire.invoke(null, m);
                parser = new CParser(m, true, null);
                Set<String> retained = new HashSet<>();
                boolean parseFailed = false;
                try {
                    parser.parse(pass == 2 ? "typedef void (*" :
                            "typedef void (*Handler)(int value); struct Slot { Handler callback; unsigned long cookie; }; void work(int count);");
                } catch (Exception expected) {
                    if (pass != 2) throw expected;
                    parseFailed = true;
                } finally {
                    restore.invoke(null, m, old, retained);
                }
                check(pass != 2 || parseFailed || !parser.didParseSucceed(), "malformed fixture accepted");
                check(slot.getLength() == size, "container extent changed");
                check(slot.getComponent(0).getLength() == 8, "callback pointer width lost");
                TypeDef callback = (TypeDef)slot.getComponent(0).getDataType();
                FunctionDefinition fn = (FunctionDefinition)((Pointer)callback.getBaseDataType()).getDataType();
                check(fn.getArguments().length == 1, "callback signature not refreshed");
                check(fn.getArguments()[0].getDataType().getLength() == 4, "callback argument width wrong");
                FunctionDefinition work = (FunctionDefinition)m.getDataType("/functions/work");
                if (work == null) {
                    Iterator<DataType> debug = m.getAllDataTypes();
                    while (debug.hasNext()) println(debug.next().getPathName());
                    println("retained=" + retained);
                }
                check(work.getArguments().length == 1, "named function signature stale");
                check(retained.contains("/functions/foreign"), "foreign prototype not retained/excluded");
                Iterator<DataType> all = m.getAllDataTypes();
                while (all.hasNext()) {
                    DataType dt = all.next();
                    check(!dt.getPathName().contains("__tutorial_retired_"), "temporary definition leaked");
                }
                println("PASS " + (pass == 0 ? "signature update" : pass == 1 ? "repeat import" : "parse failure restoration"));
            }
        } finally {
            m.endTransaction(tx, false);
            m.close();
        }
    }
}
