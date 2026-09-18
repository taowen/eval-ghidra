// Chapter 07 — a concrete differential counterexample.
//
// The two chapter 06 builds are not numerically identical. This host program
// demonstrates the difference between "one rounding" (FMA) and "two roundings"
// (separate mul + add) for the exact same a*b+c expression. It plays the role of
// the "official vs translated" differential: same inputs, compare every bit.

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cmath>

static uint32_t bits(float f) {
    uint32_t u;
    std::memcpy(&u, &f, sizeof(u));
    return u;
}

// Matches the FMADD listing: one rounding.
static float fused(float a, float b, float c) {
    return std::fma(a, b, c);
}

// Matches the FMUL+FADD listing: two roundings.
static float split(float a, float b, float c) {
    volatile float t = a * b;   // block contraction so the two builds differ
    return t + c;
}

static void compare(const char* label, float a, float b, float c) {
    float f = fused(a, b, c);
    float s = split(a, b, c);
    std::printf("%-8s a=%.9g b=%.9g c=%.9g\n", label, a, b, c);
    std::printf("         fused = %.9g  bits=0x%08x\n", f, bits(f));
    std::printf("         split = %.9g  bits=0x%08x\n", s, bits(s));
    std::printf("         bit-identical = %s\n\n",
                bits(f) == bits(s) ? "yes" : "NO");
}

int main() {
    // Values where a*b rounds and then +c rounds again: differ by one ULP.
    compare("case1", 1.00000012f, 1.00000095f, -1.0f);
    compare("case2", 1.00000012f, 1.00000203f, -1.0f);
    compare("case3", 1.00000012f, 1.00000298f, -1.0f);
    return 0;
}
