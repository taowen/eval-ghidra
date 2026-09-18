# Why Ghidra's output is so hard to read, and how to make it trustworthy

## Start with a strange observation

Drop a compiled C++ binary into Ghidra and you get something like this:

```c
void FUN_00101234(long param_1, undefined8 param_2)
{
  long lVar1;
  undefined8 uVar2;
  uVar2 = *(undefined8 *)(param_1 + 0x18);
  lVar1 = iVar5 + 8;
  if (in_x8 != 0) { ... }
}
```

This C compiles. The logic is not wrong. But you cannot tell:

- What object is `param_1`?
- What field is `+0x18`?
- Which variable is `iVar5`, and why is it related to `lVar1`?
- Where did `in_x8` come from, and why is it a parameter?
- Is `undefined8` a number, a pointer, or a struct?

**This is not Ghidra being broken.** This tutorial is about the **root causes**
behind that output. Once you understand them, you know not only *how* to fix it,
but *what can be fixed*, *what you must supply yourself*, and *why the result is
trustworthy*.

---

## Root cause: the decompiler is solving a different problem

> **A decompiler's goal is to produce C that compiles back to equivalent
> instructions, not C that recovers the source the author wrote.**

Those sound close. They are very far apart. Four causes follow.

### Root cause 1: readability is not part of its correctness criterion

As long as the generated C compiles, the control flow is complete, and the bit
semantics are right, the decompiler considers itself done. Variables named
`iVar5`, types called `undefined8`, one stack slot collapsed into one
variable — all **legal** to it. Readability is not on its acceptance list.

### Root cause 2: compilation threw away source-level information, and the decompiler cannot invent it

Your `struct Pose`, your `enum Kind`, your meaningful variable names, your
boundaries — all **gone** after compilation. Only registers and bytes remain.
The decompiler's job is to *give those bytes names*. It does not know a `Pose`
was there, so it can only name it `undefined8`.

**This information is not in the binary. No tool can conjure it. Only a reverse
engineer can supply it, using domain knowledge plus evidence.**

### Root cause 3: optimization shatters the source's structure

`-O2` performs inlining, register allocation, **stack-slot reuse**, SSA merging,
and tail calls. As a result:

- One source variable can become several storage locations and several live ranges;
- Two unrelated variables can **share the same stack slot or register**.

The decompiler already sees the shattered form. It tends to **merge** shared
storage into one variable (the mysterious *merge group*). In the source those
were plainly **two** things.

### Root cause 4: C's type system cannot hold all the machine's facts, and its ABI modeling is heuristic

- Pointers and integers **share the same register** on AArch64, but C forces you to pick one;
- AArch64 hidden returns live in `x8`, aggregate arguments get split, non-trivial C++ returns have their own convention — Ghidra has default inferences, and when they disagree with the actual binary, it is wrong.

So it uses a set of placeholders to say "I am not sure": `undefined8`, `in_x8`,
`unaff_xN`, `extraout_*`, `param_1`. **Those ugly names are the decompiler
admitting it does not know.**

---

## Classify the symptom to treat the cause

"Hard to read" has several different causes, and each needs a different
response:

| Symptom | Root cause | Nature | Fixable by refinement? |
| --- | --- | --- | --- |
| `iVar5`, `undefined8`, `param_1` | Source information is gone | **Missing information** | Not "recoverable"; you **supply it from evidence** and record it |
| One variable with two meanings, old/new pointer sharing a name | Optimization merged or reused storage | **Mis-representation** | Yes: after confirming live ranges, **split/bind** |
| `in_x8`, `extraout_*`, shifted parameters, return typed as `void` | ABI heuristic disagrees with fact | **Mis-modeling** | Yes: build a two-sided ABI evidence table, then **fix the prototype** |
| `p + N` indices, fields as `undefined4[]` | No type information | **Missing information** | Yes: after confirming layout, **import a single type header** |
| `a*b+c`, `a>=b`, `.2D` shown as scalar | The decompiler's high-level view loses instruction detail | **View distortion** | Yes: recover the operation from the **Listing** |

**Memorize this table. It is the heart of the tutorial.**

- **Missing information**: the decompiler is not wrong; the information is not in
  the binary. Your job is to supply it, and to **mark clearly what is evidenced
  versus still unknown** — never pretend you recovered it.
- **Mis-representation / mis-modeling / view distortion**: the information *is*
  in the binary. The decompiler **sees it but expresses it wrong**. These can be
  corrected with Listing evidence, which is exactly what the refine scripts do.

---

## What refinement means: replace the decompiler's defaults with evidence

When unsure, the decompiler **makes assumptions on your behalf**: assume
`undefined8`, assume `x8` is a parameter, assume shared storage is one variable.
Those assumptions fill the gaps it does not know, and make the output *look*
complete.

**Refinement = overwrite its default assumptions with facts you can independently
confirm from the Listing, then let it re-express.**

- Confirm a field offset from LDR/STR → import a type, and `param_1 + 0x18`
  becomes `pose.confidence`;
- Confirm the ABI from both call sides → fix the prototype, `in_x8` disappears,
  parameters land correctly;
- Confirm live ranges from P-code → split the merge group, one variable becomes two again;
- Confirm instructions from the Listing → recover FMA/reduction/unordered instead
  of copying the C view.

And one iron rule applies throughout:

> **Writing back is not done; re-decompiling is.**
> A tool returning `succeeded` only means "it was written", not "the semantics are right".

Because what you are fighting is precisely the decompiler's habit of *filling
the unknown with defaults*. Without looking at the regenerated C, you cannot
tell whether it used your evidence or guessed again.

---

## How the tutorial is organized: each chapter solves one root cause

Every chapter follows the same narrative:

```
(1) Write C++ with clear semantics -> compile to AArch64 .so (the answer is known)
(2) Load it into Ghidra and copy the real decompiler output (one concrete symptom)
(3) Ask: which root cause is this? (missing info / mis-representation / mis-modeling / view distortion)
(4) Find the evidence in the Listing, apply the matching method
(5) Compare the C before and after -- and state what the fix covers and does not cover
```

| Chapter | Hard-to-read symptom | Root cause | Solution |
| --- | --- | --- | --- |
| [00](00-setup/README.md) | — | Environment | NDK, independent Ghidra instance, RVA/VA rules |
| [01](01-missing-information/README.md) | `undefined8`, `p + N`, fields as indices | Missing information (types) | Build an offset table, import a single type header, re-verify size and offsets |
| [02](02-wrong-abi-model/README.md) | `in_x8`, `void`, shifted parameters | Mis-modeling (ABI) | Fill a two-sided ABI table first, then fix the prototype |
| [03](03-broken-structure/README.md) | One variable with two meanings, old/new pointers sharing a name | Mis-representation (optimization) | Build a union for the stack slot, split merge groups, bind live ranges |
| [04](04-invisible-edges/README.md) | Virtual calls with "no caller", invisible targets | Missing information (indirect edges) | Follow receiver->vptr->slot, record each receiver separately |
| [05](05-lost-boundary/README.md) | Truncated true entry, lost exception cleanup | Mis-representation (boundary) | Recover the range from FDE/LSDA, then audit |
| [06](06-view-vs-fact/README.md) | `a*b+c`, `a>=b`, `.2D` shown as scalar | View distortion (operations) | Recover operations from the Listing, not the C |
| [07](07-verify-and-scope/README.md) | "It looks fixed" | Verification | Emulation plus differential, and state the evidence scope |
| [08](08-close-out/README.md) | `iVar5` remains after delivery | Delivery standard | Seven checks, naming, plate, save |

## Three rules that recur throughout

1. **The machine instructions are the only fact; the decompiled C is a view.**
   Display is not fact.
2. **Confirm before writing back; re-decompile after writing back.**
   Neither the order nor the re-check can be skipped.
3. **Evidence has scope.** One correct function does not mean the whole chain is
   correct; every report must state what it covers.

## Relation to the real project

This method matches the tools the ARLauncher project actually uses:
`eval-ghidra.py` drives a live GhidraMCP instance for interactive refinement;
`ghidra_scripts/` holds vendor-neutral scripts (`ImportTypes`, `InspectType`,
`InspectFunctionAbi`, `InspectReturnStorage`, `RefineStackSlot`,
`RefineUnionFacet`, `RefineRegisterSlot`, `RefineDynamicLocal`, `RefineHighLocal`,
`InspectHighAt`, `RefineFunctionRange`, `AuditFunctionRanges`, and more) for
batch write-back.

## Conventions

- Target **AArch64 Android** (`aarch64-linux-android29-clang++ -O2`); stack-slot
  reuse and SSA merging introduced by `-O2` are exactly what chapter 03 confronts.
- Source lives in `N-xxx/src/`; build output in `build/` (ignored).
- Addresses are **ELF RVAs** (`eval-ghidra.py` applies `GHIDRA_IMAGE_BASE`);
  Java script arguments are **Ghidra VAs**.
- A separate Ghidra instance is used:

  | Instance | Install | Settings | Port | Project |
  | --- | --- | --- | --- | --- |
  | ARLauncher | `C:\tools\ghidra_12.1.2_PUBLIC` | `%APPDATA%\ghidra\...` | 8089 | `nr_api1212.gpr` |
  | Tutorial | `C:\tools\ghidra-tutorial` | `ghidra-tutorial\user` | **8090** | `ghidra-tutorial\projects\tutorial.gpr` |

  ```powershell
  pwsh -File start-tutorial-ghidra.ps1
  $env:GHIDRA_MCP_URL = "http://127.0.0.1:8090"
  ```

## What makes a chapter complete

1. The example compiles under the local NDK;
2. The "symptom" is real decompiler output;
3. You can **classify which root cause it is**;
4. The refine script actually ran on GhidraMCP, with before/after C preserved;
5. You can state what the fix covered and did not cover.
