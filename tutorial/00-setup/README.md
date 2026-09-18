# 00 — 准备：建立可复现的分析环境

这一章不涉及根因，只固定三件会影响后续判断的事：**怎么编译**、**连哪个
Ghidra**、**地址怎么算**。

## 1. 编译示例

分析对象是 AArch64 Android 的 `.so`，示例必须同架构、同优化级别：

```bash
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android29-clang++" \
  -O2 -shared -fPIC -fno-exceptions -fno-rtti -fvisibility=default \
  -o build/<chapter>.so src/*.cpp
```

- **`-O2` 是故意的**：根因 3（优化打碎结构）只在优化下出现。关掉优化，第 03 章
  的现象根本不会发生，你也就学不到真实工程里最常遇到的那类问题。
- 第 05 章需要异常/LSDA，去掉 `-fno-exceptions`。
- `-fvisibility=default` 让符号可见，便于按名字定位函数。

## 2. 连接现场 Ghidra

教程使用独立实例：

```powershell
pwsh -File start-tutorial-ghidra.ps1
$env:GHIDRA_MCP_URL = "http://127.0.0.1:8090"
```

```bash
python3 eval-ghidra.py 'list_open_programs()'
python3 eval-ghidra.py --help get_function_variables
```

## 3. 两条地址规则，不能混

| 规则 | 适用范围 |
| --- | --- |
| **ELF RVA** 输入，schema 自动 + image base | `eval-ghidra.py` 的地址参数 |
| **Ghidra VA**，不换算 | Java 脚本的 `args`、批量地址字符串 |

用 `get_current_program_info()` 核对 image base；本工程是 `0x100000`。

## 4. 最小工作集

| 用途 | 调用 |
| --- | --- |
| C | `analyze_for_documentation(function_address=addr)` |
| Listing | `disassemble_function(address=addr)` |
| 变量 | `get_function_variables(address=addr)` |
| high P-code | `get_function_pcode(function_address=addr, granularity="high")` |
| 调用者/引用 | `get_function_callers` / `get_xrefs_to` |
| 类型查找 | `search_data_types(pattern=...)` |
| 完整性 | `analyze_function_completeness(function_address=addr)` |
| 保存 | `save_program()` |

Java 脚本执行前必须 `switch_program(program=...)`。

## 本章验收

- [ ] 能编译出 aarch64 `.so`
- [ ] `list_open_programs()` 返回教程工程，base 是 `0x100000`
- [ ] 能说清当前操作的是 RVA 还是 VA
