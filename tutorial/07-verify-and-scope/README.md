# 07 — Verification and evidence scope: how you know it is really fixed

Every earlier chapter *changes* something. This chapter answers "how do you know
it is right, and what does that 'right' cover".

## The core problem

The biggest trap in refinement is not a wrong edit; it is **mistaking "looks
right" for "is right"**:

- A tool prints `succeeded`, but that only means "it was written";
- A leaf function is bit-exact, but the whole chain is not connected;
- Emulation produces the expected number, but it cannot speak for the device.

So verification must answer two questions: **how to verify**, and **what the
verification does and does not prove**.

## Verification layers: each proves only its own layer

| Layer | Proves | **Cannot** replace |
| --- | --- | --- |
| Direct function differential | Business result under given inputs and dependency contracts | Uncovered branches, dependency implementations, concurrent integration |
| Leaf replay | Independent function replay from a dedicated recording | Live inputs, real scheduling, full rendering |
| Official replay comparison | Same-input/same-query comparison | Actual GPU, compositor, dynamic display |
| Actual drawing | Pose production, initialization, GPU pixels | Live frame select, scan instant, physical scanout |
| On-device acceptance | Real threads, submission, compositor, head movement | Optical scanout not actually inspected |

**A PASS must always be reported with what it covers.** A local PASS cannot fill a
global gap.

## Method A: P-code emulation (and its real limits)

```bash
python3 eval-ghidra.py --help emulate_function
```

**Connecting to the tutorial instance, this tool does not work on AArch64.** Its
implementation requires an x86 register (`ESP`), so every attempt fails with:

```text
Emulation failed: Undefined register: ESP
```

That failure is the lesson, not a detour. The tool's value depends on a language
assumption that does not hold for the target. When you do have a supported
language, it gives:

| You get | You do not get |
| --- | --- |
| Scalar-only register results after a run | AArch64/NEON execution semantics |
| Whether control returns normally | Dependency behavior (e.g. libm) |
| Whether the ABI is modeled as you expect | Threads, GPU, timing |

Two habits, whichever emulator you use:

- Emulation is a **fast cross-check for pure leaves**, never device evidence;
- If the emulator supplies a library function (common for libm), you have not
  verified the real dependency — record that limitation.

## Method B: same-input differential (the real workhorse)

This is what chapter 06's two builds let you do concretely. The two Listings
differ (`fmadd` vs `fmul`+`fadd`), so take the same source expression and compare
the two results bit for bit. `src/differential.c` does exactly that on the host:

```bash
g++ -O2 -std=c++17 -o build/chapter07-differential.exe \
    tutorial/07-verify-and-scope/src/differential.c -lm
./build/chapter07-differential.exe
```

Real output:

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

One ULP apart. This is why "the C looks the same" is not evidence: the
differential compares **bits**, and it fails.

The rules that make a differential meaningful:

- The two sides use **independent fixtures that share no mutable state**;
- Tests bind existing types instead of rewriting a structure list that drifts;
- Dependencies have three execution modes: run the official, replay captured
  interactions, run the translation;
- **Test the parent first, then replace dependencies one by one**;
- Replay must check targets, arguments, and call order **before** supplying
  return values and side effects — **you cannot pour the parent's expected final
  state into the input** (that makes the test always pass while proving nothing).

## A static cross-check that does work here

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\AuditAarch64ResultUse.java",
                  args="<trueEntryVA>", capture_output=True)
```

Checks whether the W0/x8 return result is consumed correctly by the caller,
paired with the chapter 02 ABI fix.

`analyze_function_completeness`: handle fixable items; **explain every accepted
item**, and do not wave it through with a score or "tool limitation".

## If you skip this

- You treat emulation output as device behavior and miss NEON/timing differences;
- You use a local function PASS to claim the whole chain passes;
- You pour expected output into the parent so the test always passes and proves nothing;
- You accept "the C looks equivalent" without a bit-level comparison.

## Chapter checklist

- [ ] You ran `emulate_function` and can explain its `ESP` failure on AArch64
- [ ] You built and ran `differential.c` and saw `bit-identical = NO`
- [ ] You can state, for one PASS, exactly what it covers and what it does not
- [ ] You can explain why "identical C" is not evidence
