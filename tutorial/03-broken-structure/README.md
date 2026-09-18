# 03 — 表示错误：优化把结构打碎了

> **根因 3**：`-O2` 的栈槽复用、寄存器分配、SSA 合并，让"一个源码变量"变成多个
> 存储位置/活区间，也让不相关的变量共用存储。反编译器倾向于把共享存储**合并**
> 成一个变量，于是源码里的两个东西显示成一个。
> **信息在二进制里，反编译器合并错了——这类能拆。**

这是真实工程里出现频率最高的一章。

## 现象 A：同一栈槽被三个对象复用

`src/stack.cpp`：

```cpp
void reuse(float seed, const char* tag) {
    { double m[4] = {seed, seed+1, seed+2, seed+3}; use_matrix(m); }  // 阶段1 矩阵
    { char buf[32]; snprintf(buf, sizeof buf, "%s", tag); use_str(buf); } // 阶段2 字符串
    { Foo* p = make_foo(); consume(p); }                               // 阶段3 指针
}
```

反编译出现：double 被显示成指针、矩阵首字显示成 logger 字段、时间加法变成
`text + timestamp`。

**只改名或锁成 `double[]` 会污染另一个生命周期**——因为那三个对象确实共用
同一块 `Stack[-...]`。

## 现象 B：旧/新指针同名，SSA 合并

`src/live.cpp`：

```cpp
int walk(const int* base) {
    const int* p = base; int sum = 0;
    for (int i = 0; i < 8; ++i) { sum += *p++; (void)i; }  // 后递增：旧/新指针都活着
    return sum;
}
int branchy(int mode, int a, int b) {
    int v = mode ? a : b;   // merge
    if (mode) v += a;       // 同一寄存器两个 merge group
    return v;
}
```

反编译把旧/新指针、把两个 merge group 显示成同一个变量。

## 现象 C：CALL 后出现无赋值的 local

`src/call.cpp`：

```cpp
struct Result { int a, b, c, d; };
Result produce(int mode);
int consume() { Result r = produce(1); return r.a + r.d; }
```

反编译在 caller 冒出 `extraout_*`，看起来像"未初始化"。

## 判定：都属于哪类根因？

**表示错误**。都是优化把机器里的结构打碎后，反编译器**合并/命名错了**。
信息在，能修。但要先看清 P-code，不能按名字猜。

## 找出证据：先看 raw/high P-code

**第一条纪律**（手册原文）：

> 真 raw 是 `getInstructionAt(site).getPcode()`。`granularity="basic"` 也是 high
> 的基本块视图，**不能用它证明 Sleigh 原始宽度**。

用 `InspectHighAt` 看目标指令的 raw/high、输出 varnode 的 `space:offset:size`、
该 high 的所有实例、mergeGroup、def/use、基本块前驱后继。

它打印 `split_eligible` 和 `same_group_multi_block`。**后者为真只说明同组值跨了
多个基本块，不授权强拆**——还要结合前驱后继、CALL 原型、旧锁判断。

## 修正

按症状选对方法（这张表是全章重点）：

| 症状 | 方法 | 写后检查 |
| --- | --- | --- |
| 同一栈槽多用途 | 建 union + `RefineStackSlot` | 业务读写用对成员 |
| union 成员选错 | `RefineUnionFacet`（先看后写一条边） | 派生算术类型也对 |
| 多活区间寄存器 | `RefineRegisterSlot` | 命中目标组、不污染同寄存器别的值 |
| 后递增旧/新指针共享寄存器 | `RefineDynamicLocal` | 旧地址读取与指针更新次序与 Listing 一致 |
| 一个 high 多个 mergeGroup | `RefineHighLocal` | 确实分开，其他组未被错误合并 |
| CALL 输出碎片 | 把输出栈区建成结构后重绑 | 无编造初值的 `extraout_*` |
| 稳定单一语义局部 | `set_variables` | 重取 C/变量，检查 failed 计数 |

### 栈槽：先建 union 再绑

```text
RefineStackSlot.java entryVA signedStackOffset name unionName expectedBytes
```

脚本先核对预期长度，再删重叠旧局部。**执行前保存旧变量清单**，确认不会覆盖
另一个仍存活的对象；不要拿超大 `byte[]` 掩盖未知布局。
偏移用 Listing 的 `Stack[-...]`，**不是执行中的 `sp + ...`**。

**绑完立即重反编译**，逐个检查业务写入/读取及 CALL 实参——自动成员选择可能仍错。

### union 成员选错：绑一条具体 P-code 使用边

```text
RefineUnionFacet.java entryVA siteVA                               # 只读：打印 ops 与边
RefineUnionFacet.java entryVA siteVA sequenceTime edge union field  # 写
```

`edge=-1` 是输出，非负数是零基输入序号。选**真实消费点**（时间差加法的输入、
矩阵复制的 LOAD/STORE、CALL 实参），不是在栈声明上选成员。
`INDIRECT/MULTIEQUAL` 不是写入目标。**每写一个点就重反编译**，不复用旧
sequenceTime 批量猜。

### 拆 merge group：有保护条件

```text
RefineHighLocal.java entryVA instructionVA opcode space:offset:size newName [dynamic [obsoleteNamesCsv]]
```

两条保护（手册明确）：
- 写前确认目标 high 含**多个 forced merge group**，只有一个组时**脚本失败且不写 DB**；
- 写后确认返回的新 high 是**严格子集且只含所选组**。

**每次只拆一组**，重取 C，同时检查拆出组和剩余组——拆分后反编译器可能把余下值
和另一个已有语义名合并。

### CALL 输出

核对输出参数/隐藏返回 ABI 与 callee 实际 STORE 范围；把 caller 输出栈区建成
正确结构，删重叠碎片局部重绑。**不要给 `extraout_*` 编造初值。**
若 callee 已证实写满而 caller SSA 未表达跨 CALL 写入，记录
"callee 写哪个对象 → caller 哪些读取"，按内存数据流翻译。

## 命名也是交付

`local_a8`、`iVar5`、`dVar17`、`param_1` 只说明反编译器怎么编号，**不是完成**。

1. 列出所有业务值，写"来源 → 运算/用途 → 消费者"，回 Listing 核实；
2. 命名含单位和方向：证实是"目标时间减记录时间、单位微秒"才叫 `prediction_delta_us`，
   不能因是整数就叫 `timestamp`；
3. 稳定局部用 `set_variables` 的当前名定位（`iVar5` 是查找键，不是最终名）；
   失败按上面的 register/dynamic/split 处理，**不能只在 native 里改名**；
4. 纯日志/运行库临时量可保留，但要能从 CFG 确认不参与业务。

含义未明的业务变量，函数记为"精修未完成"，列出具体变量和缺失证据——
**不能用完整性工具的高分替代 C 里可读正确的名字**。

## 如果没做这一步

- 栈槽：日志阶段读坏矩阵数据；时间加法变成 `text + timestamp`；
- 寄存器：旧地址读取与指针更新次序颠倒；
- SSA：两个运行期值写进同一个错误对象；
- CALL：把 `extraout_*` 当未初始化，给它编了初值。

## 本章验收

- [ ] 三阶段读写落在正确 union 成员，派生运算类型正确
- [ ] `p++` 旧/新指针是两个名字，次序与 Listing 一致
- [ ] `branchy` 两个 merge group 分开命名
- [ ] `consume` 输出栈区是结构，无 `extraout_*` 编造初值
- [ ] 业务局部都有语义名，未解项明确标"精修未完成"
