import java.nio.file.*;
import java.util.regex.*;
import java.util.Iterator;
import ghidra.app.script.GhidraScript;
import ghidra.app.util.cparser.C.CParser;
import ghidra.program.model.data.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Function.FunctionUpdateType;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.ParameterImpl;
import ghidra.program.model.listing.ReturnParameterImpl;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.database.SpecExtension;

/** ImportTypes — parse a C struct header into the active program's DTM
 *  and apply function prototypes as USER_DEFINED signatures.
 *
 *  Mechanism (TRANSLATION_HANDBOOK §2.5):
 *   1. parse exactly one focused header; quoted includes are dependency notes
 *      and are stripped rather than recursively concatenated
 *   2. strip include guards
 *   3. move old FunctionDefinitions aside (CParser skips same-name types)
 *   4. parse the focused source, then replace old definitions while
 *      migrating references; deleting them first destroys callback typedefs
 *   5. apply each FunctionDefinition to the function of the same name, or
 *      to the exact ELF-RVA+image-base entry encoded in its address suffix
 *      (Function.updateFunction + USER_DEFINED; struct return models sret)
 *   6. conservatively skip all-void-pointer/undefined-param prototypes (they are
 *      unrefined placeholders and must not overwrite current signatures)
 *
 *  Verify parse_succeeded=true and failed=0 before using the refinement.
 *  Usage: ImportTypes.java <c-header-path> [types-only]
 */
public class ImportTypes extends GhidraScript {
    private static final int DEFAULT_SOURCE_LIMIT = 64 * 1024;

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) {
            println("usage: ImportTypes.java <c-header-path> [types-only]");
            return;
        }
        boolean typesOnly = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("types-only") && !typesOnly) typesOnly = true;
            else throw new IllegalArgumentException(
                    "expected at most one optional types-only argument");
        }
        Path root = Paths.get(args[0]).toAbsolutePath();
        String src = Files.readString(root);
        int declaredIncludes = 0;
        Matcher include = Pattern.compile("(?m)^\\s*#\\s*include\\s*\"[^\"]+\"\\s*$")
                .matcher(src);
        while (include.find()) declaredIncludes++;
        src = stripPreprocessor(src);
        println("root_source_chars=" + Files.size(root));
        println("parsed_source_files=1");
        println("declared_dependency_includes=" + declaredIncludes);
        println("parsed_source_chars=" + src.length());
        if (src.length() > DEFAULT_SOURCE_LIMIT) {
            throw new IllegalArgumentException(
                    "focused source exceeds " + DEFAULT_SOURCE_LIMIT
                    + " chars; split this header before importing");
        }

        // Function-specific ABI facts live alongside declarations in the
        // header. User authorized this non-default extension on 2026-09-05.
        // Never change the program default or invent an explicit void out arg.
        java.util.Set<String> indirectReturns = new java.util.HashSet<>();
        Matcher abi = Pattern.compile("@tutorial_nontrivial_sret\\s+(\\w+)").matcher(src);
        while (abi.find()) indirectReturns.add(abi.group(1));
        java.util.Set<String> indirectCallbacks = new java.util.HashSet<>();
        Matcher callbackAbi = Pattern.compile("@tutorial_nontrivial_callback\\s+(\\w+)").matcher(src);
        while (callbackAbi.find()) indirectCallbacks.add(callbackAbi.group(1));
        java.util.Set<String> noReturns = new java.util.HashSet<>();
        Matcher nonreturn = Pattern.compile("@tutorial_noreturn\\s+(\\w+)").matcher(src);
        while (nonreturn.find()) noReturns.add(nonreturn.group(1));
        String indirectConvention = null;
        if (!indirectReturns.isEmpty() || !indirectCallbacks.isEmpty()) {
            String document = Files.readString(root.getParent().resolve("tutorial-nontrivial-sret.xml"));
            SpecExtension extension = new SpecExtension(currentProgram);
            SpecExtension.DocInfo info = extension.testExtensionDocument(document);
            String existing = currentProgram.getOptions(SpecExtension.SPEC_EXTENSION)
                    .getString(info.getOptionName(), null);
            if (!document.equals(existing)) {
                extension.addReplaceCompilerSpecExtension(document, monitor);
            }
            indirectConvention = info.getFormalName();
            println("nontrivial_return_convention=" + indirectConvention);
        }

        DataTypeManager dtm = currentProgram.getDataTypeManager();
        java.util.Set<String> retainedPaths = new java.util.HashSet<>();
        java.util.List<RetiredDefinition> retired = retireFunctionDefinitions(dtm, src, retainedPaths);
        println("retired_function_definitions=" + retired.size());

        // In Ghidra 12.1.2, storeDataType=true makes findAnyComposite ignore
        // existing manager types. Even a parameter "struct X *" can then add
        // an empty X with REPLACE_HANDLER and erase a previously recovered
        // layout. Parse with manager lookup enabled; explicitly store only the
        // definitions produced by this header afterwards.
        CParser parser = new CParser(dtm, false, new DataTypeManager[] { dtm });
        int replaced;
        try {
            parser.parse(src);
            if (parser.didParseSucceed()) {
                java.util.Set<DataType> parsed = new java.util.LinkedHashSet<>();
                parsed.addAll(parser.getComposites().values());
                parsed.addAll(parser.getEnums().values());
                parsed.addAll(parser.getTypes().values());
                parsed.addAll(parser.getFunctions().values());
                println("parsed_types_to_store=" + parsed.size());
                int stored = 0;
                for (DataType type : parsed) {
                    dtm.addDataType(type, DataTypeConflictHandler.REPLACE_HANDLER);
                    stored++;
                    if (stored % 25 == 0 || stored == parsed.size())
                        println("stored_types=" + stored + "/" + parsed.size());
                }
            }
        } finally {
            // Even malformed input must not leave existing callback types
            // under temporary names. No function signatures are applied yet.
            try {
                println("isolating_anonymous_definitions=true");
                isolateAnonymousDefinitions(dtm, src);
            } finally {
                println("restoring_function_references=true");
                replaced = restoreFunctionReferences(dtm, retired, retainedPaths);
            }
        }
        println("parse_succeeded=" + parser.didParseSucceed());
        if (!parser.didParseSucceed()) throw new IllegalArgumentException("C header parse failed");
        println("types_after=" + dtm.getDataTypeCount(true));
        String msgs = parser.getParseMessages();
        if (msgs != null && !msgs.isEmpty()) println("messages=" + msgs);

        // Named callback typedefs also need the evidenced hidden-X8 ABI.
        // This changes only explicitly annotated types, never the default ABI.
        for (String name : indirectCallbacks) {
            DataType callback = dtm.getDataType("/" + name);
            while (callback instanceof TypeDef) callback = ((TypeDef) callback).getBaseDataType();
            if (callback instanceof Pointer) callback = ((Pointer) callback).getDataType();
            if (!(callback instanceof FunctionDefinition) ||
                    !(((FunctionDefinition) callback).getReturnType() instanceof Structure))
                throw new IllegalArgumentException("missing struct-return callback: " + name);
            ((FunctionDefinition) callback).setCallingConvention(indirectConvention);
            println("nontrivial_callback=" + name);
        }

        // Repair/import canonical layouts without reapplying unrelated live
        // function signatures. Function-definition reference migration above
        // still runs; callers must recheck affected live types and bodies.
        if (typesOnly) {
            println("types_only=true applied_signatures=0 failed=0");
            return;
        }

        int applied = 0, skipped = 0, skippedMissing = 0, failed = 0;
        java.util.List<String> failures = new java.util.ArrayList<>();
        Iterator<DataType> it = dtm.getAllDataTypes();
        while (it.hasNext()) {
            DataType dt = it.next();
            if (!(dt instanceof FunctionDefinition)) continue;
            // Definitions from other modules retain their existing references,
            // but must not reapply stale prototypes to live functions.
            if (retainedPaths.contains(dt.getPathName())) continue;
            FunctionDefinition fd = (FunctionDefinition) dt;
            String name = fd.getName();
            if (isAllVoidParams(fd)) { skipped++; continue; }
            Function f = findFunction(name);
            // A focused module may describe one side of a cross-SO boundary.
            // A missing same-name function is therefore a foreign-program
            // declaration, not an application failure. Import the same small
            // module into each active program that owns one of its functions.
            if (f == null) { skippedMissing++; continue; }
            try {
                ParameterDefinition[] definitions = fd.getArguments();
                Variable[] params = new Variable[definitions.length];
                for (int i = 0; i < definitions.length; i++) {
                    ParameterDefinition definition = definitions[i];
                    params[i] = new ParameterImpl(definition.getName(),
                            definition.getDataType(), currentProgram);
                }
                boolean indirect = indirectReturns.contains(name);
                if (indirect && !(fd.getReturnType() instanceof Structure)) {
                    throw new IllegalArgumentException("nontrivial return must be a structure");
                }
                String declaredConvention = fd.getCallingConventionName();
                if (declaredConvention == null || "unknown".equals(declaredConvention)
                        || "default".equals(declaredConvention)) declaredConvention = null;
                f.updateFunction(indirect ? indirectConvention : declaredConvention,
                        new ReturnParameterImpl(fd.getReturnType(), currentProgram),
                        FunctionUpdateType.DYNAMIC_STORAGE_ALL_PARAMS, true,
                        SourceType.USER_DEFINED, params);
                // updateFunction does not copy the FunctionDefinition's
                // varargs flag. Without this, shader format calls lose their
                // W3+ / D0+ arguments despite the canonical trailing ellipsis.
                f.setVarArgs(fd.hasVarArgs());
                // RVA fallback resolves an existing FUN_ entry. updateFunction
                // updates its ABI, but does not install the header's name.
                // Keep the type module the sole source for both.
                if (!f.getName().equals(name)) f.setName(name, SourceType.USER_DEFINED);
                if (noReturns.contains(name)) f.setNoReturn(true);
                applied++;
            } catch (Exception e) {
                failed++;
                failures.add(name + ":" + e.getMessage());
            }
        }
        for (String name : indirectReturns) {
            Function f = findFunction(name);
            if (f == null || !indirectConvention.equals(f.getCallingConventionName())) {
                failed++;
                failures.add(name + ": nontrivial return annotation was not applied");
            }
        }
        println("replaced_stale_fd=" + replaced + " retained_foreign_fd=" + retainedPaths.size());
        println("applied_signatures=" + applied + " skipped=" + skipped
                + " skipped_missing=" + skippedMissing
                + " failed=" + failed);
        if (!failures.isEmpty()) {
            for (int i = 0; i < failures.size() && i < 20; i++) {
                println("  fail " + failures.get(i));
            }
        }
    }

    private Function findFunction(String name) {
        // ELF-RVA suffixes are authoritative.  Stripped programs can contain
        // duplicate recovered names at different entries; selecting the
        // first name match silently applies a prototype to the wrong body.
        Matcher suffix = Pattern.compile("_([0-9a-fA-F]+)$").matcher(name);
        if (suffix.find()) {
            try {
                long rva = Long.parseLong(suffix.group(1), 16);
                Address entry = currentProgram.getImageBase().add(rva);
                Function exact = currentProgram.getFunctionManager()
                        .getFunctionAt(entry);
                if (exact != null) return exact;
            } catch (Exception e) {
                // Fall through to the symbolic-name lookup below.
            }
        }
        FunctionIterator matches = currentProgram.getFunctionManager()
                .getFunctions(true);
        while (matches.hasNext()) {
            Function cand = matches.next();
            if (name.equals(cand.getName())) return cand;
        }
        // Type headers record ELF RVA suffixes, while Ghidra function names
        // may use the VA suffix or retain the original symbol. Resolve the
        // exact entry by ELF_RVA + image base as a cross-program fallback.
        suffix = Pattern.compile("_([0-9a-fA-F]+)$").matcher(name);
        if (!suffix.find()) return null;
        try {
            long rva = Long.parseLong(suffix.group(1), 16);
            Address entry = currentProgram.getImageBase().add(rva);
            return currentProgram.getFunctionManager().getFunctionAt(entry);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isAllVoidParams(FunctionDefinition fd) {
        ParameterDefinition[] params = fd.getArguments();
        if (params == null || params.length == 0) return false;
        for (ParameterDefinition p : params) {
            String n = p.getDataType().getName();
            if (!"void".equals(n) && !"undefined".equals(n)
                    && !"undefined1".equals(n) && !"undefined4".equals(n)
                    && !"undefined8".equals(n)) {
                return false;
            }
        }
        return true;
    }

    private static String stripPreprocessor(String src) {
        // CParser rejects guards and must never receive recursively expanded
        // dependency families. Existing canonical DTM types satisfy references.
        return src.replaceAll(
                "(?m)^\\s*#\\s*(?:include\\s*\"[^\"]+\"|ifndef\\b.*|define\\b.*|endif\\b.*)$",
                "");
    }

    private static class RetiredDefinition {
        final DataType type;
        final CategoryPath category;
        final String name;
        RetiredDefinition(DataType type) {
            this.type = type;
            // Recover canonical paths if an older importer left a retired
            // directory on a replacement type. Never persist scratch paths.
            this.category = new CategoryPath(type.getCategoryPath().getPath()
                    .replaceFirst("^(?:/__tutorial_import_retired_[^/]+)+", ""));
            this.name = type.getName();
        }
    }

    // CParser restarts its anonymous callback counter for every parse. Its
    // _func_1 in one header is unrelated to _func_1 in another. Matching those
    // names during reference migration silently changes foreign vtable ABIs.
    // Existing definitions are already retired here; only this parse's new
    // anonymous definitions have the original counter names. Give them an
    // identity scoped to the exact expanded source before restoring references.
    private static void isolateAnonymousDefinitions(DataTypeManager dtm,
            String source) throws Exception {
        String scope = anonymousScope(source);
        java.util.List<DataType> anonymous = new java.util.ArrayList<>();
        Iterator<DataType> it = dtm.getAllDataTypes();
        while (it.hasNext()) {
            DataType type = it.next();
            if (type instanceof FunctionDefinition && type.getName().matches("_func_[0-9]+")) {
                anonymous.add(type);
            }
        }
        for (DataType type : anonymous) type.setName(scope + type.getName());
    }

    private static String anonymousScope(String source) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "__tutorial_anon_" + java.util.HexFormat.of().formatHex(digest);
    }

    private static java.util.List<RetiredDefinition> retireFunctionDefinitions(
            DataTypeManager dtm, String source, java.util.Set<String> retainedPaths) throws Exception {
        // Only declarations this parse can recreate need to move out of its
        // namespace. Renaming every foreign callback makes a six-prototype
        // import sort the entire program's type list thousands of times.
        // Token matching is deliberately conservative: identifiers in comments
        // can retire extra types, but cannot hide an actual declaration.
        java.util.Set<String> identifiers = new java.util.HashSet<>();
        Matcher identifier = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*").matcher(source);
        while (identifier.find()) identifiers.add(identifier.group());
        String scope = anonymousScope(source) + "_func_";
        java.util.List<RetiredDefinition> stale = new java.util.ArrayList<>();
        Iterator<DataType> it = dtm.getAllDataTypes();
        while (it.hasNext()) {
            DataType dt = it.next();
            if (!(dt instanceof FunctionDefinition)) continue;
            String name = dt.getName();
            if (identifiers.contains(name) || name.matches("_func_[0-9]+")
                    || name.startsWith(scope)
                    || dt.getCategoryPath().getPath().startsWith("/__tutorial_import_retired_")) {
                stale.add(new RetiredDefinition(dt));
            } else {
                // The signature application pass must also leave these live
                // foreign definitions alone, even though they never moved.
                retainedPaths.add(dt.getPathName());
            }
        }
        String scratch = "/__tutorial_import_retired_" + java.util.UUID.randomUUID();
        int index = 0;
        for (RetiredDefinition old : stale) {
            String originalPath = old.category.getPath();
            old.type.setCategoryPath(new CategoryPath(scratch
                    + (originalPath.equals("/") ? "" : originalPath)));
            // CParser may find named prototypes across categories as well.
            old.type.setName("__tutorial_retired_" + index++ + "_" + old.name);
        }
        return stale;
    }

    private static int restoreFunctionReferences(DataTypeManager dtm,
            java.util.List<RetiredDefinition> retired,
            java.util.Set<String> retainedPaths) throws Exception {
        int replaced = 0;
        for (RetiredDefinition old : retired) {
            DataType fresh = dtm.getDataType(old.category, old.name);
            if (fresh instanceof FunctionDefinition) {
                // true would move the replacement into the old scratch
                // category; preserve the new declaration's canonical path.
                dtm.replaceDataType(old.type, fresh, false);
                replaced++;
            } else {
                old.type.setCategoryPath(old.category);
                old.type.setName(old.name);
                retainedPaths.add(old.type.getPathName());
            }
        }
        return replaced;
    }
}
