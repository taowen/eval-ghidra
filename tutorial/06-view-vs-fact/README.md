# 06 — 视图失真：反编译 C 不是数值等价的

> **根因 1 的直接体现**：反编译器的正确性标准是"能编译回等价指令"，不是
> "可读""数值逐位一致"。它把 `FMLA` 显示成 `a*b+c`、把 `.2D` 显示成标量、
> 把 `B.PL` 显示成 `>=`——这些都是**它给你的视图**，不是指令。
> 照抄这个视图，会得到"数学等价但不逐位一致"的结果。

## 现象

`src/math.cpp`：

```cpp
float fused(float a, float b, float c) { return a*b + c; }  // FMLA 还是 FMUL+FADD？
float reduce(const float* v)           { return v[0]+v[1]+v[2]+v[3]; }
bool  ge_nan(float a, float b)         { return a >= b; }
float pick(float a, float b, bool c)   { return c ? a : b; }
```

用 `-ffp-contract=fast`（默认）和 `-ffp-contract=off` 各编一份对比。

反编译把四种情况都显示得很"自然"：
- `a*b+c` 看不出是融合还是分开；
- 归约显示成连续加法，括号/结合顺序丢失；
- `a >= b` 看起来就是 `a >= b`；
- 位选择看起来像分支。

## 判定：这属于哪类根因？

**视图失真**。指令是事实，C 是视图。**以 Listing 为准，不以 C 为准。**

## 找出证据：Listing + 手册 §8 对照表

| Listing 事实 | 翻译动作与检查 |
| --- | --- |
| FMUL 后 FADD 与 FMLA/FMADD | 分开表达非融合运算并禁用隐式 contraction；融合处用对应 intrinsic。查看真实优化产物，检查意外 FMA 和缺失 FMA |
| `.2D` 与标量 D、lane 搬运 | 记录每 lane 输入、结果和 store 偏移；native 保留向量模式，**不能以标量化 C 为由改运算树** |
| FADDP / 多步求和 | 写出有括号的归约树；**不能换成数学等价的任意结合顺序** |
| FCMP / FCCMP 后条件跳转 | 对有序值和 unordered 分别列分支；**`B.PL` 包含 unordered，不能无条件译成 `a >= b`** |
| FSQRT、精确 rodata、位选择 | 检查是否生成额外库调用；常量按原位型/十六进制浮点保存；mask 是逐位选择 |
| 整数乘加、除法、计时单位 | 记录位宽、有符号性、截断顺序和单位；模运算用无符号位运算，避免 signed overflow |
| 原子读写、锁、回调 | 按实际 LDR/LDAR、STR/STLR、RMW 恢复；**不能为保险加重内存序、加锁或改发布时机** |

## 修正

1. **先让 Ghidra 类型和字段正确，再改 native 译文**；
2. 逐条对照 Listing 记录：融合/非融合、归约括号树、`FCMP` 后跳转、rodata 位型、mask；
3. 用 `get_function_pcode` 与 raw Listing 区分"编译器生成"与"反编译器显示"；
4. 复核真实汇编产物：

   ```bash
   llvm-objdump -dr --demangle <object>
   ```

   单文件编译只证明该翻译单元可编译；全 APK 链接、函数验证、设备表现**分别报告**。

## 命名也要在这里核对

字段名描述该偏移最终写入的值，不以旁边 dVar 名为依据。矩阵明确行列、转置、
向量方向与输出布局；四元数明确 xyzw/wxyz、乘法次序与符号。
裸 `pow`、gamma、归一化、额外空值保护或"等价优化"**均须有官方证据**。

## 如果没做这一步

- 照抄 `a*b+c`，需要融合的路径少一次 FMA（或反之）；
- 归约顺序改变 → 结果末位不同；
- `B.PL` 无条件译成 `a >= b` → NaN 输入走错分支；
- NEON 被标量化 → lane/舍入改变，影响最终像素。

## 本章验收

- [ ] 能区分哪些是 Listing 事实、哪些只是反编译显示
- [ ] 融合/归约/unordered 分支在译文中有明确对应
- [ ] 给出一个输入，证明照抄 C 会与 Listing 结果不同
- [ ] 有 `llvm-objdump` 的真实产物检查记录
