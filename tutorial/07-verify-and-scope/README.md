# 07 — Verification and evidence scope: how you know it is really fixed

Every earlier chapter *changes* something. This chapter answers "how do you know
it is right, and what does that 'right' cover".

## The core problem

The biggest trap in refinement is not a wrong edit; it is **mistaking "looks
right" for "is right"**:

- A tool prints `succeeded`, but that only means "it was written";
- A leaf function is bit-exact, but the whole chain is not connected;
- Emulation produces the expected number, but the emulator provided libm itself.

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

## Method A: P-code emulation (fast, with hard limits)

```bash
python3 eval-ghidra.py --help emulate_function
```

Call only **confirmed true entries**, and verify packed/padded objects, registers,
and the x8 return ABI.

| You get | You do not get |
| --- | --- |
| Scalar-only input -> output | Unimplemented `CALLOTHER` |
| Bit/integer results | AArch64/NEON execution on the device |
| Whether it returns normally | The libm dependency (the **emulator may provide it**) |
| Whether the ABI is modeled correctly | Complex dependency interactions, threads, GPU |

**The emulator providing libm does not mean the official dependency has been
verified.**

## Method B: same-input differential (strong, needs fixtures)

Rules:

- The official and translated sides use **independent fixtures that share no
  mutable state**;
- Tests bind existing types instead of rewriting a structure list that drifts;
- Dependencies have three execution modes: run the official, replay captured
  interactions, run the translation;
- **Test the parent first, then replace dependencies one by one**;
- Replay must check targets, arguments, and call order **before** supplying
  return values and side effects — **you cannot pour the parent's expected final
  state into the input** (that makes the test always pass while proving nothing).

## Static check tools

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
- You pour expected output into the parent so the test always passes and proves nothing.

## Chapter checklist

- [ ] A leaf function returns the expected result under emulation
- [ ] A function with libm/complex dependencies exposes emulation's limits, which you state
- [ ] A same-input differential records "matched scope + uncovered branches"
- [ ] Every PASS carries an evidence-scope statement
