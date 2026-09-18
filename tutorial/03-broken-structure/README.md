# 03 — Mis-representation: optimization shattered the structure

> **Root cause 3**: `-O2`'s stack-slot reuse, register allocation, and SSA
> merging turn "one source variable" into several storage locations and live
> ranges, and let unrelated variables share storage. The decompiler tends to
> **merge** shared storage into one variable, so two source things display as
> one. **The information is in the binary; the decompiler merged it wrong —
> this is fixable.**

This is the chapter that comes up most often in the real project.

## Symptom A: one stack slot reused by three objects

`src/stack.cpp`:

```cpp
void reuse(float seed, const char* tag) {
    { double m[4] = {seed, seed+1, seed+2, seed+3}; use_matrix(m); }  // stage 1: matrix
    { char buf[32]; snprintf(buf, sizeof buf, "%s", tag); use_str(buf); } // stage 2: string
    { Foo* p = make_foo(); consume(p); }                               // stage 3: pointer
}
```

The decompiler shows a double as a pointer, the matrix's first word as a logger
field, and a time addition as `text + timestamp`.

**Renaming it alone, or locking it to `double[]`, corrupts the other lifecycle**
— because those three objects genuinely share the same `Stack[-...]`.

## Symptom B: old/new pointer share a name; SSA merged them

`src/live.cpp`:

```cpp
int walk(const int* base) {
    const int* p = base; int sum = 0;
    for (int i = 0; i < 8; ++i) { sum += *p++; (void)i; }  // post-increment: both old and new live
    return sum;
}
int branchy(int mode, int a, int b) {
    int v = mode ? a : b;   // merge
    if (mode) v += a;       // two merge groups in one register
    return v;
}
```

The decompiler shows the old/new pointers, and the two merge groups, as a single
variable.

## Symptom C: an unassigned local after a CALL

`src/call.cpp`:

```cpp
struct Result { int a, b, c, d; };
Result produce(int mode);
int consume() { Result r = produce(1); return r.a + r.d; }
```

The caller sprouts `extraout_*`, looking "uninitialized".

## Classify: which root cause are these?

**Mis-representation.** In every case, optimization shattered the machine
structure and the decompiler **merged or named it wrong**. The information is
present and fixable. But you must read the P-code first; do not guess by name.

## Find the evidence: read raw/high P-code first

**First rule** (verbatim from the handbook):

> The true raw P-code is `getInstructionAt(site).getPcode()`. The tool's
> `granularity="basic"` is also a high basic-block view and **cannot prove the
> original Sleigh width**.

Use `InspectHighAt` to see a target instruction's raw/high P-code, its output
varnode's `space:offset:size`, all instances of that high variable, merge
groups, def/use, and basic-block predecessors/successors.

It prints `split_eligible` and `same_group_multi_block`. **The latter being true
only means the same group spans multiple blocks; it does not authorize a forced
split** — combine it with predecessors/successors, CALL prototypes, and old
locks.

## Correction

Pick the method by symptom (this table is the core of the chapter):

| Symptom | Method | Check after writing |
| --- | --- | --- |
| One stack slot, multiple uses | Build a union + `RefineStackSlot` | Business reads/writes use the right member |
| Wrong union member | `RefineUnionFacet` (inspect first, then bind one edge) | Derived arithmetic has the right type too |
| Multiple live ranges in one register | `RefineRegisterSlot` | Hit the target group without polluting the same register's other values |
| Post-increment old/new pointer sharing a register | `RefineDynamicLocal` | Old-address read and pointer update still match the Listing |
| One high with multiple merge groups | `RefineHighLocal` | Actually split, with other groups not mis-merged |
| CALL output fragments | Build the output stack region as a struct, then rebind | No `extraout_*` with invented initial values |
| Stable, single-semantic local | `set_variables` | Re-fetch C/variables; check the failed count |

### Stack slot: build a union first, then bind

```text
RefineStackSlot.java entryVA signedStackOffset name unionName expectedBytes
```

The script checks the expected size, then removes overlapping old locals.
**Save the old variable list first**, to confirm you are not overwriting another
still-live object; do not hide an unknown layout behind an oversized `byte[]`.
Use the Listing's `Stack[-...]` offset, **not the `sp + ...` during execution**.

**Re-decompile immediately after binding**, checking business writes/reads and
CALL arguments one by one — the automatic member choice may still be wrong.

### Wrong union member: bind one concrete P-code use edge

```text
RefineUnionFacet.java entryVA siteVA                                # read-only: print ops and edges
RefineUnionFacet.java entryVA siteVA sequenceTime edge union field   # write
```

`edge=-1` is the output; a non-negative number is a zero-based input index. Pick
the **real consumption point** (the input of a time-difference add, a matrix
copy's LOAD/STORE, a CALL argument), not a member at the stack declaration.
`INDIRECT/MULTIEQUAL` are not write targets. **Re-decompile after each point**;
do not batch-guess with an old `sequenceTime`.

### Splitting a merge group: there are guard conditions

```text
RefineHighLocal.java entryVA instructionVA opcode space:offset:size newName [dynamic [obsoleteNamesCsv]]
```

Two guards (explicit in the handbook):
- Before writing, confirm the target high has **multiple forced merge groups**;
  with only one group the **script fails and does not write to the DB**;
- After writing, confirm the returned new high is a **strict subset containing
  only the selected group**.

**Split one group at a time**, re-fetch the C, and check both the split-out group
and the remaining groups — after a split the decompiler may re-merge a remaining
value with another existing semantic name.

### CALL output

Confirm the output-parameter/hidden-return ABI and the callee's actual STORE
range; build the caller's output stack region as the correct struct, remove the
overlapping fragment locals, and rebind. **Do not invent initial values for
`extraout_*`.** If the callee is proven to write the whole region while the
caller SSA still does not express the cross-CALL write, record "which object the
callee writes -> which reads the caller performs" and translate along that memory
data flow.

## Naming is part of delivery

`local_a8`, `iVar5`, `dVar17`, `param_1` only show how the decompiler numbers or
places values; they are **not** finished names.

1. List every business value and write "source -> operation/use -> consumer",
   verifying against the Listing;
2. Names must include units and direction: only after confirming it is
   "target time minus record time, in microseconds" do you name it
   `prediction_delta_us`; an integer is not automatically a `timestamp`;
3. Locate a stable local by its current name with `set_variables` (`iVar5` is a
   lookup key, not the final name); on failure use the register/dynamic/split
   flow — **you cannot just rename in the native code**;
4. Pure logging/runtime temporaries may stay, but the CFG and call boundary must
   confirm they are not business values.

For business variables whose meaning is still unknown, mark the function
"refinement incomplete" and list the specific variables and missing evidence.
**A high completeness score cannot substitute for readable, correct names in the
final C.**

## If you skip this

- Stack slots: the logging stage corrupts matrix data; a time add becomes `text + timestamp`;
- Registers: the old-address read and pointer update order are reversed;
- SSA: two runtime values are written into one wrong object;
- CALL: you treat `extraout_*` as uninitialized and invent an initial value.

## Chapter checklist

- [ ] All three stages read/write the correct union members, with correct derived types
- [ ] `p++`'s old/new pointers are two names, matching the Listing's order
- [ ] `branchy`'s two merge groups are named separately
- [ ] `consume`'s output stack region is a struct, with no invented `extraout_*` values
- [ ] Business locals have semantic names, and unresolved items are marked "incomplete"
