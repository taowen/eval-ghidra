# Script port map

Source: `experiments/ar-glass-lib-3dof/messy/tools/*.java` (41 files)
Target: `eval-ghidra/ghidra_scripts/`

Goal: reusable, vendor-neutral refine scripts for the tutorial. Remove the
`Nr`/`nr` marker (NREAL) everywhere: class names, `@category`, type names,
helper tags and data-type category prefixes. Project-specific scripts keep
their structure but their hard-coded `Nr*` types become neutral `Tutorial*`
names; they are shipped as examples of the technique, not as turnkey tools.

## Generic rename (no content change beyond category)

| old | new |
| --- | --- |
| AuditAarch64ResultUse | AuditAarch64ResultUse |
| AuditAarch64VtableSlotCalls | AuditAarch64VtableSlotCalls |
| AuditDisjointFunctionBody | AuditDisjointFunctionBody |
| AuditFunctionRanges | AuditFunctionRanges |
| AuditInferredStackTypes | AuditInferredStackTypes |
| CopyExistingLocalType | CopyExistingLocalType |
| InspectFunctionAbi | InspectFunctionAbi |
| InspectHighAt | InspectHighAt |
| InspectReturnStorage | InspectReturnStorage |
| MaterializeThunkOverride | MaterializeThunkOverride |
| RefineAllocatedLocal | RefineAllocatedLocal |
| RefineDynamicLocal | RefineDynamicLocal |
| RefineFunctionRange | RefineFunctionRange |
| RefineHighInputLocal | RefineHighInputLocal |
| RefineHighLocal | RefineHighLocal |
| RefineLoadedLocal | RefineLoadedLocal |
| RefineRegisterSlot | RefineRegisterSlot |
| RefineStackCopy | RefineStackCopy |
| RefineStackSlot | RefineStackSlot |
| RefineUnionFacet | RefineUnionFacet |
| RemoveExactCommentLines | RemoveExactCommentLines |
| RemoveFunctionLocals | RemoveFunctionLocals |
| TestFunctionTypeRefresh | TestFunctionTypeRefresh |

`@category NR.Refine` / `@category Arctrl` / `@category XREAL` all become
`@category Tutorial`.

## Drop the Nr marker from the name

| old | new |
| --- | --- |
| BindNrCallbackCalls | BindCallbackCalls |
| ImportNrTypes | ImportTypes |
| InspectNrType | InspectType |
| MergeEquivalentNrTypes | MergeEquivalentTypes |
| ReplaceNrType | ReplaceType |
| ResolveNrConflictType | ResolveConflictType |
| TestImportNrCallbacks | TestImportCallbacks |

## Project-specific examples (structure kept, Nr types neutralised)

These are pinned examples of a technique. Their `Nr*` type references become
`Tutorial*` and their `libnr_api.so` guard is dropped so the script is not tied
to one program. They still contain example offsets and must be adapted before
real use; the header comment says so.

| old | new |
| --- | --- |
| RefineCylinderCropLocals | RefineCylinderCropLocals |
| RefineExternalBlitConstructorLocals | RefineExternalBlitConstructorLocals |
| RefineExternalResolverLocals | RefineExternalResolverLocals |
| RefineExternalSurfaceHolderLocals | RefineExternalSurfaceHolderLocals |
| RefineJniEnvironmentGlobals | RefineJniEnvironmentGlobals |
| RefineJniExceptionGlobals | RefineJniExceptionGlobals |
| RefineRenderCommandGlobals | RefineRenderCommandGlobals |
| RefineRenderSourceUpdateLocals | RefineRenderSourceUpdateLocals |
| RefineRenderTextureLocals | RefineRenderTextureLocals |
| RefineStateLifecycleGlobals | RefineStateLifecycleGlobals |
| RefineWarpBackendInitLocals | RefineWarpBackendInitLocals |

## Content substitutions applied to every file

| pattern | replacement |
| --- | --- |
| `@category NR.Refine` / `Arctrl` / `XREAL` | `@category Tutorial` |
| `Nr` prefix on type/helper names (e.g. `NrLoggerHandle`) | `Tutorial` prefix |
| `nr-types-*.h` category path | `tutorial-types` |
| `libnr_api.so` program guard | removed |
| `@nr_nontrivial_sret` / `@nr_nontrivial_callback` / `@nr_noreturn` | `@tutorial_nontrivial_sret` / `@tutorial_nontrivial_callback` / `@tutorial_noreturn` |
| `@nr_callback_callsite` | `@tutorial_callback_callsite` |
| `__nr_anon_` / `__nr_import_retired_` / `__nr_retired_` | `__tutorial_anon_` / `__tutorial_import_retired_` / `__tutorial_retired_` |
| `/arctrl/stack` category | `/tutorial/stack` |
