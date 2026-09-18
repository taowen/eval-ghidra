// Replace a same-named struct with the selected focused type header.
// definition, preserving all existing references.
// Parsing delegates to ImportNrTypes.java types-only; deploy both together.
//
// Why: CParser skips same-named existing types, so a plain re-import
// cannot update FloatRigidPose / InputManager / TutorialImuTracker. This
// script renames the old type aside, parses the header (fresh type),
// then replaceDataType migrates every reference and drops the legacy.
//
// Usage (MCP run_ghidra_script):
//   ReplaceNrType.java <module.h> <TypeName>
//
// @category Tutorial

import java.io.File;
import generic.jar.ResourceFile;

import ghidra.app.script.GhidraScript;
import ghidra.app.script.GhidraScriptUtil;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeManager;

public class ReplaceType extends GhidraScript {
    private DataType findType(DataTypeManager dtm, String name) {
        // Canonical project types live at the root. Aggregate imports may
        // retain category-scoped historical copies with the same leaf name;
        // those must not make replacement of the canonical root ambiguous.
        DataType root = dtm.getDataType("/" + name);
        if (root != null) return root;
        java.util.Iterator<DataType> it = dtm.getAllDataTypes();
        DataType found = null;
        while (it.hasNext()) {
            DataType dt = it.next();
            if (dt.getName().equals(name)) {
                if (found != null) throw new IllegalStateException("ambiguous type: " + name);
                found = dt;
            }
        }
        return found;
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: ReplaceNrType.java <module.h> <TypeName>");
        }
        File header = new File(args[0]);
        if (!header.isFile()) {
            throw new IllegalArgumentException("missing header: " + header.getAbsolutePath());
        }
        String typeName = args[1];

        // MCP deploys siblings outside the GUI's configured script search paths.
        // Resolve and compile the importer before changing any type name.
        ResourceFile importerSource = new ResourceFile(getSourceFile().getParentFile(),
                "ImportNrTypes.java");
        // A previously deployed importer may no longer exist beside this script.
        // Canonical headers live under
        // tools/ghidra; resolve the repository importer before mutating types.
        if (!importerSource.isFile()) {
            File repositoryImporter = new File(header.getParentFile().getParentFile(),
                    "ImportNrTypes.java");
            if (repositoryImporter.isFile()) {
                // JavaScriptProvider compiles only configured script bundles.
                java.nio.file.Files.copy(repositoryImporter.toPath(),
                        java.nio.file.Paths.get(importerSource.getAbsolutePath()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!importerSource.isFile()) throw new IllegalArgumentException("missing " + importerSource);
        GhidraScript importer = GhidraScriptUtil.getProvider(importerSource)
                .getScriptInstance(importerSource, errorWriter);
        importer.setScriptArgs(new String[] {header.getAbsolutePath(), "types-only"});

        DataTypeManager dtm = currentProgram.getDataTypeManager();
        DataType old = findType(dtm, typeName);
        if (old == null) {
            throw new IllegalArgumentException("type not found: " + typeName);
        }

        String legacyName = typeName + "Legacy";
        DataType existingLegacy = findType(dtm, legacyName);
        if (existingLegacy != null) {
            throw new IllegalStateException("unresolved prior replacement: " + legacyName);
        }
        // Ghidra 12.1.2 PointerDB.dataTypeNameChanged reads getOldName()
        // without populating its lazy name cache. A newly loaded parent can
        // therefore send null to CategoryDB.dataTypeRemoved. Read the parent
        // closure first and retain these instances through the notification.
        java.util.Set<DataType> parents = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<DataType> pending = new java.util.ArrayDeque<>();
        pending.add(old);
        while (!pending.isEmpty()) {
            DataType type = pending.removeFirst();
            if (!parents.add(type)) continue;
            type.getName();
            pending.addAll(type.getParents());
        }
        for (DataType type : parents) type.getName();
        old.setName(legacyName);  // references follow the same DataType object
        println("renamed old " + typeName + " -> " + legacyName);

        int before = dtm.getDataTypeCount(true);
        // Keep one parser path. CParserUtils uses storeDataType=true and can
        // clear unrelated complete layouts when this header references them.
        // ImportNrTypes resolves existing composites and stores parsed types
        // without reapplying live function signatures in this mode.
        try {
            importer.execute(getState(), getControls());
        } catch (Exception failure) {
            if (findType(dtm, typeName) == null) old.setName(typeName);
            throw failure;
        }
        int after = dtm.getDataTypeCount(true);
        println("data types before=" + before + " after=" + after);

        DataType fresh = findType(dtm, typeName);
        if (fresh == null) {
            old.setName(typeName);
            throw new IllegalStateException("fresh type not parsed: " + typeName);
        }
        if (fresh == old) {
            throw new IllegalStateException("parser returned the legacy type");
        }

        DataType migrated = dtm.replaceDataType(old, fresh, true);
        if (migrated == null) throw new IllegalStateException("type migration failed");
        println("migrated references to fresh " + typeName);

        DataType legacy = findType(dtm, legacyName);
        if (legacy != null) {
            dtm.remove(legacy, monitor);
            println("removed legacy " + legacyName);
        }
    }
}
