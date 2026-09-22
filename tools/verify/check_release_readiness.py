#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""发行就绪检查 —— 拦住"带着开发期配置发出去"。

═══════════════════════════════════════════════════════════════
 为什么需要这个脚本
═══════════════════════════════════════════════════════════════

M0 阶段的开发手法**故意**依赖了一些"正式发行时必须消失"的东西：

    adb shell run-as com.pocketagent.debug …     ← 读应用私有数据

它之所以能用，是因为 debug 变体是 `debuggable=true`。这条通道是本项目
在真机上验证 dsh / proot 的关键工具（见 skill `termux-proot-remote-exec`），
但它同时也是一条**任意第三方 App 都能借用的后门** —— 只要设备和电脑连着。

`debuggable` 由 AGP 的 buildType 默认值决定：`debug` 变体是 true，
`release` 变体是 false。**默认是对的，但没人拦着谁去覆盖它。**
而"覆盖了却忘了改回来"的代价极高：用户装上的是带后门的包，
而这件事**在真机上完全看不出来** —— App 表现得一模一样。

这就是 P7 存在的唯一理由：**做一道显式的闸**。

═══════════════════════════════════════════════════════════════
 与其它检查脚本的分工
═══════════════════════════════════════════════════════════════

| 脚本                       | 管什么                             |
|----------------------------|------------------------------------|
| `check_module_deps.py`     | 跨模块引用有没有对应的 project 依赖 |
| `check_xml_comments.py`    | XML 注释里的非法 `--` 序列          |
| `check_kt_quotes.py`       | 中文文案里的 ASCII 引号误用         |
| `check_version_catalog.py` | `libs.*` 引用能否在版本目录里找到   |
| **本脚本**                 | **正式发行前的安全与合规不变量**     |

═══════════════════════════════════════════════════════════════
 用法
═══════════════════════════════════════════════════════════════

    python tools/verify/check_release_readiness.py            # 全量
    python tools/verify/check_release_readiness.py android    # 指定根目录

退出码：0 = 全过 · 1 = 发现问题 · 2 = 扫描本身失败（路径/文件缺失）

═══════════════════════════════════════════════════════════════
 ⚠️ 设计的核心权衡
═══════════════════════════════════════════════════════════════

**这个脚本检查的是"配置"，不是"产物"。**

它读的是 build.gradle.kts / AndroidManifest.xml / res/xml 这些**声明**，
而不是去解包 APK。这是刻意的：

- 好处：不需要 Android SDK、不需要构建、毫秒级完成，能在 CI 里每次提交都跑
- 代价：它无法发现"某人的 proguard 规则把类名改坏了"这类构建期问题

**两者不是替代关系。** 真正的发行流程应该是：
    本脚本（声明层）→ `./gradlew assembleRelease`（构建层）→ `apksigner verify`（产物层）

本脚本挡的是**最容易漏且后果最重**的那一类：配置写错。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

C_OK = "\033[92m"
C_FAIL = "\033[91m"
C_WARN = "\033[93m"
C_DIM = "\033[90m"
C_BOLD = "\033[1m"
C_OFF = "\033[0m"


def _supports_color() -> bool:
    import os

    if os.environ.get("NO_COLOR"):
        return False
    if not sys.stdout.isatty():
        return False
    if os.name == "nt" and not os.environ.get("WT_SESSION"):
        return bool(os.environ.get("ANSICON"))
    return True


USE_COLOR = _supports_color()


def c(text: str, color: str) -> str:
    return f"{color}{text}{C_OFF}" if USE_COLOR else text


# ═══════════════════════════════════════════════════════════════
#  规则定义
# ═══════════════════════════════════════════════════════════════


class Finding:
    """一条检查结果。

    `severity` 只有两级，刻意不设三级：
    三级会引入"这条到底要不要修"的灰色地带，而灰条会被忽略 ——
    与"白名单式红条 = 满屏误报"是同一个教训。
    """

    def __init__(self, rule: str, severity: str, message: str, where: str = "") -> None:
        self.rule = rule
        self.severity = severity  # "error" | "warn"
        self.message = message
        self.where = where

    def __str__(self) -> str:
        loc = f"  {c(self.where, C_DIM)}" if self.where else ""
        return f"[{self.rule}] {self.message}{loc}"


def strip_kt_comments(text: str) -> str:
    """去掉 Kotlin/Gradle 文件里的注释。

    ⚠️ **必须先去掉注释再匹配**，否则本仓库里"讲解不变量"的注释
    会把检查器自己骗成红的 —— 例如注释里写着
    `// debuggable = true 是错的`，裸搜 `debuggable = true` 就会命中。

    这是"检查器被自己的文档绊倒"的经典坑，本仓库的注释特别多，
    所以格外容易触发。
    """
    # 块注释 /* ... */（Kotlin 支持嵌套，但这里的用法没有嵌套，用非贪婪即可）
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    # 行注释 // ...
    text = re.sub(r"//[^\n]*", "", text)
    return text


def strip_xml_comments(text: str) -> str:
    """去掉 XML 注释。理由同 [strip_kt_comments]。"""
    return re.sub(r"<!--.*?-->", "", text, flags=re.S)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


# ═══════════════════════════════════════════════════════════════
#  逐条规则
# ═══════════════════════════════════════════════════════════════


def check_debuggable(android_root: Path) -> list[Finding]:
    """R1 ★ debuggable 不得在 release 变体上被打开。

    这是本脚本**最重要**的一条。理由见模块 docstring。
    """
    findings: list[Finding] = []
    app_build = android_root / "app" / "build.gradle.kts"
    if not app_build.is_file():
        return [
            Finding("R1", "error", ":app 的 build.gradle.kts 不存在 —— 无法验证发行配置",
                    str(app_build)),
        ]

    raw = read(app_build)
    text = strip_kt_comments(raw)

    # 找到 release { ... } 块，只在块内检查。
    # ⚠️ 不能全文件搜 —— debug 块里出现 debuggable 是完全正常的。
    release_block = extract_brace_block(text, "release")
    if release_block is None:
        findings.append(
            Finding("R1", "error", "找不到 buildTypes 里的 release 块",
                    "app/build.gradle.kts"),
        )
    else:
        if re.search(r"\bisDebuggable\s*=\s*true", release_block):
            findings.append(
                Finding(
                    "R1", "error",
                    "★ release 变体上 isDebuggable = true —— 这会让 `run-as` 后门随包发布。"
                    "正式包绝不能带它",
                    "app/build.gradle.kts",
                ),
            )
        # 反向检查：debug 变体应当保持 debuggable（开发期工具依赖它）
        debug_block = extract_brace_block(text, "debug")
        if debug_block is not None and re.search(r"\bisDebuggable\s*=\s*false", debug_block):
            findings.append(
                Finding(
                    "R1", "warn",
                    "debug 变体被显式设成 isDebuggable = false —— 开发期的 "
                    "`adb shell run-as` 验证通道会失效（见 skill termux-proot-remote-exec）",
                    "app/build.gradle.kts",
                ),
            )

    return findings


def extract_brace_block(text: str, name: str) -> str | None:
    """提取形如 `name {` … 配对 `}` 的块内容。

    刻意手写而不是用正则：Gradle DSL 里块会嵌套（buildTypes → release → proguardFiles），
    正则处理不了嵌套深度。

    ⚠️ 只匹配**作为词**出现的 name（前面不能是 `.` 或字母数字），
    否则 `buildTypes { release {` 里的 release 与
    `signingConfigs { create("release") {` 里的 release 会混淆。
    """
    pattern = re.compile(rf"(?<![\w.]){re.escape(name)}\s*\{{")
    match = pattern.search(text)
    if not match:
        return None

    start = match.end() - 1  # 指向 '{'
    depth = 0
    for i in range(start, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1 : i]
    return None  # 括号不配对


def check_allow_backup(android_root: Path, manifest: Path) -> list[Finding]:
    """R2 ★ allowBackup 必须为 false。

    API Key / 插件授权 / 操作历史都躺在应用私有目录里，
    而备份包是可以用 `adb backup` 完整取出的明文容器。
    """
    findings: list[Finding] = []
    if not manifest.is_file():
        return [
            Finding("R2", "error", "AndroidManifest.xml 不存在", str(manifest)),
        ]

    text = strip_xml_comments(read(manifest))

    m = re.search(r'android:allowBackup\s*=\s*"([^"]*)"', text)
    if m is None:
        findings.append(
            Finding(
                "R2", "error",
                "AndroidManifest 里没有 android:allowBackup —— 默认值是 true，"
                "备份会把 API Key 一起带走",
                rel(manifest, android_root),
            ),
        )
    elif m.group(1).strip().lower() != "false":
        findings.append(
            Finding(
                "R2", "error",
                f'android:allowBackup="{m.group(1)}" —— 必须是 false',
                rel(manifest, android_root),
            ),
        )

    # dataExtractionRules（Android 12+ 走这条路径，与 allowBackup 是两条独立通道）
    rules_ref = re.search(r'android:dataExtractionRules\s*=\s*"@xml/([^"]*)"', text)
    if rules_ref is None:
        findings.append(
            Finding(
                "R2", "warn",
                "没有声明 android:dataExtractionRules —— Android 12+ 会退回默认的"
                "云备份/换机迁移行为，而那份默认是**允许**备份的",
                rel(manifest, android_root),
            ),
        )
    else:
        rules_file = android_root / "app" / "src" / "main" / "res" / "xml" / f"{rules_ref.group(1)}.xml"
        findings.extend(check_extraction_rules(rules_file, android_root))

    # fullBackupContent="false"：允许把它设成 false 或指向规则文件，
    # 但绝不能是 true
    m2 = re.search(r'android:fullBackupContent\s*=\s*"([^"]*)"', text)
    if m2 is not None and m2.group(1).strip().lower() == "true":
        findings.append(
            Finding(
                "R2", "error",
                'android:fullBackupContent="true" —— 必须为 false 或指向规则文件',
                rel(manifest, android_root),
            ),
        )

    return findings


def check_extraction_rules(rules_file: Path, android_root: Path) -> list[Finding]:
    """R3 ★ 备份/迁移规则必须两个域都全排除。

    ⚠️ `<cloud-backup>` 与 `<device-transfer>` 是**两条独立通道**。
       只排除其中一个是很常见的疏漏：写的人记得云备份，
       却忘了换机迁移 —— 而后者恰恰把数据送到"用户可能已经卖掉"的旧设备上。
    """
    findings: list[Finding] = []
    if not rules_file.is_file():
        return [
            Finding("R3", "error", "dataExtractionRules 指向的文件不存在", str(rules_file)),
        ]

    text = strip_xml_comments(read(rules_file))
    where = rel(rules_file, android_root)

    # 必须两个块都在，且各自含排除规则
    for block in ("cloud-backup", "device-transfer"):
        body = extract_xml_block(text, block)
        if body is None:
            findings.append(
                Finding("R3", "error", f"<{block}> 块缺失", where),
            )
            continue
        # 至少要排除 database / sharedpref / file 三个域
        # （凭据在 database，订阅源在 sharedpref，导出文件在 file）
        for domain in ("database", "sharedpref", "file"):
            if not re.search(rf'<exclude[^>]*domain\s*=\s*"{domain}"', body):
                findings.append(
                    Finding(
                        "R3", "error",
                        f"<{block}> 没有排除 domain=\"{domain}\" —— "
                        "该域下的数据会进备份包",
                        where,
                    ),
                )

    return findings


def extract_xml_block(text: str, tag: str) -> str | None:
    """提取 `<tag>…</tag>` 的**内部内容**（不含开标签）。

    ⚠️ 开标签上的**属性不在返回值里**。若你要检查的是属性
    （如 `cleartextTrafficPermitted="true"`），用 [extract_xml_element]。
    用错这个函数会让规则静默失效 —— 见 R4 的注释。
    """
    m = re.search(rf"<{re.escape(tag)}[^>]*>(.*?)</{re.escape(tag)}>", text, re.S)
    return m.group(1) if m else None


def extract_xml_element(text: str, tag: str) -> str | None:
    """提取 `<tag …>…</tag>`，**含开标签及其属性**。

    属性检查必须用这个 —— 否则规则永远是绿的（实测踩过）。
    """
    m = re.search(rf"<{re.escape(tag)}\b[^>]*>.*?</{re.escape(tag)}>", text, re.S)
    return m.group(0) if m else None


def check_cleartext(android_root: Path, manifest: Path) -> list[Finding]:
    """R4 ★ 不得全局放开明文流量。

    模型请求里带着 API Key 和屏幕内容，走明文等于把两者一起广播。
    局域网例外（端侧 Ollama 之类）是允许的，但必须**列出具体地址**，
    不能写成通配。
    """
    findings: list[Finding] = []
    if not manifest.is_file():
        return []

    m = re.search(r'android:usesCleartextTraffic\s*=\s*"([^"]*)"', read(manifest))
    if m is not None and m.group(1).strip().lower() == "true":
        findings.append(
            Finding(
                "R4", "error",
                'AndroidManifest 里 usesCleartextTraffic="true" —— 全局放开明文，'
                "API Key 与屏幕内容会被明文传输",
                rel(manifest, android_root),
            ),
        )

    # 找到 networkSecurityConfig 指向的文件
    ref = re.search(r'android:networkSecurityConfig\s*=\s*"@xml/([^"]*)"', read(manifest))
    if ref is None:
        return findings

    cfg = android_root / "app" / "src" / "main" / "res" / "xml" / f"{ref.group(1)}.xml"
    if not cfg.is_file():
        findings.append(
            Finding("R4", "error", "networkSecurityConfig 指向的文件不存在", str(cfg)),
        )
        return findings

    text = strip_xml_comments(read(cfg))
    where = rel(cfg, android_root)

    # ★ base-config 不得允许明文。
    #
    # ⚠️ 这里**必须匹配整个元素（含开标签）**，不能用 extract_xml_block。
    #    那个辅助函数只返回开标签**之后**的内容，而
    #    `cleartextTrafficPermitted` 是**开标签上的属性** ——
    #    用它的写法是：
    #
    #        base = extract_xml_block(text, "base-config")
    #        re.search(r'cleartextTrafficPermitted\s*=\s*"true"', base)   # ✗ 永远不匹配
    #
    #    这会让该规则变成一条**死的检查**：代码在、读起来也在检查，
    #    但永远不会报红。自测（`test_release_check.py`）第一次跑就抓到了它 ——
    #    这正是"检查器必须有自测"的理由。
    base_element = extract_xml_element(text, "base-config")
    if base_element is not None and re.search(r'cleartextTrafficPermitted\s*=\s*"true"', base_element):
        findings.append(
            Finding(
                "R4", "error",
                "<base-config cleartextTrafficPermitted=\"true\"> —— 默认放行明文",
                where,
            ),
        )

    # domain-config 里不得出现通配或 0.0.0.0
    for domain in re.findall(r"<domain[^>]*>([^<]*)</domain>", text):
        d = domain.strip()
        if d in {"0.0.0.0", "*", "0.0.0.0/0"} or d.startswith("*.") or d.endswith("."):
            findings.append(
                Finding(
                    "R4", "error",
                    f'cleartext 域里出现通配地址 "{d}" —— '
                    "这会把公网一起放进来，应当只列具体主机",
                    where,
                ),
            )

    # 端侧模型的明文例外是刻意设计的，但要求它带 includeSubdomains="false"
    for tag in re.findall(r"<domain\s[^>]*>", text):
        if "includeSubdomains" not in tag:
            findings.append(
                Finding(
                    "R4", "warn",
                    f'<domain> 未显式声明 includeSubdomains："{tag.strip()}" —— '
                    "建议写 includeSubdomains=\"false\" 收窄范围",
                    where,
                ),
            )

    return findings


def check_signing(android_root: Path) -> list[Finding]:
    """R5 release 变体必须有明确的签名配置。

    ⚠️ 不声明 `signingConfig` 时，AGP 对 release 变体**不会**自动套用 debug 签名 ——
       它会产出一个未签名的 APK。而这在 `assembleRelease` 时**不会报错**，
       只有在安装或上架时才暴露。

       ⚠️ 更要防的是另一种写法：`signingConfig = signingConfigs.getByName("debug")`
       —— 那会让正式包用 debug 密钥签名。debug 密钥是公开的（密码就是 "android"），
       任何人都能签出一个"看起来是正版"的更新包。这一条按 error 处理。
    """
    findings: list[Finding] = []
    app_build = android_root / "app" / "build.gradle.kts"
    if not app_build.is_file():
        return []

    text = strip_kt_comments(read(app_build))
    release_block = extract_brace_block(text, "release")

    if release_block is None:
        return findings  # R1 已经报过"找不到 release 块"

    m = re.search(r"\bsigningConfig\s*=\s*([^\n]+)", release_block)
    if m is None:
        findings.append(
            Finding(
                "R5", "warn",
                "release 变体没有声明 signingConfig —— 会产出未签名 APK。"
                "M0 阶段可接受（还不发版），但发版前必须补上",
                "app/build.gradle.kts",
            ),
        )
    else:
        target = m.group(1).strip()
        if "debug" in target.lower():
            findings.append(
                Finding(
                    "R5", "error",
                    f"★ release 变体用了 debug 签名：`{target}` —— debug 密钥是公开的，"
                    "任何人都能签出可冒充的更新包",
                    "app/build.gradle.kts",
                ),
            )

    return findings


def check_minify(android_root: Path) -> list[Finding]:
    """R6 release 必须开 minify + shrinkResources。

    这不是"优化建议"，是**两条安全属性的前置条件**：
    · 不开 minify，类名与方法名原样保留 → 逆向成本极低
    · ProGuard 规则里的 `-assumenosideeffects` 之类要靠它才生效
    """
    findings: list[Finding] = []
    app_build = android_root / "app" / "build.gradle.kts"
    if not app_build.is_file():
        return []

    text = strip_kt_comments(read(app_build))
    release_block = extract_brace_block(text, "release")
    if release_block is None:
        return findings

    if not re.search(r"\bisMinifyEnabled\s*=\s*true", release_block):
        findings.append(
            Finding(
                "R6", "warn",
                "release 变体未开 isMinifyEnabled —— 类名/方法名原样保留，"
                "逆向成本极低（M0 阶段可接受，发版前必须开）",
                "app/build.gradle.kts",
            ),
        )
    if not re.search(r"\bisShrinkResources\s*=\s*true", release_block):
        findings.append(
            Finding(
                "R6", "warn",
                "release 变体未开 isShrinkResources",
                "app/build.gradle.kts",
            ),
        )

    return findings


def check_version_name(android_root: Path) -> list[Finding]:
    """R7 版本号不得残留开发期标记。

    `versionName = "0.1.0-m0"` 这类后缀在开发期很有用（一眼看出装的是哪个阶段），
    但发出去会让用户与问题反馈里出现"我装的是 m0"这种无法定位的信息。
    """
    findings: list[Finding] = []
    app_build = android_root / "app" / "build.gradle.kts"
    if not app_build.is_file():
        return []

    text = strip_kt_comments(read(app_build))
    m = re.search(r'versionName\s*=\s*"([^"]*)"', text)
    if m is None:
        return findings

    version = m.group(1)
    dev_markers = ("-m0", "-m1", "-dev", "-alpha", "-beta", "-snapshot", "-rc")
    hit = next((mk for mk in dev_markers if mk in version.lower()), None)
    if hit:
        findings.append(
            Finding(
                "R7", "warn",
                f'versionName = "{version}" 含开发期标记 "{hit}" —— 发版前应改为正式版本号',
                "app/build.gradle.kts",
            ),
        )

    return findings


def check_no_run_as_dependency(android_root: Path) -> list[Finding]:
    """R8 ★ 生产代码不得依赖 `run-as` 这类开发期通道。

    skill `termux-proot-remote-exec` 里的手法是**通过 adb 从 PC 侧执行**的：
        adb shell run-as com.termux <PREFIX>/bin/bash -lc ...

    它是开发期工具，**不应该出现在应用代码里**。一旦有人把
    "用 run-as 去读 Termux 数据"写进 Kotlin 源码，就等于假设
    "本应用永远 debuggable" —— 而那条假设在正式包上不成立，
    表现是"功能在开发机上好好的，用户装上就废"。

    只扫 `src/main`（测试里用它是合理的）。
    """
    findings: list[Finding] = []
    offenders: list[str] = []

    for kt in sorted(android_root.glob("**/src/main/**/*.kt")):
        if "/build/" in kt.as_posix():
            continue
        body = strip_kt_comments(read(kt))
        if re.search(r'"run-as"|\brunAs\b', body):
            offenders.append(rel(kt, android_root))

    if offenders:
        findings.append(
            Finding(
                "R8", "error",
                "生产代码里出现 run-as —— 那是开发期验证通道，"
                "正式包不可用（本应用不该 debuggable）。"
                "涉及文件：" + ", ".join(offenders[:5]),
            ),
        )

    return findings


def check_proguard_rules(android_root: Path) -> list[Finding]:
    """R9 ProGuard 规则文件存在且未被清空。

    ⚠️ 这条看似废话，但它防的是一个真实退化：为了"先把 release 跑起来"
       把 proguardFiles 注释掉或把规则文件清空，然后忘掉。
       minify 开着但规则为空，R8 会把反射用到的类（如 kotlinx.serialization
       的 serializer）整个删掉 —— 表现为**运行时崩溃**，而且只在 release 包上。
    """
    findings: list[Finding] = []
    rules = android_root / "app" / "proguard-rules.pro"

    if not rules.is_file():
        findings.append(
            Finding("R9", "error", "app/proguard-rules.pro 不存在", str(rules)),
        )
        return findings

    body = strip_kt_comments(read(rules)).strip()
    if len(body) < 20:
        findings.append(
            Finding(
                "R9", "warn",
                "proguard-rules.pro 内容近乎为空 —— minify 开着而规则为空时，"
                "R8 会把反射用到的类整个删掉（只在 release 包上崩）",
                rel(rules, android_root),
            ),
        )

    return findings


def check_release_manifest_placeholder(android_root: Path) -> list[Finding]:
    """R10 不得有未替换的占位符。

    形如 `TODO` / `FIXME` / `XXX` / `PLACEHOLDER` 的行如果是**活跃配置**
    （不是注释），发出去会直接构建失败或运行异常。
    """
    findings: list[Finding] = []
    marker = re.compile(r"\b(TODO|FIXME|XXX|PLACEHOLDER)\b")

    for pattern in ("**/src/main/AndroidManifest.xml", "**/src/main/res/xml/*.xml"):
        for path in sorted(android_root.glob(pattern)):
            if "/build/" in path.as_posix():
                continue
            body = strip_xml_comments(read(path))
            if marker.search(body):
                findings.append(
                    Finding(
                        "R10", "warn",
                        "有效配置里残留 TODO/FIXME 标记（注释外的部分）",
                        rel(path, android_root),
                    ),
                )

    return findings


def rel(path: Path, android_root: Path) -> str:
    try:
        return str(path.relative_to(android_root.parent))
    except ValueError:
        return str(path)


# ═══════════════════════════════════════════════════════════════
#  主流程
# ═══════════════════════════════════════════════════════════════

RULES = [
    ("R1", "debuggable 不得在 release 上开启", check_debuggable),
    ("R2", "allowBackup 必须为 false", check_allow_backup),
    ("R4", "不得全局放行明文流量", check_cleartext),
    ("R5", "release 签名配置", check_signing),
    ("R6", "release 必须开 minify", check_minify),
    ("R7", "版本号无开发期标记", check_version_name),
    ("R8", "生产代码不依赖 run-as", check_no_run_as_dependency),
    ("R9", "ProGuard 规则存在且非空", check_proguard_rules),
    ("R10", "无未替换的占位符", check_release_manifest_placeholder),
]


def main() -> int:
    if len(sys.argv) > 1:
        android_root = Path(sys.argv[1]).resolve()
    else:
        android_root = (Path(__file__).resolve().parent.parent.parent / "android").resolve()

    if not android_root.is_dir():
        print(f"✗ 找不到 android 目录：{android_root}", file=sys.stderr)
        return 2

    manifest = android_root / "app" / "src" / "main" / "AndroidManifest.xml"
    app_build = android_root / "app" / "build.gradle.kts"

    # 前置：关键文件缺失时直接失败，而不是"扫了 0 个文件然后说 OK"
    for required in (app_build, manifest):
        if not required.is_file():
            print(f"✗ 缺少关键文件：{required}", file=sys.stderr)
            return 2

    print(c("PocketAgent · 发行就绪检查", C_BOLD))
    print(f"  android 根  {android_root}")
    print()

    findings: list[Finding] = []
    findings.extend(check_debuggable(android_root))
    findings.extend(check_allow_backup(android_root, manifest))
    findings.extend(check_cleartext(android_root, manifest))
    findings.extend(check_signing(android_root))
    findings.extend(check_minify(android_root))
    findings.extend(check_version_name(android_root))
    findings.extend(check_no_run_as_dependency(android_root))
    findings.extend(check_proguard_rules(android_root))
    findings.extend(check_release_manifest_placeholder(android_root))

    errors = [f for f in findings if f.severity == "error"]
    warns = [f for f in findings if f.severity == "warn"]

    for f in errors:
        print(f"{c('✗', C_FAIL)} {f}")
    for f in warns:
        print(f"{c('!', C_WARN)} {f}")

    print()
    print(f"  已执行 {len(RULES)} 组规则")

    if errors:
        print(f"\n{c(f'发现 {len(errors)} 处必须修复的问题', C_FAIL)}"
              f"{f'，另有 {len(warns)} 条提醒' if warns else ''}。")
        print("这些问题在真机上完全看不出来 —— 那正是必须靠脚本拦的原因。")
        return 1

    if warns:
        print(f"{c(f'通过，但有 {len(warns)} 条提醒', C_WARN)}")
        print("（M0 阶段的部分提醒是预期的 —— 发版前需要清零）")
        return 0

    print(c("全部发行就绪检查通过。", C_OK))
    return 0


if __name__ == "__main__":
    sys.exit(main())
