# 02 — Mis-modeling: ABI is inferred, and inference is wrong

> **Root cause 4**: AArch64 uses separate register sequences for integer and
> floating-point arguments, hidden returns live in `x8`, and aggregate arguments
> get split. Ghidra has **default inferences** for these, and when they disagree
> with the actual binary it is wrong — producing `in_x8`, `void`, and shifted
> parameters. **The information is in the binary; the decompiler sees it but
> expresses it wrong — this is fixable.**

## Symptom

`src/abi.cpp`:

```cpp
struct Mat4 { float m[16]; };                         // 64B > 16B -> hidden return in x8
Mat4  make_identity();
struct Query { float x, y, conf; };
Query query_pose(float x, float y, int mode);         // mixed FP/integer registers
void  fill(Mat4* dst);
```

The real decompiler output (`batch_decompile`):

```c
void make_identity(undefined4 *param_1)          /* actually returns Mat4! */
{
  *(undefined8 *)(param_1 + 0xd) = 0;
  *param_1 = 0x3f800000;                          /* writes into the caller's storage */
  param_1[0xf] = 0x3f800000;
  param_1[5] = 0x3f800000;
  param_1[10] = 0x3f800000;
  return;
}

float query_pose(float param_1, int param_2)      /* only 2 params; y is missing! */
{
  float fVar1;
  fVar1 = param_1 + 1.0;
  if (param_2 != 1) {
    fVar1 = param_1;
  }
  return fVar1;
}

float caller(undefined1 param_1 [16], undefined1 param_2 [16], float param_3)
{
  float fVar1;
  float fVar2;
  float local_50 [16];
  make_identity(local_50);
  fVar2 = 3.0;
  fVar1 = (float)query_pose(0x40000000, 0x40400000, 1);
  return param_3 + fVar2 + local_50[0] + fVar1;
}
```

Problems:
- `make_identity` is typed `void` with a phantom first parameter; its real return
  storage is the caller's `local_50`;
- `query_pose` shows only **two** parameters and silently drops `float y`; the
  call site passes three (`0x40000000, 0x40400000, 1`) and returns nothing;
- `caller` invents parameters `param_1[16], param_2[16], param_3` that do not exist;
- The caller's `local_50[16]` is really the hidden `Mat4` return slot.

## Classify: which root cause is this?

**Mis-modeling.** The real ABI is in the instructions (who writes x8, who reads
S0/S1/W0); Ghidra's default inference just did not use it. So it **is fixable**,
and the basis is the facts on both call sides, not guesswork.

## Find the evidence: fill the ABI table first, then fix the prototype

Iron rule: **do not touch the signature until the table is complete.**

For each input record "how the call site prepares it -> how the callee consumes
it"; for each output record "how the callee writes it -> how the caller reads
it". Include at least: register/stack location, width, pointer base, output
region length, and the evidence instruction.

The real `caller` Listing is the call-side evidence:

```asm
; caller()
1046dc  mov x8, sp              ; x8 = hidden return storage for make_identity
1046e0  bl 0x00104760           ; call make_identity
1046e4  fmov s0, 0x40000000     ; x = 2.0    -> S0
1046e8  fmov s1, 0x40400000     ; y = 3.0    -> S1
1046ec  mov  w0, #0x1           ; mode = 1   -> W0
1046f0  bl 0x00104770           ; call query_pose
```

And the callee `make_identity` only ever touches `x8`:

```asm
104680  stur xzr,[x8, #0x34]
104684  str  w9,[x8]
104688  str  w9,[x8, #0x3c]
10468c  stur q0,[x8, #0x14]
...
```

The ABI table:

| Value | How the call site prepares | How the callee consumes | Conclusion |
| --- | --- | --- | --- |
| return `Mat4` | `mov x8, sp` before the call | writes 64B through `x8` | hidden return |
| `x` | `fmov s0, 2.0` | reads `s0`/`s1` | first float |
| `y` | `fmov s1, 3.0` | reads `s1` | second float |
| `mode` | `mov w0, #1` | compares `w0` | int (**not X1!**) |

Key rules (verbatim from the handbook):

- **A `double` in D0 does not mean the next pointer is in X1**; you cannot infer
  registers from the C parameter order. Distinguish ordinary scalars / NEON
  vectors / aggregate arguments / hidden return pointers — from both call sides.
- `void` cannot be kept from the old C: check W0/X0, D0, or the output buffer
  before RET, and whether the caller consumes it;
- **An unused-looking entry parameter must not be deleted**, or the following
  registers shift as a whole.

## Correction

### 1. Inspect the default convention and parameter assignment

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\InspectFunctionAbi.java",
                  args="__cdecl", capture_output=True)
```

It prints each matching function's formal return, `isForcedIndirect`, and each
parameter's storage and `isAutoParameter`. The tutorial program's default
convention is `__cdecl` even on AArch64, and the three target functions are not
registered under a named convention, so this tool is used here to confirm the
program default rather than to enumerate them.

### 2. Confirm non-trivial return storage

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\InspectReturnStorage.java",
                  args="Mat4 Query", capture_output=True)
```

It reports the result of `getStorageLocations()`, marking `forced_indirect` and
`auto`. Use it to decide whether x8 is used.

### 3. Write the correct prototype

Set the prototype to the recovered ABI. `make_identity` takes no arguments and
returns `Mat4` by value; `query_pose` has three arguments:

```python
set_function_prototype(function_address="0x4678", prototype="Mat4 make_identity(void)")
set_function_prototype(function_address="0x46a4",
                       prototype="Query query_pose(float x, float y, int mode)")
set_function_prototype(function_address="0x46d0", prototype="float caller(void)")
```

### 4. Re-decompile and check both call sides

The real C after the fix:

```c
void make_identity(Mat4 *__return_storage_ptr__)
{
  __return_storage_ptr__->m[0] = 1.0;
  __return_storage_ptr__->m[5] = 1.0;
  __return_storage_ptr__->m[10] = 1.0;
  __return_storage_ptr__->m[15] = 1.0;
  ...
}

Query query_pose(float x, float y, int mode)
{
  float fVar1;
  float fVar2;
  float fVar3;
  Query QVar4;
  fVar2 = y + 1.0;
  fVar1 = x + 1.0;
  if (mode != 1) {
    fVar2 = y;
    fVar1 = x;
  }
  fVar3 = 0.0;
  if (mode != 0) {
    fVar3 = 0.5;
  }
  QVar4.y = fVar2;
  QVar4.x = fVar1;
  QVar4.confidence = fVar3;
  return QVar4;
}

float caller(void)
{
  Query QVar1;
  Mat4 local_50;
  make_identity(&local_50);
  QVar1 = query_pose(2.0, 3.0, 1);
  return QVar1.confidence + QVar1.y + local_50.m[0] + QVar1.x;
}
```

Now:
- `make_identity` has no phantom parameter and writes real `Mat4` fields;
- `query_pose` has all three arguments with names and returns a named `Query`;
- `caller` has the correct signature and calls with the right arguments.

> **Ghidra's return convention**: even with `Mat4 make_identity(void)`, Ghidra
> renders the hidden return as an explicit `Mat4 *__return_storage_ptr__`
> parameter. That is its ABI representation, not a claim that the function takes
> an argument. On the call side it becomes `make_identity(&local_50)`. Read it as
> "writes the return into this storage".

Still-unresolved business indirect targets are marked `[REVIEW]`, **never filled
with a generic stub**.

## If you skip this

- `make_identity` stays `void` and you treat the return storage as an input;
- `query_pose` loses a parameter and returns the wrong type, so every caller is
  translated wrong;
- Deleting an "unused" parameter shifts the rest of the registers.

## Chapter checklist

- [ ] Source compiles with `pwsh -File tutorial/build.ps1 02-wrong-abi-model`
- [ ] `query_pose` shows `float x, float y, int mode` and returns `Query`
- [ ] `caller` no longer has invented parameters and calls with `(2.0, 3.0, 1)`
- [ ] Every parameter in the ABI table has evidence on both prepare/consume sides
- [ ] You can explain why Ghidra shows a `__return_storage_ptr__` parameter
