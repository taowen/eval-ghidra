# 02 — 建模错误：ABI 是推出来的，会推错

> **根因 4**：AArch64 的整数和浮点参数用不同寄存器序列，隐藏返回走 `x8`，
> 聚合参数会被拆开。Ghidra 对这些有**默认推断**，和实际二进制不符时就错，
> 冒出来的就是 `in_x8`、`void`、参数错位。
> **信息在二进制里，反编译器看得到但表达错了——这类能修。**

## 现象

`src/abi.cpp`：

```cpp
struct Mat4 { float m[16]; };                         // 64B > 16B → 隐藏返回在 x8
Mat4  make_identity();
struct Query { float x, y, conf; };
Query query_pose(float x, float y, int mode);         // 混合 FP/整数寄存器
void  fill(Mat4* dst);
```

反编译看到的是：

```c
void make_identity(undefined8 *in_x8)         // 实际返回 Mat4！
{
    ...
    *(float *)in_x8 = 1.0f;                   // 写进"参数"的其实是返回值
    ...
}

undefined8 query_pose(float param_1, int param_2)  // 丢掉一个 float，mode 错位
{
    ...
}
```

问题：
- `make_identity` 被标成 `void`，结果通过 `in_x8` 写出；
- `query_pose` 把 `float x, float y` 和 `int mode` 的寄存器序列搞错；
- caller 处冒出一串 `unaff_*` / `extraout_*`。

## 判定：这属于哪类根因？

**建模错误**。真实 ABI 就在指令里（谁写 x8、谁读 D0/D1/W0），只是 Ghidra 的
默认推断没用对。所以**可以修**，修的依据是"调用两端的事实"，不是猜。

## 找出证据：先填 ABI 表，再改原型

铁律：**表填完之前不要动签名。**

每个输入记"调用点如何准备 → callee 如何消费"；每个输出记"callee 如何写 →
caller 如何读"。至少包含：寄存器/栈位置、宽度、指针基址、输出区域长度、证据指令。

| 值 | 调用点如何准备 | callee 如何消费 | 结论 |
| --- | --- | --- | --- |
| 返回 `Mat4` | caller 传 x8 指针 | callee 用 x8 写 64B | 隐藏返回 |
| `x` | 放 S0 | 读 S0 | 第一个 float |
| `y` | 放 S1 | 读 S1 | 第二个 float |
| `mode` | 放 W0 | 读 W0 | int（**不是 X1！**） |

关键规律（手册原文）：

- **一个 `double` 在 D0 不意味着下一个指针在 X1**，不能按 C 参数总序号推寄存器；
- 要区分：普通标量 / NEON 向量 / 聚合参数 / 隐藏返回指针——以调用两端为准；
- `void` 不能凭旧 C 保留：看 RET 前 W0/X0、D0 或输出缓冲，以及 caller 是否消费；
- **入口未使用的参数也不能随意删**，否则后续寄存器整体错位。

## 修正

### 1. 看默认调用约定和参数分配

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\InspectFunctionAbi.java",
                  args="<callingConvention>", capture_output=True)
```

打印每个匹配函数的 formal return、`isForcedIndirect`、各参数 storage 与 `isAutoParameter`。

### 2. 确认非平凡返回的 storage

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\InspectReturnStorage.java",
                  args="Mat4 Query", capture_output=True)
```

报告 `getStorageLocations()` 的结果、`forced_indirect`、`auto`。据此判断 x8。

### 3. 写正确原型并重导

普通大结构返回写成 `Mat4 make_identity(void);`，**由 ABI 分配返回存储**，
**不要**擅自改成 `void make_identity(Mat4 *)`。

### 4. 重反编译，检查调用两端

- caller 实参与 callee 消费位置一致；
- 返回字段对应真实写回；
- **没有**由错误原型引起的 `in_xN`、整数当指针、输出缓冲长度错误。

仍未解析的业务间接目标标 `[REVIEW]`，**不用通用 stub 填补**。

## 如果没做这一步

- `make_identity` 保持 `void`，翻译时把返回存储当成输入；
- `query_pose` 的 `mode` 从错寄存器读 → 后续所有参数跟着错位；
- 删掉"入口未用"的参数 → 其余参数整体移位。

## 本章验收

- [ ] `make_identity` 不再是 `void`，`in_x8` 消失
- [ ] `query_pose` 的 FP/整数参数各归其位
- [ ] ABI 表里每个参数都有"准备/消费"两侧证据
- [ ] 能说出错标原型会让哪几个后续参数整体错位
