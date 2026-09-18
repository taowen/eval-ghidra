# 03 — Optimization rewrote the structure

**Task**: reimplement `reuse_stack` and `consume`. Both are ordinary functions
with ordinary logic. The difficulty is that `-O2` removed or rearranged the
source-level structure before Ghidra ever saw it.

This chapter has two symptoms, and they need **opposite responses**: one is
recoverable, the other is genuinely gone.

## Symptom A — one stack slot, three different objects

### What you are trying to do

The source is three sequential stages that reuse a 32-byte scratch region:

```cpp
int reuse_stack(int seed, const char* tag) {
    int result = 0;
    { double m[4] = {...}; use_matrix(m); result += (int)m[0]; }          // matrix
    { char buf[32]; int n = snprintf(buf, 32, "%s", tag); use_str(buf);   // string
      result += n; }
    { int* p = make_foo(); if (p) { p[0] = result; use_foo(p);            // pointer
      result += p[0]; } }
    return result;
}
```

### What the decompiler shows you

```c
int reuse_stack(int param_1, undefined8 param_2)
{
  int iVar1;
  int *piVar2;
  int iVar3;
  double local_40;
  double local_38;
  double dStack_30;
  double local_28;

  local_40 = (double)param_1;
  local_28 = local_40 + 3.0;
  local_38 = local_40 + 1.0;
  dStack_30 = local_40 + 2.0;
  use_matrix(&local_40);
  iVar3 = (int)local_40;
  iVar1 = snprintf(&local_40, 0x20, &DAT_00100650, param_2);   /* same slot */
  use_str(&local_40);                                          /* same slot */
  iVar1 = iVar1 + iVar3;
  piVar2 = (int *)make_foo();
  if (piVar2 != (int *)0x0) {
    *piVar2 = iVar1;
    use_foo();
    iVar1 = *piVar2 + iVar1;
  }
  return iVar1;
}
```

### What this costs you if you trust it

`local_40` is the first matrix element, then it is the `snprintf` buffer, then it
is the string passed to `use_str`. It is **three different objects** wearing one
name. If you reimplement this literally:

- You declare one `double local_40` and pass `&local_40` as a `char*` buffer.
  It compiles. It aliases a double and a string in one variable.
- Your translated `snprintf` writes into the same storage as your matrix, so the
  matrix's `local_40` is corrupted the moment the string stage runs. The matrix
  and string stages were never meant to overlap.
- The C gives no hint which bytes belong to which object's lifetime.

### Why it is wrong

This is **wrong representation**, and it is caused by optimization. The compiler
saw three non-overlapping lifetimes and gave them the same 32 bytes. The
decompiler sees one shared region and, as it always does, folds it into one
variable. The information is in the binary (the accesses show the widths and
offsets); it was just merged.

### What the machine actually says

```asm
; reuse_stack -- three stages, one sp region
10491c  mov  x0, sp
104920  bl   use_matrix           ; stage 1: matrix at sp
...
1049xx  mov  x0, sp
1049xx  bl   snprintf             ; stage 2: string buffer at sp
...
1049xx  bl   make_foo             ; stage 3: unrelated pointer
```

The variable list makes the overlap explicit:

```text
local_40  undefined8       Stack[-0x40]:8    <- matrix word AND string buffer
local_38  undefined1[16]   Stack[-0x38]:16   <- overlaps
local_28  undefined8       Stack[-0x28]:8
```

### Fix it: give each lifetime its own member

Build a union whose members are the objects that share the region, then bind the
real range. `RefineStackSlot` takes the function entry, the signed `Stack[-...]`
offset from the **Listing** (not the `sp +` you see mid-execution), a name, the
union type, and the expected size:

```text
RefineStackSlot.java entryVA signedStackOffset name unionName expectedBytes
```

**Save the old variable list first** so you can confirm you are not erasing
another object that is still live. Then **re-decompile immediately** and check
every business access and CALL argument — the automatic member choice may still
be wrong, and if it is, bind the exact P-code edge with `RefineUnionFacet`.

Result: the matrix reads and writes land on the matrix member, the string on the
string member, and the native types no longer alias.

## Symptom B — the structure is genuinely gone

### What you are trying to do

The source walks eight integers with a post-incrementing pointer and creates two
values across a branch:

```cpp
int walk(const int* base) {
    const int* p = base; int sum = 0;
    for (int i = 0; i < 8; ++i) { sum += *p++; (void)i; }
    return sum;
}
int branchy(int mode, int a, int b) {
    int v = mode ? a : b;
    if (mode) { v += a; } else { v -= b; }
    return v;
}
```

You expect to recover a pointer variable and two branch values.

### What the decompiler shows you

```c
int walk(undefined8 *param_1)
{
  return (int)*param_1 + (int)param_1[2] +
         (int)((ulong)*param_1 >> 0x20) + (int)((ulong)param_1[2] >> 0x20) +
         (int)param_1[1] + (int)param_1[3] +
         (int)((ulong)param_1[1] >> 0x20) + (int)((ulong)param_1[3] >> 0x20);
}

int branchy(int param_1, int param_2)
{
  int iVar1;
  iVar1 = 0;
  if (param_1 != 0) {
    iVar1 = param_2 << 1;
  }
  return iVar1;
}
```

### Why there is nothing to recover

The Listing is the reason:

```asm
; walk
104890  ldp q1,q0,[x0]        ; load 8 ints in two vector registers
104894  add v0.4S,v1.4S,v0.4S ; vector add
104898  addv s0,v0.4S         ; horizontal sum
10489c  fmov w0,s0
1048a0  ret
```

The loop is **gone**. The compiler vectorized it. There is no pointer variable,
no induction variable, no branch — so there is nothing for a refine script to
name. `branchy` is folded the same way: `param_2 << 1` is the optimizer's
simplification of both branches.

### The lesson

**Confirm something is left before you try to recover it.** A missing loop or
pointer is not a decompiler defect; it is information the optimizer removed. This
is the one class of problem where **refinement cannot help**: there is no fact in
the binary for a script to write back. If you start renaming variables in `walk`,
you will be inventing structure that was never there.

## Symptom C — the return value never arrives

### What you are trying to do

`consume` calls `produce`, gets a 16-byte `Result`, and adds two of its fields:

```cpp
struct Result { int a, b, c, d; };   // 16 bytes
Result produce(int mode);
int consume(int mode) { Result r = produce(mode); return r.a + r.d; }
```

### What the decompiler shows you

```c
int consume(void)
{
  int iVar1;
  int extraout_var;

  iVar1 = produce();
  return extraout_var + iVar1;
}
```

### What this costs you if you trust it

`extraout_var` looks like an uninitialized variable, and `produce()` is shown as
returning a single value. If you translate it, you write
`int a = produce(mode);` and then have nothing to add to it. The two fields `r.a`
and `r.d` are simply absent. You cannot reproduce the function.

### What the machine actually says

```asm
; consume()
10487c  bl   0x001049b0          ; call produce
104880  lsr  x8, x1, #0x20       ; r.d is the HIGH 32 bits of x1
104884  add  w0, w8, w0          ; r.a (w0) + r.d
104888  ldp  x29,x30,[sp], #0x10
10488c  ret
```

A 16-byte struct is returned **in `x0:x1`** — not through `x8`, because it fits in
two registers. `r.a` is `w0`; `r.d` is the high word of `x1`. The decompiler did
not model the return type, so the incoming `x1` surfaced as `extraout_var`.

### Fix it

Import `Result` and give `produce` its return type:

```python
set_function_prototype(function_address="0x4840", prototype="Result produce(int mode)")
set_function_prototype(function_address="0x4874", prototype="int consume(int mode)")
```

```c
int consume(int param_1)
{
  Result RVar1;
  RVar1 = produce(param_1);
  return RVar1.d + RVar1.a;
}
```

`extraout_var` is gone and the fields are named. **Never invent an initial value
for `extraout_*`** — it is an ABI the decompiler failed to model, not an
uninitialized local.

> Note the parameter is still `param_1`: fixing types and naming variables are
> **separate** write-backs. Rename it with `set_variables` once the data flow
> confirms its meaning.

## If you skip this

- Symptom A: your matrix and string stages alias one buffer; the output is subtly
  wrong and every test that shares the wrong layout still passes.
- Symptom B: you invent a loop and pointer that were never in the machine code.
- Symptom C: you lose the return value entirely, or invent an initial value for
  `extraout_*`.

## Chapter checklist

- [ ] `pwsh -File tutorial/build.ps1 03-broken-structure` compiles
- [ ] You can point at the shared `Stack[-0x40]` in `reuse_stack`
- [ ] You can explain why `walk` has no pointer variable to recover
- [ ] `consume`'s `extraout_var` becomes `RVar1.d + RVar1.a`
- [ ] You can say which symptom needs a union and which needs a prototype fix
