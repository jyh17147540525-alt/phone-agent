# 插件体系与 APK 实现说明 · v1.0

> 对应需求：开发一个可直接安装到安卓手机上的 APK 应用，内置插件市场（浏览、搜索、下载、安装），
> 支持从本地手动导入自定义插件，并在导入时明确提示风险由用户自行承担。
> 本文回答四个问题：**插件的定义与用途**、**插件与主应用的交互方式**、
> **插件市场的运行机制**、**应用的整体架构与主要界面**。

---

## 一、插件的定义与用途

### 1.1 定义

**插件不是一段代码，是一份「意图描述」。**

这句话是整个插件体系的地基，也是它和市面上绝大多数插件框架最大的区别。核心原则：

> **本体提供「能力」，插件提供「意图」。**

插件说"我要做这件事"，本体决定"这件事能不能做、怎么做、用哪个通道做"。
插件从头到尾拿不到任何系统级对象，也永远拿不到用户的 API Key 明文。

### 1.2 为什么需要插件

因为**本体不可能内置所有 App 的适配规则**。

微信、支付宝、美团、快递 100、各家银行的界面天天在变。如果每个 App 的适配都靠本体跟进，
等于用一个团队去追几百个 App 的版本迭代 —— 追不上的，而且永远追不上。

插件把这件工作**下放给真正在用那个 App 的人**。他知道这个 App 的按钮长什么样、
什么情况下会弹窗、哪一步最容易出错。他改一条规则，比本体团队猜十次都准。

### 1.3 三级插件

| 级别 | 形态 | 能力 | 面向谁 | 预期占比 |
|---|---|---|---|---|
| **L1 规则包** | 声明式 JSON | 匹配页面 → 执行动作 | 不会编程的普通用户 | 80% |
| **L2 JS 脚本** | QuickJS 沙箱脚本 | 逻辑分支、变量、循环 | 会写代码的用户 | 18% |
| **L3 原生 APK** | 独立 APK + AIDL | 任意原生能力 | —— | **当前不开放** |

**为什么 L1 是主力。** 一个不会编程的人，只要照着模板写
「在微信聊天页 → 找到"发送"按钮 → 点击」，就能贡献一条有用的规则。
门槛低到"能描述清楚一件事"就够了 —— 这才是插件生态能长起来的唯一可能。

**为什么 L3 现在不开。** L3 等于把任意代码加载进本进程，在签名校验与进程隔离方案
成熟之前，本体不加载任何原生插件。这不是技术缺陷，是产品决定 —— 见
`PluginValidator.validateLevel()`，申请 L3 会被直接拒绝。

### 1.4 插件的边界（红线）

插件**拿不到**：

- API Key 明文（永远拿不到，不是"需要申请"）
- `AccessibilityNodeInfo` 原始对象（只能拿到脱敏后的 `ScreenSnapshotView`）
- 绕过 `SafetyGuard` 的路径（所有调用都经能力代理）
- 以下四类能力前缀，**永不开放**：

```
payment.*    key.*    crypto.*    system.*
```

这类能力涉及资金、密钥与系统权限。判据不是"技术上能不能做"，而是
**"一旦做错，用户会不会损失真金白银"**。

对应的实现是 `PluginValidator.inspectRawCapabilities()` —— 它必须在**反序列化之前**跑。
因为 `capabilities` 的类型是严格枚举，一个申请 `payment.pay` 的插件如果直接反序列化，
得到的是"枚举解析失败"，看起来像作者写错了格式；而它实际上是一次**明确的越权尝试**。

---

## 二、插件与主应用的交互方式

### 2.1 唯一通道：能力代理

```
插件 ──调用──▶ PluginHost（能力代理）──▶ SafetyGuard ──▶ ActionExecutor
                    │                        │
                    │                        └─ 六步安全检查，命中即中止
                    └─ 能力白名单校验，未授权的能力直接拒绝
```

**没有第二条路。** 插件模块被设计成不依赖任何 Android API，
它甚至 import 不到 `android.*` —— 这不是靠约定，是靠 Gradle 模块边界强制的。

### 2.2 数据流：脱敏视图而不是原始节点

插件看到的是 `ScreenSnapshotView`：

- 文本内容经过**归一化**（NFKC + 去零宽字符）
- 输入框**只有签名，没有内容**（`InputFieldSignature` 刻意不含 `text` 字段）
- 密码框、验证码框的内容**根本不会进入快照**

这一层是为了让"插件想偷密码"这件事在**数据层面就不可能**，
而不是靠"我们审核了它的代码"。

### 2.3 一次调用的时序

```
1. 插件调用 host.invoke("action.click", target)
2. PluginHost 校验：该能力是否已授权？未授权 → 拒绝
3. 构造 ActionContext（含 inputFields 签名、当前包名、页面文本）
4. SafetyGuard 六步检查：
     ① 应用黑名单      ② 插件声明的目标 App
     ③ 页面敏感关键词   ④ 敏感控件（密码/验证码/金额）
     ⑤ 动作频率         ⑥ 危险动作二次确认
5. 命中任一 → 立即停止，丢弃截图，交还用户
6. 全部通过 → ActionExecutor 按通道优先级执行
```

第 5 步的"丢弃截图"是刻意的：命中的那一帧画面里可能就有验证码，
留在内存里等着被后续插件读走，等于白拦。

### 2.4 安装 ≠ 授权 ≠ 启用

这是三个**独立**的动作，界面上也是三步：

| 动作 | 含义 | 谁决定 |
|---|---|---|
| 安装 | 文件落到 `filesDir/plugins/<id>/` | 用户点"安装" |
| 授权 | 逐项批准它申请的能力 | 用户看风险告知后确认 |
| 启用 | 插件开始真正被调用 | 用户单独开启 |

分开的理由：装完就自动启用，等于用户点一下"安装"就把屏幕控制权交出去了 ——
而他当时可能只是想看看这个插件是干什么的。

---

## 三、插件市场的运行机制

### 3.1 没有服务端

所谓"插件市场"其实是**若干个静态 JSON 文件**，托管在 GitHub Pages / raw 上。
应用把用户订阅的源拉下来、合并、检索、展示。

好处不只是省钱：

- **没有单点** —— 某个源挂了只影响那个源，其他源照常
- **没有审核权** —— 任何人都能架自己的源，本应用不承担内容审核义务
- **没有账户体系** —— 不需要登录就能浏览与下载

代价是**没有中心化的下架能力**。一个源如果开始分发恶意插件，我们只能：
① 把它标成"来源不可信"并提示用户 ② 在本体内置一份黑名单。

### 3.2 订阅源与索引结构

```jsonc
// SubscriptionSource —— 一个源就是一份这个
{
  "name": "社区插件源",
  "author": "pocketagent-community",
  "updatedAt": "2026-09-21",
  "apiVersion": 1,
  "plugins": [
    {
      "id": "community.example.skip-splash-ad",
      "name": "跳过开屏广告",
      "version": "1.0.0",
      "level": "L1_RULES",
      "capabilities": ["screen.read", "action.click"],
      "targetApps": ["com.sina.weibo", "com.zhihu.android"],
      "downloadUrl": "https://.../skip-splash-ad-1.0.0.pagent",
      "sha256": "…64 位十六进制，算的是整个 .pagent 文件…",

      // ↓ 三个展示字段。缺了它们，市场里就只剩一串不敢点的名字
      "description": "自动点掉开屏广告的「跳过」按钮，遇到支付页面不动",
      "author": "PocketAgent 社区",
      "updatedAt": "2026-09-21"
    }
  ]
}
```

> ⚠️ **`description` / `author` / `updatedAt` 是补上来的。** 原本
> `SubscriptionEntry` 里根本没有这三个字段，而市场界面一直在渲染它们 ——
> 于是 `toMarketEntry()` 只能传 `null`，**用户在市场上永远看不到任何插件说明**，
> 只有名字和几个能力标签。与此同时校验器还在警告作者
> 「没有填写插件描述，用户在市场上无法判断这个插件做什么」。
>
> 一边要求作者写描述，一边市场没地方放描述。这种矛盾不会报错，
> 只会让整个市场退化成"一串不敢点的名字"—— 而用户判断一个插件能不能装，
> 靠的恰恰是这段描述。修法见 §5.2。

多源合并规则（`MarketCatalog.merge()`）：

- 同一 `id` **保留版本更高的那个**
- 版本相同时保留先出现的（= 订阅列表里靠前的源），让用户能通过调整订阅顺序表达偏好
- **被拒绝的条目必须展示给用户**，不能悄悄过滤

### 3.2.1 源从哪来：`community-source/`

"市场没有服务端"这句话的另一半是：**得有人把静态 JSON 生成出来**。
仓库里的 [`community-source/`](../community-source/) 就是这个东西，
它是一个完整可发布的源脚手架：

```
community-source/
├── build_source.py      打包 + 生成 index.json（含 --check 模式）
├── plugins-src/         插件源文件，人写这个
│   ├── _template/       模板，以 _ 开头 → 不参与打包
│   ├── focus-guard/
│   └── skip-splash-ad/
└── site/plugins/        产物，直接扔到 GitHub Pages
```

**打包是可复现的** —— zip 的时间戳、权限位、条目顺序、压缩级别全部固定，
同样的输入一定得到同样的 sha256。这不是洁癖：

| 不可复现的后果 | 用户看到什么 |
|---|---|
| 改了规则、重新打包、忘了提交 `index.json` | "文件内容与市场声明的哈希不一致，可能是下载途中被替换" |
| 每次 CI 重建都产生新文件 | CDN 缓存全部失效 |
| 没有稳定基线 | `--check` 模式无从实现 |

第一种情况最糟：**它会指控自己的源被黑了**，而真实原因只是有人漏提交了一个文件。
所以 `build_source.py --check` 该进 CI —— 产物与源不一致就退出码非 0。

> `build_source.py` 刻意**不做**完整校验（能力白名单、id 格式、禁止前缀）。
> 那些规则归 Kotlin 侧的 `PluginValidator` 管，已经有单测覆盖。
> 在 Python 里再实现一遍 = 同一套规则维护两份，漂移之后最糟的情况是
> 脚本放行了校验器会拒绝的插件 —— 用户拿到"打包成功、安装失败"。

### 3.3 检索

`MarketSearch.search()` 是**纯函数**，不涉及网络 —— 所以它能在没有 Android SDK 的
机器上完整测试。四种排序：相关度、名称、风险、更新时间。

相关度权重反映了用户的真实意图：

```
名称完全匹配 100 > 名称以关键词开头 80 > 名称包含 60
> ID 包含 40 > 描述包含 20 > 作者包含 10
```

**注意**：相关度相同的项之间是**真平局**，靠名称兜底排序决定先后 ——
那是任意的（中文按 Unicode 码位排，不是拼音）。界面上不要暗示"这是最匹配的"。

### 3.4 版本比较：一个必须手写的东西

```
"1.10.0" < "1.9.0"   ← 字典序会这么判，而且没有任何报错
```

后果是装了 1.9.0 的用户**永远收不到 1.10.0 的更新**，且不会有人发现。
所以 `SemanticVersion` 手写了 `compareTo`，还处理了预发布版本
（`1.0.0-beta < 1.0.0`，`beta.10 > beta.9`）。

### 3.5 安装流水线（五步，顺序即安全）

```
① 下载到内存        —— 不落盘。落盘再校验，中间有一段时间是"脏文件在盘上"
② 哈希校验          —— 必须在解压之前。解压一个未校验的包 = 直接执行攻击载荷
③ 安全解压到暂存区   —— zip slip / zip bomb 防护
④ 清单校验          —— 复用 PluginImporter 那套规则
⑤ 原子替换到正式目录 —— 先写暂存、再改名。中途失败不会留下半个插件
```

最容易被后来的人"优化"掉的是 ②→③ 的顺序：先解压再校验看起来更合理
（"解压出来才能看清单"），但那等于把攻击面提前了一整步。

**第 ③ 步的三层防护**（`PluginBundle` + `PluginInstaller.extractSafely`）：

1. **条目名白名单检查** —— 拦掉 `../`、绝对路径、`C:` 盘符、反斜杠、NUL 截断
2. **规范化路径复核** —— 字符串检查过完后，把 `canonicalPath` 再和根目录比一次
3. **边解压边算预算** —— 不在开头一次性检查，因为 zip 头里声明的 size 可以撒谎

### 3.6 诚实边界：这个市场担保不了什么

界面上**不能**出现"官方认证""安全插件"这类措辞。能做的只有三件事：

| 能做 | 做不到 |
|---|---|
| 显示每个插件来自哪个源 | 验证源本身是否善意 |
| 显示插件申请的每一项能力 | 验证插件的行为与声明一致 |
| 校验文件哈希（防传输途中的替换） | 防源自己分发恶意插件（哈希也是源给的） |

最后一行是关键：**哈希是源自己声明的**，源想给你什么哈希就给什么哈希。
要防这个需要作者签名（`signature` 字段，M2 再上），哈希替代不了。

所以界面上只能写"文件与源声明的一致"，不能写"已验证安全"。

---

## 四、应用的整体架构与主要界面

### 4.1 分层

```
┌──────────────────────────────────────────────────────────┐
│  app            Compose UI 界面层（5 个页面）               │
├──────────────────────────────────────────────────────────┤
│  plugin:api     插件契约 · 校验 · 市场索引 · 完整性校验      │  ← 零 Android 依赖
│  plugin:runtime 加载器 · 生命周期 · 能力代理 · 沙箱         │
│  plugin:rules   L1 规则包：选择器引擎、匹配器、动作执行       │
│  plugin:script  L2 JS 沙箱运行时                          │
├──────────────────────────────────────────────────────────┤
│  safety         六步安全检查 · 敏感内容匹配 · 审计           │  ← 零 Android 依赖
│  perception     ScreenSnapshot（树优先，截图兜底）           │
│  action         ActionExecutor 多通道（无障碍/Shizuku/IME）  │
│  agent          任务规划与执行循环                          │
│  provider:*     模型接入（OpenAI 兼容 / Anthropic / Gemini） │
│  keymgmt        API Key 加密存储（Keystore + AES-GCM）      │
├──────────────────────────────────────────────────────────┤
│  core:*         common / crypto / database / network        │
└──────────────────────────────────────────────────────────┘
```

**一条贯穿全项目的约束：`plugin:api` 与 `safety` 必须零 Android 依赖。**
这不是洁癖，是让最需要被验证的代码（校验、脱敏、匹配）能在任何机器上跑单测 ——
它们所在的 Gradle 模块如果声明了 `com.android.library`，没有 Android SDK 就编译不了，
于是只能靠肉眼读。现在这条约束换来了 **305 个单测**。

### 4.2 五个界面

**① 主页** —— 刻意做得**很空**。

这个应用真正的入口不是"和我聊天"，是**用户配置好能力之后去别的 App 里干活**。
做成一个对话框首页，等于暗示用户"这是个聊天软件"，而那恰恰是它和
豆包手机助手最大的区别。所以首页只放三件事：装插件（市场 / 本地导入）+ 管理已装插件。

**② 插件市场** —— 围绕三件事组织：

1. **把来源摊开**：每个卡片都显示"来自哪个源"。源不可信是用户自己的选择，
   但他有权知道自己在信任谁。
2. **把权限摊开**：直接列出插件申请的能力，**不折叠、不藏在小字里**。
3. **把异常摊开**：被拒绝的条目、加载失败的源、用不了的未知能力，
   全部显示在顶部横幅里。**悄悄过滤等于替用户做决定。**

**③ 本地导入** —— 用户明确要求"导入时明确提示风险由用户自行承担"。

一句"风险自负"写在角落的小字里，等于没写。用户点"同意"的成本太低，
低到不构成一个决定。所以这一页做了三件事：

1. 把插件能干什么**逐条摊开**，而不是笼统一句"插件可能有风险"
2. 说清**本体管不了什么** —— 用户得知道"没有签名校验"具体意味着什么
3. **高危插件要求手动打出「我已知晓风险」**，把点一下变成读一遍

状态机：

```
  IDLE ──选中文件──▶ INSPECTING ──┬─▶ READY ──输入确认词──▶ DONE
                                  ├─▶ REJECTED    （申请了禁止能力）
                                  └─▶ MALFORMED   （文件根本不是插件包）
```

三种失败结果**刻意分开**：用户看到"插件有问题"时的下一步动作完全不同 ——
REJECTED 该换来源（这个作者不老实），MALFORMED 该检查自己是不是选错了文件。
合成一个错误码，用户就只能瞎试。

**④ 已安装的插件** —— 装完之后回头看的地方。

这一页最重要的东西是一句实话：**M0 阶段这些插件一个都不会动。**

最省事的做法是摆一个"启用"开关让它看起来能用。但那是在骗用户 ——
他打开开关、回到微信、等了三分钟什么也没发生，然后会认定这个应用是坏的。
**一次这样的体验，比"还没有这个功能"糟糕得多。**
所以顶部横幅把状态说清楚（缺授权、缺执行引擎），页面上也不放任何点了没反应的控件。

其余按"回头看"的场景设计：

- 能力照旧**全部铺开**。装的时候看过一眼，不代表现在还记得。
- 风险角标用**和市场页完全相同的颜色**（组件共用 `ui/components/RiskUi.kt`）——
  用户是靠在市场页形成的颜色条件反射来判断的，两处配色一旦漂移，这道人工防线就废了。
- **卸载要二次确认**，且文案区分"从市场装的"和"自己导入的"：
  前者还能装回来，后者需要重新找到那个 `.pagent` 文件。用户有权在删之前知道这件事。
- **损坏的插件目录照样列出**（标成"无法识别"、可卸载），而不是跳过 ——
  "我装的插件从列表里消失了"会让人怀疑应用在偷删数据，那比"这个插件坏了"严重得多。

这一页顺带补上了一个真实缺口：在此之前，插件装完**没有任何地方能看到** ——
市场页能装、导入页能装，装完就人间蒸发。

**⑤ 订阅源** —— 这一页存在的理由比它看起来重要得多。

市场没有服务端，内容是若干静态 JSON。所以**「源」是这个应用里唯一的信任入口** ——
用户信任谁，取决于他订阅了谁。

在这一页做出来之前，用户只能用内置源，而那个地址**还没上线**。结果是：
打开市场、看到加载失败、然后**毫无办法**。设计文档里那句
"官方源挂了用户还能自己加源"完全落不了地 —— 一个漂亮的架构承诺，
因为缺一个界面而变成空话。

三件事：

1. **把"没有服务端"讲清楚**，而不是让用户以为背后有个官方商店在审核。
2. **只收 https，并解释为什么** —— 明文通道上中间人能把整个插件目录换成他自己的，
   而哈希校验防不住这个（哈希也是被篡改的目录给的）。
3. **告诉用户怎么自己搭一个源**。这是本应用"任何人都是源"的设计前提 ——
   不说，这个设计就只对会写代码的人成立。

顺带修掉一个逻辑洞：**内置源原本删不掉**。用户删掉之后，`ensureBuiltinSource()`
看到列表为空，下次进市场页又把它加回来了。用户会得出"这应用删不掉东西"的结论，
而一个删不掉的东西会让人怀疑它在后台做了什么。现在删除会留下标记，
并提供「恢复内置源」按钮 —— 删除必须是**可逆**的，否则它不该叫删除。

---

## 五、本轮实现与验证

### 5.1 单测：305 个，全绿

| 测试类 | 覆盖 |
|---|---|
| `PluginValidatorTest` | 身份、版本、级别、能力、目标、网络、入口、完整性、配置项校验 |
| `PluginMarketTest` | 语义化版本、更新检测、检索、筛选、排序、多源合并、展示字段透传 |
| `PluginImporterTest` | 导入四步流程、禁止能力拦截、未知能力兼容、畸形输入区分 |
| `PluginBundleTest` | zip slip 21 种变形、zip bomb 预算、常量自洽 |
| `ExampleSourceTest` | **用真实校验器验 `community-source/` 里的示例插件**（见 §5.6） |
| `SseParserTest` / `OpenAiCompatProviderTest` | SSE 解析、11 家厂商 profile、费用估算 |
| `LogSanitizerTest` | 密钥脱敏（含全大写变体） |
| `SensitiveDetectorTest` / `DefaultSafetyGuardTest` | 六步检查、归一化匹配、红线测试 |

### 5.2 本轮修掉的四个真 bug

**① 未知能力被判成 ERROR，导致整个插件从市场消失**

`inspectRawCapabilities()` 原本把"未知能力"判成 `ERROR`，于是
`MarketCatalog.merge()` 把整个条目当成恶意插件丢掉 —— 用户连"市场里有这么个插件"
都看不到，还以为是市场没收录。

而这个文件开头自己写着判据是「这个字段能不能被恶意利用」。
一个我们不认识的能力**无法被利用** —— `PluginHost` 根本不实现它，
调用只会失败。所以这是**兼容性问题，不是安全问题**。

改为 `WARNING` + 保留条目 + 在 `MarketEntry.unsupportedCapabilityIds` 里留痕。

**② 同一个概念，两条路径两种行为**

| 路径 | 字段类型 | 遇到未知 id |
|---|---|---|
| 市场目录 | `SubscriptionEntry.capabilities: List<String>` | 丢弃并记录 |
| 本地导入 | `PluginManifest.capabilities: List<PluginCapability>` | **解析失败** |

严格模式把**兼容性问题伪装成了格式错误**：用户看到"清单格式不正确"，
于是去找作者换版本 —— 而真正的原因是他的本体太旧，换版本没用。

修复：新增 `PluginCapabilityListSerializer`，宽容解析，未知 id 丢弃。
**关键点：宽容的是解析，不是校验。** 丢弃事实由 `inspectRawCapabilities()`
在反序列化之前独立捕获 —— 一个申请 `payment.pay` 的插件依然一步都不会少地被拦下。

**③ 一条测试断言了一个没有设计含义的实现细节**

`search("微信")` 里"微信自动回复"和"微信红包助手"相关度**完全并列**（都以关键词开头），
测试却断言了平局的兜底顺序。修的是测试，不是实现 ——
要测的是"名称命中整体高于作者命中"，而不是"并列项谁在前"。

**④ 界面在渲染两个契约里根本不存在的字段**

市场卡片一直在渲染 `entry.description` 与 `entry.author`，而
`SubscriptionEntry` 里**没有这两个字段** —— 于是 `toMarketEntry()` 只能传 `null`。
结果：用户在市场上永远看不到任何插件说明和作者，只有名字加几个能力标签。

更刺眼的是同一个代码库里，`PluginValidator` 还在警告作者
「没有填写插件描述，用户在市场上无法判断这个插件做什么」。
**一边要求作者写描述，一边市场没地方放描述。**

这类 bug 的特征是**不会报错**：编译器不管（`description` 是 `String?`，传 `null` 合法）、
测试不管（没有断言过透传）、运行时也不管（`?.let` 静默跳过）。它只会让
一个本来能帮用户判断"敢不敢装"的界面，退化成"一串不敢点的名字"。

修法：给 `SubscriptionEntry` 补上 `description` / `author` / `updatedAt`
（都带默认值，旧源 JSON 照常解析），`toMarketEntry()` 透传，
并补三条回归测试锁住它。

> **教训**：契约字段和界面用法之间没有任何自动检查。界面写 `entry.description`
> 时，如果契约里没有这个字段，编译器会报错 —— 但**契约里少一个可选字段、
> 界面读它、转换函数不传它**，这条链上没有任何一环会响。所以每加一个展示字段，
> 都要有一条"从源 JSON 一路走到界面能拿到的值"的测试。

### 5.3 构建状态：APK 已产出

| 项 | 状态 |
|---|---|
| JDK 17.0.13（Microsoft OpenJDK） | ✅ `D:\AndroidDev\jdk` |
| Gradle 8.13 | ✅ `D:\AndroidDev\gradle` |
| Android SDK cmdline-tools 19.0 | ✅ 已就位 |
| platforms;android-36 / build-tools;36.0.0 | ✅ 已安装 |
| 离线单测验证器 | ✅ `tools/verify/run_logic_tests.py` |
| 版本目录对账 | ✅ `tools/verify/check_version_catalog.py` |
| **模块依赖对账** | ✅ `tools/verify/check_module_deps.py` |
| 中文引号检查 | ✅ `tools/verify/check_kt_quotes.py` |
| AAR 门槛体检 | ✅ `tools/verify/check_aar_metadata.py` |
| 社区源产物对账 | ✅ `community-source/build_source.py --check` |
| `:app:assembleDebug` | ✅ **BUILD SUCCESSFUL** |
| **`./gradlew test`（全模块）** | ✅ **BUILD SUCCESSFUL** —— 本轮才第一次真正跑通 |

> ⚠️ 在补上模块依赖之前，`./gradlew test` **从来没有成功过** ——
> 它会先卡在 `:action` / `:agent` / `:provider:openai-compat` 的编译错误上。
> 详见 5.4 的坑 9–11。

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

| 属性 | 值 |
|---|---|
| 包名 | `com.pocketagent.debug` |
| 版本 | 0.1.0-m0（versionCode 1） |
| minSdk / targetSdk / compileSdk | 31 / 36 / 36 |
| 权限 | `INTERNET`、`ACCESS_NETWORK_STATE` —— **仅此两项** |
| 签名 | Android Debug（可直接侧载安装） |
| 大小 | 64,487,763 字节（约 61.5 MB） |

> 61 MB 是 debug 版的正常代价：未混淆、未裁资源、带调试符号。
> release 版经 R8 + 资源裁剪会显著缩小，但**需要先决定签名策略** ——
> 社区分发要求所有版本用同一个 keystore 签名（否则无法覆盖安装更新），
> 而这个 keystore 该由谁保管、要不要进仓库，是需要人来拍板的事。

> ⚠️ **别用文件时间戳判断 APK 是不是最新的。** `gradle.properties` 里开了
> `org.gradle.caching=true`，而 Gradle 从构建缓存恢复产物时会**保留原始
> 修改时间** —— 于是"重新构建过"和"文件是旧的"可以同时成立。
> 要确认产物是否反映了最新代码，比 **sha256**，不要比时间。

### 5.4 构建踩过的坑

前八个发生在首次 `assembleDebug`，第九到十一个是在那之后**才暴露出来**的。

| # | 报错 | 根因 | 处置 |
|---|---|---|---|
| 1 | `Your project path contains non-ASCII characters` | 工作区路径 `D:\手机agent开发` 含中文，AGP 在 Windows 上主动拒绝 | 命令行加 `-Pandroid.overridePathCheck=true`（不写进 `gradle.properties`，那是本地权宜之计） |
| 2 | `plugin is already on the classpath with an unknown version` | 根脚本漏声明 `kotlin.jvm`，而 kotlin-android/compose/serialization 会把 kotlin-gradle-plugin 顺带塞进 classpath | 补 `alias(libs.plugins.kotlin.jvm) apply false`。**规则：子模块会用到的每个插件，根脚本都要 `apply false` 声明一遍** |
| 3 | `Namespace '...openai-compat' is not a valid Java package name` | 生成器只把 `/` 换成 `.`，没处理连字符 | 新增 `namespace_for()`（**去掉**连字符而非换下划线，必须与源码包名一致） |
| 4 | `Failed to install platforms;android-36 (revision 2)` | 目录存在但为空 —— sdkmanager 实际没装完。管道喂许可证会卡住且**零输出** | 重跑 sdkmanager。**教训：零输出 ≠ 没干活**，它八分钟后其实装好了。验证要看 `source.properties` 而非目录是否存在 |
| 5 | `resource string/accessibility_service_description not found` | `res/xml/agent_accessibility_service.xml` 是**孤儿资源**（Manifest 里已注释掉），但 AAPT2 会编译 `res/` 下**每一个** XML | 补上缺失的 string。**教训：注释掉 Manifest 里的引用，不会让那个 XML 文件消失** |
| 6 | `Dependency 'androidx.compose.ui:ui-android:1.12.0' requires ... version 37 or later` | Compose BOM `2026.08.00` 锁的是 Compose 1.12.x，而 1.12.0 把门槛抬到 `minCompileSdk=37` / `minAGP=9.1.0`，超出本项目工具链 | BOM 降到 `2026.06.01`（Compose 1.11.4，门槛 35/8.6.0）。用 `check_aar_metadata.py` 确认过全部依赖 |
| 7 | `Unresolved reference 'bundle'` | `installVerifiedBundle(entry)` 里写了 `extractSafely(bundle, …)`，但这个作用域只有 `entry` | 改为 `entry.bytes` |
| 8 | `Unresolved reference 'Stage'` ×8 | `Stage` 嵌套在 `ImportUiState` 内，`ImportScreen` 用了限定名而 `ImportViewModel` 用了裸名 | 统一为 `ImportUiState.Stage` |
| 9 | `'bounds' hides member of supertype 'ElementRef' and needs an 'override' modifier` | 接口声明了 `val bounds: Rect?`，两个实现类各自带一个非空 `bounds` 构造参数却没写 `override` | 补 `override`（`val` 的类型可以协变收窄，`Rect?` → `Rect` 合法） |
| 10 | `Unresolved reference 'perception'` / `'action'` ×5 | `:agent` 用了 `:action` 与 `:perception` 的类型，但 `build.gradle.kts` 里没声明这两个项目依赖。**生成器只会硬编码一条 `:core:common`** | 补依赖；同时给生成器加了 `PROJECT_DEPS` 表，新模块不会重蹈覆辙 |
| 11 | **每个测试类都报 `ClassNotFoundException`** | 见下 |

**第 5–8 个坑的共同点：它们都不在"构建配置"里，而在"代码与资源本身"。**
前四个修完只是让构建**能跑到编译阶段**；真正的问题要等它跑到了才会露出来。
所以"构建失败"这件事要分两层看：**工具链的问题**和**代码的问题**，
混在一起排查会得出错误的结论（比如把第 6 个当成 AGP 版本太旧，去升级 AGP，
那就掉进更大的坑了 —— 该降的是 Compose，不是升 AGP）。

**第 9–10 个坑的共同点更值得记：它们藏在"从来没被编译过的模块"里。**

`tools/verify/run_logic_tests.py` 只编译零 Android 依赖的那几个模块
（provider / core / safety / plugin:api）。`:action` 和 `:agent` **从来不在里面** ——
所以它们的编译错误可以一直躺着，直到有人真的跑一次 `./gradlew test`。

> **教训：离线单测跑绿 ≠ 代码能编译。** 它覆盖的是纯逻辑模块。
> 改完跨模块的代码，必须跑一次 Gradle 才能确认。

#### 坑 11：编码错配导致全部测试"消失"

现象极具误导性 —— **每一个**测试类都报

```
java.lang.ClassNotFoundException: com.pocketagent.plugin.api.PluginBundleTest
```

而 `.class` 文件就在磁盘上，`javap` 读得出来，堆栈里还能看到 JUnit 自己的类。
这三个事实凑在一起，几乎必然把人引向"测试代码有问题"。

真实原因是一处**编码错配**：

| 环节 | 用的编码 |
|---|---|
| Gradle 把测试 worker 的 classpath 写进 `@argfile` | 守护进程的 `file.encoding` |
| JVM 读 `@argfile` | `sun.jnu.encoding`（**平台编码**，`-D` 改不动） |

`gradle.properties` 原本给守护进程设了 `-Dfile.encoding=UTF-8`，而本机
`sun.jnu.encoding = GBK`。于是一个按 UTF-8 写、一个按 GBK 读：

```
直接传参：    D:/手机agent开发/android/...        → 加载成功
UTF-8 argfile：D:/鎵嬫満agent寮?鍙?/android/...  → ClassNotFoundException
```

（上面这组对照是实测的，不是推断。验证方法：写一个只做
`Class.forName(...)` 的探针类，分别用两种方式启动它。）

JUnit 的 jar 在纯 ASCII 路径（`~/.gradle/caches`）下，所以它加载正常 ——
这也解释了为什么"有些类能找到、偏偏测试类找不到"。

**修法：把 `-Dfile.encoding=UTF-8` 从 `org.gradle.jvmargs` 里去掉。**
去掉之后守护进程与 worker 都用平台编码，两边自然一致：
Linux（UTF-8）写 UTF-8 读 UTF-8，中文 Windows（GBK）写 GBK 读 GBK。
报告文件不受影响 —— Gradle 写 XML 报告时显式用 UTF-8，实测中文测试名完好。

> 这个坑只在**项目路径含非 ASCII 字符**时触发。放到 `D:\pocketagent`
> 这类纯 ASCII 路径下就不会出现 —— 但依赖"路径是 ASCII"来规避一个编码 bug，
> 不如直接把两边的编码对齐。

### 5.5 新增的两个体检工具

构建报错要等四分钟，而下面这两个脚本**在下载依赖之前**就能把问题问出来：

```bash
# ① 版本目录对账：libs.* 引用是否都有定义
python tools/verify/check_version_catalog.py android

# ② AAR 门槛体检：这个依赖要求多高的 compileSdk / AGP
python tools/verify/check_aar_metadata.py --bom 2026.06.01
python tools/verify/check_aar_metadata.py androidx.compose.ui:ui-android:1.12.0
```

**② 特别值得留着。** 它直接下载 aar、读出里面的
`META-INF/com/android/build/gradle/aar-metadata.properties`，把 `minCompileSdk`
和 `minAndroidGradlePluginVersion` 打出来。升任何 AndroidX 依赖前先跑一遍，
就不会再出现"升了个 BOM，构建炸了，然后花二十分钟猜是哪儿不对"。

### 5.6 示例插件由真实校验器把关（`ExampleSourceTest`）

[`community-source/`](../community-source/) 里的示例插件是给所有人抄的模板。
如果它只是"看起来合法" —— 字段名对、缩进整齐、读起来像那么回事 ——
那它就是个假货：用户照着抄，抄出来的插件点安装会报错，
而报错信息不会告诉他"你抄的那个例子本身是坏的"。

所以 `ExampleSourceTest` **不重新实现一套校验规则**，而是直接调用客户端的
`PluginValidator` 与真实的反序列化器。规则只有一份，校验器改了测试立刻跟着变。

它检查七件事：

| 检查 | 为什么 |
|---|---|
| 至少有一个示例插件 | 防止目录找错之后，下面所有断言在**空集合**上"全部通过" |
| 清单能通过校验、且无意外警告 | 用严格解析器，与客户端装插件时完全一致 |
| `entry` 指向的文件真的存在 | 装上去会是"加载不了的插件" |
| `entry` 通过 `isSafeEntryName` | 一个 entry 合法但文件名不合法的组合，会在解压阶段才炸 |
| **两份 manifest 完全一致** | 漂移不会报错，只会让市场显示的版本与实际行为对不上 |
| 能点击的插件每条规则都有 `excludeConditions` | 误点"确认付款"比不能用糟糕一万倍 |
| id 不占官方保留前缀 | 示例是模板，抄的人会连 id 一起抄走 |

**唯一豁免的警告是 `sha256` 缺失**，理由见附录 —— 那个字段对 L1 插件
结构上就填不了（`rules.json` 内嵌 manifest，而 manifest 又要哈希 `rules.json`）。

> **测试本身也被验证过。** 我故意把 `plugin.json` 的描述改了一个字、
> 让它与 `rules.json` 内嵌的 manifest 不一致，确认测试会失败并报出
> "focus-guard：plugin.json 与 rules.json 里的 manifest 不一致"。
> 一个从不失败的检查等于没有检查 —— 这个教训在
> `check_kt_quotes.py` 上已经吃过一次（第一版 135 报 1 中）。

---

## 附：给插件作者的最小工作流

**别从零开始。** 直接复制 [`community-source/plugins-src/_template/`](../community-source/plugins-src/_template/)，
它把清单、规则、配置项声明的写法都摆好了，而且**不会**被当成正式插件打包
（以 `_` 开头的目录会被 `build_source.py` 跳过）。

```
1. 复制模板：
     cp -r community-source/plugins-src/_template community-source/plugins-src/my-plugin

2. 改 plugin.json（清单）与 rules.json（L1 规则）
     ⚠️ 这两个文件里各有一份 manifest，必须**逐字段相同**。
        rules.json 是自包含的（运行时读），plugin.json 是安装时读的。
        漂移了不会报错，只会让市场显示的版本与实际行为对不上。

3. 打包 + 生成索引：
     python community-source/build_source.py

4. 确认产物与源一致（改完必跑）：
     python community-source/build_source.py --check
     python tools/verify/run_logic_tests.py      # 含 ExampleSourceTest

5. 分发：把 site/plugins/ 推到静态托管，源的地址就是 .../plugins/index.json
```

清单最小示例：

```jsonc
{
  "id": "community.example.demo",       // 反向域名，至少三段，小写，段内用连字符
  "name": "示例插件",
  "version": "1.0.0",                   // 必须语义化版本
  "apiVersion": 1,                      // 高于本体支持的值会被拒绝加载
  "level": "L1_RULES",
  "capabilities": ["screen.read", "action.click"],
  "entry": "rules.json",                // L1 必须 .json，L2 必须 .js
  "targetApps": ["com.example.app"],    // 会模拟操作却不限定目标 → 警告
  "description": "这个插件做什么，写清楚。没有描述的市场插件没人敢装。"
}
```

> ⚠️ **清单里的 `sha256` 字段对 L1 插件是填不了的** —— 这不是"应当填而没填"，
> 是结构上不可能：`rules.json` 内嵌了整份 manifest，而 manifest 的 `sha256`
> 又该去哈希 `rules.json`，循环依赖。
>
> 真正起作用的哈希在**源索引**里（覆盖整个 `.pagent`，解压前校验），
> 由 `build_source.py` 生成。清单里那个字段目前只有本地导入路径会看到一条
> "没有提供内容哈希"的警告 —— 而本地导入本来就不靠校验兜底，靠用户知情。

> ⚠️ 清单里**不要**出现 `payment.*` / `key.*` / `crypto.*` / `system.*` 能力。
> 出现即拒绝安装，并会在市场上被标记为"该源正在分发申请禁止能力的插件"。

> ⚠️ **只要插件能模拟操作，每条规则都必须写 `excludeConditions`。**
> 至少排除「支付」「转账」这类文本。`ExampleSourceTest` 会强制检查这一条 ——
> 一个误点「确认付款」的插件，比一个根本不能用的插件糟糕一万倍。
