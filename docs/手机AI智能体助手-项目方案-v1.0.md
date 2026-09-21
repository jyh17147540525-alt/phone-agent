# 手机 AI 智能体助手（BYOK 版）项目方案

> **版本**：v1.0
> **日期**：2026-09-20
> **项目代号**：PocketAgent（暂定）
> **文档状态**：待评审 → 评审通过后进入 M0 技术验证

---

## 目录

- [0. 决策摘要（先看这一页）](#0-决策摘要先看这一页)
- [1. 产品目标与核心功能范围](#1-产品目标与核心功能范围)
- [2. 可行性边界：平台现实](#2-可行性边界平台现实)
- [3. 核心功能范围（MVP / V1 / V2）](#3-核心功能范围mvp--v1--v2)
- [4. 用户导入与管理 API Key 的方式](#4-用户导入与管理-api-key-的方式)
- [5. AI 能力在系统层面的实现思路](#5-ai-能力在系统层面的实现思路)
- [6. 主要模块划分](#6-主要模块划分)
- [7. 关键技术选型](#7-关键技术选型)
- [8. 数据与隐私安全](#8-数据与隐私安全)
- [9. 潜在风险与应对](#9-潜在风险与应对)
- [10. 分阶段实施计划](#10-分阶段实施计划)
- [11. 资源与成本估算](#11-资源与成本估算)
- [12. 附录](#12-附录)

---

## 0. 决策摘要（先看这一页）

### 0.1 一句话定义

**一款 Android 原生应用，用户自带大模型 API Key（BYOK），应用通过系统级能力读取屏幕与操作 App，在手机上复刻"豆包手机助手"式的跨应用任务执行体验。**

### 0.2 五个必须先接受的现实

| # | 现实 | 影响 |
|---|------|------|
| 1 | **Android 17 起收紧 AccessibilityService**：Beta 2 已实现「高级保护模式（APM）下非无障碍类应用禁止获取无障碍权限，已授权限自动撤销」 | 屏幕自动化不能单押无障碍一条路，必须做**双通道抽象** |
| 2 | **iOS 侧基本无解**：Siri/App Intents 的屏幕感知与跨应用执行由 Apple 与 Gemini 独家把持，第三方 App 无系统级读屏与代操作能力 | **项目锁定 Android，iOS 仅做"对话端"**（可选远期） |
| 3 | **Google Play 2026-07 政策**：第三方 AI 集成纳入用户数据条款，屏幕内容发往外部模型必须**传输前同意 + 隐私政策披露 + Data safety 表单声明** | 合规成本前置，不是上线前才补 |
| 4 | **豆包已用 SAEP 协议划定生态规则**：第三方应用可声明"拒绝 AI 在我这里自动化操作" | 未来可能面临 App 主动反制，需设计**尊重与降级机制** |
| 5 | **BYOK 模式下成本由用户承担，但 token 消耗由你的架构决定**：多模态截图每步都是钱 | 必须做**截图裁剪 + 分辨率分级 + 模型分级路由**，否则用户会骂你 |

### 0.3 推荐的路线选择

```
感知层：无障碍树为主干 + 多模态截图兜底（Hybrid）  ← 不是纯视觉，也不是纯无障碍
执行层：无障碍手势为主 + Shizuku/ADB 通道为辅 + 悬浮窗/IME 补充
分发：  国内应用商店 + 官网 APK 为主，Google Play 为备选
定位：  "辅助工具"而非"AI 服务提供者"（BYOK 的关键法律定位）
```

---

## 1. 产品目标与核心功能范围

### 1.1 产品目标

**核心目标**：让用户用任意一家大模型的 API Key，在 Android 手机上获得"说一句话，手机自己把事情办完"的体验。

**三级目标拆解**：

| 层级 | 目标 | 衡量指标 |
|------|------|----------|
| 业务目标 | 成为 BYOK 模式下最好用的手机智能体 | 次周留存 ≥ 35%，DAU/MAU ≥ 0.3 |
| 用户目标 | 少点 30 次屏幕，说一句话就完事 | 单任务平均替代手动操作步数 ≥ 8 步 |
| 技术目标 | 高频任务成功率可交付 | 单步操作成功率 ≥ 95%，5 步内任务端到端成功率 ≥ 75% |

### 1.2 目标用户

| 用户群 | 特征 | 核心诉求 | 优先级 |
|--------|------|----------|--------|
| **极客/开发者** | 手里有多个 API Key，爱折腾 | 自定义模型、看得到每一步、可调参数 | P0（种子用户） |
| **效率工具爱好者** | 用 Tasker/快捷指令，愿意配权限 | 跨 App 自动化、定时任务 | P0 |
| **普通尝鲜用户** | 被"AI 手机"概念吸引 | 一句话办事，不想学配置 | P1（但转化门槛高） |
| **隐私敏感用户** | 不愿把数据交给厂商 | 本地模型、数据不出机 | P1（差异化卖点） |

### 1.3 与豆包手机助手 / 系统级方案的本质差异

| 维度 | 豆包手机助手 | 本项目 |
|------|--------------|--------|
| 权限来源 | 与手机厂商**系统级合作**，预装/系统签名 | 用户手动授权的第三方 App 权限 |
| 模型 | 字节自有豆包模型，服务端 | **用户自带的任意模型** |
| 商业模式 | 卖服务/卖手机 | 卖工具（或免费+增值），**不承担推理成本** |
| 能力上限 | 高（系统 API 直调） | 中（受限于公开权限） |
| 生态阻力 | 有（所以推出 SAEP） | **更大**（无名无分，容易被当外挂） |
| 合规风险 | 有厂商背书 | **全部自己扛** |

**结论**：不要试图正面复刻系统级能力。差异化定位是 **"可换模型的、透明的、数据自主的"助手** —— 用户能用 GPT / Claude / Gemini / DeepSeek / 通义 / 本地模型自由切换，这是豆包永远做不到的。

### 1.4 产品边界（明确不做什么）

- ❌ 不做"自动抢红包""自动刷单""自动签到"等薅羊毛场景（风控与合规双重雷区）
- ❌ 不碰金融类操作（转账、支付、理财），只做到"打开页面"为止
- ❌ 不破解、不注入、不 hook 第三方 App
- ❌ 不做游戏辅助
- ❌ 不提供模型服务本身，不转售 token

---

## 2. 可行性边界：平台现实

> 这一章是全篇最重要的部分。产品经理画的饼能不能吃，取决于这里的每一条。

### 2.1 Android 无障碍服务（AccessibilityService）收紧

**现状**：Android 17 Beta 2 起，**高级保护模式（Advanced Protection Mode, APM）** 下：

1. 系统检查应用是否声明为"无障碍工具"类别，非此类应用**直接拦截**，用户连手动授权入口都没有
2. 升级或开启保护模式后，**已授予的权限自动撤销**
3. 保护模式下无法通过 ADB 或开发者选项绕过

**受影响的已知案例**：Tasker、Auto.js 等自动化工具，dynamicSpot 等悬浮窗类应用。

**对本项目的影响**：

- 目前限制**仅在 APM 模式下生效**（APM 是面向高风险用户的强保护模式，非默认）
- 但 Google 惯例是"先在可选模式试水，后续扩大为默认行为"
- **必须做双通道设计**，不能把产品命脉押在单一 API 上

**应对策略**：

| 通道 | 机制 | 抗政策风险 | 用户体验 |
|------|------|-----------|----------|
| A. 无障碍服务 | AccessibilityService + dispatchGesture | 低（会持续收紧） | 好（一次授权长期有效） |
| B. Shizuku / ADB | 用户通过无线调试授权 shell 权限，调 `input`、`am`、`screencap` | 中（系统级能力，不受无障碍政策约束） | 一般（每次重启需重新激活） |
| C. 悬浮窗 + 用户确认 | SYSTEM_ALERT_WINDOW 显示建议，用户手点 | 高 | 差（但永不失效，作为最终兜底） |
| D. 自建输入法 | 注册 IME，注入文本 | 中 | 一般（需用户切换输入法） |

**架构要求**：执行层必须抽象为 `ActionExecutor` 接口，多通道可插拔、可运行时降级。

### 2.2 Google Play 政策（2026-07-15 生效）

核心条款：**第三方 AI 集成纳入用户数据要求**，开发者对 limited use / disclosure / consent 负责。

三项强制义务：

| 义务 | 具体要求 | 落地物 |
|------|----------|--------|
| **披露** | 隐私政策 **和** Play Data safety 表单都必须声明"用户数据会离开应用，发往第三方模型提供商" | 仅声明自家后端**属于错误填报** |
| **同意** | 用户必须在**数据传输发生前**同意，不能事后补 | 独立的前置同意闸门，埋在服务条款勾选框里会被驳回 |
| **有限使用** | 数据只能用于已声明目的 | 发送屏幕内容用于"当前任务执行"✅；留存用于训练 ❌ |

**SDK 陷阱**：第三方分析/支持类 SDK 若把内容路由到模型提供商，**同样算作你的第三方 AI 集成**，责任在你。

**对本项目的额外要求**：屏幕截图内容属于高度敏感的用户数据，需要：

- 独立于通用隐私政策的**"屏幕内容处理说明"**
- 提供**"纯本地模式"**（模型在端侧运行，截图不出设备）作为合规避风港
- 首次启动的权限引导中，逐项说明"为什么需要读屏"

### 2.3 SAEP 协议与生态反制

**SAEP（Screen Automation Execution Protocol，屏幕自动化操作声明协议）**：2026-09-14 由豆包随"操作手机"功能 Beta 一同发布，允许第三方应用声明是否接受 AI 助手在其 App 内进行屏幕自动化操作，明确拒绝的应用将不被自动化。

**对本项目的含义**：

- 这是一份**行业级"电子警戒线"**，未来可能成为事实标准
- 本项目应**主动兼容**：设计一个 `AutomationPolicyRegistry`，读取/维护第三方 App 的自动化许可状态
- 对声明拒绝的 App，降级为"展示操作步骤 + 用户手动执行"
- **不要把兼容当负担，这是产品可信度的来源**：一个"会尊重 App 说不的助手"比"什么都硬点的助手"活得更久

### 2.4 iOS 侧可行性

| 能力 | iOS 支持情况 | 结论 |
|------|--------------|------|
| 读取屏幕内容 | 无第三方 API | ❌ |
| 模拟点击/手势 | 无（App Intents 只能暴露自家 App 能力） | ❌ |
| 跨应用自动化 | 快捷指令受限，无法读取他人 App 界面 | ❌ |
| 系统级助手位 | Siri 由 Apple 独占 | ❌ |
| 对话式 AI 客户端 | 完全可行 | ✅ |

**结论**：iOS 端只能做"对话 + 手动执行提示"的降级版本。**MVP 不做 iOS。**

### 2.5 路线选择矩阵（最终决策）

| 能力 | MVP 采用 | 理由 |
|------|----------|------|
| 屏幕理解 | 无障碍树主干 + 截图兜底 | 平衡 token 成本、精度与通用性 |
| 动作执行 | 无障碍手势（主）+ Shizuku（辅） | 双通道抗政策风险 |
| 悬浮交互 | SYSTEM_ALERT_WINDOW 悬浮球 | 唯一不受政策影响的入口 |
| 触发方式 | 悬浮球 + 通知栏 + 快捷方式 + 助手位 | 多入口冗余 |
| 截图 | MediaProjection | 唯一合法途径（需注意 Android 14+ 每次会话需用户确认） |
| 敏感页面 | 主动黑名单 + 停止执行 | 安全底线 |

---

## 3. 核心功能范围（MVP / V1 / V2）

### 3.1 功能全景

| 模块 | 功能 | MVP | V1 | V2 |
|------|------|:---:|:--:|:--:|
| **Key 管理** | 手动粘贴导入 | ✅ | ✅ | ✅ |
| | 剪贴板智能识别（自动判厂商） | ✅ | ✅ | ✅ |
| | 多 Key / 多 Provider 并存 | ✅ | ✅ | ✅ |
| | Key 有效性校验与健康检测 | ✅ | ✅ | ✅ |
| | 二维码 / 配置文件导入 | | ✅ | ✅ |
| | 加密导出与跨设备迁移 | | ✅ | ✅ |
| | 用量统计与预算上限 | | ✅ | ✅ |
| | 团队 Key 池（共享配额） | | | ✅ |
| **对话能力** | 文本对话 + 流式输出 | ✅ | ✅ | ✅ |
| | 多轮上下文 | ✅ | ✅ | ✅ |
| | 提示词模板库 | ✅ | ✅ | ✅ |
| | 自定义 System Prompt | ✅ | ✅ | ✅ |
| | 多模型对比（同一问题并行问多个） | | ✅ | ✅ |
| **屏幕能力** | 截图问答（"这是什么"） | ✅ | ✅ | ✅ |
| | 划词/复制文本解释 | ✅ | ✅ | ✅ |
| | 屏幕内容总结 | ✅ | ✅ | ✅ |
| | 实时屏幕感知（持续读屏） | | ✅ | ✅ |
| **任务执行** | 单步操作（打开 App / 点按钮） | ✅ | ✅ | ✅ |
| | 多步任务规划与执行 | | ✅ | ✅ |
| | 执行过程可视化（每步可看可停） | ✅ | ✅ | ✅ |
| | 失败自动重规划 | | ✅ | ✅ |
| | 任务模板（预设场景一键跑） | | ✅ | ✅ |
| | 定时/条件触发任务 | | | ✅ |
| **记忆系统** | 会话历史 | ✅ | ✅ | ✅ |
| | 用户偏好记忆 | | ✅ | ✅ |
| | App UI 图谱缓存（加速定位） | | ✅ | ✅ |
| | 长期个人知识库（RAG） | | | ✅ |
| **系统集成** | 悬浮球 | ✅ | ✅ | ✅ |
| | 通知栏快捷入口 | ✅ | ✅ | ✅ |
| | 桌面快捷方式 / Widget | | ✅ | ✅ |
| | 注册为默认数字助手 | | ✅ | ✅ |
| | 语音输入 / TTS | | ✅ | ✅ |
| **扩展生态** | 插件 / MCP 工具接入 | | | ✅ |
| | 本地模型支持 | | ✅ | ✅ |
| | 跨设备（平板 / 车机） | | | ✅ |

### 3.2 MVP 定义与验收标准

**MVP 目标**：验证"BYOK + 屏幕理解 + 基础执行"这条技术链路跑得通，且用户愿意用。

**MVP 功能清单（必须全部完成）**：

1. 用户导入至少 1 个 Provider 的 API Key，加密存储，校验通过
2. 文本对话可用，流式输出，支持 3 家以上 Provider
3. 悬浮球可唤起，支持"截图问答"
4. 无障碍服务可授权，能读取当前界面元素树
5. 能执行单步操作：打开指定 App、点击指定元素、输入文本、返回/Home
6. 执行过程有可视化反馈，用户可随时中断
7. 敏感页面（支付/密码/验证码）自动拒绝截图并提示

**MVP 验收标准（硬指标）**：

| 指标 | 目标值 |
|------|--------|
| 单步操作成功率 | ≥ 95% |
| 截图问答首字延迟（P50） | ≤ 2.5s |
| 无障碍树解析耗时 | ≤ 150ms |
| Key 加密存储审计 | 无明文落盘（含日志、崩溃上报） |
| 敏感页面拦截率 | 100% |
| 冷启动到可交互 | ≤ 1.5s |

---

## 4. 用户导入与管理 API Key 的方式

> 这是本产品的**灵魂模块**。做得好就是护城河，做得烂就是一个连不上模型的空壳。

### 4.1 Provider 抽象层

**核心设计原则**：所有 Provider 归一化到统一接口，上层业务不感知厂商差异。

```kotlin
interface LlmProvider {
    val id: String                    // "openai", "deepseek", "anthropic"...
    val displayName: String
    val authScheme: AuthScheme        // Bearer / x-api-key / QueryParam
    val defaultBaseUrl: String

    fun capabilities(): Set<Capability>   // VISION, TOOL_CALL, STREAM, JSON_MODE, LONG_CTX
    suspend fun validateKey(key: String, baseUrl: String?): KeyValidationResult
    suspend fun listModels(key: String): List<ModelInfo>
    fun chat(req: ChatRequest): Flow<ChatChunk>       // 流式
    fun countTokens(msgs: List<Message>, model: String): Int
    fun estimateCost(usage: Usage, model: String): Cost
}

enum class Capability { VISION, TOOL_CALL, STREAM, JSON_MODE, LONG_CTX, AUDIO_IN, AUDIO_OUT, LOCAL }
```

**Provider 适配清单（按优先级）**：

| 优先级 | Provider | 协议 | 备注 |
|:---:|----------|------|------|
| P0 | OpenAI | OpenAI Chat Completions | 事实标准，兼容层最多 |
| P0 | DeepSeek | OpenAI 兼容 | 国内性价比最高，用户基数大 |
| P0 | 阿里通义（DashScope） | OpenAI 兼容模式 | 国内主流 |
| P0 | 火山方舟（豆包模型） | OpenAI 兼容 | 与竞品同源模型，用户会想试 |
| P1 | Anthropic Claude | Messages API | 视觉与工具调用强，**协议不兼容需单独适配** |
| P1 | Google Gemini | generateContent | 长上下文与视觉强 |
| P1 | OpenRouter | OpenAI 兼容 | 一个 Key 通吃几十家，**强烈推荐默认推荐项** |
| P1 | 智谱 GLM / 月之暗面 Kimi / 硅基流动 | OpenAI 兼容 | 国内长尾 |
| P2 | Azure OpenAI | OpenAI 兼容 + 特殊鉴权 | 企业用户 |
| P2 | 本地模型（Ollama / LM Studio / llama.cpp） | OpenAI 兼容 / 自建 | 隐私用户刚需，**LAN 直连** |
| P2 | 百度文心 / 讯飞星火 | 各自 SDK | 老牌厂商，协议差异大 |

**关键设计：自定义 Provider**
允许用户手填 `Base URL + 模型名 + 鉴权方式 + 额外 Header`。这一条能覆盖 90% 的长尾需求，**并且是应对"某家改了协议"的逃生舱**。

### 4.2 导入方式（六种，按易用性排序）

| 方式 | 交互 | 适用场景 | 安全处理 |
|------|------|----------|----------|
| **1. 手动粘贴** | 选 Provider → 粘贴 Key → 点验证 | 通用 | 输入框 `FLAG_SECURE`，禁用剪贴板历史 |
| **2. 剪贴板智能识别** | 打开 App 自动检测剪贴板，正则匹配 Key 前缀（`sk-`、`sk-ant-`、`AIza`…），弹"检测到 DeepSeek Key，要导入吗？" | 最高频 | **识别后立即清空剪贴板**（`ClipboardManager.clearPrimaryClip()`） |
| **3. 二维码扫描** | 扫另一台设备的二维码 | 迁移、分享给他人 | 二维码内容一次性，扫描后销毁 |
| **4. 配置文件导入** | 选择 `.json` / `.txt` 文件 | 批量、开发者 | 导入后提示用户删除源文件 |
| **5. 深链接 / 剪贴板口令** | 点击 `pocketagent://import?token=...` | 从其他工具跳转 | token 一次性，有效期 ≤ 5 分钟 |
| **6. OAuth 授权（远期）** | 走厂商 OAuth 拿临时 token | 免去手动复制 | 需各厂商支持，落地成本高 |

**导入流程（统一）**：

```
用户输入/粘贴 Key
    ↓
本地格式预校验（前缀、长度、字符集）→ 失败则即时提示，不发请求
    ↓
[可选] 网络探测：调用 /models 或最小化 chat 请求（max_tokens=1）
    ↓
返回结果分类：
  ├─ 有效 → 读取可用模型列表 → 加密存储 → 完成
  ├─ 鉴权失败 → "Key 无效或已过期"
  ├─ 余额不足 → "Key 有效但余额不足"（仍允许保存，标记状态）
  ├─ 网络不可达 → "无法连接，是否保存为待验证？"（允许离线保存）
  └─ 限流 → "Key 有效但被限流"（保存并标记）
    ↓
写入加密存储 + 记录 Provider / BaseUrl / 模型 / 创建时间
```

### 4.3 加密存储方案

**三层防护**：

```
第 1 层：Android Keystore 生成 AES-256-GCM 主密钥（MasterKey）
        - setUserAuthenticationRequired(true) 可选（开启后需生物识别解锁）
        - setInvalidatedByBiometricEnrollment(true)（新增指纹则作废，防篡改）
        - 密钥永不离开 TEE/StrongBox
                ↓
第 2 层：每条 Key 用独立随机 IV 加密，密文 + IV + AuthTag 存入 Room
        - 数据库整体再用 SQLCipher 加密（防 root 后直接读库）
                ↓
第 3 层：应用层访问控制
        - Key 解密仅在发起请求的瞬间进行，用完立即清零
        - 使用 ByteArray/CharArray 而非 String（String 常驻字符串池无法擦除）
```

**必须遵守的红线**：

| 红线 | 说明 |
|------|------|
| ❌ Key 不进 SharedPreferences | 明文存储，root 后一览无余 |
| ❌ Key 不进日志 | 日志脱敏器强制过滤 `sk-`、`Bearer ` 等模式 |
| ❌ Key 不进崩溃上报 | 上报前做 PII 扫描；建议自建崩溃收集 |
| ❌ Key 不进备份 | `android:allowBackup="false"` 或配置 `backup_rules.xml` 排除 |
| ❌ Key 不进内存 dump 可见区 | 避免 String 常驻；不写入静态变量 |
| ✅ Key 页禁用截屏 | `WindowManager.LayoutParams.FLAG_SECURE` |
| ✅ 支持一键全部清除 | 清除时同时删除 Keystore 别名，确保不可恢复 |

**加密导出（V1）**：

- 用户设置导出密码 → Argon2id 派生密钥（内存 64MB、迭代 3 次）→ AES-256-GCM 加密
- 导出文件格式：`PocketAgentBackup` + 版本号 + salt + nonce + ciphertext
- **明确告知**：导出文件包含明文可解密的凭据，丢失等于泄露

### 4.4 Key 生命周期管理

| 能力 | 说明 |
|------|------|
| **健康检测** | 后台定时（默认 24h，可关）发最小请求验证 Key 有效性；连续失败 3 次标记为"失效"并通知用户 |
| **余额/配额查询** | 支持查询的 Provider 展示剩余额度；不支持的展示"本地累计消耗估算" |
| **用量统计** | 按 Provider / 模型 / 日期维度统计 token 数与估算费用；数据仅本地 |
| **预算上限** | 用户可设"每日/每月费用上限"，达到阈值后停止调用并提示（**BYOK 模式下的关键保护**） |
| **多 Key 轮询** | 同一 Provider 配多个 Key，按 round-robin 或"剩余额度优先"调度，规避单 Key 限流 |
| **限流退避** | 429 时指数退避 + 自动切换备用 Key |
| **Key 分组** | 按用途分组（如"日常对话用便宜模型""复杂任务用贵模型"） |
| **吊销与轮换** | 一键停用；支持"替换 Key"保留历史统计 |

### 4.5 多模型路由策略

**这是省钱的关键，也是体验的关键。**

```kotlin
interface ModelRouter {
    fun route(task: AgentTask, context: RoutingContext): ModelSelection
}

data class RoutingContext(
    val availableKeys: List<KeyProfile>,
    val dailyBudgetRemaining: Double,
    val requiresVision: Boolean,
    val requiresToolCall: Boolean,
    val taskComplexity: Complexity,   // SIMPLE / MODERATE / COMPLEX
    val userPreference: RoutingPreference  // CHEAPEST / FASTEST / BEST / LOCAL_ONLY
    val networkStatus: NetworkStatus
)
```

**分级路由表（默认策略）**：

| 任务类型 | 推荐模型档位 | 理由 |
|----------|--------------|------|
| 界面元素定位（Grounding） | 端侧小模型 / 便宜多模态 | 高频、简单、量大 |
| 单步指令理解 | 便宜快模型（如 DeepSeek-chat） | 简单分类任务 |
| 多步任务规划 | 强推理模型（如 Claude / GPT 高级档） | 低频但决定成败 |
| 截图内容问答 | 多模态中档 | 平衡质量与成本 |
| 敏感/隐私场景 | 本地模型 | 数据不出机 |

**用户可选偏好**：最省钱 / 最快 / 最强 / 只用本地。默认「均衡」。

### 4.6 Key 管理界面设计要点

- **首屏引导**：新用户第一次打开，用 3 步图文说明"为什么需要 API Key"，并给出「推荐先用 OpenRouter（一个 Key 通吃）」的引导路径
- **状态一目了然**：每个 Key 卡片显示 Provider 图标、模型数、状态灯（绿/黄/红）、今日消耗
- **一键测试**：卡片上直接有"测试"按钮，结果即时反馈
- **错误信息可操作**：不说"请求失败"，要说"Key 已过期，请到 DeepSeek 控制台重新生成"，并附跳转链接
- **成本可见**：在对话界面实时显示本次消耗，避免用户被账单惊吓

---

## 5. AI 能力在系统层面的实现思路

### 5.1 三层能力模型

```
┌─────────────────────────────────────────────────────────┐
│  感知层 (Perception)                                     │
│  无障碍节点树 / 屏幕截图 / OCR / 通知 / 剪贴板 / 语音      │
│  输出：统一的 ScreenSnapshot（结构化 UI 表示）             │
└──────────────────────────┬──────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│  决策层 (Cognition)                                      │
│  Orchestrator（任务规划） + Grounder（元素定位）           │
│  + Verifier（结果校验） + Memory（记忆）                  │
│  输出：ActionPlan（结构化动作序列）                        │
└──────────────────────────┬──────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│  执行层 (Action)                                         │
│  ActionExecutor 抽象：无障碍手势 / Shizuku / IME / 悬浮窗  │
│  输出：ActionFeedback（成功/失败/需要用户介入）             │
└─────────────────────────────────────────────────────────┘
```

### 5.2 感知层：三条技术路线对比与选型

| 维度 | A. 无障碍树 | B. 纯视觉截图 | C. 混合（推荐） |
|------|-------------|---------------|-----------------|
| 数据形式 | AccessibilityNodeInfo 树 → 文本/JSON | 像素 → 多模态模型 | 树为主 + 图兜底 |
| 定位精度 | 高（节点 ID 稳定） | 中（坐标回归有误差） | 高 |
| Token 消耗 | 低（几百~2K） | 高（单张图 1K~3K+） | 中（按需） |
| 延迟 | 低（~100ms） | 高（1~3s/步） | 中 |
| 通用性 | 差（自绘 UI / 游戏 / Flutter / WebView 覆盖不全） | 强 | 强 |
| 政策风险 | **高**（Android 17 收紧） | 中（MediaProjection 有限制） | 中 |
| 2026 社区主流 | 被边缘化（Surfer 2 等明确反对依赖） | 主流 | 工程实践中最优解 |

**为什么选混合而不是跟随"纯视觉"主流？**

纯视觉是**学术界为了跨平台泛化**做的选择，牺牲的是 token 成本与延迟。而本产品是**单平台（Android）+ 用户自付 token**，所以：

- 有低成本的结构化信息时**必须用**（用户的钱包不是实验室）
- 只在树信息不足时才付截图的代价
- 但**架构上不能依赖树**，因为 Android 17 之后它可能消失

**ScreenSnapshot 统一数据结构**：

```kotlin
data class ScreenSnapshot(
    val timestamp: Long,
    val packageName: String,
    val activityName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<UiNode>?,          // 无障碍树（可能为 null）
    val screenshot: Bitmap?,           // 截图（按需）
    val ocrResults: List<OcrBlock>?,   // OCR 补充
    val source: SnapshotSource,        // ACCESSIBILITY / VISION / HYBRID
    val confidence: Float
)

data class UiNode(
    val nodeId: String,                // 稳定标识（用于动作执行）
    val className: String,
    val text: String?,
    val contentDesc: String?,
    val hintText: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val children: List<UiNode>,
    val depth: Int
)
```

**降级决策逻辑**：

```
获取无障碍树
    ↓
树是否可用？(服务已授权 && 节点数 > 阈值)
    ├─ 是 → 提取交互元素 → 是否覆盖当前界面主要区域？
    │         ├─ 是 → 用树（source = ACCESSIBILITY）
    │         └─ 否 → 补截图 → 用混合（source = HYBRID）
    └─ 否 → 截图 + OCR → 纯视觉（source = VISION）
    ↓
输出 ScreenSnapshot
```

**截图成本优化（关键）**：

| 优化手段 | 效果 |
|----------|------|
| 只截变化区域（与上一帧 diff） | 省 60%+ 图像 token |
| 缩放至模型最优分辨率（非原始 2K） | 省 50%+ |
| 转 WebP 有损压缩 | 省 30% 体积 |
| 只在必要时截图（树可用就不截） | 省 80%+ |
| 连续任务复用首帧上下文 | 省 40%+ |
| 元素列表文本化（只传坐标+文本，不传图） | 省 70%+ |

### 5.3 决策层：双脑架构

**为什么需要双脑？** 长程规划与精确坐标定位是两种完全不同的能力，混在一个模型里会导致"规划很好但点不准"或"点得准但不会规划"。

```
┌────────────────────────────────────────────────────────┐
│  Orchestrator（规划脑）—— 云端强模型                      │
│  职责：理解用户意图 → 拆解任务 → 生成步骤序列 → 处理异常    │
│  输入：用户指令 + 任务历史 + 记忆 + 当前步骤反馈            │
│  输出：NextStep { action, target, params, expectation }  │
└───────────────────────┬────────────────────────────────┘
                        ↓ 目标描述（自然语言）
┌────────────────────────────────────────────────────────┐
│  Grounder（定位脑）—— 端侧小模型 / 便宜多模态              │
│  职责：把"微信搜索框"定位到具体节点 ID 或屏幕坐标           │
│  输入：ScreenSnapshot + 目标描述                          │
│  输出：ElementRef { nodeId | (x,y) } + confidence        │
└───────────────────────┬────────────────────────────────┘
                        ↓ 可执行动作
┌────────────────────────────────────────────────────────┐
│  Executor（执行）—— 本地                                  │
│  职责：调用 ActionExecutor 执行 → 采集反馈                 │
└───────────────────────┬────────────────────────────────┘
                        ↓ 执行结果
┌────────────────────────────────────────────────────────┐
│  Verifier（校验）—— 轻量规则 + 小模型                     │
│  职责：对比 expectation 与实际屏幕，判断成功/失败/异常      │
│  失败 → 回传 Orchestrator 重规划（最多 3 次）              │
└────────────────────────────────────────────────────────┘
```

**记忆系统（三层）**：

| 层级 | 存储内容 | 生命周期 | 存储位置 |
|------|----------|----------|----------|
| 短期 | 当前任务的步骤轨迹、屏幕快照摘要 | 单次任务 | 内存 |
| 中期 | 会话摘要、用户纠错记录 | 单次会话 | Room |
| 长期 | 用户偏好（常用 App、习惯路径）、App UI 图谱缓存 | 永久 | Room + 可选 RAG |

**App UI 图谱缓存（性能杀手锏）**：
首次在某 App 完成某操作后，记录「页面特征哈希 → 元素选择器」映射。下次遇到相同页面，**跳过模型调用直接定位**。这能把重复任务的成本降到接近 0。

**长任务稳健性机制**：

- **执行-验证闭环**：每个动作后强制校验，不通过则重规划（参考 Mano 的 Think-Verify-Action-Expectation 循环）
- **最大步数限制**：默认 20 步，超出则中止并询问用户
- **幂等性保护**：重复点击同一元素 2 次无变化 → 判定卡死 → 重新截图分析
- **用户接管点**：检测到登录页、验证码、支付页 → 立即暂停，交还用户

### 5.4 执行层：多通道设计

```kotlin
interface ActionExecutor {
    val channel: ExecutorChannel
    fun isAvailable(): Boolean
    suspend fun perform(action: UiAction): ActionResult
    fun priority(): Int   // 优先级，高者先用
}

enum class ExecutorChannel { ACCESSIBILITY, SHIZUKU, IME, OVERLAY_PROMPT }
```

| 通道 | 实现 | 支持动作 | 优势 | 劣势 |
|------|------|----------|------|------|
| **ACCESSIBILITY** | `AccessibilityService` | 点击节点、手势、滑动、文本输入、全局返回/Home/通知栏、滚动 | 精准、可读元素 | 政策风险、部分 UI 读不到 |
| **SHIZUKU** | Shizuku API → shell | `input tap/swipe/text`、`am start`、`screencap`、`settings`、`pm` | 系统级、不受无障碍政策约束、可启动任意 Activity | 需用户手动激活（无线调试），重启后失效 |
| **IME** | 自定义输入法 | 文本注入（绕过部分 App 的输入限制） | 兼容性好 | 用户需切换输入法 |
| **OVERLAY_PROMPT** | 悬浮窗提示 | 引导用户手动点击 | 永不失效 | 体验差，仅兜底 |

**关键：`am start` 直达能力**
很多时候用户说"帮我打开微信给张三发消息"，最省事的不是模拟点击，而是直接用 Intent 或 `am start` 直达。这要求维护一份「常用 App 深层链接映射表」（如微信、支付宝、地图、音乐、外卖）。**这是性价比最高的一项优化。**

**MediaProjection 截图注意事项**：

- Android 14+ 每次建立截图会话需用户确认（系统弹窗），**不能后台静默截图**
- 会话可被系统随时终止，需监听 `MediaProjection.Callback`
- Android 15+ 对单 App 截图能力有额外限制（`MediaProjection` 的 app-level 授权模式）
- 建议：**前台服务 + 常驻通知**，明确告知用户"正在读取屏幕"

### 5.5 兜底与降级策略

| 失败场景 | 降级方案 |
|----------|----------|
| 无障碍权限被撤销（Android 17 APM） | 自动切换 Shizuku 通道；若也不可用 → 悬浮窗引导模式 |
| Shizuku 失效（重启后） | 提示重新激活；期间使用无障碍 |
| 元素定位失败 3 次 | 转为"展示操作步骤 + 高亮目标位置"，让用户手动点 |
| 模型 API 不可用 | 切换备用 Provider / 备用 Key；全部失败则切本地模型 |
| 任务超步数 | 中止并展示已完成步骤，询问是否继续 |
| 敏感页面 | 立即停止，截图丢弃，提示用户手动操作 |
| 无网络 | 仅本地模型可用；否则进入纯离线对话模式 |

### 5.6 交互形态

| 形态 | 说明 | 优先级 |
|------|------|--------|
| **悬浮球** | 常驻边缘，点开唤起输入框/语音；可拖动、可隐藏 | P0 |
| **通知栏快捷入口** | 常驻通知，一键唤起 | P0 |
| **助手位（ROLE_ASSISTANT）** | 注册为系统默认数字助手，长按电源/Home 唤起 | P1 |
| **截图分享入口** | 从系统截图分享菜单直接送入 App 分析 | P1 |
| **桌面 Widget** | 常用任务模板一键执行 | P1 |
| **快捷方式** | 长按图标 → 预设任务 | P1 |
| **语音唤醒** | 本地关键词唤醒（如"小助手"） | P2 |

**执行过程可视化（必须做）**：
每一步都要在悬浮窗中展示"我现在在做什么、要点哪里"，并提供**中止按钮**。这是用户建立信任的唯一方式，也是产品最大的差异化体验。**黑盒执行 = 用户不敢用。**

### 5.7 关键流程时序（示例："帮我在美团点一杯瑞幸拿铁"）

```
用户：「帮我在美团点一杯瑞幸拿铁」
  │
  ├─[1] Orchestrator 规划
  │    步骤：1) 打开美团 2) 搜索"瑞幸" 3) 选择门店
  │          4) 选择"拿铁" 5) 选规格 6) 加入购物车
  │          7) 进入结算页 ← 到此为止，交还用户
  │
  ├─[2] 步骤1：Deep Link 直达（am start / Intent）
  │    反馈：美团首页已打开 ✓
  │
  ├─[3] 步骤2：感知层取 ScreenSnapshot
  │    树可用 → 找到搜索框节点 → Grounder 确认 → 点击 → 输入"瑞幸"
  │    反馈：搜索结果页已出现 ✓
  │
  ├─[4] 步骤3-6：循环 感知→决策→执行→验证
  │    命中"App UI 图谱缓存"（美团瑞幸页首次已记录）→ 跳过模型调用，直接定位
  │    反馈：已加入购物车 ✓
  │
  ├─[5] 步骤7：检测到结算/支付页面
  │    ⚠️ 命中敏感页面规则 → 立即停止
  │    → 悬浮窗提示："已到结算页，请你确认支付。我不会替你付款。"
  │
  └─[6] 记录本次轨迹 → 更新 UI 图谱缓存 → 更新用户偏好
```

**注意最后一步**：产品**主动放弃**支付环节。这不是能力不足，是**设计上的克制**，也是合规的护身符。

---

## 6. 主要模块划分

### 6.1 整体架构

```
┌───────────────────────────────────────────────────────────────┐
│                        App Module (UI)                        │
│  Compose UI │ 对话页 │ Key 管理页 │ 任务页 │ 设置页 │ 悬浮窗 UI  │
└──────────────────────────┬────────────────────────────────────┘
                           │
┌──────────────────────────▼────────────────────────────────────┐
│                    Domain Layer (纯 Kotlin)                    │
│  UseCase │ Entity │ Repository 接口 │ 业务规则（无 Android 依赖）│
└──────────────────────────┬────────────────────────────────────┘
                           │
┌──────────────────────────▼────────────────────────────────────┐
│                       Data Layer                               │
│  Repository 实现 │ 本地 DB(Room) │ 网络(OkHttp) │ 加密(Keystore) │
└──────────────────────────┬────────────────────────────────────┘
                           │
┌──────────────────────────▼────────────────────────────────────┐
│                   Platform Layer (系统能力)                     │
│  无障碍服务 │ Shizuku │ MediaProjection │ 悬浮窗 │ 通知监听       │
│  IME │ 前台服务 │ 快捷方式 │ 语音 │ 生物识别                     │
└───────────────────────────────────────────────────────────────┘
```

### 6.2 模块清单与职责

| # | 模块 | 职责 | 关键类/接口 | 优先级 |
|---|------|------|-------------|:---:|
| 1 | **core-common** | 工具类、扩展函数、常量、Result 封装 | `Result<T>`、`Dispatchers` | P0 |
| 2 | **core-crypto** | Keystore 封装、加解密、密钥生命周期 | `CryptoManager`、`SecureStore` | P0 |
| 3 | **core-database** | Room 数据库、DAO、迁移 | `AppDatabase`、各 Entity/DAO | P0 |
| 4 | **core-network** | OkHttp 客户端、SSE 解析、重试、脱敏日志 | `HttpClientFactory`、`SseParser`、`LogSanitizer` | P0 |
| 5 | **provider-api** | Provider 抽象接口与数据模型 | `LlmProvider`、`ChatRequest`、`Capability` | P0 |
| 6 | **provider-openai-compat** | OpenAI 及兼容厂商实现（覆盖 8+ 家） | `OpenAiCompatProvider` | P0 |
| 7 | **provider-anthropic** | Claude Messages API 适配 | `AnthropicProvider` | P1 |
| 8 | **provider-gemini** | Gemini generateContent 适配 | `GeminiProvider` | P1 |
| 9 | **provider-local** | Ollama / LM Studio / llama.cpp 接入 | `LocalProvider` | P1 |
| 10 | **key-management** | Key 导入、校验、存储、健康检测、用量统计 | `KeyRepository`、`KeyValidator`、`UsageTracker` | P0 |
| 11 | **model-router** | 模型路由与成本控制 | `ModelRouter`、`BudgetGuard` | P1 |
| 12 | **perception** | 屏幕感知统一入口 | `PerceptionManager`、`ScreenSnapshot`、`A11yTreeReader`、`ScreenshotCapturer`、`OcrEngine` | P0 |
| 13 | **action-executor** | 多通道动作执行抽象与实现 | `ActionExecutor`、`A11yExecutor`、`ShizukuExecutor`、`ImeExecutor` | P0 |
| 14 | **agent-core** | 任务编排：规划、定位、执行、校验循环 | `AgentOrchestrator`、`Grounder`、`Verifier`、`TaskStateMachine` | P0 |
| 15 | **memory** | 三层记忆与 UI 图谱缓存 | `MemoryStore`、`UiGraphCache`、`SessionSummarizer` | P1 |
| 16 | **safety-guard** | 敏感页面拦截、危险动作二次确认、自动化策略注册 | `SafetyGuard`、`SensitivePageDetector`、`AutomationPolicyRegistry` | P0 |
| 17 | **task-engine** | 任务模板、定时触发、任务队列 | `TaskTemplateRepository`、`TaskScheduler` | P1 |
| 18 | **overlay-ui** | 悬浮球、执行过程可视化、结果卡片 | `OverlayService`、`FloatingBall`、`StepTimelineView` | P0 |
| 19 | **assistant-service** | 无障碍服务、通知监听、前台服务、助手位注册 | `AgentAccessibilityService`、`AgentForegroundService` | P0 |
| 20 | **voice** | 语音输入、TTS、唤醒词 | `SpeechRecognizerWrapper`、`TtsEngine` | P2 |
| 21 | **plugin-sdk** | 插件/MCP 工具扩展（V2） | `ToolProvider`、`McpClient` | P2 |
| 22 | **onboarding** | 权限引导、首次配置向导、诊断工具 | `PermissionWizard`、`DiagnosticsTool` | P0 |

### 6.3 模块依赖关系（关键约束）

```
        app
         │
   ┌─────┴──────┬──────────┬─────────┬──────────┐
   ▼            ▼          ▼         ▼          ▼
 key-mgmt   agent-core  task-eng  overlay   onboarding
   │            │          │
   ▼            ▼          ▼
model-router perception  memory
   │            │          │
   └────────────┴──────────┘
                ▼
          action-executor
                ▼
           core-* (common / crypto / database / network)
                ▼
          provider-api → provider-*
```

**硬约束**：
- `agent-core` 不直接依赖任何 Provider 实现，只依赖 `provider-api`
- `perception` 与 `action-executor` 不依赖 `agent-core`（可独立测试）
- 所有 Provider 实现可独立编译为模块，方便第三方贡献
- `domain` 层零 Android 依赖，保证可单元测试

---

## 7. 关键技术选型

### 7.1 总体选型表

| 层面 | 选型 | 理由 | 备选（否决原因） |
|------|------|------|------------------|
| 平台 | **Android 10+ (API 29+)**，主推 API 34+ | 无障碍与 MediaProjection 能力完整 | — |
| 语言 | **Kotlin** | 唯一合理选择 | Java（冗长） |
| UI | **Jetpack Compose** | 声明式、悬浮窗也可复用 | XML View（维护成本高） |
| 架构 | **Clean Architecture + MVVM + Hilt** | 模块解耦、可测试 | MVC（不可维护） |
| 异步 | **Coroutines + Flow** | 与 Compose 天然契合，SSE 流式友好 | RxJava（过重） |
| 本地 DB | **Room + SQLCipher** | 类型安全 + 全库加密 | Realm（已边缘化） |
| 配置存储 | **DataStore (Proto)** | 替代 SharedPreferences | SharedPreferences（同步阻塞） |
| 网络 | **OkHttp + Retrofit + kotlinx.serialization** | SSE 需 OkHttp 原生支持 | Ktor（生态略薄） |
| DI | **Hilt** | 官方方案 | Koin（运行时开销） |
| 图片/图像 | **Coil** + 自研图像处理 | 轻量 | Glide |
| OCR | **ML Kit Text Recognition**（中英） | 端侧、免费、够用 | 云端 OCR（成本+隐私） |
| 端侧推理 | **LiteRT-LM / MediaPipe LLM Inference / ONNX Runtime Mobile** | Google 官方，NNAPI/GPU 加速 | 纯 llama.cpp JNI（维护成本高） |
| 端侧模型 | **Gemma 4 2B 级 / Qwen3 小尺寸 / AgentCPM-GUI** | 2B 级已可在中端机离线跑；AgentCPM-GUI 面向中文 App | — |
| 提权 | **Shizuku API** | 无需 root，社区成熟 | Root（用户门槛过高） |
| 崩溃监控 | **自建 / Sentry 私有化** | 数据出境合规 | Firebase Crashlytics（数据出境风险） |
| 构建 | **Gradle KTS + Version Catalog** | 依赖集中管理 | Groovy（可读性差） |
| 测试 | **JUnit5 + Turbine + Robolectric + 自建真机回归框架** | 无障碍/截图必须真机测 | — |
| CI/CD | **GitHub Actions / 自建 Jenkins** | 需真机测试集群 | — |

### 7.2 为什么不用 Flutter / React Native

这是个常见诱惑，得把账算清楚：

| 需求 | Flutter/RN 支持情况 | 结论 |
|------|---------------------|------|
| AccessibilityService | 需写原生插件，且插件与 UI 框架无关，跨端收益为零 | ❌ |
| MediaProjection | 需原生插件 | ❌ |
| SYSTEM_ALERT_WINDOW 悬浮窗 | 需原生插件，且窗口生命周期管理复杂 | ❌ |
| Shizuku | 需原生插件 | ❌ |
| 自定义输入法（IME） | **无法用跨端框架实现** | ❌ |
| 前台服务 + 保活 | 需原生插件 | ❌ |
| 高帧率悬浮 UI 与手势 | 跨端桥接有性能损耗 | ❌ |
| 普通业务 UI（对话页、设置页） | 跨端优势明显 | ✅ |

**结论**：本项目 **90% 的技术难点都在原生系统能力上**，跨端框架只会增加一层胶水代码和调试成本。**Kotlin 原生是唯一理性选择。** 如果你坚持用 Flutter，那只能说这项目适合当论文题目。

### 7.3 关键第三方库清单

| 用途 | 库 | 备注 |
|------|-----|------|
| Shizuku | `dev.rikka.shizuku:api` + `provider` | 需引导用户安装 Shizuku App |
| 权限请求 | `Accompanist Permissions` / 自研 | 无障碍需跳转系统设置 |
| 图片压缩 | 自研（Android BitmapFactory + WebP） | 控制截图体积 |
| JSON | `kotlinx.serialization` | 性能好，无反射 |
| Markdown 渲染 | `compose-markdown` / 自研 | 对话内容渲染 |
| 图表（用量统计） | `Vico` | Compose 原生 |
| 日志 | `Timber` + 自研脱敏 Tree | **必须脱敏** |
| 加密 | `androidx.security:security-crypto` + 自研 Keystore 封装 | 注意该库已停止维护，建议自研 |

---

## 8. 数据与隐私安全

### 8.1 数据分类与处理原则

| 数据类别 | 示例 | 敏感级 | 存储位置 | 是否出设备 |
|----------|------|:---:|----------|:---:|
| API Key | `sk-xxxx` | **极高** | Keystore + 加密 DB | ❌ 永不 |
| 屏幕截图 | 界面像素 | **极高** | 内存（任务结束即销毁） | ⚠️ 仅用户同意后 |
| 无障碍树内容 | 控件文本 | **高** | 内存 | ⚠️ 仅用户同意后 |
| 对话历史 | 用户提问与回答 | 高 | 本地加密 DB | ⚠️ 发往用户自己的 Provider |
| 用户偏好 | 常用 App、习惯 | 中 | 本地 DB | ❌ |
| 用量统计 | token 数、费用 | 低 | 本地 DB | ❌ |
| 崩溃日志 | 堆栈 | 中 | 本地 → 上报（脱敏） | ⚠️ 需脱敏 |
| 匿名遥测 | 功能使用频次 | 低 | 上报 | ✅ 可关 |

**核心原则**：**默认本地，出设备必告知，敏感数据永不出设备。**

### 8.2 安全设计清单

**传输安全**
- 全链路 TLS 1.3，证书固定（Certificate Pinning）可选
- 禁止明文 HTTP（`cleartextTrafficPermitted=false`，本地模型 LAN 直连单独放行）
- SSE 流式响应需正确处理中断与重连

**存储安全**
- API Key：Keystore + AES-256-GCM + SQLCipher（见 4.3）
- 对话历史：SQLCipher 加密
- 截图：仅内存，任务结束显式 `recycle()` 并置空引用
- `android:allowBackup="false"`，配置 `dataExtractionRules`
- 禁用 `debuggable`，开启 R8 混淆 + 字符串加密

**运行时安全**
- Root / Hook 检测（检测到则提示风险，但不阻断）
- 截屏防护：Key 管理页、隐私设置页 `FLAG_SECURE`
- 剪贴板：导入后立即清空；禁用剪贴板历史记录（`EXTRA_IS_SENSITIVE`）
- 内存：Key 用 `ByteArray`，用后 `fill(0)` 清零
- 日志：`LogSanitizer` 强制过滤 Key、手机号、身份证、银行卡模式

**权限最小化**
- 只申请必要权限，每项权限都有"为什么"说明页
- 无障碍服务配置中 `android:accessibilityFlags` 与 `canRetrieveWindowContent` 精确声明
- 不申请通讯录、短信、定位等无关权限

### 8.3 敏感页面保护机制（安全底线）

```kotlin
object SensitivePageRules {
    // 按包名黑名单
    val packageBlacklist = setOf(
        "com.eg.android.AlipayGphone",      // 支付宝
        "com.tencent.mm:pay",               // 微信支付
        "com.unionpay",                     // 银联
        "com.icbc", "com.ccb", "com.cmb",   // 银行
        // ...
    )

    // 按页面关键词
    val keywordPatterns = listOf(
        "支付", "付款", "转账", "确认支付", "输入密码", "验证码",
        "银行卡", "身份证", "支付密码", "指纹支付", "面容支付",
        "充值", "提现", "贷款", "借款"
    )

    // 按控件特征
    val dangerousWidgets = listOf(
        "密码输入框", "验证码输入框", "金额输入框"
    )
}
```

**触发后的行为（严格执行，不可配置绕过）**：

1. **立即停止**当前任务
2. **丢弃**已捕获的截图（不缓存、不上传）
3. 悬浮窗提示："检测到敏感页面，我已停止操作。请你手动完成这一步。"
4. 记录一条本地审计日志（不含敏感内容，仅记录"因敏感页面中止"）

### 8.4 合规要点

| 法规/政策 | 要求 | 落地动作 |
|-----------|------|----------|
| **Google Play 用户数据政策（2026-07）** | 第三方 AI 集成需披露 + 同意 + 有限使用 | 隐私政策 + Data safety 表单 + 前置同意闸门 |
| **《个人信息保护法》（中国）** | 告知同意、最小必要、单独同意（敏感信息） | 屏幕内容属敏感个人信息，需**单独同意**弹窗 |
| **《生成式人工智能服务管理暂行办法》** | 提供生成式 AI 服务需备案 | **BYOK 定位为"工具软件"**，不提供模型服务，规避备案义务（需法律意见确认） |
| **《移动互联网应用程序信息服务管理规定》** | App 备案 | 需完成工信部 App 备案 |
| **应用商店审核（华为/小米/OPPO/vivo）** | 无障碍权限需说明用途，可能被拒 | 提前准备权限使用说明与演示视频 |

**"工具定位"的法律论证要点**（建议找律师出意见书）：

1. 本 App 不提供、不转售、不代理任何模型服务
2. 模型调用由用户自备的第三方账号发起，用户与模型提供商之间是直接服务关系
3. 本 App 仅提供"屏幕读取与操作"的工具能力
4. 用户数据不经过本 App 运营方服务器（**这是关键 —— 架构上必须做到无自有后端**）

> ⚠️ **重要架构决策**：为了坐实"工具"定位，**MVP 阶段不建设任何自有服务端**。所有数据本地化，所有模型请求由客户端直连用户指定的 Provider。这既是合规护身符，也是产品卖点。

### 8.5 用户可控性（信任的基础）

| 控制项 | 说明 |
|--------|------|
| **一键暂停** | 悬浮球随时可停，所有任务立即中止 |
| **逐动作确认模式** | 可设置"每步都问我"，适合首次使用建立信任 |
| **权限使用日志** | 记录每次读屏的时间、App、原因，用户可查 |
| **数据一键清除** | 清除所有 Key、历史、缓存、记忆 |
| **出设备数据清单** | 明确列出"哪些数据在什么情况下会发到哪里" |
| **纯本地模式** | 一键切换到只用本地模型，彻底离线 |

---

## 9. 潜在风险与应对

### 9.1 风险矩阵

| # | 风险 | 概率 | 影响 | 等级 | 应对策略 |
|---|------|:---:|:---:|:---:|----------|
| R1 | **Android 17+ 无障碍 API 全面收紧**（APM 变默认） | 高 | 致命 | 🔴 | 多通道执行架构（无障碍/Shizuku/悬浮窗引导）；感知层不依赖树 |
| R2 | **应用商店因无障碍权限下架** | 中高 | 严重 | 🔴 | 主推官网 APK + 国内商店；准备权限用途说明；保持"辅助工具"合规定位 |
| R3 | **第三方 App 反制**（检测到自动化后限制、SAEP 声明拒绝） | 中高 | 严重 | 🔴 | 兼容 SAEP 类协议；尊重 App 声明；行为拟人化（随机延迟、非匀速滑动）；不做高频重复操作 |
| R4 | **API Key 泄露或被盗刷** | 中 | 严重 | 🟠 | Keystore 加密、日志脱敏、预算上限、异常用量告警、无自有后端 |
| R5 | **Token 成本失控**（用户被账单吓到） | 高 | 中 | 🟠 | 截图裁剪/压缩、模型分级路由、UI 图谱缓存、实时费用显示、预算熔断 |
| R6 | **长任务成功率低导致口碑崩** | 高 | 严重 | 🔴 | 先做单步与短任务；每步可视化可中断；失败降级为"步骤提示"；不吹牛 |
| R7 | **合规风险**（被认定提供生成式 AI 服务需备案） | 中 | 严重 | 🟠 | 无自有后端架构；工具定位；法律意见书；不提供任何模型服务 |
| R8 | **国产 ROM 权限管控差异**（小米/华为/OPPO 后台限制） | 高 | 中 | 🟠 | 分机型适配引导向导；保活策略；提供"诊断工具"自查 |
| R9 | **模型协议频繁变更导致适配失效** | 高 | 中 | 🟠 | Provider 插件化；自定义 Provider 逃生舱；协议变更快速响应流程 |
| R10 | **被竞品（豆包/厂商）降维打击** | 中 | 中 | 🟡 | 差异化定位：BYOK、可换模型、透明、数据自主；不做正面竞争 |
| R11 | **用户不理解"自带 Key"概念，转化率低** | 高 | 中 | 🟠 | 极简引导；推荐 OpenRouter 一站式方案；提供"免费额度试用"（用自己的 Key 池，成本可控） |
| R12 | **MediaProjection 每次需用户确认，体验割裂** | 高 | 中 | 🟡 | 会话保持策略；优先用无障碍树避免截图；清晰说明 |
| R13 | **反作弊系统误判**（银行/游戏/支付类） | 中 | 严重 | 🔴 | 主动黑名单；用户告知；不做任何游戏相关功能 |

### 9.2 针对 R1（最致命风险）的专项预案

**情景**：Android 18 或某次更新后，APM 成为默认开启，所有非无障碍类应用无法获取无障碍权限。

**预案（三阶段）**：

| 阶段 | 动作 |
|------|------|
| **现在** | 执行层抽象完成，Shizuku 通道与无障碍通道并行开发，能力对等 |
| **预警期** | 监控 Android 版本更新；一旦 APM 有变默认迹象，立即在引导流程中主推 Shizuku 通道 |
| **最坏情况** | 产品降级为"屏幕分析助手 + 步骤引导"模式：AI 看懂屏幕并给出操作建议，用户手动执行。**虽然体验打折，但产品不死**，且此形态完全合规 |

**关键认知**：把"手动引导模式"当作**正式产品形态**来设计，而不是当成残废版。它在政策收紧时是救命的，在政策宽松时是低门槛的入门模式。

### 9.3 针对 R3（第三方反制）的专项预案

**行为拟人化规范**（必须写进执行层）：

| 项 | 规范 |
|----|------|
| 点击位置 | 元素中心 ± 随机偏移（不超过元素边界 30%） |
| 操作间隔 | 300~800ms 随机，不使用固定值 |
| 滑动轨迹 | 贝塞尔曲线，非直线；速度非线性 |
| 频率限制 | 同一 App 内连续操作不超过 N 次/分钟 |
| 输入方式 | 逐字输入 + 随机间隔，而非一次性 setText |
| 夜间模式 | 用户不活跃时段降低自动化频率 |

**尊重机制**：
- 维护 `AutomationPolicyRegistry`，读取第三方 App 的自动化声明（未来 SAEP 类协议普及后接入）
- 对声明拒绝的 App，功能降级为"引导模式"，并在 UI 上明确说明原因（**把限制变成透明度卖点**）

---

## 10. 分阶段实施计划

### 10.1 总体路线图

```
M0 技术验证 ──► M1 MVP ──► M2 任务引擎 ──► M3 稳定与体验 ──► M4 合规发布 ──► M5 生态扩展
  2-3周        6-8周        8-10周          6周              4周            持续
  (关键)      (可内测)      (核心价值)      (可公开)         (可上架)       (护城河)
```

### 10.2 M0：技术可行性验证（2-3 周）

**目标**：用最小成本证明"这条链路能跑通"，并摸清平台限制的真实边界。

| 任务 | 交付物 | 验收标准 |
|------|--------|----------|
| 无障碍服务读取界面树 | Demo App | 能打印出微信首页所有可点击元素及文本 |
| MediaProjection 截图 | Demo App | 能截取当前屏幕并保存，Android 14+ 弹窗流程跑通 |
| 点击/输入执行 | Demo App | 能自动完成"打开微信 → 搜索框输入文字 → 点击搜索结果" |
| Shizuku 通道验证 | Demo App | 通过 Shizuku 执行 `input tap` 与 `am start` 成功 |
| 单 Provider 接入 | Demo App | 打通 OpenAI 兼容协议的流式对话 |
| 端侧模型 PoC | 报告 | 在目标机型上跑通一个 2B 级多模态模型的推理，记录延迟与内存 |
| **Android 17 APM 实测** | 报告 | 在有条件的设备上验证 APM 模式下的限制表现 |
| 敏感页面检测 PoC | Demo App | 能识别支付宝首页并拒绝截图 |

**M0 决策门（Go/No-Go）**：
- ✅ Go 条件：核心链路（读屏 → 决策 → 执行）端到端跑通，单步成功率 ≥ 80%
- ❌ No-Go 信号：Shizuku 与无障碍双通道均不可用，或端侧模型在目标机型上完全跑不动

**M0 必须输出的三份报告**：
1. 《平台能力边界报告》—— 逐项列出可用/不可用能力及系统版本差异
2. 《目标机型适配报告》—— 小米/华为/OPPO/vivo/三星/Pixel 的权限表现
3. 《技术选型确认书》—— 确认或修正本文档的技术选型

### 10.3 M1：MVP（6-8 周）

**目标**：可用、可内测、能收集真实反馈。

| 模块 | 交付内容 |
|------|----------|
| Key 管理 | 手动导入 + 剪贴板识别；3 家 Provider（OpenAI 兼容 / DeepSeek / OpenRouter）；加密存储；有效性校验；用量统计基础版 |
| 对话 | 文本对话、流式输出、多轮上下文、自定义 System Prompt、提示词模板 |
| 屏幕感知 | 无障碍树读取 + MediaProjection 截图 + ML Kit OCR；ScreenSnapshot 统一结构 |
| 屏幕问答 | 悬浮球唤起 → 截图 → 多模态模型 → 结果卡片 |
| 单步执行 | 点击元素、输入文本、滑动、返回/Home、打开 App（含 Deep Link） |
| 安全 | 敏感页面黑名单拦截；执行过程可视化；一键中止 |
| 引导 | 权限申请向导；诊断工具；首次使用教程 |

**M1 验收**：内部 10 人试用 2 周，单步成功率 ≥ 95%，无 Key 泄露事故，无敏感页面违规。

### 10.4 M2：任务引擎（8-10 周）—— 核心价值阶段

| 模块 | 交付内容 |
|------|----------|
| Agent 编排 | Orchestrator + Grounder + Verifier 双脑架构；任务状态机；失败重规划（最多 3 次） |
| 多步任务 | 支持 5-10 步的跨 App 任务；最大步数保护；卡死检测 |
| 记忆系统 | 短期轨迹 + 中期会话摘要 + 长期偏好；UI 图谱缓存（命中率目标 ≥ 40%） |
| 模型路由 | 分级路由 + 预算熔断 + 多 Key 轮询 + 限流退避 |
| 任务模板 | 10 个高频场景（导航、发消息、点外卖到结算页、查快递、定闹钟、调设置等） |
| 扩展 Provider | Claude、Gemini、通义、火山方舟、智谱、本地 Ollama |
| 端侧模型 | 接入 LiteRT-LM/MediaPipe，提供"本地模式" |
| 行为拟人化 | 随机延迟、非直线滑动、频率限制 |

**M2 验收**：5 步内任务端到端成功率 ≥ 75%；UI 图谱缓存命中率 ≥ 40%；单任务平均 token 成本较 M1 下降 50%。

### 10.5 M3：稳定性与体验（6 周）

| 任务 | 说明 |
|------|------|
| 成功率专项优化 | 针对 Top 20 高频场景做定向优化 |
| 失败恢复 | 全链路降级策略落地（见 5.5） |
| 机型适配 | 覆盖 Top 20 机型，输出适配矩阵 |
| 性能优化 | 冷启动、内存占用、耗电优化 |
| 语音能力 | 语音输入 + TTS 播报 |
| 多入口 | 助手位注册、Widget、快捷方式、截图分享入口 |
| 数据看板 | 本地用量统计可视化（图表） |

**M3 验收**：连续使用 7 天无崩溃；任务成功率 ≥ 85%；后台耗电 ≤ 3%/天。

### 10.6 M4：合规与发布（4 周）

| 任务 | 说明 |
|------|------|
| 隐私合规 | 隐私政策、单独同意弹窗、Data safety 表单、出设备数据清单 |
| 安全审计 | 第三方安全审计（重点：Key 存储、日志脱敏、截图处理） |
| 法律意见 | 就"工具定位"取得法律意见书 |
| 应用备案 | 工信部 App 备案 |
| 商店上架 | 国内主流商店；Google Play 作为备选（评估无障碍权限审核风险） |
| 官网与 APK 分发 | 自有分发渠道建设 |
| 帮助中心 | 常见问题、机型适配指南、故障排查 |

### 10.7 M5：生态扩展（持续）

- 插件 / MCP 工具接入（让用户自己写工具）
- 定时任务与条件触发
- 长期记忆 + 个人知识库 RAG
- 跨设备（平板、车机）
- Provider 市场（社区贡献适配）
- 团队 / 家庭共享 Key 池

### 10.8 里程碑甘特概览

```
周次   0    4    8    12   16   20   24   28   32
M0     ████
M1         ████████████
M2                      ████████████████
M3                                       ████████
M4                                               ████
M5                                                   ██████►

关键节点：
  第 3 周  ── M0 决策门（Go / No-Go）
  第 11 周 ── MVP 内测
  第 21 周 ── 任务引擎完成，可公开测试
  第 27 周 ── 稳定版
  第 31 周 ── 正式发布
```

---

## 11. 资源与成本估算

### 11.1 团队配置（建议最小可行团队）

| 角色 | 人数 | 职责 | 关键要求 |
|------|:---:|------|----------|
| 技术负责人 / 架构师 | 1 | 架构设计、技术决策、M0 验证 | 有 Android 系统层开发经验 |
| Android 高级工程师 | 2 | 无障碍/截图/悬浮窗/Shizuku 等系统能力 | **必须有 AccessibilityService 实战经验** |
| Android 工程师 | 1 | 业务 UI、Key 管理、对话模块 | Compose 熟练 |
| AI/算法工程师 | 1 | Agent 编排、提示词工程、端侧模型集成 | 有 GUI Agent 或 RAG 经验 |
| 产品经理 | 1 | 需求、场景定义、验收 | 懂技术边界 |
| UI/UX 设计师 | 0.5 | 界面、交互、动效 | 移动端效率工具经验 |
| 测试工程师 | 1 | 真机回归、机型适配、自动化测试 | 有多机型测试经验 |
| 安全/合规顾问 | 0.2（外部） | 安全审计、法律意见 | 数据安全 + 互联网法务 |

**合计**：约 7.7 人。**最小可行配置 5 人**（去掉 1 名 Android、测试与设计兼职）。

### 11.2 成本估算

| 项目 | MVP 阶段（约 11 周） | 到发布（约 31 周） |
|------|---------------------|-------------------|
| 人力成本 | 约 7.7 人 × 2.5 月 | 约 7.7 人 × 7 月 |
| 测试机采购 | 6-10 台主流机型 | 20 台覆盖矩阵 |
| 模型 API 成本（开发测试） | 自有 Key，约 ¥2,000-5,000/月 | 同左 |
| 服务器 | **¥0**（无自有后端架构） | 官网/静态资源约 ¥200/月 |
| 安全审计 | — | 约 ¥50,000-150,000 |
| 法律意见书 | — | 约 ¥20,000-50,000 |
| App 备案 | 免费 | 免费 |
| 应用商店开发者账号 | 约 ¥1,000 | 同左 |

**关键成本优势**：无自有后端 = 无推理成本 = 无服务器扩容压力。这是 BYOK 模式最大的商业优势，**必须在架构上守住**。

### 11.3 成本红线（不可突破）

1. ❌ 不建设自有模型代理服务器（合规 + 成本双重考虑）
2. ❌ 不代付用户 token（除非是明确设计的免费试用额度，且额度硬上限）
3. ❌ 不存储用户屏幕内容到任何服务端
4. ✅ 崩溃上报必须脱敏，且提供开关

---

## 12. 附录

### 12.1 数据模型（核心表）

```sql
-- API Key 表（密文存储）
CREATE TABLE api_key (
    id              TEXT PRIMARY KEY,
    provider_id     TEXT NOT NULL,          -- "openai" | "deepseek" | ...
    display_name    TEXT NOT NULL,
    encrypted_key   BLOB NOT NULL,          -- AES-GCM 密文
    iv              BLOB NOT NULL,
    base_url        TEXT,
    custom_headers  TEXT,                   -- JSON
    status          TEXT NOT NULL,          -- VALID | INVALID | UNKNOWN | RATE_LIMITED | NO_BALANCE
    last_checked_at INTEGER,
    created_at      INTEGER NOT NULL,
    enabled         INTEGER DEFAULT 1,
    group_name      TEXT,
    sort_order      INTEGER
);

-- 可用模型缓存
CREATE TABLE model_info (
    provider_id     TEXT NOT NULL,
    model_id        TEXT NOT NULL,
    display_name    TEXT,
    capabilities    TEXT,                   -- JSON: ["VISION","TOOL_CALL",...]
    context_window  INTEGER,
    input_price     REAL,                   -- 每 1M token 价格
    output_price    REAL,
    updated_at      INTEGER,
    PRIMARY KEY (provider_id, model_id)
);

-- 会话
CREATE TABLE conversation (
    id              TEXT PRIMARY KEY,
    title           TEXT,
    model_selection TEXT,                   -- JSON
    created_at      INTEGER,
    updated_at      INTEGER,
    archived        INTEGER DEFAULT 0
);

-- 消息
CREATE TABLE message (
    id              TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    role            TEXT NOT NULL,          -- user | assistant | system | tool
    content         TEXT,
    attachments     TEXT,                   -- JSON: 图片引用等
    token_usage     TEXT,                   -- JSON
    created_at      INTEGER,
    FOREIGN KEY (conversation_id) REFERENCES conversation(id) ON DELETE CASCADE
);

-- 任务执行记录
CREATE TABLE task_run (
    id              TEXT PRIMARY KEY,
    conversation_id TEXT,
    user_input      TEXT NOT NULL,
    plan            TEXT,                   -- JSON: 规划出的步骤
    status          TEXT NOT NULL,          -- RUNNING | SUCCESS | FAILED | ABORTED | NEED_USER
    total_steps     INTEGER,
    completed_steps INTEGER,
    total_tokens    INTEGER,
    estimated_cost  REAL,
    started_at      INTEGER,
    finished_at     INTEGER,
    abort_reason    TEXT
);

-- 任务步骤
CREATE TABLE task_step (
    id              TEXT PRIMARY KEY,
    task_run_id     TEXT NOT NULL,
    step_index      INTEGER NOT NULL,
    action_type     TEXT NOT NULL,
    target_desc     TEXT,                   -- 模型输出的自然语言目标
    resolved_ref    TEXT,                   -- JSON: 解析后的节点/坐标
    executor        TEXT,                   -- ACCESSIBILITY | SHIZUKU | IME | MANUAL
    status          TEXT NOT NULL,
    screenshot_ref  TEXT,                   -- 仅记录是否存在，不存图
    error_message   TEXT,
    duration_ms     INTEGER,
    created_at      INTEGER,
    FOREIGN KEY (task_run_id) REFERENCES task_run(id) ON DELETE CASCADE
);

-- UI 图谱缓存（性能关键）
CREATE TABLE ui_graph_cache (
    id              TEXT PRIMARY KEY,
    package_name    TEXT NOT NULL,
    page_fingerprint TEXT NOT NULL,         -- 页面特征哈希
    element_key     TEXT NOT NULL,          -- 目标描述（如"搜索框"）
    selector        TEXT NOT NULL,          -- JSON: 节点选择器
    hit_count       INTEGER DEFAULT 0,
    success_count   INTEGER DEFAULT 0,
    last_used_at    INTEGER,
    UNIQUE(package_name, page_fingerprint, element_key)
);

-- 用户偏好记忆
CREATE TABLE user_memory (
    id              TEXT PRIMARY KEY,
    category        TEXT NOT NULL,          -- PREFERENCE | APP_HABIT | CORRECTION
    key             TEXT NOT NULL,
    value           TEXT NOT NULL,
    confidence      REAL DEFAULT 1.0,
    created_at      INTEGER,
    updated_at      INTEGER
);

-- 用量统计
CREATE TABLE usage_record (
    id              TEXT PRIMARY KEY,
    provider_id     TEXT NOT NULL,
    model_id        TEXT NOT NULL,
    input_tokens    INTEGER,
    output_tokens   INTEGER,
    estimated_cost  REAL,
    scene           TEXT,                   -- CHAT | GROUNDING | PLANNING | VISION
    created_at      INTEGER
);

-- 敏感操作审计日志
CREATE TABLE audit_log (
    id              TEXT PRIMARY KEY,
    event_type      TEXT NOT NULL,          -- SENSITIVE_BLOCKED | PERMISSION_GRANTED | TASK_ABORTED
    package_name    TEXT,
    description     TEXT,                   -- 不含敏感内容
    created_at      INTEGER
);
```

### 12.2 动作协议（LLM 输出的结构化格式）

```json
{
  "thought": "用户想在微信里给张三发消息。当前在微信首页，需要先点搜索框。",
  "action": {
    "type": "CLICK",
    "target": {
      "description": "顶部的搜索输入框",
      "preferred_selector": "resource-id:com.tencent.mm:id/搜索框"
    },
    "fallback": {
      "type": "TAP_COORD",
      "x": 540,
      "y": 180
    }
  },
  "expectation": "进入搜索页面，出现输入框",
  "need_user_confirm": false
}
```

**支持的动作类型**：

| 类型 | 参数 | 说明 |
|------|------|------|
| `CLICK` | target | 点击元素 |
| `TAP_COORD` | x, y | 坐标点击（兜底） |
| `LONG_PRESS` | target, duration | 长按 |
| `INPUT_TEXT` | text, target | 输入文本 |
| `CLEAR_TEXT` | target | 清空输入框 |
| `SCROLL` | direction, distance | 滚动 |
| `SWIPE` | from, to, duration | 滑动 |
| `BACK` / `HOME` / `RECENTS` | — | 全局操作 |
| `OPEN_APP` | package, deep_link | 打开 App / 深层链接 |
| `WAIT` | ms | 等待加载 |
| `ASK_USER` | question | 询问用户 |
| `FINISH` | summary | 任务完成 |
| `ABORT` | reason | 中止任务 |

### 12.3 提示词设计要点

**Orchestrator System Prompt 关键要素**：

1. **角色**：你是一个手机操作助手，通过观察屏幕和执行动作来完成用户任务
2. **能力边界**：明确告知可用动作类型，禁止发明动作
3. **安全约束**（硬编码在 system prompt 中，不可被用户指令覆盖）：
   - 绝对不执行支付、转账、密码输入相关操作
   - 遇到敏感页面必须返回 `ABORT` 或 `ASK_USER`
   - 不执行任何游戏相关操作
   - 不执行批量、高频、重复性操作
4. **输出格式**：严格的 JSON Schema，附 few-shot 示例
5. **失败处理**：连续失败 2 次必须改变策略；3 次则 `ASK_USER`

**Grounder 提示词要点**：
- 输入：元素列表（文本化，含序号、类型、文本、坐标）
- 输出：**序号**（而非坐标）—— 大幅降低出错率，且 token 更省
- 示例：`{"element_index": 7, "confidence": 0.92}`

### 12.4 开源方案参考

| 项目 | 用途 | 可借鉴点 |
|------|------|----------|
| **AgentCPM-GUI**（清华/人大/OpenBMB） | 中文移动 App GUI Agent | 中文 App 适配、紧凑动作空间、端侧部署 |
| **Mobile-Agent-v3/v3.5**（阿里通义） | 跨平台 GUI Agent | 端云协同路由、小尺寸端侧模型 |
| **UI-TARS / UI-TARS-2**（字节+清华） | 纯视觉端到端 Agent | 数据飞轮、多轮 RL |
| **MagicGUI**（荣耀+复旦） | 移动端 GUI Agent | 完整移动动作空间（含 Drag/Wait/Takeover） |
| **MAI-UI**（阿里） | 真实世界部署 | 端云协同 + MCP 工具一体化 |
| **OmniParser**（微软） | 屏幕截图结构化 | 屏幕元素解析为结构化数据 |
| **Shizuku** | Android 提权 | 无需 root 的系统 API 调用 |
| **Mobile-Agent / AppAgent** | 早期移动 Agent | 感知-规划-执行基础架构 |

### 12.5 待确认决策点（需评审拍板）

| # | 决策点 | 选项 | 建议 |
|---|--------|------|------|
| D1 | 首发平台 | Android only / Android+iOS 降级版 | **Android only** |
| D2 | 最低支持版本 | API 29 / API 31 / API 33 | **API 31（Android 12）**，平衡覆盖率与能力 |
| D3 | 是否建设自有后端 | 有 / 无 | **无**（合规 + 成本双赢） |
| D4 | 是否做 Google Play | 做 / 不做 / 后置 | **后置**，先国内渠道 + 官网 APK |
| D5 | Shizuku 通道优先级 | M0 / M1 / M2 | **M0 验证，M1 实现**（抗政策风险） |
| D6 | 端侧模型优先级 | M2 / M3 | **M2**（隐私卖点 + 省钱） |
| D7 | 商业模式 | 免费 / 买断 / 订阅 | **免费 + 增值**（高级任务模板、插件市场） |
| D8 | 是否做"免费试用额度" | 是 / 否 | **是但硬上限**（降低首次转化门槛，用自有 Key 池） |
| D9 | 产品名称 | PocketAgent / 其他 | 待定 |
| D10 | 敏感页面规则是否允许用户配置 | 允许 / 不允许 | **不允许绕过，只允许加严** |

### 12.6 下一步行动（本周内）

| # | 行动 | 负责 | 产出 |
|---|------|------|------|
| 1 | 评审本方案，确认 D1-D10 决策点 | 全员 | 评审纪要 |
| 2 | 确定目标机型清单，采购测试机 | 技术负责人 | 机型清单 |
| 3 | 搭建项目骨架（模块划分 + CI） | Android 高级工程师 | 可编译的空项目 |
| 4 | 启动 M0：无障碍读屏 + 截图 PoC | Android 高级工程师 | Demo APK |
| 5 | 启动 M0：Shizuku 通道 PoC | Android 高级工程师 | Demo APK |
| 6 | 启动 M0：端侧模型推理 PoC | AI 工程师 | 性能测试报告 |
| 7 | 就"工具定位"咨询法律意见 | 产品经理 | 法律意见书初稿 |
| 8 | 输出《平台能力边界报告》 | 技术负责人 | 报告 |

---

## 附：本方案的核心判断（一句话总结）

> 这个项目**技术可行，但产品上限被平台政策锁死**。真正的护城河不在"能自动点屏幕"（这条赛道政策风险极高且随时可能被封），而在 **BYOK 体系的完善度、执行过程的透明可控、以及对隐私与生态规则的尊重**。把"手动引导模式"当作正式产品形态来设计，把"多通道执行"当作架构第一原则，这个项目就有活路。反之，如果一上来就赌无障碍权限、赌长任务全自动、赌用户不介意黑盒操作，那就是给自己挖坑。

---

*文档结束。待评审确认后进入 M0 阶段。*
