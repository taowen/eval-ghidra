# 03 — Mis-representation: optimization shattered the structure

> **Root cause 3**: `-O2`'s stack-slot reuse, register allocation, and SSA
> merging turn "one source variable" into several storage locations and live
> ranges, and let unrelated variables share storage. The decompiler tends to
> **merge** shared storage into one variable, so two source things display as
> one. **The information is in the binary; the decompiler merged it wrong —
> this is fixable.**

This is the chapter that comes up most often in the real project.

## Symptom A: one stack slot reused by three objects

`src/stack.cpp`:

```cpp
int reuse_stack(int seed, const char* tag) {
    int result = 0;
    { double m[4] = {...}; use_matrix(m); result += (int)m[0]; }   // stage 1: matrix
    { char buf[32]; int n = snprintf(buf, 32, "%s", tag); use_str(buf); result += n; } // stage 2: string
    { int* p = make_foo(); if (p) { p[0] = result; use_foo(p); result += p[0]; } }      // stage 3: pointer
    return result;
}
```

The real decompiler output:

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
  iVar1 = snprintf(&local_40, 0x20, &DAT_00100650, param_2);   /* same slot! */
  use_str(&local_40);                                          /* same slot! */
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

The same `local_40` is the matrix (`double[4]`), the `snprintf` buffer
(`&local_40`), and the string (`use_str(&local_40)`). The variable list makes the
conflict explicit:

```text
local_40  undefined8       Stack[-0x40]:8    <- matrix word + string buffer
local_38  undefined1[16]   Stack[-0x38]:16   <- overlaps
local_28  undefined8       Stack[-0x28]:8
```

**Renaming it alone, or locking it to `double[]`, corrupts the other lifecycle**
— because those three objects genuinely share the same `Stack[-0x40..]`.
Also note `param_2` is `undefined8`, not `const char*`.

## Symptom B: optimization changes the shape entirely

`src/live.cpp`:

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

The real output is a lesson in a different direction: the decompiler is not
confused, it is **too clever**, because the optimizer already rewrote the code.

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

The real Listing shows why:

```asm
; walk
104890  ldp q1,q0,[x0]        ; load 8 ints at once
104894  add v0.4S,v1.4S,v0.4S ; vector add
104898  addv s0,v0.4S         ; horizontal add
10489c  fmov w0,s0
1048a0  ret
```

The loop and the pointer increment are **gone** — the compiler vectorized them.
There is no old/new pointer variable left to name. This is the counterpart to
symptom A: sometimes the decompiler is wrong, and sometimes the source-level
structure simply no longer exists in the machine code.

`branchy` is folded too: `param_2 << 1` is the optimizer's simplification of the
two branches. To produce a real merge-group symptom you need a case the
optimizer cannot fold; the refine scripts handle it the same way, but this
example shows you must first confirm there is something left to recover.

## Symptom C: an unassigned local after a CALL

`src/call.cpp`:

```cpp
struct Result { int a, b, c, d; };   // 16 bytes
Result produce(int mode);
int consume(int mode) { Result r = produce(mode); return r.a + r.d; }
```

The real decompiler output:

```c
int consume(void)
{
  int iVar1;
  int extraout_var;

  iVar1 = produce();
  return extraout_var + iVar1;
}
```

`extraout_var` looks like an uninitialized local, and the call shows no return
type. The `consume` Listing explains it:

```asm
10487c  bl 0x001049b0          ; call produce
104880  lsr x8, x1, #0x20      ; r.d is the HIGH 32 bits of x1
104884  add w0, w8, w0         ; r.a (w0) + r.d
104888  ldp x29,x30,[sp], #0x10
10488c  ret
```

A 16-byte struct is returned **in x0:x1**, not through x8. `r.a` is `w0`; `r.d`
is the high word of `x1`. The decompiler did not model the return type, so it
exposed the incoming `x1` as `extraout_var`. The fix is to type `produce` as
returning `Result`.

## Classify: which root cause are these?

**Mis-representation.** In A and C, optimization shattered the machine structure
and the decompiler **merged or failed to model it**; the information is present
and fixable. In B, the source structure was removed by the optimizer, so there
is nothing to recover — recognize that before you start renaming. In every case,
read the raw/high P-code first; do not guess by name.

## Find the evidence: read raw/high P-code first

**First rule** (verbatim from the handbook):

> The true raw P-code is `getInstructionAt(site).getPcode()`. The tool's
> `granularity="basic"` is also a high basic-block view and **cannot prove the
> original Sleigh width**.

Use `InspectHighAt` to see a target instruction's raw/high P-code, its output
varnode's `space:offset:size`, all instances of that high variable, merge
groups, def/use, and basic-block predecessors/successors.

It prints `split_eligible` and `same_group_multi_block`. **The latter being true
only means the same group spans multiple blocks; it does not authorize a forced
split** — combine it with predecessors/successors, CALL prototypes, and old
locks.

## Correction

Pick the method by symptom (this table is the core of the chapter):

| Symptom | Method | Check after writing |
| --- | --- | --- |
| One stack slot, multiple uses | Build a union + `RefineStackSlot` | Business reads/writes use the right member |
| Wrong union member | `RefineUnionFacet` (inspect first, then bind one edge) | Derived arithmetic has the right type too |
| Multiple live ranges in one register | `RefineRegisterSlot` | Hit the target group without polluting the same register's other values |
| Post-increment old/new pointer sharing a register | `RefineDynamicLocal` | Old-address read and pointer update still match the Listing |
| One high with multiple merge groups | `RefineHighLocal` | Actually split, with other groups not mis-merged |
| CALL output fragments | Build the output stack region as a struct, then rebind | No `extraout_*` with invented initial values |
| Stable, single-semantic local | `set_variables` | Re-fetch C/variables; check the failed count |

### Stack slot: build a union first, then bind

```text
RefineStackSlot.java entryVA signedStackOffset name unionName expectedBytes
```

The script checks the expected size, then removes overlapping old locals.
**Save the old variable list first**, to confirm you are not overwriting another
still-live object; do not hide an unknown layout behind an oversized `byte[]`.
Use the Listing's `Stack[-...]` offset, **not the `sp + ...` during execution**.

**Re-decompile immediately after binding**, checking business writes/reads and
CALL arguments one by one — the automatic member choice may still be wrong.

### Wrong union member: bind one concrete P-code use edge

```text
RefineUnionFacet.java entryVA siteVA                                # read-only: print ops and edges
RefineUnionFacet.java entryVA siteVA sequenceTime edge union field   # write
```

`edge=-1` is the output; a non-negative number is a zero-based input index. Pick
the **real consumption point** (the input of a time-difference add, a matrix
copy's LOAD/STORE, a CALL argument), not a member at the stack declaration.
`INDIRECT/MULTIEQUAL` are not write targets. **Re-decompile after each point**;
do not batch-guess with an old `sequenceTime`.

### Splitting a merge group: there are guard conditions

```text
RefineHighLocal.java entryVA instructionVA opcode space:offset:size newName [dynamic [obsoleteNamesCsv]]
```

Two guards (explicit in the handbook):
- Before writing, confirm the target high has **multiple forced merge groups**;
  with only one group the **script fails and does not write to the DB**;
- After writing, confirm the returned new high is a **strict subset containing
  only the selected group**.

**Split one group at a time**, re-fetch the C, and check both the split-out group
and the remaining groups — after a split the decompiler may re-merge a remaining
value with another existing semantic name.

### CALL output

Confirm the return ABI and the callee's actual write range, then give the callee
the right return type. For the `produce`/`consume` example, importing `Result`
and setting the prototypes:

```python
set_function_prototype(function_address="0x4840", prototype="Result produce(int mode)")
set_function_prototype(function_address="0x4874", prototype="int consume(int mode)")
```

turns

```c
int consume(void)
{
  int iVar1;
  int extraout_var;
  iVar1 = produce();
  return extraout_var + iVar1;
}
```

into

```c
int consume(int param_1)
{
  Result RVar1;
  RVar1 = produce(param_1);
  return RVar1.d + RVar1.a;
}
```

`extraout_var` is gone and the fields are named. **Do not invent initial values
for `extraout_*`.** If the callee is proven to write the whole region while the
caller SSA still does not express the cross-CALL write, record "which object the
callee writes -> which reads the caller performs" and translate along that memory
data flow.

Note the parameter is still named `param_1` even after the prototype fix:
**type recovery and variable naming are separate write-backs.** Use
`set_variables` to rename it once the data flow confirms its meaning.

## Naming is part of delivery

`local_a8`, `iVar5`, `dVar17`, `param_1` only show how the decompiler numbers or
places values; they are **not** finished names.

1. List every business value and write "source -> operation/use -> consumer",
   verifying against the Listing;
2. Names must include units and direction: only after confirming it is
   "target time minus record time, in microseconds" do you name it
   `prediction_delta_us`; an integer is not automatically a `timestamp`;
3. Locate a stable local by its current name with `set_variables` (`iVar5` is a
   lookup key, not the final name); on failure use the register/dynamic/split
   flow — **you cannot just rename in the native code**;
4. Pure logging/runtime temporaries may stay, but the CFG and call boundary must
   confirm they are not business values.

For business variables whose meaning is still unknown, mark the function
"refinement incomplete" and list the specific variables and missing evidence.
**A high completeness score cannot substitute for readable, correct names in the
final C.**

## If you skip this

- Stack slots: the logging stage reads as matrix data; the C shows one `local_40`
  serving three objects, and the native types drift;
- Registers/SSA: two runtime values are written into one wrong object;
- CALL: you treat `extraout_*` as uninitialized and invent an initial value, or
  you lose the result fields entirely.

## Chapter checklist

- [ ] Source compiles with `pwsh -File tutorial/build.ps1 03-broken-structure`
- [ ] You can point at the shared `Stack[-0x40]` in `reuse_stack`
- [ ] You can explain why `walk` has no pointer variable to recover
- [ ] `consume`'s `extraout_var` becomes `RVar1.d + RVar1.a`
- [ ] You can state which symptom needs a union and which needs a prototype fix
- [ ] Business locals have semantic names, and unresolved items are marked "incomplete"
