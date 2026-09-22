# 剩余待办实施方案 与 Agent 循环草稿

> 版本 v1.0 ｜ 2026-09-22 ｜ 状态：**规划稿（待评审，不执行）**
>
> 性质：本文**只做方案规划，不含实现**。所有代码引用均为现有契约，未落地的部分标注为「待建」。
>
> 前置文档：
> - `docs/BYOK模型接入与dsh代理设计-v1.0.md`（LlmGateway 的架构依据，以下简称"BYOK 文档"）
> - `docs/移动端Agent约束分析与迭代优化方案-v1.0.md`（五个横切组件，以下简称"移动端文档"）
> - `docs/多Key与模型调度设计-v1.0.md`（ModelRouter 依据）
> - `docs/沙箱优先架构与离线优先方案-v1.0.md`（沙箱四层模型）
>
> 对应 commit 基线：`a20934e`（828 tests / 0 failed）

---

## 0. 结论速览

**当前处境一句话：配置闭环已经通了，执行闭环一环都没接。**

用户现在能做到：配 Key（模型 + 语音）→ 配模型与档位 → 选调度策略。
但从"配好了"到"真的发出去"，中间横着一道门 —— `LlmGateway`。
再往后，从"发得出去"到"能读屏点屏幕"，还横着 `PerceptionLadder` 与 agent 循环两应当。

**剩余 7 项待办的依赖关系（这是本文最重要的产出）：**

```
                    ┌───────────────────────────────────────┐
                    │  P1  LlmGateway        ★ 解锁一切的前置 │
                    │      配好 Key 也发不出请求，根因在此      │
                    └──────────────────┬────────────────────┘
                                       │ 提供「模型调用」这一原语
                    ┌──────────────────▼────────────────────┐
                    │  P2  Agent 循环（骨架 + 状态机）        │
                    │      ★ 本文第二部分是它的草稿           │
                    └──────────────────┬────────────────────┘
                                       │ 循环需要「看屏幕」与「点屏幕」
        ┌──────────────────────────────┼──────────────────────────────┐
        │                              │                              │
┌───────▼────────┐          ┌──────────▼──────────┐        ┌──────────▼─────────┐
│ P3 感知通道     │          │ P4 执行通道           │        │ P5 横切组件          │
│ PerceptionLadder│          │ ActionDispatcher      │        │ AgentBudget 等 5 个 │
│ （五档降级）     │          │ （四通道降级）         │        │                     │
└────────────────┘          └──────────────────────┘        └────────────────────┘
                                       │
                    ┌──────────────────▼────────────────────┐
                    │  P6  overlay 视觉层 + 急停跨进程投递    │
                    │      （需真机，最后做）                │
                    └───────────────────────────────────────┘

  P7 发行 build（debuggable=false）—— 与上面全部无依赖，随时可做
```

**三条关键判断：**

1. **P1 与 P2 必须串行**，且 P1 先做。理由：agent 循环的每一步都要调模型，
   在网关还是假的的情况下写循环，只能拿 mock 测 —— 而 mock 测不出"流式 token 乱序""Done 发了两次"这类真问题。
2. **P3/P4/P5 可以并行**，但它们**都依赖真机**（无障碍服务、截图权限、悬浮窗）。
   所以**在真机就位前，能推进的只有 P1 + P2 的纯逻辑部分 + P7**。
3. **P5 的五个横切组件不能"最后统一加"** —— 移动端文档已经论证过，
   `AgentBudget` 必须是**一等公民**（轮次能耗差三个数量级）。它要从循环的第一版就长在里面。

---

# 第一部分 · P1：LlmGateway 实施方案

## 1.1 定位：这不是"一个新模块"，是现有资产的一次调用方替换

BYOK 文档 §5.1 已经点明：`LlmProvider` 接口**一个字都不用改**。
现在 `OpenAiCompatProvider`（11 家 profile / SSE 解析 / 错误映射 / token 估算 / 费用钳制）
全都写好了、测过了，只是**没有调用方**。

网关要做的事，本质是：**给这些资产接一个 HTTP 服务端的入口**。

```
现在：  [无人调用]  →  OpenAiCompatProvider  →  Provider

之后：  dsh/agent 循环  →  LlmGateway(HTTP)  →  OpenAiCompatProvider  →  Provider
                          └ 鉴权 / 路由 / 熔断 / 解密 / 落库
```

## 1.2 一个必须先澄清的边界：网关有**两个**消费方，不是一个

BYOK 文档通篇假设网关的消费方是 **dsh（Node 进程）**。但实际有两个：

| 消费方 | 协议 | 是否需要 HTTP 服务端 | 何时需要 |
|---|---|---|---|
| **dsh（Node）** | OpenAI HTTP + SSE | ✅ 必须 | 路线 C 落地后 |
| **PocketAgent 自己的 agent 循环** | 直接函数调用 | ❌ 不需要 | **P1 阶段就需要** |

**这个区分很重要，因为它改变了模块的切法。** 如果一上来就把"网关"等同于"HTTP 服务端"，
会得到一个尴尬的结果：agent 循环要调模型，得先绕一圈 `127.0.0.1` 发 HTTP 给自己。

**建议的切法：把"网关"拆成「核心」与「服务端外壳」两层。**

```
┌─────────────────────────────────────────────────────────────┐
│  GatewayCore（纯逻辑，零 Android 依赖）★ 可以进离线验证器      │
│                                                             │
│   suspend fun complete(                                      │
│       req: ChatRequest,                                      │
│       ctx: GatewayContext,     ← 调用方身份、任务特征          │
│   ): Flow<ChatChunk>                                        │
│                                                             │
│   内部：路由 → 熔断 → 解密 → 转发 → 计量                       │
│   ★ 这是唯一处理真 Key 的地方                                  │
└───────────────┬──────────────────────────┬──────────────────┘
                │                          │
    ┌───────────▼──────────┐   ┌───────────▼──────────────┐
    │ AgentLoop 直接调用     │   │ HttpGatewayServer        │
    │ （P2 用，零 HTTP 开销）│   │ （dsh 用，loopback HTTP）│
    └──────────────────────┘   └──────────────────────────┘
```

**收益：**
- agent 循环不绕 HTTP，省掉一次 **同机 TCP + SSE 编码解码**（每轮都发生，是实打实的开销）
- `GatewayCore` 零 Android 依赖 → **能进 `run_logic_tests.py` 离线验证器**，
  而路由 / 熔断 / 计量这些逻辑恰好是最值得单测的
- `HttpGatewayServer` 变成薄薄一层"协议翻译"，出问题时排查面小
- 将来 dsh 与 agent 循环**共用同一套路由与熔断**

**代价**：多一个接口层。可接受 —— 它换到的是"核心逻辑可离线测试"，这与项目
「优先做零 Android 依赖模块」的既有原则一致。

> 📌 **这是本文对 BYOK 文档的一处修正**（该文档 §5.3 只规划了单一 `LlmGatewayServer`）。

## 1.3 模块划分与依赖

```
provider/gateway/
  ├── GatewayCore.kt            ★ 纯逻辑入口，唯一处理真 Key
  ├── GatewayContext.kt         调用方身份 + 任务特征
  ├── RoutingBridge.kt          连接 ModelRouter 与 CredentialRepository
  ├── BudgetGuard.kt            预算熔断（纯逻辑部分）
  ├── UsageRecorder.kt          用量累计（落库接口，实现在 keymgmt）
  └── http/
      ├── HttpGatewayServer.kt  loopback HTTP 服务端
      ├── ChatCompletionsHandler.kt
      ├── SseResponseWriter.kt  Flow<ChatChunk> → SSE
      └── GatewayTokenProvider.kt
```

**依赖约束（必须守住）：**

| 依赖 | 允许 | 理由 |
|---|---|---|
| `:provider:api` | ✅ | `LlmProvider` / `ChatRequest` / `ChatChunk` |
| `:modelrouter` | ✅ | 纯 Kotlin，路由决策 |
| `:core:crypto` | ✅ | `withDecryptedKey` |
| `:core:network` | ✅ | `LogSanitizer`（错误信息过滤） |
| 具体 Provider 实现 | ❌ **只通过注入 `List<LlmProvider>`** | 否则新增一家厂商要改网关 |
| `:core:database` | ❌ **在 `GatewayCore` 里不行** | 会把它踢出离线验证器 |
| Android SDK | ❌ **在 `GatewayCore` 里不行** | 同上 |

> ⚠️ **`GatewayCore` 必须零 Android 与零 Room 依赖。**
> 这不是洁癖 —— 项目已经吃过一次亏：`MigrationTest.kt` 因为所在的
> `core:database` 进不了离线验证器，**从未被执行过**，导致 Room 2.8 的 API 变更全部漏网。
> 落库通过**接口注入**（`UsageRecorder` 是接口，实现在 `keymgmt`）。

## 1.4 实施步骤（每步都以"可验证"为界）

| 步 | 内容 | 验证方式 | 前置 |
|---|---|---|---|
| **1.4.1** | `GatewayCore` 骨架 + `GatewayContext` | 离线单测：路由分派正确 | — |
| **1.4.2** | `RoutingBridge`：`ModelRouter` ← `ModelConfigRepository` 接线 | 离线单测（用 fake 仓储） | 1.4.1 |
| **1.4.3** | `BudgetGuard`：预算熔断（纯逻辑） | 离线单测：超限拒绝、边界值 | 1.4.1 |
| **1.4.4** | `GatewayCore.complete` 串起来（解密 → 转发 → 计量） | 离线单测（用 fake `LlmProvider`） | 1.4.1–3 |
| **1.4.5** | `UsageRecorder` 接口 + `keymgmt` 实现 | Gradle 单测（碰 Room） | 1.4.4 |
| **1.4.6** | `HttpGatewayServer` + SSE 写出 | **真机**（需要真实端口） | 1.4.4 |
| **1.4.7** | `GatewayTokenProvider` + 六条安全加固 | 真机 + 离线（token 比较逻辑） | 1.4.6 |
| **1.4.8** | 接通 agent 循环（回到 P2） | 端到端 | 1.4.4 |

**★ 1.4.1–1.4.5 全部可以离线验证** —— 这意味着**在真机就位前就能推进大半**。

## 1.5 已知坑（来自 BYOK 文档 §4.3，此处只列"动手时最容易忘的三条"）

| 坑 | 后果 |
|---|---|
| **`[DONE]` 恰好一次** | 现有 `OpenAiCompatProvider.chat` 的注释记录过一个真实 bug：Done 可能发两次或零次。走 SPI 时它表现为**客户端卡住等不到结束** |
| **客户端断开必须取消上游** | 否则 Provider 那边还在烧 token 计费，而用户已经放弃了 |
| **错误发生在流中** | 首字节发出后才发现上游 401，HTTP 状态码已经发出去了 → 只能在流里发 error 事件，**且不能带 Key** |

## 1.6 待决策新增

| ID | 问题 | 建议 |
|---|---|---|
| **D-AP** | `GatewayCore` 与 `HttpGatewayServer` 分层（本文 §1.2 修正） | **采纳分层**。理由：核心进离线验证器 + agent 循环不绕 HTTP |
| **D-AQ** | `ChatRequest` 的 `model` 字段由谁决定？ | **`GatewayCore` 忽略它，只看路由决策**。与 BYOK 文档 §4.5 策略 A 一致（`/v1/models` 只返回虚拟模型） |

---

# 第二部分 · P2：Agent 循环草稿 ★ 本文重点

> 这一部分是**草稿**，不是定稿。目标是把"循环该怎么转"想清楚，
> 并暴露出现有 `AgentOrchestrator` 契约里**需要修正的地方**。

## 2.1 现有契约的状况：设计得很好，但有四处与移动端约束冲突

现有 `AgentOrchestrator.kt`（单文件，含全部契约）定义了：
`AgentOrchestrator` / `Grounder` / `Verifier` / `TaskStateMachine`，
以及 `AgentTask` / `AgentEvent` / `PlanStep` / `GroundResult` / `VerifyResult` / `TaskState`。

**这套契约的核心洞察（保持）**：双脑分离 —— 规划脑（云端强模型）与定位脑（端侧小模型）
是两种不同能力，混在一起会得到"规划好但点不准"或"点得准但不会规划"。

**但有四处需要修正：**

| # | 现状 | 问题 | 建议 |
|---|---|---|---|
| **①** | 没有 `AgentBudget` | `AgentTask.maxSteps = 20` 是一个**裸参数**，没有时长上限、没有能耗上限、没有流量预算 | 引入 `AgentBudget`，由循环**强制**检查，而不是靠调用方自觉 |
| **②** | 没有 `PerceptionLadder` | `StepStarted` 事件里直接带 `snapshotSummary`，隐含"每步都感知" | 循环第 0 步必须允许**完全不感知**（直接 `am start`） |
| **③** | `PlanStep.actionType: String` | **字符串**而非枚举 | ⚠️ 拼错时**编译通过、运行时静默走错分支**。应改成 `ActionType` 枚举（已有！在 `:action` 里） |
| **④** | 没有"中断恢复"位置 | `AgentEvent` 无 checkpoint 事件；`TaskState.Idle` 不带 taskId | 移动端**中断频率极高**（移动端文档 §2.7），必须可恢复 |

**第 ③ 条最值得单独说**：`:action` 模块已经有 `enum class ActionType`（15 个值），
而 `PlanStep` 却用 `String`。这是**同一份概念的两套表示** —— 模型输出 `"CLICK "`（带空格）
或 `"Tap"` 时，字符串版本不会报错，会在派发时静默走进 `else` 分支。
**这正是项目反复踩过的"不报错、只是安静地少做一件事"类 bug。**

## 2.2 循环的骨架（伪代码，草稿）

```kotlin
suspend fun runTask(task: AgentTask, budget: AgentBudget): Flow<AgentEvent> = channelFlow {
    val state = TaskStateMachine.start(task.id)

    // ── 阶段 0：规划（只调一次强模型）─────────────────────────
    val plan = orchestrator.plan(task)            // 可能因预算被拒
    send(AgentEvent.Started(task.id, plan.steps))

    for (step in plan.steps) {
        // ── 预算检查（★ 一等公民，在每一步之前）──────────────
        when (val verdict = budget.check(state)) {
            is Exceeded -> { send(Failed(...)); return@channelFlow }
            else -> {}
        }
        // ── 护栏检查（不可绕过，且在感知之前）────────────────
        if (safety.blocked(step)) { send(UserInterventionRequired(...)); return@channelFlow }

        // ── 感知阶梯（★ 第 0 档合法：不感知）────────────────
        val snapshot = ladder.acquire(step)       // 可能是 null（第 0 档）
        send(StepStarted(...))

        // ── 定位（仅在需要元素时）───────────────────────────
        val ref = if (step.needsElement) grounder.locate(step.targetDescription, snapshot) else null

        // ── 执行 ───────────────────────────────────────────
        val result = dispatcher.dispatch(step.toAction(ref))
        send(StepExecuted(...))

        // ── 校验（规则优先，模型兜底）─────────────────────────
        val after = ladder.acquireForVerify(step)
        val verdict2 = verifier.verify(step.expectation, snapshot, after)
        send(StepVerified(...))

        // ── 失败处理 ────────────────────────────────────────
        if (verdict2 is Failed) {
            if (state.replanCount >= task.maxReplans) { send(Failed(...)); return@channelFlow }
            val newPlan = orchestrator.replan(task, state, verdict2)
            send(Replanning(state.replanCount, verdict2.reason))
            // ⚠️ 重规划后是否重置 stepIndex？见 §2.4 待决策 D-AR
        }

        // ── Checkpoint（每步后落盘，为中断恢复）──────────────
        checkpoint.save(state)                    // ★ 移动端必需
    }
    send(Completed(...))
}
```

## 2.3 四个阶段各自的关键设计

### 2.3.1 规划阶段：**只调一次**

```kotlin
interface Planner {
    suspend fun plan(task: AgentTask, budget: BudgetReamaining): Plan
    suspend fun replan(task: AgentTask, state: TaskState, failure: VerifyResult): Plan
}
```

**★ 为什么"只调一次"很重要**：现有契约的注释说"长程规划交给云端强模型"，
但没有说明频率。若做成"每步都重新规划"，则：
- 轮次翻倍 → **能耗翻倍**（移动端文档：能耗几乎完全由轮次决定）
- 每步多一次网络往返 → 弱网下体验崩坏
- 用户看到计划反复改变 → 不敢用

**建议**：规划一次产出**完整步骤列表**（`PlanStep` 的 `index` 字段已经暗示了这个设计），
只在**校验明确失败**时才 `replan`。

### 2.3.2 感知阶段：第 0 档是最大的优化空间

移动端文档 §§2.1 的五档阶梯：

| 档 | 手段 | 成本 | 何时用 |
|---|---|---|---|
| **0** | **不感知，直接 `am start`** | **零** | 启动 App、明确 deep link |
| 1 | 无障碍树 | 极低 | 大部分点击/输入 |
| 2 | 树 + 局部截图 | 低 | 树信息不足 |
| 3 | 全屏降质截图 | 中 | 自绘 UI（游戏、Flutter） |
| 4 | 全屏原质截图 | 高 | 兜底 |

**★ 现在的契约里没有档位概念** —— `StepStarted` 直接带 `snapshotSummary`，
默认了"每步都有快照"。**草稿里 `ladder.acquire(step)` 返回 `ScreenSnapshot?`（可空）**
是刻意的：**第 0 档返回 null 是合法状态，不是错误。**

> ⚠️ **这是最容易在实现时被"顺手修好"的地方** —— 一个可空返回值看起来像是设计缺陷，
> 后来者会倾向把它改成非空。**必须在接口注释里写明"null 表示第 0 档，是正常路径"。**

### 2.3.3 定位阶段：优先缓存，输出序号而非坐标

现有 `Grounder` 契约的三条要点（保持）：
1. 优先命中 UI 图谱缓存 → 重复任务成本趋近 0
2. **输入用序号而非坐标** → 准确率更高、token 更省
3. 低置信度返回 `NeedMoreInfo` → 补一张截图重试

**草稿新增一条**：`GroundResult.Ambiguous` 现在是"返回候选列表，由调用方消歧"，
但**调用方是谁、怎么消歧**没定义。建议补：

```kotlin
sealed interface AmbiguousResolution {
    data class PickFirst(val reason: String) : AmbiguousResolution      // 置信度差距大
    data class AskUser(val options: List<String>) : AmbiguousResolution // 差距小，交给用户
    data class Refine(val hint: String) : AmbiguousResolution           // 再问一次模型
}
```

**判据建议**：最高分与次高分的置信度差距 > 0.2 → `PickFirst`；否则 `AskUser`。
（与项目其它地方一致：**能自动判断的自动判断，不能的明确交给用户，不做静默猜测。**）

### 2.3.4 校验阶段：规则优先，且必须能覆盖 80%

现有 `Verifier` 契约已明确"规则层必须优先，每一步都调模型会把用户的钱烧光"。

**草稿补充规则层的具体清单**（这些是零成本的）：

| 规则 | 判定 |
|---|---|
| 包名 / Activity 变化 | 期望跳转 → Success；未变 → 可能 Failed |
| 目标文本出现 / 消失 | 按 `PlanStep.expectation` 匹配 |
| 元素仍在原位 | 点击未生效 → Failed |
| 屏幕无任何变化 | 连续 2 次 → **卡死**（现有契约已定义：同一动作重复 2 次且屏幕无变化） |

**⚠️ 卡死检测的一个陷阱**：某些动作（如 `WAIT`、`SCROLL` 到底部）**本就应该无变化**。
卡死检测必须**排除这些动作类型**，否则会把正常的"滚到底了"判成卡死。
（这是"类型化的动作枚举"（§2.1 第 ③ 条修正）带来收益的又一个例子 —— 字符串版本没法可靠排除。）

## 2.4 待决策

| ID | 问题 | 选项 | 建议 |
|---|---|---|---|
| **D-AR** | 重规划后 `stepIndex` 是否重置？ | 重置 / 累加 | **累加**。重置会让 `maxSteps` 失去意义（无限重规划 = 无限步数），而移动端最怕的就是轮次失控 |
| **D-AS** | `PlanStep.actionType` 是否改成 `ActionType` 枚举？ | 改 / 保持 String | **改**。理由见 §2.1 第 ③ 条 —— 字符串拼错是静默的 |
| **D-AT** | `GroundResult.Ambiguous` 的消歧判据 | 置信度差距阈值 / 总是问用户 | **阈值 0.2**，超过自动选、否则问用户 |
| **D-AU** | 规划失败的降级 | 直接失败 / 降级为手动引导 | **降级**。与原则 5 一致（"手动引导是正式产品形态"） |
| **D-AV** | `AgentBudget` 的默认值 | 见 §3.1 | 轮次 8 / 时长 5min / 能耗 0.1% |
| **D-AW** | Checkpoint 落盘频率 | 每步 / 每 N 步 | **每步**。移动端进程随时会死（移动端文档 §2.3），省这一次写入会让恢复点变得不可预测 |

---

# 第三部分 · P3/P4/P5：三条能力通道

## 3.1 P5：五个横切组件（建议**先于** P3/P4 做，因为它们约束后者的形状）

| 组件 | 职责 | 为什么不能后加 |
|---|---|---|
| **`AgentBudget`** | 轮次 / 时长 / 能耗 / 流量上限，超限主动中止 | ★ **轮次能耗差三个数量级**（30 轮 0.27% vs 8 轮 0.07%）。事后加等于重写循环 |
| **`TaskCheckpoint`** | 每步后持久化任务状态 | 移动端中断频率极高，**恢复能力是产品形态的一部分**，不是补丁 |
| **`PerceptionLadder`** | 五档降级选择 | 它决定循环里"要不要感知"，**循环骨架依赖它** |
| **`PrivacyFilter`** | 上传前裁剪 / 降质 / 遮蔽 | ★ 必须是**所有上传路径的唯一出口**。事后加必然漏 |
| **`PowerGovernor`** | 能耗采集 + 触发降级 | 需要全局视角（App + Node + 网络），**单层实现不了** |

**建议的默认预算值（草稿）：**

```kotlin
data class AgentBudget(
    val maxTurns: Int = 8,            // 移动端文档的推荐值
    val maxDurationMs: Long = 300_000, // 5 分钟
    val maxEnergyPercent: Float = 0.1f, // 0.1% —— P0-5 实测读数可用，但 ±1% 量化误差
    val maxUploadBytes: Long = 5_000_000, // 5MB，非 WiFi 下的可感知阈值
    val requireWifiForHeavy: Boolean = false, // 用户可选
)
```

> ⚠️ **能耗上限 0.1% 与 P0-5 的实测结论有张力**：P0-5 结论是"±1% 量化误差 →
> 只能测长任务，测不了单次任务"。**0.1% 的预算用当前读数是测不准的。**
> → 这暴露一个真实缺口：**要么改用更细的能耗来源（如 `BatteryStats`），
> 要么把能耗预算改为"任务结束后核对"而非"中途强制中止"**。见 D-AV。

## 3.2 P3：感知通道

**现状**：`:perception` 只有 `ScreenSnapshot.kt`（契约完整，零实现）。

**关键约束（`ScreenSnapshot.kt` 契约注释已写明）**：
- ⚠️ **这是感知层的唯一出口** —— 决策层不认识 `AccessibilityNodeInfo`，也不认识 `Bitmap`
- 无障碍树遍历**必须支持超时**（建议 300ms），超时返回部分结果
- 截图**必须走前台服务**，且 Android 14+ 每次会话需用户确认
- `dispose()` **必须在 `finally` 中调用** —— 截图是最高敏感度数据

**实施建议**：

| 步 | 内容 | 依赖 |
|---|---|---|
| 3.2.1 | `AccessibilitySnapshotProducer`（树 → `UiNode` 归一化） | 真机 |
| 3.2.2 | `MediaProjectionSnapshotProducer`（截图 + OCR 兜底） | 真机 |
| 3.2.3 | `PerceptionLadder` 的档位决策（**纯逻辑，可离线测**）| — |
| 3.2.4 | `PerceptionManager` 实现（编排上面三者）| 3.2.1–3 |

**★ 3.2.3 可以离线做** —— 档位决策是"给定期望与能力，选最低够用的档"，
纯策略逻辑，与 Android 无关。

## 3.3 P4：执行通道

**现状**：`:action` 只有 `ActionExecutor.kt`（契约完整，零实现）。

**四条通道的优先级与降级**（契约已定义）：

| 通道 | 抗政策风险 | 体验 | 实施难度 |
|---|---|---|---|
| `ACCESSIBILITY` | **低**（Android 17 APM 会禁） | 好 | 中 |
| `SHIZUKU` | 中 | 一般 | 中（需装 Shizuku） |
| `IME` | 中 | 一般 | 高（需自建输入法） |
| `OVERLAY_PROMPT` | **高** | 差 | 低（复用悬浮球） |

**建议实施顺序：`ACCESSIBILITY` → `OVERLAY_PROMPT` → `SHIZUKU` → `IME`。**

理由：
1. **先做 ACCESSIBILITY**（体验最好，且是当前政策下仍可用的主路径）
2. **立刻做 OVERLAY_PROMPT**（成本最低，且它是"永远可用"的兜底 —— 
   产品的抗风险承诺靠它兑现，不能留到最后）
3. SHIZUKU / IME 是增强项，依赖用户额外配置

> ⚠️ **`HumanizePolicy` 已在契约里定义**（随机偏移 / 随机间隔 / 频率上限）。
> 契约注释明确写了它的正当性："不是规避检测的黑产手段，而是让自动化行为更接近真人操作节奏"。
> **实施时必须真的随机化** —— 固定常量会让"拟人化"变成"规律化"，效果相反。

---

# 第四部分 · P6/P7：视觉层与发行

## 4.1 P6：overlay 视觉层 + 急停跨进程投递

**现状缺口**（MEMORY 已记录）：
- `OverlayService` 视觉层**只打日志**
- 急停的**跨进程实际投递没做**

**必须真机的部分**：面板 / 进度环 / 吸附动画 / 模型选择菜单。

**可以离线的部分**：进度环的**状态推导**（`overlaylogic` 已有状态机）、
急停的**协议定义**（消息格式、超时、确认）。建议先把协议冻结，再做投递。

**⚠️ 一条易忘的约束**：贴边几何算错的后果链特别长 ——
球跑出屏幕 → 丢掉"存在可见 overlay 窗口" → **丢掉 Android 15 的后台启动 FGS 豁免**。
`overlaylogic` 的 `computeEdgePosition` 已按此实现（球心停在屏幕边缘内侧，只留一条细线），
**改动时要保持这个性质**。

## 4.2 P7：发行 build

**内容**：确保 `debuggable = false`。

**为什么单列一项**：这关系到 `run-as com.termux` 那套开发期验证手段的有效性。
MEMORY 已记：「**仅限开发期验证** —— 正式 APK 不该 debuggable，我们自己的应用将来不能用这招」。
→ **必须有一个明确的检查点**，否则容易带着 `debuggable=true` 发出去。

**建议**：加进 `tools/verify/` 的检查清单，做成脚本守卫（防止复发）。

---

# 第五部分 · 实施顺序建议（含真机依赖标注）

| 阶段 | 内容 | 真机 | 可离线 | 预计产出 |
|---|---|---|---|---|
| **S1** | P1 的 1.4.1–1.4.5（`GatewayCore` 全套） | ❌ | ✅ | 网关核心 + 单测 |
| **S2** | P2 的纯逻辑部分：`Planner` 契约修正、`TaskStateMachine` 实现、`AgentBudget` | ❌ | ✅ | 循环骨架 + 状态机 |
| **S3** | P5 的 `AgentBudget` / `TaskCheckpoint` / `PerceptionLadder`（纯逻辑） | ❌ | ✅ | 三个横切组件 |
| **S4** | P7（发行检查脚本） | ❌ | ✅ | 守卫脚本 |
| ─── | ─── **以下是真机分界线** ─── | ─── | ─── | ─── |
| **S5** | P1 的 1.4.6–1.4.7（HTTP 服务端 + 安全加固） | ✅ | ❌ | 可用的网关 |
| **S6** | P3 的 3.2.1–3.2.2（感知实现） | ✅ | ❌ | 能看屏幕 |
| **S7** | P4 的 ACCESSIBILITY + OVERLAY_PROMPT | ✅ | ❌ | 能点屏幕 |
| **S8** | P2 的端到端接通 | ✅ | ❌ | **能执行任务** |
| **S9** | P6（视觉层）+ 其余执行通道 | ✅ | ❌ | 产品形态完整 |

**★ S1–S4 全部不需要真机** —— 这是这份规划最有价值的信息：
**在真机就位之前，有相当一部分工作可以完整推进并验证。**

---

# 第六部分 · 待决策汇总（本文字新增）

| ID | 问题 | 建议 | 出处 |
|---|---|---|---|
| **D-AP** | 网关是否拆「核心 / HTTP 外壳」两层 | 采纳 | §1.2 |
| **D-AQ** | `ChatRequest.model` 由谁决定 | 网关忽略，只看路由 | §1.6 |
| **D-AR** | 重规划后 `stepIndex` 是否重置 | 累加（不重置） | §2.4 |
| **D-AS** | `PlanStep.actionType` 改 `ActionType` 枚举 | 改 | §2.1 |
| **D-AT** | `Ambiguous` 消歧判据 | 置信度差距 0.2 | §2.3.3 |
| **D-AU** | 规划失败的降级 | 降级为手动引导 | §2.4 |
| **D-AV** | `AgentBudget` 默认值 + 能耗预算的测准问题 | 轮次 8 / 时长 5min；**能耗改"事后核对"** | §3.1 |
| **D-AW** | Checkpoint 落盘频率 | 每步 | §2.4 |

**★ D-AV 需要特别说明**：它不是一个"选哪个数字"的问题，而是暴露了一个**真实的技术缺口**
（P0-5 实测：能耗读数 ±1% 量化误差）。**建议在 S3 阶段先做一个小实验确认能拿到更细的读数**，
再决定能耗预算是"硬中止"还是"事后核对"。

---

# 附录 · 本文对既有文档的修正

| # | 文档 | 原文 | 修正 |
|---|---|---|---|
| 1 | BYOK 文档 §5.3 | 只规划了单一 `LlmGatewayServer` | 拆成 `GatewayCore`（纯逻辑）+ `HttpGatewayServer`，理由见 §1.2 |
| 2 | BYOK 文档 全文 | 假设网关消费方只有 dsh | 实际有两个（dsh + agent 循环），后者不需要 HTTP |
| 3 | `AgentOrchestrator.kt` | `PlanStep.actionType: String` | 建议改 `ActionType` 枚举，理由见 §2.1 |

---

# 附录 B · 本文的确定性分级

| 内容 | 确定性 |
|---|---|
| 现有各契约（`AgentOrchestrator` / `ScreenSnapshot` / `ActionExecutor` / `SafetyGuard` / `ModelRouter` / `LlmProvider`）的实际内容 | **已核对源码** |
| BYOK 文档中的 dsh 实测结论（baseURL / headers / 三协议 / MCP transport） | **转述实测**（原文见该文档附录 A） |
| 五个横切组件的必要性论证 | **转述移动端文档**（§2.1 能耗账） |
| 循环骨架的伪代码、模块切分、实施顺序 | **本文建议**，待评审 |
| 能耗预算 0.1% 与 P0-5 量化误差的冲突 | **本文发现的缺口**，需小实验确认 |
