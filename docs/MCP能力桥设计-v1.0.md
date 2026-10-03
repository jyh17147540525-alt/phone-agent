# MCP 能力桥设计 v1.0 —— 让 dsh 调用手机的能力

> 状态：**已交付 + 真机联调通过**（2026-10-03，提交 `29b4920`；验收记录与遗留项见 §8）
> 日期：2026-10-02 初稿 · 2026-10-03 回写验收结果
> 路线来源：`docs/豆包手机智能体考察与dsh移植可行性研讨-v1.0.md` §3.4 的 **P2（MCP 桥打通）**
> 前置事实来源：本机对 `@deepseek-ai/dsh-mcp-client`（0.1.0-rc.7）与 `@modelcontextprotocol/sdk`（1.30.0）
> 的**源码精读**（非文档转述）；兼容性结论均标注了出处（§附录 A）。
>
> **P2 验收线**（原文）：Android 侧一个 `screen_read` 工具能被 dsh 成功调用并返回节点树。

---

## §0 一句话

在 App 里起一个**只监听 127.0.0.1 的 MCP 服务器**，把「读屏」这个能力以标准 MCP 工具
（`mcp__phone__screen_read`）暴露给 dsh —— 全部判定逻辑放在**纯 Kotlin 模块**里
（可离线验证），Android 侧只做两根接线的薄壳。

---

## §1 兼容目标（钉死的清单，不是猜的）

dsh 的 MCP 客户端 = **官方 `@modelcontextprotocol/sdk` 的 `StreamableHTTPClientTransport`**。
以下行为直接来自源码（出处见附录 A），它们就是本服务器的**验收清单**：

| # | 客户端行为（源码事实） | 我们服务端的对策 |
|---|---|---|
| 1 | 每个消息 `POST` 到配置的 URL；`Accept: application/json, text/event-stream`；`Content-Type: application/json` | 只认 `POST`；**回 `application/json`**（规范允许非 SSE；SDK 明确分支支持） |
| 2 | 通知（无 `id`）得到 `202` 后：若是 `notifications/initialized`，**会发一个 `GET` 尝试开 SSE 流**；`GET` 返回 **405 = 明确被容忍**（源码注释原文） | 通知回 `202` 空体；`GET` 回 `405`（带 `Allow: POST`） |
| 3 | 请求的响应按 `Content-Type` 分派：`text/event-stream` → 按 SSE 解析；`application/json` → 直接解析（单个或数组）；**其它 → 报错** | `Content-Type: application/json; charset=utf-8`（`mediaTypeEssence` 取本质类型，带 charset 没问题） |
| 4 | `initialize` 里客户端发 `protocolVersion = LATEST_PROTOCOL_VERSION`（1.30.0 = `2025-11-25`）；服务端回包版本必须 ∈ 客户端支持清单 | 回显请求的版本（若在其清单内）；清单：`2025-11-25 / 2025-06-18 / 2025-03-26 / 2024-11-05 / 2024-10-07`；不在则回落 `2025-06-18` |
| 5 | 若无 `Mcp-Session-Id` 响应头，客户端后续不带该头；`DELETE` 仅在持有 session 时发送 | **完全 stateless**：不发 session id；`DELETE` 回 `405`（SDK 容忍） |
| 6 | 鉴权头来自插件配置的 `headers`（原样附加到每条请求） | 用 `Authorization: Bearer <一次性 token>`（与网关同一套加固，§4） |
| 7 | 工具名 `mcp__<serverName>__<rawName>`；`serverName` 必须匹配 `[A-Za-z0-9_-]{1,32}` | `serverName = phone` → 工具名 `mcp__phone__screen_read` |
| 8 | `tools/call` 结果：`content` 数组里**文本块按顺序换行拼接**进模型；`isError: true` → 抛出 → 模型看到失败；`structuredContent` 透传给程序化调用方 | 文本块 = 人类/模型可读的节点列表；`structuredContent` = 结构化快照；安全拒绝走 `isError: true` |
| 9 | 连接丢失按指数退避重连（500ms 起、上限 30s、`maxAttempts` 10 次）；HTTP 传输**按调用重试** | 服务器侧无需配合；提示用户：App 重启后端口/token 变化 → 见 §6 的投递说明 |
| 10 | 客户端 `initialize` 发送 `clientInfo = {name:"dsh-mcp-client", version:"0.0.1"}`、`capabilities = {}` | `initialize` 结果含 `serverInfo` 与 `capabilities.tools`（见 §3） |

★ **架构含义**：没有私有协议、没有 SSE 依赖、没有会话管理 —— 一个**单端点、无状态、纯 JSON**
的 HTTP 服务器就够。这让「桥」可以在无 Android 依赖的纯 Kotlin 里实现并被完整离线测试。

---

## §2 架构与模块归属

```
┌────────────────────────── 手机（同一台设备）──────────────────────────┐
│                                                                       │
│   dsh（proot Debian，Node）                                           │
│     └─ dsh-mcp-client ──HTTP(127.0.0.1:PORT)──►  ┌─────────────────┐  │
│                                                  │  :mcp（纯 Kotlin）│  │
│   PocketAgent App                                │  McpServer       │  │
│     └─ :app 薄壳 ──实现端口──►                    │  ├ 协议/分发      │  │
│        ScreenReaderPort ─────────────────────►  │  ├ ScreenRead 工具│  │
│        ScreenSafetyPort ─────────────────────►  │  ├ 隐私关卡       │  │
│                                                  │  └ 配置渲染       │  │
│                                                  └─────────────────┘  │
└───────────────────────────────────────────────────────────────────────┘
```

| 内容 | 落点 | 为什么 |
|---|---|---|
| 协议核心 / HTTP 服务器 / 工具注册表 / screen_read 判定与序列化 / dsh 配置渲染 | **新模块 `:mcp`（纯 Kotlin）** | 全部零 Android 依赖 → 进 `run_logic_tests.py`；这一层错的表现全是静默的（见 §7） |
| `ScreenReaderPort` 实现（`AccessibilityPerceptionManager` → DTO） | `:app` 薄壳 | 只有它需要 `android.graphics` / 无障碍服务 |
| `ScreenSafetyPort` 实现（`:safety` 的 `SensitiveDetector`） | `:app` 薄壳 | ⚠️ `:safety` 是 **Android 库模块**（带 hilt/robolectric），纯 Kotlin 模块**在 Gradle 上依赖不了它** —— 详见 §7 的端口说明 |
| 生命周期（起停、状态、草稿文件） | `:app`（AppContainer + 设置页卡片） | 与网关会话同一模式 |

**数据流（一次 screen_read 调用）**：

```
dsh ─POST tools/call {name:"screen_read"}→ McpServer
  → 工具查表 → ScreenReadTool.execute(args)
     → ScreenReaderPort.capture()            [:app → :perception，主线程采集]
     → 派生可见文本 + 输入控件形状（纯函数）
     → ScreenSafetyPort.review(...)          [敏感页/密码框 → 拒绝，isError]
     → PrivacyFilter.review(树路径)          [:agentlogic，产出遮蔽计划]
     → 序列化（文本块 + structuredContent）
  → JSON 响应 → dsh → 模型
```

---

## §3 协议子集（实现清单）

**端点**：`POST /mcp`（配置里的 URL 即 `http://127.0.0.1:PORT/mcp`）。

| 消息 | 处理 | 响应 |
|---|---|---|
| `initialize` | 版本协商（§1-4）；记录 `clientInfo`（仅日志用，不入库） | `result: {protocolVersion, capabilities:{tools:{listChanged:false}}, serverInfo:{name:"pocketagent-mcp", version:"0.1.0"}}` |
| `notifications/initialized` | 无需处理（客户端随后会试 `GET`，我们回 405） | `202` 空体 |
| `ping` | 规范里客户端可发；回空结果 | `result: {}` |
| `tools/list` | 返回注册表（当前仅 `screen_read`） | `result: {tools:[{name, description, inputSchema}]}` |
| `tools/call` | 查表 → 执行 → 结果 / `isError` | 见 §5 |
| 其它方法（请求） | — | `error: -32601 Method not found` |
| 任何通知（无 `id`） | 一律忽略语义 | `202` 空体 |
| 批处理数组 / 非对象 | 拒绝（SDK 单条发送，不支持不是"遗漏"是"声明"） | `error: -32600` |
| `GET /mcp`、`DELETE /mcp` | — | `405` + `Allow: POST`（SDK 明确容忍） |
| 其它路径 / 方法 | — | `404` / `405`，**不回显路径**（同网关加固） |

**JSON-RPC 细节**：`jsonrpc:"2.0"` 校验；`id` 原样回显（数字或字符串）；解析失败 → `-32700`；
非法请求 → `-32600`；`tools/call` 名字未知 → `-32602`。错误对象只含 `{code, message}`，
message 不含内部细节（同网关的 `sanitize` 纪律）。

**为什么不做 SSE / session**：源码证明客户端两者都不要求（§1-2/3/5）；
做得越少，能静默出错的面就越小。将来若需要服务端主动通知（如工具列表变化），
再按规范加 SSE —— 到那时 `listChanged:false` 才需要改。

---

## §4 安全模型

**威胁模型与网关完全相同**（复用其结论）：只监听 loopback ⇒ 外部网络碰不到；
但 **Android 的 localhost 没有进程隔离** —— 同机任何有 INTERNET 权限的 App 都能连
`127.0.0.1:PORT`。screen_read 能读屏 ⇒ 拿到 token 的恶意 App 能读用户屏幕。

| # | 措施 | 实现 |
|---|---|---|
| 1 | 只监听 `127.0.0.1`（`InetAddress.getLoopbackAddress()`） | 同网关 |
| 2 | 端口由内核分配（`port = 0`） | 同网关 |
| 3 | **一次性 token**：每次启动重新生成，32 字节 `SecureRandom` | **复用 `GatewayTokenProvider`**（:provider:gateway）——同一威胁模型、同一套加固；复制一份等于给未来留两个会各自漂移的安全实现 |
| 4 | 常量时间比较（`MessageDigest.isEqual`） | 同上（`matches`） |
| 5 | **拒绝带 `Origin` 头的请求（403）** | MCP 规范对 localhost 服务器的建议：防 DNS rebinding（浏览器必带 `Origin`，Node 客户端不带）。源码侧：dsh 用 Node fetch ⇒ 不带 `Origin` |
| 6 | `Host` 头必须是 loopback（`127.0.0.1[:port]` 或 `localhost[:port]`），否则 403 | 同上的纵深防御 |
| 7 | 响应头不带 `Server`/`Date`；错误信息过滤 | 同网关 |
| 8 | 请求体上限 256KB；连接数上限；读超时 30s | 同网关的数值纪律 |

**关卡顺序（screen_read，不可调换）**：

1. **敏感页面一票否决**：包名黑名单/前缀 + 页面关键词 + 控件形状（密码/验证码/身份证/金额）
   任一命中 → 直接拒绝。判定用的是 `:safety` 的 `SensitiveDetector`（那条引擎的"漏判比误判危险"
   纪律是现成的，不在本模块重造）。
2. **`PrivacyFilter` 关卡**（`:agentlogic`，项目红线"上传路径的唯一出口"）：树路径
   （`requiresScreenshot=false`）会返回"遮蔽输入框文本"的计划；本模块**照着计划执行遮蔽**。
3. 序列化（§5）。

⚠️ **审计留到 P4**：本条链路暂时只拒绝、不落审计事件（`SafetyGuard.recordBlock` 的接线在
"安全护栏前置"阶段做）——这是**明确的欠账**，不是遗忘。

---

## §5 `screen_read` 契约

**工具声明**（进 `tools/list`）：

```json
{
  "name": "screen_read",
  "description": "读取手机当前屏幕上的可见元素（无障碍树）。返回元素列表：文本、类型、坐标、可点击性。用于了解用户当前在哪个界面、有哪些可操作项。注意：只有文字结构、没有图像；敏感页面（支付/密码/验证码）会被安全策略拒绝。",
  "inputSchema": {"type": "object", "properties": {}}
}
```

**成功结果**：`content = [ {type:"text", text: <下面的文本>} ]`，另附 `structuredContent`。

文本格式（给模型）：

```
【屏幕快照】com.tencent.mm 1440×3200 · 节点 47 · 来源=无障碍树 · 置信度 0.7
1. Button ["发送"] clickable @(1223,2299) id=com.tencent.mm:id/send
2. EditText ["搜索"] 可输入 [已遮蔽] @(600,180)
3. TextView 无文本 @(720,185)
（已截断：共 123 个节点，仅列出前 100 个）
```

规则（全部是**可离线断言的常量**）：

- 每行：`序号. 简类名 ["text"] ["desc"] [可输入|可滚动] @(中心坐标) id=短id`；
  text/desc 各截断到 80 字符（超长加 `…`）。
- 节点上限 **300**；超出时**如实报截断**（含总数）——不许静默截断。
- 遮蔽：`PrivacyFilter` 计划命中的输入框节点，其 `text` 一律替换为 `[已遮蔽]`（comment：树的文本
  同样会带出用户正在打的字；这是该计划的树路径语义）。
- 头部行必须包含**来源与置信度**——树不含视觉信息（图标、图片按钮、自绘控件），
  给模型满分置信度会让它跳过补采（沿用 `ScreenSnapshot.confidence` 的既有立场）。

`structuredContent`（给程序化调用方）：`{package, activity, width, height, source, confidence,
nodeCount, listedCount, truncated, nodes:[{nodeId,class,text,desc,bounds:[l,t,r,b],clickable…,depth}]}`。
本期**不声明 `outputSchema`**（dsh 对已声明 schema 会做子集校验，等形状稳定再开）。

**失败/拒绝结果**：一律 `isError: true` + 一条**用户能直接读**的文本：

| 情形 | 文本（要点） |
|---|---|
| 敏感页面/密码框等 | 对应 `SensitivityHit.userMessage` + "这是安全策略，请停止该方向并把原因转达给用户" |
| 无障碍服务未开启 | "手机的辅助功能（无障碍）服务没有开启，请在系统设置里为本应用开启后再试" |
| 树读不到（窗口不提供节点） | "该界面不提供无障碍信息（可能是游戏/自绘界面），当前版本无法读取"，**且不得**包装成"界面是空的" |
| 读取时间超时 | 如实报告超时（沿用 `PerceptionManager` 的既有语义） |

---

## §6 dsh 侧配置：渲染与投递

**落点**：profile 的 `cordis.patch.yml`（当前真机上是空 `[]`；文件自身注释写明
"Edit cordis.patch.yml, not this file"，它就是官方的用户补丁层）。

**渲染内容**（`:mcp` 纯逻辑，含幂等合并；语法出处见附录 A-3）：

```yaml
# ─── PocketAgent MCP 能力桥（由应用生成，请勿手改本块）───
- insert:
    - id: mcp-pocketagent
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: phone
        transport: streamable-http
        url: "http://127.0.0.1:12345/mcp"
        headers:
          Authorization: "Bearer <本次启动的 token>"
        toolCallTimeoutMs: 30000
        failOnStartupError: false
```

**合并语义**（与 `DshConfigPatch` 同一纪律，实现为**逐行手术**）：

- 文件为 `[]` / 空 → 用本块替换（保留注释行）；已有本块（按哨兵注释行与 `id: mcp-pocketagent` 双认）
  → 原地替换（**幂等**：合并两次结果相同）；
- 文件里有**我们看不懂的结构**（不是顶层数组）→ **拒绝合并并如实报告**（同
  `mergeIntoCredentialsYaml` 的返回 `null` 约定）——绝不覆盖用户的东西。

**投递（本期手工，写进验收步骤）**：跨 UID 写 Termux 私有目录这件事在**网关那一期就没解决**
（`docs` 的未决项），本设计**不新造投递机制**。App 侧把渲染好的 patch 落到自己的私有草稿
（沿用 `dshDraftSettings` 模式），验收时由人（workbuddy）取出来放进容器里的
`~/.dsh/profiles/headless/cordis.patch.yml`。

⚠️ **token 轮换与 dsh 重启的耦合**：端口与 token 每次 App 启动都变（这是安全设计），
而 patch 层按 `patchReload: "startup"` 在 dsh 启动时生效 ⇒ **App 重启后需要重投配置并重启 dsh**
（若真机实测 HMR 对 patch 生效，则只需重投）。这条写进验收步骤，不藏。

---

## §7 模块与工程约定

**新模块 `:mcp`（纯 Kotlin / 进离线验证器）**。四个登记点（本项目约定）：

1. `android/settings.gradle.kts` 的 `include(":mcp")`（带"为什么存在"注释）；
2. `android/gen_module_build_files.py` 的 `PURE_KOTLIN`（总数判据：43 → **44**）；
3. 同脚本 `PROJECT_DEPS`：`"mcp": [":provider:gateway", ":agentlogic"]` ——
   前者复用 `GatewayTokenProvider` + `DshConfigPatch.yamlScalar`；后者复用 `PrivacyFilter`；
   另 `EXTRA_TEST_DEPS` 登记（测试用 `runTest`）；
4. `tools/verify/run_logic_tests.py` 的 `MODULES`。

**⚠️ `:safety` 的 Gradle 制约（本设计的一个硬约束）**：`:safety` 是 Android 库模块
（`com.android.library` + hilt；尽管其源码零 Android import），**纯 Kotlin 模块依赖不了它**。
所以敏感判定走**窄端口注入**：

```kotlin
// :mcp 定义
fun interface ScreenSafetyPort { fun review(query: ScreenSafetyQuery): ScreenSafetyOutcome }
data class ScreenSafetyQuery(
    val packageName: String,
    val visibleTexts: List<String>,
    val fields: List<InputFieldLite>,   // 不含控件里已输入的内容
)
// :app 实现 = 把 InputFieldLite 逐字段映射成 :safety 的 InputFieldSignature，跑 SensitiveDetector
```

⇒ "哪些页面算敏感"由 `:safety` 自己的测试钉住（已存在 `SensitiveDetectorTest`）；
"命中后怎么办（拒绝 + 文案 + 顺序）"由 `:mcp` 的离线测试钉住。
将来若把 `SensitiveRules/SensitiveDetector` 拆成纯模块，这条端口可退化为直接调用。

**`:perception` 的一处小扩展**：`UiNode` 增 `password: Boolean = false`，
`toUiNode` 填 `node.isPassword`。理由：**密码框检测的第一优先信号是 `inputType=password`**
（`SensitiveDetector` 的既有判据），而当前 `UiNode` 把它丢了 → 桥只能靠文本猜，
"漏判比误判危险"。这是 :perception 的加固性小改，随本任务一并提交。

**本模块为什么必须离线可测（错误后果全是静默的）**：

- 协议分发漏一个分支 → dsh 看到"工具消失"，用户看到"AI 不会读屏了"，没有报错；
- 关卡顺序颠倒（先序列化后过滤）→ 敏感文本**已经进了响应体**，无人知道；
- 遮蔽计划没执行 → 输入框里的半句话跟着树发给模型；
- 配置合并吞掉用户的另一个 patch 条目 → 用户的手工配置静默消失。

---

## §8 分期与验收

**本期（P2）范围内的分步**（每步都有离线判据）：

1. 模块注册 + 协议核心（initialize / tools / notifications / 错误码）→ 单测；
2. HTTP 服务器（真 socket 集成测试：完整 MCP 握手、405、401、Origin 拒绝、体上限）；
3. `screen_read`（端口 / 关卡顺序 / 序列化 / 截断 / 四种失败形态）→ 单测；
4. 配置渲染与合并（幂等 / 拒绝不懂的结构 / 转义）→ 单测；
5. `:app` 薄壳（A11ySource 适配器、两个端口实现、生命周期、设置页卡片）——**沙盒无法编译
   Android 侧**，交 Windows 侧构建验证；
6. 全量离线验证器（基线 1623 → 更高）**全绿**。

**真机验收（交 workbuddy，按 §6 的投递说明）**：

```
① 构建安装最新 APK（覆盖安装后重开无障碍，见既有约定）
② 打开 App → 设置 → dsh 集成页 → 开启「MCP 能力桥」→ 状态显示端口与 token 已生成
③ adb shell run-as <pkg> cat files/mcp/cordis.patch.yml   （取出草稿）
④ 把内容写入手机容器：~/.dsh/profiles/headless/cordis.patch.yml
⑤ 重启 dsh（若 HMR 生效则跳过）
⑥ 跑一句让它读屏的话（headless 会话），观察：
   · dsh 日志出现 mcp-client(phone) 连接成功、注册 1 个工具
   · 工具调用返回节点树文本（而不是报错/空）
⑦ 反向验证（安全）：在支付页/锁屏上调用 → 应得到"安全策略拒绝"的 isError，而不是节点
```

### 验收结果（2026-10-03 回写）

**离线侧（沙盒 + 提交前）**：

| 项 | 结果 |
|---|---|
| 全量离线验证器 | `OK (1694 tests)` 全绿（基线 1623 → `:mcp` +69 → 桥的 ::1 修复 +1 → 网关钉字面量 +1，最后一项随兄弟修复待收编） |
| 变异验证：遮蔽计划置空 | 恰好 1 条红 ——「正常读取返回文本与结构化输出且输入框被遮蔽」（收编时执行） |
| 变异验证：绑定地址改 `::1` | 40 条红：钉字面量 1 条 + 数十条 `Connection refused`（与真机同款症状）—— 「地址漂移」这一类有防线 |

**真机侧**（提交 `29b4920`）：①—⑥ 全部执行并通过。dsh 自主调用 `mcp__phone__screen_read`
成功并**准确描述界面细节**（"每次开启端口都会变""左上角有返回按钮"）—— 不是猜的。

★ **联调中炸出并修复一个跨平台漂移**：`InetAddress.getLoopbackAddress()` 在 Android（ART）上
返回 `::1`，与对外公布的 `http://127.0.0.1:<port>` **不是同一个地址** ⇒ 客户端 `ECONNREFUSED`。
修法：绑定 / `mcpUrl` / Host 白名单统一 **`LOOPBACK_HOST`（IPv4 字面量）常量**。
⚠️ 这正是"离线验证器**结构性**抓不到"的一类（JVM 上两边一起错、测试全绿）——
防线只能是"不用会漂移的写法"（字面量 + 注释说清为什么）。

**遗留（未回填，按优先级）**：

1. **⑦ 反向安全验证未在真机执行**（支付/敏感页 → `isError` 拒绝）—— P2 安全验收的另一半；
2. **真机遮蔽目击未执行**（输入框内容是否真的被遮蔽；单测覆盖、真机未见）；
3. **网关同款漂移已修、未收编**：`HttpGatewayServer` 的工作树改动等待评审
   （`LOOPBACK_HOST` 常量 + 钉字面量测试；1694 全绿 + 变异 40 红），收编后需重建
   并在真机核对 `/proc/net/tcp` 出现该端口（IPv4 而非 `tcp6`）。

---

## §9 不做什么（明确的边界）

- **截图**（`screen_capture`）、点击/滑动/输入、启动应用 —— P3 执行端，本设计只留出工具插槽
  （`ToolRegistry` 可加）。
- **速率限制与审计落库** —— P4 护栏前置（欠账已在 §4 标注）。
- **服务端主动通知（SSE）** —— 无消费者需要（`listChanged:false`），不加。
- **自动投递到容器** —— 未决项与网关同源，不在 P2 展开。
- **多客户端/多服务器** —— serverName 固定 `phone`；冲突策略等真出现再说。

---

## 附录 A：兼容性证据（精读来源）

1. `@modelcontextprotocol/sdk@1.30.0` `dist/esm/client/streamableHttp.js`（478 行全文）：
   `send()` 的 202/JSON/SSE 分支、`_startOrAuthSse` 的 405 容忍、`_commonHeaders` 的
   `mcp-session-id` / `mcp-protocol-version`、`terminateSession` 的 405 语义。
2. 同包 `dist/esm/client/index.js`：`connect()` 的 `LATEST_PROTOCOL_VERSION` /
   `SUPPORTED_PROTOCOL_VERSIONS` 校验（`types.js`：最新 `2025-11-25`，清单五档）；
   `assertCapabilityForMethod` 要求 `tools` 能力存在。
3. `@deepseek-ai/dsh-mcp-client` 0.1.0-rc.7 `lib/index.js`（785 行全文）：`createTransport`
   （`requestInit.headers`）、`callToolUncached`（`client.request` 绕过 SDK 的输出校验）、
   `extractText`/`projectContent`（文本换行拼接、image 持久化、`isError` 抛错）、
   `connectGeneration`（`clientInfo={name:"dsh-mcp-client",version:"0.0.1"}`、`capabilities:{}`）、
   重连策略实现；`README.zh.md` 的配置字段表。
4. cordis 补丁层 `insert:` 语法：官方文档与生态实例（deepseek-harness.github.io 的
   mcp-memory 指南、sandbaseai handbook 的 MCP 集成篇、codegraph/SpreadJS 实例）。
   ⚠️ 实测注记：`insert:` 的单映射/列表两种写法在生态文档中都出现过；本设计采用**列表**写法，
   若真机不认，改一处模板即可（渲染器是纯函数且有测试）。
