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

## The V5 plate: write it for the next reader

```text
One line describing how inputs become outputs.

Algorithm:
  In real business order: data sources, operations, writes back, locks/callbacks.
Parameters:
  Name, type, unit, owning object/output range.
Returns:
  Meaning of the return value and error paths.
Special Cases:
  Real edge conditions; unresolved items or tool residue and their evidence.
Source:
  Pinned official SO identity; key Listing evidence lives in function/instruction comments.
```

Add PRE/EOL comments to key instructions, and keep lanes/rounding trees in vector
expressions. **After a type change, check whether the plate is still accurate.**

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

- [ ] All seven checks point to concrete evidence
- [ ] No unexplained business placeholder names remain in the C; residue is documented
- [ ] The plate matches the current C/types and distinguishes confirmed/inferred/unknown
- [ ] `save_program()` succeeded and the remaining gaps are stated
