# PocketAgent → AI 手机助理：架构重设计 v1.0

> 状态：**设计稿，待评审**
> 日期：2026-09-26
> 前置文档：`docs/无感虚拟屏方案存档与交接-v1.0.md`
> 决策来源：用户 2026-09-26 口头拍板（见 §1.2）

---

## §0 一页纸结论

**一句话**：从「能替你点屏幕的 agent 工具」变成「认识你、会用你的手机、能打电话的助理」。

| 维度 | 结论 |
|---|---|
| 净新增模块 | **3 个纯 Kotlin**（`:personalogic` `:memorylogic` `:voicelogic`）+ **3 个 Android 库**（`:assistant` `:voice` `:tts`） |
| 删除模块 | **零**。现有 39 个模块全部保留，角色从「主角」降为「手脚」 |
| 复用空壳 | `:memory`（已有 build 文件 + 已在 `ROOM_MODULES`，零源码） |
| 唯一硬依赖 | `:provider:gateway`（已有）—— 助理的一切模型调用必须过它 |
| 最大技术风险 | 人格微调是**感知问题**，不是记忆问题（详见 §4.2） |
| 最大安全风险 | 微调可能击穿安全边界（详见 §4.2 骨骼/血肉分离） |

**三条不可违反的新不变量**（改动需评审）：

1. **助理层不直接碰 Provider** —— 一切模型调用过 `GatewayCore`（路由 → 熔断 → 解密 → 转发 → 计量）。
2. **记忆层不直接碰网络** —— 只读写本地；需要 LLM 蒸馏时通过**注入的端口**回调，不自己发请求。
3. **人格层不能碰安全边界** —— `SafetyBones` 之外的字段才可微调，且白名单**硬拒绝**而非忽略。

---

## §1 方向变更

### 1.1 定位对比

| | 旧定位（v3.0） | 新定位（v4.0） |
|---|---|---|
| 用户心智 | 「一个能自动点 App 的工具」 | 「一个认识我的助理」 |
| 主入口 | 悬浮球 → 发任务 | 对话 / 通话 |
| 有无状态 | 无状态（任务结束即结束） | **有状态**（人格 + 记忆跨会话累积） |
| 输出形态 | 屏幕上的一次操作 | 说的话 + 做的事 |
| 成功判据 | 任务办成了 | 任务办成了**且用户觉得「ta 懂我」** |

★ **这不是推翻，是加一层。** 旧定位里的全部能力（读屏、点击、插件、网关、虚拟屏、第 0 档能力）在新定位里依然要，只是**从「被用户直接调用」变成「被助理调用」**。这正是用户已拍板的「全保留，作助理的手脚」。

### 1.2 已拍板决策（2026-09-26）

| # | 决策 | 选项 |
|---|---|---|
| D-AS-1 | 腾讯开源项目 = **TencentDB Agent Memory**，移植形态 = **Kotlin 重写** | 是它 · Kotlin 移植 |
| D-AS-2 | 实时语音路线 = **混合**（默认级联，检测到 Realtime 支持时升级） | 混合：默认级联可升级 |
| D-AS-3 | 现有 39 模块 = **全保留**，降为助理的执行器 | 全保留，作助理的手脚 |

---

## §2 现状盘点与差距

### 2.1 现状（截至 2026-09-26）

- **39 模块**（23 个有代码）· **201 个源文件** · **离线单测 1339 / 0 failed**
- **能**：插件装/卸 · 订阅源 · 校验 API Key · 多模型档位 · dsh 配置草稿 · 权限真实状态 · **第 0 档文件能力（真机通过）**
- **不能**：读屏/点击代码未实现 · `:agent` 未接 `GatewayCore` · **无模块真正用 TTS 说话**
- 阶段：S1–S4 ✅ · **S5–S9 ❌ 未开始**

### 2.2 差距矩阵

| 新需求 | 现状 | 差距性质 |
|---|---|---|
| ① 男/女两种内置形象 | **无** | 全新 |
| ② 角色设定 + 对话中微调 | **无** | 全新，**且是算法核心** |
| ③ 保护用户 token | 有 `AgentBudget` / `BudgetGuard`（**被动熔断**） | 缺**主动降耗**（分层记忆 + 上下文卸载） |
| ④ TTS 服务封装 | 有 `:provider:tts-openai`（**单一厂商适配器**） | 缺**多厂商封装层** + 能力探测 + 降级链 |
| ⑤ 打电话模式 | **无** | 全新 |

### 2.3 ★ 一个必须说清的区分：③ 的两半

`AgentBudget` 与 Agent Memory **不是同一件事，也不互相替代**：

| | `AgentBudget`（已有） | Agent Memory（要移植） |
|---|---|---|
| 机制 | **被动熔断**：超预算就停 | **主动降耗**：少花 |
| 防的是 | 任务**跑飞**（无限循环、重复调用） | 任务**跑贵**（上下文线性膨胀） |
| 触发时机 | 事后（已花掉一部分） | 事前（根本不塞进上下文） |
| 用户感受 | 「任务莫名中止」 | 「没感觉，就是便宜了」 |

⇒ **两者都要，且都必须从第一版就位。** 横切组件「最后统一加」会漏 —— 这是 `AgentBudget` 已经付过学费的教训。

---

## §3 新架构总览

```
┌───────────────────────────────────────────────────────────────┐
│ L5 交互层  Interaction                                          │
│   通话界面（全屏）  ·  聊天界面  ·  悬浮球（保留，降为辅助入口）    │
├───────────────────────────────────────────────────────────────┤
│ L4 助理层  Assistant                          ★ 全新            │
│   PersonaEngine（人格/性别/角色设定）                            │
│   PersonaTuner（微调 → 产生「ta 为我做出了改变」）  ★算法核心     │
│   DialogueOrchestrator（对话编排 / 工具调用决策）                 │
│   VoiceSession（实时语音会话，可打断）                           │
├───────────────────────────────────────────────────────────────┤
│ L3 记忆层  Memory                    ★ 移植腾讯 Agent Memory    │
│   L0 原始对话  →  L1 原子记忆  →  L2 场景归纳  →  L3 用户画像     │
│   ContextOffloader（上下文卸载 → SAF）                           │
│   TaskCanvas（任务画布，仅画布进上下文）                          │
├───────────────────────────────────────────────────────────────┤
│ L2 推理层  Inference（已有，微增）                               │
│   :provider:gateway（路由→熔断→解密→转发→计量）  ★唯一出口        │
│   :modelrouter（本地启发式调度）                                 │
│   :provider:*（openai-compat / anthropic / gemini / local）      │
│   TtsFacade（★新增：封装用户导入的 TTS + 能力探测 + 降级链）       │
├───────────────────────────────────────────────────────────────┤
│ L1 能力层  Capability（已有，降为「手脚」）                       │
│   :perception  :action  :agent  :agentlogic                     │
│   :capabilitylogic  :capability  :filelogic                     │
│   :display（虚拟屏）  :overlay  :overlaylogic  :safety           │
│   :plugin:{api,runtime,rules,script,store,devtools}             │
├───────────────────────────────────────────────────────────────┤
│ L0 基础层  Foundation（已有）                                    │
│   :core:{common,crypto,database,network}  :keymgmt              │
│   :channel  :update  :onboarding  :github  :contribute          │
└───────────────────────────────────────────────────────────────┘
```

**数据流（一次「打电话」的完整链路）**：

```
用户说话
  → VadGate（本地，判停 + 判打断）
  → AsrPort（流式 ASR）
  → DialogueOrchestrator
      ├─ 向 MemoryPort 取上下文（L1 摘要 + L2 场景 + L3 画像，非全量 L0）
      ├─ 向 PersonaEngine 取当前人格（含已生效的 PersonaDelta）
      ├─ 组装 prompt
      └─ 调 GatewayCore（路由→熔断→解密→转发→计量）
  → 流式 LLM 输出
      ├─ EmotionTagger 提取情绪标签
      └─ TtsFacade.synthesize(text, style)（能力探测 → 降级链）
  → 播放（可被 BargeInController 立即打断）
  → 本轮结束：Turn 落 L0，异步触发 L1 提取
```

---

## §4 五个需求的落地方案

### 4.1 需求①：男 / 女两种内置形象

**设计**：`PersonaPreset` —— 两个内置模板（男声助理 / 女声助理），每个含：

- 称谓习惯（怎么称呼用户）
- 语气轴默认值（见 §4.2）
- 话术种子（若干条示例，用于 few-shot 锚定风格）
- 音色**参数建议**（如「建议基频偏移 −2 半音、语速 0.95×」）
- 可选头像

**★★ 关键区分：「性别」是人格模板，不是音色。**

音色来自**用户导入的 TTS**（需求④）。两者可能不一致 —— 用户选了「女声形象」，导入的却是男声音色。

⇒ **UI 必须诚实提示：「当前音色与你选的形象不一致」**，而不是静默。

理由：这正是本项目头号 bug 形态「安静地少做一件事」—— 用户以为选了女声就得到女声，实际不是，且没有任何提示。

---

### 4.2 需求②：角色设定与微调 ★ 算法核心

用户原话：「在交流过程中对该角色设定进行微调，让用户产生『ta 为我做出了改变』的体验，**该微调对算法要求较高**」。

#### 4.2.1 核心洞察

> **微调不是记忆问题，是感知问题。用户看不见的调整，等于没调整。**

所以设计目标不是「把偏好存下来」（那是记忆层的事），而是「**让改变可见**」。一个把用户偏好完美存进数据库、但从不表现出来的助理，需求②**完全落空**。

#### 4.2.2 四个机制

**(1) 骨骼 / 血肉分离 —— 安全前提**

| | 内容 | 可否微调 |
|---|---|---|
| **骨骼** | 安全边界、诚实、拒绝支付、不代解锁、隐私红线 | **永不可微调** |
| **血肉** | 称呼、语气、长度、幽默度、主动度、正式度、emoji、方言 | 白名单内可微调 |

实现：`PersonaField` 枚举即白名单。`PersonaTuner.tune()` 对**不在白名单的字段硬拒绝并记审计**（不是静默忽略）。

★ 没有这条，用户一句「以后你替我付款」就击穿了架构原则 4（主动放弃支付环节）。而且因为它是「自然语言指令」，看起来完全合理 —— 这是最危险的一类攻击。

**(2) 三源触发 + 置信度门**

| 触发源 | 例子 | 初始置信度 | 生效方式 |
|---|---|---|---|
| **显式指令** | 「以后叫我老张」「别用感叹号」 | 1.0 | **立即生效** |
| **隐式反馈** | 用户打断 / 重说 / 负面情绪词 / 回复显著变短 | < 1.0 | 进**候选区** |
| **行为统计** | 总在 23:00 后要求简短 | < 1.0 | 时间条件化偏好 |

候选区需 **N 次一致证据**才升为 `ACTIVE`。目的：防止「被一句话改坏」。

**(3) 变更账本（Change Ledger）—— 让改变可见 ★★**

每次微调落一条 `PersonaDelta`（字段 / 旧值 / 新值 / 触发证据 / 置信度 / 状态 / 时间）。

并且：**助理被允许在对话中自然地引用它。**

> 「你上次说我讲废话，我现在尽量一句说完。」

★ 这是「ta 为我做出了改变」体验的**唯一可靠来源** —— 因为它是**可被说出口的**。数据库里的一行记录不构成体验，一句「我记得你不喜欢这样」才构成。

**没有变更账本 = 需求②必然落空。**

**(4) 反漂移锚定 —— 用户掌控感的来源**

人格会随微调累积漂移。定期计算当前 `PersonaSpec` 与**基线**的人格距离；超阈值则主动询问：

> 「我好像变得不像我了，要回到最初吗？」

★ 这条同时提供**双向**掌控感：能变，**也能变回去**。「ta 为我做出了改变」的另一面是「我说了算」。

#### 4.2.3 数据结构

```kotlin
/** 人格骨骼：永不可微调。任何试图修改的调用都必须被硬拒绝并记审计。 */
data class SafetyBones(
    val refusePayment: Boolean = true,      // 架构原则 4
    val refuseUnlock: Boolean = true,
    val honesty: Boolean = true,
    val privacyRedline: Boolean = true,
)

/** 语气轴：血肉。每根轴 0..100，可微调。 */
data class ToneAxes(
    val warmth: Int = 60,        // 温度
    val brevity: Int = 50,       // 简洁
    val humor: Int = 30,         // 幽默
    val proactivity: Int = 40,   // 主动程度
    val formality: Int = 30,     // 正式度
    val emoji: Int = 20,         // 表情
)

/** 人格白名单字段。★ 不在此枚举内的一律不可微调。 */
enum class PersonaField {
    ADDRESS_STYLE,   // 怎么称呼用户
    TONE_WARMTH, TONE_BREVITY, TONE_HUMOR,
    TONE_PROACTIVITY, TONE_FORMALITY, TONE_EMOJI,
    DIALECT,         // 方言/口癖
    CATCHPHRASE,     // 口头禅
    // 注意：此处**没有**任何与安全边界相关的字段
}

enum class DeltaSource { EXPLICIT, IMPLICIT, BEHAVIORAL }
enum class DeltaState { CANDIDATE, ACTIVE, ROLLED_BACK }

data class PersonaDelta(
    val id: String,
    val field: PersonaField,
    val oldValue: String,
    val newValue: String,
    val source: DeltaSource,
    val evidence: List<String>,   // 触发证据，可溯源（用户能问「你为什么变了」）
    val confidence: Double,
    val state: DeltaState,
    val createdAt: Long,
)

data class PersonaSpec(
    val presetId: String,         // 内置男/女模板
    val displayName: String,
    val addressStyle: String,
    val tone: ToneAxes,
    val bones: SafetyBones,
    val baseline: ToneAxes,       // ★ 反漂移锚点
)
```

#### 4.2.4 验收（必须能离线断言）

- 试图 `tune(SAFETY_BONES, ...)` → **抛异常 + 落审计**（不是静默返回）
- 单条隐式反馈 → 停留在 `CANDIDATE`，**不生效**
- 连续 N 条一致隐式反馈 → 升 `ACTIVE`，且**产生一条可引用的 `PersonaDelta`**
- 回滚一条 `ACTIVE` delta → 人格精确回到前值
- 人格距离超阈值 → 触发锚定询问

---

### 4.3 需求③：保护用户 token = 移植 TencentDB Agent Memory

**来源**：`github.com/TencentCloud/TencentDB-Agent-Memory`（官方文写作 `Tencent/TencentDB-Agent-Memory`），**MIT 协议**，npm 包 `@tencentdb-agent-memory/memory-tencentdb`。

**原始实测数据**（腾讯官方文，2026-05-14）：

| 记忆类型 | Benchmark | 原成功率 | 加插件后 | 相对提升 | Token 消耗 | 加插件后 | 相对变化 |
|---|---|---|---|---|---|---|---|
| 短期 | WideSearch | 33% | 50% | +51.52% | 221.31M | 85.64M | **−61.38%** |
| 短期 | SWE-bench | 58.40% | 64.20% | +9.93% | 3474.1M | 2375.4M | −33.09% |
| 短期 | AA-LCR | 44.00% | 47.50% | +7.95% | 112.0M | 77.3M | −30.98% |
| **长期** | **PersonaMem** | **48%** | **76%** | **+59%** | — | — | — |

★ 注意最后一行：**长期记忆 / 用户画像是「人格微调」的直接证据源**。这就是为什么「保护 token」和「角色微调」指向同一个项目。

#### 4.3.1 移植映射表

| 腾讯原设计 | 本项目 Kotlin 落点 | 说明 |
|---|---|---|
| **L0 原始对话层**（全量保留每一轮） | `:memorylogic` `TurnLog` | 落 Room（`:memory` 已在 `ROOM_MODULES`） |
| **L1 原子记忆层**（自动提取事实/偏好/约束/阶段结论） | `:memorylogic` `AtomExtractor` | 四类原子 |
| **L2 场景归纳层**（按任务自动聚合） | `:memorylogic` `SceneAggregator` | 按 task 聚合 |
| **L3 用户画像层**（持续蒸馏稳定画像） | `:memorylogic` `ProfileDistiller` | ★ **输出直接喂给 `:personalogic`** |
| **上下文卸载**（原始结果搬到外部文件） | `:memorylogic` `ContextOffloader` | ★ 卸载目标 = **SAF 目录** |
| **Mermaid 任务画布**（任务结构折叠） | `:memorylogic` `TaskCanvas` | 仅画布进上下文 |
| **异构存储 + 全链路可溯源** | `:memory` Room + SAF 文件 | 每条记忆可回溯到源 Turn |
| 层间管道（提取→聚合→蒸馏） | 三个 `*Port` 接口 | 每层可独立替换 |

#### 4.3.2 ★ 三条移植要点

**① L3 是「保护 token」与「人格微调」的交汇点。**

```
L0 原始对话 → L1 原子记忆（含「偏好」类）→ L2 场景归纳 → L3 用户画像
                                                              ↓
                                                   PersonaTuner 的证据源
```

⇒ **不要把它拆成两个模块做。** 否则会得到两套互相不知道的用户模型：一套用来省 token，一套用来调人格，且它们对「用户是谁」的判断会逐渐分歧。

**② 上下文卸载的落点是 SAF，不是 App 私有目录。**

理由：用户**可见、可删、可审计**；且项目已有**真机验证过的 SAF 全链路**（第 0 档文件能力）。

代价：SAF 写文件比私有目录慢 → 必须异步 + 批量 + 可重试。

**③ 卸载前必须过 `PrivacyFilter`（`6822983`）。**

原始工具结果里可能是用户真实隐私 —— 虚拟屏实验中就**实际撞见过用户真实微信聊天列表**。隐私过滤必须在卸载**之前**，不是之后（卸载后再过滤 = 隐私已经落到磁盘上了）。

#### 4.3.3 与既有 `AgentBudget` 的关系

见 §2.3。**互补，不替代，两者都要。**

---

### 4.4 需求④：TTS 服务封装

**现状**：`:provider:tts-openai` 是**一个厂商适配器**，不是封装层。

**设计**：`TtsFacade` 三层职责

1. **能力探测**：问适配器「你支持哪些参数」——emotion? speed? pitch? SSML? 流式?
2. **降级链**：`emotion 参数` → `SSML 韵律` → `纯文本 + 语速/音高偏移` → `纯文本`
3. **统一接口**：

```kotlin
interface TtsAdapter {
    /** 能力声明。★ 必须是显式声明，不能靠「试一下看会不会失败」。 */
    val capabilities: TtsCapabilities
    fun synthesize(text: String, style: VoiceStyle): Flow<AudioChunk>
}

data class TtsCapabilities(
    val emotion: Boolean = false,
    val ssml: Boolean = false,
    val speed: Boolean = false,
    val pitch: Boolean = false,
    val streaming: Boolean = false,
)

interface TtsFacade {
    /** ★ 返回实际使用了哪一级降级，供 UI 诚实展示 */
    fun synthesize(text: String, style: VoiceStyle): Flow<TtsChunk>
}
```

**★ 为什么必须有降级链**：用户导入的 TTS 千差万别，且**能力未知**。如果只按最全的接口写，遇到能力弱的适配器就会**静默丢参数** —— 正是「安静地少做一件事」的 bug 形态。降级必须**显式且可观测**。

---

### 4.5 需求⑤：打电话模式

用户要求：**响应速度快、具备情感表达、可打断**。

#### 4.5.1 状态机

```
IDLE ──开始通话──→ LISTENING ──VAD 判停──→ THINKING ──首 token──→ SPEAKING
  ↑                    ↑                                            │
  │                    └────── 用户开口（BargeIn）───────────────────┘
  │                                    ↓
  └──结束通话──── INTERRUPTED ──清空播放队列 + 取消在途请求──→ LISTENING
```

**★ 「可打断」必须从第一版长在状态机里。**

理由同 `AgentBudget`：横切关注点事后统一加必漏。具体后果 —— 如果第一版没有 `INTERRUPTED` 态，后面加会改**所有**调用点，且极易漏掉「取消在途 LLM 请求」这一半（表现：助理停了，但 token 还在烧）。

#### 4.5.2 延迟预算（「响应快」的量化抓手）

| 环节 | 预算 |
|---|---|
| VAD 判停 | ~300ms |
| ASR 流式首字 | ~200ms |
| LLM 首 token | ~200ms |
| TTS 首包 | ~100ms |
| **合计（目标）** | **≤ 800ms** |

⇒ **全链路必须流式。任一段非流式就会超预算。**

⇒ 这条要**写进 `:voicelogic` 的离线测试**：给定各段模拟延迟，断言总延迟 ≤ 800ms。超预算要能被测试抓到，而不是等到真机上「感觉有点慢」。

#### 4.5.3 组件

| 组件 | 职责 |
|---|---|
| `VadGate` | 本地 VAD：判「说完了没」+ 判「用户开口了」（→ 打断信号） |
| `AsrPort` | 流式 ASR，可插拔；Realtime 路线时绕过 |
| `VoiceSessionOrchestrator` | 状态机 + 延迟预算 |
| `BargeInController` | 打断：立即停播 + **取消在途请求** + 清空 TTS 队列 |
| `EmotionTagger` | 从 LLM 流式输出提取情绪标签 → 映射到 `VoiceStyle` |

#### 4.5.4 混合路线（D-AS-2）的落地

- **默认**：级联（ASR → LLM → TTS）
- **升级**：检测到 Provider 支持 Realtime API → 自动切换到端到端语音
- ★ **升级判据必须显式**（来自能力探测结果），**不能靠「试一下看会不会失败」** —— 后者会在真机上表现为「偶尔卡一下」，极难排查

---

## §5 新增模块清单与登记点

### 5.1 新增模块

**纯 Kotlin（零 Android 依赖，进离线验证器）**：

| 模块 | 职责 | 为什么必须离线可测 |
|---|---|---|
| `:personalogic` | 人格模型 / 微调算法 / 变更账本 / 反漂移 | ★ 判错后果**全是静默的**：微调不生效 = 用户以为变了其实没变；白名单漏一个字段 = 安全边界被击穿且无异常 |
| `:memorylogic` | L0–L3 分层记忆 / 上下文卸载 / 任务画布 | ★ 判错后果**全是静默的**：提取漏一类 = 该记的没记；卸载漏过隐私 = 隐私已落盘且无人知道 |
| `:voicelogic` | 语音会话状态机 / 打断判定 / 延迟预算 | ★ 判错后果**全是静默的**：延迟预算超了只是「感觉慢」；打断漏取消在途请求 = 白烧 token |

**Android 库**：

| 模块 | 职责 |
|---|---|
| `:assistant` | 助理编排：`DialogueOrchestrator`、`PersonaEngine` 的 Android 侧 |
| `:voice` | 音频采集 / 播放 / VAD 的 Android 实现 |
| `:tts` | `TtsFacade` + 适配器注册表 |

**复用现有**：

- `:memory` —— 已有 build 文件、已在 `ROOM_MODULES`、**零源码** → 承载 Room 落库
- `:provider:tts-openai` —— 作为 `TtsFacade` 的**第一个适配器**

### 5.2 四个登记点（本项目约定）

新增模块必须在**四处**同时登记，缺一不可：

| # | 位置 | 内容 |
|---|---|---|
| 1 | `android/settings.gradle.kts` | `include(":xxx")` |
| 2 | `android/gen_module_build_files.py` → `PURE_KOTLIN` | 纯 Kotlin 模块集合 |
| 3 | `android/gen_module_build_files.py` → `ANDROID_LIB` | Android 库 → 依赖别名列表 |
| 4 | `android/gen_module_build_files.py` → `PROJECT_DEPS` | 模块间依赖 |

视需要还要登记：`NEEDS_HILT` / `NEEDS_SERIALIZATION` / `API_DEPS` / `EXTRA_TEST_DEPS` / `ROOM_MODULES`。

**★ 补登陷阱（已付学费）**：往 `PURE_KOTLIN` 加模块时，必须同时确认 `--check` 的**总数在涨**。

`filelogic` 曾经因为漏登记，生成器**从不检查它**，而 `--check` 照样报「全部 34 个模块一致」。

> **一个漏掉模块的检查，同样是假的** —— 只是它不报红，所以更不容易被发现。

---

## §6 关键接口草案

```kotlin
// ── 记忆层（:memorylogic）────────────────────────────────────
interface MemoryPort {
    suspend fun appendTurn(turn: Turn): Unit                       // L0
    suspend fun extractAtoms(turns: List<Turn>): List<Atom>        // L1
    suspend fun aggregate(atoms: List<Atom>): List<Scene>          // L2
    suspend fun distill(scenes: List<Scene>): UserProfile          // L3
    /** 卸载原始内容，返回可恢复的引用（落 SAF） */
    suspend fun offload(payload: OffloadPayload): OffloadRef
    suspend fun recall(ref: OffloadRef): OffloadPayload
    /** 组装进 prompt 的上下文（只含摘要 + 索引，非全量） */
    suspend fun buildContext(query: String, budget: Int): MemoryContext
}

/** 记忆层需要 LLM 时**不自己发请求**，通过此端口回调（不变量 2）。 */
interface MemoryLlmPort {
    suspend fun complete(prompt: String, purpose: Purpose): String
}

// ── 人格层（:personalogic）──────────────────────────────────
interface PersonaTuner {
    /** ★ 白名单外字段硬拒绝（抛异常 + 审计），不静默忽略 */
    suspend fun tune(delta: PersonaDelta): TuneResult
    suspend fun rollback(deltaId: String): TuneResult
    /** 反漂移：与基线的距离超阈值则返回锚定建议 */
    fun driftCheck(spec: PersonaSpec): DriftReport
    /** 可供助理在对话中自然引用的变更摘要 */
    fun recentChanges(n: Int): List<PersonaDelta>
}

// ── 语音层（:voicelogic / :voice）────────────────────────────
interface VoiceSession {
    val state: StateFlow<VoiceState>
    suspend fun start(): Unit
    /** 用户开口 → 立即停播 + 取消在途请求 + 清空队列 */
    suspend fun bargeIn(): Unit
    suspend fun stop(): Unit
}
```

---

## §7 分阶段路线图

| 阶段 | 内容 | 可验收产物 |
|---|---|---|
| **P0** | 架构冻结 + 6 个模块骨架登记，全部编译通过 | `--check` 总数 +6；离线验证器通过 |
| **P1** | `:personalogic` 纯逻辑 + 离线测试（**无 UI、无 LLM**，用 fixture） | §4.2.4 五条验收全绿 |
| **P2** | `:memorylogic` L0/L1 + 卸载到 SAF（离线测） | 卸载→恢复往返一致；隐私过滤在卸载前 |
| **P3** | `:assistant` 接线：`DialogueOrchestrator` 过 `GatewayCore` + 聊天 UI | 真机跑通一次多轮对话 |
| **P4** | `:tts` + `:voice`（级联路线）+ 通话 UI | 真机跑通一次可打断通话 |
| **P5** | L2/L3 蒸馏 + `PersonaTuner` 联动 ★ 算法核心 | 「ta 为我做出了改变」可被用户说出 |
| **P6** | Realtime 升级通道 | 能力探测 → 显式升级，不靠试错 |

**★ 顺序理由**：

- `PersonaTuner`（P5）依赖 L3 画像 → 必须排在 `:memorylogic`（P2）之后
- 但 `:personalogic`（P1）可以先做，因为它的算法**不依赖真实数据**（fixture 足够）
- `:voicelogic` 的延迟预算与打断状态机必须在 P4 **第一版**就位（横切关注点）

---

## §8 风险、红线与未决

### 8.1 风险表

| 风险 | 影响 | 对策 |
|---|---|---|
| 人格微调**被用户感知不到** | 需求②完全落空 | 变更账本 + 允许助理引用（§4.2.2-3） |
| 微调漂移成「另一个 AI」 | 用户失去信任 | 反漂移锚定 + 可回滚 |
| 微调**击穿安全边界** | 架构原则 4 归零 | 白名单 + 硬拒绝 + 审计 |
| 卸载的隐私内容落到磁盘 | 隐私红线 | 卸载**前**过 `PrivacyFilter` |
| 级联延迟超 800ms | 需求⑤落空 | 全链路流式 + 离线延迟断言 |
| TTS 能力未知导致静默丢参数 | 「安静地少做一件事」 | 能力探测 + 降级链 + 不一致时提示 |
| 移植变成「抄接口不抄语义」 | 得到空壳 | 验收以**行为**为准，不以接口 |

### 8.2 红线（不可越）

1. **`SafetyBones` 永不可微调** —— 包括但不限于：代支付、代解锁、隐私红线、诚实
2. **卸载前必须过 `PrivacyFilter`** —— 顺序不可颠倒
3. **助理的一切模型调用必须过 `GatewayCore`** —— 不得绕过计量
4. **记忆层不得自行发网络请求** —— 必须走注入的 `MemoryLlmPort`

### 8.3 未决（需后续拍板）

| # | 问题 | 影响面 |
|---|---|---|
| U-1 | 助理的**主入口**形态：全屏通话 / 聊天页 / 悬浮球，哪个为主？ | L5 交互层设计 |
| U-2 | 记忆的**默认保留期**与用户可删除粒度 | 隐私 + 存储 |
| U-3 | L2/L3 蒸馏用哪个**模型档位**（成本 vs 质量） | 成本 |
| U-4 | 是否做**语音唤醒词**（涉及常驻麦克风权限） | 权限 + 功耗 |
| U-5 | 人格微调的**候选区阈值 N**（几次一致证据才升级） | 需求②手感 |

---

## 附录 A：TencentDB Agent Memory 原始设计摘要

**来源**：腾讯云开发者社区《TencentDB Agent Memory 正式开源：让 Agent 沉淀经验，让人专注创造》（2026-05-14）

**分层记忆（L0–L3）**：

| 层 | 内容 |
|---|---|
| L0 原始对话层 | 全量保留每一轮交互 |
| L1 原子记忆层 | 自动提取**事实、偏好、约束、阶段结论** |
| L2 场景归纳层 | 按任务自动聚合 |
| L3 用户画像层 | 持续蒸馏出稳定的长期画像 |

层间管道：**提取 → 聚合 → 蒸馏**。每层可独立升级或替换。

**解决的三类问题**（官方原话）：

- **跨会话断裂**：昨天反复确认的代码规范，今天新开会话又全忘了
- **事实与偏好混淆**：用户说过「我用 TypeScript」和「帮我查一下天气」，价值完全不同，却被同等对待
- **上下文膨胀**：任务越长，堆进上下文的历史信息越多，Token 消耗持续攀升，模型注意力也在衰减

**两项关键技术**：

- **上下文卸载**：把原始工具结果搬到外部文件，上下文里只保留摘要和索引
- **Mermaid 无限画布**：把任务结构折叠成可导航的画布

**其他**：异构存储 + 全链路可溯源；支持 OpenClaw / Hermes Gateway；Docker 镜像支持 `linux/amd64` 与 `linux/arm64`；MIT 协议。

**⚠️ 版本说明**：仓库默认分支为 `feat/server_team`，其 description 描述的是 **v2.0「团队级记忆中枢」**方向（Chat Memory / Skill / LLM-Wiki / Code-Graph 四类资产），与本文引用的 **v1.x「分层记忆引擎」（L0–L3）**不完全相同。移植以 **v1.x 的 L0–L3 + 上下文卸载**为准 —— 那才是对应「保护 token + 用户画像」的部分。

---

## 附录 B：与既有架构原则的对照

| 既有原则 | 本方案如何遵守 |
|---|---|
| 1. 不建自有后端 | 记忆层只读写本地；卸载落 SAF；蒸馏走注入端口 |
| 2. 执行层多通道抽象 | 不变。`:perception` / `:action` 等降为助理调用的执行器 |
| 3. 感知层不依赖无障碍树 | 不变 |
| 4. 主动放弃支付环节 | **由 `SafetyBones.refusePayment` 在人格层再钉一道** |
| 5. 手动引导是正式形态 | 不变；助理可在引导模式下用语音解释每一步 |
| 6. domain 层零 Android 依赖 | 新增 3 个 `*logic` 模块全部零 Android 依赖 |
| 7. Key 永不落明文 | 不变；`GatewayCore` 仍是唯一出口 |
| 8. 插件边界（本体给能力，插件给意图） | 不变；助理调用能力同样经 `PluginHost` 代理 |
| 9. 凭据运行时注入 | 不变 |
| 10. `ModelRouteCoordinator` 不缓存 | 不变；人格不影响路由决策 |
