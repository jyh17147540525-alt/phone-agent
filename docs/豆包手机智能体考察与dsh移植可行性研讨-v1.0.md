# 豆包手机智能体考察 · dsh 安卓移植可行性 · 框架设计研讨

> 版本 v1.0 ｜ 2026-09-21 ｜ 状态：**研讨稿（待决策）**
>
> 本文所有"实测"结论均来自本机对 `F:\dsh-program` / `D:\dsh-home` 的实际检查，
> 命令与输出见附录 A。"公开报道"结论来自 2026-09 的新闻与厂商口径，已标注来源。

---

## 0. 结论速览

**三条必须最先知道的结论：**

**① 豆包的能力天花板来自「系统签名预装」，不是技术巧妙。**
它读屏走 `READ_FRAME_BUFFER` 直读 GPU 帧缓冲，操作走 `INJECT_EVENTS` 内核注入，
后台隔离靠 WindowManagerService 创建的 headless 虚拟屏。
**这三样第三方侧载应用一样都拿不到。** 所以"照着豆包做"这条路在权限层面是死的 ——
不是难度问题，是物理上做不到。

**② dsh 可以移植，但不是用 nodejs-mobile。**
实测 dsh 的 Node 下限是 **20**（核心包用了 `toReversed()`、`import.meta.resolve`），
而 nodejs-mobile 封顶在 **Node 18.20.4（2024-10，已 EOL）**。
唯一的现实来源是 **Termux 的 Node 26.4.0 for Android** —— 需自行交叉编译或借用其二进制。

**③ 本项目的"不影响用户正常使用"这条需求，与"第三方侧载应用"这个身份存在结构性冲突。**
豆包能做到是因为它跑在 headless 虚拟屏上；AOSP 从 Android 10 起就禁止普通应用把
别人的 App 启到虚拟屏上。**唯一出路是 Shizuku，而 EX-16 至今未验证。**
这条需求要么重新协商，要么把 Shizuku 从"可选"提升为"硬依赖"。

---

# 第一部分 · 豆包手机智能体全方位考察

## 1.1 产品事实

| 项 | 内容 |
|---|---|
| 发布时间 | **2026-09-14** 消费者版（此前 2025 年底有技术预览版） |
| 首款机型 | **努比亚 NaviX Ultra**（中兴通讯 + 字节跳动联合研发），9/16 开售 |
| 产品定位 | 官方口径「全球首款 AI 智能体手机」 |
| 硬件 | 骁龙 8 Elite Gen5 / 512GB 起 / 2 亿像素 / 7100mAh / 7.62mm / 长鑫 10667Mbps LPDDR5X |
| 实体入口 | 侧边**带指纹识别的独立橙色 AI 实体按键** |
| 合规 | 已完成大模型备案 + 工信部终端入网许可 |
| 热度 | 截至 9/15 京东预约量 36 万+ |

> ⚠️ 冷静看待："全球首款 AI 智能体手机"是**厂商宣传口径**。
> 从公开信息看，NaviX Ultra 的差异化在**系统级集成深度**（AI 键、本地数据授权、服务直连），
> 而非底层模型能力 —— 它用的是豆包大模型，模型本身并非独家。

## 1.2 核心能力（六类）

**① 交互唤醒**
- 语音：针对**远场、内噪、嘈杂环境**做了优化
- **AI 实体键**：带指纹鉴权，本人验证后**无需二次解锁**即可调用助手执行任务

**② 屏幕问答**
- 用户**无需截图、无需切换应用**，可直接围绕当前屏幕内容提问，或把它当作任务输入
- 官方演示：相机对准室内 → 语音"根据这个装修风格，帮我选一个一米二以内、价格不超过一千元的柜子"
  → 助手结合画面在电商平台完成筛选推荐

**③ 本地记忆与检索**（用户主动授权前提下）
- 覆盖**电话录音、相册、短信、便签**
- 提供搜索工具**按需检索**
- 支持**手动保存当前屏幕内容**供后续查找
- 引入**任务排队与插队机制**

**④ 录音转写**
- 接入**飞书妙记**：转录、实时翻译、区分说话人、自动总结
- 可检索**过往录音的具体内容**

**⑤ 任务执行**（豆包智能服务 Beta）
- 已接入**曹操出行、地图**等服务
- 曹操出行首批支持**北京、杭州**
- 用户提出打车需求后，助手可确认常用地址、选择车型、**发起下单**

**⑥ 操作手机**（GUI Agent，Beta）
- 即"看着屏幕替你操作"，受 **SAEP 协议**约束，见 §1.5

## 1.3 交互方式

| 通道 | 特征 | 备注 |
|---|---|---|
| **语音** | 主通道，远场/嘈杂优化 | 最自然的入口 |
| **AI 实体键** | 指纹鉴权后免二次解锁 | 硬件级入口，第三方 App 无法复制 |
| **屏幕上下文** | 隐式输入，无需用户显式提供 | 这是"理解场景"的关键 |
| **顶部横条** | 监工 + 中断 | 可把虚拟屏画面投射到物理屏查看 |

**交互设计的关键洞察**：豆包把"屏幕内容"当成了**隐式输入**，而不是要求用户描述。
"相机对准 → 语音提问 → 跨平台完成"这个组合，本质是**多模态输入 + 跨应用执行**。
这标志着手机 AI 助手正从「回答问题」转向「理解你所在的场景并替你完成任务」。

## 1.4 场景适配

**已落地场景**：打车下单、比价筛选、录音转写与检索、本地数据检索、屏幕内容问答

**场景选择的规律**：
- **服务直连优先于 GUI 操作**（曹操出行是接口接入，不是模拟点击）
- GUI 操作只用于**没有接口的场景**
- 官方对 GUI 操作持保守态度（Beta + 30 天公示期）

> 📌 这条规律对本项目直接适用：**有 API 就走 API，GUI 只当兜底。**
> 这也是公开分析给开发者的核心建议。

## 1.5 SAEP 协议（本次发布最有价值的部分）

**SAEP = Screen Automation Execution Protocol（屏幕自动化操作声明协议）**

**机制：**
- 第三方应用可**分类分级**声明操作边界
- 可以声明**禁止任何操作**，也可以只限制特定操作：
  **内容修改、最终发布、删除、签到、抽奖等权益领取行为**
- 接入方式二选一：**接入 SAEP 协议** 或 **回复官方邮件**，**两者效力相同**
- **拒绝声明随时生效**，相关应用**立即**列入不可操作清单

**公示期规则（关键）：**
- 公示期：**2026-09-14 起 30 天**（至 **2026-10-15**）
- 公示期内，豆包**仅对以下应用**执行自动化操作：
  1. 系统自带应用
  2. 中兴应用
  3. 字节跳动旗下应用
  4. 已通过 SAEP 或邮件**明确同意接入**的第三方应用
- **其余应用默认不操作**
- 公示期满后：未回复且未接入的应用按**风险层级逐步开放**；**明确表示拒绝的应用始终不会被操作**

**行业类比**："AI 时代的 Robots 协议"（robots.txt 式的单方声明、自愿约定）

**但要清醒**：
- 这是**自律协议，不是技术标准，更不是强制约束**
- 30 天公示只是公示
- 真正决定成败的是两件事：**主流 App 的参与率**、**拒绝后是否真的完全不碰**

## 1.6 技术架构（第三方分析）

> 来源：知乎《豆包后台操作手机原理分析》（2026-08-05），属**第三方逆向分析**，
> 非官方披露。关键结论与官方公开口径（端云协同、L5 机密计算）一致，可信度较高，
> 但具体权限名与进程细节**应以真机验证为准**。

**感知层 —— 不走无障碍**

| 权限 | 作用 |
|---|---|
| `CAPTURE_VIDEO_OUTPUT` | 捕获屏幕视频输出流 |
| `CAPTURE_SECURE_VIDEO_OUTPUT` | 捕获标记为 Secure 的页面（豆包声称遵循 Secure 标记，不截银行类页面） |
| `READ_FRAME_BUFFER` | **直接从 GPU 图形缓冲区读原始图像**，而非调用上层截图 API |

**关键优势**：绕过应用层的反截图/反录屏限制（数据来自内核层 GPU 缓冲区，而非应用层 API）。

**隔离层 —— headless 虚拟屏**

```
┌─────────────────────────────────────────┐
│           物理屏幕 (Display 0)           │
│  ┌─────────────────────────────────┐    │
│  │     用户前台：刷抖音、聊微信      │ ← 用户正常使用
│  └─────────────────────────────────┘    │
│  ┌─────────────────────────────────┐    │
│  │  虚拟屏 (Virtual Display)        │ ← 豆包在后台操作
│  │  同分辨率 / 独立焦点 / GPU 合成   │
│  └─────────────────────────────────┘    │
└─────────────────────────────────────────┘
```

通过 `WindowManagerService` 创建**与物理屏分辨率相同的 headless 虚拟屏**，
**独立输入焦点**，与物理屏互不干扰。点击顶部横条可投射到物理屏"监工"。

**规划层 —— 重云轻端**

| 环节 | 实测/推测参数 |
|---|---|
| 本地进程 | `aikernel`，内存占用约 **160MB** |
| 上传频率 | 每 **3–5 秒**上传虚拟屏画面 |
| 单帧大小 | 约 **250KB/帧** |
| 上传目标 | 字节服务器（`obriccloud.com`） |
| 云端模型 | 多模态大模型（推测为 UI-TARS 系列） |
| 返回内容 | 约 **1KB** 精简指令（`click @e2` / `swipe_up` / `input_text`） |

**执行层 —— 幽灵触控**

- 持有 **`INJECT_EVENTS`** 系统权限（**需系统签名**，随厂商预装）
- 直接向 **Linux 内核 Input Subsystem** 注入原始输入事件
- 通过指定 **`displayId`** 让事件精准落在虚拟屏，而非物理屏
- **不受前台限制**，可发送到任意窗口

**反风控 —— 仿生触控算法**
- 点击坐标加入微小随机偏移（非像素级完美）
- 按压时间模拟人类分布（非固定 50ms）
- 滑动轨迹加入贝塞尔曲线拟合（非直线）
- 目的：骗过微信、淘宝等 App 识别机械点击的风控引擎

**安全阀**
1. 尊重 Secure 标记（不截银行类页面）
2. 支付/转账强制用户手动确认 + 真人检测
3. 权限逐项主动授权，屏幕有明确提示
4. 用户随时可点顶部横条中断

## 1.7 合规与法理（对本项目是重大利好）

**争议焦点：AI 智能体是独立行为主体，还是用户手中的工具？**
—— 即"**双重授权**"问题：智能体跨应用操作，除了用户授权，是否还需要第三方 App 授权？

**当前学理倾向：**
- 多数学者认为智能体本质是**用户意志和行为的技术延伸**；用户授权智能体跨应用访问自己的
  数据、完成指定任务，是**用户意志的外化**
- 结合《个人信息保护法》确立的**个人信息可携带权**、《民法典》数据保护原则性规定，
  这种操作**具备法理正当性**
- 人大、复旦等多所高校学者形成基本共识：**GUI 操作路径下，智能体经用户充分授权后，
  无需另行取得第三方 App 的"双重授权"**

**域外参照：**
- 美国第九巡回上诉法院在**亚马逊诉 Perplexity** 案中**撤销初步禁令**，
  认为真正访问服务器的主体是**用户**，AI 智能体只是用户手中的工具
- 欧盟《数字市场法案》通过"守门人"制度，要求大平台对第三方 AI 工具提供**非歧视的互操作权限**

**专家共识**：应坚持「**开放为原则、限制为例外**」，既不能简单"凡绕开即违法"，
也不能"凡用户想要即正当"。

**政策背景**：国务院《关于深入实施"人工智能+"行动的意见》——
到 **2027 年**，新一代智能终端、智能体等应用普及率需**超 70%**。

**端云协同机密计算 L5 认证**：豆包手机助手通过中国信通院泰尔实验室首批
"端云协同机密计算能力评测"，架构含硬件信任根、可信执行环境、访问隔离、密钥管理、
端云可信校验。

> 📌 **对本项目的意义**：法理上"用户授权即可，无需第三方双重授权"的共识正在形成，
> 这对 PocketAgent 的合规姿态是**实质性利好** —— 之前担心的"擅自操作他人 App"的法律风险
> 有明确的抗辩基础。但**技术上的权限壁垒依然存在**（见 §1.8）。

## 1.8 ★ 对 PocketAgent 的硬约束（本部分最重要的结论）

| 维度 | 豆包手机助手 | PocketAgent（第三方侧载） | 差距性质 |
|---|---|---|---|
| 读屏 | `READ_FRAME_BUFFER` 直读 GPU 帧缓冲 | 无障碍节点树 + MediaProjection 截图 | **权限不可得** |
| 操作注入 | `INJECT_EVENTS` 内核级注入 | 无障碍手势 / Shizuku `input` | **权限不可得** |
| 后台隔离 | headless 虚拟屏（WMS 级） | Shizuku + `overlay_display_devices`（且第三方 App 无法启到副屏） | **架构受限** |
| 反风控 | 仿生触控算法 | 无（且本项目明确禁止刷单/薅羊毛） | 可自行实现但伦理存疑 |
| 权限来源 | **系统签名预装** | 用户手动授权 | **根本差异** |
| 适用设备 | 仅合作厂商机型 | 任意 Android 12+ | 本项目优势 |

**结论：**
1. **豆包的技术路线无法复制**，因为它建立在手机厂商的系统签名之上。这不是"努力就能追上"的差距。
2. **本项目只能走"无障碍 + Shizuku"路线**，即公开分析中被称为"普通无障碍方案（如 AutoGLM）"的那条。
3. **"不影响用户正常使用"这条需求，在第三方身份下只能靠 Shizuku 实现**，且受 AOSP
   Android 10+ 限制（普通应用只能在自己创建的虚拟屏上启动自己的 Activity）。
4. 因此 **EX-16（Shizuku 能否把第三方 App 启到副屏）从"重要实验"升级为"决定产品形态的
   生死实验"**。若不成立，"不影响用户使用"必须重新协商（降级为小窗 / 全屏接管 / 手动引导）。

---

# 第二部分 · dsh 安卓移植可行性

## 2.1 dsh 是什么（本机实测）

| 项 | 实测值 |
|---|---|
| 包名 | `@deepseek-ai/dsh` |
| 版本 | **0.1.0-rc.7**（注意：**RC 版本**） |
| 许可证 | **MIT** |
| 形态 | Node.js CLI，基于 **cordis** 插件框架 |
| 直接依赖 | **71 个** `@deepseek-ai/*` 包 |
| 依赖树 | **195 个** `@deepseek-ai/*` 包，**256 个**顶层包 |
| 全量体积 | **329 MB** |
| 入口 | `lib/bin.js` |
| 数据目录 | `$DSH_HOME`（本机 `D:\dsh-home`） |

**启动模型：**
```
dsh --profile <name>              启动指定 profile
dsh --profile headless "job"      跑单次会话，打印结果后退出 ★
dsh web                           --profile web 的别名（浏览器 UI）
dsh plugin --profile <name> ...   转发给 pnpm 管理插件
```

**profile 的本质（关键认知）：**
一个 profile **不是一个配置文件，而是一个 pnpm 项目**：
- `package.json` → `dsh.profile.bundles` 声明有序的插件 bundle 列表
- `cordis.patch.yml` → 用户自己的补丁层（按 id 定位的配置覆盖、禁用、插入）
- `pnpm-lock.yaml` + `node_modules/` → 插件实体
- 插件来源：npm 包 / GitHub 仓库 / 本地 `file:` 路径
- 还有 `.dsh-market` 插件市场目录

**插件树的组装顺序：**
```
空根
 → 各 bundle 的 patch（按 dsh.profile.bundles 顺序）
 → profile 的 cordis.patch.yml
 → $DSH_HOME/cordis.patch.yml
 → --patch 覆盖层
```

> 📌 **这个设计对移植极其有利**：要裁剪出一个"安卓可用"的最小 profile，
> 只需要改 `dsh.profile.bundles` 列表，**不需要改 dsh 源码**。

## 2.2 可复用的能力资产

dsh 的 195 个包覆盖了做一个 agent 需要的**几乎全部工程件**：

| 能力域 | 包 |
|---|---|
| **Agent 循环** | `dsh-agent` / `dsh-agent-instructions` / `dsh-goal` / `dsh-goal-round-driver` |
| **LLM 接入** | `dsh-llm` + deepseek / openai / anthropic / google / mistral / pi-ai |
| **会话** | `dsh-session` / `dsh-session-projection` / `dsh-session-reference` / `dsh-session-query-sqlite` |
| **上下文压缩** | `dsh-compaction-basic` / `dsh-compaction-tool-result-pruner` |
| **Token 计量** | `dsh-token-meter` |
| **工具集** | fs / fs-search / bash / bash-persistent / pwsh / web / subagent / workflow / todo / skill / ralph / ask-user / str-replace-editor / goal / jobs / cordis |
| **★ MCP** | **`dsh-mcp-client`** |
| **沙箱** | `dsh-sandbox-local` / `dsh-sandbox-windows-acl` |
| **Web 宿主** | `dsh-host-webserver` / `dsh-host-frontend-static` / `dsh-host-apiproxy` / `dsh-web-frontend` |
| **技能系统** | `dsh-skill` / `dsh-skill-filesystem` |
| **计划模式** | `dsh-plan-mode` |
| **子代理** | `dsh-subagent` / `dsh-tool-subagent` / `dsh-tool-subagent-control` |
| **工作流** | `dsh-workflow-worker-thread` |
| **调度** | `dsh-schedule` / `dsh-jobs-local` |
| **人设/上下文** | `dsh-persona` / `dsh-time-context` / `dsh-tmux-context` / `dsh-system-prompt` |
| **终端** | `dsh-terminal` / `dsh-terminal-bash` |

**这是一笔巨大的工程资产。** 会话管理、上下文压缩、token 计量、计划模式、子代理编排、
工作流、MCP —— 每一项从零写都是数周到数月的工作量。

## 2.3 体积账（实测）

| 项 | 体积 |
|---|---|
| 全量 `node_modules` | **329 MB** |
| `@deepseek-ai/*` 全部 | **28 MB** |
| └ 最大的 `dsh-web-frontend` | 4.7 MB |
| └ 多数包 | 200–900 KB |

**329MB 的大头（都不是 dsh 本身）：**

| 包 | 体积 | 安卓上是否必需 |
|---|---|---|
| `pnpm` | 39 MB | ❌ 运行时不需要（构建期裁剪即可去掉） |
| `@opentelemetry` | 31 MB | ⚠️ 可裁剪 |
| `@img`（sharp 的原生库） | 28 MB | ❌ 图片处理，安卓上可换 |
| `node-pty` | 27 MB | ❌ **无 android prebuild** |
| `@mistralai` | 25 MB | ❌ 不用 Mistral 可删 |
| `@google` | 14 MB | ⚠️ 按需 |
| `openai` | 12 MB | ⚠️ 按需 |
| `@aws-sdk` | 5.9 MB | ❌ 可删 |
| `@anthropic-ai` | 5.8 MB | ⚠️ 按需 |

> **结论**：裁剪后一个"安卓最小 profile"（headless + 1 个 LLM provider + MCP）
> 的 JS 部分**保守估计 40–70 MB**，加上 Node 运行时（单 ABI 约 30–40 MB），
> **APK 预计 80–130 MB**（arm64 单架构）。

## 2.4 ★ 四条硬约束（全部实测）

### 约束 1：Node 版本下限是 20，nodejs-mobile 不可用

**dsh 实际使用的现代 API（实测扫描）：**

| API | 最低 Node | 出现在 | 可否绕过 |
|---|---|---|---|
| `Array.prototype.toReversed()` | **20** | `dsh-agent-instructions`、`dsh-goal-round-driver` | ❌ **核心包，绕不开** |
| `import.meta.resolve` | **20.6** | `dsh-sandbox-local`、`dsh-workflow-worker-thread`、`dsh-host-directory-picker-native` | ⚠️ 可禁用对应功能 |
| `node:sqlite` | **22.5** | 仅 `dsh-session-query-sqlite` | ✅ **惰性动态导入**，禁用会话搜索即可 |

> `dsh-session-query-sqlite` 的注释原文：
> *"a disabled deployment never imports node:sqlite, opens the index, or observes sources."*
> 即**该包是设计成可禁用的**。

**所以真实下限 = Node 20**（因为 `toReversed()` 在两个核心包里）。

**而运行时来源实测：**

| 方案 | Node 版本 | 状态 |
|---|---|---|
| **nodejs-mobile** | **18.20.4**（2024-10-07） | ❌ **封顶 Node 18，且已 EOL。不可用** |
| **Termux packages** | **26.4.0** | ✅ 唯一现实的 Android 来源 |

nodejs-mobile 现状（GitHub API 实测）：871 star、未归档、最后推送 2026-04-30、
46 个 open issue、最新 release 停在 2024-10。**维护缓慢，且停在 EOL 的 Node 18。**

### 约束 2：Termux 的 Node 二进制有路径硬编码问题

Termux 的包按 `/data/data/com.termux/files/usr` 前缀构建，**不能直接塞进别的 App**。
两条出路：
- **照 Termux 的 `packages/nodejs/build.sh` 自行交叉编译**（推荐，可控且可长期维护）
- 借用其二进制并做路径重定向（快，但脆弱，且升级链路依赖 Termux）

### 约束 3：桌面工具在安卓上大多无用

| dsh 工具 | 安卓上的情况 |
|---|---|
| `dsh-tool-bash` / `dsh-terminal-bash` | ❌ Android **没有 bash**，需换 `sh` 或自实现 |
| `dsh-tool-pwsh` / `dsh-pwsh-sandbox` | ❌ **Windows 专用**，完全无用 |
| `dsh-sandbox-windows-acl` | ❌ **Windows 专用** |
| `dsh-tool-fs` / `dsh-tool-fs-search` | ⚠️ 面向桌面文件系统；安卓 Scoped Storage 模型完全不同 |
| `node-pty` | ❌ **prebuilds 只有 darwin/linux/win32，无 android** |
| `dsh-tool-web` | ✅ 可用 |
| `dsh-tool-todo` / `dsh-tool-skill` / `dsh-tool-ask-user` | ✅ 可用 |
| `dsh-mcp-client` | ✅ **可用，且是关键** |
| `dsh-host-webserver` + `dsh-web-frontend` | ✅ **可用（localhost + WebView）** |

### 约束 4：dsh 是 RC 版本

`0.1.0-rc.7` —— API 可能大改。把产品核心押在一个 RC 版本上，需要**锁定版本 + 隔离层**。

## 2.5 四种移植路线

| 路线 | 做法 | 优点 | 缺点 | 可行性 |
|---|---|---|---|---|
| **A. Node 全量移植** | 交叉编译 Node 20+，打包裁剪 profile | dsh 原样运行，195 包全复用 | APK 80–130MB；Node on Android 非官方支持；bash/pwsh/pty 需替换；启动慢 | ⚠️ 中 |
| **B. Kotlin 重写内核** | 用 Kotlin 实现 agent 循环/会话/压缩/工具 | 原生、小、快、完全可控 | **等于重造 195 个包**；生态归零 | ⚠️ 低（工作量） |
| **C. 混合：dsh 为脑 + Android 为 MCP 能力端** ★ | dsh 跑在 Android（Node），Android 能力以 **MCP server** 暴露，dsh 用 `dsh-mcp-client` 调用 | 复用 dsh 全部 agent 工程；Android 侧只写"能力端"；MCP 是开放标准，保留换掉 dsh 的退路 | 仍需 Node on Android；进程间通信与生命周期管理复杂 | ✅ **推荐** |
| **D. 远程脑 + 瘦客户端** | dsh 跑在 PC/服务器，手机只做 UI + 能力端 | 手机端极轻；dsh 零改动 | **违背"数据不出设备"与 BYOK 定位**；需常在线网络 | ❌ 与项目原则冲突 |

## 2.6 推荐路线 C 及其理由

**为什么是 C：**

1. **`dsh-mcp-client` 是 dsh 自带的** —— Android 能力天然可以作为 MCP 工具接入，
   **dsh 侧零改动**。这不是权宜之计，是 dsh 本身就设计好的扩展点。
2. **Android 侧只需要实现"能力端"** —— 而 PocketAgent **已经在做这件事**
   （`ScreenSnapshot` / `ActionExecutor` / `SafetyGuard` 已有可编译代码）。
3. **MCP 是开放标准** —— 保留"将来 dsh 挂了/改了，换一个 MCP 客户端"的退路，
   不会把项目锁死在 DeepSeek 的实现上。
4. **安全护栏必须留在 Android 侧** —— `SafetyGuard` 在能力端拦截，
   意味着**即使 dsh 被插件投毒或模型被诱导，也绕不过本地护栏**。
   这与项目既有的"插件永远拿不到 Key、绕不过 SafetyGuard"原则完全一致。

**但必须诚实说**：路线 C 的前提是 **Node 20+ 能在红米 K60 上跑起来**，
这是当前**最大的技术未知数**，必须先做验证实验（见 §4.4 P0）。

---

# 第三部分 · 框架设计

## 3.1 分层架构

```
┌───────────────────────────────────────────────────────────────┐
│  L0  宿主层        Android App (Kotlin + Compose)              │
│      Activity / WebView / 前台服务 / 权限引导 / 悬浮窗 / 更新    │
├───────────────────────────────────────────────────────────────┤
│  L1  运行时层      Node.js on Android                          │
│      node 二进制 / 启动守护 / 日志 / 崩溃恢复 / 单 ABI 打包      │
├───────────────────────────────────────────────────────────────┤
│  L2  智能体内核    dsh（原样复用，零改动）                       │
│      agent 循环 / 会话 / 压缩 / token 计量 / 计划模式 / 子代理    │
│      dsh-llm-* (BYOK) / dsh-skill / dsh-goal / dsh-persona     │
├───────────────────────────────────────────────────────────────┤
│  L3  能力桥        MCP over localhost                          │
│      Android 侧 MCP Server  ←→  dsh-mcp-client                 │
├───────────────────────────────────────────────────────────────┤
│  L4  能力端        Android 原生（复用 PocketAgent 既有资产）      │
│      感知 ScreenSnapshot  │ 执行 ActionExecutor  │ 安全 SafetyGuard │
│      无障碍树 + 截图兜底   │ 无障碍/Shizuku/IME/悬浮窗引导         │
└───────────────────────────────────────────────────────────────┘
```

**关键设计原则（沿用项目既有约定）：**
- **L4 的 SafetyGuard 是最后一道闸**，dsh 侧不可绕过
- **L2 的 dsh 不做任何业务改造**，保证可跟随上游升级
- **L3 用开放标准 MCP**，保证 dsh 可替换

## 3.2 关键组件

| 组件 | 层 | 职责 | 难点 |
|---|---|---|---|
| **NodeRuntimeManager** | L1 | 打包/启动/停止 node 进程，崩溃重启，日志采集，退出清理 | Android 生命周期与 node 进程的绑定 |
| **ProfileBundler** | 构建期 | 裁剪 `dsh.profile.bundles`，剔除 Windows/桌面专用包，产出最小 profile | 依赖闭包分析；缺包时的启动失败排查 |
| **AndroidMcpServer** | L3 | 把 L4 能力暴露为 MCP 工具（stdio 或 localhost HTTP） | 协议实现；并发与超时；进程间错误传播 |
| **CapabilityMapper** | L3 | dsh 工具名 ↔ Android 能力的映射表 | 语义对齐（如 `click` 的坐标空间） |
| **DshBridgePlugin** | L2/L3 | 一个 dsh 插件，负责与 Android 宿主通信（生命周期、UI 事件、中断） | 需跟随 dsh 插件 API 变化 |
| **WebViewHost** | L0 | 承载 dsh 的 web UI（`dsh-host-webserver` 输出） | localhost 端口管理；与原生界面的边界 |
| **PermissionWizard** | L0 | 分机型权限引导（红米 K60 / HyperOS 的 11 项配置） | 各家 ROM 路径不同 |
| **SafetyGuard** | L4 | 敏感页面/控件/动作/频率拦截 + 审计 | **必须前置于所有能力调用** |

## 3.3 能力映射表（dsh 工具 ↔ Android 能力）

| dsh 侧 | Android 侧实现 | 状态 |
|---|---|---|
| `bash` / `terminal` | ❌ 不可用（无 bash）→ 新增 `android_shell`（`sh`）或直接禁用 | 待定 |
| `pwsh` | ❌ 不可用（Windows 专用）→ 直接剔除 | 剔除 |
| `fs` / `fs-search` | ⚠️ 需重定向到 App 沙箱目录，或禁用 | 待定 |
| `web` | ✅ 保留（OkHttp / 系统 WebView） | 保留 |
| `todo` / `skill` / `ask-user` | ✅ 保留 | 保留 |
| **（新增）** `screen_read` | 无障碍树 → `ScreenSnapshot` | **核心** |
| **（新增）** `screen_capture` | MediaProjection（兜底） | 核心 |
| **（新增）** `android_tap` / `swipe` / `input_text` | `ActionExecutor`（无障碍/Shizuku） | **核心** |
| **（新增）** `android_launch_app` | Shizuku `am start` / 无障碍 | 核心 |
| **（新增）** `android_notify` | 通知 | 次要 |
| **（新增）** `android_safety_check` | `SafetyGuard` 前置检查 | **必须** |

## 3.4 实现路径（分阶段）

| 阶段 | 目标 | 验收标准 | 预估 |
|---|---|---|---|
| **P0** | **运行时可行性验证** ★ | 红米 K60 上跑起 Node 20+，`dsh --profile headless "1+1"` 返回结果 | 1–2 周 |
| **P1** | 最小 profile 跑通 | 裁剪后的 profile 在设备上启动，能完成一次真实 LLM 调用 | 1–2 周 |
| **P2** | MCP 桥打通 | Android 侧一个 `screen_read` 工具能被 dsh 成功调用并返回节点树 | 2 周 |
| **P3** | 执行端接入 | `android_tap` 打通（先无障碍，再 Shizuku），能完成一次"打开设置→点击某项" | 2–3 周 |
| **P4** | 安全护栏前置 | 命中支付页面时 `SafetyGuard` 拦截生效，且 dsh 无法绕过 | 1 周 |
| **P5** | 界面与交付 | WebView 承载 dsh UI + 原生权限引导 + 可安装 APK | 2 周 |

> **P0 是唯一的前置关卡。** P0 不过，路线 C 直接作废，需回退到路线 B（Kotlin 重写）
> 或重新协商产品形态。

---

# 第四部分 · 研讨要点

## 4.1 必须达成的共识（按重要性）

**① 承认"豆包路线不可复制"。**
我们的权限天花板与豆包差一个"系统签名"。这不是努力问题。所有方案设计都必须
**从"第三方侧载应用"这个身份出发**，而不是从"豆包能做到所以我们也行"出发。

**② 重新审视"不影响用户正常使用"这条需求。**
它最初可能是参照豆包的体验提出的。但豆包能做到是因为 headless 虚拟屏 +
`INJECT_EVENTS`。**在第三方身份下，这条需求的实现路径只有 Shizuku，且未验证。**
建议明确一个**降级预案**：虚拟屏 → 小窗 → 全屏接管 → 手动引导。

**③ 接受"GUI 只是兜底"的定位。**
公开分析给开发者的核心建议是"有 API 就走 API，GUI 只用于没有接口的场景"。
PocketAgent 的差异化不该是"模拟点击更稳"，而应该是**"任何 App 都不需要适配"** ——
这是 GUI 路线唯一的、也是真实的优势。

**④ dsh 的价值是"工程资产"，不是"产品"。**
我们借的是会话管理、上下文压缩、token 计量、子代理编排、MCP 这些**工程件**，
不是豆包那种系统级能力。要避免"移植了 dsh 就等于有了豆包"的错觉。

**⑤ 法律姿态已经变好，要主动用。**
"用户授权即可，无需第三方双重授权"的学理共识正在形成，且有域外判例支撑。
这对本项目是**实质性利好**，应在 README / 用户协议里明确援引。

## 4.2 需要立刻验证的技术假设

| # | 假设 | 验证方式 | 不成立的后果 |
|---|---|---|---|
| **T1** | Node 20+ 能在红米 K60 上运行 | 交叉编译或借 Termux 二进制，跑 `node -e` | **路线 C 作废** |
| **T2** | 裁剪后的 profile 能启动 | 逐步剔除 bundle，看启动失败点 | 需扩大体积预算 |
| **T3** | MCP over localhost 在 Android 上可用 | dsh 调 Android 侧一个 echo 工具 | 需改为 stdio 或自定义协议 |
| **T4** | Shizuku 能把第三方 App 启到副屏（**EX-16**） | 真机 `am start --display` | **"不影响用户使用"不成立** |
| **T5** | 侧载后能拿到无障碍权限（**EX-13**） | 真机安装 + 受限设置解除 | **整个产品形态不成立** |
| **T6** | node 进程能被前台服务稳定保活 | 后台 30 分钟不被打死 | 需换保活策略 |

## 4.3 可以并行推进的事（不依赖 T1）

- L4 能力端继续完善（`ScreenSnapshot` / `ActionExecutor` / `SafetyGuard`）——
  **这些资产在路线 A/B/C 下都需要，且已经开工**
- 能力映射表（§3.3）细化
- Android 侧 MCP Server 的协议实现（可以先用一个假 dsh 客户端测）

---

# 第五部分 · 待决策问题

| ID | 问题 | 选项 | 建议 |
|---|---|---|---|
| **D-A** ★ | **架构路线选哪个？** | A 全量 / B Kotlin 重写 / **C 混合** / D 远程 | **C**，但以 P0 通过为前提 |
| **D-B** ★ | **"不影响用户正常使用"是否坚持？** | 坚持（则 Shizuku 成为硬依赖）／降级为四级链／放弃 | 坚持 + 明确降级预案 |
| **D-C** | **APK 体积上限？** | 80–130MB 可接受／必须压到 50MB 以下 | 需用户定。社区侧载场景下这是下载门槛 |
| **D-D** | **Node 运行时来源？** | 照 Termux 配方自行交叉编译／借用 Termux 二进制／等官方 | **自行交叉编译**（可控、可长期维护） |
| **D-E** | **dsh 的 RC 版本风险怎么控？** | 锁版本 + 隔离层／跟随升级／等正式版 | 锁版本 + 隔离层，等 1.0 |
| **D-F** | **是否兼容 SAEP？** | 兼容／不兼容／观望 | 观望。兼容需要中心化索引，与"不建后端"冲突 |
| **D-G** | **会话数据存哪？** | 启用 `node:sqlite`（需 Node 22.5+）／禁用（丢检索）／改 Android SQLCipher（要改 dsh 代码） | 先禁用，P5 后再评估 |
| **D-H** | **主界面用 dsh 的 web UI 还是原生 Compose？** | web UI（省事，但体验受限于 WebView）／原生（好体验，但两套 UI） | 先 web UI 验证，后逐步原生 |
| **D-I** | **是否做"拟人化触控"？** | 做／不做 | **不做**。本项目明确禁止刷单/薅羊毛，拟人化的唯一目的是规避风控，伦理上站不住 |
| **D-J** | **Node 进程与 App 生命周期如何绑定？** | 常驻前台服务／按需启动／任务期间才启动 | 任务期间才启动（省电、降低被杀概率） |

---

# 第六部分 · 风险登记

| ID | 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|---|
| **R1** | Node 20+ 无法在 Android 稳定运行 | 中 | **致命**（路线 C 作废） | P0 优先验证；备选路线 B |
| **R2** | EX-16 失败，"不影响用户"不成立 | **中高** | **致命**（产品形态重写） | 明确降级链；提前与用户协商 |
| **R3** | EX-13 失败，侧载拿不到无障碍 | 中 | **致命** | 社区分发路线的头号风险，需真机验证 |
| **R4** | dsh RC 版本 API 大改 | 中高 | 高（维护成本） | 锁版本 + 隔离层 |
| **R5** | APK 体积过大影响传播 | 中 | 中 | 单 ABI 打包；裁剪依赖；按需下载资源 |
| **R6** | Play Protect 误报（读屏+模拟点击） | 中 | 中 | 提供 VirusTotal 报告；主动申诉 |
| **R7** | 第三方 App 风控封号 | 中 | 中 | 明确免责声明；不做拟人化规避 |
| **R8** | dsh 上游与 DeepSeek 商业策略绑定 | 低 | 中 | MCP 开放标准保证可替换 |

---

# 附录 A · 实测数据与方法

**检查对象：**
- `F:\dsh-program`（程序体）
- `D:\dsh-home`（数据目录）

**关键命令与结果：**

```bash
# 1. 包元数据
cat F:/dsh-program/node_modules/@deepseek-ai/dsh/package.json
#   → version 0.1.0-rc.7, license MIT, 无 engines 声明

# 2. 依赖规模
grep -oE '"@deepseek-ai/[a-z0-9-]+"' package.json | sort -u | wc -l
#   → 71（直接依赖）
ls node_modules/@deepseek-ai/ | wc -l
#   → 195
ls node_modules | wc -l
#   → 256（顶层包）

# 3. 体积
du -sh F:/dsh-program/node_modules
#   → 329M
du -sh F:/dsh-program/node_modules/@deepseek-ai
#   → 28M

# 4. Node 版本下限（关键）
grep -rl "toReversed" --include="*.js" .
#   → dsh-agent-instructions, dsh-goal-round-driver   ← Node 20+
grep -rl "import.meta.resolve" --include="*.js" .
#   → dsh-sandbox-local, dsh-workflow-worker-thread, ...  ← Node 20.6+
grep -rl "node:sqlite" --include="*.js" .
#   → dsh-session-query-sqlite（惰性导入，可禁用）      ← Node 22.5+

# 5. node-pty 的 prebuilds
find node_modules/node-pty -name "*.node"
#   → darwin-arm64, darwin-x64, linux-arm64, linux-x64, win32-arm64
#   → ★ 无 android

# 6. 运行时来源
# nodejs-mobile（GitHub API）
#   → 最新 release v18.20.4 (2024-10-07)，最后推送 2026-04-30，871 star
# termux-packages/packages/nodejs/build.sh
#   → TERMUX_PKG_VERSION=26.4.0
```

**结论**：dsh 需要 **Node ≥ 20**；nodejs-mobile 封顶 **18**；
**Termux 提供 Node 26.4.0 for Android** —— 这是唯一现实的运行时来源。

---

# 附录 B · 本文的确定性分级

| 内容 | 确定性 |
|---|---|
| dsh 的包结构、依赖数、体积、Node 下限 | **实测**（本机命令，可复现） |
| nodejs-mobile / Termux 的 Node 版本 | **实测**（GitHub API / 源码） |
| 豆包的产品能力、SAEP 机制、公示期规则 | **公开报道**（多方一致，可信） |
| 豆包的权限名（`READ_FRAME_BUFFER` 等）与 `aikernel` 细节 | **第三方逆向分析**（知乎），非官方，需真机验证 |
| 云端模型为 UI-TARS | **推测**（原文标注"推测"） |
| 四条移植路线与框架设计 | **本文建议**，待评审 |
