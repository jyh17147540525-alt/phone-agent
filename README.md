# PocketAgent

安卓上的 AI 伴侣应用。人格、身体、记忆、时刻感都在本地跑，模型只负责生成语言。
自带 Key（BYOK），没有服务端。

**An Android AI companion app.** Personality, body state, memory and time awareness run on
device; the model only produces language. Bring your own API key, no backend.

![license](https://img.shields.io/badge/license-GPL--3.0-blue)
![platform](https://img.shields.io/badge/platform-Android%2010%2B-green)
![server](https://img.shields.io/badge/server-none-lightgrey)

---

## English

### What it is

An Android app that keeps a character's state on the device and uses a language model to voice it.
Three ways to use it: **roleplay chat**, **workbench** (task execution), and **presence** (the
character has its own day and may message you first). Chat histories are separated per mode.

### What it does

- **Character state in code, not in a prompt**: four hormone axes (dopamine, serotonin, oxytocin,
  cortisol) with hourly decay, circadian energy curve, optional menstrual cycle, structured health
  report, willingness level that can decline a request, and a world book that separates fixed
  settings from facts that may only be appended.
- **33 built-in tools**, mounted on demand: screen reading and taps, file read/write, Office
  generation (`pptx` / `docx` / `xlsx`), image conversion, PDF pages and image-to-PDF, zip
  packing with zip-slip protection, chart rendering to PNG, web search and fetch (private and
  loopback addresses blocked by default), reminders, text-to-speech and speech-to-text.
- **Linux workspace** with three backends picked in order: root container (chroot), Termux,
  built-in Python. The root tier can install packages and cut the network; `/work` is bind-mounted
  to the app's workspace.
- **Voice replies**: cloud or self-hosted TTS, audio written to disk before the bubble appears,
  clips up to 60 seconds, multiple clips per reply, plain text underneath, in-app preview for
  produced files.
- **Memory**: extraction, scoring, relevance recall, three fidelity levels; emotional charge slows
  decay.

### Architecture

- Pure Kotlin logic modules (`:personalogic`, `:memorylogic`, `:filelogic`, `:voicelogic`,
  `:rootlogic`, `:agentlogic`, `:mcp`, `:modelrouter`, `:keymgmt`, ...) with no Android framework
  dependency, so they run as plain JVM tests.
- Compose UI in `:app`. Provider layer for OpenAI-compatible, Anthropic, Gemini, local and TTS
  endpoints. No server component.

### Requirements and build

Android 10+ (targetSdk 36). Root tier needs Magisk / KernelSU / ReSukiSU authorization; the app
works without root through the built-in Python engine and every office, image and web tool.

```bash
cd android
./gradlew :app:assembleDebug
# output: android/app/build/outputs/apk/debug/app-debug.apk (~143 MB debug, no minification)
```

Third-party uses: `run_logic_tests.py` for the offline suites, `verify_all.py` for the 14 checks.

License: GPL-3.0.

---

## 中文

### 这是什么

安卓上的伴侣应用。角色的状态在本地计算，语言模型只负责把它说出来。
三种用法：**角色聊天**、**工作台**（干活）、**在场**（角色有自己的日常，可能主动发消息）。
三种模式的历史互不混用。

### 能做什么

**状态写在代码里，不写在提示词里**

- 多巴胺 / 血清素 / 催产素 / 皮质醇四条轴，按小时衰减，由事件推动
- 作息与睡眠债、一天里的精力曲线
- 可选周期，只影响精力与部分激素，不涉及血清素
- 一份结构化体检报告，外加报告里不会写的部分：痛阈、怕针、旧伤、气味偏好
- 意愿值低于阈值可以拒绝（设置里有开关，默认不开）
- 世界书：设定与事实分开写入，事实只能追加，不能覆盖设定

**33 个内置工具，按需挂载**

- 屏幕与手机：读屏、截图、点击、滑动、输入、按键、改设置、停用应用
- 文件：读取、写入、列目录
- Office：生成 `pptx` / `docx` / `xlsx`（真实 OOXML，不是占位文件）
- 图像与文档：转格式、缩放、PDF 转图、图片合并成 PDF
- 压缩包：打包、列出、解开（带 zip-slip 防护）
- 图表：柱状与折线，纯 Kotlin 画 PNG
- 联网：搜索与网页抓取，默认拒绝本机与内网地址，可在设置里开放
- 提醒与语音：闹钟提醒、朗读、听写（只在要求时开麦克风，时长上限 20 秒）

**Linux 工作区**

三档后端按序取用：root 容器（chroot）→ Termux → 内置 Python。
root 档能装软件包、能真正断网，`/work` 与工作区双向绑定；内置引擎只跑 `python3 -c`，
仅标准库。根文件系统可在设置里一键安装（Alpine，约 3.6 MB）。

**语音**

云端或自建 TTS，回复的音频先落盘再上屏，点击播放本地文件，不重复计费。
单条上限 60 秒，可一次多条，正文仍是纯台词，语音标签不会显示在界面上。
产出的文件以卡片列出，可在应用内预览图片、PDF 与 Office 文字。

**记忆**

抽取、打分、相关性检索，三级保真（精确 / 模糊 / 遗忘）；情绪强度会拖慢遗忘速度。

### 架构

- 纯 Kotlin 逻辑层：`:personalogic` `:memorylogic` `:filelogic` `:voicelogic` `:rootlogic`
  `:agentlogic` `:mcp` `:modelrouter` `:keymgmt` 等，不依赖 Android 框架，可在普通 JVM 上跑测试
- Compose 界面层：`:app`
- Provider 层：OpenAI 兼容 / Anthropic / Gemini / 本地模型 / TTS
- 没有服务端，Key 与数据只在本机

### 系统要求与安装

Android 10 及以上（targetSdk 36）。root 档需要 Magisk / KernelSU / ReSukiSU 授权，
应用分身需单独授权。不 root 也能用：内置 Python 引擎与全部办公、图像、联网工具都可用。

```bash
cd android
./gradlew :app:assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk（约 143 MB，debug 未混淆）
```

从 Releases 安装，或自行构建后在应用内填写 Key（OpenAI 兼容、Anthropic、Gemini 任选）。

### 安全边界

- 工具按权限档分级，默认档位只给读取类能力
- 联网工具默认拒绝本机与内网地址，包含云元数据端点；重定向每一跳重新校验
- 解压前做路径规范化，拒绝越界路径
- 语音标签只送往语音合成，界面文本会切干净
- 能做什么由档位决定，不由提示词或免责声明决定

### 隐私

没有账号、没有遥测、没有云端同步。对话、记忆与语音缓存都在应用私有目录。
出网只有两类：你的模型请求，以及你明确触发的联网工具。

### 开发与验证

```bash
# 纯逻辑测试，不需要手机或模拟器
python tools/verify/run_logic_tests.py

# 全量检查：构建、测试、命名、行尾、提示词卫生等 14 项
python tools/verify/verify_all.py
```

当前状态：离线测试 4000+ 条全绿，全量检查 14/14。

状态机（激素、意愿、记忆衰减）与协议解析（provider、工具参数、压缩包路径）的改动需要配测试。

### 参与贡献

Issue 与 PR 都欢迎。提交前请跑上面两条命令；涉及真机行为的改动，请在 PR 里写清验证方法。

### 许可证

GPL-3.0，详见 [LICENSE](LICENSE)。

### 免责声明

本项目按现状提供，不附任何担保。用它在自己设备上读写文件、执行命令的风险由使用者承担。
不要用它侵犯他人隐私或绕过他人设备的授权。
