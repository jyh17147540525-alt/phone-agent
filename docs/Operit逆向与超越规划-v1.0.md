# Operit 逆向工程与超越规划 v1.0

> 2026-10-18 · claude · 状态：**规划稿（待拍板，不执行）**
> 前置：`docs/同类项目对比与借鉴-v1.0.md`（workbuddy 的评估报告，2026-10-14）
> 触发：用户要求「做得比 Operit 好，逆向他们的优秀功能，避免重复造轮子」
> 方法：**源码级机制逆向**（GitHub API 索引 4383 个文件 + 精读 6 个关键实现），不做 APK 反编译
> 边界：本文只做规划与交互设计，不含实现；所有代码引用都是现有契约

---

## §0 一页纸

### 0.1 三句话

1. **功能数量这条赛道我们不追，也追不上** —— 他们 `app/` 单体 2418 个文件、20+ 家 provider、5 级权限 × 4 能力族、1 年 7 个月迭代。用「更多功能」打，是把我们唯一的资产（45 模块 + 离线验证器 + 纯逻辑可测）换成他们的资产。
2. **但「功能数量」不是用户感知的维度。** 用户感知的是三件事：**看不看得见（发现）、停不停得下（控制）、说不说得清（解释）**。这三件事恰好是**结构问题**，不是数量问题 —— 而结构是我们的强项。
3. ⇒ **主线：把已有的严谨后端，翻译成用户能感知的三拍闭环 —— 预演 → 执行 → 复盘。** 这是 Operit 的架构**做不到**的：他们的工具结果是字符串（`AITool` 只有 `name + parameters`），我们的工具结果带 `structuredContent`。

### 0.2 我们的现状（2026-10-18 实测，不是回忆）

| 维度 | 实测值 |
|---|---|
| Gradle `include` | **45 条**（含 6 个 `:plugin:*`、2 个 `:display*`） |
| **已登记但零源码的模块** | **9 个**：`:assistant` `:voice` `:voicelogic` `:tts` `:memory` `:onboarding` `:channel` `:contribute` `:update` |
| 主源码 | 222 文件 / **39,735 行**（不含 build） |
| 测试 | 103 文件 / **24,853 行**（比 0.63） |
| 离线验证器 | **`OK (1951 tests)`**（2026-10-18 实跑，exit 0）→ **`OK (2231 tests)`**（W1 落地后，2026-10-19） |
| MCP 工具 | **10 个**：`screen_read` `android_tap` `android_swipe` `android_type` `android_key` `context_offload` `context_recall` `office_tools` `office_run` `persona_brief` |
| 对话链路 | `ChatPort.send()` **整轮返回**（`StringBuilder` 收完再一次给 UI），**不流式**。W1 已备好流式适配层 `:agentui` 的 `StreamPump`，但 `ChatPort.sendStreaming()` **尚未接线** |
| 人格 | `:personalogic` 18 源文件 / 14 测试文件，**未在 UI 露出** |
| 卡片层 | **W1 已落地**：`:agentui` 纯逻辑模块（12 源文件），五类卡 + 三拍 + 失败卡 + 导出/回放。Compose 渲染**未做** |
| 记忆 | L0/L1 + 卸载已落地；L2/L3 未做 |
| 插件 | 只有 `:plugin:api`（12 文件）；`runtime` / `rules` / `script` / `store` / `devtools` **全空** |
| 悬浮层 | `:overlay` 只有 Service（只打日志），**无任何 UI** |

### 0.3 六个逆向靶点（第一批，按性价比排序）

| # | 他们的机制 | 我们的处置 | 成本 |
|---|---|---|---|
| 1 | **5 级权限 × 4 能力族**的工具矩阵 | 逆向 → 重构成「能力族 × 通道 × 风险」三轴，**UI 只问三句** | 小 |
| 2 | **会话四件套**（分支/迁移/摘要/导出） | 已有设计稿，**W2 落地**（不重复设计） | 小 |
| 3 | **Provider 装饰链**（限流/计量/思考质量/结构化桥） | 逆向 → `GatewayCore` 补三件 | 小 |
| 4 | **工具生命周期 Hook + 执行预算** | 逆向 → `:capabilitylogic` 做 typed hook | 中 |
| 5 | **ToolPkg**（manifest + 子包 JS + UI DSL + Hook + i18n） | 只借格式，**v1 禁 UI DSL** | 中 |
| 6 | **悬浮层五模式**（chat/ball/fullscreen voice/OCR/pet） | 只借形态 → **一球三态** | 中 |

### 0.4 五件明确不做（写进 README，挡住"我们也加一个"的提议）

终端 / Ubuntu、项目工作区与代码编辑器、内置多标签浏览器、3D 虚拟形象、统一市场后端。
理由见 §5.4 —— 每一条都会把「助理」这个定位稀释成「平台」。

### 0.5 如果只批一件事

**批 W1（`:agentui` + 五类卡 + 流式）。** 理由：它是其余五波的地基（会话/记忆/权限/语音都要落在卡片上），
且**全部离线可验**（≥120 条断言，不依赖真机）。W1 做完，"比 Operit 好"才有了第一个可指的东西；
W1 不做，后面每一波都会各自发明一套 UI 表达。

---

## §1 逆向的边界与方法

### 1.1 我们能看到 / 看不到

| 能 | 不能 |
|---|---|
| 源码结构、类名、状态机、数据模型、注释 | 真机手感、动画帧率、实际任务成功率 |
| README 与官方文档（ToolPkg 格式指南 55 KB） | 他们用户的真实抱怨分布（只有 220 个 issue 标题，样本偏） |
| 发布说明里的版本演进（v1.0 → v1.12） | 他们**没写出来**的设计取舍 |

⇒ **本文所有结论都落在"机制"层**；「谁的交互更顺手」这种结论**归 WorkBuddy 的真机体验**（见 `TO-WORKBUDDY.md`）。

### 1.2 数据快照（GitHub API，2026-10-18 实测）

| 项 | 值 |
|---|---|
| Star / Fork / Open issues | 8,794 / 745 / 220 |
| 仓库体积 | 591,280 KB（≈ 577 MB） |
| 源码文件（`git/tree?recursive=1`） | **4,383** |
| `app/` 文件 | **2,418**（`ui/features` 544、`core/tools` 191） |
| provider 适配器 | **20+**（OpenAI/Anthropic/Gemini/DeepSeek/豆包/Kimi/Qwen/xAI/Mistral/Ollama/MNN/llama.cpp…） |
| 工具权限层 | **5**：`STANDARD` `ACCESSIBILITY` `DEBUGGER` `ADMIN` `ROOT` |
| 许可 | LGPL-3.0-only |

### 1.3 三条纪律

1. **抄思路，不抄代码。** 他们的 `app/` 单体依赖 ObjectBox 与大量内部类，搬进 45 模块会同时破坏两边（`同类项目对比` §5 已论证）。
2. **真要复用代码，只搬"小而独立"的**（≤ 1 个文件、无 ObjectBox、无内部类）。LGPL-3 §2 允许升级为 GPL-3，**必须在文件头署名 + 写改动**。
3. **每条结论给证据路径。** 附录 A 列全部引用文件，任何人可复核。

---

## §2 十个机制拆解 ★ 本文核心

> 格式：**它怎么做 → 好在哪 → 软肋 → 我们怎么做 → 落点 → 验收**

### 2.1 工具权限矩阵（5 级 × 4 能力族）

**它怎么做**：`AndroidPermissionLevel` 五级；每级一套同形工具族 —— `*UITools` / `*FileSystemTools` / `*ShellExecutor` / `*DeviceInfoToolExecutor` / `*SystemOperationTools`。同一个「点屏幕」在无障碍、Root、Shizuku（admin）、调试四套实现里各有一份。`ToolGetter` 按当前权限挑实现。

**好在哪**：**能力（做什么）与通道（怎么做到）正交**。加一条通道 = 加一套实现，工具名和提示词一个字不改。这正是我们 `:action` 的「四通道抽象」想达到的效果 —— 他们把它做全了。

**软肋**：矩阵对**用户**不可见。用户看到的是「20 个工具 × 三态权限」，而不是「我允许它碰我的微信」。而且四套实现是四份代码，行为漂移是必然的（他们有 `DebuggerUITools` 等四份平行实现）。

**我们的做法**：**保留两轴，砍掉第三轴的实现数**。
- 能力族（读屏/点击/输入/文件/应用/系统）已经在 `:capabilitylogic` 的 `CapabilityCatalog` 里；
- 通道（无障碍/Shizuku/IME/悬浮窗引导）已经在 `:action` 的 `ActionExecutor` 契约里；
- **新增第三轴：风险等级**（`READ < WRITE < EXTERNAL < MONEY < IDENTITY`），由工具**声明**，不由通道决定。
- UI 不展示矩阵，只展示**三句话**（见 §3.7）。

**落点**：`:capabilitylogic`（`CapabilityRisk` + 分类器）、`:mcp`（每个 `McpTool` 增 `risk` 只读属性）。

**验收（离线）**：给定 10 个工具的调用，分类器输出稳定；`MONEY`/`IDENTITY` 一律不允许「一直允许」；风险未声明的工具**编译期**就该报（`McpTool.risk` 无默认值）。

---

### 2.2 会话四件套（分支 / 迁移 / 摘要 / 导出）

**它怎么做**：消息分支、历史分组与批量迁移、自动总结、上下文上限、导入导出、锁定。

**好在哪**：**"试错"有了基本单位**。同一问题换个问法不该污染原会话；导出是"数据属于用户"的兑现。

**软肋**：功能多但入口散（分支/迁移/导出分散在 `ExportDialogs`、`ChatHistorySelector` 等多处）。

**我们的做法**：**不重新设计**。`docs/会话侧边栏与全局记忆设计-v1.0.md` §3.5/§3.6 已经把「分支 + 导出/备份」补进设计，**W2 直接实现**。差异点是**入口收敛**：所有会话操作收进侧边栏的同一个「…」菜单，不做三处入口。

**落点**：`:app`（`SessionDrawer` + Room v4 迁移）、`:core:database`。

**验收（离线）**：分支树结构断言（父 id / 根 id / 深度）；导出 JSON 往返一致；删除父会话时分支的**降级策略**明确（保留为独立会话，不静默丢）。

---

### 2.3 Provider 装饰链

**它怎么做**：`AIService` 外面套一串装饰器 —— `RateLimitedAIService`（`SlidingWindowRateLimiter` + `RequestConcurrencyRegistry`）、`TokenTrackingAIService`、`StructuredToolCallBridge`、`ThinkingQualityMapping`、`MediaCapabilityProbe`、`ApiKeyPoolAvailabilityTester`、`LlmRetryPolicy`。

**好在哪**：**横切关注点全部收在装饰器里**，provider 实现只管协议。加一家的成本 = 一个文件。

**软肋**：装饰顺序不可见（谁在外层决定了重试是否计入限流），且没有"这一轮花了多少"的用户可见账。

**我们的做法**：`GatewayCore` 已经是「路由→熔断→解密→转发→计量」，**差三件**：
1. **限流**（按 provider+key 的滑动窗口）—— 现在是 `BudgetGuard` 的粗粒度熔断；
2. **能力探测结果透传**（图片/思考/工具调用），驱动 §3 的 UI（"这个模型不支持图片"要**提前说**，不是失败后说）；
3. **模型决策解释**（`ModelResolution.via` 已有，UI 没露）—— 「这次为什么用这个模型」一句话。

**落点**：`:provider:gateway`（新增 `RateLimiter`、`Capabilities` 透传）、`:modelrouter`（不动）。

**验收（离线）**：装饰顺序断言（限流在重试外层）；能力探测缺省值不谎报（`unknown` 必须显式）；`via` 的每一级都能生成人话。

---

### 2.4 工具生命周期 Hook + 执行预算

**它怎么做**：`AIToolHook` 七个回调（requested / intercept / permissionChecked / executionStarted / result / error / finished），`onToolCallIntercept` 可返回 `Block(reason)`；ToolPkg 的 hook 有 `ToolPkgHookExecutionBudget`（防止扩展把主线程拖死）。

**好在哪**：**扩展点长在生命周期上**，不在结果上。能"拦下来"和"只能看结果"是两个量级的能力。执行预算这条尤其成熟 —— 我们 `:plugin` 设计里没有。

**软肋**：`AITool` 是字符串参数（`List<ToolParameter>`），hook 拿不到类型化参数，只能靠字符串匹配。

**我们的做法**：`CapabilityGuard` 已经是"执行前拦截"，但**只有内置规则**，没有第三方扩展位。W5 加：
- `CapabilityHook`（typed，参数是 `CapabilityCall`，不是字符串）；
- `HookBudget`：**单次 hook ≤ 50 ms、单次调用 hook 总预算 ≤ 200 ms**，超时记审计并**放行内置判定**（不让扩展拖死助理）；
- 顺序钉死：`SafetyGuard（硬否决）→ PermissionStore（用户三态）→ Hook（可拦）→ 执行`。**Hook 不能越过 SafetyGuard**。

**落点**：`:capabilitylogic`。

**验收（离线）**：hook 超预算时被跳过且审计一条；hook 抛异常不导致调用失败（降级为"未拦截"）；顺序用记账型 stub 断言调用序列。

---

### 2.5 聊天协议：XML 文本流 vs 结构化事件

**它怎么做**：模型输出里带 `<think>` / `<tool name=… >` / `<tool_result>` / `<canvas>`，UI 用 `ThinkToolsXmlNodeGrouper` 分组、`ToolResultDisplay` 渲染。另有一条原生 tool call 通道（v1.6.3 起）。

**好在哪**：**任何模型都能用** —— 不支持原生 tool call 的模型也能"调工具"。这是他们 BYOK 广度的一部分。

**软肋**：UI 依赖模型把 XML 写对。标签少一个引号、多一层嵌套，渲染就退化；而**退化是静默的**（显示成一段普通文本）。

**我们的做法**：**不学 XML，学它的兼容姿态**。
- 主路径：MCP 原生 tool call + `structuredContent` → **能力卡**（§3.2）。卡片是**投影**，不进模型上下文 —— 模型看到的仍然只有 `ToolOutcome.text`，所以 UI 再花哨也不烧 token。
- 兼容路径：**不做** XML 解析。遇到不支持 tool call 的模型，失败卡直接给「换一个支持工具调用的模型」，并**列出当前模型的能力**（§2.3 的能力探测）。**把"模型不行"变成一句可执行的话**，而不是偷偷降级成文本。

**落点**：`:mcp`（`structuredContent` 规范）、`:agentui`（投影）。

**验收（离线）**：投影函数对 10 个工具的返回值都能产出卡片；`structuredContent` 缺失时降级为「纯文本卡」而**不丢这次调用**；卡片内容与 `text` **不重复展示**（同一事实只出现一次）。

---

### 2.6 记忆：图谱 + 多空间 + 混合检索

**它怎么做**：多记忆空间、文档导入与分块、节点关系编辑、按时间/语义/关系混合检索、图谱可视化；对话侧有 `ChatMemoryWindowPlanner`（窗口规划）与 `MemoryAutoSaveScheduler`（自动保存）。

**好在哪**：**可视化**让"它记得什么"变成可看的东西；多空间让"工作/生活"分开。

**软肋**：图谱的每条边是**谁建的、依据哪句话**，在 UI 上不可追。用户看到一张漂亮的图，但回答不了「你凭什么记得这个」。

**我们的做法**：**不追图谱编辑，追溯源。**
- 已有 L0/L1 分层（L2/L3 待做）+ `OffloadRef` 内容哈希 + `AtomExtractor` 的来源轮次 → **每条记忆都能点回原话**（这是我们已经有的，只是没露出来）。
- W2 记忆面板做三件事：**列表（按时间）→ 点开看原话 → 一键遗忘（级联）**。图谱只做"关系浏览"的只读视图，**不做编辑**。
- **"为什么记得"**：`Atom` 上加 `sourceTurnId` 与 `extractedAt`，面板显示「我记得：你不吃香菜（来自 10-12 那轮外卖对话）」。

**落点**：`:memorylogic`（补 source 字段）、`:app`（记忆面板）。

**验收（离线）**：每条 `Atom` 必带 `sourceTurnId` 且指向存在的 Turn（**无源记忆不允许落库**）；遗忘一条记忆后，依赖它的 L3 画像字段**同步失效**（不是留着旧值）。

---

### 2.7 语音：VAD + 唤醒 + 多 TTS + 队列

**它怎么做**：`OnnxSileroVad`（本地 VAD）、`PersonalWakeEnrollment`/`PersonalWakeFeatureExtractor`（个人唤醒词）、`SherpaSpeechProvider`（离线 ASR）、8 家 TTS（含 OpenAI Realtime / VITS / 豆包 / MiniMax）、`QueuedTtsPlayback`。

**好在哪**：**整条链路都在本地有退路**。唤醒词用个人特征注册 —— 比固定唤醒词更少误触发。

**软肋**：麦克风独占冲突在文档里承认了，但引导做在设置深处。

**我们的做法**：**先闭环，不做形象、不做唤醒词**。
- 用 `:voicelogic` 已有的状态机 + 800 ms 延迟预算（`AI手机助理架构` §4.5.2）；
- VAD 用 `onnxruntime-android` + Silero 模型（**不自研**）；
- TTS 先接系统 TTS + OpenAI 兼容（`:provider:tts-openai` 已有）+ `TtsFacade` 降级链；
- **唤醒词后置**（D-OP 拍板项）：它要求常驻麦克风，与"少打扰"冲突，且不是闭环必需。

**落点**：`:voicelogic`（已有空模块）、`:voice`、`:tts`（都已有空模块 —— **不要再建新模块**）。

**验收（离线）**：状态机穷举（含 `INTERRUPTED` 清空队列 + 取消在途）；延迟预算给定分段延迟断言 ≤ 800 ms；TTS 降级链每一级都返回"实际用了哪级"。

---

### 2.8 悬浮层五模式

**它怎么做**：`FloatingChatWindow`（聊天窗）、`SiriBall`（球 + 粒子）、`FloatingFullscreenMode`（全屏语音 + 波形）、`FloatingScreenOcrMode`（圈选识屏）、`pet`（桌宠 + 情绪）。

**好在哪**：**同一个入口，五种存在感**。用户不用决定"打开哪个功能"，只决定"我现在要它多在场"。

**软肋**：五个模式 = 五套窗口生命周期，Android 后台限制下每套都要单独保活；认知负担也高（用户得知道球双击是什么）。

**我们的做法**：**一球三态，不做第五种**。

| 态 | 触发 | 形态 | 我们的约束 |
|---|---|---|---|
| 待机 | 默认 | 屏幕边缘细线 + 球 | 球心停在边缘内侧（`computeEdgePosition` 已有，别改） |
| 聆听 | 长按球 | 波形 + 一句话提示 | 松手即说，300 ms VAD 判停 |
| 执行 | 任务中 | 顶部 2px 进度条 + 「我来」 | **不遮挡**：只占状态栏下方 2 px |

→ 点击球 = 半屏对话；长按 = 说话；执行中点击「我来」= 接管（§3.4）。**双击不做**（想不出一个必须双击的动作，那就不要有）。

**落点**：`:overlay`（现在只有 Service）、`:overlaylogic`（状态机已有）、`:app`（Compose 内容）。

**验收（真机，归 WorkBuddy）**：三态切换无闪烁；执行条不遮挡系统状态栏；锁屏/切后台后球仍在（FGS 已验）。

---

### 2.9 互操作：外部 HTTP / A2A / Tasker / Web Chat

**它怎么做**：`ExternalChatHttpServer`（局域网 OpenAI 兼容入口，Bearer Token）、`A2aHttpHandler`（Agent2Agent 协议）、Tasker 触发、Web Chat（React 前端打进 assets）。

**好在哪**：**手机是服务器**。桌面浏览器能用自己的手机助理，这是"设备即算力"的想象力。

**软肋**：每一条都是**攻击面**。他们自己在 README 里写「默认关闭，启用前请评估局域网暴露风险」—— 这个提醒本身就是产品缺陷的承认。

**我们的做法**：**只做一条，且是"我们当客户端"的方向**。
- W5 做 **MCP 客户端**：把别的 MCP server（桌面 dsh、公司内部工具）接进来，让助理多一双手。**我们不当 server**（dsh 那条 `:mcp` 已经是 server，够了）。
- 不做 Web Chat、不做 A2A、不做 Tasker。理由：三条都要维护协议兼容，收益是"给极客用"，与"助理"定位不符。

**落点**：`:mcp`（新增 client 侧，与现有 server 侧共用 `JsonRpc`）。

**验收（离线）**：client 的 `initialize`/`tools/list`/`tools/call` 三方法用本地 mock server 跑通；外部工具与内置工具**同名冲突**必须加载期报错（`ToolRegistry` 已有这条纪律）。

---

### 2.10 不逆向的三样（附理由，防止"顺手抄"）

| 他们的东西 | 为什么不做 |
|---|---|
| **shower 虚拟屏**（独立 server + Binder + `SurfaceControl`） | 我们 `flags=85003` 已存档，且已证明「多任务交叉时无感崩塌」。**没有证据显示他们解决了"无感"**（这是我的推断，不是实测）—— 再投入是往一个已知的坑里填第二遍土 |
| **本地模型**（MNN / llama.cpp / GGUF） | 战略上重要（数据不出设备），但工程量大且与当前闭环无关。**进路线图，不进 W1–W6** |
| **3D 形象 / 桌宠** | 是"平台"的表现力，不是"助理"的能力。我们的差异化是"可信"，不是"可爱" |

---

## §3 交互设计：把后端严谨变成可感知体验 ★

### 3.1 三个真问题（所有 Agent UI 都逃不掉）

| 问题 | 用户的真实感受 | Operit 的解法 | 我们的解法 |
|---|---|---|---|
| **看不见** | "它到底在干什么？" | 流式文字 + think 折叠 + 工具卡 | **能力卡**（结构化、可折叠、带证据） |
| **停不下** | "别点了！" | 悬浮球 + 停止 | **接管条**（2px，随时"我来"） |
| **说不清** | "为什么点这里？" | think 文本 | **为什么面板**（四元证据链） |

### 3.2 能力卡（Capability Card）

**定义**：一次工具调用 / 一次观察 / 一次回答，在 UI 上是一张**有类型**的卡，不是一个字符串。

```kotlin
// :agentui（纯 Kotlin，零 Android，进离线验证器）
sealed interface Card {
    val id: CardId
    val at: Long
    val state: CardState          // Draft → Running → Done/Blocked/Failed
    val evidence: List<Evidence>  // 为什么这么做（可为空）

    data class Intent(…)      : Card   // 我打算做什么（预演，未执行）
    data class Act(…)         : Card   // 我做了什么（工具调用）
    data class Obs(…)         : Card   // 我看到了什么（读屏/截图摘要）
    data class Say(…)         : Card   // 我说了什么
    data class Trouble(…)     : Card   // 出问题了（必有 ≥2 个出口）
}

/** 状态机是纯函数：给定事件序列，结果唯一。 */
fun reduce(state: Timeline, event: CardEvent): Timeline
```

**五类卡，只此五类。** 不做 DSL、不做卡片市场、不允许插件定义新卡型（v1）。理由：卡型是**产品语言**，一旦开放就会变成"每个插件一套 UI"，用户要重新学一遍。

**卡片的三条硬约束**：
1. **卡片是投影，不进上下文** —— 模型只看到 `ToolOutcome.text`；卡片再丰富也不烧 token。
2. **同一事实只出现一次** —— 卡的标题与正文不重复；工具 `text` 已经说了的，卡片不再复述。
3. **卡片可折叠成一行** —— 长任务默认折叠 Obs，只留 Act + Say；用户点开才看细节。

### 3.3 三拍闭环：预演 → 执行 → 复盘

```
用户说「给妈发个消息，说今晚不回家吃饭」
        │
   ① 预演（Intent 卡）—— 只在「不可逆 / 高风险」时出现
        ┌──────────────────────────────────────
        │ 我准备：在「微信」给「妈妈」发一条消息
        │ 内容：今晚不回家吃饭
        │
        │ [发送]  [改一下]  [取消]
        │ ⚠️ 发出后无法撤回
        └──────────────────────────────────────
        │
   ② 执行（Act 卡）—— 每步一张，实时状态
        ┌──────────────────────────────────────
        │ ● 打开微信            已完成  0.8s
        │ ● 搜索「妈妈」         已完成  1.2s
        │ ● 点进对话            已完成  0.6s
        │ ● 输入内容            已完成  0.3s
        │ ◐ 点「发送」           进行中…
        └──────────────────────────────────────
        │
   ③ 复盘（Obs + Say 卡）—— 结果 + 能不能反悔
        ┌──────────────────────────────────────
        │ ✓ 已发送。界面显示「已发送」。
        │ 不可撤销 —— 已发出的消息无法收回。
        │ 要我提醒你明天补一句吗？
        └──────────────────────────────────────
```

**为什么"预演"不是每步都做**：低风险动作（打开 App、滑动、读屏）预演=拖慢。**预演只在高风险触发**：`EXTERNAL` / `MONEY` / `IDENTITY`，或**动作不可逆**（发送、删除、提交表单）。

### 3.4 接管条（Takeover）

- 执行中，屏幕顶部一条 **2 px** 进度条 + 右侧「我来」；
- 点「我来」→ **立即停手**：取消在途模型请求（不只是停止动画）、清空动作队列、把控制权交回；
- 状态机进 `Paused(USER_TAKEOVER)`，**checkpoint 落盘**（`TaskCheckpoint` 已有）；
- 回来时问一句：「刚才你自己弄完了，还是接着来？」—— **不擅自恢复**。

★ 这条是"少打扰"的兑现：**助理在场，但从不抢方向盘。**

### 3.5 为什么面板（Explain）

每张 Act 卡的右上角一个 `?`，展开四行：

```
我看到：微信 · 聊天页 · 输入框「说点什么」· 按钮「发送」
我依据：你的话「给妈发个消息」+ 规则 #12「发送前确认收件人」
我理解：收件人是「妈妈」（第一顺位），不是「妈妈家」群
我要做：点「发送」
```

四元组对应 `Evidence` 的四个来源：`NodeRef` / `RuleHit` / `UserInstruction` / `ModelClaim`。
**没有证据就不许编** —— 面板宁可显示「这一步没有可解释的依据（模型直接决定）」，也不许用模型自己的话复述一遍当成解释。

### 3.6 能力发现：`@` 与命令面板

输入框打 `@` 出面板，四组：

| 组 | 内容 | 排序（确定性） |
|---|---|---|
| 能力 | 10 个 MCP 工具 + 外部 MCP | 精确名 > 前缀 > 拼音首字母 > 最近使用 > 名字字典序 |
| 记忆 | 最近 5 条 L1 原子 | 时间倒序，**不按相似度**（相似度排序会让"我记得"变成"我猜"） |
| 文件 | 当前工作区 / SAF 授权目录 | 最近修改 |
| 应用 | 已安装（有 launcher intent） | 最近使用 |

★ **排序必须可离线断言** —— 同一输入在同一状态下结果唯一。这是防止"越用越乱"的唯一办法。

### 3.7 权限时机：就地问三句，不做静态仪表盘

**现状**：`SafetyGuard` 是全局硬否决（6 步），但**没有"用户对单个能力的授权"**（`同类项目对比` §4.1 已指出这是倒挂）。

**设计**：

```
┌──────────────────────────────────────────
│ 「点击屏幕」想操作「微信」
│
│  [只这一次]   [以后都行]   [不要]
│
│ 为什么问：这一步会真的点下去
└──────────────────────────────────────────
```

- **只在 `WRITE` 及以上问**；`READ`（读屏/读文件）不问，但**在任务结束时汇总一句**「这次读了 3 次屏幕」。
- `MONEY` / `IDENTITY` **没有「以后都行」**（只给"这一次"和"不要"）。
- 记住的授权按 `(能力, 目标应用)` 存，**换应用重新问**。
- 设置里保留一个**只读仪表盘**（谁被允许了什么），但**不作为主入口** —— 静态清单永远追不上新增能力，只会变成"用户从没打开过的页面"。

**三道门的文案必须区分**（`ToolOutcome` 已有 `Refused` / `Failed` 两分，再补一道 `Denied`）：

| 门 | 用户看到 | 模型该做 |
|---|---|---|
| `SafetyGuard` 拒绝 | 「这一步我不能做：当前在支付页面」 | 停止该方向，**不重试** |
| 用户拒绝（`Denied`） | 「你刚才没允许这一步。要改吗？」 | 换方法或请用户手动 |
| 执行失败（`Failed`） | 「点了，但界面没反应」+ 下一步 | 可重试 |

★ 三者混成一句"失败了"，用户就永远不知道该找谁。

### 3.8 失败卡：永远给 ≥2 个出口

```
┌──────────────────────────────────────────
│ ⚠ 没成功：点了「发送」，但界面没有出现「已发送」
│ 可能原因：网络断了 / 被弹窗挡住 / 按钮位置变了
│
│ [重试]   [换个方法]   [我来]
└──────────────────────────────────────────
```

- 「换个方法」不是空话：打开**候选动作**列表（改用 `node_id` / 改坐标 / 等 2 秒再点 / 走 `am start`）。
- 「我来」= 接管并给出**操作提示**（"你需要手动点一下发送"），不是甩手。
- **禁止**只给 `isError` 原文。文案模板在 `:agentui` 里，**可离线断言**（每条失败都必须有 ≥2 个出口）。

### 3.9 视觉与动效（承接现有设计系统，不新造）

`:app/ui/design` 已有 `Tokens` / `Glass` / `Motion` / `Risk` / `Components`。卡片直接复用：
- 圆角/间距/字体走 `Tokens`；
- Act 卡的"进行中"用 `Motion` 的既有曲线，**不引入第二套动效语言**；
- `Risk` 档位决定卡片描边颜色（`MONEY` 用警示色，且**必须**在预演卡出现）。
- **深色模式下不透明度 ≥ 0.87**（玻璃层叠两层后文字对比度会掉，这是已踩过的坑）。

### 3.10 性能预算（写进验收，不写"尽量流畅"）

| 指标 | 预算 | 为什么 |
|---|---|---|
| 首字（用户说完 → 第一张卡出现） | **≤ 400 ms** | 超过 1 s 用户会以为没听见 |
| 卡片追加 | **≤ 16 ms**（一帧） | 掉帧会打断"它在干活"的感知 |
| 1000 卡滚动 | **60 fps** | Operit 自带一份 vendored `LazyList` —— 这是他们踩过列表性能的**证据** |
| 内存 | **≤ 120 MB**（10 万 token 会话） | 中端机 4 GB 的可用余量 |
| 卡片折叠状态持久化 | 冷启动恢复 | 回来时不该丢掉"我刚才看到哪" |

### 3.11 这一层有多少能离线验

**能**：卡片状态机、事件序列、撤销策略、失败出口、权限策略、@ 排序、文案模板、时间线导出格式 —— **全部纯逻辑**。
**不能**：滚动帧率、触摸热区、颜色对比度、动效手感 —— 归真机。

⇒ 目标：**交互层新增 ≥ 120 条离线断言**，让"交互回归"第一次变成可自动化的东西。这是 Operit 单体架构拿不出的东西。

---

## §4 功能矩阵（对标与取舍）

| 功能 | Operit | 我们现状 | 目标 | 处置 |
|---|---|---|---|---|
| 对话 / 流式 | ✅ 流式 + XML 渲染 | ⚠️ 整轮返回 | 流式 + 卡片 | **W1 追平并超越** |
| 会话管理 | ✅ 分支/迁移/摘要/导出 | ⚠️ 列表/搜索/重命名/删除 | 四件套 | **W2 追平** |
| 记忆 | ✅ 图谱 + 多空间 | ⚠️ L0/L1 + 卸载 | 溯源 + 遗忘 | **W2 超越（溯源）** |
| 人格 | ✅ 角色卡 + 群聊 + 3D | ✅ 白名单 + 账本 + 反漂移（未露 UI） | 露出「它为我变了」 | **W2 超越** |
| 工具调用 | ✅ 20+ 工具 | ✅ 10 个 MCP 工具 | 卡片化 + 风险分级 | **W1 超越（可控性）** |
| 每工具权限 | ✅ 三态 | ❌ | 就地问 + 记忆 | **W4 追平并简化** |
| 按用途分配模型 | ✅ | ✅ 已完成（2026-10-14） | 补"为什么用它" | **W6 小补** |
| 扩展机制 | ✅ ToolPkg/Skill/MCP/工作流 | ⚠️ 只有契约 | 规则包 + 预演安装 | **W5 差异化** |
| 工作流自动化 | ✅ 可视化 + 定时 + Tasker | ❌ | 不做 | **不做** |
| 语音 | ✅ 唤醒 + 全链路 | ❌（模块空） | 通话闭环 | **W3 追平最小集** |
| 悬浮层 | ✅ 五模式 | ❌（只有 Service） | 一球三态 | **W3** |
| 浏览器 Agent | ✅ 多标签 + 脚本 | ❌ | 不做 | **不做** |
| 终端 / 工作区 | ✅ Ubuntu + 编辑器 | ❌ | 不做 | **不做** |
| 本地模型 | ✅ MNN/llama.cpp | ❌ | 路线图后置 | **后置** |
| 3D 形象 | ✅ DragonBones/MMD/FBX | ❌ | 不做 | **不做** |
| 统一市场 | ✅ | ⚠️ 静态订阅源 | 先 10 个官方规则包 | **后置** |
| 任务可回放 | ❌ | ⚠️ checkpoint 有、没 UI | 时间线导出/回放 | **W6 超越（独有）** |

---

## §5 避免重复造轮子

### 5.1 仓库里已经有的（先查再写）

| 能力 | 已有资产 | 别做 |
|---|---|---|
| 预算/断点/降级/隐私 | `:agentlogic`：`AgentBudget` `TaskCheckpoint` `PerceptionLadder` `PrivacyFilter` | 不要新写"任务管理器" |
| 能力目录/守卫/审计 | `:capabilitylogic`：`CapabilityCatalog` `CapabilityGuard` `CapabilityAuditLog` `CapabilityRunner` | 不要新写"权限系统" |
| 人格 | `:personalogic` 18 文件（白名单/账本/漂移/装配） | 不要新写"角色系统" |
| 记忆 | `:memorylogic`（L0/L1/卸载/哈希/闸门） | 不要新写"记忆库" |
| 安全 | `:safety`：`DefaultSafetyGuard` 6 步 | 不要在工具里再判一遍 |
| 文件 | `:filelogic`：`FileAccessDecider` `Workspace` `OfficeTools` | 不要新写"文件工具" |
| 模型 | `:provider:gateway` + `:modelrouter`（按用途） | 不要新写"路由" |
| MCP | `:mcp`：`JsonRpc` `McpDispatcher` `ToolRegistry` 10 工具 | 不要新写"协议层" |
| 设计系统 | `:app/ui/design`：`Tokens` `Glass` `Motion` `Risk` `Components` | 不要新写"卡片样式" |
| **9 个空模块** | `:assistant` `:voice` `:voicelogic` `:tts` `:memory` `:onboarding` `:channel` `:contribute` `:update` | **不要再建同名新模块**；先填这些 |

### 5.2 开源库选型（不自研）

| 用途 | 选型 | 为什么不自研 |
|---|---|---|
| Markdown 渲染 | `org.commonmark:commonmark` + 自绘 Compose（或 `multiplatform-markdown-renderer`） | Markdown 规范有 600+ 页，自研必漏 |
| Diff（变更预览） | `io.github.java-diff-utils:java-diff-utils` | Myers 算法自研易错且无收益 |
| 二维码（角色卡/导出） | `com.google.zxing:core` | 成熟 |
| 音频播放 | `androidx.media3` | 流式/焦点/耳机事件全都要 |
| VAD | `onnxruntime-android` + Silero VAD ONNX | 训练与调参不是我们的活（Operit 也这么做） |
| 离线 ASR | `sherpa-onnx`（k2-fsa，AAR 需手动集成，不走 Maven 依赖） | 同上 |
| JS 沙箱（L2 插件） | `app.cash.quickjs:quickjs-android`（Maven Central） | 自写 JNI + 绑定 = 数月 |
| MCP 客户端（W5） | `io.modelcontextprotocol:kotlin-sdk`（官方 Kotlin SDK） | 协议兼容性靠官方跟 |
| 图片加载 | Coil | 成熟 |
| 调度 | WorkManager（AndroidX） | 系统限制只有它能处理 |

⚠️ **每个依赖都要过 `check_no_secrets` / 体积检查**；纯逻辑模块**不许**引入 Android 依赖（会掉出离线验证器）。

### 5.3 可以真正复用的 Operit 代码（三类，小而独立）

| 类 | 例子 | 条件 |
|---|---|---|
| 纯算法 | `ExpressionParser` / `Calculator` / `SlidingWindowRateLimiter` | ≤ 1 文件、无 Android 依赖、补测试 |
| 协议常量 | 无（他们的协议是私有的，抄了会绑死） | — |
| 资源 | 无（许可证与语言包不同） | — |

**纪律**：文件头写 `// 来源：AAswordman/Operit (LGPL-3.0)，已改为 GPL-3.0，改动：…`；`LICENSE` 附录列清单。
**结论：一个都不急。** 我们缺的不是算法，是闭环。

### 5.4 明确不建的轮子

| 不建 | 理由（一句话） |
|---|---|
| 终端 / Ubuntu 用户空间 | 用户要的是"替我办事"，不是"给我一台 Linux" |
| 代码编辑器 / 项目工作区 | 手机屏幕不是 IDE；Desktop 已经赢了 |
| 多标签浏览器 | WebView 的坑我们填不过浏览器团队 |
| 3D 形象运行时 | 表现力赛道，与"可信"无关 |
| 市场后端 | 一旦有服务端，"不设后端"这条原则就没了 |
| Root 通道 | 把目标用户砍到极小，且与"少打扰"冲突 |

---

## §6 模块落点与工程约束

### 6.1 新增 `:agentui`（纯 Kotlin）

```
android/agentui/src/main/kotlin/com/pocketagent/agentui/
  Card.kt            五类卡 + CardState + Evidence
  CardEvent.kt       事件（ToolStarted/ToolSucceeded/Blocked/Failed/Delta…）
  Timeline.kt        reduce(state, event) —— 纯函数
  UndoPolicy.kt      哪些动作可撤销 / 不可撤销 / 已过期
  FailurePlan.kt     失败 → ≥2 个出口 + 文案模板
  PermissionPolicy.kt 风险 → 是否询问 / 可选项
  MentionIndex.kt    @ 的分组与确定性排序
  TimelineExport.kt  时间线 → Markdown/JSON（可回放）
```

**登记三处**（纯 Kotlin 不走 `ANDROID_LIB`）：`settings.gradle.kts` / 生成器的 `PURE_KOTLIN` / `PROJECT_DEPS`；若用 `kotlinx-serialization` 再登 `NEEDS_SERIALIZATION`。
★ 加完确认 `--check` 的**总数在涨**（`filelogic` 漏登记的教训）。

### 6.2 改动点（文件级）

| 模块 | 改动 | 性质 |
|---|---|---|
| `:mcp` | `McpTool` 增 `risk`（无默认值）；`ToolOutcome` 增 `Denied` | 契约 |
| `:mcp` | `CardProjection`：`ToolOutcome` → `Card` 事件（复用 `structuredContent`） | 新 |
| `:capabilitylogic` | `PermissionStore` 端口 + `CapabilityRisk` + `CapabilityHook` + `HookBudget` | 新 |
| `:agentlogic` | `TaskStateMachine` 增 `Paused(USER_TAKEOVER)` | 小改 |
| `:agentlogic` | `UndoableAction` 分类（输入/草稿/启动 = 可撤销；点击/发送 = 不可） | 新 |
| `:app` | `ChatPort.sendStreaming()`；`TasksScreen` 接卡片；`@` 面板；权限弹层 | UI |
| `:app` | `ui/agent/`：`CardList` `IntentCard` `ActCard` `TroubleCard` `WhyPanel` `TakeoverBar` | UI |
| `:personalogic` | **不动**（W2 只接 UI） | — |
| `:safety` | **不动**（三道门里的第一道，一行不改） | — |

### 6.3 不许破坏的四条不变量

1. `SafetyGuard` 永远是**第一道**，`PermissionStore` 在其后，`Hook` 在其后；
2. 卡片是**投影**，模型上下文只认 `ToolOutcome.text`；
3. 纯逻辑模块**零 Android 依赖**（否则掉出离线验证器）；
4. 空模块**先填不新建**（9 个已登记的空壳）。

---

## §7 路线图 W1–W6

| 波次 | 内容 | 离线产物（可验） | 真机产物（归 WorkBuddy） | 人日 |
|---|---|---|---|---|
| **W1** | `:agentui` + 五类卡 + 三拍 + 失败卡 + 流式接入 | ≥120 条断言；`--check` 总数涨；`OK (≥2071 tests)` | 卡片渲染/滚动/首字延迟 | 8 |
| **W2** | 会话分支/导出 + 记忆面板（溯源/遗忘）+ 人格露出 | 分支树/导出往返/无源记忆拒落库 | 面板手感、长列表 | 7 |
| **W3** | `:voicelogic` 状态机 + `:voice`/`:tts` 最小闭环 + 一球三态 | 状态机穷举/800 ms 预算/降级链 | 打断、回声、锁屏、球不遮状态栏 | 10 |
| **W4** | `@` 面板 + 权限就地询问 + 三道门文案 + 只读仪表盘 | 排序确定性/策略矩阵/文案出口 ≥2 | 首次询问时机、授权记忆 | 6 |
| **W5** | 规则包 v1 + 安装前预演 + MCP 客户端 | 解析/校验/预演/同名冲突 | 装一个真规则包跑通 | 8 |
| **W6** | 时间线导出/回放 + 指标面板 + 六个基准任务 | 导出格式/回放一致性 | **六任务跑分** | 6 |

**顺序理由**：
- W1 先做，因为**卡片协议是所有后续 UI 的地基**（会话、记忆、权限、语音全都要落在卡片上）；
- W3 语音与 W5 扩展**互不依赖**，可并行；
- W6 的指标必须最后，因为要先有卡片与时间线才量得出来。

**总计 ≈ 45 人日**（不含真机等待）。对比：Operit 用 1 年 7 个月做到现在，我们**不追它的 2418 个文件**，只追 6 个波次。

---

## §8 指标：六个基准任务 + 五个数字

功能数量不是指标。**任务闭环率**才是。

### 8.1 六个基准任务（每次改交互都跑）

| # | 任务 | 判定 | 关键点 |
|---|---|---|---|
| 1 | 给指定联系人发一条消息 | 界面出现「已发送」 | 含确认弹窗；不可撤销要如实说 |
| 2 | 设一个明早 7:30 的闹钟 | 闹钟列表出现该条目 | 时间解析容错 |
| 3 | 查一个快递单号 | 读出最新一条物流 | 需要读屏 + 滚动 |
| 4 | 填一张三字段表单（姓名/电话/地址） | 三字段值正确 | 输入通道 + 校验 |
| 5 | 相册里选 10 张建一个相册 | 新相册里有 10 张 | 长按多选（最易错） |
| 6 | 外卖「再来一单」到付款前停下 | 停在支付页且**未付款** | 红线：不代付 |

### 8.2 五个数字

| 指标 | 目标 | 怎么量 |
|---|---|---|
| 一次成功率 | **≥ 80%** | 六任务 × 10 次 |
| 失败后可继续率 | **≥ 90%** | 失败卡至少一个出口能走通 |
| 平均步数 | **≤ 6** | 时间线里 Act 卡数量 |
| 误触率 | **≤ 1%** | 需要用户手动纠正的动作占比 |
| 首字延迟 | **≤ 400 ms** | 说完 → 第一张卡 |

★ 这张表**比功能清单更能说明"比他们好"** —— Operit 的 README 里没有这类数字，只有功能列表。

---

## §9 风险与红线

| 风险 | 后果 | 对策 |
|---|---|---|
| 卡片协议过度设计 | 三个月做一个 UI 框架，任务还是跑不通 | **只五类卡，v1 不开放卡型**；先跑通六任务再加 |
| **假的撤销** | 用户以为能撤回，实际不能 → 比没有更糟 | 只有「输入/草稿/启动」可撤销；其余**显式标"不可撤销"**（§3.3 文案） |
| 预演拖慢 | 每步多一次点击，用户关掉 | 只在 `EXTERNAL/MONEY/IDENTITY` + 不可逆动作出现 |
| 权限疲劳 | 每步都问 → 用户全点"以后都行" | 风险分级 + `(能力, 应用)` 记忆 + `READ` 不问 |
| 扩展生态冷启动 | 没人写包 → 市场空 | 先出 **10 个官方规则包**（微信/支付宝/设置/相册/日历/外卖/快递/地图/音乐/文件），不做市场 |
| 追功能导致核心不闭环 | 变成"什么都有、什么都不行" | §5.4 的"不建清单"写进 README |

**四条不越的红线**（沿用既有）：
1. `SafetyBones` 不可微调；2. 卸载前必过 `PrivacyFilter`；3. 一切模型调用过 `GatewayCore`；4. 记忆层不发网络请求。
**新增两条**：
5. **不代付**（`MONEY` 风险永远只给"停在这里"）；6. **不假装撤销**。

---

## §10 拍板项（需要你定）

| ID | 问题 | 我的建议 | 影响面 |
|---|---|---|---|
| **D-OP-1** | 主入口：对话为主 / 悬浮球为主 | **对话为主，球为快捷**（W1 先对话；W3 加球） | 整个 W1 的页面结构 |
| **D-OP-2** | 是否做 XML 兼容层（给不支持 tool call 的模型） | **不做**；失败卡直接给"换模型" | 省 3 人日，代价是弱模型不可用 |
| **D-OP-3** | 撤销范围 | **仅 输入/草稿/启动**；点击类一律标"不可撤销" | 决定 §3.3 的文案与 `UndoPolicy` |
| **D-OP-4** | 扩展包是否允许 UI DSL（像 ToolPkg 那样） | **v1 不允许**；只允许"声明式卡片模板" | 安全 vs 表现力 |
| **D-OP-5** | 语音优先级 | **W3**，不提前；唤醒词后置 | 若提前，W1/W2 要顺延 |
| **D-OP-6** | 是否做 MCP 客户端 | **做**（W5，成本低、收益高） | 可与桌面 dsh 互通 |

---

## 附录 A：逆向证据清单

| 结论 | 证据（仓库路径，main @ 2026-10-18） |
|---|---|
| 5 级权限 | `app/.../core/tools/system/AndroidPermissionLevel.kt` |
| 4 能力族 × 4 通道 | `core/tools/{accessbility,admin,debugger,root,standard}/` |
| 工具模型是字符串 | `data/model/AITool.kt`（`ToolParameter(name, value)`） |
| 生命周期 Hook | `core/tools/AIToolHook.kt`（7 回调 + `Block(reason)`） |
| Hook 预算 | `plugins/toolpkg/ToolPkgHookExecutionBudget.kt` |
| Provider 装饰链 | `api/chat/llmprovider/{RateLimitedAIService,TokenTrackingAIService,StructuredToolCallBridge,ThinkingQualityMapping,MediaCapabilityProbe}.kt` |
| XML 渲染协议 | `ui/features/chat/components/part/ThinkToolsXmlNodeGrouper.kt`（`think`/`tool`/`tool_result`） |
| ToolPkg 格式 | `docs/TOOLPKG_FORMAT_GUIDE.md`（manifest / 子包 / UI DSL / 资源 / i18n） |
| 悬浮五模式 | `ui/floating/{FloatingChatWindow,ui/ball/SiriBall,ui/fullscreen/FloatingFullscreenMode,ui/screenocr/FloatingScreenOcrMode,ui/pet}.kt` |
| 语音链路 | `api/speech/{OnnxSileroVad,PersonalWakeEnrollment,SherpaSpeechProvider}.kt`、`api/voice/QueuedTtsPlayback.kt` |
| 记忆图谱 | `ui/features/memory/screens/graph/GraphVisualizer.kt`、`core/tools/defaultTool/standard/MemoryQueryToolExecutor.kt` |
| 互操作 | `integrations/{a2a/A2aHttpHandler,http/ExternalChatHttpServer,tasker/AIAgentTasker}.kt` |
| 虚拟屏 | `tools/shower/`、`showerclient/` |
| quickjs 未接入 | `Repo_Arch_Basic.md`（"当前 README 明确说明该模块尚未接入 `app`"） |

## 附录 B：与既有文档的关系

| 文档 | 关系 |
|---|---|
| `同类项目对比与借鉴-v1.0.md` | **前置评估**。本文接它的 §4（四件必须学的）与 §7（下一步），把"学什么"变成"怎么做" |
| `会话侧边栏与全局记忆设计-v1.0.md` | **被本文引用，不重复**。W2 直接实现其 §3.5/§3.6/§4 |
| `AI手机助理架构-v1.0.md` | 本文 §3 是它的 L5 交互层的**第一次具体设计** |
| `MCP能力桥设计-v1.0.md` | 本文 §6.2 的 `risk` / `Denied` 是它的**向后兼容增量**（10 个工具签名不变） |
| `数字生命助理设计-v1.0.md` | 人格露出（W2）按它的四道防线做，不改算法 |
| `移动端Agent约束分析与迭代优化方案-v1.0.md` | 性能预算（§3.10）与它的能耗/轮次结论对齐 |
| `同类项目对比` §6 选项 A | **本文是该选项的执行版**：不追数量，追"可读可测可信"的产品化 |
