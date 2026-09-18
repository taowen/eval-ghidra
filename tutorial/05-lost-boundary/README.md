# 05 — Mis-representation (boundary): the true entry is not the address you think

> **Root cause**: `getFunctionContaining` succeeding **does not prove the address
> is an entry**. On top of that, analyses such as `noreturn` propagation change
> function boundaries, so a true entry can be truncated and exception cleanup
> lost. Get the boundary wrong and the ABI, locals, and operations all rest on a
> wrong range.

## Symptom

`src/range.cpp` (built **with** exceptions — `build.ps1` drops `-fno-exceptions`
for this chapter):

```cpp
struct Guard {
    int* flag;
    ~Guard() { if (flag != nullptr) { *flag = 0; } }   // must run on the exception path
};

int spanned(int mode, int* flag) {
    Guard g(flag);
    work();
    if (mode) {
        fail();            // may unwind
        return -1;
    }
    tail_cleanup();
    return *flag;
}
```

The real Listing shows the function continues past where Ghidra stopped. Its
reported body is `0010662c - 0010666f`, but the code keeps going:

```asm
; spanned (Ghidra body ends at 0x666f)
1046644  cbz  w20, 0x106658
1046648  bl   0x10ad00            ; fail
104664c  mov  w0, #-0x1
1046650  cbnz x19, 0x106660
1046654  b    0x106664
1046658  bl   0x10ad30            ; tail_cleanup
104665c  ldr  w0, [x19]
1046660  str  wzr, [x19]           ; <- last byte Ghidra includes (0x666f)
1046664  ldp  x20, x19, [sp, #0x10]
1046668  ldp  x29, x30, [sp], #0x20
104666c  ret
1046670  cbz  x19, 0x106678        ; <- outside the reported body
1046674  str  wzr, [x19]           ; exception cleanup: Guard::~Guard()
1046678  bl   0x1069cc            ; _Unwind_Resume
```

Problems:
- Ghidra's body stops at `0x666f`; the exception cleanup (`0x6670..0x667b`) is
  outside the function;
- `get_function_by_address(0x6670)` reports **No function found** — the landing
  pad belongs to no function;
- The destructor cleanup on the exception path is orphaned from `spanned`.

The decompiler output for the truncated body already hints at the problem: the
signature is `undefined spanned(void)` and it reaches the cleanup `*param_2 = 0`
only through a re-synthesized path.

## Classify: which root cause is this?

**Mis-representation (boundary).** The true boundary comes from the FDE; Ghidra's
body expressed it wrongly. It is fixable.

## Find the evidence: FDE/LSDA

The authoritative range is in `.eh_frame`. `llvm-dwarfdump --eh-frame` gives:

```text
00000cd4 00000034 00000024 FDE cie=00000cb4  pc=0000662c...0000667c
```

So the true range is `[0x662c, 0x667c)` — **13 bytes longer** than Ghidra's body.
LSDA (`.gcc_except_table`) gives the landing pad at `0x6670`. AArch64 ranges must
be 4-byte aligned.

## Correction

### 1. Fix the range

```text
RefineFunctionRange.java entryVA exclusiveEndVA
```

Run with the FDE values (Ghidra VAs):

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\RefineFunctionRange.java",
                  args="0x10662c 0x10667c", capture_output=True)
```

The real result:

```text
before=[[0010662c, 0010666f]]
after=[[0010662c, 0010667b]]
```

The script verifies 4-byte alignment, refuses to steal bytes from another
function, refuses to shrink the existing body, disassembles any missing
instructions, and prints the body before and after.

### 2. Batch audit

```text
AuditFunctionRanges.java entryVA endVA [entryVA endVA ...]
```

**Run this after signature import / auto-analysis** — `noreturn` propagation can
remove a previously recovered landing pad from the body. The real result:

```text
PASS 0010662c..0010667c instructions=20/20 body=[[0010662c, 0010667b]]
PASS exact ranges=1; semantic readiness not assessed
```

### 3. Give the function its signature

The range fix does not type anything. Setting
`int spanned(int mode, int *flag)` yields:

```c
int spanned(int mode, int *flag)
{
  int iVar1;
  work();
  if (mode == 0) {
    tail_cleanup();
    iVar1 = *flag;
  } else {
    fail();
    iVar1 = -1;
    if (flag == (int *)0x0) {
      return -1;
    }
  }
  *flag = 0;
  return iVar1;
}
```

The exception-path cleanup `*flag = 0` is now attributed to `spanned`.

### 4. Disjoint body

```text
AuditDisjointFunctionBody.java entryVA startVA endVA [startVA endVA ...]
```

Checks a range split into multiple pieces that still belong to one function.

### 5. Suspicious branches

Read `flowType`/`fallThrough`/`flows` and verify the real conditional jumps. When
the machine CFG has a single successor but the C has an extra else, **keep the
raw/high and structured-C side by side; do not forge machine edges with
FlowOverride**. You may record the real branch condition and write order in the
plate.

## Discipline while the range is unresolved

**You are not required to reproduce the exception runtime** (exception
allocator, RTTI, unwinder need not be refined), but **business exception types,
error codes, cleanup ranges, resource release, and unlock ordering must be
preserved**. This example links the full libunwind; none of it needs refinement —
only the cleanup inside `spanned` matters.

## If you skip this

- Truncated range: the exception cleanup runs outside the function and the
  native translation loses the destructor call, leaking on the throw path;
- A landing pad attributed to no function breaks control-flow recovery;
- Deleting the cleanup branch in the official CFG to "avoid logging" corrupts
  the unlock/release order on the exception path.

## Chapter checklist

- [ ] Source compiles with `pwsh -File tutorial/build.ps1 05-lost-boundary`
- [ ] You can read `pc=0000662c...0000667c` from `llvm-dwarfdump --eh-frame`
- [ ] `RefineFunctionRange` changes the body from `..0010666f` to `..0010667b`
- [ ] `AuditFunctionRanges` reports `20/20` and PASS
- [ ] You can explain why the unwinder code itself needs no refinement
