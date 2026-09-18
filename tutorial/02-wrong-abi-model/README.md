# 02 — The ABI default is wrong

**Task**: reimplement `make_identity` and `query_pose` so callers can use them
exactly as the library did. You need to know what each function takes and
returns, and how a large struct return is delivered.

## What the decompiler shows you

```c
void make_identity(undefined4 *param_1)          /* 64-byte return! */
{
  *(undefined8 *)(param_1 + 0xd) = 0;
  *param_1 = 0x3f800000;                          /* writes into caller storage */
  param_1[0xf] = 0x3f800000;
  param_1[5] = 0x3f800000;
  param_1[10] = 0x3f800000;
  return;
}

float query_pose(float param_1, int param_2)      /* only two parameters */
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
  fVar1 = (float)query_pose(0x40000000, 0x40400000, 1);   /* three args?! */
  return param_3 + fVar2 + local_50[0] + fVar1;
}
```

## What this costs you if you trust it

Look closely at `caller`. It calls `query_pose` with **three** arguments
(`0x40000000 = 2.0`, `0x40400000 = 3.0`, `1`), but `query_pose` is declared with
**two** parameters. One argument has no name and no home. The decompiler is
telling you it does not know what the second float is.

If you translate this literally:

- You write `float query_pose(float x, int mode)`. The real signature is
  `Query query_pose(float x, float y, int mode)`.
- You conclude `query_pose` returns a `float` (the decompiler's type for it), when
  it really returns a 12-byte `Query`. Every caller now has the wrong type.
- You write `void make_identity(undefined4 *out)` and have callers pass a buffer,
  when the real function has **no arguments** and returns a `Mat4` by value.
  Code written against the official header will not link against your version.

None of this is visible by reading the C. The C is self-consistent; it just
describes a different function.

## Why the ABI is wrong

This is **wrong modeling**. C forces a choice at every type: a value is either an
integer or a pointer or a float. The machine has none of these constraints, so the
decompiler applies default rules for how arguments and returns move — and those
rules do not match this binary.

Two rules were violated here:

1. **A large struct return is not a normal return.** `Mat4` is 64 bytes. On
   AArch64, anything larger than 16 bytes is returned through a hidden pointer in
   **`x8`**, which the caller prepares. The decompiler chose to model that as an
   explicit first parameter instead.
2. **Floating-point and integer arguments use separate register sequences.**
   `float x` and `float y` go in `s0`/`s1`; `int mode` goes in `w0`. The
   decompiler's default signature packed them as if they shared one sequence, so
   the second float fell off.

## What the machine actually says

The call site is the cleanest evidence. From `caller`'s Listing:

```asm
; caller()
1046dc  mov  x8, sp              ; x8 = hidden return storage for make_identity
1046e0  bl   0x00104760          ; call make_identity
1046e4  fmov s0, 0x40000000      ; arg1 = 2.0 -> S0
1046e8  fmov s1, 0x40400000      ; arg2 = 3.0 -> S1
1046ec  mov  w0, #0x1            ; arg3 = 1   -> W0
1046f0  bl   0x00104770          ; call query_pose
```

And the callee confirms the hidden return — `make_identity` only ever touches
`x8`, never `x0`:

```asm
; make_identity()
104680  stur xzr,[x8, #0x34]
104684  str  w9,[x8]
104688  str  w9,[x8, #0x3c]
10468c  stur q0,[x8, #0x14]
...
```

So the facts are:

| Value | How the call site prepares it | How the callee consumes it | Conclusion |
| --- | --- | --- | --- |
| `make_identity` return | `mov x8, sp` first | writes 64B through `x8` | hidden return |
| `x` | `fmov s0, 2.0` | reads `s0` | first float |
| `y` | `fmov s1, 3.0` | reads `s1` | second float |
| `mode` | `mov w0, #1` | compares `w0` | int, **not** `x1` |

> **Fill this table before touching the signature.** Record, for every input,
> "how the call site prepares it -> how the callee consumes it", and for every
> output, "how the callee writes it -> how the caller reads it". A default
> signature is a guess until both sides agree.

## Fix it

The ABI facts above tell you the prototype. Import `Mat4` and `Query`, then set:

```python
set_function_prototype(function_address="0x4678", prototype="Mat4 make_identity(void)")
set_function_prototype(function_address="0x46a4",
                       prototype="Query query_pose(float x, float y, int mode)")
set_function_prototype(function_address="0x46d0", prototype="float caller(void)")
```

## What changes

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

The lost `y` is back; the return type is `Query`; `caller` has no invented
parameters; the arguments match the Listing.

> **Ghidra's return convention**: even with `Mat4 make_identity(void)`, Ghidra
> shows a `Mat4 *__return_storage_ptr__` parameter. That is its way of drawing
> the hidden `x8` return, not a claim that the function takes an argument. On the
> call side it becomes `make_identity(&local_50)`.

## If you skip this

- You write `float query_pose(float, int)`, drop `y`, and every caller you write
  is wrong in a way that still compiles.
- You build `void make_identity(void *)` and cannot link against the real
  declaration.
- You delete a parameter because it "looks unused", and every later register
  shifts by one.

## Chapter checklist

- [ ] `pwsh -File tutorial/build.ps1 02-wrong-abi-model` compiles
- [ ] You can point at the `mov x8, sp` / `fmov s0` / `mov w0` evidence
- [ ] `query_pose` reads back with all three parameters and returns `Query`
- [ ] `caller` has no invented parameters and calls `query_pose(2.0, 3.0, 1)`
- [ ] You can explain why Ghidra shows a `__return_storage_ptr__` parameter
