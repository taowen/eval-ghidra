# 01 — The type is not in the binary

**Task**: reimplement `pose_z` and `bump` in your own C++ so they behave exactly
like the library. Before you can do that, you need to answer: what does `pose_z`
take, and what does it return?

## What the decompiler shows you

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

## What this costs you if you trust it

This output is not wrong, but it is unanswerable. If you translate it directly,
several plausible readings are all wrong:

- **`param_1` is `long`, so the offset is literal.** You write
  `*(uint32_t *)(p + 8)`. If you ever declare `p` as anything but a byte pointer,
  `p + 8` now means "8 elements forward", not "8 bytes". The C gives you no way to
  know the element size.
- **`+0x28` is a `char`, `+0x24` is a `uint`.** You invent field names like `mode`
  and `state`. The real source called them `kind` and `flags`. Your reviewer, or
  future you, cannot connect your names to anything in the binary.
- **Return type `undefined4`.** You pick `int`. It is actually `float`. It
  compiles, and it silently breeds rounding differences everywhere it is used.

The decompiler did not make a mistake. It reported every byte it saw. What it
could not tell you is the **shape** of the data, because that shape was destroyed
at compile time.

## Why the shape is gone

This is **missing information**. Concretely, the source was:

```cpp
struct Vec3  { float x, y, z; };
struct Pose  { Vec3 position; float q[4]; };     // 28 bytes
struct Node {
    Node*         next;    // +0x00
    Pose          pose;    // +0x08
    uint32_t      flags;   // +0x24
    uint8_t       kind;    // +0x28
};

float pose_z(const Pose* p) { return p->position.z; }
void  bump(Node* n)         { n->flags |= 1u; }   // if kind == 3
```

After compilation: no `struct`, no field names, no `float` — only offsets. No
tool can put them back. **You supply them from the instructions.**

## What the machine actually says

The Listing is the fact. Here it is, from the tutorial instance:

```asm
; pose_z
104750  ldr s0,[x0, #0x8]      ; load a 4-byte float from x0+8
104754  ret

; bump
104758  ldrb w8,[x0, #0x28]    ; 1-byte load at +0x28
10475c  cmp  w8,#0x3
104760  b.ne 0x00104774
104764  ldr  w8,[x0, #0x24]    ; 4-byte load at +0x24
104768  orr  w8,w8,#0x1        ; set bit 0
10476c  str  w8,[x0, #0x24]    ; 4-byte store at +0x24
104770  ret
104774  str  wzr,[x0, #0x24]   ; store 0 at +0x24
104778  ret
```

Read the base + displacement + width, and you get the layout:

| Instruction | Base | Byte offset | Width | Meaning |
| --- | --- | --- | --- | --- |
| `ldr s0,[x0,#0x8]` | `x0` | +0x08 | 4B float | a float field at +8 |
| `ldrb w8,[x0,#0x28]` | `x0` | +0x28 | 1B | a one-byte field |
| `ldr w8,[x0,#0x24]` | `x0` | +0x24 | 4B | a four-byte field |

> **The decompiler's `+N` is already scaled by element size.** `param_1 + 8` in
> the C means byte +8 here only because the decompiler kept a byte form. In other
> functions you will see `param_1[1]` for the same byte offset. **Reconcile with
> the Listing, never with the C text.**

## Fix it: supply the type, then use it

### 1. Write one canonical type header

No `#include`, one header per module. Do not create the type inside Ghidra — then
there are two sources of truth that drift apart.

```c
typedef struct Vec3 { float x; float y; float z; } Vec3;
typedef struct Pose { Vec3 position; float q[4]; } Pose;
typedef struct Node {
    void*         next;  /* +0x00 */
    Pose          pose;  /* +0x08 */
    unsigned      flags; /* +0x24 */
    unsigned char kind;  /* +0x28 */
    char          _pad[7];
} Node;
```

### 2. Pre-check, then import

```bash
python3 experiments/ar-glass-lib-3dof/messy/tools/audit-ghidra-type-includes.py \
  tutorial-types/chapter01-layout.h
```

```python
switch_program(program="01-missing-information.so")
run_ghidra_script(script_name=r"...\ghidra_scripts\ImportTypes.java",
                  args=r"...\tutorial-types\chapter01-layout.h",
                  timeout_seconds=300, capture_output=True)
```

### 3. Verify the imported layout

Parsing succeeding proves nothing. Read the layout back:

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\InspectType.java", args="Pose")
run_ghidra_script(script_name=r"...\ghidra_scripts\InspectType.java", args="Node")
```

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

This matches the source: `Pose` 28B, `Node` 48B, `flags` at +0x24, `kind` at
+0x28.

### 4. Give the function a prototype that uses the type

**This is the step people miss.** Right after the import, the C has not changed:

```c
undefined4 pose_z(long param_1)
{
  return *(undefined4 *)(param_1 + 8);   /* still unreadable */
}
```

The `Pose` type now exists in the Data Type Manager, but nothing told the
decompiler that `param_1` *is* a `Pose *`. Importing a type describes data; it
says nothing about what a function receives. So set the prototype:

```python
set_function_prototype(function_address="0x4750", prototype="float pose_z(Pose *p)")
set_function_prototype(function_address="0x4758", prototype="void bump(Node *n)")
```

## What changes

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

Now the task is doable: you can see the parameter type, the return type, and
every field. **Type import and prototype are two separate write-backs — neither
alone fixes the C.** (Chapter 02 is about the same split from the ABI side: the
layout lives in the type, the call convention lives in the prototype.)

## If you skip this

- You guess field names and invent a wrong `Pose` layout; nothing in the binary
  can later correct you, because there is no name to check against.
- You import the type, see no change in the C, and conclude the tool failed —
  when you simply had not set the prototype.
- Your native struct is missing padding that Ghidra's has: both sides compile,
  every internal test passes, and the struct is still the wrong size.

## Chapter checklist

- [ ] `pwsh -File tutorial/build.ps1 01-missing-information` compiles
- [ ] `Pose` reads back as 28B, `Node` as 48B, offsets matching the source
- [ ] `pose_z` becomes `(p->position).z`; `bump` shows `n->flags`/`n->kind`
- [ ] You can explain why importing the type alone changed nothing
