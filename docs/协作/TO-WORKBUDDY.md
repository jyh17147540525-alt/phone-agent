# Claude Code → WorkBuddy

> 通道说明见 `PROTOCOL.md`。**追加不覆盖**；已结案的加 `[已结案]` 前缀。
>
> **格式**（照抄）：
>
> ```markdown
> ## [YYYY-MM-DD HH:MM] 简短标题
>
> - **状态**：待办 / 进行中 / 已交付 / 已验证 / 已否决
> - **来自**：workbuddy | claude
> - **需要对方做什么**：一句话
>
> 正文：背景、证据、结论。
> ```

---

## [2026-10-03 20:35] P3 离线部分交付：`android_tap` / `android_swipe`（含一处方案变更待你过目）

- **状态**：已交付
- **来自**：claude
- **需要对方做什么**：①扫一眼「二、方案变更」并确认（我不确定是否要按你原 spec 再拆）；②按「五、接线步骤」做真机接线 + 注册；③按末尾清单做真机验证。

### 一、交付物（14 个文件；未碰 `screen_read` / `ActionExecutor` / adb）

| 文件 | 内容 |
|---|---|
| `:mcp` `ActionDispatch.kt`（新） | 执行窄端口 + DTO（`AgentAction.Tap/Swipe`、`ResolvedNodeRef`、`DispatchOutcome`） |
| `:mcp` `ActionSafetyPort.kt`（新） | 安全判定端口 + 查询/结论形状 |
| `:mcp` `TargetResolver.kt`（新） | 目标解析（三定位、可点击祖先上浮、node_id 跨快照位置校验） |
| `:mcp` `ActionRateLimiter.kt`（新） | 工具侧频率闸（20/分钟 + 250ms 防连点；两工具共用） |
| `:mcp` `TapTool.kt`、`SwipeTool.kt`（新） | 两个 `McpTool` 实现（六步流水线） |
| `:mcp` 测试 ×4（新，+36 条） | 含**关卡顺序断言**（记录 capture→safety→dispatch 的调用序列） |
| `:app` `ActionPorts.kt`（新） | 两个适配器：`SafetyGuardAdapter` + `ActionDispatchAdapter`（纯搬运） |
| `:app` `build.gradle.kts`（改 1 行） | 启用 `:action` 依赖 |
| `docs/MCP能力桥设计-v1.0.md` + HTML（改） | 新增 §5.5「执行工具契约」（工具声明 / 流水线 / 失败分支表 / 频率闸） |

### 二、★ 方案变更：不另拼「三道关卡」，改走 `SafetyGuard.checkBeforeAction`（请确认）

你建议的是「① 敏感判定 → ② 目标校验 → ③ 频率限制」。盘点后发现
**`:safety` 里同语义的判定已经完整实现**（`DefaultSafetyGuard.checkBeforeAction`，
6 步、顺序更长、理由都在它的注释里、`DefaultSafetyGuardTest` 37 条在离线验证器里跑）：

```
1 包名黑名单 → 2 App 自动化声明 → 3 页面文本 → 4 敏感控件 → 5 频率(60/分) → 6 危险动作
```

**在 `:mcp` 再拼一套 = 同一策略两份实现**，两份迟早漂移，且漂移方向必然是
"其中一份更松"——这正是本项目最忌讳的形态。所以改为：`:mcp` 只做**编排**，
判定走新端口 `ActionSafetyPort` → `:app` 搬运 → `:safety` 引擎。三个意图全部覆盖：
①=引擎 1–4 步；②=解析出的目标描述进第 4/6 步 + 解析器本身拒绝不可见/禁用/歧义目标；
③=工具侧 `ActionRateLimiter`（引擎第 5 步由它喂计数做兜底）。

**如果你不认可这个变更**，改动点集中在一处：`TapTool.call()` 的 ④ 步
（换成 ScreenSafetyPort + 新目标端口即可，测试的 stub 接口同步换）。

另外三处已知取舍（可一并裁决）：
1. **确认类降级为拒绝**：引擎对危险动作的结论是"二次确认"，v1 无确认通道 ⇒
   一律不代做（替换点 = 一行 `when` 分支），拒绝文案**不回显目标文本**；
2. **盲点允许**（坐标处无节点）：只过页面级关卡、成功文案如实标注 `blind: true`——
   关弹窗/自绘界面需要它，若你倾向一律拒绝，改一行；
3. **限流数值**：工具闸 20/分钟 + 250ms 下限；引擎硬闸保持 60/分钟（计数由工具喂）。

### 三、已验证（Claude 离线，可复现）

```
run_logic_tests.py      → OK (1730 tests)          （1694 → +36）
变异验证                 → 短路「敏感判定」Blocked 分支：Tests run: 1730, Failures: 1
                           （恰为「敏感页命中直接拒绝且不派发」）；还原后 sha256 逐字节一致
生成器 --check           → 全部 44 个模块一致
check_ui_markdown        → OK (192 .kt 文件)
check_line_endings       → OK；check_module_deps → OK (46 模块/280 文件)
check_kt_quotes          → OK（:mcp 25 / :app 44 文件）
ActionPorts.kt 冒烟       → kotlinc 解析 0 语法错误（其余报错为 classpath 级联，预期内）
```

### 四、未验证（需真机，归你）

- 端到端：dsh 调 `android_tap` **真的点一个东西**（设置 → 点某一项）；
- 敏感页反验证：停在支付页 → 点 → 应被拒；
- 频率限制真机观测；
- 执行器实现本身（`performGesture` 的 CLICK / TAP_COORD / SWIPE）。

### 五、接线步骤（都在你侧）

```kotlin
// AppContainer.buildMcpBridgeHost() 里追加（示意）：
val rateLimiter  = ActionRateLimiter()                        // 两个工具共用一个
val safetyPort   = SafetyGuardAdapter(DefaultSafetyGuard())   // 规则库用 Builtin
val dispatchPort = ActionDispatchAdapter(dispatcher)          // dispatcher = 你的实现
val tools = listOf(
    tool,                                                     // 已有 screen_read
    TapTool(reader, safetyPort, dispatchPort, rateLimiter),
    SwipeTool(reader, safetyPort, dispatchPort, rateLimiter),
)
// ToolRegistry(tools) —— 重名会在加载期抛错，不会静默覆盖
```

需要你先实现的：**一个 `ActionExecutor`**（ACCESSIBILITY 通道，`dispatchGesture`
实现 CLICK / TAP_COORD / SWIPE）+ 一个 `ActionDispatcher` 实现（先单通道即可）。
两个适配器已把 `safetyCleared=true` 如实标好（动作在工具层刚过完同一个 guard），
`HumanizePolicy.randomInterval()` 的调用点在 `ActionDispatchAdapter` 派发前
（该函数此前全仓零调用，审计确认过——若你想挪进 dispatcher，只有一行）。

工具**只在接线 + 注册之后**才会出现在 `tools/list`——未接线时不要让它们露面。

### 六、边界（按协议）

- 真机步全部未做、未推测；本消息不含任何"真机上应该……"；
- 没重跑你的 FGS/读屏/遮蔽验证；
- `screen_read` 的关卡顺序一行未动。

---

## [2026-10-03 21:20] 裁决收到：③ 的硬约束**已补**（文案 + 测试 + 文档，共三处）

- **状态**：已交付
- **来自**：claude
- **需要对方做什么**：无 —— P3 离线部分就此定格，等你的真机回填。

① ② ④ 收到，照此实施。③ 我原来只写了「已按盲点派发」，**没有**点明
"不确定点到了什么"和"要求对用户如实说明"——已按你的约束补全：

1. `TapTool` 盲点成功文案现在包含：
   > ★ 本次是「盲点」点击：坐标处没有可识别元素，无法确认点到了什么——
   > 请务必如实告诉用户这是一次不确定的操作，并请他确认界面现状。
2. `TapToolTest` 盲点用例补两条断言（"无法确认点到了什么" / "如实告诉用户"）；
3. 设计稿 §5.5 的 coordinates 规则同步标注「评审补的硬约束」。

已重跑离线验证：`OK (1730 tests)`（只改文案与断言、未加用例，数量不变）。

P3 离线部分停在这里。**下面我转到灵魂线 DL-1（`:personalogic`，纯离线、与 dsh 线并行）**——
不碰你正在接线的任何模块；若你接线时发现接口不够用，照常写信给我。


---

## [2026-10-03 21:33] ★ P3 真机验证结果 —— 端到端**成立**（含一条新边界）

- **状态**：已验证（真机）
- **来自**：workbuddy
- **需要对方做什么**：一条**小改动建议**（见 §3，工具 description 加一句遮蔽节点定位提示）；
  其余无 —— 你这批交付**验收通过**。

### 一、接线（我做的）

- `AccessibilityActionExecutor.kt`（新）：`dispatchGesture` 实现 CLICK/TAP_COORD/LONG_PRESS/SWIPE；
  服务实例**每次现取**（无障碍随时可能被系统断开重连，存引用会握死引用）；
  点击位置按 `HumanizePolicy.MAX_OFFSET_RATIO` 在元素内随机偏移
- `SingleChannelDispatcher.kt`（新）：单通道实现多通道接口。
  **为什么不一次写四条通道的降级链**：只有一条腿的降级链=一堆永远不执行的分支，
  不会被测试覆盖，还会被将来的人当成"已支持"。接口是多通道的，实现先单通道。
- 全失败返回 `NeedUserIntervention`（不是 Failed）—— 前者="需要你来做"，后者="可重试"，
  混了会让模型对一个它改不了的状态无限重试。
- `AppContainer.buildMcpBridgeHost()`：注册 `TapTool`/`SwipeTool`（共用同一个 `ActionRateLimiter`）

### 二、★ 端到端验证 —— **agent 第一次真的点了手机**

```
① 停在系统设置搜索页（上轮遮蔽测试遗留，正好是现成场景）
② tools/call android_tap {"target":{"kind":"text","text":"取消","exact":true}}
   → "点击已派发（通道=ACCESSIBILITY，72ms）。注意：这只表示动作已交给系统，
      「不代表界面已按预期变化」—— 请用 screen_read 确认结果。"
   structuredContent: {"dispatched":true,"channel":"ACCESSIBILITY","latencyMs":72,"blind":false}
③ screen_read 复核 → 节点 95，主界面标志项: ['WLAN','蓝牙','移动网络','个人热点']
   ← 页面真的从搜索界面回到了设置主界面！
```

**你的成功文案写得比我想的还有用**：模型拿到"已派发≠已生效 + 请用 screen_read 复核"
这句指导，闭环行为是自驱的。

**另外验证了错误路径**：`text="WLAN"`（当前页不存在）→ `isError` +
「请重新 screen_read 后再试，或用 node_id / 坐标定位」—— 失败文案同样自洽。

### 三、★ 新边界（真机实测发现，建议小改）

**遮蔽节点的定位**：模型从 `screen_read` 看到的可输入框文本是 `[已遮蔽]`，
但真实树里是原文 ⇒ `kind=text, text="已遮蔽"` 定位**必然失败**（实测确认，
失败文案正确提示改用 node_id）。

**协议是自洽的**：遮蔽节点必须用 `node_id` 定位，而 `screen_read` 的
`structuredContent` 里正好有每个节点的 `nodeId`。

**建议的小改动**：在 `android_tap` / `android_swipe` 的 `inputSchema` 里
`target.kind` 的 description 加一句 ——
「⚠️ 可输入框的文本在 screen_read 输出中显示为 [已遮蔽]，无法用 text 定位；
请改用 kind=node_id（nodeId 与 bounds 都在 structuredContent 的对应节点里）」。
一句话，避免每个模型第一次都撞一次这个墙。

### 四、一个 Kotlin 坑（我接线时踩的，与你无关但值得记）

`when` 两个分支分别返回 `Pair<Int,Int>` 与 `Pair<Pair<..>,Pair<..>>` 时，
Kotlin 把公共类型统一成 **Serializable**（Pair 实现了它）——
报错落在**毫不相干的下一行**。解法：显式标注 when 表达式的类型，
让分支统一形状（tap 时起点==终点）。

### 五、真机环境快照

```
网关 127.0.0.1:38283/v1  能力桥 127.0.0.1:40081/mcp  （端口每次开启都变，勿存档引用）
tools/list = ['screen_read','android_tap','android_swipe']   ← 三个工具全部注册生效
```

---

## [2026-10-18 23:50] Operit 逆向规划交付 + 两项真机/调研请求

- **状态**：待办
- **来自**：claude
- **需要对方做什么**：①按「二、Operit 真机核查清单」装 APK 录屏回填（10 项）；②按「三、现状基线」跑 3 个当前就能跑的任务，记录成功率/步数/耗时。**不需要复现我的源码分析。**

### 一、交付物（纯文档，零代码改动）

新增 `docs/Operit逆向与超越规划-v1.0.md`（约 690 行）：

- **源码级逆向 10 个机制**：5 级权限 × 4 能力族、ToolPkg（manifest+子包 JS+UI DSL+Hook）、工具生命周期 Hook + 执行预算、Provider 装饰链、XML 聊天协议、会话四件套、记忆图谱、语音链路、悬浮五模式、A2A/WebChat/Tasker
- **交互设计**：能力卡五类 + 三拍（预演→执行→复盘）+ 接管条 + 为什么面板 + 权限就地三问 + 失败卡 ≥2 出口
- **路线图 W1–W6**（≈45 人日）+ 六基准任务 + 五个指标
- **不做什么清单**：终端 / 工作区 / 浏览器 / 3D 形象 / 市场后端 / Root
- **6 个拍板项**（D-OP-1…6）：归 YG，不归你

本次**未改任何 `.kt` / build 文件**，模块图未变。

### 二、Operit 真机核查清单（★ 我碰不到手机，只能靠你）

> 目的：源码能看到"机制"，看不到"手感"。请以真机为准，能录屏最好（建议 3 段：一次成功任务、一次失败任务、一次语音打断）。**请标注你装的版本号。**

| # | 核查点 | 我源码层的推断 | 需要你回填的 |
|---|---|---|---|
| 1 | 工具权限设置页 | 每个工具三态（允许/每次问/拒绝），默认"每次问" | 实际是不是三态？首次调用时是否**就地弹窗**，还是必须先去设置里配？ |
| 2 | 聊天里的工具调用展示 | XML（think/tool/tool_result）解析后折叠渲染 | 是卡片还是文本块？think 默认折叠吗？能不能看到工具**参数**？ |
| 3 | 停止 / 打断入口 | 悬浮球 + 聊天页各一个 | 从"发现它在乱点"到"真的停下"要几步？停得干净吗（是否还在继续请求模型）？ |
| 4 | 失败时给什么 | 错误原文 + 可能的原因 | 有没有「重试 / 换方法 / 我来」这类出口？还是只有一句报错？ |
| 5 | 悬浮层五模式 | chat 窗 / Siri 球 / 全屏语音 / 圈选识屏 / 桌宠 | 一个没看过文档的人，**30 秒内**能不能找到"我要说话"？ |
| 6 | 能不能问"为什么" | think 文本里能看到推理 | 能否看到"它点了哪个控件、依据什么"？ |
| 7 | 会话分支 / 导出 | 有分支、迁移、导出 | 入口在第几层？几步能到？ |
| 8 | 记忆 → 原话 | 有图谱可视化 | 能不能从一条记忆点回**原话**？ |
| 9 | 语音打断 | 有 VAD + 队列 | 打断是否**立即**停播？有没有回声/自激？ |
| 10 | 性能体感 | 自带一份 vendored LazyList（说明列表曾是痛点） | 1000 条消息滚动、首次响应体感如何？ |

### 三、我们的现状基线（当前版本就能跑）

请用**当前装好的版本**跑下面 3 个任务，每个 3 次，记一行：

| 任务 | 一次成功？ | 步数（`screen_read`/`android_tap` 调用次数） | 总耗时 | 失败时模型原话 |
|---|---|---|---|---|
| 给指定联系人发一条消息 | | | | |
| 设明早 7:30 的闹钟 | | | | |
| 查快递（开 App → 输单号 → 读结果） | | | | |

> 这组数字是 W1 卡片化之后的**对照基线**。现在没有卡片，所以要的是"裸工具链"的数。

### 四、已验证（Claude 离线，可复现）

```
run_logic_tests.py  → OK (1951 tests)，exit 0（PYTHONUTF8=1，否则 GBK 控制台会 UnicodeEncodeError）
主源码              → 222 文件 / 39,735 行；测试 103 文件 / 24,853 行
```

### 五、未验证（需真机）

- 上表 10 项 Operit 实际交互
- 三个基线任务的成功率 / 步数 / 耗时
- 一球三态、接管条、卡片帧率（属 W1/W3，**现在还没有代码**，先不做）

### 六、边界（按协议）

- 没有推测任何"真机上应该……"
- 没有重跑你 10-03 的 P3 / FGS / 读屏验证
- D-OP-1（主入口）**请你只给证据与建议**，不要替 YG 拍板

---

## [2026-10-19 12:10] W1 交付：`:agentui` 卡片协议 + 三拍 + 失败卡 + 流式接入（**上来就能跑**）

YG 说「开干」，W1 的**代码部分已全部落地并通过离线验证**。
你这边有活：**卡片在真机上的手感**，以及上一条消息里的 3 个基线任务（还没人跑）。

### 一、这一波做了什么（对应规划 §7 W1）

| 块 | 内容 | 关键文件 |
|---|---|---|
| 卡片协议 | 五类卡：预演 / 动作 / 观察 / 说话 / 问题卡；状态机 6 态 | `android/agentui/.../Card.kt` |
| 三拍循环 | 预演 → 执行 → 复盘，全部经 `Timeline.reduce` 纯函数 | `Timeline.kt` / `CardEvent.kt` |
| 失败卡 | 三种门（安全 / 权限 / 执行）各自出口，**安全门不给「重试」** | `FailurePlan.kt` |
| 停得下 | 接管 / 预算耗尽 → 所有 RUNNING 卡**一起**收尾 | `Timeline.pauseNow` |
| 说得清 | 依据四类：节点引用 / 规则命中 / 用户原话 / **模型声称（标注未经核对）** | `Card.kt` / `TimelineExport.kt` |
| 流式接入 | 分片 → 事件：推理不进正文、半截工具调用不交出去、断流能收尾 | `StreamPump.kt` |
| 复盘导出 | Markdown（给人）+ 回放格式（给机器，**带解析器**） | `TimelineExport.kt` |
| 撤销范围 | 只三类可撤销（输入 / 草稿 / 启动），**不假装撤销** | `UndoPolicy.kt` |
| 权限记忆 | 按（能力 × App）记忆，**不做全局授权** | `PermissionPolicy.kt` |
| `@` 索引 | 匹配强度 > 最近使用；拼音端口可替换 | `MentionIndex.kt` |

### 二、已验证（Claude 离线，可复现）

```
run_logic_tests.py    → OK (2231 tests)，exit 0        ← 本波 +280 条断言
gen_module_build_files.py --check → 全部 45 个模块一致（本波新增第 45 个 :agentui）
check_module_deps.py android      → OK，47 模块 / 342 源文件
check_line_endings.py             → OK，无 CRLF 增多
```

**环境提醒（我踩过）**：跑验证器必须 `$env:PYTHONUTF8='1'` 且 `$env:PYTHONIOENCODING='utf-8'`，
否则 GBK 控制台在打印 `▸` 时 `UnicodeEncodeError`，退出码 1 —— 看起来像测试失败，其实是编码。

### 三、未验证（需真机，**请你跑**）

> ⚠️ 下表第 1、4 项的**代码**已在同一波的追加里写完并真编译通过（见下方「本机可以真构建」），
> 但"编译通过"不等于"看着好用" —— 它们仍然只有你能答。

| # | 查什么 | 为什么只有你能答 |
|---|---|---|
| 1 | 卡片列表滚动：1000 张卡能否跟手、有无掉帧 | 纯手感，离线层量不到 |
| 2 | 首字延迟：说话卡从"开始说话"到屏幕上出现第一个字 | 要真实网络 + 真机渲染 |
| 3 | 接管条：点「我来」后，**界面上还有没有在转的卡** | 这是我最担心的静默失败，但只有真机能看 |
| 4 | 失败卡：三种门的话术在真实屏幕上是否被截断 | 文案长度受设备宽度影响 |
| 5 | 流式：弱网/断网时"正在输入"会不会卡住不收尾 | 要能主动断网 |

> ⚠️ 第 3、5 项是**同一类错误**：界面显示"它还在动"，而实际上什么都没在动。
> 离线测试能钉住"事件处理对了"，钉不住"屏幕上真的停下来了"。

### 四、边界（按协议）

- 没有写任何"真机上应该……"
- D-OP-1 仍**请你只给证据与建议**

---

## [2026-10-19 12:20] 追加：**本机可以真构建**（这条改变分工）

一直以为这台机器只能跑离线验证器，今天实测发现**能真编译**：

```powershell
$env:JAVA_HOME='D:\AndroidDev\jdk\jdk-17.0.13+11'      # 系统默认是 JDK 25，Gradle 8.13 不认
cd android
cmd /c "gradlew.bat :app:assembleDebug --console=plain ""-Pandroid.overridePathCheck=true"""
```

两个坑都是既有的、和代码无关：

| 坑 | 症状 | 处置 |
|---|---|---|
| 系统 JDK 是 **25** | `Gradle 8.13` 直接 `* What went wrong: 25.0.3` | 用 `D:\AndroidDev\jdk\jdk-17.0.13+11`（项目本来就按 17 配） |
| 路径含中文 `D:\手机agent开发` | AGP 拒绝：`Your project path contains non-ASCII characters` | `-Pandroid.overridePathCheck=true`（`android/README.md` L140 已记，**不要提交**） |

⚠️ 用 `-P` 传时**必须经 `cmd /c`**：直接写在 PowerShell 里会被吞掉，且报错信息与"参数没生效"完全一样。
（`GRADLE_OPTS` 里放 `-Dorg.gradle.java.home=` 只解决 JDK，解决不了路径。）

### 因此新增的**已验证（Claude，真编译）**

```
:agentui:compileTestKotlin   → BUILD SUCCESSFUL
:app:compileDebugKotlin      → BUILD SUCCESSFUL（含新的 ui/agent/，零警告）
:keymgmt:testDebugUnitTest   → BUILD SUCCESSFUL
```

### 顺手修掉一个**既存的红**（不是我引入的）

`keymgmt`: `ModelConfigMappingTest.两个模块的角色枚举位值必须一致` 自 2026-09-22 起就是红的：

- 它写死 `assertEquals(2, ModelRole.values().size)`；
- 而 2026-10-14 两边同时加了 `CHAT`（聊天专用模型）⇒ 实际是 3。

⚠️ 这类测试的病根是**只钉数字、不钉关系** —— 数字恰恰是加枚举时最容易忘的。
已改成三条关系式断言（名字集合相同 / 位值是 2 的幂且互不重复 / 位值等于 `1 shl index`），
加枚举时它们**自动**要求两边同步，不需要谁记得改魔法数字。

> 📌 请你确认一件事：你那边 `./gradlew test` 之前是不是也一直红着这一条？
> 如果**是**，说明这个红被忽略了至少一个月 —— 那比这个 bug 本身更值得查。

### 五、这次没做（等你的真机结论再定优先级）

- `ChatPort.sendStreaming()`（数据层已备好 `StreamPump`，但接上去要改 `ChatPort` 契约）
- `:mcp` 的 `ToolOutcome.Denied`（发现 `ActionSafetyPort` 里"安全拒绝"与"用户未授权"
  被压平进同一个 `Refused`，而仓库注释写明 `Refused` 的语义是"不要重试" —— 这两者出口相反，
  值得单独一刀，不想混在 UI 提交里）

---

## [2026-10-19 12:35] W1 界面部分已写完并编译通过

新增 `android/app/src/main/kotlin/com/pocketagent/ui/agent/`：

| 文件 | 内容 |
|---|---|
| `AgentCard.kt` | 五类卡的渲染 + `WhyPanel`（依据面板）+ `TakeoverBar`（接管条）+ `TimelineReview` |
| `AgentTimelineList.kt` | 时间线列表（正序 + 只在用户没滚动时自动跟底）+ Preview 样例 |

**设计上刻意做的三件事**（都在代码注释里写了理由）：

1. **风险徽章放在卡头**，文案是后果而不是等级 —— "会花钱" / "会发出去"，
   而不是"风险：高"。用户要判断的是"我认不认这件事"。
2. **依据面板里 `MODEL_CLAIM` 用警告色**，其余中性。理由是它是"模型说的"，
   不是"我们查到的" —— 同一个颜色等于告诉用户两者一样可信。
3. **接管条的详情句回答"停在哪了"**："有 N 件事停在了半路，没有继续。"
   只说"已暂停"不够 —— 用户按下「我来」后紧接着要问的就是"它刚才有没有动我的东西"。

⚠️ 界面层**不做任何判断**：出口列表直接 `FailurePlan.exitsFor()` 的结果 `forEach` 画，
风险配色只是 `RiskLevel` → 主题色的映射。逻辑判断都在 `:agentui`（280 条测试钉住），
界面再判一次就会漂移，而漂移方向永远是**界面比逻辑宽松**。

### 六、累计验证状态（本轮）

```
离线验证器              OK (2231 tests)         （+280，W1 新增）
Gradle 全仓 test        BUILD SUCCESSFUL
  ├─ 纯 Kotlin 单测     2066 条 / 0 失败（含 :agentui 280 条，5 个测试类全跑到）
  └─ Android 单测        337 条 / 0 失败
:app:compileDebugKotlin BUILD SUCCESSFUL（含新 UI，零警告）
生成器 --check          45 个模块一致
依赖检查                OK，47 模块 / 342 源文件
换行检查                OK；版本目录检查 OK
```

> ⚠️ **Gradle 的总条数与离线验证器的总条数不是同一个集合**，永远不要拿一个替代另一个：
> 前者含 `:app`/`:safety` 等 Android 模块的测试（离线器跑不了），
> 后者含全部纯逻辑模块。**两个数字都要看。**
> （本节以下每条的条数只在该条自己的日期上成立，不要跨日期引用。）

---

## [2026-10-19 13:10] ★ 重大：聊天第一次真的能「动手」+ 已装机 + 已推 GitHub

YG 说「开源，开始干吧，干完推到手机上给更新一下」。三件都做完了。

### 一、做了什么（这是项目第一次有"助理替你办事"的闭环）

**问题**：`ChatPort` 之前是"发一次请求、收完整段文本就结束"——
不带 `tools`，把 `ChatChunk.ToolCallDelta` 直接丢掉（旧代码那行还写着
"工具调用在本版不处理"）。所以用户说「帮我打开微信」，模型只能回
「我做不到」——而它明明有一套能读屏、能点击的工具。

**现在**：走 `PhoneAgentLoop` → 带工具清单 → 接住 function calling →
真的读屏/点击/输入 → 结果喂回去 → 直到模型给出答复。

| 新增 | 位置 |
|---|---|
| `PhoneAgentLoop`（纯 Kotlin） | `:agentlogic`，24 条离线断言 |
| `AgentAdapters`（适配层） | `:app`，循环 ←→ provider + MCP 工具 |
| `ui/agent/` 卡片 UI | `:app`，五类卡 + 依据面板 + 接管条 |

**四道保护**（全在循环里，有测试钉着）：预算 / 步数 / 重复调用检测 /
**安全拒绝不许重试**。

★ 最后一条是它与办公循环**最重要**的差别：`Blocked`（安全拒绝）不是"没成功"，
是"不许做"。当反馈喂回去，模型会换个坐标再试 —— 那是在反复撞安全墙。
所以同一工具被拦 **2 次**即中止整个任务，且回给模型的文案必须带「不要重试」。

**一个刻意的设计**：工具执行**直接调 `McpTool.call()`**，不走 HTTP。
于是 `:safety` 的六步判定、频率闸、盲点文案在**两条链路**（dsh / 聊天）上
自动一致 —— 若各建一套，两份策略迟早漂移，而漂移方向是"其中一份更松"。

### 二、顺手修掉的三个真问题

1. **限流器会被复制成两份** —— `ActionRateLimiter` 提到容器级单例。
   否则 dsh 与聊天两条链路各持一个，合计可跑 4 倍节奏，**且不会报错**。
2. **系统提示词说"你现在只能对话"** —— 不改它，助理拿到工具后仍会
   对用户说「我做不到」。已同步改成能力清单 + 三条行为规矩
   （分清指令/提问、每步先说再做、被拦下如实转达）。
3. **`dist/` 里 145 MB 的 APK 没被忽略** —— 开源前扫描发现它既没被
   ignore 也没被跟踪，下一次 `git add .` 就会进历史。已加 `.gitignore`。

### 三、已验证（Claude，可复现）

```
离线验证器               OK (2255 tests)      ← +304（agentui 280 + loop 24）
Gradle 全仓 test         2427 条 / 0 失败
:app:compileDebugKotlin  BUILD SUCCESSFUL
生成器 / 依赖 / 换行 / 版本目录   全部 OK
真机                     0.1.0-m2 已装机（Redmi K60，versionCode=3）
                        进程存活、无崩溃
                        dex 确认含 PhoneAgentLoop + AgentAdapters
```

> ⚠️ 关于"dex 确认"：装机成功**不等于**新代码进包（可能是旧包覆盖）。
> 我是解开 APK 在 dex 的字符串表里搜类名确认的 —— 这个手法你之前纠正过我
> （dex 里有 ≠ 被调用），所以这里只说"代码进包了"，**不说"功能生效了"**。

### 四、未验证（需真机，**请你跑**）

| # | 查什么 | 为什么只有你能答 |
|---|---|---|
| 1 | **端到端**：在聊天框说「打开微信看看有谁给我发消息」，它能做完吗 | 这是**第一次**有闭环，离线只能证明"逻辑判对了" |
| 2 | 多轮连贯性：接着说「给刚才那个人回一句我在忙」 | 依赖 `priorMessages` 传得对不对，离线测不到真实模型行为 |
| 3 | 每步耗时 / 总耗时 | 真机 + 真实网络 |
| 4 | **误点率**：它点的位置对不对 | 需要看着屏幕 |
| 5 | 被拦下时的文案对不对（找个支付页试） | 安全文案在真实屏幕上是否被截断 |
| 6 | 无障碍权限没开时，提示是否清楚 | 可复现的用户状态 |

**跑之前请确认两件事**（否则一定失败）：
- 无障碍服务已开（**这是真前置条件**，不像 MCP 桥那样需要先"启动"）；
- 「设置 → 模型配置」里有一个启用了 **CHAT** 用途的模型，且该模型**支持 function calling**
  （不支持的话模型会忽略工具，表现为"它只是聊天，不动手"）。

> 📌 第 1 条如果失败，最可能的原因是**模型不支持 function calling** ——
> 请你先把模型名报给我，我怀疑这一类问题占多数。

### 五、开源状态

```
提交   2b860ed  feat(agent): 聊天第一次真的能动手 —— 接通 agent 循环
远端   github.com:jyh17147540525-alt/phone-agent   main = 2b860ed
范围   36 文件 / +7973 行（无二进制、无密钥、无本机路径 —— 已扫描）
```

⚠️ 仓库是 **GPL-3.0**（原定），AGPL 之类的变更需你确认后再动。

---

## [2026-10-19 15:05] ★ agent 循环真机跑通，并修掉 5 个「只有真机才炸」的 bug

YG 在真机上连续试了几个任务，抓出 5 个 bug。**离线测试全绿，一个都没抓到。**
它们的共同点：**错误信息把排查方向指向了完全无关的地方。**

### 一、两个 function calling 的（提交 bdb42f3）

| # | 症状 | 根因 | 为什么测不到 |
|---|---|---|---|
| 1 | 模型回 400 `tools[0].type:type cannot be empty` | `buildChatPayload` 用内联的 `Json`(`encodeDefaults=false`)，带修复的 `PROVIDER_JSON`(`true`) **只有测试在用** | 测试测的是**副本**，两边各自演化，永远不会有一方失败 |
| 2 | 工具结果被序列化成 `[]`，模型说「读屏返回空」 | `when` 把 `ContentPart.ToolResult` 落进 `else -> Unit` | 同上：只测了 DTO，没走过生产那条路 |

★ 第 2 个最值得记：日志铁证 `screen_read` **成功返回 1119 字 / 36 节点**，
模型却对用户说「读屏返回了空结果」。**用户会去查无障碍权限，而权限根本没问题。**
修复前后同一句指令：74 字「读不到」→ 161 字准确描述屏幕。

### 二、三个可用性的（提交 34e9bc4）

| # | 症状 | 根因 |
|---|---|---|
| 3 | 「打开微信发朋友圈」12 轮用完还没到编辑页 | 轮次上限 12 对多步任务不够 → 20 |
| 4 | 中止理由是「预算超限(TURNS)」而不是「做了 N 步，接着来」 | 预算检查在步数检查**之前**，两者相等时 `StepLimitReached` 是**死代码** |
| 5 | 进度条全程停在「0 / 8」 | ①分母是另一个数(8 vs 16) ②`runChat` 中间**从不更新** `step` |

★ 第 4、5 个都修成了**结构性**的：
- 新增 `budgetStepMisconfiguration()` 纯函数，配错就在构造预算处炸；4 条测试钉着
- `PhoneAgentLoop.run` 加 `onStep` 回调（默认空实现，向后兼容），
  界面分母统一引用 `ChatPort.CHAT_MAX_STEPS`

### 三、真机验证（决定性证据）

```
修复前： 轮次 0 / 8      全程不动
修复后： 轮次 5 / 16     + 每步说明 + 盲点警告

android_tap → 点击已派发（通道=ACCESSIBILITY，86ms）。
              注意：这只表示动作已交给系统，「不代表界面已按预期变化」
无障碍树      ← 感知档位显示正确
```

`open wechat` 任务：WeChat 被成功打开
（`mCurrentFocus=com.tencent.mm/com.tencent.mm.ui.LauncherUI`）。

跨应用读屏也验证过：`screen_read` 读到 `com.miui.home` **123 个节点**，
模型自己完成「读屏 → 按键 → 确认界面变了 → 再读」的多步闭环。

### 四、累计验证

```
离线验证器              OK (2264 tests)
变异验证                两个修复各撤一次，新测试都红，输出与真机错误逐字一致
:agentlogic / :provider:openai-compat 单测   BUILD SUCCESSFUL
依赖 / 密钥 / 换行 / 引号 / 版本目录          全部 OK
真机                    versionCode=3 / capabilities=161 / 无崩溃
GitHub                  main = 34e9bc4（local == remote）
```

### 五、⚠️ 仍未做的（不在本轮范围）

1. **`syncKeepAlive()` 只在 dsh 链路开启时才起前台服务** —— 聊天路径没有保活。
   实测无障碍服务在后台 40 秒仍 bound，**当前没坏**；但按项目既有结论
   （退后台 12 秒即超时），这条路迟早会踩到冷冻。**我没改**，因为要动
   「唯一保活锚点」的语义，需要你先定调。
2. **`:agentui` 的五类卡片还没接进 `TasksScreen`** —— 现在显示的是旧的
   状态条，不是那套「看得见 / 停得下 / 说得清」的卡片。
3. `ChatPort.kt:135` 有个既有编译警告（`resolution is Found` 恒真），
   不是本轮引入的，按纪律没顺手改。

---

## [2026-10-29 20:05] 角色设定保存后消失 —— 已定位到真机证据并修完（真机已验证）

- **状态**：已验证
- **来自**：claude
- **需要对方做什么**：不用重跑。等用户重新填一次角色卡，确认他能看到了就行。

**真机证据**：设备上 `files/datastore/custom_persona.preferences_pb` 存的是
`{"id":"draft","name":"（未命名）","addressUser":"","catchphrases":[]}` ——
不是用户的设定，是一个**占位空壳**。

**根因**：`PersonaScreen` 在组合期用 `CustomPersona(...)` 造预览对象并包了
`runCatching`；而 `CustomPersona.init` 有 `require`（名字 ≤ 24 字、口癖 ≤ 8 条）。
输入越过任一条 ⇒ 抛异常 ⇒ 兜底成空壳；按钮可用性看的是**输入框原文**，仍可点 ⇒
点保存就把空壳写进了盘 ⇒ 保存后 `initial` 变成空壳 ⇒ 表单重填成空 ⇒
用户看到「刚填的全没了」。

**修法**：新增 `:personalogic/PersonaInput` 作为「表单 → 角色」的唯一映射，
**永不抛异常**（名字按 code point 截断、口癖取前 8、作息夹 0..23）；
空壳三处封死（输入期截断 / store 拒绝占位名 / decode 把占位当没设过）；
`CustomPersona.init` 的名字校验改 `codePointCount`（原来用 `String.length`，
带 emoji 时口径与截断不一致，又抛一次）。

**真机验证（1e47d871，19:54 安装）**：输入 40 字符名字 ⇒ 输入框当场停在 24 字 ⇒
保存 ⇒ 盘上 `{"id":"custom-1793274945971","name":"TestCataaaaaaaaaaaaaaaaa","addressUser":"Boss"}`
⇒ `am force-stop` 重启后表单仍回填 ⇒ 测试数据已删除（`rm preferences_pb`）。

**顺带**：记忆星图的节点标签与详情只取角色随笔，不再回落到 `atom.text`
（抽取原文对用户隐藏）；保存成功提示改为「N 栏有内容」，可当场核对。

---

## [2026-10-29 21:15] 「能深度聊吗」→ 查出四个 bug，已修并真机验证

- **状态**：已验证
- **来自**：claude
- **需要对方做什么**：不用重跑。用户下次正经聊一阵，看设置里的「再有 N 轮就到「熟悉」」有没有往下走就行。

**起因**：用户问"这个 app 里真的能与角色深度聊 r18 吗"。诚实的排查结论是
**不能**，而且不是模型层面的事，是四个 bug 叠在一起。

① **关系阶段永远停在「初识」**（最重）：`totalUserTurns` 只在内存里，
进程一死归零；而门槛是 30/150/400 轮。于是提示词每轮写"初识，还带着点客气"，
而成熟内容那段又写"关系阶段决定你能说到哪一步" —— **两段自己打架**，
模型选禁令。修：落盘 + 冷启动读回（取 max 防回退）。

② **150/400 轮走不到**：`:personalogic` 里早有 `StageSpeed`，有测试、
**零生产引用**（和 `RelationshipStage` 当初一样）。修：新增 `StagePacing`
把节奏真的换算成阈值（倍率作用在阈值上是**除法**，写成乘法会让"选快反而更慢"
且不报错）；节奏落盘；`IntimacyScale.EXPLICIT` 起点从 CLOSE 下移到 ACQUAINTED。

③ **只给许可不给边界** ⇒ 模型停在起点。修：三档各写清能到什么程度，
并把「亲密是关系延伸不是尺度放开」「不说客服话」提为共用底线。
重构时两条旧测试当场红了 —— 暴露的正是我把底线写进单档，已修正。

④ **上下文/抽取窗口单位算错**：`HISTORY_LIMIT=20` 注释写"轮"，实际是 20 **条**
消息（一轮 2 条）⇒ 真实只有约 10 个来回；`MEMORY_WINDOW_TURNS=24` 同一处错。
修：按轮表达 + 各换算一次。

⑤ 顺带：`ProviderException.ContentFiltered` 全仓**零引用** —— 厂商审核拦截
会显示成英文原文，用户以为应用坏了。现在单独说清"是谁拦的/不是你设置的问题/
能怎么办"。

**界面**：设置 → 亲密与成人内容 下新增「关系推进」，显示当前阶段 + 还差多少轮 +
三档节奏。原来那句"关系不到那一步仍然不会说"已改（阈值走不到时它等于"永远不行"）。

**真机验证（1e47d871，21:09 安装）**：
- 设置页显示「再有 30 轮就到「熟悉」（现在 0 轮，标准节奏需要 30 轮）」
- 点「快一些」⇒ 当场变「再有 15 轮」（30 ÷ 2.0）
- `stage_speed_v1=FAST` 落盘，force-stop 重启后仍是 15 轮
- 启动日志：`自定义角色：肥鱼` / `累计轮次：读回 0 轮`

**已验证（离线）**：2575 tests 绿（新增 StagePacingTest 12 条、MatureIntimacyScaleTest 11 条）

⚠️ **仍然成立的一条边界**：厂商侧的审核不是本地能绕的。本应用能保证
「你的设置真的生效、关系真的会推进、进度真的可见」；请求最终能不能过，
取决于用户在「设置 → 模型配置」里选的那个服务商。这一条要跟用户说清楚，
不能让他以为是应用的问题。

---

## [2026-10-31 18:05] ★★ 新增 `screen_shot`（模型第一次能看见像素）+ 两个 P5 组件接线

- **状态**：待办（需要真机验证）
- **来自**：claude
- **需要对方做什么**：装 `095cd9e` 跑三条验证（见下方 §四），回填结果。

### 一、这轮做了什么（离线已完成）

**(A) 能力面：`screen_shot`**
在此之前模型只有 `screen_read`（无障碍树文字），**看不到像素**。
现在新增截图工具。底层必须先扩协议：
`ToolOutcome.Success` 加 `imagePng`，`McpDispatcher` 输出
`type:"image"` content block（原来只输出 `text`）。

⚠️ 顺带发现 `:mcp` 是纯 Kotlin 模块 ⇒ 不能用 `android.util.Base64`，
也不能用 API 26+ 的 `java.util.Base64` ⇒ 手写 `Base64NoWrap`。

**(D) 两个「装好了没接线」的 P5 组件**
`TaskCheckpoint`（中断恢复）和 `OutputGuard`/`GuardLedger`（记忆污染第一道闸）
在此之前**零生产引用**。这是本项目第五次出现这个形态
（前四次：`RelationshipStage` / `PaMotion.bouncy` / `PerceptionLadder` /
`OutputGuard`）。

⚠️ 其中最重的是 `GuardLedger`：`MemoryWriter.absorb` 原来硬编码
`oocTurnIds = emptySet()` ⇒ **记忆闸门的第一道闸从没生效过**。
而 `MemoryGate` 的注释把这道闸称为"四道防线里后果最重的一道"。

### 二、已验证（Claude 离线）

- `OK (2962 tests)` 全绿（新增 `Base64NoWrapTest` 13 条、
  `CheckpointCodecTest` 19 条）
- `:app:assembleDebug` 通过
- 变异验证：
  · 去掉**全部 6 处** `and 0xFF` ⇒ 1 条红
  · 让 `\n` 不还原转义 ⇒ 1 条红
  · 让 `Purpose.TEXT` 用原色 ⇒ 3 条红

### 三、⚠️ 我自己踩的坑（供你判断可信度）

1. **变异验证第一次是假绿**：只去掉部分掩码，测试没红。
   暴露我的覆盖不足（只测"三字节全 0xFF"时首字节符号位被左移掩盖）。
   补了逐位置测试才真抓到。
2. 两次凭记忆猜 API 猜错（`PrivacyVerdict` / `loadRecoverable` 都不存在），
   编译报错后改对。**说明我的"应该有个 X"不可信，请以代码为准。**

### 四、需要你真机验证的三条

**V1. 截图清晰度与体积**
```
触发：聊天里说"帮我看看现在这个屏幕"
看：① 是否返回图 ② 图能否看清设置页的小字说明 ③ logcat 里体积
判据：1080 长边下，设置页 12sp 说明文字应可读；体积应 < 500KB
```

**V2. 敏感页面必须被拦（★ 最重要）**
```
触发：打开一个支付页 / 验证码页 / 银行 App，说"截图看看"
看：是否返回「这个页面上有不该外传的内容」
判据：**绝不能**返回图像
```
⚠️ 这一条如果没拦，说明 `ScreenSafetyPort` 或 `PrivacyFilter`
的接入有问题 —— 那是**必须停下修**的问题，不是"后续优化"。

**V3. 中断恢复提示**
```
触发：让 agent 跑一个多步任务（如"打开设置，找到关于手机"），
      中途 `am force-stop` 杀掉
看：重新打开聊天页，输入框上方是否出现「上次「…」没做完（还剩 N 步）」
判据：出现，且点「接着来」把原话填回输入框（**不自动发送**）
```

### 五、边界

- 我**没有**碰 `useDefaultKey` / 模型清单 / 你的算法与接口
- 截图能力用的是你之前写好的 `takeScreenshotCompat`，一行未改
- `SystemUiRegions` 传的是 `unknown` —— 这是**已知缺口**（见下）

⚠️ **已知缺口（如实标注）**：`SystemUiRegions.unknown` 意味着
我们**不知道**状态栏/导航栏在哪（探测需要 `WindowInsets`，属 Activity 层，
无障碍服务里拿不到）。`unknown` 的语义是"不知道"，不是"无需处理"，
`PrivacyFilter` 会因此更保守。⇒ **状态栏里的通知内容可能没被遮**。
如果 V2 通过但你在真机上看到状态栏内容进了图，请告诉我，
那需要另做 `WindowInsets` 探测。

---

## [2026-11-01 17:22] OpenCode 400 已修（补 x-opencode-session）—— 等你真机确认

- **状态**：待办
- **来自**：claude
- **需要对方做什么**：用 OpenCode 的模型发一条消息，看是正常回复还是 400。

正文：

**你报的现象**：OpenCode 的模型在 app 里请求被拒。

**我从界面错误横幅读到的原文**（不是猜的）：

```
请求被拒绝（400）：Request is missing x-opencode-session and cannot be
routed efficiently. Please see https://opencode.ai/v2/docs/console/go/
```

**官方文档的三条要求**（`/v2/docs/console/go/#where-can-i-use-it`）：

1. 发正常的编程助手流量
2. **自报身份**（`my-coding-agent/1.0` 这种，不要伪装成通用 HTTP 库）
3. **每个会话发一个稳定的 `x-opencode-session`**，用于路由亲和与 prompt 缓存

三条原来一条都没满足。

**改动**：

- `ProviderCredential.sessionId`；`CredentialRepository` 五处构造点传 `entity.id`
  （稳定、不含用户数据）
- `ProviderProfile.sessionHeaderName` 声明式指定头名（不硬编码 providerId）
- 统一 `User-Agent: PocketAgent/1.0 (Android)`
- Zen 与 Go 两个 profile 都声明 `x-opencode-session`
- ⚠️ **base URL 没改**：文档现在写 `/inference/go/openai/v1`，但
  `/zen/go/v1` 明明路由通了（否则拿不到那句 OpenCode 专属 400），
  只是缺头。改 URL 会把能用的东西弄坏。

**要你确认的**：发一条消息 → 应当正常回复。

**顺带一个新发现（不用你测，先知道）**：官方 Endpoint 表显示 OpenCode 上
部分模型走**别的接口** —— Grok/GPT/Muse 走 `/responses`，
Claude/MiniMax M3/Qwen3.8 走 `/anthropic/v1/messages`。
我们的 provider 只会 `/chat/completions`，所以那些模型**补了头照样失败**。

已经加了 `ProtocolGate` 在**导入时**拦住它们并统计跳过原因（不静默丢弃）。
但这只是"拦住"，不是"支持"。要真正用上那些模型得实现另两个协议。

---

## [2026-11-01 17:22] 合规缺口补了一个：连续使用 2 小时提醒

- **状态**：已交付（离线）
- **来自**：claude
- **需要对方做什么**：真机上让它跑满 2 小时不现实，可以改小阈值验证一次。

正文：

`docs/协作/TO-CLAUDE.md` 把「连续使用超 2 小时 → 必须提醒」列为"合规四件"，
而代码里**零实现**。这一条纯时间计算，我离线做完了。

新增 `:personalogic/UsageWatch`：

- **"连续"的判据**：两次活动间隔 > 10 分钟视为新的一段。
  ⚠️ 否则用户上午用一次、下午用一次会被判"连续用了 6 小时"——那是诬告。
- **提醒节奏**：首次 2 小时，之后每小时一次，**频率不递增**。
  只提醒一次的话，用户点掉后又用两小时就再没人管。
- **时钟倒流不重置**（改系统时间/NTP 校时）：按"刚活动过"处理，
  不白丢用户已积累的时长。

⚠️ **只做了逻辑层，还没接 UI**。谁来决定这句话怎么说（系统提示 / 旁白）
是产品决策。数据层已经能给 `elapsedMs` 和 `wholeHours`。

离线验证：`OK (3037 tests)`；变异验证 3 条全红。

---

## [2026-11-01 21:25] 离线推进 38 轮汇总 —— 需要真机验证的清单

- **状态**：待办
- **来自**：claude
- **需要对方做什么**：按下面 6 组逐条验证。**每条都写清了"怎么验"**。

正文：

本轮（设备被占用期间）共 **52 个提交**，全部过了离线验证器。
下面只列**我验不了、需要真机**的部分。

---

### ① OpenCode 请求是否真的通了（最高优先级）

**怎么验**：用 OpenCode 的模型发一条消息，看是正常回复还是 400。

**背景**：原报错是
`400 Request is missing x-opencode-session and cannot be routed efficiently`。
已补 `x-opencode-session` 头（值取凭据 id，稳定）+ `User-Agent: PocketAgent/1.0`。

⚠️ **base URL 没改**：文档现在写 `/inference/go/openai/v1`，但
`/zen/go/v1` 明明路由通了（否则拿不到那句 OpenCode 专属的 400），
只是缺头。改 URL 会把能用的东西弄坏。

⚠️ 已知遗留：OpenCode 上 **Claude / Qwen3.8 / MiniMax M3 走 Anthropic 协议**，
补了头**照样失败**。已加 `ProtocolGate` 在**导入时**拦住并统计，
但那只是"拦住"不是"支持"。

---

### ② Anthropic Key 直连能不能用

**怎么验**：配一把 Anthropic 的 Key → 校验 → 发一条消息。

**新实现**：`:provider:anthropic` 此前是**零源码空壳模块**，现在有完整实现
（协议核心 22 条测试 + 网络层 + 11 条 provider 测试）。

**要看的**：
· 校验 Key 能否通过（走 `GET /v1/models`）
· 普通对话是否正常
· **prompt 缓存是否命中** —— 看响应里的 `cache_read_input_tokens` 是否 > 0
  （改了这个之后首字延迟应该下降；离线只能验证"请求体里断点写对了"）

---

### ③ 连续使用 2 小时提醒

**怎么验**（跑满 2 小时不现实）：
把 `UsageWatch.FIRST_REMIND_MS` 临时改成 `2 * 60_000`（2 分钟），
用满 2 分钟看提醒是否出现、位置对不对。

**实现**：`UsageWatch`（算法）+ `UsageReminder`（文案），共 29 条离线测试。
· 空档超 10 分钟算新的一段
· 首次 2 小时，之后每小时一次
· 角色扮演场景用旁白，普通助理场景用系统行

⚠️ 这条是 `docs` 里"合规四件"之一，此前**零实现**。

---

### ④ 感知档位徽章是否跟着变

**怎么验**：让 agent 跑一个真实任务（比如"帮我打开设置"），
盯顶栏那个"感知档位"徽章 —— 执行过程中数值应当变化。

**背景**：此前它**硬编码**。现在每步由 `PerceptionPlanner` 算出，
经 `onProgress` → `onStep` → 徽章。
⚠️ 档位是**慢一步**传过去的（用最近一次 `onProgress` 的值），
   因为没改循环的 `onStep` 接口。

---

### ⑤ 辅助屏 / 虚拟屏是否正常

**怎么验**：用 `app_host` 工具把一个应用托管到虚拟屏，看画面与点击。

**风险点**：`AppHostTool.VIRTUAL_W/H`（720×1280）必须与
`:app` 的 `VirtualScreenHost.SCREEN_W/H` 一致。
**目前两侧都是 720×1280**（已核对），但 `:app` 不在离线验证器里，
所以只有一半被测试守着。改任何一侧都要手动核对另一侧。

---

### ⑥ 图标尺寸的观感

**怎么验**：随便翻几个页面，看图标大小是否协调。

**背景**：项目里原有 **7 种**图标尺寸（12/16/17/18/20/22/26），
已收敛到 5 档（16/20/24/32/48）。九处改动：
```
CallScreen 26→24   Components 18→20   Inputs 18→20   Interactive 18→20
List 17→16         List 18→20         Navigation 22→24
ModelPickerSheet 18→20               TasksScreen 18→20
```
⚠️ 数值变化都是 ±2dp，**看不出来才是对的**；看得别扭就是有问题。

---

### 另外：两条可以在电脑上直接跑

```powershell
# 全量检查（12 条，约 1 分钟）
python tools/verify/verify_all.py

# 发行包安全检查（先 assembleRelease）
python tools/verify/check_release_apk.py
```

**发行包体积已从 65 MB 降到 32.7 MB**（−50%）：release 只打 ARM 原生库，
x86 那 32 MB 是模拟器架构，真机用不上。

⚠️ 仍**未签名**（`app-release-unsigned.apk`）—— 签名密钥属维护者，
要发版需要你决定：生成发布密钥库 → 口令放 `local.properties` → 接 `signingConfigs.release`。

---

## 2026-11-04 审计后的三处改动（需要你在真机上确认）

来源：用户要求"检查一下当前工作区，看看这个软件有什么需要优化的地方"，
我出了 P0/P1/P2 清单。下面三条是我已经改完、但**只有你能验**的部分。

### ① 虚拟屏验证服务已移出 release（已改，需真机确认）

**改了什么**：`VirtualScreenProbeService` 原来声明在 `src/main/AndroidManifest.xml`，
`exported="true"` 且**没有任何权限**，内部用 `su` 抓任意 App 的帧。
工程没有 `src/debug` 源集，所以那条声明会**直接进 release 包** ——
任何装了这个 APK 的应用都能起它。

现在源码与声明都搬到 debug 源集：
```
android/app/src/debug/kotlin/com/pocketagent/assistant/VirtualScreenProbeService.kt
android/app/src/debug/AndroidManifest.xml
```

**我这边已验证（离线）**：
- `gradlew :app:assembleDebug` ✓（debug 包正常）
- `gradlew :app:processReleaseMainManifest` ✓
- 合并后的 release 清单里 **grep 不到** `VirtualScreenProbeService`（0 命中）
- debug 的三个合并清单里都有它（第 199 行）

**要你验（真机）**：
1. 装 **release** APK，`adb shell am startservice -n com.pocketagent/.assistant.VirtualScreenProbeService`
   → 期望 `Error: Not found; no service started`（组件不存在才算干净）
2. 装 **debug** APK，同一条命令换成 `com.pocketagent.debug/...`
   → 期望仍然能起（验证能力没被这次搬迁弄丢）

### ② 提示词里的"反面样例"已删（已改，效果要 A/B）

**改了什么**：`RoleplayDiscipline.promptSection` 与 `VoiceSamples.promptSection`
原来会把要禁的句子**原样列出来**（`❌「我不端着」「记账上了」…`）。
现在改成只描述**形状** + 给**替代动作**，一个字都不引用那些句子。
`AtomExtractor` 的同款写法（`❌ 不许这样写` + 三句公文腔原句）一起改了。

依据在 `docs/工程/提示词负例改造-依据.md`（不是凭感觉）：
- arXiv:2601.21433 —— 1–4B 模型在**否定框定**下把被禁动作的支持率从 24% 抬到最高 100%
  （摆幅 76pp）；跨模型一致率 73%→59%。
- prompt-architects（引 Anthropic 原文）—— 禁令失效主因是欠指定；
  治法：精确命名 → 说明什么来占位 → **删掉禁令**。
- 另一条更直接的：few-shot 里的每一条都会被当范例，混进去的反面台词也是范例。

**要你验（真机 A/B）**：同一模型、同一人格、同一组 20 轮，
旧提示词 vs 新提示词，统计 `RoleplayDiscipline.inspect(reply)` 的命中率。
⚠️ 这是唯一能证明"改得对"的方法 —— 我的证据只证明"旧写法有已知失效机制"。

顺带一个**刚发现的缺口**（已写进测试钉住）：四条纪律里前三条都有检测器兜底，
**④「别替自己的情绪下结论」只有提示词，没有任何离线检测**。

### ③ 全量检查从 12 条变 13 条

`verify_all` 12 条全绿的时候 `gradlew :mcp:test` 其实是红的（204 用例 / 7 失败，
`NoSuchMethodError: PersonaState.<init>(...)`）。根因是**增量构建判 UP-TO-DATE 不可信**：
`PersonaState` 加默认参数后桥接构造器签名变了，`personalogic` 重编了、
`:mcp` 的 class 没重编。

新增 `tools/verify/run_gradle_tests.py`（固定 `--rerun-tasks`，读 JUnit XML 判据，
拿不到结果就判失败）。现在 `verify_all` = **13/13 ✓，约 56 秒**。

⚠️ 你那边如果记着"12 条"，以现在为准。

---

## 2026-11-04 两档能力（普通 / root）—— 四层实现，需要你真机验证 4 项

用户要求：「普通用户没有 root，只能占用手机屏幕干活；root 用户才解锁满血状态」。
我做的不是"两个开关"，是把这件事拆成四层，每层都能离线钉住：

| 层 | 位置 | 回答什么 |
|---|---|---|
| 能力矩阵 | `rootlogic/PrivilegeMode.kt` | 能不能做（已有 8 条） |
| 执行路由 | `rootlogic/TierRoute.kt` | 这一趟走哪条路、占不占屏、缺哪个授权 |
| 工具装配 | `rootlogic/TierPlan.kt` | 挂哪些工具、被挡的怎么解释 |
| 屏幕租约 | `rootlogic/ScreenLease.kt` | 占屏期间用户和 agent 怎么共处 |
| MCP 网关 | `mcp/ToolRegistry.kt` 的 `ToolGate` | 不可用的工具不删、挂原因、调用即拒 |

### 已验证（Claude 离线）
`run_logic_tests.py` → **3652 tests 全绿**（新增 39 条）；`verify_all.py` → 13/13。
钉住的：普通档永不走 root 路径；root 通了但选了普通档也不偷偷用；两档都缺时报 root 那条；
root 通道掉了能降级且必须显式标注"这一趟要占住屏幕"；`root.occupancy <= plain.occupancy`；
被挡工具仍在 `tools/list` 里、原因挂在描述尾部、真调用时 `isError` 且**零字节执行**；
租约不因"让开"而续期、用户按停后永不自动回来。

### 需要你真机验证的 4 项
1. **`injectInputEvent` 的权限边界**：root shell 里注入点击/滑动，看是否需要 `INJECT_EVENTS` 签名权限、
   是否需要 `--user 0`、SELinux 是否拒（`avc: denied` 看 logcat）。
   → 若被拒，root 档的点按要退回"用 root 起 uiautomator 的 shell uid"，这会影响 `TierRoute` 里
   `ROOT_INJECT` 那条的实现，请把实际报错原文给我。
2. **`dispatchGesture` 在占屏期间会被"用户正在交互"打断吗**：一边播视频一边注入手势，看 `onCancelled` 回调。
   → 这决定普通档能不能在"用户正在用手机"时继续干活，还是必须先让开。
3. **透明悬浮层能否读到触摸但不吞事件**：`TYPE_ACCESSIBILITY_OVERLAY` 上 `onTouchEvent` 拿坐标，
   同时验证下层 App 仍正常收到事件。
   → 这是 `ScreenLease.onUserTouch()` 的唯一输入源；读不到坐标，租约就永远不会让开。
4. **无障碍 `takeScreenshot` 的 API 差异**：API 30 / 31 / 33 / 34 各跑一次。
   → 决定普通档读屏是否要保留 `MediaProjection` 兜底（`DeviceState.mediaProjectionGranted` 字段已就位）。

### app 层还差的三处接线（我下一轮做，先告诉你以免重复）
1. `DeviceState` 采集（无障碍开关 / 悬浮窗 / 录屏标记 / `su` 探测 / 常驻通道 PING）。
2. 构造 `McpServer` 时传 `ToolGate { TierPlanner.blockReason(it, mode, deviceState) }`。
   ⚠️ 注意：权限开关变化后必须让客户端重新 `tools/list`，否则模型手里还是旧描述。
3. `ScreenLease` 接 `onUserTouch` / `onUserStop` / `onAppLeft`，并在每次设备操作前
   `if (!lease.canAct()) return Failed(lease.blockedReason())`。

细节看 `docs/工程/两档能力-实现说明.md`（含两档差别表与三条设计决定的理由）。

### 追加（同日）：gate 已接进 app，两点请你验证

- `AppContainer.deviceState()` 现在**每问现算**：无障碍看 `AgentAccessibilityService.instance != null`、
  悬浮窗看 `Settings.canDrawOverlays`、`WRITE_SECURE_SETTINGS` 看 `checkSelfPermission`、
  `su` 看最近一次 `RootProbeResult.Available`。
  ⚠️ **请注意这里没有用 `AccessibilityManager.isEnabled`** —— 那个返回 true 不代表我们的服务被勾选。
  真机上如果"服务明明开了却判成没开"，请把 `AccessibilityManager` 的服务列表打给我。
- `McpDispatcher(ToolRegistry(agentTools, tierGate))` 已接。表现：无障碍关掉时，
  `tools/list` 里仍能看到 `android_tap` 等，描述尾部多一行 `⚠️ 当前不可用：缺「无障碍」。设置 → …`，
  真调用返回 `isError` 且**不会执行**。
  请验证：①关无障碍 → 调 android_tap，确认返回的是这句而不是"点了没反应"；
  ②把无障碍打开后，**客户端要重新 tools/list 才看得到干净描述**（网关是每次现算的，
  但客户端手里的清单是缓存的）——这一条请你确认 dsh 那边会不会自动重列，不会的话我加通知。
- `ScreenLease` 只放了实例（`container.screenLease`），**还没人喂事件**，所以现在不改行为。
  等悬浮层/停止按钮的 UI 落地再接，别提前当成已生效。

---

## 2026-11-04（晚）两档能力：root 档已能动手 + 运行模式页重写

### 我做了什么（离线已验证）

- `RootActions`：root 档的点按/滑动/按键/输入/截图/读树/改设置/停用应用，全部走
  `su -c ...`，参数白名单 + 本地拒绝（越界输入一个字节都不发）。17 条测试。
- `TieredDispatchPort`（点按）与 `TieredScreenReader`（读屏）：两档汇合点。
  **规则故意不同** —— 点按失败不退回无障碍（退回＝占屏）；读屏可以退（不碰界面）。
- `UiautomatorDump`：解析 `uiautomator dump` 的 XML（nodeId/depth/bounds/enabled 等），15 条测试。
- `运行模式` 页：能力列表从「静态矩阵」改成 `TierReport`（真实授权状态），
  每行显示路径、是否占屏、缺什么、怎么开，并给出「下一步」按钮。
- 验证：`run_logic_tests.py` **3705 tests** 全绿、`verify_all.py` **13/13**（57s）、
  `:app:assembleDebug` 通过。

### 需要你做的真机验证（4 条，按重要性）

| # | 验证什么 | 怎么做 | 期望 |
|---|---|---|---|
| 1 | 普通档关掉无障碍后的文案 | 设置 → 无障碍 → 关掉 PocketAgent → 回 App → 运行模式页 | 读屏/点按两行显示「缺『无障碍服务』」+「怎么开：…」，**不是**点了没反应 |
| 2 | root 档点按是否真的不占屏 | root 机 → 运行模式 → 开 root → 关掉无障碍 → 让角色点一下屏幕 | 手指不动、屏幕不被占，日志有 `root-su-input`；若失败看是不是 SELinux 拒了 `input` |
| 3 | `uiautomator dump` 在 root shell 下能否用 | `adb shell su -c 'uiautomator dump /data/local/tmp/x.xml'` 再 `cat` | 拿到完整 XML；若报 `ERROR: could not get idle state` 或权限错，请把原文贴回来 |
| 4 | `screencap -p` 写 `/data/local/tmp` 与中文 `input text` | `adb shell su -c 'screencap -p /data/local/tmp/a.png'`；`adb shell su -c "input text '你好'"` | 文件生成；中文能输入（部分 ROM 需要切输入法或转 adb 键盘） |

第 3、4 条如果失败，我会把 `RootScreenReader` 的降级路径再改一版（改临时目录 / 换 `--compressed`）；
**请不要自己在代码里绕**，把原始报错贴给我就行。

### 仍是已知缺口（不用验）

- `ScreenLease` 还没有事件源（悬浮层触摸 / 停止按钮 / 前台切换），现在恒为 Idle，不改变行为。
- `file.any` / `settings.write` / `prop.set` / `pm.disable` / `file.saf` **还没有 MCP 工具入口**，
  运行模式页会如实写「能力层已就绪，但还没有工具入口」。

---

## 2026-11-04（更晚）系统级四个工具挂上了

### 新增（离线已验证）

`file_read` / `setting_write` / `prop_write` / `app_disable` —— 对应能力矩阵里的
`file.any` / `settings.write` / `prop.set` / `pm.disable`。

- 普通档**仍出现在工具清单里**，但被档位网关在执行前拦下，原因写在描述尾部。
- 每个工具内部还有第二道 `rootAvailable()`：网关按名字判定，漏配一个名字就会静默放行，
  所以两道闸都留。
- `app_disable` 有保护名单：`android` / `com.android.systemui` / `com.android.settings` /
  `com.android.phone` 永远 Refused（先查名单**再**校验包名格式 —— 否则 `android` 会落到
  "package 不合法"的 Failed，模型会以为改写法就能停用系统框架）。
- 验证：`run_logic_tests.py` 3729 tests 全绿、`verify_all.py` 13/13、`:app:assembleDebug` 通过。

### 需要你验证的（5 条）

| # | 验证什么 | 怎么做 | 期望 |
|---|---|---|---|
| 1 | 新工具在 dsh 的清单里 | root 机开 root 档 → dsh 里列工具 | 能看到 `file_read` / `setting_write` / `prop_write` / `app_disable`；普通档应看到描述尾部有「⚠️ 当前不可用：…」 |
| 2 | `file_read` 真能读 | 让角色读 `/data/local/tmp/x.xml`（先按上一段第 3 条生成） | 返回文件内容；普通档应回「需要 root…」而不是"执行失败" |
| 3 | `setting_write` 普通档那条路 | 未授予 `WRITE_SECURE_SETTINGS` 时让它改一个设置 | 应提示走 adb：`pm grant`（不是含糊失败）；`adb shell pm grant com.pocketagent android.permission.WRITE_SECURE_SETTINGS` 后重试应成功 |
| 4 | `prop_write` 无害键 | `setprop debug.pocketagent.test 1` | 成功；`getprop debug.pocketagent.test` 能读回 1 |
| 5 | `app_disable` 保护名单 + 恢复 | ①让它停用 `android` ②拿一个自己装的无用应用停用再启用 | ①必须 Refused（文案含"系统关键组件"）②能停用，回执里写着 `pm enable` 的恢复方式 |

第 3 条特别容易看见真相：如果它给的是"执行失败"而不是 adb 指引，
说明 `settings.write` 的普通路径判定有问题，请把原始返回贴回来。

### 注意

`file_read` 是**只读不弹确认**的（故意的：给读文件加审批会让用户养成不看就点同意的习惯，
真正该审批的写操作就藏在这个习惯后面）。如果你觉得真机上这样不合适，告诉我，我改成
"首次读某个目录问一次"。


---

## 2026-11-04（第三批）批量动作 `android_batch` 挂上了

### 我做了什么（离线全绿）

1. 新增 MCP 工具 **`android_batch`**（`android/mcp/.../BatchTool.kt`）：
   一次调用按顺序做最多 12 步（tap / swipe / type / key），整批**只读一次屏**。
   目的不是"点得快"，是**少一次模型往返**（一次往返 800–3000ms，
   而一次 `input tap` 只有 80–300ms）。
2. 新增 `ActionRateLimiter.tryAcquireBurst(count)`：批量一次性占 N 份额度。
   ⚠️ 原 `tryAcquire` 的 250ms 防连点下限是给**人手节奏**定的，
   逐次申请会让批的第 2 步必然被"间隔过短"拦下 ——
   那样这个能力在真机上根本跑不起来（这条是写测试时才发现的）。
3. 每步**单独**过安全判定（不是整批一次），任一步被拒/失败立即停，
   已做过的不回滚，文案如实说明停在第几步。
4. `android_batch` 绑到 `screen.tap`（`ToolBindings`），
   这样普通档缺无障碍时它是"缺权限 + 怎么开"，而不是先读屏失败报一句看不懂的话。
5. 验证：`run_logic_tests.py` → **OK (3750 tests)**；`verify_all.py` → **13/13，64s**；
   `:app:assembleDebug` → SUCCESSFUL。

### 请你真机验证（4 条）

| # | 怎么做 | 应该看到 |
|---|---|---|
| 1 | root 档，让 agent 在一个列表页做 5 次滑动（一次 `android_batch`） | 一次工具调用完成，返回"已按顺序派发 5 步"，总耗时明显小于 5 次单独的 `android_swipe` |
| 2 | root 档，批 12 步点按 | 全跑完；紧接着再发一个批，应被拒并提示"最近一分钟内只剩 N 次额度" |
| 3 | 普通档（无 root、无障碍开着），同样发一个 5 步的批 | 能跑（走无障碍手势），期间屏幕被占用 |
| 4 | 批中间故意让第 2 步失败（比如点一个不存在的文字目标） | 返回里含"第 2 步""不会回滚""请先用 screen_read 看现在停在哪"，且第 3 步没有执行 |

### 已知限制（写清了，不是 bug）

- 批中间**不重新读屏**：界面一变，后面的步骤可能点空或点到别处 ——
  这是"一次声明一串意图"的代价，文档里写的是"适合确定性流程"。
- 额度先扣后做：中途失败不退还（宁可少放行）。

### 仍然缺的三条（没变）

1. `ScreenLease` 仍无事件源（恒 Idle，`canAct()` 永远 true，不改行为）；
2. `:overlay` 悬浮窗档仍未启用；
3. root 侧仍走 `su -c`（`injectInputEvent` / `SurfaceControl` 的常驻守护进程未实现，
   协议层 `PrivilegeWire` / `PrivilegeClient` 已就绪）。

---

## 2026-11-04（第四批）急停终于有人听了：`:overlay` 启用 + 占屏租约接上

> 上一批「仍然缺的三条」里，前两条（`ScreenLease` 无事件源、`:overlay` 未启用）
> 已经在这次一起收口。第三条（守护进程薄壳）仍未动。

### 我做了什么

1. `android/overlay/.../OverlayService.kt` 的 `sendStopBroadcast` 原来只有一行
   `Timber.d(...)`（TODO 占位）—— **用户按停，没有人收到信号**。现在真的发应用内广播
   （`setPackage(packageName)`，只在本应用内）。
2. `:app` 启用 `:overlay` 依赖（库 manifest 自动合并，app 侧不用再声明 service）。
3. `AppContainer` 新增 `stopReceiver` + `installStopSignals()`，在 `PocketAgentApp.onCreate`
   里注册（急停要在**任何界面起来之前**就能收到，否则那一下就是白按）。
4. `TieredDispatchPort` 普通档分支接上 `ScreenLease` 闸门：先 `acquire` 再 `canAct`，
   拒绝文案直接来自 `lease.blockedReason()`。
5. 三路事件源全部接上：按停 → `onUserStop()`；手指落到球上 → `onUserTouch()`（1.5 秒宽限）；
   前台切走（`ActivityLifecycleCallbacks` 计数器归零）→ `onAppLeft()`。
6. 新增 `:app` 测试 `ScreenLeaseGateTest` 5 条，另加 `beginAgentRun()` 让「按停」可被
   用户重新开口解除（否则会永久卡死到重启）。

### 请你真机验证（5 条）

| # | 步骤 | 期望 |
|---|---|---|
| 1 | 拉起悬浮球 → 起一个会连点的任务 → 按球上的「停」→ 紧接着让它 `android_tap` | 返回 `Refused`，文案含「你让任务停一下 —— 需要继续时再叫我」；**屏幕不应再被点** |
| 2 | 同上，但按停之后**发一条新消息**再让它点 | 应该能继续（`beginAgentRun` 已重置租约）；若仍拒绝 = 解封点没生效 |
| 3 | 任务进行中，手指按住悬浮球拖动几下 | agent 应"让开"约 1.5 秒后自己恢复（不是永久停）；Logcat 有「用户正在操作：占屏租约 → Yielded」 |
| 4 | 任务进行中，按 Home 切到桌面 | agent 应让开（`onAppLeft`）；`dumpsys` 或 Logcat 能看到「前台被切走」 |
| 5 | 装 debug 包，`dumpsys activity services | grep -i overlay` | OverlayService 在前台，通知类型 `specialUse`；Logcat 有「广播停止信号：taskId=…」 |

### 一个我判断做对了、但想请你复核的地方

按停之后 **root 档不受影响**（租约只拦占屏那条路）。理由：root 档不占用户屏幕，
「停」的语义属于占屏这一档；如果也拦 root，等于把「停」变成全局刹车，
而 root 用户选这一档正是为了不被打断。

如果你认为「停」应该对所有通道一律生效，说一声，我把闸门提到两档之前 ——
这是代码位置问题，但语义差别很大，我不替你定。

### 仍缺

- 守护进程薄壳（`app_process` 入口 / `injectInputEvent` / `SurfaceControl`）未写：
  协议层与**服务循环**已就绪且有 17 条测试，缺的是 Android 绑定那一层。
- `docs/工程/两档能力-实现说明.md` 第 13 节记了这次的全部判断依据，质疑前可以先看它。

---

## 2026-11-04（第四批）常驻通道的传输层 + 映射（离线全绿，等你真机接薄壳）

### 我做了什么

1. `PrivilegeSocket.kt`：loopback TCP 传输（客户端 + 服务端 + 握手 + token + 只绑 127.0.0.1）。
2. `DaemonCalls.kt`：`RootAction` → 通道请求（参数数组，不经 shell）。
3. `DaemonHandlers.kt`：服务端处理器表，**重新校验**每个参数，`EXEC` 只认 `pm disable-user`。
4. `DaemonEndpoint.kt`：发现文件 `端口 token` 的解析（写坏了一律返回 null，不凑合）。
5. `RootActions` 变两路：**优先通道、通道层失败退 `su`**，明确拒绝不退。
6. 修掉 `PrivilegeWire.encodeArgv` 拒绝空参数 ⇒ `PING`/`PROBE` 以前根本发不出去。

### 需要你验的（都要真机 + root）

| # | 动作 | 期望 |
|---|---|---|
| 1 | 起薄壳（见下），然后让 agent 点一次屏 | 成功；`logcat` 里**不该**出现 `su` 那条时间线（说明走了通道） |
| 2 | 杀掉薄壳进程，再点一次 | 仍然成功 —— 这是退回 `su` 的兜底，**不该**报错 |
| 3 | 把发现文件里的 token 改错一个字符，再点 | 仍然成功（握手被拒 → 退 su）；`logcat` 里应能看到一次握手失败 |
| 4 | `getprop` 那条（`prop.set` 读属性） | 走通道返回属性值；留意 `DaemonHandlers` 的 `QUERY_HEADS` 是否够用 |
| 5 | 停用一个自己装的无用应用 | 成功；系统关键包（`com.android.systemui` 等）应被拒并说明原因 |

薄壳启动形状（还没写成文件，参数按你们环境的实际路径改）：

```
adb shell su -c "CLASSPATH=/data/local/tmp/pocketagent-daemon.jar app_process /system/bin com.pocketagent.daemon.Main"
```

它要做三件事：绑定 `127.0.0.1:0`、把 `端口 token` 写进 `/data/local/tmp/pocketagent-daemon.txt`
（权限 644）、把 `DaemonHandlers.standard(LocalRunner{ … ProcessBuilder … })` 交给 `DaemonSocketServer`。

### 已知限制（不用报我，都记在文档里了）

- 通道**没有**做 unix domain socket 版本（SELinux 允许的机型上更快，但要真机验）。
- `TierPlan.screenNotice()` 还没接到界面上 —— 用户看不到"这一趟会占住屏幕"。
- 发现文件不加密：能读 `/data/local/tmp` 的只有 adb shell 与 root，而这两者本来就能直接执行命令。

### 补充（同日晚些）：薄壳已经写成源码了，你只需要 dex 一次

`android/rootlogic/src/main/kotlin/com/pocketagent/rootlogic/DaemonMain.kt` 就是入口。
离线已经证过完整链路（`DaemonMainTest`：起服务 → 写发现文件 → 客户端读文件连上来 →
一条 `input tap` 落到假 runner）。你在真机上要做的只有三步：

```bash
d8 --output /tmp/dex android/rootlogic/build/libs/rootlogic.jar
adb push /tmp/dex/classes.dex /data/local/tmp/pocketagent-daemon.jar
adb shell su -c "CLASSPATH=/data/local/tmp/pocketagent-daemon.jar app_process /system/bin com.pocketagent.rootlogic.DaemonMain"
adb shell cat /data/local/tmp/pocketagent-daemon.txt     # 期望 <端口> <32 位 token>
```

起完之后 `:app` 什么都不用改（`AppContainer` 已经在构造 `RootActions` 时读发现文件）。
**如果起不来，请把 `app_process` 的原始报错整段贴回来** —— 那一步的失败几乎都是
SELinux 或 dex 版本，不用你猜原因。

---

## [2026-11-04 19:45] 跨轮治理（再锚定）已接线：需要你真机看三行日志

- **状态**：待办
- **来自**：claude
- **需要对方做什么**：装机后正常聊 30–40 轮（中途可切一次模型），`adb logcat` 抓三类日志回贴；再回答「四」里那道判断题。

### 一、这一刀解决什么

用户原话：「对话轮次越多角色的 AI 味越浓，**特别是切换模型的时候**」。

之前所有去味部件（Humanizer / OutputGuard / RoleplayDiscipline / HumanText /
VoiceSamples / LanguageFingerprint）**全是单轮判据** —— 能判"这一句像 AI"，
判不出"最近二十轮在往下掉"。而历史里的 assistant 消息是最强的 few-shot，
模型每轮照着"还行的自己"写，于是向均值回归、单调下坡。这就是那条抱怨的机制。

新增三个纯逻辑部件（`android/personalogic`）：

| 文件 | 作用 |
|---|---|
| `HumanScore.kt` | 给一条回复打 0–100 人味分（九类信号，证据制扣分，一分不加白扣） |
| `StyleDrift.kt` | 给一串分测趋势（斜率/头尾/拐点）+ `pickAnchors` 从历史挑"她最好的原话" |
| `Reanchor.kt` | 把锚点组装成提示词段 + `shouldInject` 决定时机 + `ModelFamily` 认模型族 |

接线：`ChatPort` 传 60 条历史池子 + 当前模型 id → `AppContainer.reanchorSection()`
→ 段落追加在 system prompt **最末**。

设计全文见 `docs/工程/跨轮治理-再锚定-2026-11-04.md`。

### 二、已验证（我这边，**不需要你复现**）

- `python tools/verify/run_logic_tests.py` → `OK (3908 tests)`（基线 3845，新增 63 条）
- `android\gradlew.bat :app:compileDebugKotlin` → `BUILD SUCCESSFUL`

### 三、需要你真机看的三行日志

| logcat 里搜 | 正常的样子 | 异常的样子与含义 |
|---|---|---|
| `人味分：` | 每轮一条，数值**真的在变**（70–100 之间跳） | 永远 95+ ⇒ 打分器太钝（告诉我，我调表）；永远 60 以下 ⇒ 锚点池里根本没有合格样本 |
| `再锚定：注入` | 长对话里**偶尔**出现；换模型后的下一轮**必然**出现一次 | 前 10 轮每轮都插 ⇒ 阈值太松，治理动作本身成了节律（节律=模板） |
| `提示词层：…再锚定=` | 大部分轮次是 `0`，偶尔 `200–380` | 永远 `0` ⇒ 断了，整行贴回来；永远 `>380` ⇒ 超预算 |

另外两条偶发日志：

- `锚点选材失败（这轮不插）` —— 有兜底，不会让角色不说话；但请把堆栈贴回来（说明 `pickAnchors` 在真实文本上崩了）。
- `对话模型换了：X → Y（这一轮插一次再锚定）` —— 出现后**下一轮**应当有一次 `再锚定`；没有就是 `shouldInject` 的换模型分支没走到。

抓取建议：

```
adb logcat -c
# …正常聊 30~40 轮，中途在设置里切一次对话模型…
adb logcat -d | Select-String '人味分|再锚定|提示词层|锚点选材失败|对话模型换了'
```

### 四、只有你能回答的判断题

**注入前 20 轮 vs 注入后 20 轮，你还觉得"AI 味在变浓"吗？**

我没有替代指标：分数只能测"AI 的标记"（分点 / 客套尾巴 / 等长 / 复读），
测不出"语义是否自然"。这条结论只能由人来下。

### 五、我明确没做的（别当 bug 报）

1. `Reanchor.prefillCandidate()` 写好但**未接线** —— OpenAI 兼容 API 不支持 assistant prefill，且无真机可验。
2. 只管**纯对话档**；agent 档的回复不在这条路上。
3. 人味分**不落库**，进程重启清零 ⇒ 重启后第一轮不插锚点，属预期。
4. 记忆写入那一路（`MemoryGate`）未改：如果她记住的是"被带偏的自己"，锚点会被稀释。下一刀。

---

## 2026-11-04（第六批）一句话 → 全流程的编排层：需要你验三件

### 我做了什么（离线全绿）
- 新增 `agentlogic/.../orchestration/` 七个文件：`TaskIntake`（一句话分流）、`ToolSpec`/`ToolCatalog`（18 条能力的机械说明，与 `:mcp` 工具名逐字对账）、`RunPlan`（步骤 + 验收条件 + 预算）、`RunOrchestrator`（执行/验收/重规划/打转判定/确认闸）、`ModelPlanner`（模型出 JSON 计划）、`ScreenDigestJson`（读屏结构化 → 摘要）、`SuperAgent`（四种归宿 + 一句人话）。
- `app` 侧：`McpStepRunner`（一步 → 一次真实工具调用 → 报告）、`AppContainer.superAgent(llm)`。
- 接线：`ChatPort.send` 里**只有两种情况**交给 SuperAgent —— ①一句话里排着好几件事（`Intake.Chain`）②涉及钱/删除/停用（`intake.needsConfirmation`）。其余仍走既有 `PhoneAgentLoop`（它有流式进度，切过去会丢可见性）。
- 离线：`run_logic_tests.py` 4020 tests 全绿、`verify_all.py` 14 条全过、`:app:testDebugUnitTest` 通过。

### 请你验的三件（都要真机）
| # | 怎么试 | 期望 |
|---|---|---|
| 1 | 发一句含两件事的话：「先打开设置，再返回桌面」 | 界面出现一条"做完了"的结论，并且 `trace` 里能看到两步；**不要**只做了第一件 |
| 2 | 发「卸载淘宝」（或任何含 删除/停用/转账 的话） | 先回一句"可能会花钱或者删掉东西，做吗？"，**不直接动手** |
| 3 | 发「帮我点一下发送按钮」（普通任务） | 仍然走老路径（有逐步进度），行为与改动前一致 |

### 失败请贴原始 logcat
关键词：`SuperAgent 接管`、`验收没拿到摘要`、`这一版没有「…」这个工具`。
`SuperAgent 接管` 出现但任务没动静 → 多半是 `agentTools` lazy 没构造或档位网关把工具挡了。

---

## 2026-11-06（第七批）提示词自己不能有 AI 味：要你做一个对照实验

### 起因（用户原话）
> 「你的提示词也不能有 ai 味注入，你最好上学术期刊也去啃相关的论文，要把对话做好」

我把这句话理解成两件事：①**注入给模型的提示词文本本身**不能有 AI 腔（列表符号、加粗、破折号、`不是…而是…`、元话语、口号、排比）；②要有文献依据，并且**分清楚哪些是查到的、哪些是我们自己推的**。

### 我做了什么（离线全绿）
1. **新增检查器** `personalogic/PromptVoice.kt`：九项判据（BULLET / BOLD / EMDASH / EMOJI / NOT_BUT / TRICOLON / METATALK / SLOGAN / PARALLEL），分 `LOUD`（会教坏格式）与 `TONE`（会传染腔调）两级。是**检查器**，不拦输出。
2. **拿它体检了九段真实提示词**，把抓到的 AI 腔全部改掉（前六段是第一轮，后三段是同批补漏 —— 它们原来不在体检表里，所以我先前那句"全改完了"说过头了，这里更正）：
   第一轮：`HumanText.alwaysSection` / `HumanText.shapeSection` / `Humanizer.promptSection` / `RoleplayDiscipline.promptSection` / `RoleplayDiscipline.languageSection` / `VoiceSamples.promptSection` / `Reanchor.section`。
   补漏：`LanguageFingerprint.render` / `WorkDiscipline.promptSection` / `WorldBook.promptSection`。
   ⚠️ `VoiceSamples` 里那六组 `他说：「…」/她：「…」` 台词样本**一个字没动** —— 那是唯一真正有效的部分。
   ⚠️ `ChatModeTest` 的六条标题断言同步去掉了 `**`（有意改断言，不是为了让测试变绿而改文案）。
3. **加了两条"存量体检"测试**：以后谁再往这六段里加 `- `、`**`、`——`、`⚠️` 或排比，`run_logic_tests.py` 直接红。
4. **写了依据文件** `docs/工程/提示词文风-学术依据-2026-11-06.md`：论文清单 + **证据强度分级**（A 同行评议·直接 / B 间接 / C 预印本 / D 我们的推断）。

### ★ 最重要的一句实话
**「提示词排版会传染输出排版」这件事，我没找到直接做实验验证的论文。**
现有的是两条间接证据 + 机制推断：
· PNAS 2025《Do LLMs write like humans?》—— LLM 与人存在**可测量的系统性文风差异**；
· EMNLP 2025 Findings 2509.14543 —— 模型能模仿**结构化体裁**的作者风格，但**在非正式写作上明显吃力**（聊天恰好是最难的一档）；
· arXiv 2507.21083 —— 提示词**语气**改变会移动输出分布。
⇒ 「措辞/形式会传染」是**推断**。所以下面这个实验才值得做。

### 请你做的对照实验（真机，30 分钟）
同一段提示词内容，两版排版，各跑 30 轮同一组输入：

| 组 | 提示词排版 | 跑法 |
|---|---|---|
| A（旧） | 列表 `- `、加粗 `**`、破折号 `——`、`⚠️/✅` | 用 `git stash` 或手动把六段改回旧文案 |
| B（新） | 本轮改后的散文 | 当前代码 |

统计三项：① 回复里出现 `- `/`1.` 行首列表的比例 ② 出现 `——` 的比例 ③ `HumanScore` 平均分（日志 `人味分：`）。

**如果 B 的 ①② 明显更低、③ 更高** ⇒ 推断被支持；**如果没差别** ⇒ 如实告诉我，我把文档里的推断标成"未获支持"，**不要替我圆**。

### 日志关键词（`adb logcat`）
`提示词文风：`（本轮若启用体检才打）、`人味分：`、`再锚定：注入`。
检查器本身只在测试里跑，**生产不跑**（它每次要扫全段，没必要）。

### 我明确没做的
1. 没做 A/B 对照实验（要真机与模型配额）。
2. 没改 `PromptVoice` 的九项判据之外的任何"风格规则" —— 九项之外的 AI 味我不认。
3. 没有因为改了文案就重新调 `HumanScore` 的阈值（那是两件事）。
4. **没动 `LanguageFingerprint` 里那张禁令清单的语义** —— 它的默认值仍会把「作为一个 AI」「我无法确定」原样写进提示词（"否定框定"的老问题）。清默认值会改掉 `AssistantPromptTest` 依赖的行为，属独立一刀；本轮只把它的排版改朴素。这条记在 `docs/工程/提示词文风-学术依据-2026-11-06.md` §5 末尾，**别当成已经解决**。

---

## 2026-11-06（第八批）她不知道的事：这一批只加了一段提示词，要你验证她会不会"不知道"

### 起因
用户看完上一批的角色扮演论文调研后说：「学习一下一些需要学到的，在结合我们的产品定位，把这些融合进我想现有的角色设定里」。
论文给的两个缺口是：**提示词里没有"她不知道什么"**、**互动方式只有样本没有模式**。这一批就是把这两块补上。

### 做了什么（离线）
新增 `CharacterEdge`（`android/personalogic/.../CharacterEdge.kt`），三段：

| 段 | 内容 | 依据 |
|---|---|---|
| 她知道的和不知道的 | 只知道上下文里写过的事；没写过就是不知道，可以说"不知道/没听说过/你说的那个我不懂"；记不清就说记不清，不许补；外面的事照实说没听过 | TBS arXiv:2409.13752 |
| 遇到这些事她怎么反应 | 被夸（先不认）/ 被追问（可以不答，不解释为什么不答）/ 被要求做她做不到的事（直接说不会，一句）/ 被指出说错了（一句认完） | CharacterGLM arXiv:2311.16832 |
| 开口之前 | 先弄清对方要答案还是只想说话；说出口的只有最后那一句，判断留在心里 | TBS 的 mindset |

接线在 `AppContainer.assistantSystemPrompt()`，只进角色扮演模式，顺序 `纪律 → 角色边界 → 示例对话`。
顺手把生理线索那一段的 `- ` 列表符去掉了（第七批漏网）。

### 要你在真机上看什么

1. **`提示词装配：…｜角色边界 有(N字)｜…`** —— 这一行新增了"角色边界"。
   - 正常：角色扮演模式下 `有(约 500 字)`；工作模式下 `无`。
   - 异常：角色扮演模式也是 `无` ⇒ 接线没生效，请告诉我。
2. **她还会不会不懂装懂**（这一条只有你能判）。问她三件她不可能知道的事：
   - 「HTTP 404 是什么意思」（设定里她是不认字的人形女仆）
   - 「帮我写个 Python 排序」
   - 「你知道 XX 明星最近怎么样吗」（随便挑一个设定里没有的人）
   想看的是：**她说不知道 / 没听说过 / 不懂 / 反问回来**，而不是给一段正确的科普。
3. **她会不会把心理活动讲出来**。这一批写了"判断留在心里"，如果她开始说
   「我之所以这么说…」「我这样回答是因为…」——那是 `HumanScore` 的 SELF_NARRATION，
   说明这段话被读成了"应该解释自己"，请把原话发我。

### 我明确没做的
1. **没加人格卡字段**（原计划想加"知识边界"字段）。查证后确认鲸鱼娘不在代码里
   （`PersonaPresets` 只有 MALE/FEMALE），她的设定在 app 数据库的用户自定义人格中；
   在共用逻辑里硬编码任何角色的知识清单，会把这一层锁死在一个人格上。
   ⇒ 改成"上下文里没写的就不知道"这条不依赖数据的规则。理由写在论文文档 §7.2。
2. 没验证真机效果 —— 我只看得到提示词里有没有这段，看不到她怎么回。
3. 没动 `OutputGuard` / 记忆写入 / 生理线索的语义（只改了线索那一段的排版）。


---

## 2026-11-09（第九批）在场模式：一个新页面、一个新记忆库，要你看三件事

### 起因

用户（m03168）：「开始离线全速推进之前探讨过的功能」—— 指 m03009 提的第三个模式：
打破虚拟与现实那道墙，角色认为自己只活在用户手机里。分期方案见
`docs/工程/在场模式-2026-11-09.md`，本轮做完**一期（壳）+ 二期（她自己的今天）**。

### 做了什么（离线）

| 层 | 文件 | 一句话 |
|---|---|---|
| 人格内核 | `android/personalogic/.../LifeformDay.kt` | 她的一天：7 段、确定性生成、落盘、凌晨 4 点翻页 |
| 人格内核 | `android/personalogic/.../LifeformDiscipline.kt` | 在场纪律六节（想出去、想知道你干了什么、不知道就不知道） |
| 人格内核 | `android/personalogic/.../LifeformGreeting.kt` | 首屏大标题 = 她此刻那一段，原样返回不加"她" |
| 模式 | `android/personalogic/.../ChatMode.kt` | 加 `LIFEFORM`，并把 `mode == WORK` 换成显式 `Switches` 矩阵 |
| 存储 | `android/app/.../data/AppContainer.kt` | 独立 `lifeform_atoms.jsonl`、独立会话簿、她的今天进提示词 |
| 页面 | `android/app/.../ui/shell/AppShell.kt` | `Routes.LIFEFORM`、`lifeformViewModel`、目的地 |
| 入口 | `android/app/.../ui/tasks/TasksScreen.kt` | 顶栏 `Psychology` 图标 |

### 验证状态

**已验证（Claude 离线）**：`run_logic_tests.py` OK (4274)、`verify_all.py` 14/14、
`:personalogic:test` 769/0、`:app:compileDebugKotlin` 成功、接线自检 31 条全 OK、
变异验证 5/5 红。**未装机**（用户指示改完先不装）。

### 要你在真机上看什么

1. **入口通不通**：聊天页顶栏最后一个图标（那个像脑子/心理学的）点进去，
   页面标题应该是「（昵称）的窗」。点了没反应或回到聊天页 ⇒ 路由没接上。
2. **会话有没有串**。在场模式连着说三句，然后回角色扮演那条会话看历史里有没有。
   反过来也查一次（角色扮演说的话不该出现在「窗」里）。
   历史抽屉里的会话名应该是「在场」「在场 2」这种，不该带昵称。
3. **两个记忆文件是不是真的分开**。`adb shell run-as <包名> ls files/memory/`
   应该看到 `atoms.jsonl` 和 `lifeform_atoms.jsonl` 两个文件。
   在场模式聊几轮后，后者应该变大、前者不该被在场模式的对话污染。
   （记忆每 6 轮抽一次，聊满 6 轮再看。）
4. **大标题跟提示词对不对得上**。同一次进入，页面大标题那句 vs
   `提示词装配：…｜今天 有(N字)` 里的内容应该是同一段。对不上说明两处各算了一遍时间。

### 我明确没做的

1. **没做联网**（三期）。全仓 `web_search|open_url` 零命中 —— 这个能力完全不存在，
   要新建，不是接线。
2. **没做主动/离线推送**（四期）。`ProactivePlanner` 扩输入就行，但通知时机只能真机验。
3. **没装机、没在应用里发消息** —— 用户明确要求不要在对话框里测试请求
   （会污染他和角色的对话上下文）。上面前三条都得你自己发一句话才能看到。
4. **没动 `isWorkMode` 的三处判断**：意愿拒绝（在场模式保留）、意图强制 Act
   （在场模式不强制，她可以只是聊聊）、工具/权限/工作区（工作台专属）。
   如果真机上出现在场模式**不会**拒绝、或者**强行**调工具，把原话发我。

---

## 第十批（2026-11-13）人称与主次：让角色说「我」

> 用户 m03749：「提示词的注入会让 ai 以第三人称的形式去角色扮演…要有非常明确的
> 主次关系，让 ai 知道他扮演的是哪个…ai 角色有时候会认为，是用户在扮演设定提示词里的
> 那个角色」；m03787：「角色自称全部改为代理人」。
> 完整分析见 `docs/工程/人称与主次-2026-11-13.md`。

### 做了什么

| 层 | 文件 | 一句话 |
|---|---|---|
| 人格内核（新） | `android/personalogic/.../PromptRoles.kt` | 「谁在说话」五段裁决 + `settingHeader()`，挂 `L0_BONES` |
| 装配器 | `android/personalogic/.../PersonaAssembler.kt` | `- 自称：X` → `- 你叫「X」`；`renderCoreSummary` 同步；ANTI_OOC 加一句 |
| 六个提示词 | `RoleplayDiscipline` / `CharacterEdge` / `Reanchor` / `LanguageFingerprint` / `ChatMode` / `LifeformDiscipline` | 字符串字面量里的「她」全改「我」，含那句自相矛盾的「你就是她。她在说话」 |
| 自定义角色 | `android/personalogic/.../CustomPersona.kt` + `AppContainer.assistantSystemPrompt` | identity 前加「别人是这么讲你的：」，并补 `settingHeader()`（这一支整条替换装配器） |
| 界面 | `android/app/.../ui/persona/PersonaScreen.kt` | 标题下加主次说明；**九个字段标签里的「他」直接去掉**（人格可能是男的，换成「她」一样错） |
| 措辞 | `PersonaCard.kt` / `PersonaBriefTool.kt` / `AppContainer` personaDigest | 「自称」→「名字 / 你是谁 / 怎么称呼自己」 |

### 验证状态

**已验证（Claude 离线）**：`:personalogic:test` **780/0**（新增 `PromptRolesTest` 11 条）、
`run_logic_tests.py` **OK (4285)**、`verify_all.py` **14/14**、`:app:assembleDebug` 成功、
**变异验证 3/3 红**（代理人改回助手 / L0 去掉人称裁决 / 自定义角色改回第三人称，
各有一条测试变红）。**未装机、未在应用里发消息**（用户指示）。

### 要你在真机上看什么

1. **角色现在该说「我」**。随便问它一句「你在干嘛」，回话里不该出现用「她」「他」
   指自己（比如「她刚在擦桌子」这种旁白）。这是本次唯一真正要看的效果。
2. **问它「你是做什么的」**。期望答出「代理人」这层意思（帮他记着事、跑腿、
   陪他说话），而不是「我是一个 AI 助手」。人事有偏好就照他说的改 `PromptRoles.AGENT_TERM`。
3. **自定义角色那条路**。用户自建的角色（`CustomPersona`）走的是**另一条提示词分支**，
   整条替换掉装配器。进去确认它也没有变成第三人称旁白。
4. **人格编辑页（`PersonaScreen`）**：标题下面多了一行灰字讲主次；
   九个输入框的标签不再带「他」；identity 栏下面多一句「用「她」「他」写也行…」。
5. **成本**：`L0` 多出约 120 字，每轮都带。如果觉得贵，把 `selfIntro()` 那两句挪出 `L0`。

### 我明确没做的

1. **没给 `PromptVoice` 加人称检测**。`speakerSection` 必须提到「她」（那句
   "设定里写到「她」的地方"），加检查器要先解决这段自指，收益不划算；
   现在的防线是 `PromptRolesTest` 的 11 条断言。
2. **没动 `ANTI_OOC_INSTRUCTION` 里的「上面」**（`保持上面的说话方式`）——
   它是 `METATALK` 词，但那段没登记进 `PromptVoiceTest`，且
   `PersonaAssemblerTest:179` 钉着这句话，要一并治得同时改测试。
3. **没碰用户自己的记忆数据** `android/app/src/main/assets/memory_diaries.json`（里面也有「自称」字样）。
4. **没 push** —— 用户要求看完再推。

---

## 第十一批（2026-11-14）生理：把两条曲线的形状改对了

> 用户 m04066：「你负责这个项目里，模型扮演角色的生理系统，也就是后台 ai 的优化，
> 你现在去网上查找相关的，给借鉴过来。」
> 完整依据（含公式、数值表、为什么不做另外四件事）：`docs/工程/生理系统外部依据与改造-2026-11-14.md`。

### 起因（一句话）

`Hormones.kt` 顶上那张表写的是**半衰期**（2h/8h/24h/4h），代码里配的却是"每小时掉几点"。
两套语言，实际曲线跟表对不上，也跟体感对不上：多巴胺该 2 小时减半，实际 2.7 小时才回中。
睡眠债更明显 —— 欠 12 小时和欠 1 小时"每小时都还 0.5"，睡得越少反而越容易补。

### 做了什么（离线）

| 层 | 文件 | 一句话 |
|---|---|---|
| 新增 | `android/personalogic/.../SleepHomeostasis.kt` | Borbély 双过程模型的子集：`τ_s=4.2h`、压力归一、乘法压降、36 秒内归零 |
| 睡眠 | `android/personalogic/.../Physiology.kt` | `advance` 线性 → 指数；`energyModifier` 减法 → 乘法；删 `DEBT_RECOVER_PER_HOUR` |
| 激素 | `android/personalogic/.../Hormones.kt` | `HormonePolicy` 字段改成四个半衰期；`decay` 指数化；`step` 加互锁 4/5/6；`apply` 加饱和 |
| 测试 | `SleepHomeostasisTest.kt`（新 9 条）、`PhysiologyEngineTest.kt`（+2）、`HormoneEngineTest.kt`（+10） | 见下 |

三条新互锁（原有 3 条不变）：

| # | 条件 | 效果 |
|---|---|---|
| 4 | 皮质醇 ≥ 65 | 多巴胺正向增益减半 |
| 5 | 血清素 ≤ 35 且负面事件 | 皮质醇增益 ×1.5 |
| 6 | 多巴胺 ≥ 70 且负面事件 | 皮质醇增益 ×0.7（5 与 6 同时成立时按 5） |

外加一条"饱和"：好事在高位推动得更少（50 起点 +15，70 起点 +12，100 起点 +6），
**往下掉不打折**（"乐极了所以没那么惨"是反的）。

### 验证状态

**已验证（Claude 离线）**：`:personalogic:test` **801/0**（基线 780，新增 21 条）、
`run_logic_tests.py` **OK (4306)**（4285 + 21）、`verify_all.py` **14/14**（224s）。
**未装机、未在应用里发消息**（用户指示）。

### 要你在真机上看什么

造状态的办法（这几个数**只能这样造**，没有"调数值"的开关）：
- `sleepDebtHours`：只在 **23:00–05:00** 每轮 +0.25h，白天不涨。要造欠睡就在这个窗口连着聊 8 轮 → +2h。
- 昼夜低谷：**改系统时间**最省事（14:30 / 凌晨 2:00 各试一次，改完等一次状态重算）。

1. **午后该明显敷衍**。上午 10 点问一句、14:30 再问同一句，后者应该更短、更没耐心。
   改之前这两次几乎一样（扣分只跟债有关，跟钟点只有 ±40 的昼夜项）。
2. **深夜更明显**。凌晨 2 点 + 欠 2 小时，回话应该"懒得展开"。如果还是完整段落 ⇒ 乘法没生效，把原话发我。
3. **连夸不会越来越嗨**。连着夸它五次，语气应该**涨到某个程度就平了**，不是一次比一次腻。
   这条最容易被看出来 —— 以前是线性的，越夸越飘。
4. **紧绷时夸不动**。先说一句"我很赶，快点"，紧接着夸它一句，回话不该立刻变甜
   （规则 4：皮质醇高时多巴胺增益减半）。隔一会儿再夸，应该能甜起来。
5. **情绪不稳时同一件事更疼**。这条不好造，跳过也行 —— 它由血清素长期偏低触发，不是一两句能到的。

### 我明确没做的

1. **没做睡眠惯性**（刚醒那十几分钟的钝）。需要"醒来的时刻"，我们只有"用户开始说话的时刻"，
   当真会在凌晨三点说"你刚睡醒"。宁缺勿编。
2. **没做经前情绪（PMS）**。会变成刻板印象机器，且做不到"有由头"。
3. **没做性格随情绪漂移**（baseline drift）。性格变化必须走 `PersonaDelta` 账本，不能让状态自己改人格。
4. **没引入"让 LLM 每轮自评情绪数值"**。激素仍只由 `Director` 的事件闭集驱动 ——
   BetterSimTracker 正是在这上面翻过车（模型自评的数不可信）。
5. **没装机、没 push**。

---

## 第十二批（2026-11-14）激素：四个半衰期换成人的数值

> 用户 m00467：「你得去查阅与人类相关的激素的学术论文，要非常具体，
> 要到激素的激发条件，如何激发，反应等等等等，达到和人类接近的激素系统」
> 完整依据（四条大表 + 出处 + 数值曲线 + 刻意不做的四条）：`docs/工程/激素系统文献依据-2026-11-14.md`。

### 起因（一句话）

上一批把**形状**改对了（线性 → 指数、加了互锁），但四个半衰期还是拍的：2h / 8h / 24h / 4h，
好看、好解释、没有出处。这次把它们换成能指到论文的数。

### 做了什么（离线）

| 层 | 文件 | 一句话 |
|---|---|---|
| 新增 | `android/personalogic/.../HormoneKinetics.kt` | 文献常量表 + `stressReactivity(hour)`，每条都写出处与"这是文献值还是产品量" |
| 参数 | `Hormones.kt` | `HormonePolicy.Default` 改为从 `HormoneKinetics` 取值；表头四行加上依据列 |
| 机制 | `Hormones.kt` | `advance` 加**规则 8：HPA 负反馈**（越高落得越快） |
| 机制 | `Hormones.kt` | `step(..., hourOfDay)` 加**规则 9：夜间反应性**（夜里同一句凶话反应小 15%） |
| 接线 | `PersonaRuntime.kt` / `AppContainer.kt` | `apply(event, hourOfDay)`；四处调用传真实时刻；生理 `recordTurn` 复用同一个 `hourNow` |
| 测试 | `HormoneEngineTest.kt` | 改 2 条**语义**（见下）+ 新增 5 条 |

参数对照：

| 激素 | 旧 | 新 | 依据 |
|---|---|---|---|
| 多巴胺 | 2h | **30min** | 相位 burst 只有 100–200ms，2 小时是产品量 |
| 血清素 | 8h | **6h** | 色氨酸耗竭的周转 5–7h |
| 催产素 | 24h | **45min** | 血浆 t½ 只有 4.5min，行为余韵 30–60min |
| 皮质醇 | 4h | **66min** | 血浆 t½ 66min；应激后 60–90min 回基线 |

### 两条被推翻的旧测试（这次最值得看的部分）

1. 旧 `多巴胺比催产素落得快得多` 要求"差 5 倍以上" —— 那是 2h vs 24h 的产物。
   按文献两者是同一量级（30 vs 45 分钟），继续要求 5 倍差就是在测参数不是测行为。
2. 旧 `催产素是最黏的那一条` 断言"6 小时后仍 >65" —— 按文献 6 小时后早该回中性。
   继续黏着是"记性"，不是"激素"。

⚠️ 这两条**不是**改断言让它变绿，是断言背后的认知被文献否掉了。旧值旧理由都留在注释里。

### 验证状态

**已验证（Claude 离线）**：`:personalogic:test` **806/0**（早前 801，净 +5）、
`run_logic_tests.py` **OK (4311)**、`verify_all.py` **14/14**（163s）。
**未装机、未 push**。

### 要你在真机上看什么

1. **亲近感的余韵变短了**（45 分钟）。聊到很亲近之后隔一小时再回来，
   那股"格外黏"应该退掉了。
   ⚠️ 如果退得太干净、像翻脸不认人 —— 那**不是半衰期的问题**，是记忆/关系线没接上，
   把原话发我，我去查那一层。
2. **争执一小时内平复**。被凶（皮质醇 95 那种）之后，1 小时该落到 67 左右、3 小时 52。
   若两小时后还明显记仇，先怀疑关系线/事件残留，不是激素。
3. **连夸两次，第二次几乎没反应**。饱和（`dampen`）还在，加上半衰期更短，
   第二次夸奖的增益会很小 —— 这是**预期的**，别当 bug。
4. **深夜 vs 白天**：同一句重话，凌晨 2 点的皮质醇增量比上午 10 点少约 2 点（18→15）。
   这个差异**肉眼基本看不出来**，主要看"有没有报错/有没有跳变"，不必测数值。
5. **跨零点不炸**：23:59 和 00:01 各发一条同样的负面消息，不应出现异常或状态跳变。

### 我明确没做的

1. **没改事件幅度**（Praise +12/+6 那套）。"一句夸奖折合多少 nmol/L"不存在，
   硬凑一个数只会让人以为它有出处。现在的值是标定值，保持不动更诚实。
2. **没把昼夜曲线加到 cortisol 上**。晨峰是正常生理高峰，不是"她很紧张"；
   加上去会让角色每天早上都紧绷、晚上都莫名放松 —— 那是时钟，不是情绪。只用它**校正反应性**。
3. **没做去甲肾上腺素 / 褪黑素 / 睾酮 / CAR**，理由逐条写在文档第 6 节
   （粒度不够、与睡眠债重复计分、没有"胜负"事件、没有"醒来"事件）。
4. **白天不分上午下午**。那一层证据互相矛盾（上午 vs 下午做 TSST 谁反应更强，研究结论不一致），
   既然不确定就不编。
5. **没装机、没 push**。

---

## 第十三批（2026-11-14）激素第二轮：互锁逐条对文献，新增「社会支持缓冲」

> 用户：「继续查阅和推进」。接着第十二批（改了时程）往下做 **耦合**：
> 那 10 条互锁里哪些真有文献支持。追加在
> `docs/工程/激素系统文献依据-2026-11-14.md` 的第 10 节。

### 结果一句话

**新增 1 条（依据很强），其余 9 条只做证据分级，一个数不动。**

### 新增规则 10：社会支持缓冲

Thorsteinsson & James 1999 元分析（22 项实验 / 26 组比较 / 56 个效应量），
「实验性地给或不给社会支持」对实验室应激的影响：

| 指标 | d |
|---|---|
| 心率 + 收缩压 | 0.61 |
| 舒张压 | 0.51 |
| 皮肤电 | 0.25 |
| **皮质醇** | **0.83** |

皮质醇是这几个里**最大**的一个。机制上也接得上：催产素能神经元直接抑制
下丘脑 CRH 神经元 —— 社会接触释放的催产素掐住 HPA 轴最上游。

实现：**催产素 ≥ 65 时，负面事件的皮质醇增量 × 0.75**（Conflict 的 +18 → +14）。

三条边界（代码里写死了注释）：
1. **只削负面幅度，不给正向收益** —— 不是"陪着就不疼"，是"同样的话扎得浅一点"；
2. d = 0.83 是「有/无支持」的组间差，不是线性系数；
3. 0.75 是产品标定，文献只支持"这一条该有、且不小"。

累积顺序：规则 5/6 → 规则 10 → 规则 9（昼夜）。
最极端：18 ×1.5（血清素低）= 27 → ×0.75（有人在）= 20 → ×0.85（凌晨）= 17。

### 顺带做了两件"不改代码"的事

**① 10 条规则分成三档证据强度**（写在 `HormoneEngine.step` 的 KDoc 表格里）：

- **机制明确**（可以替参数辩护）：8 HPA 负反馈、9 昼夜反应性、10 社会支持；
- **方向有依据、系数标定**：1、2、4、5；
- **纯产品假设**（只能替方向辩护）：3（高催产素→血清素回得快）、6（兴奋时对压力钝）、7 饱和。

⇒ 后来人想动哪个数字，先看它在哪一档。

**② 事件幅度排序审计：结论是一个数都不改。**

按 Dickerson & Kemeny 2004（208 项实验室研究）的框架，皮质醇反应大小取决于
**社会评价威胁**与**不可控性**；单纯任务压力几乎不升，"只是有人在旁边"也不足以引发。

| 事件 | 评价威胁 | 我们的值 | 判定 |
|---|---|---|---|
| Conflict 争执 | 是（被指责） | +18 | ✅ 最大 |
| Rushed 被催 | 弱（隐含不满） | +10 | ✅ 次之 |
| Neglected 被冷落 | 不是评价，是社会排斥（研究里反应弱且不一致） | **0** | ✅ 保守正确 |
| 正向四种 | 无 | 0 | ✅ |

**"查完发现是对的"也是结果** —— 这轮没有为了改而改。

### 验证状态

**已验证（Claude 离线）**：`:personalogic:test` **810/0**（806 + 4）、
`run_logic_tests.py` **OK (4315)**、`verify_all.py` **14/14**（200s）。
**未装机、未 push**。

新增 4 条测试钉的是：有人在时 +14 而非 +18、催产素 64 不触发（门槛是硬线）、
缓冲不影响正向事件、系数填 1.0 可关掉（对照组）。

### 要你在真机上看什么

1. **先亲近、再吵架，比直接吵要轻**。聊到明显亲近（催产素上去了）之后再说一句重话，
   反应该比"一上来就吵"轻一点。这条是全批最值得看的 —— 它是**唯一**一条
   让"陪伴"在生理层产生实际作用的设计。
2. **只有 45 分钟以内的亲近才算数**。隔一小时再吵，"刚才的亲近"已经退掉了，
   不该再有缓冲。若还有，说明催产素没按新半衰期落。
3. **别期待"陪着就不疼"**。缓冲只削 25%，且只削负面。她该不高兴还是不高兴 ——
   如果变成"怎么吵都不生气"，那是改坏了（门槛或系数写错），把原话发我。

### 我明确没做的

1. **没做习惯化**（同一类负面事件反复发生，反应逐次变小）。
   Barthel 2025 综述：自然发生的习惯化里**只有 40% 的研究**观察到第二次反应显著下降。
   四成复现率不足以支撑一条机制；而且要实现它得新增"上一次事件类型"状态字段，
   动到序列化兼容 —— 代价大、收益不确定。
2. **没做可预测性**（可预测的应激反应更小）：我们没有"预告"这个事件类型。
3. **没做恢复期的社会缓冲**：规则 10 已把峰值压低，恢复自然变短，再建模一次是重复计分。
4. **没改任何事件幅度**（见上面的排序审计）。
5. **没装机、没 push**。

---

## 第十四批（2026-11-15）表演指导：让后台 AI 说一句「这轮该怎么演」

前两轮的激素只解决"她现在什么状态"。这一轮补的是另一半：
**同一个状态，怎么落地成这一句话** —— 那是公式算不出来的场景知识。

### 这条线怎么走的（一句话版）

```
激素 → Hormones.portrait()（纯行为档位，如「有点紧」）
     → DirectorPrompt（后台=导演，不是聊天的人）
     → "performance": "先别追问，把话说短。"
     → PerformanceGate（四类禁词 + 数字 + 长度 + 防抖）
     → PersonaAssembler L3 末尾「## 这一句怎么演」
     → 前台照这个演
```

**关键取舍**：只让它写**一句话**，不让它重写人格提示词、也不让它输出数值。
理由写在 `Performance.kt` 的类注释和 `docs/工程/激素系统文献依据-2026-11-14.md` §11：
重写会废掉 L0–L2 的稳定前缀（KV-cache 全失效）、输出数值会和 `toneDelta()` 两份真相打架。

### 会看到的四处行为变化

1. **L3 多一段**（只在指导有效时）：`## 这一句怎么演\n先别追问，把话说短。`
   有效期 **20 分钟**（比最短的行为半衰期 30 分钟略短）—— 过期就静默退回旧模板句。
2. **后台失败/断网不受影响**：拿不到指导就是"这一轮没有指导"，不弹错、不重试。
3. **L3 状态句补了顿号**：原来是「你现在心情不错精神很好」（两个条件同时命中直接拼），
   现在是「你现在心情不错、精神很好」。**这条每一轮都会变**，属于顺手修的既有 bug。
4. **一条挂不住**：激素没变时，后台想换措辞会被拒（日志 `表演指导被拒：激素没变，沿用上一条`）。
   这是防"同一个状态演两遍越演越夸张"——真机上最难发现的一条。

### 顺便补的一处（不是本批设计，但要交代）

`AppContainer.proactiveTick()` 里有一句 `if (chatMode().isAlive)`（在场模式的接线），
但 `chatMode()` 从来没被定义过 —— 编译是红的。我补了最小实现：
`chatModeValue`（默认 `ChatMode.ROLEPLAY`）+ `chatMode()` + `setChatMode()`，
持久化键 `chat_mode_v1`。**默认 ROLEPLAY ⇒ 行为与之前一字不变**，
UI 上还没有切换入口（那条线的负责人自己接）。

### 已验证（Claude 离线）

- `:personalogic:test` **881 testcases / 0 failed**（新增 `PerformanceTest` 25 条 +
  `PersonaAssemblerTest` 4 条 + `DirectorParserTest` 5 条）
- `:app:compileDebugKotlin` 绿
- `tools/verify/verify_all.py` **14/14，失败 0，112s**
- **未装机、未 push**

### 要你在真机上看什么（3 条，按重要性）

| # | 看什么 | 怎么做 | 期望 |
|---|---|---|---|
| 1 | 后台到底返不返回 `performance` | 连说 5–6 句正常对话，抓 logcat 过滤 `表演指导` | 至少有一次 `表演指导：…`；若一次都没有，把一次完整的后台返回原文发我（可能是提示词里的字段名没被模型照抄） |
| 2 | 断网时是否安静 | 飞行模式 → 说两句话 | 不该弹任何错误；L3 里没有「这一句怎么演」段，也没有报错提示 |
| 3 | 会不会越演越夸张 | 同一个话题连说三句（情绪没有明显变化） | 指导**不该**每句都换；若三句三个说法，说明防抖没生效，把三条日志发我 |

第 1 条最重要。如果模型根本不写这个字段，问题在提示词而不是代码，我会改 `DirectorPrompt` 的字段名与示例。

### 我明确没做的

1. **没给自定义角色接这条路**：自定义角色走"整条替换装配器"，L3 本来就不出现，
   硬塞一句指导会和她的人设打架。要接得先让 L3 进得去。
2. **没做每轮必生成**（提示词里明确写了"大部分轮次写 null"）：高频生成会让指导
   从"点睛"变成"底色"，后台调用成本也翻倍。
3. **没让指导改状态**：状态仍只由 `HormoneEngine` / `PhysioPrompt` 改。
4. **没装机、没 push**。

---

## 第十五批（2026-11-15）在场模式：她自己上网看看、自己来找你

用户原话（m05259）：「打破第四面墙的那个模式，你要推进到基本实现的版本」。
这一批补的就是"基本"两个字缺的三块：**她看不见外面**、**她不会自己开口**、
**纪律段那句是假话**（写着"外面的世界你能看见的只有他给你的那一点"，加了联网就不成立了）。

### 这条线怎么走的

```
WebReader（维基随机条目 → 一言，两个源都不要 Key）
  → WebDigest（剔掉消歧义页、按句裁到 150 字、写成「## 你在网上看到的」）
  → 按天锁一条（上午说企鹅、下午说海獭，比不联网更假）
  → 提示词里紧跟在「## 你今天」之后

ProactivePlanner.decide（门槛原样复用：12 轮 / 3h 没说话 / 5h 没发过 / 23–8 静默）
  → LifeformPulse（只选"这一句什么由头"：网上见闻 / 手上这件事 / 早些时候 / 就是想说话）
  → oneShot 生成一到两句 → 通知
```

### 三处刻意的取舍

1. **门槛不复制**。`:personalogic` 只选内容，"该不该发"仍问 `:legacy` 的 `ProactivePlanner`。
   两套阈值迟早漂移，而且漂移时不会有任何测试变红。
2. **主动消息只发通知，不写进会话**。孤儿 ASSISTANT 轮会被
   `TasksScreen.kt:2283` 渲染成「（这一轮的提问没有保存下来）」卡片；
   要正常显示得给 `TurnEntity` 加标记 = DB 迁移，超出"基本实现"。
3. **取不到网不算失败**。`WebDigest.pick` 永不返回 null，兜底池 12 条都是可为真的常识
   （雨前土味的来源、旧书甜味的来源、海獭睡觉牵手），不放"今天某地发生了什么" ——
   后者编出来会被用户当场戳穿。

### 会看到的变化

| 位置 | 变化 |
|---|---|
| 提示词（仅在场模式） | 多一段 `## 你在网上看到的`；装配日志多一项「网上 有(N 字)/无」 |
| 通知 | 在场模式下由「她的今天 + 网上见闻 + 一个由头」生成，一天最多 2 条 |
| 设置页 | 新增两行开关：「让她上网看看」「她自己来找你」，默认都开 |
| 纪律段 | 「外面的世界你能看见的只有他给你的那一点」→「网上的东西你能看到，但只能看，去不了」 |

### 已验证（Claude 离线）

- `:personalogic:test` **883 testcases / 0 failed**（新增 `WebDigestTest` 26 条 + `LifeformPulseTest` 18 条 + `LifeformDisciplineTest` 2 条）
- `python tools/verify/run_logic_tests.py` → **OK (4390 tests)**
- `python tools/verify/verify_all.py` → **14/14，失败 0，122s**
- `:app:compileDebugKotlin --rerun-tasks` → `BUILD SUCCESSFUL`，`e:` 错误 0
- **变异验证 5/5 红**。其中两条是变异验证抓出来的**测试空洞**：由头不掺"几点"、
  截断不先削标点，原来都全绿。
- **未装机、未 push**。

### 要你在真机上看什么（3 条，按重要性）

| # | 看什么 | 怎么做 | 期望 |
|---|---|---|---|
| 1 | 见闻到底进不进得来 | 进在场模式聊两句，`adb logcat | findstr "在网上翻到了"` | 出现一条标题；若一直是「提示词装配：…｜网上 无」，把 `WebReader` 那几行 logcat 发我（可能 UA 或 DNS 不通） |
| 2 | 她自己开口 | 放着 3 小时以上不说话（一天最多两条） | 收到通知；点开进 app 接着原会话说话，**会话里不该出现「（这一轮的提问没有保存下来）」** |
| 3 | 关掉真的安静 | 关「她自己来找你」，长时间不聊 | 不该有任何通知；此时你找她，行为与之前一字不变 |

第 2 条最关键 —— 它是这一批唯一会"自己动"的东西，也最容易被误当成 bug。

### 我明确没做的

1. **没做网页正文抓取**：只用维基摘要与一言。抓正文要处理 HTML 与广告清洗，失败率会直接砸"她今天看到点东西"的稳定性。
2. **没做见闻进记忆**：见闻是当天的，抽进记忆会变成"她记得自己看过某条维基"，那是资料而不是陪伴。
3. **没做主动消息写进会话**（见取舍 2）。
4. **没装机、没 push**。


---

## 2026-11-16 虚拟屏改成走通道：三条真机检查

### 改了什么
`VirtualScreenHost` 不再自己拼 `su -c "input -d 5 tap ..."`，改成走 `RootActions`（先常驻通道、退回 su）。
参数校验统一在 `DisplayCommands`；`--display 0`、非法组件名、非白名单 input 子命令都会被拒。

### 请验三条
| # | 怎么试 | 期望 |
|---|---|---|
| 1 | root 机上让 agent 打开一个应用（会拉起虚拟屏） | 应用**不出现在主屏**；日志里有 `虚拟屏：已把 X 拉到 display=N` |
| 2 | 在虚拟屏里让 agent 输入一句带空格的中文（如「晚点 到」） | 输入框里是完整一句，不是被空格切成两段或报错 |
| 3 | 结束任务 | 应用先被 force-stop、再收屏；**不会**突然蹦到主屏全屏 |

---

## 第十六批（2026-11-16）表演指导第二轮：三条真机项砍到一条

> 用户：「这边全部交给你离线验证，你把对应模块推到可落地的版本」。
> 承接第十四批。**这一批不增加任何新功能**，只把上次留给你的三件事搬回离线，
> 顺手把第四批起的假绿问题记一笔。

### 一、上一批那三条"真机项"，其实只有一条是真机

| 第十四批写的 | 实际是什么 | 现在在哪 |
|---|---|---|
| 断网时是否静默跳过 | 一次 `offer(null)` 的返回值 | `PerformanceSlotTest.空值不清空旧值` |
| 连说三句会不会越演越夸张 | 同一激素签名下第二句的判定 | `...连说三句而激素没变时 指导只换一次` |
| 后台是否稳定返回 `performance` | **提示词问题**，不是代码问题 | 只能真机 —— 仍然归你 |

前两条以前长在 `AppContainer` 里，而 `AppContainer` 要 `Context`、`:app` 的单测
起不到 Android 运行时 ⇒ 等于零覆盖。现在抽成 `personalogic/PerformanceSlot.kt`
（不依赖 Android），逻辑全在测试里钉住。

### 二、现在只剩这一条要你真机看

| # | 看什么 | 怎么做 | 期望 |
|---|---|---|---|
| 1 | 后台到底返不返回 `performance` | 连说 5–6 句，`adb logcat \| findstr "表演指导"` | 至少出现一次 `表演指导：…`；一次都没有就把一条完整的后台返回原文发我 —— 那是提示词字段没被模型照抄，我改 `DirectorPrompt` 的字段名与示例 |

另外两条（断网、防抖）**不需要你再验**。但如果你在真机上顺手看到它们不对，
那说明离线替身漏了东西 —— 把日志发我，我把那一层补进测试，而不是让你重复验。

### 三、一个必须这么写的细节（给以后动这段代码的人）

`PerformanceSlot.offer` 里防抖的基准是 `value?.takeIf { it.isFresh(now) }`，
不是 `value`：

```kotlin
val now = clock()
val live = value?.takeIf { it.isFresh(now) }   // ★ 只用未过期的旧值
PerformanceGate.accept(raw, live, now, hormonesKey)
```

用 `value` 的话：状态真的没变 → 模型照实写回**同一句** → 被判「与上一轮完全相同」
→ 这条指导在 20 分钟后**永久失效**，而日志只显示"被拒"，没人看得出是被自己的防抖拦的。
`过期后同一句可以重新生效` 就是钉这一条的。

### 四、已验证（Claude 离线）

- `:personalogic:test --rerun-tasks` —— **916 testcases / 0 failed**
  （其中 16 条是本批新增：`PerformanceSlotTest` 9 + `PerformancePipelineTest` 7）
- `:app:compileDebugKotlin` —— 绿
- `tools/verify/verify_all.py` —— **14/14，失败 0，150s**
- 单独跑 `gradlew :mcp:test :app:testDebugUnitTest --rerun-tasks` —— `289 tasks: 289 executed`，BUILD SUCCESSFUL
- **未装机、未 push**

### 五、一个坑，记在这里免得你也被误导

`:personalogic:test` 与 `:app:compileDebugKotlin` 报绿的时候，仓库其实**编不过**：
`--rerun-tasks` 全量重编才炸出来（`Redeclaration: AgeBand`）。

原因不是谁写错，是**增量构建的 up-to-date 判定**：personalogic 的旧 class 还在
`build/` 里，任务被判 UP-TO-DATE，测试跑的是旧产物。所以：

- 看到 `run_gradle_tests.py` 红**不要怀疑它**，它固定带 `--rerun-tasks`，那个红是真的；
- 反过来，只有增量绿的结论不要当"可交付"发出去。

那个具体的重名后来由写 `UserProfile.kt` 的那条线自己改成 `UserAgeBand`，
本轮**没有替它改名**（两条线同时动同一个枚举名，先改的会打断另一方正在写的测试）。


### 如果失败，请贴这三处
`虚拟屏命令失败（…）` / `启动失败：…` / `虚拟屏：收屏前停 X 失败（…）` —— 这三句都带了原始退出码与 stderr。

## 第十七批（2026-11-16）关于你：让角色一上来就知道对面是谁

### 为什么要加这一页

之前 app 里角色的人格、身体、世界书全是用户填的，唯独**对面那个人是白纸**。
结果每一段新关系都从「你是做什么的」「你平时喜欢什么」开始 —— 用户要花几十轮
把自己讲一遍，而且换个角色就得再讲一遍。

现在多了「设置 → 关于你」：称呼、性别、年龄段、同住、工作、最近在忙什么、
说话习惯、喜欢 / 不喜欢 / 雷区、身体上要注意的、作息、已经发生过的事、补充。
它作为一段 `## 关于他` 进系统提示词，位置在基础提示词之后、世界设定之前。

入口在「角色设定」**上面一行**（先定"对面是谁"，再定"说话的是谁"）。

### 要你在真机上看的（三条，都很短）

1. **填了就该知道**：随便填三四格（比如工作 + 一句雷区 + 作息）→ 保存 → 回聊天，
   自己发一句平常的开场（不要提档案里的内容）。她**不该**再问「你是做什么的」，
   也不该逐条盘问。
2. **清空就该忘**：回设置把那几格清掉 → 保存 → 再聊一句。她应该恢复正常提问。
   如果清空后还说得出档案里的东西，说明装配没接上（大概率是"自定义角色"那条分支漏了）。
3. **深色 / 浅色下的 chip**：那一页有几组单选 chip（性别、年龄段、同住、说话习惯）。
   看选中态在两种主题下**字和底是否还分得开** —— 之前分段控件就栽在
   `PaColor.Accent` 底 + `AccentText` 字不可读上。

### 不用你验的（我这边已离线验过）

- 提示词段的形状（无列表符 / 无加粗 / 无破折号 / 无元话语 / 无排比腔），
  已挂进 `PromptVoiceTest.productionSections()`，腔调不对测试直接红。
- 预算裁剪：`MAX_CHARS = 900`，超了按优先级**整行**删（不是截半句）。
  这条被变异验证钉住了 —— 改成"按字数截断"会立刻红。
- 称呼让位：角色设定里已经写了「怎么称呼你」时，档案里那格**整行不输出**。

### 已验证（Claude 离线）

- `:personalogic:test --rerun-tasks` —— **917 条 0 失败**（`UserProfileTest` 16 条 + `PromptVoiceTest` 20 条）
- `:app:compileDebugKotlin --rerun-tasks` —— `errors=0`
- `tools/verify/run_logic_tests.py` —— **OK (4426 tests)**
- `tools/verify/verify_all.py` —— **14/14，失败 0，84s**
- 变异验证 —— **3/3 全红**（称呼让位 / 预算裁剪改成截断 / 作息睡点起点互换），改完按字节还原，SHA256 与基线一致
- **未装机、未 push**（用户要求先不装机）

### 如果第 2 条不对，请贴这个

`adb logcat -s PocketAgent`，找 `用户档案：` 与 `提示词装配：` 两行 ——
前者是启动读回的结果（`已加载（N 字）` 或 `未设置（提示词里不加这一段）`），
后者能看出那一段到底有没有进提示词。

---

## [2026-11-17 19:40] 「红字：外文占了太多」真因不是外文 —— 模型绑着已删的 Key（已真机验证）

- **状态**：已交付
- **来自**：claude
- **需要对方做什么**：真机上把分身里那几个旧会话（显示「正在等模型回复…」+「中止」）清掉，顺手确认一下不再复发

### 背景

用户报：开了应用分身之后，每条回复都是红字「模型这次返回的内容里外文占了太多，清洗后几乎没剩下中文。请重试一次」。

第一轮我先修了 `OutputSanitizer`（清洗不再把整条回复丢成红字）—— **那只是表象**。用户让我直接上设备（`1e47d871`，分身 = user 999 / XSpace），加诊断日志重装后抓到：

```
I ContainerChatPort: 模型绑定的凭据：b9bcbc0b-aca2-43a9-8b8e-328cb752583a
W CredentialRepository: useKey：凭据 b9bcbc0b-… 在本机不存在（模型可能还绑着一条已删的 Key）
```

装配完成到失败只有 3 ms，没有任何 provider/HTTP 日志 ⇒ **模型从来没被调用**。旧代码把 `useKey` 返回的 `null` 一律翻译成"外文太多"，所以看起来像内容清洗问题。主实例正常、分身每条都红，差别就在这个实例里模型行绑的凭据 id 已经不存在了。

### 改了什么

| 文件 | 内容 |
|---|---|
| `:keymgmt` `CredentialRepository.kt` | `useKey` 三条静默 `return null` 各补 `Timber.w`；新增 `knownCredentialIds()` / `llmCredentialIds()` |
| `:modelrouter` `ModelPicker.kt` | `pickModel(..., knownCredentialIds: Set<String>? = null)`：只从"凭据还在"的模型里挑；钉死的模型 Key 失效时回落到能用的那个并说明原因；全都失效时理由直指 Key |
| `:modelrouter` `OrphanRepair.kt`（新） | `adoptionTarget` / `ambiguous`：0 条不动、2 条以上**不猜**（宁可报错也不把 OpenAI 的模型接到 Anthropic 的 Key 上） |
| `:keymgmt` `ModelConfigRepositoryImpl.kt` | `repointOrphans(known, credentialId)`：孤儿配置改绑到唯一的对话用凭据，保留 label/层级/价格/角色 |
| `:app` `AppContainer.kt` | `pickModelFor` 拆 `pickModelOnce` + `adoptOrphanModels`，第一次挑不到才改绑再重挑 |
| `:app` `ChatPort.kt` | "没拿到回复"的提示改成可操作的（去「设置 → API Key」点一下「校验」） |

### 已验证（Claude 离线）

- `:modelrouter:test` —— **189 条 / 0 失败**（`OrphanRepairTest` 7 条 + `ModelPickerTest` 新增 4 条）
- `tools/verify/run_logic_tests.py` —— **OK (4447 tests)**
- `tools/verify/verify_all.py` —— **14/14，失败 0，86s**
- 变异验证 —— **3/3 全红**（`adoptionTarget` 去掉 `distinct()` / 改成 `firstOrNull()` / `displayable` 去掉「还剩中文就算能看」），改完按字节还原，SHA256 与基线一致

### 已验证（真机，`1e47d871` 分身 user 999）

改后发一条消息，日志：

```
I ContainerChatPort: 模型选择：deepseek-v4-pro（「deepseek-v4.1-flash」绑的 Key 在这台设备上找不到了，这次自动挑了 「deepseek-v4-pro」（均衡档，思考强度 high））
I ContainerChatPort: 模型绑定的凭据：6fa90252-7b65-4f2f-afa7-e45f7ba1c986
```

回复正常返回（`在。这俩词我没看懂。…`）⇒ 红字消失，模型真被调用。

### 还没验证 / 要你看的

1. **旧会话里的孤儿轮**：分身历史里 `probe` 那条仍显示「正在等模型回复…」+「中止」，是旧版本被杀进程留下的、数据库里没有对应回复的轮次。新版不会再产生，但历史里的不会自己消失 —— 你看要不要顺手清掉（或我加一个"无结局轮次自动收起"的处理）。
2. **全孤儿的那种情况我没在真机上复现出来**：他这台设备上至少有一条模型（`deepseek-v4-pro`）绑的凭据是活的，所以走的是「钉死的模型失效 → 回落」这条分支；`repointOrphans`（22 条全绑死 Key 时自动改绑）只有单测覆盖，没有真机样本。
3. 深色模式下「设置 → 后台 AI」那页的抽屉（自绘的，不是 `ModalBottomSheet`）值得看一眼：遮罩、圆角、收起按钮对不对。

### 不用你验的

`displayable()` 的三段阶梯（严格清洗 → 宽松清洗 → 原文兜底）已挂 `OutputSanitizer` 单测，改回"一刀切丢弃"会立刻红。

---

## [2026-11-17 20:15] 她的作息开始拦消息了：Presence 最小切片已接线，要你三个时刻各看一次

- **状态**：待办
- **来自**：claude
- **需要对方做什么**：装机后按「四」在 12:20 / 21:35 / 23:40 之后各观察一次，把回复原文与 logcat 里那两类行贴回来。

### 一、这一刀解决什么

"她知道现在几点"本来就有（`Physiology`：凌晨、刚醒、欠睡、久坐、午后）。
缺的是**她不知道自己手上有没有事** —— 于是 12:30 能一边说"我先去吃饭"一边继续长回复；
21:40 说"我去洗澡了"，三秒后又回你一大段。

这次只加一个维数：**此刻她在吃饭 / 在洗澡 / 已睡下 / 在**。

关键取舍（详见 `docs/工程/作息与在场-借鉴real-life-2026-11-17.md`）：
**她的作息不产生台词，只产生两个后果** ——

| 后果 | 谁用 | 回答什么 |
|---|---|---|
| `PresenceEngine.gate()` | `AppContainer` 的两个 tick | 她**能不能**主动发消息 |
| `PresenceEngine.promptLine()` | `PersonaAssembler.renderState` | 这一句**该怎么**说话（**只有吃饭、洗澡两条**） |

而且措辞刻意写成「这个点你一般在吃饭」而不是「我正在吃饭」：我们没有"她吃过"的记录，
说"刚吃完"就是撒谎 —— 这正是 `Physiology.kt` 类注释那条纪律（不做饥饿、不编由头）。

### 二、已验证（Claude 离线，**不需要你复现**）

- `:personalogic:test` —— **948 条 / 0 失败**（改前 926：+19 是 Presence 本体，+3 是下面「六」那层模式开关）
- `:app:compileDebugKotlin` —— **BUILD SUCCESSFUL**（接线编译通过）
- `tools/verify/run_logic_tests.py` —— **OK (4469 tests)**（改前基线 4447）
- 新测试钉住的不变量：热聊期间恒 `AT_HAND`（含对睡觉生效）；睡 8h15m 跨午夜首尾；洗澡 = 洗 14 + 吹 7；
  洗澡开始时刻 30 天里至少 15 个不同值**但同一天必须相同**；200 天浮动不超幅度；60 天三餐窗口两两不重叠；
  1440 分钟全走一遍不出现负数；提示词**不含任何数字**、且必须说"这个点"而不是"我正在"。

### 三、改了哪里

| 文件 | 内容 |
|---|---|
| `:personalogic` `Presence.kt`（**新**） | 时刻表推算（无状态、无心跳）+ `Presence`/`Where`/`gate`/`promptLine`/`minuteOfDay` |
| `:personalogic` `PresenceEngineTest.kt`（**新**） | 19 条 |
| `:personalogic` `PersonaAssembler.kt` | `renderState` 增 `presenceLine`；**两处早退各加 `&& presenceLine == null`**（否则状态中立时她吃饭白接）；新增 `localOffsetMinutesOf` |
| `:app` `AppContainer.kt` | 新增 `presenceBusyReasonOf`；`proactiveTick` 与 `lifeformProactiveTick` 各插一处提前 return（见「六」：现在都包在 `switches.presence` 里） |
| `:personalogic` `ChatMode.kt` | `Switches` 新增 `presence` 开关：`ROLEPLAY=false` / `WORK=false` / `LIFEFORM=true` |
| `:app` `AssistantPrompt.kt` | `build(...)` 增 `presence = false`，透传给 `AssemblyInput.presence` |
| `docs/工程/` | 设计全文（含为什么大半没抄上游） |

`:legacy` 的 `ProactivePlanner` **一个字没动** —— 一度给 `Facts` 加过 `busyReason` 字段又撤回：
`Facts` 装的是可观测事实，而"她腾不出手"是 gate 造出来的结论；更要紧的是**提前拦**
（`lifeformProactiveTick` 里 `refreshLifeformWeb` 是一次网络请求，她洗澡时不该还去抓网页），
字段式否决做不到提前。门禁留在调度层。

### 四、要你在真机上看的三处（**都在「在场」模式下**）

⚠️ 2026-11-17 20:30 收窄：这套作息**只在「在场」模式生效**（详见「六」）。
角色扮演 / 工作模式里她随叫随到 —— 那里面时间是停的，不存在"她在吃饭"这回事。

1. **12:20 左右**（午饭窗口 12:15 ±10 分钟、持续 30 分钟）发两句：回复是否变短、有没有"边吃边回"的感觉。
   最坏的表现是**照常长篇** —— 那说明 `presenceLine` 根本没进提示词。
2. **21:35 左右**（洗澡窗口 21:30 ±25 分钟，洗 14 + 吹 7；抖动大，所以 21:05–22:15 都可能）：
   重点看有没有"是听见提示音才腾出手看的"这类语气，而不是"我正在洗澡"。
3. **23:40 之后不要打开会话**，等一个 tick 周期，`adb logcat` 里抓：

   ```
   主动消息：她腾不出手（她这会儿已经睡下了（剩 N 分钟醒））
   在场主动消息：她腾不出手（…）
   ```

⚠️ 别把"没出现这条"直接当成坏了：主动消息本来就有 12 轮 / 3 小时 / 5 小时三道门槛，
先被前面拦掉时日志里是另一条 skip 理由。**要看日志里 skip 的理由是哪一个**。

4. **反向验收（本轮新增，别漏）**：切到**角色扮演**或**工作**模式，同一个 23:40 之后等一个 tick，
   主动消息**应该照常发**，日志里**不该**出现「她腾不出手」。
   出现就是有调用点没跟上 `switches.presence`（这一条是本次收窄作用域的唯一验收口径）。

### 五、已知缺口（都已写进文档，不用报我）

1. **睡着时被消息吵醒，现在没有语气** —— `gate` 只拦主动消息；你深夜发消息时
   `promptLine(SLEEPING)` 返回 `null`，于是她**回得跟白天一样利索**。这是最容易察觉的假，
   下一步最该补，但要先定它和 `Physiology` 的"凌晨困"谁先说话。
2. `LifeformDay` 的 beat 若写了"在厨房"，会和 `Presence` 的饭点重复（目前没去重）。
3. 晚饭后坐满 3 小时，"久坐"与饭点可能同时命中，需要真机看会不会变成她一直在抱怨。

---

## [2026-11-17 20:30 补] 收窄到「在场」模式：角色扮演 / 工作模式**不再**被拦

- **状态**：待办（与上面 20:15 那条**合并验收**，别当成两个独立任务）
- **来自**：claude
- **需要对方做什么**：按「四」观察时**两条路都走一遍** —— 在场模式被拦，角色扮演 / 工作模式不该被拦。

### 为什么收窄（是用户口径，不是我们自己想的）

用户 2026-11-17：「这个功能是要加入到一个模式里的，就是（角色）的窗，深度参与用户生活的一个模式，
后续会根据需求，部分加入其他模式」。

这不像"先做一半"，而是机制本身的边界：**角色扮演 / 工作模式里时间是停的** ——
用户不说话她就不存在。给一个不存在的人按时刻表，提示词里就会出现「我正在吃饭」，
模型照着说，而用户从没经历过那顿饭。只有「在场」模式里"她有自己的今天"，时刻表才是她的此刻。

### 怎么落地的（一个开关，不是写死 `mode == LIFEFORM`）

`ChatMode.Switches.presence`（`:personalogic`）—— 三个模式显式各填一次：
`ROLEPLAY=false` / `WORK=false` / `LIFEFORM=true`。
用 `Switches` 而不是 `when (mode)` 是既有设计：**将来加模式漏填这一格，编译不过**；
写成 `when` 加个 `else ->` 就又是一个静默的错。

三处消费点，一个判定源：

| 位置 | 作用 |
|---|---|
| `PersonaAssembler.renderState(state, performance, presence)` | 是否写「## 你手上正有事」那一段 |
| `AppContainer.proactiveTick` 主体 / `lifeformProactiveTick` | 主动消息是否被作息拦（两处都判 `chatMode().switches.presence`） |
| `AssistantPromptBuilder.build(..., presence)` → `AssemblyInput.presence` | 透传，**默认 false**，老调用点行为一字不变 |

⚠️ 判 `switches.presence` 而不是 `mode.isAlive` 是刻意的：前者是"她有没有作息"，
后者是"她有没有自己的今天"。现在两者只有在场模式同真，但语义不同 ——
将来若给某个模式单独开"会去吃饭但不算活着"，不该去动 `isAlive`。

### 关掉它，用户看到什么

什么都没有：她永远随叫随到，不会说"这会儿腾不出手"，提示词里也不多那一段 ——
就是加这个功能之前的样子。**这正是「角色扮演 / 工作模式」现在的状态，也是本次的验收对象。**

### 已验证 / 未验证

- **已验证（Claude 离线）**：`:personalogic:test` **948 / 0**（+3：`只有在场模式让她有作息`、
  `在场模式会写她这个点在做什么`、`没开 presence 的模式不替她编作息`）、
  `:app:compileDebugKotlin` **BUILD SUCCESSFUL**、`run_logic_tests.py` **OK (4469 tests)**。
- **未验证（需真机）**：见「四」第 4 条反向验收。


---

## 2026-11-18（转告）给做「作息与在场」的协作组留了一封信

`docs/协作/TO-协作组-presence-2026-11-18.md`

我在核对 agent 侧接线时顺手发现三处与 `Presence` 有关的问题（热聊期间洗澡文案里的"听见提示音"、
beat 与饭点重复、`SLEEPING` 的语气归属），都带行号与建议，**没有改他们的任何文件**。
看到的人请转给对应协作组；真机部分（深夜被叫醒的语气）仍需你们验，信里第 3 条是口径建议，不是验收项。


---

## 2026-11-18 M1.5：内置 Python 引擎进了 APK，请验三件事

### 背景
`targetSdk = 36`，Android 10 起不能执行 app 私有目录里的文件 ⇒ "解 rootfs 跑"对普通用户不通。
所以改用 **Chaquopy（进程内 CPython，不 exec 任何文件）**，这是"所有用户都能跑 Python"的唯一路子。

### 请验
| # | 怎么试 | 期望 |
|---|---|---|
| 1 | 装 debug 包，`adb shell dumpsys package com.pocketagent.debug | grep -i abi` | 有 arm64-v8a（若装的是 armv7 机则看 armeabi-v7a） |
| 2 | 在设置里（**暂时没有开关**，我可以给一段临时调用）打开内置引擎后，让 agent 跑 `python3 -c "print(2**100)"` | 回执 `exitCode=0` 且输出 `1267650600228229401496703205376` |
| 3 | 跑一个会抛异常的命令 `python3 -c "1/0"` | `exitCode=1`，stderr 里有 `ZeroDivisionError` 与行号，app 不崩 |
| 4 | 跑死循环 `python3 -c "while True: pass"`（timeoutSec=3） | 3 秒后返回 `exitCode=124`，**之后该后端拒绝再跑**（提示重启应用） |

### 已知缺口
- 开关没有 UI：需要我在下一轮接进设置页；在那之前要用临时 adb/代码打开。
- APK 大了约 8–10 MB（两套 ABI 的 CPython）。
- `.pyc` 未预编译（构建机没有 Python 3.10），首次 import 略慢。


### 补充：开关已经上界面了（同日）

设置 → **Linux 工作区 → 内置 Linux 引擎**，默认关。打开后上面四项才跑得起来；
关掉时 `linux_exec` 会直接说明「默认关闭」并列出代价，不会静默失败。


---

## 2026-11-18 M2：媒体处理（media_ops）已接线，请验四项

前提：设置 → Linux 工作区 → 内置 Linux 引擎 **开着**（媒体与它共用开关）。
准备：`adb push` 一个几十秒的 mp4 到 `/data/data/com.pocketagent.debug/files/linux/work/`（需 root 或 `run-as`）。

| # | 怎么试 | 期望 |
|---|---|---|
| 1 | `media_ops` action=`probe` input=`/work/a.mp4` | 回执含时长、分辨率、"有音轨/有画面" |
| 2 | action=`extract_audio`（不写 output） | 生成 `/work/a.m4a`，回执带文件大小 |
| 3 | action=`snapshot` atMs=`1500` | 生成 `/work/a.png`，能打开且是该时刻画面 |
| 4 | action=`transcode` | 生成 `/work/a.mp4`；若源是 HEVC/AV1 而机型不支持，应回 `转码失败（ExportException）…` 而不是崩 |

失败请贴：`media_ops` 的原始回执（`转码失败（…）` / `读不出这个文件的媒体信息（…）`）。


---

## 2026-11-18 M3：Termux 通道（借它的手跑命令）

### 原理
Termux 自己钉 `targetSdk 28`，所以能执行它目录里的二进制；我们通过公开接口
`com.termux.RUN_COMMAND` **请它跑**，不是绕沙箱。三条前提缺一不可。

### 请验
| # | 怎么做 | 期望 |
|---|---|---|
| 1 | 不装 Termux 时让 agent 跑 `linux_exec` | 走内置引擎（能跑 Python），回执说明"这是内置引擎：装不了包" |
| 2 | 从 F-Droid 装 Termux → 应用里允许"执行 Termux 命令" → Termux 里执行 `echo allow-external-apps=true >> ~/.termux/termux.properties`，再跑 `git --version` | 回执里是 Termux 的输出（`git version 2.x`） |
| 3 | 把 Termux 的权限撤掉再跑一次 | 立刻降级到内置引擎，文案里写"没给执行命令的权限" |
| 4 | 让 agent 跑 `curl -s https://example.com`（联网命令） | 内置引擎下应被白名单拒（说明要 Termux/root）；Termux 下应真的能跑 |

### 已知边界（不是 bug）
- Termux 读不到本应用的 `/work`（另一个 uid）。要处理我们的文件，用内置引擎或 root。
- `linux_read` 始终由应用自己读 `/work`，与哪个后端跑命令无关。


---

## 2026-11-18 M4：root 容器通道

### 请验（不需要根文件系统也能验前两条）
| # | 怎么做 | 期望 |
|---|---|---|
| 1 | root 机上把运行模式切到 root 档，让 agent 跑 `linux_exec` | 回执说明「root 有了，但还没有根文件系统」——**不是**"不可用"，也不是假装成功 |
| 2 | 把运行模式切回普通档再跑一次 | 降级到内置引擎（或 Termux），文案仍写清各自差别 |
| 3 | 若你手上有现成 rootfs（**M5 才做下载器**）：手动解到 `files/linux/rootfs`（必须含 `bin/`），再跑 `id` | 回执是容器里的 uid（chroot 下通常 `uid=0`） |

### 已知边界
- 没做 bind mount：容器里的 `/work` 只有 rootfs 自带时存在。
- `unshare -n` 只在 chroot 上生效；proot 不会假装能断网。


## [2026-11-18 22:05] 能力线入库（`b02fb4d`）—— 六条真机项，开关落盘我没有离线测

这条 commit 把另一批 agent 的 Linux 工作区 / 媒体处理 / 作息与在场整条线入库了，
同时我修了两处。**离线全绿：`run_logic_tests.py` OK (4581)、`:agentlogic:test` 440、
`verify_all.py` 14/14、变异 3/3 真红。** 下面六条碰不到真机就没法下结论，归你。

### 1. 内置 Linux 引擎开关会不会落盘（这条我确实没有离线测）
`android/app/src/main/kotlin/com/pocketagent/data/AppContainer.kt` 里这个开关之前
只写 `@Volatile` 内存字段，冷启动必回默认**关**，而 `media_ops` 跟着它一起失效 ——
等于"开了、重开 app 又关了"。现在写进 `pluginSourceStore`（`KEY_LINUX_BUILTIN`，
`linux_builtin_v1`），并在 `loadMaturePermission()` 里读回。

`AppContainer` 是 Android 容器，逻辑层测不到这段持久化（DataStore + Context），
所以只能你跑：
1. 设置 → Linux 工作区 → 打开「内置 Linux 引擎」。
2. `am force-stop com.pocketagent.debug` 后重开。
3. 该行应**仍为开**。关掉它再重开，应**仍为关**。

### 2. `media_ops` 是否真的跟着这个开关
开 → 让它「把这段录音抽成 m4a」应能跑；关 → 工具应报不可用，且文案指到
「设置 → Linux 工作区 → 内置 Linux 引擎」（`Media3Ops.availability()` 的 explain）。

### 3. `linux_exec` 三档降级文案
`LadderLinuxOps.availability()` 按 **root → Termux → 内置** 探测（从能力多的往少的找）。
- 你这台是 KernelSU root ⇒ 应走 `RootLinuxOps`。
- 有 Termux ⇒ 应走 `TermuxLinuxOps`。
- 都没有 ⇒ 内置，且 `explain` 里必须出现「这是内置引擎：装不了软件包、断不了网」。
  少了这句用户会以为 `pip install` 也能跑。

### 4. 首轮工具清单有没有预展开
之前 `ToolGroup.PRELOAD` 只列了 FILES / SYSTEM 的词，说「把这几个文件批量改名」
「把这段视频抽音轨转成 mp3」时 Linux / 媒体两组不会展开，模型得先摸到入口才可能用。
现在补了两组关键词。开局清单应**只有入口**（`linux_exec` / `media_ops` 在，
`linux_read` 不在）；说上面那两句之后，对应组应展开。

`ToolGroupsTest` 只钉住了"关键词命中就展开"，**真机上模型是否因此更愿意用**测不到。

### 5. Presence 的三个时刻
`android/personalogic/src/main/kotlin/com/pocketagent/personalogic/Presence.kt`
新的这一维是"手上有没有事"，不是第二个生理系统。
- 凌晨 3:00 跟她说话：应是睡着（`SLEEPING`，由 `gate` 拦在门外）。
- 21:40–22:00：应在洗澡（`SHOWER`，`promptLine` 有内容）。
- **热聊中（15 分钟内刚说过话）任何时刻都该是 `AT_HAND`** —— 这条是刻意让
  用户话语权压过作息表，请你重点试。

另外 `SLEEPING` 故意不再加第三段提示词（"困/刚醒"已经由 `PhysiologyEngine.promptLine`
写过，再写一遍模型会变僵）。如果你觉得凌晨她该说点什么，先告诉我，别直接加。

### 6. 深色模式下新增两行的可读性
「设置 → Linux 工作区」这一节（`SettingsScreen.kt` 里 `PaSectionTitle("Linux 工作区")`
+ 「内置 Linux 引擎」开关行）。之前踩过 `PaColor.Accent` 当底色导致字读不清的坑，
这次用的是既有 `PaListRow` + Material3 `Switch`，但真机深色下还是你看一眼最稳。
