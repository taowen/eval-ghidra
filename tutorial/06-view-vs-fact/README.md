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
float reduce(const float* v)           { return v[0]+v[1]+v[2]+v[3]; }
bool  ge_nan(float a, float b)         { return a >= b; }
float pick(float a, float b, bool c)   { return c ? a : b; }
```

Compile twice, once with `-ffp-contract=fast` (the default) and once with
`-ffp-contract=off`, and compare.

The decompiler renders all four "naturally":
- `a*b+c` does not reveal whether it is fused or separate;
- The reduction appears as a running sum, with brackets/association lost;
- `a >= b` looks exactly like `a >= b`;
- The bit-select looks like a branch.

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

- [ ] You can distinguish Listing facts from decompiler display
- [ ] Fused/reduction/unordered branches have explicit counterparts in the translation
- [ ] You can give an input where copying the C differs from the Listing
- [ ] There is a real `llvm-objdump` inspection record
