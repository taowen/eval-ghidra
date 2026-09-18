# ghidra_scripts

Ghidra scripts used by the tutorial, ported from a real reverse-engineering
project and stripped of vendor-specific names. Run them with
`eval-ghidra.py`'s `run_ghidra_script` (absolute path) against a live
GhidraMCP instance.

## What these scripts are

Every script here is **reusable**. It depends only on public Ghidra APIs and your
program's own data. Each takes explicit arguments (function VA, register, stack
offset, type name, ...) and carries **no embedded addresses, offsets, or type
definitions**.

```
ImportTypes, InspectType, InspectFunctionAbi, InspectReturnStorage,
InspectHighAt, AuditAarch64ResultUse, AuditAarch64VtableSlotCalls,
AuditFunctionRanges, AuditDisjointFunctionBody, AuditInferredStackTypes,
BindCallbackCalls, CopyExistingLocalType, MaterializeThunkOverride,
MergeEquivalentTypes, ReplaceType, ResolveConflictType,
RefineFunctionRange, RefineStackSlot, RefineStackCopy, RefineUnionFacet,
RefineRegisterSlot, RefineDynamicLocal, RefineHighLocal, RefineHighInputLocal,
RefineAllocatedLocal, RefineLoadedLocal, RemoveFunctionLocals,
RemoveExactCommentLines, TestImportCallbacks,
PinMcpPort
```

Scripts that were written against one specific analyzed binary — with hard-coded
offsets and placeholder type names — have been removed. If you need that kind of
per-analyte bind script, write it for your own binary; a copied one contains no
usable evidence.

## Argument conventions

- Java script `args` are **Ghidra VAs**, not ELF RVAs. The scripts call
  `toAddr(Long.decode(...))`, which does no image-base conversion. This differs
  from `eval-ghidra.py` tool parameters, which take ELF RVAs.
- `args` is a single space-separated string. There is no shell quoting.
- A bare type name selects the canonical root type; a `/path` selects an exact
  category-scoped type.

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
