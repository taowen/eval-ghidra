# 04 — 信息缺失（间接边）：Ghidra 看不到虚表

> **根因**：虚调用经过表指针，Ghidra 默认看不到边，于是 `get_function_callers`
> 为空。这不是"没有调用者"，是**间接边不在它的直接图里**。
> 把"无 caller"当死代码删掉，功能就没了。

## 现象

`src/vtable.cpp`：

```cpp
struct IRenderer {
    virtual void initialize(int) = 0;
    virtual void render(const Frame*) = 0;
    virtual ~IRenderer() = default;
};
struct GlesRenderer : IRenderer { ... };
struct NullRenderer : IRenderer { ... };

void drive(IRenderer* r, const Frame* f) { r->render(f); }  // 间接调用
void register_cb(void (*cb)(int));
```

反编译看到：

```c
void drive(long param_1, long param_2)
{
    (**(code **)(*(long *)param_1 + 8))(param_1, param_2);   // 裸表偏移
}
```

问题：
- 调用点只看到表偏移，看不出目标；
- `get_function_callers(GlesRenderer::render)` 为空；
- 两个实现的目标容易混成一个。

## 判定：这属于哪类根因？

**信息缺失（间接边）**——直接引用图里没有这条边。但**目标本身在二进制里**
（表里存着函数地址），所以**能追**，只是要按固定链路，而不是靠 caller 列表。

## 找出证据：按固定链路记录

手册规定：

> 按 **receiver → vptr 字段 → slot 字节偏移 → 目标 → ABI** 记录。
> 沿构造函数写表、重定位、注册回调查目标。`get_function_callers` 为空不代表无调用者。
> 多实现按 receiver 分开记录。相邻 vtable 函数只能提供线索。

## 修正

### 1. 列出按 slot 调用且 receiver 匹配的调用点

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\AuditAarch64VtableSlotCalls.java",
                  args="<slot> [receiverField]", capture_output=True)
```

`slot` 是 vtable 字节偏移；可选 `receiverField` 限定 receiver 字段偏移。

### 2. 绑定普通回调调用点

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\BindCallbackCalls.java",
                  args=r"...\tutorial-types\chapter04-vtable.h", capture_output=True)
```

从类型头的 `@tutorial_callback_callsite` 标记读调用点，绑定实际目标。

### 3. 两个实现分别记录

对 `GlesRenderer::render` 与 `NullRenderer::render` 分别记目标 RVA。
沿构造函数写表指令或表地址的 `get_xrefs_to` 追。

### 4. 未解析的目标标 `[REVIEW]`

**不用通用 stub 填补。**

## 如果没做这一步

- "无 caller"的虚函数被当死代码删，功能丢失；
- 两个 receiver 目标混一，翻译时调错实现；
- 用相邻 vtable 槽猜目标，得到错误的被调函数。

## 本章验收

- [ ] 两个 receiver 的 `render` 目标分别可见且正确
- [ ] 回调注册点能追到真实函数
- [ ] 每个间接调用有完整 receiver→slot→目标→ABI 记录
- [ ] 能解释"caller 为空为什么不是死代码"
