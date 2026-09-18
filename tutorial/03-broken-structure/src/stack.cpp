// Chapter 03, symptom A — one stack slot reused by three objects.
//
// At -O2 the three stages below share one 32-byte stack region. The decompiler
// tends to merge them into one variable, so a double displays as a pointer and
// a matrix word as a logger field.

#include <cstdint>
#include <cstdio>

extern "C" {

__attribute__((noinline)) void use_matrix(const double* m);
__attribute__((noinline)) void use_str(const char* s);
__attribute__((noinline)) void use_foo(int* p);
__attribute__((noinline)) int* make_foo();

__attribute__((visibility("default"), noinline))
int reuse_stack(int seed, const char* tag) {
    int result = 0;
    {
        double m[4];
        m[0] = static_cast<double>(seed);
        m[1] = static_cast<double>(seed) + 1.0;
        m[2] = static_cast<double>(seed) + 2.0;
        m[3] = static_cast<double>(seed) + 3.0;
        use_matrix(m);
        result += static_cast<int>(m[0]);
    }
    {
        char buf[32];
        int n = snprintf(buf, sizeof(buf), "%s", tag);
        use_str(buf);
        result += n;
    }
    {
        int* p = make_foo();
        if (p != nullptr) {
            p[0] = result;
            use_foo(p);
            result += p[0];
        }
    }
    return result;
}

} // extern "C"
