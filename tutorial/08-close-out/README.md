# 08 — Hand off what you know, and what you do not

**Task**: you have refined a function and it is correct. Now someone else — or you
in six months — has to use the Ghidra database you produced. The task is to make
it usable **without** misleading them about what is proven.

This matters more than it sounds. Chapter 01 established that some information is
*not in the binary* and you had to supply it. If you do not mark which parts are
evidence and which are your inference, the next reader will treat your guesses as
facts.

## What an un-closed function looks like

Take chapter 02's `query_pose` as it stands right after the prototype fix:

```c
Query query_pose(float x, float y, int mode)
{
  float fVar1;
  float fVar2;
  float fVar3;
  Query QVar4;

  fVar2 = y + 1.0;
  fVar1 = x + 1.0;
  if (mode != 1) {
    fVar2 = y;
    fVar1 = x;
  }
  fVar3 = 0.0;
  if (mode != 0) {
    fVar3 = 0.5;
  }
  QVar4.y = fVar2;
  QVar4.x = fVar1;
  QVar4.confidence = fVar3;
  return QVar4;
}
```

The types are right, but the locals are `fVar1`/`fVar2`/`fVar3`/`QVar4`. A reader
cannot tell what they mean, and the completeness tool agrees:

```text
completeness_score = 41.54
undefined_variables = [fVar1, QVar4, fVar2, fVar3]   (all generic names)
has_plate_comment  = false
```

## What this costs you if you skip close-out

- The next reader re-derives every variable's meaning from scratch, or worse,
  guesses wrong and builds on the wrong guess.
- Nothing records that `confidence` is only ever 0.0 or 0.5, so the reader may
  assume a range that does not exist.
- Your inferred names are indistinguishable from verified facts.

## The seven checks

Each one must point at a concrete evidence location, not a feeling:

1. Function, parameters, business locals, and members all have checked types and
   semantic names; the ABI matches on both call sides; no unexplained `local_*`
   or `iVar*` business placeholder names remain.
2. The true entry and the full business/cleanup range are verified, and no internal
   block was mis-created as a function.
3. No unexplained `in_xN`, `unaff_xN`, or `extraout_*`; any tool residue has
   writer-and-user evidence.
4. Direct callee contracts and business indirect targets are recovered; logging
   and runtime boundaries are not expanded recursively.
5. Business-relevant rodata has a known type, bit pattern, and purpose.
6. Error returns, business exception cleanup, unlocks, and lifetimes are
   explainable.
7. The Ghidra struct/union/local bindings match the native layout, and affected C
   has been re-decompiled.

These map back to the earlier chapters: 1 -> ch 03 naming, 2 -> ch 05 range,
3 -> ch 02/03 ABI and CALL, 4 -> ch 04 indirect edges, 5 -> ch 06 rodata,
6 -> ch 05 cleanup, 7 -> ch 01 layout.

## Close out `query_pose`

### 1. Name the locals

```python
set_variables(function_address="0x46a4", variables={
  "fVar1": {"name": "adjusted_x", "type": "float"},
  "fVar2": {"name": "adjusted_y", "type": "float"},
  "fVar3": {"name": "confidence", "type": "float"},
  "QVar4": {"name": "result",     "type": "Query"}
})
```

The tool answers with a partial success — `names_set: 4, failed: 4`. This is worth
understanding rather than fighting: **`fVar1` and friends are decompiler display
names, not persistent symbols.** The names do get stored, but the C keeps printing
`fVar*` until the database symbol is tied to the value the decompiler uses. The
type step does that:

```python
set_local_variable_type(function_address="0x46a4", variable_name="result",     new_type="Query")
set_local_variable_type(function_address="0x46a4", variable_name="adjusted_x", new_type="float")
set_local_variable_type(function_address="0x46a4", variable_name="adjusted_y", new_type="float")
```

Now:

```c
Query query_pose(float x, float y, int mode)
{
  float adjusted_y;
  Query result;
  float adjusted_x;

  adjusted_y = y + 1.0;
  adjusted_x = x + 1.0;
  if (mode != 1) {
    adjusted_y = y;
    adjusted_x = x;
  }
  result.confidence = 0.0;
  if (mode != 0) {
    result.confidence = 0.5;
  }
  result.y = adjusted_y;
  result.x = adjusted_x;
  ...
}
```

> **There are two layers.** The decompiler prints its own names; the database holds
> yours. A rename shows up in the C only once the two are connected. If you only
> rename and never check the C, you will believe you named something you did not.

### 2. Write the plate

```text
Builds a Query from a 2D point and a mode flag.

Algorithm:
  adjusted_x = (mode == 1) ? x + 1.0 : x
  adjusted_y = (mode == 1) ? y + 1.0 : y
  confidence = (mode == 0) ? 0.0 : 0.5

Parameters:
  x, y  float input point
  mode  int; 1 applies the +1 offset, 0 yields zero confidence

Returns:
  Query (12 bytes) in s0/s1/s2.

Special Cases:
  mode values other than 0/1 take the non-1 offset branch and the non-0
  confidence branch.

Source:
  Tutorial chapter 02 example; Listing at RVA 0x46a4.
```

### 3. Re-check

```text
completeness_score = 72.69   (from 41.54)
undefined_variables = []
has_plate_comment = true
```

The deductions that remain are the tool's Hungarian-notation house style (it wants
`flx`, `nMode`) plus requests for numbered steps and inline comments. Those are
**conventions, not correctness**, so accept them with a reason. What matters is
that every name is semantic and no placeholder remains.

## Separate three kinds of information

| Kind | How to express it |
| --- | --- |
| **Confirmed by the Listing** (offsets, ABI, operations) | State it; the comment may cite the instruction |
| **Supplied by you** (variable names, business meaning) | State it, mark the source as inference, add evidence to `Source` when available |
| **Still unknown** | Keep an evidence name (`unknown_0c`), list it explicitly, do not guess |

## Close-out order, and failure handling

1. Run `analyze_function_completeness`, fix what is fixable, explain the rest.
2. Confirm all seven checks have evidence locations.
3. `save_program()`.
4. Report completion **and the remaining gaps** — do not paper over them.

When something fails:

- Keep the error, the current C, and any changes already made.
- **A script timeout does not mean the script stopped.** Check whether it is still
  running before retrying a write.
- Do not manually nest GhidraScript transactions.
- If saving reports `active transaction`, confirm the task ended and inspect the
  transaction state — do not `close(save=False)` and discard unsaved work.

## If you skip this

- The next reader re-derives every variable meaning, or trusts your inferred names
  as if they were evidence.
- The tool's remaining warnings look like unfinished work rather than documented,
  accepted conventions.
- A later `noreturn` pass re-truncates a repaired range and nobody notices,
  because the audit was not part of the close-out.

## Chapter checklist

- [ ] `analyze_function_completeness` was run before and after
- [ ] The score rose and `undefined_variables` became empty
- [ ] The plate matches the current C/types and separates confirmed/inferred/unknown
- [ ] Every remaining deduction has a stated reason (convention vs real gap)
- [ ] `save_program()` succeeded and the gaps are written down
