# Reading a decompiler like a reverse engineer

## The actual problem

You have a stripped AArch64 `.so`. You need to reimplement one function in C++ —
maybe because it computes something you must match, or because you are porting it.
So you open Ghidra and read the decompiled C.

Here is the trap. The decompiled C **looks like source code**, so you copy it.
Then your reimplementation compiles, runs, and produces *slightly wrong* results:
a field read from the wrong offset, a parameter taken from the wrong register, a
float rounded once instead of twice. You spend days comparing outputs before
realizing the C you copied was never an accurate description of the function.

**This tutorial is about not falling into that trap.** Every chapter takes one
concrete task, shows exactly how the decompiler misleads you, proves what the
machine actually does, and fixes the decompiler's view so the C can be trusted.

## See it in ten seconds

Here is a real function from this tutorial, and the real decompiler output:

```c
undefined4 pose_z(long param_1)
{
  return *(undefined4 *)(param_1 + 8);
}
```

Now answer: what does this function take, and what does it return?

- Is `param_1` an integer, a pointer to a struct, a pointer to an array?
- What lives at `+8`?
- Why `undefined4` and not `float`?

You cannot answer any of these from the C. The answers exist in the binary; the
decompiler just did not tell you. Here is the same function after this tutorial's
treatment:

```c
float pose_z(Pose *p)
{
  return (p->position).z;
}
```

That transition is the whole subject. The rest of this page explains why the
first form happens, and the chapters show how to produce the second.

## Why the decompiler omits what you need

A decompiler's goal is not "show you the source". It is:

> **Produce C that compiles back to equivalent machine instructions.**

Readability, source types, and original variable names are not part of that goal.
Four consequences follow, and each one is a class of problem this tutorial fixes.

### 1. It is not trying to be readable

If the output compiles, has complete control flow, and preserves the bit
semantics, the decompiler is done. `iVar5`, `undefined8`, one stack slot folded
into one variable — all acceptable to it.

### 2. The information you want was destroyed by compilation

Your `struct Pose`, your field names, your `enum`, your variable names — none of
that survives compilation. The binary has registers, bytes, and offsets. The
decompiler can only *name what it can infer*, so a struct becomes `long`.

**This information is not recoverable by any tool.** It is gone. What you can do
is *supply* it — from the machine instructions — and record it so the next reader
knows what is evidence and what is your inference.

### 3. Optimization rewrites the source's structure

`-O2` inlines functions, reuses stack slots, merges live ranges, and vectorizes
loops. One source variable can become several machine locations; two unrelated
variables can share one slot. The decompiler sees only the rewritten form and
often **merges** things that were separate, or loses a loop entirely.

### 4. C cannot express every machine fact, so the decompiler guesses

On AArch64, pointers and integers share registers; large struct returns go
through `x8`; aggregates get split across registers. C forces a choice, so the
decompiler applies a **default ABI model** — and when that model disagrees with
the actual calling convention, it is wrong.

That is where `in_x8`, `extraout_*`, `unaff_xN`, and bare `param_1` come from.
**Those names are the decompiler saying "I do not know".**

## Classify before you fix

Not everything is fixable the same way. Before touching anything, decide which
class of problem you have:

| What you see | What it means | Can refinement fix it? |
| --- | --- | --- |
| `undefined8`, `long param_1`, fields as `+0x24` | The true type is not in the binary | Not "recover" — **supply it** from the Listing and record it |
| `in_x8`, return typed `void`, a parameter missing or shifted | The ABI default is wrong | **Yes**: build an ABI table from both call sides, fix the prototype |
| One variable carrying two meanings; old and new pointer sharing a name | Optimization merged two things | **Yes**: confirm live ranges, then split or bind |
| A loop or a pointer increment that simply is not there | Optimization removed the structure | **No**: confirm it is gone before hunting for it |
| `a*b+c`, `a>=b`, `.2D` shown as scalar | The high-level view dropped instruction detail | **Yes**: recover the operation from the Listing |

The distinction that matters:

- **Missing information**: the decompiler is not wrong; the fact is not in the
  binary. You supply it and label it.
- **Wrong representation**: the fact *is* in the binary and the decompiler saw
  it. These are the ones the refine scripts fix.

## What "refining" means

When the decompiler is unsure, it **inserts a default assumption** and moves on.
The output looks complete because every gap got filled with a guess.

> **Refinement = replace a default assumption with a fact you verified from the
> Listing, then let the decompiler re-express the function.**

And one rule governs all of it:

> **Writing a fact back is not done. Re-decompiling and checking the C is done.**

A tool reporting `succeeded` means "the annotation was stored", not "the
semantics are now correct". Since you are fighting the decompiler's habit of
guessing, you must look at the regenerated C to see whether it used your fact or
guessed again.

## How each chapter works

Every chapter is built around one real task:

```
1. What you are trying to do         a concrete function you must reimplement
2. What the decompiler shows you     the real C, and where it misleads you
3. What the machine actually does    the Listing / FDE / P-code evidence
4. Why it went wrong                 which class of problem this is
5. How to supply the missing fact    the refine operation, with before/after C
6. What it changes                   and what breaks if you skip it
```

The examples are compiled by us, **so the correct answer is known**. That is the
point: you get to see the gap between what Ghidra printed and what the source
actually was, on a function where you cannot be fooled. Then you apply the same
method to a binary where nobody knows the answer.

| Chapter | The task | How the decompiler misleads you |
| --- | --- | --- |
| [00](00-setup/README.md) | Set up the environment | — |
| [01](01-missing-information/README.md) | Reimplement `pose_z`/`bump` | Types are absent; offsets appear as `+0x24` |
| [02](02-wrong-abi-model/README.md) | Reimplement `make_identity`/`query_pose` | Return typed `void`; a float argument disappears |
| [03](03-broken-structure/README.md) | Reimplement `reuse_stack`/`consume` | One slot serves three objects; the return value is lost |
| [04](04-invisible-edges/README.md) | Trace which `render` runs | The vtable edge is invisible; both targets look uncalled |
| [05](05-lost-boundary/README.md) | Recover `spanned`'s cleanup | The function ends too early; exception cleanup is orphaned |
| [06](06-view-vs-fact/README.md) | Match `fused`'s arithmetic | Identical-looking C hides `fmadd` vs `fmul`+`fadd` |
| [07](07-verify-and-scope/README.md) | Know whether the fix is real | A plausible result is not a verified one |
| [08](08-close-out/README.md) | Hand the function to the next person | Named locals and a plate are part of delivery |

## Three rules that hold everywhere

1. **The machine instructions are the only fact. The C is a view.**
2. **Verify before writing back; re-decompile after writing back.**
3. **Evidence has scope.** One correct function is not a correct program.

## Relation to the real project

The method and tools here come from the ARLauncher reverse-engineering work:
`eval-ghidra.py` drives a live GhidraMCP instance for interactive refinement, and
`ghidra_scripts/` holds the vendor-neutral write-back scripts
(`ImportTypes`, `RefineStackSlot`, `RefineHighLocal`, `RefineFunctionRange`, ...).

## Conventions

- Target **AArch64 Android** (`aarch64-linux-android29-clang++ -O2`). The `-O2`
  is deliberate: the stack-slot reuse and SSA merging chapter 03 confronts only
  appear under optimization.
- Source in `N-xxx/src/`; build output in `build/` (ignored).
- **ELF RVA** for `eval-ghidra.py` parameters; **Ghidra VA** for Java script
  `args`. Do not mix them.
- Tutorial uses its own Ghidra instance:

  ```powershell
  pwsh -File start-tutorial-ghidra.ps1
  $env:GHIDRA_MCP_URL = "http://127.0.0.1:8090"
  ```

## What makes a chapter complete

1. The example compiles under the local NDK;
2. The symptom is real decompiler output, not a paraphrase;
3. You can name which class of problem it is;
4. The refine operation really ran, with before/after C captured;
5. You can say exactly what the fix covers and what it does not.
