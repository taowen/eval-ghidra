# 05 — The function ends before it is over

**Task**: reimplement `spanned`, including the cleanup that runs when `fail()`
unwinds. If you miss the exception path, your version leaks or misbehaves exactly
when something goes wrong.

## What the decompiler shows you

Ghidra reports the function's body as `0010662c - 0010666f`. Inside that range,
the C is readable enough:

```c
undefined4 spanned(int param_1, undefined4 *param_2)
{
  undefined4 uVar1;
  work();
  if (param_1 == 0) {
    tail_cleanup();
    uVar1 = *param_2;
  } else {
    fail();
    uVar1 = 0xffffffff;
    if (param_2 == (undefined4 *)0x0) {
      return 0xffffffff;
    }
  }
  *param_2 = 0;
  return uVar1;
}
```

## What this costs you if you trust it

The source was:

```cpp
struct Guard {
    int* flag;
    ~Guard() { if (flag != nullptr) { *flag = 0; } }   // MUST run when unwinding
};

int spanned(int mode, int* flag) {
    Guard g(flag);
    work();
    if (mode) { fail(); return -1; }   // fail() may unwind -> Guard::~Guard() runs
    tail_cleanup();
    return *flag;
}
```

The cleanup `*flag = 0` is the destructor. It has to run **twice**: on the normal
return path, and when `fail()` unwinds. The decompiler only shows it on a path it
reconstructed inside the truncated range. If you translate from this view:

- You write the cleanup once, inline on the normal path.
- When your `fail()` throws, no destructor runs, `*flag` is never cleared, and a
  lock or a flag stays set. The bug appears only under exceptions — you may never
  hit it in testing.

The decompiler did not hide this on purpose. The code that handles it lives
**outside the range it reported as the function**.

## Why the range is wrong

This is **wrong representation (boundary)**. The true extent of a function is
recorded in the ELF `.eh_frame` FDE, and that is authoritative. Ghidra's
recovered body stopped early, cutting off the exception landing pad.

## What the machine actually says

The Listing continues past where Ghidra stopped:

```asm
; spanned -- Ghidra's body ends at 0x666f, but the code does not
1046660  str  wzr, [x19]           ; last byte Ghidra includes (0x666f)
1046664  ldp  x20, x19, [sp, #0x10]
1046668  ldp  x29, x30, [sp], #0x20
104666c  ret
1046670  cbz  x19, 0x106678        ; <- outside the reported body
1046674  str  wzr, [x19]           ; exception cleanup: Guard::~Guard()
1046678  bl   0x1069cc             ; _Unwind_Resume
```

And querying the orphan:

```text
get_function_by_address(0x6670) -> No function found
```

The FDE confirms the real boundary. `llvm-dwarfdump --eh-frame`:

```text
00000cd4 00000034 00000024 FDE cie=00000cb4  pc=0000662c...0000667c
```

**True range `[0x662c, 0x667c)` — 13 bytes longer** than Ghidra's body. The LSDA
(`.gcc_except_table`) gives the landing pad at `0x6670`.

## Fix it

Give the range the FDE values (Ghidra VAs):

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\RefineFunctionRange.java",
                  args="0x10662c 0x10667c", capture_output=True)
```

```text
before=[[0010662c, 0010666f]]
after=[[0010662c, 0010667b]]
```

The script refuses to shrink a body, refuses to steal bytes from another function,
and disassembles any missing instructions before changing anything.

Audit it, **after** any signature import or auto-analysis — `noreturn`
propagation can strip a recovered landing pad again:

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\AuditFunctionRanges.java",
                  args="0x10662c 0x10667c", capture_output=True)
```

```text
PASS 0010662c..0010667c instructions=20/20 body=[[0010662c, 0010667b]]
PASS exact ranges=1; semantic readiness not assessed
```

Then type it (the range fix does not touch the signature):

```python
set_function_prototype(function_address="0x662c", prototype="int spanned(int mode, int *flag)")
```

## What changes

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

The cleanup is now attributed to `spanned`, and the landing pad is inside the
function instead of orphaned.

> **You do not need to refine the unwinder.** This binary links all of libunwind.
> None of `_Unwind_RaiseException`, `unw_step`, etc. is business code. What must
> be exact is the **business cleanup inside `spanned`** — the `*flag = 0` — and
> the order in which it runs.

## If you skip this

- The exception-path cleanup is missing, so a lock or flag stays set only when
  something throws.
- You reimplement the destructor call on the normal path and assume that covers it.
- A later `noreturn` analysis silently re-truncates the body and you do not
  notice, because you ran the audit before the analysis instead of after.

## Chapter checklist

- [ ] `pwsh -File tutorial/build.ps1 05-lost-boundary` compiles
- [ ] You can read `pc=0000662c...0000667c` from `llvm-dwarfdump --eh-frame`
- [ ] `RefineFunctionRange` moves the body from `..0010666f` to `..0010667b`
- [ ] `AuditFunctionRanges` reports `20/20` PASS
- [ ] You can explain why the linked unwinder needs no refinement
