# 04 — Missing information (indirect edges): Ghidra cannot see the vtable

> **Root cause**: a virtual call goes through a table pointer, so Ghidra does
> not see the edge by default and `get_function_callers` is empty. This is not
> "no caller"; the **indirect edge is simply absent from its direct graph**.
> Treat "no caller" as dead code and delete it, and the feature is gone.

## Symptom

`src/vtable.cpp`:

```cpp
struct IRenderer {
    virtual void initialize(int) = 0;
    virtual void render(const Frame*) = 0;
    virtual ~IRenderer() = default;
};
struct GlesRenderer : IRenderer { ... };
struct NullRenderer : IRenderer { ... };

void drive(IRenderer* r, const Frame* f) { r->render(f); }  // indirect call
void register_cb(void (*cb)(int));
```

The real decompiler output for `drive`:

```c
void drive(long *param_1)
{
                    /* WARNING: Could not recover jumptable at 0x00104bb8. Too many branches */
                    /* WARNING: Treating indirect jump as call */
  (**(code **)(*param_1 + 8))();
  return;
}
```

And the caller query for both implementations:

```text
get_function_callers(GlesRenderer::render) -> No callers found
get_function_callers(NullRenderer::render) -> No callers found
```

Problems:
- The call site shows `*param_1 + 8` with no recovered target and no arguments;
- Both `render` implementations report **no callers**, even though `drive` calls
  them;
- The two implementations' targets are easily merged into one.

## Classify: which root cause is this?

**Missing information (indirect edges)** — the edge is not in the direct
reference graph. But **the target itself is in the binary** (the table stores
the function address), so it **is traceable** — just not via the caller list.

## Find the evidence: record along a fixed chain

The handbook states:

> Record **receiver -> vptr field -> slot byte offset -> target -> ABI**.
> Trace the target from the constructor's table write, relocations, or callback
> registration. An empty `get_function_callers` does not mean no caller.
> Record each implementation per receiver. Adjacent vtable functions are only clues.

The real Listing of `drive` shows the chain's first links:

```asm
; drive
104bb0  ldr x8,[x0]        ; x8 = receiver->vptr   (receiver = x0)
104bb4  ldr x2,[x8, #0x8]  ; x2 = vptr[+0x8]       (slot byte offset 8)
104bb8  br  x2             ; tail-call the target
```

`get_function_callers` is empty, but `get_xrefs_to` is not:

```text
get_xrefs_to(GlesRenderer::render @ 0x4ca0):
  From 00100974 [INDIRECTION]
  From 00100aec [DATA]
  From 00108d80 [DATA]      <- the vtable slot
  From Entry Point [EXTERNAL]

get_xrefs_to(NullRenderer::render @ 0x4cc4):
  From 0010098c [INDIRECTION]
  From 00100b28 [DATA]
  From 00108db0 [DATA]      <- the other vtable slot
  From Entry Point [EXTERNAL]
```

The symbols name the tables directly:

```text
_ZTV12GlesRenderer   at RVA 0x8d68   (GlesRenderer vtable)
_ZTV12NullRenderer   at RVA 0x8d98   (NullRenderer vtable)
_ZN12GlesRenderer6renderEPK5Frame    at RVA 0x4ca0
_ZN12NullRenderer6renderEPK5Frame    at RVA 0x4cc4
```

Reading the table memory at RVA `0x8d80` (GlesRenderer vtable + 0x18, i.e. the
slot region) yields 8-byte pointers whose low word matches the two `render`
entries: that is the slot -> target edge. **The `[DATA]` xref from the vtable
address is the missing edge the caller graph does not contain.**

## Correction

### 1. List call sites that use a slot with a matching receiver

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\AuditAarch64VtableSlotCalls.java",
                  args="8", capture_output=True)
```

`slot` is the vtable byte offset; the optional second argument limits the
receiver to a field offset.

**Real result for this example**:

```text
SUMMARY slot=0x8 candidates=0 calls=0
```

The audit found nothing, and that is a lesson: this `drive` uses a **tail branch**
(`br x2`) rather than a call (`blr`), and its receiver is an untyped `long *`.
The script is written to match receiver-field + slot call patterns; when the
shape differs, it reports zero rather than guessing. Use it where the shape
matches, and fall back to reading the Listing and the `[DATA]` xrefs otherwise.

### 2. Bind ordinary callback call sites

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\BindCallbackCalls.java",
                  args=r"...\tutorial-types\chapter04-vtable.h", capture_output=True)
```

It reads call sites from the type header's `@tutorial_callback_callsite` markers
and binds the actual targets. Precondition: the site must be a **computed call**
(`blr`), not a computed jump. In this example `on_frame` ends with `br x1`:

```asm
; on_frame
104bc8  adrp x8, ...
104bcc  ldr  x1,[x8, #0xfe0]   ; load g_callback
104bd0  cbz  x1, 0x104bd8
104bd4  br   x1                ; tail branch, not a blr
104bd8  ret
```

So the script would reject it. That is correct behavior — the tool has a stated
precondition, and you should not force a binding the instruction shape does not
support. Record the target manually instead.

### 3. Record the two implementations separately

Record the target RVA of `GlesRenderer::render` (0x4ca0) and
`NullRenderer::render` (0x4cc4) separately, tracing each via its own vtable
xrefs (`0x108d80` vs `0x108db0`). Do not generalize one receiver's target to the
other.

### 4. Mark unresolved targets `[REVIEW]`

**Do not fill them with a generic stub.**

## If you skip this

- A virtual function with "no caller" is deleted as dead code, losing the feature;
- The two receivers' targets merge, calling the wrong implementation;
- Guessing from an adjacent vtable slot yields the wrong callee.

## Chapter checklist

- [ ] Source compiles with `pwsh -File tutorial/build.ps1 04-invisible-edges`
- [ ] `get_function_callers` is empty for both `render` implementations
- [ ] `get_xrefs_to` shows the `[DATA]` edge from each vtable
- [ ] You can state the slot (`+0x8`) from `drive`'s Listing
- [ ] You can explain why the audit script reports zero here
