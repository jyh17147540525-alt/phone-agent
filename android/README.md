# PocketAgent — Android 工程骨架

[English](README.en.md) | **简体中文**

> 手机 AI 智能体助手（BYOK 版）
> 本目录是 M0 阶段的工程骨架，包含 Gradle 配置与核心接口契约（真实可编译代码）。
> **注意**：现在能构建出可安装的 debug APK 了。缺的是感知层、执行层与 agent 循环 ——
> 见根目录 [README](../README.md)。

## 模块结构

```
android/
├── settings.gradle.kts              模块注册与仓库配置
├── build.gradle.kts                 根构建脚本（插件统一声明）
├── gradle.properties                全局构建属性
├── gradle/libs.versions.toml        版本目录（依赖集中管理）
│
├── app/                             ★ 应用壳：UI、导航、DI 装配
│
├── core/                            ── 基础设施层（无业务逻辑）
│   ├── common/                      工具类、Result 封装、调度器
│   ├── crypto/                      Keystore 封装、加解密、安全存储
│   ├── database/                    Room + SQLCipher、DAO、迁移
│   └── network/                     OkHttp、SSE 解析、日志脱敏
│
├── provider/                        ── 模型接入层
│   ├── api/                         LlmProvider 抽象与数据模型（零依赖）
│   ├── openai-compat/               OpenAI 及兼容厂商（覆盖 8+ 家）
│   ├── anthropic/                   Claude Messages API
│   ├── gemini/                      Gemini generateContent
│   └── local/                       Ollama / LM Studio / llama.cpp
│
├── perception/                      ── 感知层
│   无障碍树读取、MediaProjection 截图、OCR、ScreenSnapshot 统一结构
│
├── action/                          ── 执行层（多通道）
│   ActionExecutor 抽象 + A11y / Shizuku / IME / OverlayPrompt 四通道实现
│
├── agent/                           ── 决策层
│   AgentOrchestrator（规划）+ Grounder（定位）+ Verifier（校验）+ Memory
│
├── safety/                          ── 安全护栏
│   敏感页面拦截、危险动作二次确认、自动化策略注册（SAEP 兼容）
│
├── keymgmt/                         ── BYOK 体系
│   Key 导入、校验、健康检测、用量统计、预算熔断、模型路由
│
├── memory/                          ── 记忆系统
│   三层记忆（短期轨迹 / 中期摘要 / 长期偏好）+ UI 图谱缓存
│
├── overlay/                         ── 悬浮交互
│   悬浮球、执行过程可视化、结果卡片
│
├── plugin/                          ── 插件体系（6 个模块）
│   ├── api/                         插件契约、能力定义、Manifest —— 零 Android 依赖
│   ├── runtime/                     加载器、生命周期、能力代理、沙箱
│   ├── rules/                       L1 规则包：选择器引擎、匹配器、动作执行
│   ├── script/                      L2 JS 沙箱运行时
│   ├── store/                       插件页：列表、订阅源、导入导出、冲突检测
│   └── devtools/                    快照审查、规则编辑器、测试台
│
├── display/                         ── 虚拟屏
│   虚拟屏创建/销毁、启动 App、可用性检测
│   └── preview/                     副屏预览悬浮窗 + 坐标映射
│
├── update/                          ── 应用内自更新、哈希校验
├── channel/                         ── 渠道号与隐私友好统计
├── onboarding/                      ── 分机型权限引导向导与自检
├── github/                          ── GitHub Device Flow / BYO Token、上报通道
└── contribute/                      ── 规则与插件贡献流程（生成 PR、内容预览）
```

共 30 个模块。

## 依赖方向（硬约束）

```
        app
         │
   ┌─────┴──────┬──────────┬─────────┐
   ▼            ▼          ▼         ▼
keymgmt    agent       overlay    safety
   │            │          │         │
   ▼            ▼          ▼         │
provider/*  perception   memory      │
   │            │          │         │
   └────────────┴──────────┴─────────┘
                ▼
              action
                ▼
        core/* (common/crypto/database/network)
```

**禁止的依赖**：
- `agent` 不得直接依赖任何 `provider/*` 实现，只能依赖 `provider/api`
- `perception` 与 `action` 不得依赖 `agent`（必须可独立单元测试）
- `core/*` 不得依赖任何上层模块
- `domain` 层（在各模块内）不得引入 Android 依赖

## 快速开始

```bash
# 1. 用 Android Studio 打开本目录（android/）
# 2. 首次同步会下载 Gradle 与依赖，需配置代理
# 3. 确认 local.properties 中的 sdk.dir 指向 Android SDK

# 编译
./gradlew :app:assembleDebug

# 单元测试
./gradlew test

# 静态检查
./gradlew detekt lint
```

> ⚠️ **项目路径不能含非 ASCII 字符**，否则 AGP 在 Windows 上直接拒绝构建。
> 临时绕过：`-Pandroid.overridePathCheck=true`。**不要提交这个绕过。**

> ⚠️ 如果 `./gradlew test` 下每个测试类都报 `ClassNotFoundException`，而 `.class` 文件明明在磁盘上，
> 那是 `@argfile` 编码错配。**不要用给 `org.gradle.jvmargs` 加 `-Dfile.encoding=UTF-8` 来"修"它** ——
> 那正是病因。完整推导见根目录 [README](../README.md#️-别给守护进程设--dfileencodingutf-8)。

## 版本说明

`gradle/libs.versions.toml` 中的版本号为 2026-09 的规划值，**开工前必须用 Android Studio 的
AGP Upgrade Assistant 与本机 SDK 实际情况校对**。targetSdk 需满足 Google Play 的 API 36 要求
（2026-08-31 截止）。

⚠️ **版本上限**：Compose BOM 锁 `2026.06.01`（Compose 1.11.4）。**1.12.0 是分水岭**，要求
`minCompileSdk=37` / `minAGP=9.1.0`，超出本项目 AGP 8.13.0 + compileSdk 36 的工具链；
`activity-compose 1.11.0` 与 `core-ktx 1.17.0` 要求 `minCompileSdk=36` / `minAGP=8.9.1`，正好卡在线上。
升任何 AndroidX 依赖前先跑 `python tools/verify/check_aar_metadata.py --bom <版本>`。
