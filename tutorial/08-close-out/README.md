# 08 — Close-out: separate "information I supplied" from "facts still unknown"

Every earlier chapter supplied information or corrected a representation. This
chapter answers an easily overlooked question: **after all that, how does the
next person know what is evidenced, what is my inference, and what is still
unknown?**

Because root cause 2 already showed that some information **is not in the binary
at all** — we supplied it. Without separating it, the next reader will treat our
inference as official fact.

## The seven checks

Go through them one by one; each must point to a concrete evidence location:

1. Function, parameters, business locals, and members have each been checked for
   type/semantic name, and the ABI matches on both call sides; **no unexplained
   `local_*`, `iVar*` business placeholder names**, and display residue is
   documented per the naming rules;
2. The true entry and the full business/cleanup range are verified, and **no
   internal block was mis-created as a function**;
3. **No unexplained `in_xN/unaff_xN/extraout_*`**; tool residue has writer and
   user evidence;
4. Direct callee contracts and business indirect targets are recovered;
   identified logging/runtime boundaries are not expanded recursively;
5. Business-relevant rodata has a known type, bit pattern, and purpose; strings
   are not decrypted for pure logging;
6. Error returns, business exception cleanup, unlocks, and lifetimes are
   explainable; the exception runtime need not be reproduced;
7. The current Ghidra struct/union/local bindings match the native layout, and
   affected C has been re-checked.

Mapping: check 1 -> chapter 03 naming, 2 -> chapter 05 range, 3 -> chapters 02/03
ABI and CALL, 4 -> chapter 04 indirect calls, 5 -> chapter 06 rodata,
6 -> chapter 05 cleanup, 7 -> chapter 01 layout plus the re-decompile rule.

## Worked example: chapter 02's `query_pose` (RVA 0x46a4)

Before close-out, `analyze_function_completeness` reports:

```text
completeness_score = 41.54
undefined_variables = [fVar1, QVar4, fVar2, fVar3]   (all generic names)
has_plate_comment = false
```

### 1. Name the locals

```python
set_variables(function_address="0x46a4", variables={
  "fVar1": {"name": "adjusted_x", "type": "float"},
  "fVar2": {"name": "adjusted_y", "type": "float"},
  "fVar3": {"name": "confidence", "type": "float"},
  "QVar4": {"name": "result",     "type": "Query"}
})
```

The tool reports a partial success (`names_set: 4, failed: 4`) because `fVar1`
and friends are **decompiler display names**, not persistent DB symbols. The
names land in the DB, but the C still shows `fVar*` until those DB locals are
wired to the SSA values. Re-running the type step fixes the display:

```python
set_local_variable_type(function_address="0x46a4", variable_name="result",     new_type="Query")
set_local_variable_type(function_address="0x46a4", variable_name="adjusted_x", new_type="float")
set_local_variable_type(function_address="0x46a4", variable_name="adjusted_y", new_type="float")
```

The C becomes:

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

> **Lesson**: there are two layers. The decompiler prints its own names; the
> database holds the names you set. A rename only shows up in the C once the DB
> symbol is tied to the value the decompiler uses.

### 2. Write the V5 plate

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

The remaining deductions are the tool's Hungarian-notation house style (it wants
`flx`, `nMode`, ...) and a request for numbered algorithm steps and inline
comments. Those are **tool conventions, not correctness**, so they are accepted
with a reason rather than chased. What matters for the tutorial is that
`undefined_variables` is empty and every name is semantic.

## Separate three kinds of information (the point of this chapter)

| Category | How to express it in the plate/C |
| --- | --- |
| **Confirmed by the Listing** (offsets, ABI, operations) | State directly; the comment may cite the instruction |
| **Semantics you supplied** (variable names, business meaning) | State it, but mark its source as inference; add official evidence to Source when available |
| **Still unknown** | **Keep an evidence name** (e.g. `unknown_0c`), list it explicitly, do not guess |

Check 5's "do not decrypt strings for pure logging" and check 4's "do not expand
runtime boundaries recursively" both draw this line: **what is delegated to
standard C++/the runtime, and what must be exact.**

## Close-out order

1. Run `analyze_function_completeness`, handle fixable items, explain accepted ones;
2. Confirm all seven checks have evidence locations;
3. `save_program()`;
4. Report completion — **and report the remaining gaps**; do not paper over them
   with SKIP or "no error observed".

## When an operation fails

- Keep the returned error, the current C/variables, and any changes made;
- **A script timeout does not mean it stopped**: first check whether the task is
  still running, and do not retry a write;
- **Do not manually nest GhidraScript transactions**;
- If saving reports `active transaction`, confirm the task has ended and check
  the transaction state; do not `close(save=False)` and discard unsaved refinement.

## Chapter checklist

- [ ] `analyze_function_completeness` was run before and after
- [ ] The score rose and `undefined_variables` became empty
- [ ] The plate matches the current C/types and distinguishes confirmed/inferred/unknown
- [ ] Remaining deductions are explained (tool convention vs real gap)
- [ ] `save_program()` succeeded and the remaining gaps are stated
