// Chapter 06 — operations: the decompiled C is not numerically equivalent.
//
// This file is compiled twice with different floating-point contraction. The
// interesting lesson is not the C text (which looks the same) but the Listing
// (which does not).

#include <cstdint>

extern "C" {

// May fuse to FMLA, or stay FMUL+FADD, depending on the contraction setting.
__attribute__((visibility("default"), noinline))
float fused(float a, float b, float c) {
    return a * b + c;
}

// A four-term sum: the association order is a rounding decision.
__attribute__((visibility("default"), noinline))
float reduce4(const float* v) {
    return v[0] + v[1] + v[2] + v[3];
}

// B.PL / B.MI after FCMP: the unordered branch. The C says `>=`.
__attribute__((visibility("default"), noinline))
int ge_nan(float a, float b) {
    return a >= b;
}

// Bit select: the compiler may use FCMP+CSEL, not a branch.
__attribute__((visibility("default"), noinline))
float pick(float a, float b, int c) {
    return c ? a : b;
}

} // extern "C"
