// Chapter 03, symptom C — unassigned locals after a CALL.
//
// produce() returns a 16-byte struct through a hidden pointer. The caller then
// reads fields from its own stack copy. The decompiler may fail to express the
// cross-CALL write and surface fragment locals like `extraout_*`.

#include <cstdint>

struct Result {
    int a;
    int b;
    int c;
    int d;
};

extern "C" {

__attribute__((visibility("default"), noinline))
Result produce(int mode) {
    Result r{};
    r.a = mode + 1;
    r.b = mode + 2;
    r.c = mode + 3;
    r.d = mode + 4;
    return r;
}

__attribute__((visibility("default"), noinline))
int consume(int mode) {
    Result r = produce(mode);
    return r.a + r.d;
}

} // extern "C"
