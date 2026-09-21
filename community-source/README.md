# PocketAgent 社区源

[English](README.en.md) | **简体中文**

这是一个**插件源**。它不是一个网站、不是一个服务，只是几个静态文件。

PocketAgent 的"插件市场"没有服务端。所谓市场，就是客户端去拉取用户订阅的
**若干个静态 JSON 地址**，合并、检索、展示。本目录就是其中一个源的完整脚手架 ——
你可以直接拿它去架自己的源，也可以照着它理解市场是怎么运转的。

```
  客户端                          静态托管（GitHub Pages / 对象存储 / 任何能放文件的地方）
  ┌──────────┐    GET index.json    ┌──────────────────────────────┐
  │ 市场页    │ ──────────────────►  │  index.json                  │
  │          │                      │  skip-splash-ad-1.0.0.pagent │
  │          │    GET *.pagent      │  focus-guard-1.0.0.pagent    │
  │          │ ──────────────────►  └──────────────────────────────┘
  └──────────┘
```

> **为什么不做服务端？** 三个理由，按重要性排序：
> 1. **没有审核权**。任何人架源都不需要经过我们，我们也不承担内容审核义务
> 2. **没有单点**。官方源挂了，用户自己加的源照常工作
> 3. **零成本、零运维**，而且没有账户体系 —— 浏览和下载都不需要登录
>
> 代价是**没有中心化的下架能力**。一个源如果开始分发恶意插件，客户端只能：
> ① 把违规条目**明确展示**为"被拒绝"（不是悄悄过滤）② 靠内置黑名单。
> 所以源的声誉完全由源自己负责 —— 这也意味着**你架源就是在给自己背书**。

---

## 目录结构

```
community-source/
├── README.md                  ← 你正在看的这份
├── build_source.py            ← 打包 + 生成 index.json
├── plugins-src/               ← 插件源文件（人写这个）
│   ├── _template/             ← 模板，以 _ 开头 → 不参与打包
│   ├── focus-guard/
│   │   ├── plugin.json        ← 清单
│   │   └── rules.json         ← 规则包（L1 的入口文件）
│   └── skip-splash-ad/
│       ├── plugin.json
│       └── rules.json
└── site/plugins/              ← 生成物（脚本写这个，可直接托管）
    ├── index.json
    ├── focus-guard-1.0.0.pagent
    └── skip-splash-ad-1.0.0.pagent
```

`plugins-src/` 是源，`site/` 是产物。**只改前者，后者由脚本生成。**

---

## 快速开始

```bash
cd community-source

# 打包 + 生成 index.json
python build_source.py

# 改完源之后，确认产物跟上了（CI 里跑这个）
python build_source.py --check

# 如果最终域名不是默认那个，改掉下载地址前缀
python build_source.py --base-url https://your-name.github.io/my-plugins
```

加一个插件只要三步：

```bash
cp -r plugins-src/_template plugins-src/my-plugin     # ① 复制模板
vim plugins-src/my-plugin/plugin.json                 # ② 改清单与规则
python build_source.py                                # ③ 打包
```

---

## 发布到 GitHub Pages

设你的 Pages 地址是 `https://<用户名>.github.io/<仓库名>`：

```bash
# ① 建一个仓库，比如 pocketagent-plugins
# ② 把 site/plugins/ 的内容推到仓库根目录下的 plugins/
mkdir -p /tmp/pub && cp -r site/plugins /tmp/pub/
cd /tmp/pub && git init && git add -A && git commit -m "publish source"
git remote add origin git@github.com:<用户名>/pocketagent-plugins.git
git push -u origin main

# ③ 仓库 Settings → Pages → Source 选 main 分支 /(root)
```

完成后，源的地址就是：

```
https://<用户名>.github.io/pocketagent-plugins/plugins/index.json
```

把它填进应用的**订阅源管理**页即可。想让别人也能用，就把这个地址发出去。

> ⚠️ **如果要做官方内置源**，地址必须与客户端里的常量完全一致 ——
> 见 `android/app/src/main/kotlin/com/pocketagent/data/AppContainer.kt`
> 的 `BUILTIN_SOURCE_URL`（当前是 `https://pocketagent-community.github.io/plugins/index.json`）。
> 对不上的话，用户装好应用打开市场只会看到「源加载失败」。

---

## 源索引的格式（`index.json`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | ✅ | 源的名字，会显示在客户端 |
| `author` | string | | 维护者 |
| `updatedAt` | string | | 最后打包日期 |
| `apiVersion` | int | ✅ | 当前为 `1`。**高于客户端支持的版本会整个源被拒** |
| `plugins` | array | ✅ | 插件条目列表 |

每个 `plugins[]` 条目：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | ✅ | 必须与包内清单的 `id` 一致 |
| `name` | string | ✅ | |
| `version` | string | ✅ | 语义化版本 |
| `level` | string | ✅ | `L1_RULES` / `L2_SCRIPT` / `L3_NATIVE` |
| `capabilities` | string[] | ✅ | 能力 id 列表，见下表 |
| `downloadUrl` | string | ✅ | **绝对** https 地址 |
| `sha256` | string | ✅ | **整个 `.pagent` 文件**的哈希，64 位十六进制 |
| `targetApps` | string[] | | 生效的应用包名。留空意味着对所有应用生效，客户端会警告 |
| `description` | string | | **会显示在市场上。别留空** —— 用户靠它决定装不装 |
| `author` | string | | 会显示在市场上 |
| `updatedAt` | string | | 插件最后更新日期 |
| `signature` | string | | 作者签名（机制尚未落地） |

> ⚠️ `sha256` 算的是**整个 `.pagent` 文件**的哈希，不是里面某个文件的。
> 客户端在**解压之前**先校验它（`PluginInstaller` 的"顺序即安全"），
> 所以这里填错 = 所有插件都装不上，而且报错会说"可能是下载途中被替换"。
> **别手写这个值，让 `build_source.py` 生成。**

---

## 插件包的格式（`.pagent`）

就是一个 zip，但有几条硬性约束：

- `plugin.json` 必须在**包的根目录**，不能套一层目录。
  套了的话安装会失败并报"插件包里没有找到 plugin.json"。
- 条目名只允许正斜杠，不能有 `..`、不能以 `/` 开头、不能有盘符或反斜杠。
  这是 zip slip 防护，客户端会逐条检查。
- 条目数 ≤ 512，解压后总大小 ≤ 32 MB，压缩比 ≤ 200。
  正常插件不到 2 KB，这些限制只对压缩炸弹有意义。

---

## L1 规则包的格式（`rules.json`）

L1 是声明式规则包，不执行任何代码。它是生态主力（预期占 80%）。

```jsonc
{
  "manifest": { /* 与 plugin.json 完全相同的清单 */ },
  "rules": [
    {
      "id": "skip-splash",              // 规则 id，在插件内唯一
      "name": "点掉「跳过」按钮",         // 会显示在执行日志里
      "enabled": true,
      "priority": 100,                  // 数值越大越先执行
      "match": {
        "packageName": "com.example.app",   // 可选。见下方说明
        "activity": "com.example.app.MainActivity",  // 可选，支持前缀匹配
        "conditions": [ /* 必须全部满足 */ ],
        "excludeConditions": [ /* 任一满足则不匹配 —— 安全设计的关键 */ ]
      },
      "actions": [ /* 按顺序执行 */ ],
      "constraints": { /* 本体强制，插件无法覆盖 */ }
    }
  ]
}
```

### ⚠️ `manifest` 为什么要写两遍

因为 `plugin.json` 是**安装时**读的（用于风险告知页），而 `rules.json` 是**运行时**读的。
后者被设计成自包含，所以内嵌了一份清单。

**两份必须逐字节一致。** 漂移的后果是：市场上显示版本 1.0.0，
装上去实际跑的是 1.0.1 的规则 —— 而没有任何一处会报错。
`ExampleSourceTest` 会在打包前把这件事查出来。

> 这是当前契约里的一处冗余，M1 实现执行引擎时会一并收掉。

### 条件类型

| 类型 | 参数 | 说明 |
|---|---|---|
| `nodeExists` | `selector`, `timeoutMs` | 屏幕上存在匹配的节点 |
| `textContains` | `value` | 屏幕任意位置出现该文本 |
| `textEquals` | `value` | 精确匹配 |
| `activityIs` | `value` | 当前 Activity |
| `packageIs` | `value` | 当前包名 |
| `timeBetween` | `startHour`, `endHour` | 时间范围，0–23 |

### 动作类型

| 类型 | 参数 | 需要的能力 |
|---|---|---|
| `click` | `selector` 或 `x`/`y` | `action.click` |
| `longPress` | `selector`, `durationMs` | `action.click` |
| `inputText` | `text` | `action.input` |
| `scroll` | `direction`, `distancePx` | `action.gesture` |
| `swipe` | `fromX/Y`, `toX/Y`, `durationMs` | `action.gesture` |
| `back` / `home` | — | `action.gesture` |
| `openApp` | `packageName` 或 `deepLink` | `app.launch` |
| `wait` | `ms` | 无 |
| `notify` | `title`, `content` | `notification.post` |
| `callLlm` | `prompt`, `model` | `llm.call`（**会花用户的钱**） |

### 约束（`constraints`）

本体强制执行，插件改不动。写松了也不会生效，但**写紧了是给自己省事**：

| 字段 | 默认 | 说明 |
|---|---|---|
| `maxTriggersPerMinute` | 6 | 每分钟最多触发几次 |
| `cooldownMs` | 5000 | 两次触发的最小间隔 |
| `requireScreenOn` | true | 要求屏幕点亮 |
| `maxActions` | 20 | 单次执行的最大动作数 |

### 选择器语法

CSS 风格，空格表示"同一个节点上同时满足"，`>` 表示"子节点"：

```
vid:com.tencent.mm:id/menu          按资源 ID
text:发送                            精确文本
text^:广告                           文本前缀
text$:完成                           文本后缀
text*:确认                           文本包含
desc:搜索                            内容描述
class:android.widget.Button          类名
[clickable=true]                     属性过滤
```

组合示例：

```
vid:com.tencent.mm:id/menu > [clickable=true] text^:广告
```

> **只用文本选择器比只用坐标稳得多。** 坐标会随分辨率、字体大小、
> 系统版本变化，而文本通常不会。红米 K60 是 3200×1440 的 2K 屏，
> 你在别的机型上量出来的坐标在这台机器上必然偏。
>
> 反过来，**纯文本选择器在同一个屏幕上出现多处时会误点**。
> 能加上层级限定（`>`）就加上。

---

## 可用能力

L1 规则包能用的：

| id | 说明 | 风险 |
|---|---|---|
| `screen.read` | 读取屏幕上的文字和按钮 | 高 |
| `action.click` | 替你点击 | 高 |
| `action.input` | 替你输入文字 | 高 |
| `action.gesture` | 滑动、长按 | 中 |
| `app.launch` | 打开应用 | 低 |
| `notification.post` | 发通知 | 低 |
| `llm.call` | 调用用户的模型（**花用户的钱**） | 中 |

L1 用不到的（声明了会得到一条警告，因为那是多余的权限）：
`screen.capture`、`notification.read`、`clipboard.read`、`clipboard.write`、
`storage`、`network.request`。

**永久不开放**，声明了直接拒绝安装：
`payment.*`、`key.*`、`crypto.*`、`system.*`

---

## 写插件的几条约定

**1. 点击类规则必须写 `excludeConditions`。**
只要插件能点击，就必须在规则里排除支付、转账、收银台、验证码、密码、免密
这类页面。这不是可选项 —— `ExampleSourceTest` 会强制检查。
一个误点"确认付款"的插件，比一个不能用的插件糟糕一万倍。

**2. 能力要申请得刚好。**
多申请一个用不到的能力，用户就会多看到一个警告弹窗，然后开始犹豫要不要装。
L1 规则包声明 `storage` 或 `network.request` 一定会被问"你要这个干什么"。

**3. 描述要写具体。**
"这是一个插件" 和 空着没区别。写清：做什么、在什么情况下会动屏幕、
不会做什么。市场页面上只有这段话能帮用户判断。

**4. 目标应用要限定。**
`targetApps` 留空意味着对所有应用生效，客户端会给出额外警告，
用户会犹豫。除非确实需要，都填上。

**5. `version` 用语义化版本。**
客户端靠它判断有没有更新。注意 `1.10.0 > 1.9.0` —— 是按数字段比的，
不是按字符串比的，所以别写成 `1.9.0` 之后就跳到 `2.0.0` 来"避免问题"。

---

## 关于两个示例插件

**它们的规则是真实可用的，但选择器需要你在真机上校准。**

我没有你的手机，也没法确认微博、知乎、B 站这些应用当前版本的
"跳过"按钮长什么样。示例用的是**纯文本选择器**（`text*:跳过`），
这类选择器对版本变化相对不敏感，但不保证在所有版本上都命中。

校准方法：

1. 先把插件装上并启用
2. 打开目标应用，看执行日志里规则有没有触发
3. 没触发 → 用客户端的"查看屏幕节点"功能找到那个按钮的实际文本或资源 ID
4. 改 `rules.json` 里的选择器，重新打包

**没命中时什么都不会发生**，不会误点。这是刻意的设计：
匹配不到就不动作，比"猜一个位置点下去"安全得多。

---

## 已知的未定问题

这些是**真的还没定**，不是忘了写：

| 问题 | 现状 |
|---|---|
| `timeBetween` 的区间是开是闭 | 未定义。`startHour=8, endHour=22` 是否包含 8 点和 22 点整，实现时才会确定 |
| `timeBetween` 跨午夜 | **未定义**。`startHour=23, endHour=6` 是"23 到次日 6 点"还是空区间，没实现也没测。**暂时不要写跨午夜的区间** |
| `signature` 验签机制 | 字段预留了，客户端还不验。市场条目的信任等级只会是"未签名" |
| L2 脚本沙箱 | 契约里有 `L2_SCRIPT`，执行引擎未实现。现在只能写 L1 |
| L3 原生插件 | 明确不开放，声明了直接拒绝安装 |
| 规则的 `priority` 冲突 | 同优先级时按什么顺序执行，未定义 |

---

## 校验

改完源之后跑：

```bash
python build_source.py --check          # 产物是否与源一致
python tools/verify/run_logic_tests.py  # 含 ExampleSourceTest，会校验示例插件
```

`ExampleSourceTest` 做的是**真校验**，不是走过场 —— 它调用客户端的
`PluginValidator` 和真实的反序列化器，逐条检查示例插件：

- 清单能被解析（用客户端的严格解析器，不是 Python 的 `json.loads`）
- 校验无 ERROR（警告会打印出来）
- `plugin.json` 与 `rules.json` 内嵌的 manifest 完全一致
- `entry` 指向的文件真的在包里
- 能力都在 L1 可用范围内
- **凡是能点击的插件，每条规则都有 `excludeConditions`**

这几条里任何一条挂了，都会在**你打包之前**暴露出来。

---

## 许可

本目录下的插件示例采用 GPL-3.0（与本项目一致）。
你架自己的源、写自己的插件，可以选任何许可 —— 但请在清单里写明。
