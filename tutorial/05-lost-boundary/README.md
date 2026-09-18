# 05 — 表示错误（边界）：真入口不是你以为的那个地址

> **根因**：`getFunctionContaining` 返回成功，**不证明该地址是入口**。
> 加上 `noreturn` 传播等分析动作会改函数边界，真入口可能被截断、异常清理
> 可能丢失。边界错了，后面 ABI、局部、运算全建在错误范围上。

## 现象

`src/range.cpp`（编译时**不加** `-fno-exceptions`）：

```cpp
void cleanup();
void spanned(int mode) {
    Guard g;                                  // 析构必须在异常路径执行
    work();
    if (mode) throw std::runtime_error("x");  // 制造 landing pad
    tail_cleanup();                           // 尾部清理
}
```

反编译/分析后看到：
- 函数范围比实际短，尾部 `tail_cleanup` 和返回值被截断；
- LSDA/landing pad 被误建成独立函数；
- 异常路径的析构/解锁在 C 里消失。

## 判定：这属于哪类根因？

**表示错误（边界）**。真实边界由 FDE 给出，反编译器/分析动作表达错了。能修。

## 找出证据：FDE/LSDA

- **FDE** 给出真实起止（`.eh_frame`）；
- **LSDA** 给出 landing pad 与清理区域；
- 指令质量：AArch64 区间必须 4 字节对齐。

## 修正

### 1. 修范围

```text
RefineFunctionRange.java entryVA exclusiveEndVA
```

脚本会：核对 4 字节对齐、拒绝从别的函数偷字节、拒绝缩短现有 body、
逐条反汇编缺失指令，并打印前后 body。

### 2. 批量核对

```text
AuditFunctionRanges.java entryVA endVA [entryVA endVA ...]
```

**必须在签名导入/自动分析之后跑**——因为 `noreturn` 传播可能把之前恢复的
landing pad 从 body 里去掉。

### 3. 不相邻 body

```text
AuditDisjointFunctionBody.java entryVA startVA endVA [startVA endVA ...]
```

检查被拆成多块但同属一个函数的范围。

### 4. 可疑分支

读 `flowType`/`fallThrough`/`flows` 核对真实条件跳转。机器 CFG 单后继而 C 多出
else 时，**保留 raw/high 和结构化 C 对照，不改 FlowOverride 伪造机器边**。
可以把真实分支条件/写入顺序记到 plate。

## 未决范围时的纪律

**不要求复刻异常运行库**（异常分配器、RTTI、unwinder 不必精修），
但**业务异常类型、错误码、清理范围、资源释放、解锁顺序必须保留**。

## 如果没做这一步

- 范围截断 → 尾部清理和返回值丢失，异常路径资源泄漏；
- landing pad 被误建成独立函数 → 控制流断裂；
- 为了"不翻译日志"删掉官方 CFG 里的清理分支 → 异常时解锁/释放顺序错误。

## 本章验收

- [ ] 真入口/排他结束与 FDE 一致，`AuditFunctionRanges` PASS
- [ ] 异常路径的析构/解锁在 C 中可解释，未误建成独立函数
- [ ] 能指出范围截断会让哪个资源在异常时泄漏
- [ ] `analyze_function_completeness` 的范围类问题已处理，接受项写明原因
