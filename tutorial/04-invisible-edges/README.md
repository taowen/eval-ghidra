# 04 — A call edge that is invisible to the tool

**Task**: you need to know which `render` implementation actually runs when
someone calls `drive`, so you can reimplement the right one. There are two:
`GlesRenderer::render` and `NullRenderer::render`.

## What the decompiler shows you

```c
void drive(long *param_1)
{
                    /* WARNING: Could not recover jumptable at 0x00104bb8. Too many branches */
                    /* WARNING: Treating indirect jump as call */
  (**(code **)(*param_1 + 8))();
  return;
}
```

And the obvious way to find callers:

```text
get_function_callers(GlesRenderer::render) -> No callers found
get_function_callers(NullRenderer::render) -> No callers found
```

## What this costs you if you trust it

The tool tells you neither `render` is called. If you believe the caller list:

- You conclude the two `render` functions are dead code and skip them. The
  library's rendering does nothing in your reimplementation.
- Or you assume `drive` calls one of them and pick whichever looks right. You
  have a 50% chance of reimplementing the wrong path.
- You merge the two implementations, since nothing distinguishes them.

The caller list is not lying. The edge it is looking for simply does not take the
form it recognizes.

## Why the caller is empty

This is **missing information**, but a specific kind: the edge exists, it is just
**indirect**. `drive` does not call a named function; it loads a function pointer
out of a table and branches to it. A "who calls this function" query follows
direct references, and there is no direct reference to follow.

The information is in the binary. You have to follow a different chain to see it.

## What the machine actually says

`drive`'s Listing gives you the path:

```asm
; drive
104bb0  ldr x8,[x0]        ; x8 = receiver->vptr        (receiver = x0)
104bb4  ldr x2,[x8, #0x8]  ; x2 = vptr[+0x8]            (slot byte offset 8)
104bb8  br  x2             ; branch to the target
```

So: **receiver -> vptr -> slot +0x8 -> target.** Now look at the references to
each implementation instead of the callers:

```text
get_xrefs_to(GlesRenderer::render @ 0x4ca0):
  From 00100974 [INDIRECTION]
  From 00100aec [DATA]
  From 00108d80 [DATA]      <- the vtable slot
  From Entry Point [EXTERNAL]

get_xrefs_to(NullRenderer::render @ 0x4cc4):
  From 0010098c [INDIRECTION]
  From 00100b28 [DATA]
  From 00108db0 [DATA]      <- the other vtable slot
  From Entry Point [EXTERNAL]
```

There are **no `[CALL]` xrefs**, which is exactly why the caller list was empty.
There are `[DATA]` xrefs from the two vtables. The symbols name them:

```text
_ZTV12GlesRenderer   at RVA 0x8d68   (GlesRenderer vtable)
_ZTV12NullRenderer   at RVA 0x8d98   (NullRenderer vtable)
_ZN12GlesRenderer6renderEPK5Frame    at RVA 0x4ca0
_ZN12NullRenderer6renderEPK5Frame    at RVA 0x4cc4
```

Reading the table memory at RVA `0x8d80` (GlesRenderer vtable + 0x18) yields
8-byte pointers whose values are the two `render` entries. **That `[DATA]` xref
is the missing edge.**

> Record the chain explicitly, per receiver: **receiver -> vptr field -> slot byte
> offset -> target -> ABI**. Do not generalize one receiver's target to the other,
> and do not treat adjacent vtable slots as evidence.

## Using the audit scripts (and their preconditions)

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\AuditAarch64VtableSlotCalls.java",
                  args="8", capture_output=True)
```

**Real result here:**

```text
SUMMARY slot=0x8 candidates=0 calls=0
```

Zero candidates, and that is instructive. This `drive` uses a **tail branch**
(`br x2`), not a call (`blr`), and its receiver is an untyped `long *`. The
script matches a specific shape (typed receiver field + slot call); when the
shape differs it reports zero instead of guessing. Use it where the shape matches
and fall back to the Listing plus `[DATA]` xrefs otherwise.

The callback binder has a similar precondition:

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\BindCallbackCalls.java",
                  args=r"...\tutorial-types\chapter04-vtable.h", capture_output=True)
```

It requires a **computed call** (`blr`), not a computed jump. In this example
`on_frame` ends with a tail branch:

```asm
; on_frame
104bc8  adrp x8, ...
104bcc  ldr  x1,[x8, #0xfe0]   ; load g_callback
104bd0  cbz  x1, 0x104bd8
104bd4  br   x1                ; tail branch, not blr
104bd8  ret
```

So the script rejects it, correctly. **A tool refusing an input shape is a result,
not a failure.** Record the target manually instead of forcing a binding.

## What changes

Once you have the two vtable slots and their targets, you can:

- State that `drive` dispatches through slot +0x8 to whichever implementation the
  object carries.
- Recover both `render` bodies separately, per receiver.
- Mark any still-unresolved indirect target as `[REVIEW]` — **never fill it with
  a generic stub**.

## If you skip this

- You treat "no callers" as "dead code" and delete working functionality.
- You merge two implementations and call the wrong one.
- You force a binding the instruction shape does not support and record a false
  edge.

## Chapter checklist

- [ ] `pwsh -File tutorial/build.ps1 04-invisible-edges` compiles
- [ ] `get_function_callers` is empty for both `render` implementations
- [ ] `get_xrefs_to` shows the `[DATA]` edge from each vtable
- [ ] You can read slot `+0x8` out of `drive`'s Listing
- [ ] You can explain why the audit script reports zero candidates here
