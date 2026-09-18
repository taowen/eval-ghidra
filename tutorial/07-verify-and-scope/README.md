# 07 — A result that looks right is not a verified result

**Task**: you have refined a function. Before you trust your reimplementation, you
need to answer two questions: *did it actually become correct?* and *how much of
the program does "correct" cover?*

## What the trap looks like

- The refine tool printed `succeeded`. That only means the annotation was stored.
- You emulated a leaf and got the number you expected. The emulator may have
  supplied the dependency for you.
- Your reimplementation matches the original on the cases you tried. The cases you
  did not try are where it differs.

None of these is verification. This chapter is about making the difference
between "looks right" and "is right" concrete.

## What the tool can and cannot do here

```bash
python3 eval-ghidra.py --help emulate_function
```

On this tutorial's AArch64 target, `emulate_function` **does not work at all**.
Its implementation requires the x86 register `ESP`, so every call fails:

```text
Emulation failed: Undefined register: ESP
```

That failure is the first lesson: a tool's usefulness depends on an assumption
about the input. When the assumption does not hold, the tool fails — loudly, which
is the good outcome. Do not mistake "the tool ran" for "the target was verified".

When you do have a supported language, emulation gives you:

| You get | You do not get |
| --- | --- |
| Register results for scalar-only code | AArch64/NEON execution semantics |
| Whether control returns normally | Real dependency behavior (libm, syscalls) |
| Whether the ABI is modeled as you expect | Threads, GPU, timing |

If the emulator provides a library function for you, you have **not** verified the
real dependency. Record that limitation instead of accepting the result.

## What actually verifies a computation

Chapter 06 gave you the perfect setup: two builds whose C is almost identical but
whose machine code differs. So verify the way the real project does — a
**same-input differential that compares bits**.

`src/differential.c` takes the exact `a*b+c` expression and computes it two ways:
one rounding (FMA) and two roundings (mul then add). Same inputs, compare every
bit.

```bash
g++ -O2 -std=c++17 -o build/chapter07-differential.exe \
    tutorial/07-verify-and-scope/src/differential.c -lm
./build/chapter07-differential.exe
```

```text
case1    a=1.00000012 b=1.00000095 c=-1
         fused = 1.07288372e-06  bits=0x35900001
         split = 1.07288361e-06  bits=0x35900000
         bit-identical = NO

case2    a=1.00000012 b=1.00000203 c=-1
         fused = 2.14576744e-06  bits=0x36100001
         split = 2.14576721e-06  bits=0x36100000
         bit-identical = NO
```

**The differential fails**, and that is the answer you needed. "The C is
equivalent" was never evidence; the bit comparison is.

### Rules that make a differential meaningful

These are the rules the real project uses, and they exist because each one has
been violated at some point:

- The official and translated sides run from **independent fixtures that share no
  mutable state**.
- Tests bind the **existing types** instead of re-declaring a structure list that
  drifts from the real one.
- Dependencies run in one of three modes: execute the official implementation,
  replay a captured interaction, or execute your translation. Which mode was used
  is part of the result.
- **Test the parent first, then replace its dependencies one at a time.**
- Replay must check targets, arguments, and call order **before** producing return
  values and side effects. **You may not pour the parent's expected final state
  into the input** — that makes the test pass while verifying nothing.

## A static cross-check that works on any target

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\AuditAarch64ResultUse.java",
                  args="<trueEntryVA>", capture_output=True)
```

Checks whether a W0/x8 return value is actually consumed by its caller, which
pairs with the chapter 02 ABI fix.

`analyze_function_completeness`: fix what it can, and **explain every item you
accept** — never wave it through because a score is high or because "the tool has
limitations".

## Evidence has scope

No verification proves more than its own layer.

| What you verified | What it proves | What it does **not** prove |
| --- | --- | --- |
| One function, given inputs | That function's behavior for those inputs | Uncovered branches, its dependencies |
| A leaf replay | That leaf, from a recording | Live inputs, scheduling |
| Official replay comparison | Same-input equivalence | GPU, compositor, display |
| Actual drawing | Pose/GPU output | Frame selection, physical scanout |
| On-device run | Threads, submission, display | Optical scanout not inspected |

**Every PASS must be reported with what it covers.** A local PASS cannot stand in
for a global one.

## If you skip this

- You accept a passed test that never exercised the failing branch.
- You use a local function PASS to claim the whole chain works.
- You pour expected output into the parent and the test always passes.
- You accept "the C looks equivalent" without ever comparing bits.

## Chapter checklist

- [ ] You ran `emulate_function` and can explain its `ESP` failure on AArch64
- [ ] You built and ran `differential.c` and saw `bit-identical = NO`
- [ ] For one PASS, you can state its exact coverage and its gaps
- [ ] You can explain why "identical C" is not evidence
