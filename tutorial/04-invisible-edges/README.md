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

The decompiler shows:

```c
void drive(long param_1, long param_2)
{
    (**(code **)(*(long *)param_1 + 8))(param_1, param_2);   // raw table offset
}
```

Problems:
- The call site only shows a table offset; the target is invisible;
- `get_function_callers(GlesRenderer::render)` is empty;
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

## Correction

### 1. List call sites that use a slot with a matching receiver

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\AuditAarch64VtableSlotCalls.java",
                  args="<slot> [receiverField]", capture_output=True)
```

`slot` is the vtable byte offset; the optional `receiverField` limits the
receiver to a field offset.

### 2. Bind ordinary callback call sites

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\BindCallbackCalls.java",
                  args=r"...\tutorial-types\chapter04-vtable.h", capture_output=True)
```

It reads call sites from the type header's `@tutorial_callback_callsite` markers
and binds the actual targets.

### 3. Record the two implementations separately

Record the target RVA of `GlesRenderer::render` and `NullRenderer::render`
separately. Trace via the constructor's table-write instructions or
`get_xrefs_to` on the table address.

### 4. Mark unresolved targets `[REVIEW]`

**Do not fill them with a generic stub.**

## If you skip this

- A virtual function with "no caller" is deleted as dead code, losing the feature;
- The two receivers' targets merge, calling the wrong implementation;
- Guessing from an adjacent vtable slot yields the wrong callee.

## Chapter checklist

- [ ] Both receivers' `render` targets are visible and correct
- [ ] The callback registration site traces to a real function
- [ ] Every indirect call has a full receiver->slot->target->ABI record
- [ ] You can explain why "empty caller" is not dead code
