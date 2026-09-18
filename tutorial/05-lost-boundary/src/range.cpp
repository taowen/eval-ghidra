// Chapter 05 — function boundary and exception cleanup.
//
// Built WITH exceptions (build.ps1 omits -fno-exceptions for this chapter), so
// the compiler emits .eh_frame FDEs and LSDA entries. The destructor in Guard
// must run on the exception path, and tail_cleanup is the last thing before the
// return.

#include <cstdint>

struct Guard {
    int* flag;
    explicit Guard(int* f) : flag(f) {}
    ~Guard() { if (flag != nullptr) { *flag = 0; } }
};

extern "C" {

__attribute__((noinline)) void work();
__attribute__((noinline)) void tail_cleanup();
__attribute__((noinline)) void on_error();

// Throwing helper kept in its own function so the range of spanned() contains a
// call that may unwind.
__attribute__((noinline)) void fail() {
    on_error();
}

__attribute__((visibility("default"), noinline))
int spanned(int mode, int* flag) {
    Guard g(flag);
    work();
    if (mode) {
        fail();          // may throw / longjmp; cleanup must still run
        return -1;
    }
    tail_cleanup();
    return *flag;
}

} // extern "C"
