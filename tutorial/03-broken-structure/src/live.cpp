// Chapter 03, symptom B — register live ranges and SSA merging.
//
// walk() post-increments a pointer, so the old pointer, the new pointer and the
// program counter can share a register. branchy() creates two values that can
// occupy the same register across a branch, which the decompiler may merge into
// a single merge group.

#include <cstdint>

extern "C" {

__attribute__((visibility("default"), noinline))
int walk(const int* base) {
    const int* p = base;
    int sum = 0;
    for (int i = 0; i < 8; ++i) {
        sum += *p++;
        (void)i;
    }
    return sum;
}

__attribute__((visibility("default"), noinline))
int branchy(int mode, int a, int b) {
    int v = mode ? a : b;
    if (mode) {
        v += a;
    } else {
        v -= b;
    }
    return v;
}

} // extern "C"
