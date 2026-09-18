# 06 — View distortion: the decompiled C is not numerically equivalent

> **The direct manifestation of root cause 1**: the decompiler's correctness
> criterion is "compiles back to equivalent instructions", not "readable" or
> "bit-for-bit identical". It shows `FMLA` as `a*b+c`, `.2D` as a scalar, and
> `B.PL` as `>=` — those are **the view it gives you**, not the instructions.
> Copy that view and you produce "mathematically equivalent but not bit-exact"
> results.

## Symptom

`src/math.cpp`:

```cpp
float fused(float a, float b, float c) { return a*b + c; }  // FMLA or FMUL+FADD?
float reduce4(const float* v)          { return v[0]+v[1]+v[2]+v[3]; }
int   ge_nan(float a, float b)         { return a >= b; }
float pick(float a, float b, int c)    { return c ? a : b; }
```

Build the same source twice, once with the default `-ffp-contract=fast` and once
with `-ffp-contract=off`, then compare. The decompiler renders both almost
identically:

```c
/* default (fast) build */
float fused(float param_1, float param_2, float param_3)
{
  return param_3 + param_2 * param_1;
}

/* -ffp-contract=off build */
float fused(float param_1, float param_2, float param_3)
{
  return param_1 * param_2 + param_3;
}
```

Both read as "a*b+c", and the only visible difference is operand order. But the
Listings are not the same at all:

```asm
; default: fused -> FMADD (one rounding)
4668: 1f010800  fmadd s0, s0, s1, s2

; -ffp-contract=off: FMUL + FADD (two roundings)
4668: 1e210800  fmul  s0, s0, s1
466c: 1e222800  fadd  s0, s0, s2
```

The other three show more view distortion:

```c
float reduce4(float *param_1)
{
  return *param_1 + param_1[1] + param_1[2] + param_1[3];
}

bool ge_nan(float param_1, float param_2)
{
  return param_2 <= param_1;          /* Ghidra flipped the comparison */
}

undefined4 pick(undefined4 param_1, undefined4 param_2, int param_3)
{
  if (param_3 != 0) {                 /* shown as a branch ... */
    param_2 = param_1;
  }
  return param_2;
}
```

...while the Listing says:

```asm
; ge_nan: ordered GE via cset (false on NaN)
4688: 1e212000  fcmp s0, s1
468c: 1a9fb7e0  cset w0, ge

; pick: a conditional SELECT, not a branch
4694: 7100001f  cmp  w0, #0x0
4698: 1e200c20  fcsel s0, s1, s0, eq

; reduce4: strictly left-to-right association
4670: 2d400400  ldp  s0, s1, [x0]
4674: 1e212800  fadd s0, s0, s1
4678: 2d410801  ldp  s1, s2, [x0, #0x8]
467c: 1e212800  fadd s0, s0, s1
4680: 1e222800  fadd s0, s0, s2
```

Problems:
- `a*b+c` does not reveal whether it is fused or separate; **the C is identical
  while the machine code is not**;
- The reduction appears as a running sum, but you must confirm the association
  order (here strictly left-to-right) rather than assume it;
- `a >= b` was rendered as `b <= a` — same truth value, different text, so the C
  text cannot be copied literally;
- `pick` looks like a branch but is a branchless select.

## Classify: which root cause is this?

**View distortion.** The instructions are the fact; the C is a view. **Go by the
Listing, not the C.**

## Find the evidence: Listing + the handbook's comparison table

| Listing fact | Translation action and check |
| --- | --- |
| FMUL followed by FADD vs FMLA/FMADD | Express non-fused operations separately and disable implicit contraction; use the matching intrinsic where fused. Inspect the real optimized output for unexpected and missing FMA |
| `.2D` vs scalar D, lane moves | Record each lane's input, result, and store offset; keep confirmed vector patterns in the native code — **do not change the operation tree because the C looks scalar** |
| FADDP / multi-step sums | Write the reduction as a parenthesized tree; **do not swap in a mathematically equivalent association order** |
| FCMP / FCCMP followed by a branch | List branches for ordered and unordered separately; **`B.PL` includes unordered and must not be translated unconditionally to `a >= b`** |
| FSQRT, exact rodata, bit select | Check whether an extra library call is generated; store constants by original bit pattern/hex float; a mask is a bit select |
| Integer multiply-add, division, timing units | Record width, signedness, truncation order, and units; implement modular arithmetic with well-defined unsigned bit operations to avoid signed overflow |
| Atomic reads/writes, locks, callbacks | Recover from the actual LDR/LDAR, STR/STLR, RMW, and call boundary; **do not add memory ordering, locks, or change publication timing for safety** |

## Correction

1. **First make Ghidra's types and fields correct, then edit the native translation**;
2. Record against the Listing line by line: fused/non-fused, reduction tree,
   `FCMP` branch, rodata bit patterns, masks;
3. Use `get_function_pcode` and the raw Listing to separate "what the compiler
   generated" from "what the decompiler displays";
4. Inspect the real assembly product:

   ```bash
   llvm-objdump -dr --demangle <object>
   ```

   A single-file compile only proves that translation unit compiles; full APK
   linking, function verification, and device behavior are reported **separately**.

### Reproducing the two builds

`build.ps1` produces the default build. The no-contraction variant is a second
compile of the same source:

```powershell
$ndk = "$env:ANDROID_NDK_HOME\toolchains\llvm\prebuilt\windows-x86_64\bin"
$exe = "$ndk\clang++.exe"
@("--target=aarch64-linux-android29","-O2","-shared","-fPIC","-fno-rtti",
  "-fno-exceptions","-fvisibility=default","-ffp-contract=off",
  "-o","C:/games/eval-ghidra/tutorial/build/06-nocontract.so",
  "C:/games/eval-ghidra/tutorial/06-view-vs-fact/src/math.cpp") |
  Set-Content tutorial/build/06-nocontract.rsp -Encoding ascii
& $exe "@tutorial/build/06-nocontract.rsp"
```

In the translated source, fuse only where the Listing shows `fmadd`/`fmla`, and
disable implicit contraction for the non-fused sites. The two builds above are
the control: identical C, different bits.

## Naming is also checked here

A field name describes the value ultimately written at that offset, not the
adjacent `dVar` name. Matrices must state row/column, transpose, vector
direction, and output layout; quaternions must state xyzw/wxyz, multiplication
order, and signs. Bare `pow`, gamma, normalization, extra null protection, or
"equivalent optimization" **must all have official evidence**.

## If you skip this

- Copying `a*b+c` omits an FMA where fusion is required (or adds one);
- Changing the reduction order makes the last bit differ;
- Translating `B.PL` unconditionally to `a >= b` makes NaN inputs take the wrong branch;
- Scalarizing NEON changes lanes/rounding and affects the final pixels.

## Chapter checklist

- [ ] Source compiles with `pwsh -File tutorial/build.ps1 06-view-vs-fact`
- [ ] You built the `-ffp-contract=off` variant and diffed the two Listings
- [ ] You can point at `fmadd` vs `fmul`+`fadd` and explain the rounding difference
- [ ] You can explain why `ge_nan`'s C reads `b <= a`
- [ ] You can explain why `pick`'s C shows a branch but the machine code is `fcsel`
- [ ] There is a real `llvm-objdump` inspection record
