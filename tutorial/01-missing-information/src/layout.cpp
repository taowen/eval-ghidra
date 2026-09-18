// Chapter 01 — object layout.
//
// The compiled output has no field names or types. The decompiler can only
// say "the 2nd undefined8" and "(param + 0x24)". The only facts available are
// the LDR/STR instructions: base + byte displacement + width.

#include <cstdint>

struct Vec3 {
    float x;
    float y;
    float z;
};

struct Pose {
    Vec3 position;   // +0x00 .. +0x0b
    float q[4];      // +0x0c .. +0x1b
};                   // sizeof == 28, align 4

struct Node {
    Node*    next;   // +0x00
    Pose     pose;   // +0x08  (Vec3 is 12B, then q at +0x14..+0x23)
    uint32_t flags;  // +0x24
    uint8_t  kind;   // +0x28
    // 7 bytes padding to +0x30
};                   // sizeof == 48

extern "C" {

// Reads pose.position.z, which is at byte offset 8.
// The decompiler sees `param_1 + 1` because the pointer element is undefined8.
__attribute__((visibility("default"), noinline))
float pose_z(const Pose* p) {
    return p->position.z;
}

// Writes Node.flags at +0x24 after reading Node.kind at +0x28.
__attribute__((visibility("default"), noinline))
void bump(Node* n) {
    if (n->kind == 3u) {
        n->flags |= 1u;
    } else {
        n->flags = 0u;
    }
}

// Forces the struct sizes to be part of the binary so the tutorial can check
// the recovered layout against the real one.
__attribute__((visibility("default")))
unsigned pose_size() { return static_cast<unsigned>(sizeof(Pose)); }

__attribute__((visibility("default")))
unsigned node_size() { return static_cast<unsigned>(sizeof(Node)); }

__attribute__((visibility("default")))
unsigned pose_z_offset() { return static_cast<unsigned>(offsetof(Pose, position) + offsetof(Vec3, z)); }

__attribute__((visibility("default")))
unsigned node_flags_offset() { return static_cast<unsigned>(offsetof(Node, flags)); }

__attribute__((visibility("default")))
unsigned node_kind_offset() { return static_cast<unsigned>(offsetof(Node, kind)); }

} // extern "C"
