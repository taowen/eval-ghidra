# ghidra_scripts

Ghidra scripts used by the tutorial, ported from a real reverse-engineering
project and stripped of vendor-specific names. Run them with
`eval-ghidra.py`'s `run_ghidra_script` (absolute path) against a live
GhidraMCP instance.

## Two kinds of script

Read this before running anything. The scripts fall into two groups:

### 1. Reusable refine/audit scripts — use as-is

These depend only on public Ghidra APIs and your program's own data. They take
explicit arguments (function VA, register, stack offset, type name, ...) and
carry **no embedded addresses or type definitions**.

```
ImportTypes, InspectType, InspectFunctionAbi, InspectReturnStorage,
InspectHighAt, AuditAarch64ResultUse, AuditAarch64VtableSlotCalls,
AuditFunctionRanges, AuditDisjointFunctionBody, AuditInferredStackTypes,
BindCallbackCalls, CopyExistingLocalType, MaterializeThunkOverride,
MergeEquivalentTypes, ReplaceType, ResolveConflictType,
RefineFunctionRange, RefineStackSlot, RefineStackCopy, RefineUnionFacet,
RefineRegisterSlot, RefineDynamicLocal, RefineHighLocal, RefineHighInputLocal,
RefineAllocatedLocal, RefineLoadedLocal, RemoveFunctionLocals,
RemoveExactCommentLines, TestFunctionTypeRefresh, TestImportCallbacks,
PinMcpPort
```

### 2. Project-specific examples — adapt before use

These were written against one analyzed binary. They contain **hard-coded
offsets and `Tutorial*` type names** and exist to show the technique, not to
work turnkey. Treat the offsets as placeholders, replace the `Tutorial*`
types with your own canonical types, and re-verify every address.

```
RefineCylinderCropLocals, RefineExternalBlitConstructorLocals,
RefineExternalResolverLocals, RefineExternalSurfaceHolderLocals,
RefineJniEnvironmentGlobals, RefineJniExceptionGlobals,
RefineRenderCommandGlobals, RefineRenderSourceUpdateLocals,
RefineRenderTextureLocals, RefineStateLifecycleGlobals,
RefineWarpBackendInitLocals
```

## What `Tutorial*` names mean

The `Tutorial*` prefix (e.g. `TutorialLoggerHandle`, `TutorialSharedHandle`)
is a **neutral placeholder** for whatever vendor type occupied that role. It is
not a real type shipped by any SDK. The example scripts expect you to import
matching types under those names, or to rename them to your own canonical
types.

## Argument conventions

- Java script `args` are **Ghidra VAs**, not ELF RVAs. The scripts call
  `toAddr(Long.decode(...))`, which does no image-base conversion. This differs
  from `eval-ghidra.py` tool parameters, which take ELF RVAs.
- `args` is a single space-separated string. There is no shell quoting.
- A bare type name selects the canonical root type; a `/path` selects an exact
  category-scoped type.

## Why the `Nr` prefix was removed

`Nr` abbreviated a product name. The tutorial is generic, so it was removed
everywhere: class names, `@category`, helper tags (`@tutorial_*`), data-type
category paths, and the retired/anon prefixes (`__tutorial_*`). See
[PORT_MAP.md](PORT_MAP.md) for the exact mapping.

## Running a script

```python
# eval-ghidra.py snippet
switch_program(program="<your>.so")
run_ghidra_script(
    script_name=r"C:\games\eval-ghidra\ghidra_scripts\InspectType.java",
    args="Pose",
    timeout_seconds=120,
    capture_output=True)
```

Java scripts never act on a program unless one is open; `switch_program()`
selects the active program first.

## PinMcpPort

`PinMcpPort` is not a refine script. It writes the GhidraMCP `Server Port`
tool option into a Ghidra instance and saves the tool config, so a second
instance keeps a stable, distinct port across launches. See the tutorial's
[`../start-tutorial-ghidra.ps1`](../start-tutorial-ghidra.ps1).
