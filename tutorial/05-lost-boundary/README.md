# 05 — Mis-representation (boundary): the true entry is not the address you think

> **Root cause**: `getFunctionContaining` succeeding **does not prove the address
> is an entry**. On top of that, analyses such as `noreturn` propagation change
> function boundaries, so a true entry can be truncated and exception cleanup
> lost. Get the boundary wrong and the ABI, locals, and operations all rest on a
> wrong range.

## Symptom

`src/range.cpp` (**do not** add `-fno-exceptions`):

```cpp
void cleanup();
void spanned(int mode) {
    Guard g;                                  // destructor must run on the exception path
    work();
    if (mode) throw std::runtime_error("x");  // creates a landing pad
    tail_cleanup();                           // tail cleanup
}
```

After decompilation/analysis:
- The function range is shorter than reality; the tail `tail_cleanup` and the
  return value are truncated;
- The LSDA/landing pad is mis-created as a separate function;
- The destructor/unlock on the exception path vanishes from the C.

## Classify: which root cause is this?

**Mis-representation (boundary).** The true boundary comes from the FDE; the
decompiler/analysis expressed it wrongly. It is fixable.

## Find the evidence: FDE/LSDA

- **FDE** gives the true start/end (`.eh_frame`);
- **LSDA** gives landing pads and cleanup regions;
- Instruction granularity: an AArch64 range must be 4-byte aligned.

## Correction

### 1. Fix the range

```text
RefineFunctionRange.java entryVA exclusiveEndVA
```

The script verifies 4-byte alignment, refuses to steal bytes from another
function, refuses to shrink the existing body, disassembles any missing
instructions, and prints the body before and after.

### 2. Batch audit

```text
AuditFunctionRanges.java entryVA endVA [entryVA endVA ...]
```

**Run this after signature import / auto-analysis** — `noreturn` propagation can
remove a previously recovered landing pad from the body.

### 3. Disjoint body

```text
AuditDisjointFunctionBody.java entryVA startVA endVA [startVA endVA ...]
```

Checks a range split into multiple pieces that still belong to one function.

### 4. Suspicious branches

Read `flowType`/`fallThrough`/`flows` and verify the real conditional jumps. When
the machine CFG has a single successor but the C has an extra else, **keep the
raw/high and structured-C side by side; do not forge machine edges with
FlowOverride**. You may record the real branch condition and write order in the
plate.

## Discipline while the range is unresolved

**You are not required to reproduce the exception runtime** (exception
allocator, RTTI, unwinder need not be refined), but **business exception types,
error codes, cleanup ranges, resource release, and unlock ordering must be
preserved**.

## If you skip this

- Truncated range: tail cleanup and the return value are lost, and the exception
  path leaks resources;
- A landing pad mis-created as its own function breaks control flow;
- Deleting the cleanup branch in the official CFG to "avoid logging" corrupts
  the unlock/release order on the exception path.

## Chapter checklist

- [ ] True entry / exclusive end match the FDE; `AuditFunctionRanges` passes
- [ ] The exception path's destructor/unlock is explainable in the C and not a separate function
- [ ] You can say which resource leaks on an exception if the range is truncated
- [ ] `analyze_function_completeness` range issues are handled, with accepted items explained
