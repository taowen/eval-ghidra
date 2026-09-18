# Script port map

Source: `experiments/ar-glass-lib-3dof/messy/tools/*.java` (41 files)
Target: `eval-ghidra/ghidra_scripts/`

Goal: reusable, vendor-neutral refine scripts for the tutorial. Remove the
`Nr`/`nr` marker (NREAL) everywhere: class names, `@category`, type names,
helper tags and data-type category prefixes. Only scripts that are reusable
against an arbitrary program are kept; per-analyte bind scripts are removed.

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

## Removed: project-specific examples

Eleven scripts were written against one specific analyzed binary, with
hard-coded offsets and neutral `Tutorial*` placeholder type names
(`RefineCylinderCropLocals`, `RefineExternalBlitConstructorLocals`,
`RefineExternalResolverLocals`, `RefineExternalSurfaceHolderLocals`,
`RefineJniEnvironmentGlobals`, `RefineJniExceptionGlobals`,
`RefineRenderCommandGlobals`, `RefineRenderSourceUpdateLocals`,
`RefineRenderTextureLocals`, `RefineStateLifecycleGlobals`,
`RefineWarpBackendInitLocals`). They were deleted: a copied per-analyte bind
script carries no usable evidence for another binary. Write that kind of script
against your own program when you need it.

## Content substitutions applied to every file

| pattern | replacement |
| --- | --- |
| `@category NR.Refine` / `Arctrl` / `XREAL` | `@category Tutorial` |
| `Nr` prefix on type/helper names (e.g. `NrLoggerHandle`) | removed or `Tutorial` prefix (see above) |
| `nr-types-*.h` category path | `tutorial-types` |
| `libnr_api.so` program guard | removed |
| `@nr_nontrivial_sret` / `@nr_nontrivial_callback` / `@nr_noreturn` | `@tutorial_nontrivial_sret` / `@tutorial_nontrivial_callback` / `@tutorial_noreturn` |
| `@nr_callback_callsite` | `@tutorial_callback_callsite` |
| `__nr_anon_` / `__nr_import_retired_` / `__nr_retired_` | `__tutorial_anon_` / `__tutorial_import_retired_` / `__tutorial_retired_` |
| `/arctrl/stack` category | `/tutorial/stack` |
