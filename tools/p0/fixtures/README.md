# fixtures 的来源说明

## 这些不是真机录制的

**当前 fixtures 目录下的所有样本，都是按官方输出格式手写的合成样本，不是从设备上抓的。**

这一点必须写在最前面，因为它直接决定了这些 fixture 能证明什么、不能证明什么：

| 能证明 | 不能证明 |
|---|---|
| 解析函数在**已知格式**下逻辑正确 | 真实设备输出的格式就是这样的 |
| 边界情况（空值、`null`、多段、历史行）被正确处理 | 国产 ROM 没有额外的格式变体 |
| 改解析代码时不会悄悄改坏既有行为 | —— |

换句话说：**fixture 绿了不代表解析器对真机是对的。**
它只保证「同样的输入永远得到同样的输出」，以及「我们想到的格式都处理了」。

## 首次真机运行时必须做的事

第一次在真机上跑完 `run_p0.py` 之后：

1. 打开报告里的「原始输出」折叠块
2. 逐条对照解析值和原始文本 —— 它们一致吗？
3. **不一致就把原始输出另存为本目录下的新 fixture**，并在下面的清单里登记

真机输出与这些合成样本不一致是**正常且预期的**：Android 每个大版本都会改
`dumpsys` 的排版，而国产 ROM 还会再改一层。

## 已覆盖的格式变体

| 文件 | 覆盖的写法 | 依据 |
|---|---|---|
| `meminfo_android12plus.txt` | `TOTAL PSS: 12345` | Android 12 起改为标签式 |
| `meminfo_android11.txt` | 表格首列 `TOTAL  12345  ...` | Android 11 及更早 |
| `meminfo_no_total.txt` | 没有 TOTAL 行（进程已死） | 必须返回 None 而不是 0 |
| `batterystats_multi_uid.txt` | 多行 `Uid u0aNNN:` | 应用 + 子进程共用 uid 时要相加 |
| `batterystats_no_power.txt` | 没有 Estimated power use 段 | 必须返回 None |
| `battery_with_counter.txt` | 有 `Charge counter:` | 支持库仑计的设备 |
| `battery_no_counter.txt` | `Charge counter: 0` | 驱动不支持的设备 —— 0 要当取不到 |
| `display_with_overlay.txt` | 同时有 `mDisplayId=` 与 `uniqueId="overlay:2"` | 副屏已创建 |
| `display_no_overlay.txt` | 只有主屏 | 副屏未创建 |
| `appops_allow.txt` / `_deny.txt` / `_deny_with_history.txt` | 三种模式 + 带历史行 | 历史行不能覆盖当前值 |
| `appops_mode_prefix.txt` | `mode=allow` | 部分版本加前缀 |
| `appops_empty.txt` | 空输出 | 必须返回 None |

## 如何新增

把真机输出原样存成一个 `.txt`，然后在 `tests/test_parse.py` 里加一个用例
说明**这份样本要防的是什么**。只丢文件不加用例，等于没加。
