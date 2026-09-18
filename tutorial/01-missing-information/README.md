# 01 — Missing information: the type is not in the binary

> **Root cause 2**: the `struct` you wrote, the field names, the array/nested
> layout — all **gone** after compilation. Only registers and bytes remain. The
> decompiler does not know a `Pose` was there, so it can only name it
> `undefined8`. **No tool can conjure this information; you supply it from
> evidence.**

## Symptom

`src/layout.cpp`:

```cpp
struct Vec3 { float x, y, z; };
struct Pose { Vec3 position; float q[4]; };     // 28B
struct Node {
    Node*    next;    // +0x00
    Pose     pose;    // +0x08
    unsigned flags;   // +0x24
    unsigned char kind; // +0x28
};

float pose_z(const Pose* p) { return p->position.z; }
void  bump(Node* n)         { n->flags |= 1u; }
```

The decompiler shows:

```c
float pose_z(undefined8 *param_1) { return *(float *)(param_1 + 1); }
void  bump(long param_1)         { *(uint *)(param_1 + 0x24) |= 1; }
```

Problems:
- What is `param_1 + 1`? It is **not byte offset 1**; it is "the 1st
  `undefined8`", i.e. byte +8;
- `Pose`'s `x/y/z/q` are gone, and field xrefs are empty;
- `Node` is a `long`; nothing shows it is an object.

## Classify: which root cause is this?

**Missing information.** Field names, types, even the fact that this is a
struct, are not in the binary. So you **cannot "recover" it, only "supply" it**.
Refinement's goal is to add the layout you can confirm from the Listing as a
single type header, and to mark clearly which fields are evidenced and which
remain unknown.

## Find the evidence

The Listing has byte-level facts:

```asm
LDR S0, [X0, #8]     ; read a float at byte offset 8
```

**Rule: the decompiler's `p + N` is already scaled by element size; you cannot
copy N directly.** Build the offset table from the Listing's "base + byte
displacement + width".

| Instruction | Base | Byte offset | Width | Conclusion |
| --- | --- | --- | --- | --- |
| `LDR S0,[X0,#8]` | object | +8 | 4B float | `position.z` |
| `LDR W1,[X0,#0x24]` | object | +0x24 | 4B | `flags` |

Also distinguish three kinds of pointer: object base, internal member (nested
struct), and secondary-base pointer.

## Correction

### 1. Write a single type header (`tutorial-types/chapter01-layout.h`)

Convention: keep canonical structs in a standalone `*-layout.h` with **no
`#include`**. Do not build an umbrella header, and do not let a temporary Ghidra
struct become a second source of truth.

```c
typedef struct Vec3 { float x; float y; float z; } Vec3;
typedef struct Pose { Vec3 position; float q[4]; } Pose;
typedef struct Node {
    void*    next;      /* +0x00 */
    Pose     pose;      /* +0x08 */
    unsigned flags;     /* +0x24 */
    unsigned char kind; /* +0x28 */
    char _pad[7];       /* to +0x30 */
} Node;
```

### 2. Pre-check the size before importing

```bash
python3 experiments/ar-glass-lib-3dof/messy/tools/audit-ghidra-type-includes.py \
  tutorial-types/chapter01-layout.h
```

The importer rejects modules over 65536 characters; split a large one into
smaller modules along real dependencies.

### 3. Import

```python
switch_program(program="<chapter>.so")
run_ghidra_script(script_name=r"...\ghidra_scripts\ImportTypes.java",
                  args=r"...\tutorial-types\chapter01-layout.h",
                  timeout_seconds=300, capture_output=True)
```

### 4. **Re-verify actual size and offsets** (critical; parsing is not enough)

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\InspectType.java",
                  args="Pose", capture_output=True)
run_ghidra_script(script_name=r"...\ghidra_scripts\InspectType.java",
                  args="Node", capture_output=True)
```

You must read back: `Pose`=28B, `position.z` at +8; `Node`=0x30. On the native
side, also add `static_assert(sizeof(...))` and `offsetof` assertions.

**Why the re-check matters**: Ghidra's fields can be right while the native side
has already shifted from a missing padding — check both.

### 5. Re-decompile

Confirm the C now shows `Pose *`, `n->flags`, `n->kind`.

## If you skip this

- You treat `param_1 + 1` as "1 byte offset" and write fields in the wrong place;
- Missing padding: Ghidra is right but native is shifted — self-tests can still
  "pass" because every internal access shares the same wrong layout;
- Every function records fields from its own guess, producing several layouts
  that drift apart.

## Chapter checklist

- [ ] `Pose` is 28B, `position.z` at +8; `Node` is 0x30
- [ ] `bump`'s C shows named, typed `flags`/`kind`
- [ ] The pre-check script returns OK for this header
- [ ] You can say what `param_1 + 1` would be misread as without the type
