// Live-Ghidra regression for cross-header anonymous callback collisions.
// Deploy beside ImportTypes.java; runs types-only imports, then removes its
// fixture types. Does not change any official function signature.
// @category Tutorial
import ghidra.app.script.GhidraScript;
import ghidra.app.script.GhidraScriptUtil;
import ghidra.program.model.data.*;
import generic.jar.ResourceFile;
import java.nio.file.*;
import java.util.*;

public class TestImportCallbacks extends GhidraScript {
    private FunctionDefinition callback(String owner) {
        Structure s = (Structure)currentProgram.getDataTypeManager().getDataType("/" + owner);
        if (s == null) throw new IllegalStateException("missing fixture " + owner);
        return (FunctionDefinition)((Pointer)s.getComponent(0).getDataType()).getDataType();
    }

    private void check() {
        FunctionDefinition a = callback("TutorialCallbackImportTestA");
        FunctionDefinition b = callback("TutorialCallbackImportTestB");
        if (!a.getReturnType().getName().equals("int") || a.getArguments().length != 2
                || !(a.getArguments()[0].getDataType() instanceof Pointer)
                || !a.getArguments()[1].getDataType().getName().equals("int")) {
            throw new IllegalStateException("header B corrupted header A: " + a);
        }
        if (!b.getReturnType().getName().equals("void") || b.getArguments().length != 1
                || !b.getArguments()[0].getDataType().getName().equals("double")) {
            throw new IllegalStateException("header A corrupted header B: " + b);
        }
        if (a.equals(b)) throw new IllegalStateException("distinct callbacks share one definition");
    }

    @Override
    public void run() throws Exception {
        DataTypeManager dtm = currentProgram.getDataTypeManager();
        if (dtm.getDataType("/TutorialCallbackImportTestA") != null
                || dtm.getDataType("/TutorialCallbackImportTestB") != null) {
            throw new IllegalStateException("previous fixture types still exist");
        }
        ResourceFile source = new ResourceFile(getSourceFile().getParentFile(), "ImportTypes.java");
        if (!source.isFile()) throw new IllegalStateException("deploy ImportTypes.java beside test");
        Set<String> before = new HashSet<>();
        Map<String, String> foreignPrototypes = new HashMap<>();
        var types = dtm.getAllDataTypes();
        while (types.hasNext()) {
            DataType type = types.next();
            before.add(type.getPathName());
            if (type instanceof FunctionDefinition fd)
                foreignPrototypes.put(type.getPathName(), fd.getPrototypeString());
        }
        Path dir = Files.createTempDirectory("tutorial-callback-import-test-");
        Path a = dir.resolve("a.h"), b = dir.resolve("b.h");
        Path shared = dir.resolve("shared.h"), left = dir.resolve("left.h"), right = dir.resolve("right.h");
        Files.writeString(shared, "#ifndef NR_CALLBACK_TEST_SHARED\n#define NR_CALLBACK_TEST_SHARED\n"
                + "struct TutorialCallbackImportTestA { int (*call)(void *self, int value); };\n#endif\n");
        Files.writeString(left, "#include \"shared.h\"\n");
        Files.writeString(right, "#include \"./shared.h\"\n");
        Files.writeString(a, "#include \"left.h\"\n#include \"right.h\"\n");
        Files.writeString(b, "struct TutorialCallbackImportTestB { void (*call)(double value); };\n");
        try {
            for (Path header : new Path[] {a, b, a}) {
                GhidraScript importer = GhidraScriptUtil.getProvider(source)
                        .getScriptInstance(source, errorWriter);
                importer.setScriptArgs(new String[] {header.toString(), "types-only"});
                importer.execute(getState(), getControls());
                if (dtm.getDataType("/TutorialCallbackImportTestB") != null) check();
                for (var entry : foreignPrototypes.entrySet()) {
                    DataType current = dtm.getDataType(entry.getKey());
                    if (!(current instanceof FunctionDefinition fd)
                            || !entry.getValue().equals(fd.getPrototypeString()))
                        throw new IllegalStateException("foreign callback changed: " + entry.getKey());
                }
            }
            int fixtureCallbacks = 0;
            types = dtm.getAllDataTypes();
            while (types.hasNext()) {
                DataType type = types.next();
                if (type instanceof FunctionDefinition && !before.contains(type.getPathName()))
                    fixtureCallbacks++;
            }
            if (fixtureCallbacks != 2)
                throw new IllegalStateException("expected two fixture callbacks, got " + fixtureCallbacks);
            println("PASS: diamond includes and A->B->A preserve both callback ABIs and all foreign prototypes");
        } finally {
            List<DataType> created = new ArrayList<>();
            types = dtm.getAllDataTypes();
            while (types.hasNext()) {
                DataType type = types.next();
                if (!before.contains(type.getPathName())) created.add(type);
            }
            for (DataType type : created) dtm.remove(type, monitor);
            Files.deleteIfExists(a);
            Files.deleteIfExists(b);
            Files.deleteIfExists(left);
            Files.deleteIfExists(right);
            Files.deleteIfExists(shared);
            Files.deleteIfExists(dir);
        }
    }
}
