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

The real decompiler output (`batch_decompile(functions='pose_z,bump')`):

```c
undefined4 pose_z(long param_1)
{
  return *(undefined4 *)(param_1 + 8);
}

void bump(long param_1)
{
  if (*(char *)(param_1 + 0x28) == '\x03') {
    *(uint *)(param_1 + 0x24) = *(uint *)(param_1 + 0x24) | 1;
    return;
  }
  *(undefined4 *)(param_1 + 0x24) = 0;
  return;
}
```

Problems:
- `param_1` is a bare `long`; nothing says it is a `Pose *` or `Node *`;
- `bump` reads `+0x28` and `+0x24` as char/uint with no names;
- `Pose`'s `x/y/z/q` are gone, and field xrefs are empty;
- Return type is `undefined4`, not `float`.

## Classify: which root cause is this?

**Missing information.** Field names, types, even the fact that this is a
struct, are not in the binary. So you **cannot "recover" it, only "supply" it**.
Refinement's goal is to add the layout you can confirm from the Listing as a
single type header, and to mark clearly which fields are evidenced and which
remain unknown.

## Find the evidence

The Listing has byte-level facts. The real Listing captured from the tutorial
instance:

```asm
; pose_z
104750  ldr s0,[x0, #0x8]
104754  ret

; bump
104758  ldrb w8,[x0, #0x28]
10475c  cmp w8,#0x3
104760  b.ne 0x00104774
104764  ldr w8,[x0, #0x24]
104768  orr w8,w8,#0x1
10476c  str w8,[x0, #0x24]
104770  ret
104774  str wzr,[x0, #0x24]
104778  ret
```

**Rule: the decompiler's `p + N` is already scaled by element size; you cannot
copy N directly.** Build the offset table from the Listing's "base + byte
displacement + width".

| Instruction | Base | Byte offset | Width | Conclusion |
| --- | --- | --- | --- | --- |
| `ldr s0,[x0,#0x8]` | `x0` | +0x08 | 4B float | `Pose.position.z` |
| `ldrb w8,[x0,#0x28]` | `x0` | +0x28 | 1B | `Node.kind` |
| `ldr w8,[x0,#0x24]` | `x0` | +0x24 | 4B | `Node.flags` |

Also distinguish three kinds of pointer: object base, internal member (nested
struct), and secondary-base pointer.

> **Note on `p + N` scaling**: with the default `undefined8 *` parameter,
> `pose_z`'s `param_1 + 8` is already a byte offset here only because the
> decompiler chose to keep the byte form; in other functions you will see
> `param_1[1]` or `(param_1 + 1)` meaning byte +8. Always reconcile with the
> Listing, never with the C text.

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

The real `InspectType` output after import:

```text
/Pose size=28 align=4
0000 size=12 position                         Vec3
000c size=16 q                                float[4]

/Node size=48 align=8
0000 size=8 next                              void *
0008 size=28 pose                             Pose
0024 size=4 flags                             uint
0028 size=1 kind                              uchar
0029 size=7 _pad                              char[7]
```

These match the source exactly (`Pose`=28, `Node`=48, `pose` at +8, `flags` at
+0x24, `kind` at +0x28). On the native side, also add `static_assert(sizeof(...))`
and `offsetof` assertions.

**Why the re-check matters**: Ghidra's fields can be right while the native side
has already shifted from a missing padding — check both.

### 5. **Give the function a prototype that uses the type**

This is the step that surprises people. Right after importing, the C has **not
changed**:

```c
undefined4 pose_z(long param_1)
{
  return *(undefined4 *)(param_1 + 8);   /* still unreadable */
}
```

The field types exist in the Data Type Manager, but the decompiler does not know
`param_1` points at a `Pose`. Importing a type describes data; it does not tell
the decompiler what the function receives. So set the prototype:

```python
set_function_prototype(function_address="0x4750", prototype="float pose_z(Pose *p)")
set_function_prototype(function_address="0x4758", prototype="void bump(Node *n)")
```

Now the C becomes readable:

```c
float pose_z(Pose *p)
{
  return (p->position).z;
}

void bump(Node *n)
{
  if (n->kind == '\x03') {
    n->flags = n->flags | 1;
    return;
  }
  n->flags = 0;
  return;
}
```

> **Lesson**: type import and prototype are two separate write-backs. Neither
> alone fixes the C. This is the same split chapter 02 is about, seen from the
> layout side: the layout lives in the type, the ABI lives in the prototype.

## If you skip this

- You treat `param_1 + 0x28` as "some char at an offset" and never learn it is `Node.kind`;
- Importing types but not fixing the prototype leaves the C unchanged — a common
  false sense of progress;
- Missing padding: Ghidra is right but native is shifted — self-tests can still
  "pass" because every internal access shares the same wrong layout;
- Every function records fields from its own guess, producing several layouts
  that drift apart.

## Chapter checklist

- [ ] Source compiles with `pwsh -File tutorial/build.ps1 01-missing-information`
- [ ] `Pose` reads back as 28B, `Node` as 48B, with matching offsets
- [ ] `pose_z`'s C becomes `(p->position).z`, `bump`'s shows `n->flags`/`n->kind`
- [ ] You can explain why importing the type alone did not change the C
