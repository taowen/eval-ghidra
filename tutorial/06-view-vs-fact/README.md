# 06 — The C looks the same; the machine code does not

**Task**: match the library's arithmetic exactly. Floating-point results must be
bit-for-bit identical, because a one-ULP difference compounds through a render
pipeline into visible artifacts.

## What the decompiler shows you

Build the same source twice — once with the default `-ffp-contract=fast`, once
with `-ffp-contract=off` — and decompile both:

```c
/* default build */
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

Both read as "a*b+c". The only visible difference is operand order, which looks
like a decompiler quirk. Other functions look equally harmless:

```c
float reduce4(float *param_1)
{
  return *param_1 + param_1[1] + param_1[2] + param_1[3];
}

bool ge_nan(float param_1, float param_2)
{
  return param_2 <= param_1;          /* flipped */
}

undefined4 pick(undefined4 param_1, undefined4 param_2, int param_3)
{
  if (param_3 != 0) {                 /* looks like a branch */
    param_2 = param_1;
  }
  return param_2;
}
```

## What this costs you if you trust it

The two `fused` bodies are the trap. You reimplement from one of them:

- You write `return a * b + c;` and it is correct for one build and wrong for the
  other. The C does not tell you which.
- You assume the compiler picks the same rounding you would. It does not: one
  version rounds once (`fmadd`), the other rounds twice (`fmul` + `fadd`).

For `ge_nan` and `pick`, copying the C is also misleading:

- `return param_2 <= param_1;` is a *rewritten* comparison. You cannot tell from
  the C whether the NaN case is handled the way the original did.
- `pick` decompiles as a branch. If you reimplement it as a branch, you introduce
  a control-flow path the original never had.

## Why the C cannot show you

This is **view distortion**. Ghidra's C is a high-level rendering: it chooses
readable operators (`*`, `+`, `<=`) and control structures (`if`) that reproduce
the *value*, but not the *rounding* or the *instruction*. Bit-exactness lives in
the instruction stream, and the C has thrown that away.

The decompiler's contract is "compiles back to equivalent instructions", and
`a*b+c` does compile to either form depending on the compiler flags. So the C is
not wrong; it is simply not precise enough for this task.

## What the machine actually says

The Listings are the fact:

```asm
; fused -- default: one rounding
4668: 1f010800  fmadd s0, s0, s1, s2

; fused -- -ffp-contract=off: two roundings
4668: 1e210800  fmul  s0, s0, s1
466c: 1e222800  fadd  s0, s0, s2
```

```asm
; reduce4 -- strictly left-to-right
4670: 2d400400  ldp  s0, s1, [x0]
4674: 1e212800  fadd s0, s0, s1
4678: 2d410801  ldp  s1, s2, [x0, #0x8]
467c: 1e212800  fadd s0, s0, s1
4680: 1e222800  fadd s0, s0, s2

; ge_nan -- ordered GE, false on NaN
4688: 1e212000  fcmp s0, s1
468c: 1a9fb7e0  cset w0, ge

; pick -- a conditional SELECT, not a branch
4694: 7100001f  cmp  w0, #0x0
4698: 1e200c20  fcsel s0, s1, s0, eq
```

Read off the facts, not the C:

- `fused`: fused in one build, separate in the other. **Same C, different bits.**
- `reduce4`: strictly left-to-right association — do not "simplify" it.
- `ge_nan`: `cset ge` is the ordered predicate; NaN gives false. The C says
  `b <= a`, which happens to match, but you had to check.
- `pick`: `fcsel` is a select; there is no branch.

## Verify it with a differential

Do not take the Listing's word alone. Build both forms on the host and compare
bit patterns (`src/differential.c`):

```bash
g++ -O2 -std=c++17 -o build/chapter07-differential.exe \
    tutorial/07-verify-and-scope/src/differential.c -lm
./build/chapter07-differential.exe
```

```text
case1    a=1.00000012 b=1.00000095 c=-1
         fused = 1.07288372e-06  bits=0x35900001
         split = 1.07288361e-06  bits=0x35900000
         bit-identical = NO
```

One ULP apart. That is the whole point of chapter 07: "the C looks equivalent" is
not evidence. The differential compares bits.

## Fix it

In the native translation:

- Fuse only where the Listing shows `fmadd`/`fmla`, and disable implicit
  contraction for the sites that show `fmul` + `fadd`.
- Write the reduction as the parenthesized tree matching the Listing's order.
- Reproduce the condition predicate exactly (`cset ge`, not a C `>=` you hope
  compiles the same way).
- Use a select for `pick`, not a branch.
- Inspect the real object to confirm your translation produced the instruction you
  intended:

  ```bash
  llvm-objdump -dr --demangle <object>
  ```

  A single-file compile proves only that the translation unit compiles; full
  linking and device behavior are separate claims.

## If you skip this

- You fuse where the original did not (or vice versa) and results differ by one
  ULP, then diverge further through the pipeline.
- You reorder a reduction because "addition is associative" and change the result.
- You translate `ge`/`b.pl` without checking the predicate and get NaN handling
  wrong.
- You turn a `fcsel` into a branch and change the control flow.

## Chapter checklist

- [ ] `pwsh -File tutorial/build.ps1 06-view-vs-fact` compiles
- [ ] You built the `-ffp-contract=off` variant and diffed the two Listings
- [ ] You can point at `fmadd` vs `fmul`+`fadd` and explain the rounding difference
- [ ] You can explain why `ge_nan` reads `b <= a`
- [ ] You can explain why `pick` shows a branch but the machine code is `fcsel`
