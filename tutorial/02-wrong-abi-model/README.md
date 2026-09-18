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

The decompiler shows:

```c
void make_identity(undefined8 *in_x8)         // actually returns Mat4!
{
    ...
    *(float *)in_x8 = 1.0f;                   // "parameter" is really return storage
    ...
}

undefined8 query_pose(float param_1, int param_2)  // one float lost, mode shifted
{
    ...
}
```

Problems:
- `make_identity` is typed `void`; its result is written through `in_x8`;
- `query_pose` gets the `float x, float y` / `int mode` register assignment wrong;
- The caller shows a trail of `unaff_*` / `extraout_*`.

## Classify: which root cause is this?

**Mis-modeling.** The real ABI is in the instructions (who writes x8, who reads
D0/D1/W0); Ghidra's default inference just did not use it. So it **is fixable**,
and the basis is the facts on both call sides, not guesswork.

## Find the evidence: fill the ABI table first, then fix the prototype

Iron rule: **do not touch the signature until the table is complete.**

For each input record "how the call site prepares it -> how the callee consumes
it"; for each output record "how the callee writes it -> how the caller reads
it". Include at least: register/stack location, width, pointer base, output
region length, and the evidence instruction.

| Value | How the call site prepares | How the callee consumes | Conclusion |
| --- | --- | --- | --- |
| return `Mat4` | caller passes an x8 pointer | callee writes 64B via x8 | hidden return |
| `x` | placed in S0 | read from S0 | first float |
| `y` | placed in S1 | read from S1 | second float |
| `mode` | placed in W0 | read from W0 | int (**not X1!**) |

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
                  args="<callingConvention>", capture_output=True)
```

It prints each matching function's formal return, `isForcedIndirect`, and each
parameter's storage and `isAutoParameter`.

### 2. Confirm non-trivial return storage

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\InspectReturnStorage.java",
                  args="Mat4 Query", capture_output=True)
```

It reports the result of `getStorageLocations()`, marking `forced_indirect` and
`auto`. Use it to decide whether x8 is used.

### 3. Write the correct prototype and re-import

Write the large-struct return as `Mat4 make_identity(void);` and let the ABI
allocate the return storage. **Do not** change it to
`void make_identity(Mat4 *)` on your own.

### 4. Re-decompile and check both call sides

- Caller arguments match the callee's consumption;
- Return fields correspond to the actual writes;
- There is **no** `in_xN`, integer-as-pointer, or wrong output-buffer length
  caused by a bad prototype.

Still-unresolved business indirect targets are marked `[REVIEW]`, **never filled
with a generic stub**.

## If you skip this

- `make_identity` stays `void` and you treat the return storage as an input;
- `query_pose`'s `mode` is read from the wrong register, shifting every
  subsequent parameter;
- Deleting an "unused" parameter shifts the rest of the registers.

## Chapter checklist

- [ ] `make_identity` is no longer `void`; `in_x8` is gone
- [ ] `query_pose`'s FP/integer arguments land correctly
- [ ] Every parameter in the ABI table has evidence on both prepare/consume sides
- [ ] You can say which subsequent parameters a bad prototype shifts
