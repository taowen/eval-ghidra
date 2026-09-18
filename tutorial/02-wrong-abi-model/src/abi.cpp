// Chapter 02 — ABI: hidden return and mixed register sequences.
//
// Mat4 is 64 bytes, larger than 16, so AArch64 returns it through a hidden
// pointer in x8. The decompiler often types such a function as returning void
// and surfaces the hidden storage as `in_x8` / `unaff_x8`.
//
// query_pose mixes float and int arguments. Someone reading only the C
// parameter list might assume "x in X0, y in X1, mode in X2", but AArch64 uses
// separate sequences: floats go in S0/S1, the int lands in W0.

#include <cstdint>

struct Mat4 {
    float m[16];
};

struct Query {
    float x;
    float y;
    float confidence;
};

extern "C" {

// 64-byte return: hidden return pointer in x8.
__attribute__((visibility("default"), noinline))
Mat4 make_identity() {
    Mat4 r{};
    r.m[0] = 1.0f;
    r.m[5] = 1.0f;
    r.m[10] = 1.0f;
    r.m[15] = 1.0f;
    return r;
}

// Mixed FP/int arguments. Also returns a 12-byte aggregate.
__attribute__((visibility("default"), noinline))
Query query_pose(float x, float y, int mode) {
    Query q{};
    q.x = (mode == 1) ? x + 1.0f : x;
    q.y = (mode == 1) ? y + 1.0f : y;
    q.confidence = (mode == 0) ? 0.0f : 0.5f;
    return q;
}

// Caller so the parameter/return mis-modeling propagates to a call site.
__attribute__((visibility("default"), noinline))
float caller() {
    Mat4 m = make_identity();
    Query q = query_pose(2.0f, 3.0f, 1);
    return m.m[0] + q.x + q.y + q.confidence;
}

} // extern "C"
