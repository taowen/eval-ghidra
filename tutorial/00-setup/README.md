# 00 — Setup: a reproducible analysis environment

This chapter has no root cause. It fixes three things that affect every later
judgment: **how you compile**, **which Ghidra you connect to**, and **how
addresses are computed**.

## 1. Compile the examples

The target is an AArch64 Android `.so`, so examples must share the
architecture and optimization level:

```bash
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android29-clang++" \
  -O2 -shared -fPIC -fno-exceptions -fno-rtti -fvisibility=default \
  -o build/<chapter>.so src/*.cpp
```

- **`-O2` is deliberate**: root cause 3 (optimization shattering the structure)
  only appears under optimization. Turn it off and chapter 03's symptom never
  occurs, so you never learn the most common real-world problem.
- Chapter 05 needs exceptions/LSDA, so drop `-fno-exceptions`.
- `-fvisibility=default` keeps symbols visible so functions can be found by name.

## 2. Connect to a live Ghidra

The tutorial uses its own instance:

```powershell
pwsh -File start-tutorial-ghidra.ps1
$env:GHIDRA_MCP_URL = "http://127.0.0.1:8090"
```

```bash
python3 eval-ghidra.py 'list_open_programs()'
python3 eval-ghidra.py --help get_function_variables
```

## 3. Two address rules that must not be mixed

| Rule | Applies to |
| --- | --- |
| **ELF RVA** input, schema adds the image base | `eval-ghidra.py` address parameters |
| **Ghidra VA**, no conversion | Java script `args`, batch address strings |

Check the image base with `get_current_program_info()`; in this project it is
`0x100000`.

## 4. Minimal working set

| Purpose | Call |
| --- | --- |
| C | `analyze_for_documentation(function_address=addr)` |
| Listing | `disassemble_function(address=addr)` |
| Variables | `get_function_variables(address=addr)` |
| high P-code | `get_function_pcode(function_address=addr, granularity="high")` |
| Callers/refs | `get_function_callers` / `get_xrefs_to` |
| Type lookup | `search_data_types(pattern=...)` |
| Completeness | `analyze_function_completeness(function_address=addr)` |
| Save | `save_program()` |

Java scripts must be preceded by `switch_program(program=...)`.

## Chapter checklist

- [ ] You can compile an aarch64 `.so`
- [ ] `list_open_programs()` returns the tutorial project, base is `0x100000`
- [ ] You can say whether the current operation uses an RVA or a VA
