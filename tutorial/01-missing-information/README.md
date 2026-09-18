# 01 — 信息缺失：类型不在二进制里

> **根因 2**：你写的 `struct`、字段名、数组/嵌套结构，编译后**全部消失**，
> 只剩寄存器和字节。反编译器不知道那里有 `Pose`，只能起 `undefined8`。
> **这类信息任何工具都变不出来，只能靠逆向者用证据补。**

## 现象

`src/layout.cpp`：

```cpp
struct Vec3 { float x, y, z; };
struct Pose { Vec3 position; float q[4]; };     // 28B
struct Node {
    Node*    next;    // +0x00
    Pose     pose;    // +0x08
    unsigned flags;   // +0x24
    unsigned char kind; // +0x28
};

float pose_z(const Pose* p) { return p->position.z; }
void  bump(Node* n)         { n->flags |= 1u; }
```

反编译看到的是：

```c
float pose_z(undefined8 *param_1) { return *(float *)(param_1 + 1); }
void  bump(long param_1)         { *(uint *)(param_1 + 0x24) |= 1; }
```

问题：
- `param_1 + 1` 是什么？它**不是字节偏移 1**，而是"第 1 个 `undefined8`"，
  即字节 +8；
- `Pose` 的 `x/y/z/q` 全无，字段 xref 为空；
- `Node` 是 `long`，看不出是对象。

## 判定：这属于哪类根因？

**信息缺失**。字段名、类型、甚至"这里是个结构体"这个事实，都不在二进制里。
所以**不能"还原"，只能"补"**。精修的目标是：把从 Listing 能确证的布局，
以唯一类型头的形式补进去，并明确标注哪些字段是证据、哪些仍未知。

## 找出证据

Listing 里有字节级事实：

```asm
LDR S0, [X0, #8]     ; 读 float，字节偏移 8
```

**规律：反编译的 `p + N` 已按元素缩放，不能直接抄 N。**
建立偏移表要用 Listing 的"基址 + 字节位移 + 宽度"。

| 指令 | 基址 | 字节位移 | 宽度 | 结论 |
| --- | --- | --- | --- | --- |
| `LDR S0,[X0,#8]` | 对象 | +8 | 4B float | `position.z` |
| `LDR W1,[X0,#0x24]` | 对象 | +0x24 | 4B | `flags` |

同时区分三种指针：对象基址、内部成员（嵌套）、secondary-base 指针。

## 修正

### 1. 写唯一类型头（`tutorial-types/chapter01-layout.h`）

约定：canonical 结构体单独放 `*-layout.h`，**不含 `#include`**；不要造聚合总头，
也不要让 Ghidra 里的临时结构成为"第二份真相"。

```c
typedef struct Vec3 { float x; float y; float z; } Vec3;
typedef struct Pose { Vec3 position; float q[4]; } Pose;
typedef struct Node {
    void*    next;      /* +0x00 */
    Pose     pose;      /* +0x08 */
    unsigned flags;     /* +0x24 */
    unsigned char kind; /* +0x28 */
    char _pad[7];       /* 到 +0x30 */
} Node;
```

### 2. 导入前预检规模

```bash
python3 experiments/ar-glass-lib-3dof/messy/tools/audit-ghidra-type-includes.py \
  tutorial-types/chapter01-layout.h
```

模块超过 65536 字符会被导入器拒绝；大了就按真实依赖拆成多个小模块。

### 3. 导入

```python
switch_program(program="<chapter>.so")
run_ghidra_script(script_name=r"...\ghidra_scripts\ImportTypes.java",
                  args=r"...\tutorial-types\chapter01-layout.h",
                  timeout_seconds=300, capture_output=True)
```

### 4. **复核实际尺寸和偏移**（关键，不能只看 parse 成功）

```python
run_ghidra_script(script_name=r"...\ghidra_scripts\InspectType.java",
                  args="Pose", capture_output=True)
run_ghidra_script(script_name=r"...\ghidra_scripts\InspectType.java",
                  args="Node", capture_output=True)
```

必须读到：`Pose`=28B、`position.z` 在 +8；`Node`=0x30。
native 侧同时加 `static_assert(sizeof(...))` 和 `offsetof` 断言。

**为什么必须复核**：Ghidra 字段可以是对的，而 native 因为漏 padding 已经偏移——
这里两边都要查。

### 5. 重反编译

确认 C 里出现 `Pose *`、`n->flags`、`n->kind`。

## 如果没做这一步

- 把 `param_1 + 1` 当"偏移 1 字节"，字段写到错误位置；
- 漏 padding → Ghidra 对、native 错，自测仍可能"通过"（所有内部读写共享同一个错误布局）；
- 每个函数按自己理解记字段，产生互相漂移的多份布局。

## 本章验收

- [ ] `Pose` 28B、`position.z` 在 +8；`Node` 0x30
- [ ] `bump` 的 C 中 `flags`/`kind` 有名有类型
- [ ] 预检脚本对本章头返回 OK
- [ ] 能说出"不导入类型时 `param_1 + 1` 会被误读成什么"
