# BYOK 模型接入与 dsh 代理设计

> 版本 v1.0 ｜ 2026-09-21 ｜ 状态：**设计稿（待评审）**
>
> 前置文档：`docs/豆包手机智能体考察与dsh移植可行性研讨-v1.0.md`（以下简称"研讨文档"）
>
> 本文所有"实测"结论均来自本机对 `F:\dsh-program` 的实际检查，命令与输出见附录 A。
> 附录 B 列出本文对研讨文档的**订正**。

---

## 0. 结论速览

**三条必须最先知道的结论：**

**① 路线 C 引入了一个原方案完全没有覆盖的 Key 暴露面 —— 这是重大漏洞。**

项目原则第 7 条写着"**Key 永不落明文**"。但路线 C 让 dsh 跑在 Node 进程里、
由 dsh 自己调模型 —— 而 dsh 要调模型就必须拿到 Key。
**Key 于是必须从 Kotlin 进程跨到 Node 进程**，而环境变量、配置文件、命令行参数、
stdin 四种传法**全部存在明文暴露点**。研讨文档对此只字未提。

**② 解法：LLM 代理（Local Loopback Gateway）—— 让 Key 永远不离开 Kotlin 进程。**

实测发现 `dsh-llm-deepseek` 与 `dsh-llm-pi-ai` **都支持任意 `baseURL` 覆盖**，
且请求就是标准的 `POST {baseURL}/chat/completions`。
所以：**把 dsh 的 baseURL 指向 `http://127.0.0.1:<port>/v1`**，
由 Android 侧代理解密真 Key 并转发。**dsh 从头到尾不知道真 Key 长什么样。**
这不是 hack，是 dsh 官方支持的配置方式。

**③ 意外收获：代理方案让 `pi-ai` 及其约 57 MB 依赖变成可裁剪项。**

实测 dsh 只有 **2 个** LLM provider 包（`dsh-llm-deepseek` + `dsh-llm-pi-ai`），
其中 `dsh-llm-pi-ai` 依赖第三方 SDK `@earendil-works/pi-ai`，
后者又拖进 `@mistralai`(25MB) / `@google`(14MB) / `openai`(12MB) / `@anthropic-ai`(5.8MB)。
**既然协议转换已经由代理层承担，dsh 侧只需要"会说 openai-completions"的通道**，
pi-ai 及其 57MB 依赖可以整体裁掉。

---

# 第一部分 · 问题：路线 C 与「Key 永不落明文」的冲突

## 1.1 原则回顾

项目已确立的架构原则第 7 条：

> **Key 永不落明文**：Keystore + AES-GCM + SQLCipher；不进日志、不进崩溃上报、
> 不进备份、不进 SharedPreferences。

以及第 8 条（插件边界原则）：

> 所有插件调用必须经 `PluginHost` 能力代理，插件**永远拿不到 API Key 明文**。

**注意这两条原则的共同前提：Key 只在 Kotlin 进程内存在，且只在构造请求头的那一瞬间解密。**

## 1.2 路线 C 打破了这个前提

路线 C 的架构是：

```
L2  智能体内核    dsh（原样复用，零改动）
L3  能力桥        MCP over localhost
L4  能力端        Android 原生（复用 PocketAgent 既有资产）
```

问题出在 L2：**dsh 自己要调 LLM**（它的 `dsh-llm-*` 插件就是干这个的）。
dsh 要调 LLM，就必须持有 API Key。

于是 Key 必须跨越 **Kotlin 进程 → Node 进程** 这条边界。四种传法：

| 传法 | 机制 | 暴露面 | 判定 |
|---|---|---|---|
| **环境变量** | `DEEPSEEK_API_KEY=sk-xxx` 启动 node | Linux 上 `/proc/<pid>/environ` 可读；崩溃转储、子进程继承、`ps eww` 都可能带出 | ❌ |
| **配置文件** | 写进 profile 的 `cordis.patch.yml` | **明文落盘**，直接违反"不进 SharedPreferences/文件"红线 | ❌ 最严重 |
| **命令行参数** | `node bin.js --api-key=sk-xxx` | `/proc/<pid>/cmdline` **任何进程可读，无需 root** | ❌ 最严重 |
| **stdin 一次性传入** | 启动时写入，Node 读入内存后不留盘 | 相对最好，但 **Key 仍在 Node 进程内存中常驻**，且 Node 的 GC 会让明文在堆上存活不可控时长 | ⚠️ 勉强 |

**四种都不合格。** 前三种是明确的红线违反，第四种虽然避开了落盘，
但"Key 在 Node 进程内存里"这件事本身就与"Key 只在 Kotlin 进程内"的前提冲突。

更要命的是：**Node 进程里跑着 dsh 的插件系统**。研讨文档 §2.1 已确认
"profile 的本质是一个 pnpm 项目"，用户可以装第三方插件。
**一旦 Key 进了 Node 进程，插件就有理论上的途径摸到它** —— 而这正是第 8 条原则
（"插件永远拿不到 API Key 明文"）要防的事。

> 📌 **结论**：只要 dsh 自己持有 Key，路线 C 就与项目两条核心安全原则同时冲突。
> **这不是可以"注意一下"的小问题，是架构级的缺陷。**

## 1.3 我们需要的性质

一个合格的方案必须同时满足：

1. **Key 永不进入 Node 进程**（内存、环境、参数、文件都不行）
2. **dsh 的 agent 能力不受损**（它得能正常调模型、用 tool call、流式输出）
3. **dsh 侧零改动**（否则无法跟随上游升级，违反"原样复用"原则）
4. **预算熔断与用量统计不能被绕过**（研讨文档 D-G / §4.4 的要求）
5. **安全护栏在 Kotlin 侧**（与"插件绕不过 SafetyGuard"同构）

第 1 条与第 2 条看起来是矛盾的 —— 不给 Key，dsh 怎么调模型？

**答案：不告诉它真 Key，只告诉它"往哪儿发"。**

---

# 第二部分 · dsh 的模型接入能力（本机实测）

## 2.1 订正：dsh 只有两个 provider 包

**研讨文档 §2.2 写的"`dsh-llm` + deepseek / openai / anthropic / google / mistral / pi-ai"是错的。**

实测 `ls F:/dsh-program/node_modules/@deepseek-ai/ | grep -i llm`：

```
dsh-llm
dsh-llm-deepseek
dsh-llm-pi-ai
dsh-llm-retry
dsh-session-title-first-prompt-llm
dsh-session-title-llm
```

而 `grep -i -E "openai|anthropic|gemini|google|mistral|claude"` **零命中**。

**真实构成：**

| 包 | 角色 |
|---|---|
| `dsh-llm` | LLM 运行时抽象（adapter registry + 可拦截的流式调用 API） |
| `dsh-llm-deepseek` | DeepSeek 专用适配器 |
| `dsh-llm-pi-ai` | **通用适配器**，基于第三方 SDK `@earendil-works/pi-ai` |
| `dsh-llm-retry` | 重试策略 |

`@mistralai` / `@google` / `openai` / `@anthropic-ai` 这些包**确实存在**（研讨文档的体积账没写错），
但它们是 **`@earendil-works/pi-ai` 的依赖**，不是 dsh 自己的 provider 包。

## 2.2 `dsh-llm-deepseek`：baseURL 可覆盖

`dsh-llm-deepseek/lib/types/index.d.ts`：

```typescript
export interface Config {
    /** Credential reference (environment-variable name) resolved per request;
     *  defaults to `DEEPSEEK_API_KEY`. */
    apiKeyEnv?: string;
    /** Endpoint base; falls back to $DEEPSEEK_BASE_URL from a trusted environment
     *  layer, then the public API. */
    baseURL?: string;
    thinking?: 'enabled' | 'disabled';
    reasoningEffort?: 'off' | 'low' | 'high' | 'max';
    ...
}
```

实现（`lib/index.js:589`）：

```javascript
response = await fetch(`${connection.baseURL}/chat/completions`, {
    method: "POST",
    headers,
    body: payload,
    signal
});
```

**就是标准 OpenAI Chat Completions 协议，baseURL 是配置项。** ✅

## 2.3 `dsh-llm-pi-ai`：通用适配器，三协议 + 任意端点

这是**最关键的发现**。它的模块文档原文：

> Generic pi-ai-backed LLM adapter plugin. One plugin instance owns a dict of provider
> routes; a route naming an installed pi-ai provider inherits that provider's endpoint,
> protocol, and model catalog as defaults, and **a route pi-ai does not ship is declared
> outright**.

配置示例（原文摘录）：

```yaml
providers:
  # Catalog route: everything but the credential comes from pi-ai.
  openai:
    apiKeyEnv: OPENAI_API_KEY
    retryPolicy: { mode: normal, maxRetries: 2 }

  # Hand-declared route: pi-ai ships nothing under this key.
  acme-gateway:
    displayName: Acme Gateway
    apiKeyEnv: ACME_GATEWAY_API_KEY
    api: openai-completions
    baseURL: https://gateway.acme.example/v1
    compat:
      thinkingFormat: deepseek
    models:
      - id: acme-large
        name: Acme Large
        contextWindow: 65536
        maxTokens: 4096
```

**支持的协议（`lib/index.js:1223`）：**

```javascript
const PROTOCOLS = {
    "openai-completions": openAICompletionsApi,
    "openai-responses":    openAIResponsesApi,
    "anthropic-messages":  anthropicMessagesApi
};
```

**配置字段（`lib/index.js` 的 zod schema）：**

```javascript
api:     z.union(supportedProtocols()),
baseURL: z.string(),
models:  z.array(modelProfile),
headers: z.dict(z.string()),      // ★ 支持自定义请求头
compat:  compatProfile,
reasoning: z.union(THINKING_LEVELS),
...
```

> 📌 **`baseURL` + `headers` + `api: openai-completions` 三个字段加起来，
> 足以让 dsh 把请求发到任意地址、带上任意头。这就是代理方案的立足点。**

## 2.4 凭据机制：`apiKeyEnv` 是"环境变量名"，不是 Key 本身

```typescript
/** Credential reference (environment-variable name) resolved per request;
 *  defaults to `DEEPSEEK_API_KEY`. */
apiKeyEnv?: string;
```

注意它是**引用**（变量名），不是 Key 值。dsh 文档里还提到一个
"optional credential seam"（可选凭据接缝），说明凭据解析是可插拔的。

`dsh-llm/lib/types/api-key.d.ts` 里另有一句关键说明：

> Absence is a configuration state this function never sees — a profile naming
> **no credential** authenticates through the provider's own ambient discovery or OAuth —
> so callers decide whether a value was supplied before asking.

**即：profile 可以完全不给凭据。** 但 `dsh-llm-pi-ai` 的实现注释又补了一句：

> pi-ai's OpenAI-compatible implementation, for one, **still insists on a key or an
> `Authorization` header of its own**.

**结论：OpenAI 兼容协议这条路上，dsh 会坚持要一个 Key（或 Authorization 头）。**
这正好给了我们一个天然的位置 —— **放本地随机 token**，顺便成为防蹭用的鉴权。

## 2.5 配置语法（实测）

`D:\dsh-home\profiles\web\cordis.patch.yml` 里的真实写法：

```yaml
- id: llm-deepseek
  name: '@deepseek-ai/dsh-llm-deepseek'
  config:
    maxToken: 16384

- id: dsh-mcp-blender
  name: '@deepseek-ai/dsh-mcp-client'
  config:
    serverName: blender
    transport: stdio
    command: F:/Apps/blender-mcp-server/Scripts/blender-mcp.exe
    args: []
    toolCallTimeoutMs: 120000
    failOnStartupError: false
    reconnect:
      enabled: true
      initialDelayMs: 5000
```

**语法：`- id: <loader entry id>` + `name: <包名>` + `config: {...}`，
是 profile 的补丁层（`cordis.patch.yml`），按 id 定位已有条目并覆盖配置。**

## 2.6 附带确认：`dsh-mcp-client` 支持 streamable-http

这条对 L3 能力桥至关重要。`dsh-mcp-client/lib/types/index.d.ts`：

```typescript
export interface StreamableHttpConfig {
    transport: 'streamable-http';
    serverName: string;                        // 工具名 = mcp__<serverName>__<rawName>
    url: string;                               // ★ MCP 端点
    headers: Record<string, string>;           // ★ 可放本地 token
    toolCallTimeoutMs: number;
    failOnStartupError: boolean;
    reconnect?: ReconnectConfig;
}
```

**Android 侧可以直接起一个 HTTP MCP Server，dsh 通过 `streamable-http` 连过去。**

这解决了研讨文档没考虑到的一个实现障碍：`stdio` transport 需要 dsh 启动一个
**可执行文件**（如上面的 `blender-mcp.exe`），而 Android 上的 MCP Server 是
Kotlin 代码，**根本不是一个可执行文件**。没有 `streamable-http`，L3 就得靠
"再塞一个 Node 脚本做转发"的丑陋绕路。

---

# 第三部分 · 方案：Local Loopback Gateway

## 3.1 架构

```
┌─────────────────────────────────────────────────────────────────┐
│  L2  dsh (Node 进程)                                             │
│                                                                  │
│   providers:                                                     │
│     pocketagent:                                                 │
│       api: openai-completions                                    │
│       baseURL: http://127.0.0.1:<gwPort>/v1   ← 指向本地代理      │
│       apiKeyEnv: POCKETAGENT_LOCAL_TOKEN      ← 假 token，不是真 Key│
│                                                                  │
│   ★ dsh 进程内：没有任何真实 API Key                              │
└──────────────────────────┬──────────────────────────────────────┘
                           │ HTTP (loopback only)
                           │ Authorization: Bearer <本地随机 token>
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  L4  LlmGateway (Kotlin 进程内)                                   │
│                                                                  │
│   ① 校验本地 token（不是真 Key，仅防同机其他 App 蹭用）            │
│   ② 路由决策：选 Provider / 选模型（对 dsh 透明）                  │
│   ③ 预算熔断：超限直接拒，dsh 绕不过                               │
│   ④ 从 Keystore 解密真 Key（CryptoManager.withDecryptedKey）      │
│   ⑤ 转发到真实 Provider（复用 OpenAiCompatProvider）              │
│   ⑥ 流式回传 + 记录用量                                            │
│                                                                  │
│   ★ 真 Key 只在 ⑤ 构造请求头的那一行出现，用完即清零               │
└──────────────────────────┬──────────────────────────────────────┘
                           │ HTTPS
                           ▼
        真实 Provider（DeepSeek / Anthropic / Gemini / Ollama / ...）
```

## 3.2 一次请求的完整时序

```
dsh 发起 POST http://127.0.0.1:PORT/v1/chat/completions
  │   Authorization: Bearer <本地随机 token>
  │   body: {"model":"auto","messages":[...],"stream":true}
  ▼
LlmGateway 收到
  ├─ ① 校验 token → 不符则 401（且不泄露任何信息）
  ├─ ② 查路由表：model="auto" + 当前任务特征 → (providerId="deepseek", model="deepseek-chat")
  ├─ ③ 查预算：今日已用 $X，上限 $Y → 超限则 429 + 明确文案
  ├─ ④ crypto.withDecryptedKey(keyBlob) { plain ->
  │        ProviderCredential(plain) 构造
  │        provider.chat(request, credential)  ← 复用现有实现
  │     }   // 作用域结束，plain 自动 fill(0)
  ├─ ⑤ 把 Provider 的 Flow<ChatChunk> 转成 SSE 写回响应体
  └─ ⑥ 累计 token 与费用（本地库）
  ▼
dsh 收到标准 OpenAI SSE 流，正常工作
```

## 3.3 为什么这是"官方支持的用法"而非 hack

三条依据：

1. **`baseURL` 是 `dsh-llm-deepseek` 的显式配置项**（`baseURL?: string`），
   文档原话是 "Endpoint base"。
2. **`dsh-llm-pi-ai` 的设计目标就是接任意网关** —— 它的文档里那个
   `acme-gateway` 示例，和我们做的事**结构完全一致**（自定义 baseURL + 自定义 api 协议 + 自定义 models）。
3. **协议是标准 OpenAI Chat Completions** —— dsh 的实现就是 `fetch(baseURL + "/chat/completions")`。

**换句话说：dsh 本来就设计成可以指向自建网关。我们只是那个网关而已。**

## 3.4 收益清单

| # | 收益 | 说明 |
|---|---|---|
| 1 | **Key 永不进 Node 进程** | 内存、环境变量、命令行、文件，四条路径全部封死 |
| 2 | **dsh 零改动** | 纯配置，跟随上游升级无障碍 |
| 3 | **插件拿不到 Key** | 与项目第 8 条原则同构 —— 插件运行在 Node 里，而 Node 里没有真 Key |
| 4 | **预算熔断不可绕过** | 熔断在 Kotlin 侧，dsh 与插件都碰不到 |
| 5 | **模型路由对 dsh 透明** | 用户在 PocketAgent UI 换模型，dsh 只看到"pocketagent"一个 provider，**无需重启** |
| 6 | **11 家 Provider profile 全复用** | 已有的 `OpenAiCompatProvider` + `ProviderProfiles` 就是出口 |
| 7 | **可裁掉 pi-ai 及其 ~57MB 依赖** | 协议转换由代理层承担后，dsh 侧只需一个 openai-completions 通道 |
| 8 | **Key 校验/健康检测/用量统计天然落点** | 全部在网关层，是唯一的流量必经之处 |

> 📌 第 5 条值得单独强调：**如果不用代理，用户换模型就必须改 dsh 配置并重启 Node 进程。**
> 用了代理，换模型只是 Kotlin 侧改一个变量。这是**产品体验层面的实质差异**，
> 不只是安全考量。

---

# 第四部分 · LlmGateway 详细设计

## 4.1 端点契约

网关需要实现 **OpenAI Chat Completions 兼容的服务端**。最小可用集：

| 方法 | 路径 | 用途 | 必须 |
|---|---|---|---|
| `POST` | `/v1/chat/completions` | 对话（含 `stream: true` 的 SSE） | ✅ |
| `GET` | `/v1/models` | 模型列表（dsh 的模型发现） | ✅ |
| `GET` | `/healthz` | 就绪探测（dsh 启动前自检） | 建议 |

**关键约束：**

- **只监听 `127.0.0.1`**，绝不 `0.0.0.0`。Android 上监听全网卡等于把用户的 Key 暴露给整个局域网。
- **`/v1/models` 返回什么？** 两种策略：
  - **A. 只返回一个虚拟模型**（如 `pocketagent-auto`）—— dsh 侧极简，路由完全由 Kotlin 决定
  - **B. 返回所有已配置 Provider 的真实模型名** —— 用户在 dsh 界面里也能选模型，但路由逻辑分散
  - **建议 A**，理由见 §4.5

## 4.2 组件清单

| 组件 | 层 | 职责 | 复用既有资产 |
|---|---|---|---|
| **LlmGatewayServer** | L4 | loopback HTTP 服务端，路由与生命周期 | 新增 |
| **GatewayTokenProvider** | L4 | 每次启动生成随机 token，只交给 Node 进程 | 新增 |
| **ChatCompletionsHandler** | L4 | 反序列化请求 → `ChatRequest` | 复用 `OpenAiCompatDtos` |
| **SseResponseWriter** | L4 | `Flow<ChatChunk>` → SSE 响应体 | **反向复用 `SseParser`** 的协议知识 |
| **ModelRouter** | L4 | 选 Provider / 选模型（研讨文档 §4.5 的路由表） | 新增（设计已有） |
| **BudgetGuard** | L4 | 预算熔断，超限拒绝 | 新增 |
| **UsageRecorder** | L4 | token 与费用累计，落本地库 | 复用 `core/database` |
| **ProviderRegistry** | L4 | Key ↔ Provider 绑定关系，解密与构造凭据 | 复用 `CryptoManager` + `LlmProvider` |

**注意 `SseResponseWriter` 与 `SseParser` 的关系**：现有 `SseParser` 是**客户端**解析
（读 SSE），网关需要的是**服务端**生成（写 SSE）。两者共享同一套协议知识
（`data:` 行、`[DONE]` 终止符、多行 data 拼接），实现上应放在一起以便对照。

## 4.3 流式转发实现要点

这是最容易出错的部分，列出已知坑：

| 坑 | 说明 |
|---|---|
| **背压** | OkHttp 的响应体是阻塞读，Kotlin 侧是 Flow。转发时若下游（dsh）读得慢，必须能传导背压，否则内存堆积 |
| **客户端断开** | dsh 或用户中止连接时，必须**取消上游请求**（`call.cancel()`），否则 Provider 那边还在烧 token 计费 |
| **`[DONE]` 恰好一次** | 现有 `OpenAiCompatProvider.chat` 的注释里记录过一个真实 bug：Done 可能发两次或零次。**网关必须把这条契约翻译成"恰好一个 `data: [DONE]`"** |
| **错误发生在流中** | 首字节发出后才发现上游 401，HTTP 状态码已经发出去了。只能改为在流里发一个 error 事件，**且不能带 Key** |
| **超时分层** | 连接超时 / 首 token 超时 / 流空闲超时是三个不同的东西，需要分别设置 |
| **非流式请求** | dsh 可能发 `stream: false`。网关必须支持，不能只做流式 |

## 4.4 安全加固（六条）

| # | 措施 | 防的是什么 |
|---|---|---|
| 1 | **只监听 `127.0.0.1`** | 局域网内其他设备直连 |
| 2 | **端口随机**（每次启动） | 端口扫描 |
| 3 | **本地随机 token**（每次启动，仅交给 Node 进程） | **同机其他 App** —— Android 上 localhost 没有进程隔离，任何 App 都能连 `127.0.0.1:<port>` |
| 4 | **token 用常量时间比较** | 时序侧信道（低风险，但成本几乎为零） |
| 5 | **响应头不带任何 Provider 信息** | 减少信息泄露；也让"dsh 不知道真实 Provider"这条性质更彻底 |
| 6 | **错误信息过滤** | 上游返回的错误体可能含 Key 片段或内部地址，转发前过一遍 `LogSanitizer` 同款规则 |

> ⚠️ **第 3 条是最容易被忽略的。** 开发者常以为"监听 localhost 就是安全的"，
> 但在 Android 上，**任何** App 都可以连接 `127.0.0.1` 上的任意端口。
> 没有 token 校验，一个恶意 App 就能把你的网关当免费 API 中转站用。

## 4.5 为什么建议 `/v1/models` 只返回一个虚拟模型

**策略 A（推荐）**：`/v1/models` 返回单个 `pocketagent-auto`。

理由：

1. **路由逻辑单点化** —— 全在 Kotlin 的 `ModelRouter` 里，不分散到 dsh 配置
2. **换模型无需重启 Node** —— 用户改设置，下一个请求就生效
3. **dsh 侧配置极简** —— `models` 数组只有一项，profile 可复现
4. **与"代理层做协议转换"的定位一致** —— dsh 不该知道背后是谁

**代价**：dsh 的界面上看不到真实模型名。但因为主界面是 PocketAgent 自己的 Compose UI，
模型选择在**我们的界面**里做，所以这个代价不存在。

**策略 B** 适用于另一种产品形态：想让 dsh 的 web UI 也能直接切模型。
这需要 `/v1/models` 返回所有 Provider 的所有模型，且 `chat/completions` 按 model 名反查路由。
**可作为 V2 的增强，不作为 MVP。**

---

# 第五部分 · 与现有资产的关系

## 5.1 `LlmProvider` 抽象层的角色变化（重要）

| | 原设想 | 代理方案下 |
|---|---|---|
| 调用方 | PocketAgent 的 agent 循环 | **LlmGateway** |
| 作用 | 直接调模型 | 网关的**出口** |
| 是否保留 | ✅ | ✅ **且更加核心** |

**`LlmProvider` 接口一个字都不用改。** 现有 `OpenAiCompatProvider`（11 家 profile、
SSE 解析、错误映射、token 估算、费用钳制）**整体复用**，只是调用方从"agent 循环"
变成"网关的转发逻辑"。

研讨文档里 `L1`/`L2` 的分层因此需要微调：

```
L2  dsh（Node）—— 不再持有 Key，LLM 请求指向本地网关
L3  能力桥 —— MCP（Android 能力 → dsh） + LLM 网关（dsh → Android 模型能力）★ 双向
L4  能力端 —— 感知 / 执行 / 安全 / 模型接入
```

> 📌 **L3 从"单向"变成"双向"**，这是本设计对研讨文档分层架构的修正。
> 原方案里 L3 只承担"Android 能力暴露给 dsh"一个方向；
> 现在它还要承担"dsh 的模型请求回到 Android"的反方向。

## 5.2 复用清单

| 既有资产 | 在代理方案中的用途 |
|---|---|
| `LlmProvider` 接口 | 网关出口的抽象 |
| `OpenAiCompatProvider` + 11 个 `ProviderProfile` | 实际转发 |
| `SseParser` | 协议知识复用（反向写服务端） |
| `ChatCompletionRequest` 等 DTO | 反序列化 dsh 的请求 |
| `CryptoManager.withDecryptedKey` | 解密真 Key 的**唯一**正确姿势 |
| `ProviderCredential`（ByteArray + clear） | 凭据传递 |
| `core/database` | 用量与费用落库 |
| `Cost` / `TokenUsage` | 统计口径 |

## 5.3 需要新增的模块

建议新增 `:gateway`（或放在 `:provider:gateway`）：

```
provider/gateway/
  ├── LlmGatewayServer.kt         loopback HTTP 服务端
  ├── GatewayTokenProvider.kt     随机 token 生成与分发
  ├── ChatCompletionsHandler.kt   请求 → ChatRequest
  ├── SseResponseWriter.kt        Flow<ChatChunk> → SSE
  ├── ModelRouter.kt              路由决策
  ├── BudgetGuard.kt              预算熔断
  └── UsageRecorder.kt            用量落库
```

**依赖约束**：`:provider:gateway` 依赖 `:provider:api` 与 `:core:crypto`，
**不依赖任何具体 Provider 实现**（通过注入 `List<LlmProvider>`）。

---

# 第六部分 · 用户可见的模型管理

## 6.1 首次引导（复用研讨文档 §4.6 的设计）

```
第 1 步  说明定位
         "本应用不提供 AI 模型。你用自己的 API Key 调用任意模型。"
         "Key 只存在你的手机上，加密存储，我们看不到。"

第 2 步  选择 Provider
         推荐 OpenRouter（一个 Key 通吃）或 DeepSeek（国内便宜）

第 3 步  粘贴 Key
         → 格式预校验 → 网络验证 → 分类反馈（有效/无效/余额不足/不可达）
         → 加密存储
```

## 6.2 模型选择（对 dsh 完全透明）

用户在 PocketAgent 界面里配置：

```
┌──────────────────────────────────────────┐
│  模型路由                                  │
│                                          │
│  默认模型    [ DeepSeek-chat        ▾ ]   │
│  复杂任务    [ Claude Sonnet 4.5    ▾ ]   │
│  视觉任务    [ Gemini 2.5 Flash     ▾ ]   │
│  隐私场景    [ 本地 Ollama          ▾ ]   │
│                                          │
│  路由偏好    ( ) 最省钱  (•) 均衡  ( ) 最强│
└──────────────────────────────────────────┘
```

**这些改动不触及 dsh 配置，不需要重启 Node 进程。** 下一个请求即生效。

## 6.3 成本控制

| 功能 | 实现位置 | 说明 |
|---|---|---|
| 实时消耗显示 | 网关 → UI | 每轮对话结束推送 |
| 预算上限 | **网关（强制）** | 超限直接 429，dsh 与插件都绕不过 |
| 用量统计 | 网关 + 本地库 | 按 Provider / 模型 / 日期 |
| 费用估算 | 复用 `estimateCost` | 已含负数钳制（见 `OpenAiCompatProvider` 注释） |

> ⚠️ **预算熔断必须在网关层**，不能交给 dsh。
> 理由与"SafetyGuard 必须在能力端"完全同构：**放在被约束方自己的进程里，约束就不成立。**

---

# 第七部分 · 待决策问题（接续研讨文档 D-A ~ D-J）

| ID | 问题 | 选项 | 建议 |
|---|---|---|---|
| **D-K** ★ | **是否采用 LLM 代理方案？** | 采用／不采用（接受 Key 进 Node 进程）／先做实验 | **采用**。这是唯一同时满足 §1.3 五条性质的方案 |
| **D-L** | **是否裁掉 `pi-ai` 及其 ~57MB 依赖？** | 裁掉（省体积）／保留（多协议冗余） | **裁掉**。协议转换已由代理层承担，dsh 侧只需 openai-completions |
| **D-M** | **代理层做不做协议转换？** | 做（dsh 只见 OpenAI，背后任意 Provider）／不做（dsh 自己选协议） | **做**。否则 Anthropic/Gemini 得在 dsh 侧配多份，且 Key 管理分散 |
| **D-N** | **本地 token 强度？** | 32 字节随机／短 token／不校验 | **32 字节随机**。成本为零，且防的是同机 App |
| **D-O** | **网关用哪个 HTTP 服务端？** | Ktor Server／NanoHTTPD／自建（OkHttp 无服务端能力） | **Ktor Server**（与协程/Flow 契合最好），需评估体积 |
| **D-P** | **`/v1/models` 返回虚拟模型还是真实模型？** | 虚拟（策略 A）／真实（策略 B） | **A**（见 §4.5） |
| **D-Q** | **网关与 App 生命周期如何绑定？** | 常驻／任务期间启动 | **任务期间**（与 D-J 一致），但需处理 dsh 启动时的就绪探测 |

---

# 第八部分 · 风险登记

| ID | 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|---|
| **R9** | dsh 未来版本改了 `baseURL` 语义或去掉该配置项 | 低 | 高（代理方案失效） | `dsh-llm-pi-ai` 的 hand-declared route 是**公开设计目标**，改掉的可能性低；锁版本 + 隔离层（同 R4） |
| **R10** | 网关的 SSE 转发实现有 bug，导致流式中断 | **中** | 中（体验受损） | 复用现有 `SseParser` 的协议知识；写协议级单测（用真实 SSE 样本回放） |
| **R11** | 同机恶意 App 蹭用网关 | 低 | 中（消耗用户额度） | 本地随机 token + 只监听 loopback（§4.4） |
| **R12** | 网关成为性能瓶颈（Kotlin 转发 Node 请求） | 低 | 低 | 纯流式转发，无缓冲，开销可忽略 |
| **R13** | dsh 的某些能力依赖 pi-ai 特有功能（裁掉后缺失） | 中 | 中 | 先保留 pi-ai 跑通，**验证通过后再裁**；裁剪列为独立步骤而非前置 |

---

# 附录 A · 实测命令与结果

```bash
# 1. dsh 的 LLM 相关包（★ 订正研讨文档的关键）
ls F:/dsh-program/node_modules/@deepseek-ai/ | grep -i llm
#   → dsh-llm, dsh-llm-deepseek, dsh-llm-pi-ai, dsh-llm-retry,
#     dsh-session-title-first-prompt-llm, dsh-session-title-llm

ls F:/dsh-program/node_modules/@deepseek-ai/ | grep -i -E "openai|anthropic|gemini|google|mistral|claude"
#   → 零命中（确认没有独立的多家 provider 包）

# 2. dsh-llm-deepseek 支持 baseURL
grep -n "baseURL" dsh-llm-deepseek/lib/types/index.d.ts
#   → 36:    baseURL?: string;   // "Endpoint base"

grep -n "chat/completions" dsh-llm-deepseek/lib/index.js
#   → 589:  response = await fetch(`${connection.baseURL}/chat/completions`, {...})

# 3. dsh-llm-pi-ai 支持的协议
grep -n -A 5 "const PROTOCOLS" dsh-llm-pi-ai/lib/index.js
#   → openai-completions / openai-responses / anthropic-messages

grep -n "headers:" dsh-llm-pi-ai/lib/index.js
#   → headers: z.dict(z.string())    ★ 支持自定义请求头

# 4. pi-ai 是第三方 SDK（解释了 @mistralai 等包的来源）
grep -A 4 '"dependencies"' dsh-llm-pi-ai/package.json
#   → "@earendil-works/pi-ai": "^0.82.1"

# 5. dsh-mcp-client 支持 streamable-http
grep -n -A 10 "StreamableHttpConfig" dsh-mcp-client/lib/types/index.d.ts
#   → transport: 'streamable-http'; url: string; headers: Record<string,string>

# 6. profile 配置语法（真实样本）
head -40 D:/dsh-home/profiles/web/cordis.patch.yml
#   → - id: <entry id> / name: <package> / config: {...}
```

---

# 附录 B · 本文对研讨文档的订正

| # | 研讨文档原文 | 订正 |
|---|---|---|
| 1 | §2.2 "**LLM 接入**：`dsh-llm` + deepseek / openai / anthropic / google / mistral / pi-ai" | dsh **只有 2 个 provider 包**：`dsh-llm-deepseek`（专用）+ `dsh-llm-pi-ai`（通用适配器，基于第三方 SDK `@earendil-works/pi-ai`）。**没有** openai/anthropic/gemini/mistral 独立包；那些包是 pi-ai 的依赖 |
| 2 | §2.3 体积账把 `@mistralai` 25MB 标为"不用 Mistral 可删" | 订正：它是 `pi-ai` 的依赖，**只有裁掉整个 pi-ai 才能一起删**。而代理方案恰好提供了这个可能（见 §0 结论③） |
| 3 | §3.1 分层架构中 L3 只承担"Android 能力 → dsh"单向 | L3 实际是**双向**：还要承担"dsh 的模型请求 → Android 网关"（见 §5.1） |
| 4 | **全文未提及** Key 如何跨进程传递 | 这是**重大遗漏**。路线 C 与"Key 永不落明文"存在架构级冲突，本文 §1 专门处理 |

---

# 附录 C · 本文的确定性分级

| 内容 | 确定性 |
|---|---|
| dsh 的 provider 包清单、`baseURL`/`headers`/`api` 配置项、三协议、MCP transport | **实测**（本机命令，可复现，见附录 A） |
| `pi-ai` 是第三方 SDK、其依赖关系 | **实测**（`package.json`） |
| 配置语法（`- id:` / `name:` / `config:`） | **实测**（`D:\dsh-home\profiles\web\cordis.patch.yml` 真实样本） |
| 代理方案的可行性与收益 | **本文建议**，待评审与实验验证 |
| Android 上"任何 App 都能连 localhost" | **已知事实**（Android 无 localhost 进程隔离），但**具体机型行为建议实测** |
