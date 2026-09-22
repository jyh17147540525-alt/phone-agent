#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""`check_release_readiness.py` 的自测 —— 确认检查器**真的会红**。

═══════════════════════════════════════════════════════════════
 为什么检查器需要自己的测试
═══════════════════════════════════════════════════════════════

一个"永远打印 OK"的检查器看起来和"代码很干净"完全一样。
两者都是绿色，而前者**比没有检查更糟** —— 它给人已经检查过了的错觉。

本项目已经踩过这个模式（生成器 `--check` 长期有 4 条固定的红，
被当成"已知白名单"，结果训练所有人忽略它）。检查器的正确性只能靠
**故意构造违规输入、确认它真的报错**来证明。

这个脚本对每条规则造一个最小违规样例，断言：
  ① 该规则**报出了预期数量的 error**（不能是 0，也不该是别的规则蹭出来）
  ② 退出码确实变成 1

═══════════════════════════════════════════════════════════════
 用法
═══════════════════════════════════════════════════════════════

    python tools/verify/test_release_check.py

退出码：0 = 所有规则的"有牙性"都验证通过 · 1 = 有规则漏报
"""

from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CHECKER = Path(__file__).resolve().parent / "check_release_readiness.py"


def load_checker():
    """把检查器当模块加载，直接调它的规则函数。"""
    spec = importlib.util.spec_from_file_location("check_release_readiness", CHECKER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# ── 基线（合规）文件内容 ────────────────────────────────────
#
# 刻意写得**最小但合规**：每个 fixture 只改一处，
# 这样"红了"就一定能归因到那一处。

GOOD_APP_BUILD = """\
plugins { }

android {
    defaultConfig {
        versionName = "1.0.0"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("upload")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
"""

GOOD_MANIFEST = """\
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="false"
        android:networkSecurityConfig="@xml/network_security_config">
    </application>
</manifest>
"""

GOOD_RULES = """\
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
    </device-transfer>
</data-extraction-rules>
"""

GOOD_NETWORK = """\
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors><certificates src="system" /></trust-anchors>
    </base-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
"""

GOOD_PROGUARD = """\
# 保留 kotlinx.serialization 生成的 serializer
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
"""


def build_fixture(
    root: Path,
    *,
    app_build: str = GOOD_APP_BUILD,
    manifest: str = GOOD_MANIFEST,
    rules: str = GOOD_RULES,
    network: str = GOOD_NETWORK,
    proguard: str = GOOD_PROGUARD,
) -> None:
    """在 root 下铺一套最小 Android 工程骨架。"""
    app = root / "app"
    (app / "src" / "main" / "res" / "xml").mkdir(parents=True, exist_ok=True)
    (app / "build.gradle.kts").write_text(app_build, encoding="utf-8")
    (app / "proguard-rules.pro").write_text(proguard, encoding="utf-8")
    (app / "src" / "main" / "AndroidManifest.xml").write_text(manifest, encoding="utf-8")
    (app / "src" / "main" / "res" / "xml" / "data_extraction_rules.xml").write_text(rules, encoding="utf-8")
    (app / "src" / "main" / "res" / "xml" / "network_security_config.xml").write_text(network, encoding="utf-8")


def errors_of(findings: list) -> list:
    return [f for f in findings if f.severity == "error"]


def rule_hits(findings: list, rule: str) -> list:
    """某规则报出的**全部**结果（不分严重级别）。

    ⚠️ 第一版这里只数 `severity == "error"`，于是 R6/R7/R9/R10 四条
    （它们刻意只报 warn）全被误判成"规则失效"。

    这是个**测试自己写错**的典型：断言比规则更严，导致红条指向
    一个不存在的问题。教训与源码 bug 一样 —— 红 ≠ 源码错，
    要先判定"是谁错"。
    """
    return [f for f in findings if f.rule == rule]


def rule_errors(findings: list, rule: str) -> list:
    return [f for f in findings if f.rule == rule and f.severity == "error"]


def main() -> int:
    checker = load_checker()
    tmp = Path(tempfile.mkdtemp(prefix="release-check-selftest-"))

    failures: list[str] = []
    checks = 0

    def run_all(root: Path) -> tuple[list, list]:
        manifest = root / "app" / "src" / "main" / "AndroidManifest.xml"
        return (
            checker.check_debuggable(root)
            + checker.check_allow_backup(root, manifest)
            + checker.check_cleartext(root, manifest)
            + checker.check_signing(root)
            + checker.check_minify(root)
            + checker.check_version_name(root)
            + checker.check_no_run_as_dependency(root)
            + checker.check_proguard_rules(root)
            + checker.check_release_manifest_placeholder(root),
            [],
        )

    def expect_rule(name: str, mutate, rule: str, min_hits: int = 1, severity: str | None = None) -> None:
        """造一个违规 fixture，断言指定规则**报出** ≥ min_hits 条。

        @param severity 为 None 时不分级别（warn 与 error 都算）。
                        只有确实要钉住级别时才传值。
        """
        nonlocal checks
        checks += 1
        case_dir = tmp / name
        build_fixture(case_dir)
        mutate(case_dir)
        findings, _ = run_all(case_dir)
        hits = rule_hits(findings, rule)
        if severity is not None:
            hits = [f for f in hits if f.severity == severity]
        if len(hits) < min_hits:
            failures.append(
                f"✗ {name}：期望 {rule} 报出 ≥{min_hits} 条"
                f"{f'（{severity}）' if severity else ''}，实际 {len(hits)} 条"
                f"（全部结果：{[str(f) for f in findings]}）"
            )
        else:
            print(f"  ✓ {name} → {rule} 报出 {len(hits)} 条")

    def expect_clean(name: str) -> None:
        """基线 fixture 必须**零 error** —— 否则检查器有误报。"""
        nonlocal checks
        checks += 1
        case_dir = tmp / name
        build_fixture(case_dir)
        findings, _ = run_all(case_dir)
        errs = errors_of(findings)
        if errs:
            failures.append(f"✗ {name}：合规 fixture 报出了误报 error：{[str(f) for f in errs]}")
        else:
            print(f"  ✓ {name} → 零误报")

    print("PocketAgent · 发行检查器自测")
    print()

    # ── 基线：合规配置必须零 error（防误报）─────────────────
    print("基线（应零 error）")
    expect_clean("baseline")

    # ── R1 debuggable ──────────────────────────────────────
    print("\nR1 · debuggable")
    expect_rule(
        "R1-release-debuggable",
        lambda d: write(d, "app/build.gradle.kts", GOOD_APP_BUILD.replace(
            "            isMinifyEnabled = true",
            "            isDebuggable = true\n            isMinifyEnabled = true")),
        "R1",
    )

    def r1_inject_in_comment(d: Path) -> None:
        # ★ 关键的反向测试：注释里提到 isDebuggable = true 不该报错
        #   （本仓库注释极多，检查器被自己的文档绊倒是很真实的风险）
        body = GOOD_APP_BUILD.replace(
            "        release {",
            "        release {\n            // ⚠️ 绝不能写 isDebuggable = true —— 那会让 run-as 后门随包发布",
        )
        write(d, "app/build.gradle.kts", body)

    expect_clean_because_comment = "R1-注释里的-debuggable-不应报警"
    checks += 1
    case_dir = tmp / expect_clean_because_comment
    build_fixture(case_dir)
    r1_inject_in_comment(case_dir)
    findings, _ = run_all(case_dir)
    if rule_errors(findings, "R1"):
        failures.append(
            f"✗ {expect_clean_because_comment}：注释里的 isDebuggable 被误报为 error"
        )
    else:
        print(f"  ✓ {expect_clean_because_comment} → 注释被正确忽略")

    # ── R2 allowBackup ─────────────────────────────────────
    print("\nR2 · allowBackup")
    expect_rule(
        "R2-allowBackup-true",
        lambda d: write(d, "app/src/main/AndroidManifest.xml",
                        GOOD_MANIFEST.replace('android:allowBackup="false"', 'android:allowBackup="true"')),
        "R2",
    )
    expect_rule(
        "R2-allowBackup-缺失",
        lambda d: write(d, "app/src/main/AndroidManifest.xml",
                        GOOD_MANIFEST.replace('        android:allowBackup="false"\n', "")),
        "R2",
    )

    # ── R3 备份规则 ────────────────────────────────────────
    print("\nR3 · 备份/迁移规则")
    expect_rule(
        "R3-缺-device-transfer",
        lambda d: write(d, "app/src/main/res/xml/data_extraction_rules.xml",
                        GOOD_RULES.split("<device-transfer>")[0] + "</data-extraction-rules>\n"),
        "R3",
    )
    expect_rule(
        "R3-device-transfer-未排除-database",
        lambda d: write(d, "app/src/main/res/xml/data_extraction_rules.xml",
                        GOOD_RULES.replace(
                            '<exclude domain="database" path="." />\n        <exclude domain="sharedpref" path="." />\n        <exclude domain="external" path="." />\n    </device-transfer>',
                            '<exclude domain="sharedpref" path="." />\n    </device-transfer>')),
        "R3",
    )

    # ── R4 明文流量 ────────────────────────────────────────
    print("\nR4 · 明文流量")
    expect_rule(
        "R4-usesCleartextTraffic",
        lambda d: write(d, "app/src/main/AndroidManifest.xml",
                        GOOD_MANIFEST.replace(
                            'android:allowBackup="false"',
                            'android:allowBackup="false"\n        android:usesCleartextTraffic="true"')),
        "R4",
    )
    expect_rule(
        "R4-base-config-放行明文",
        lambda d: write(d, "app/src/main/res/xml/network_security_config.xml",
                        GOOD_NETWORK.replace(
                            '<base-config cleartextTrafficPermitted="false">',
                            '<base-config cleartextTrafficPermitted="true">')),
        "R4",
        severity="error",
    )
    expect_rule(
        "R4-通配域",
        lambda d: write(d, "app/src/main/res/xml/network_security_config.xml",
                        GOOD_NETWORK.replace(
                            "<domain includeSubdomains=\"false\">127.0.0.1</domain>",
                            "<domain>0.0.0.0</domain>")),
        "R4",
    )

    # ── R5 签名 ────────────────────────────────────────────
    print("\nR5 · 签名配置")
    expect_rule(
        "R5-release-用-debug-签名",
        lambda d: write(d, "app/build.gradle.kts", GOOD_APP_BUILD.replace(
            'signingConfig = signingConfigs.getByName("upload")',
            'signingConfig = signingConfigs.getByName("debug")')),
        "R5",
        # 用 debug 密钥签名必须是 error 而非 warn：debug 密钥是公开的，
        # 任何人都能签出可冒充的更新包 —— 这不是"发版前再改"的提醒，是红线
        severity="error",
    )

    # ── R6 minify ──────────────────────────────────────────
    print("\nR6 · minify")
    expect_rule(
        "R6-未开-minify",
        lambda d: write(d, "app/build.gradle.kts",
                        GOOD_APP_BUILD.replace("            isMinifyEnabled = true\n", "")),
        "R6",
    )

    # ── R7 版本号 ──────────────────────────────────────────
    print("\nR7 · 版本号")
    expect_rule(
        "R7-版本号带-m0",
        lambda d: write(d, "app/build.gradle.kts",
                        GOOD_APP_BUILD.replace('versionName = "1.0.0"', 'versionName = "0.1.0-m0"')),
        "R7",
    )

    # ── R8 run-as ──────────────────────────────────────────
    print("\nR8 · 生产代码依赖 run-as")

    def r8_inject(d: Path) -> None:
        src = d / "app" / "src" / "main" / "kotlin" / "com" / "pocketagent"
        src.mkdir(parents=True, exist_ok=True)
        (src / "Bad.kt").write_text(
            'package com.pocketagent\n\n'
            'object Bad {\n'
            '    fun hack() = Runtime.getRuntime().exec(arrayOf("run-as", "com.termux", "ls"))\n'
            '}\n',
            encoding="utf-8",
        )

    expect_rule("R8-源码里出现-run-as", r8_inject, "R8", severity="error")

    # ── R9 ProGuard ────────────────────────────────────────
    print("\nR9 · ProGuard 规则")

    def r9_wipe(d: Path) -> None:
        (d / "app" / "proguard-rules.pro").write_text("#\n", encoding="utf-8")

    expect_rule("R9-规则文件近乎为空", r9_wipe, "R9")

    def r9_delete(d: Path) -> None:
        (d / "app" / "proguard-rules.pro").unlink()

    expect_rule("R9-规则文件缺失", r9_delete, "R9")

    # ── R10 占位符 ─────────────────────────────────────────
    print("\nR10 · 未替换占位符")
    expect_rule(
        "R10-manifest-残留TODO",
        lambda d: write(d, "app/src/main/AndroidManifest.xml",
                        GOOD_MANIFEST.replace(
                            'android:allowBackup="false"',
                            'android:allowBackup="false"\n        android:label="TODO"')),
        "R10",
    )

    # ── 退出码 ─────────────────────────────────────────────
    print("\n退出码")
    checks += 1
    bad_dir = tmp / "R1-release-debuggable"
    rc = run_checker_subprocess(bad_dir)
    if rc == 1:
        print(f"  ✓ 违规 fixture 的退出码为 1")
    else:
        failures.append(f"✗ 违规 fixture 的退出码应为 1，实际 {rc}")

    checks += 1
    good_dir = tmp / "baseline"
    rc_good = run_checker_subprocess(good_dir)
    if rc_good == 0:
        print(f"  ✓ 合规 fixture 的退出码为 0")
    else:
        failures.append(f"✗ 合规 fixture 的退出码应为 0，实际 {rc_good}")

    # ── 缺失关键文件 → 退出码 2 ────────────────────────────
    checks += 1
    empty_dir = tmp / "empty"
    empty_dir.mkdir()
    rc_empty = run_checker_subprocess(empty_dir)
    if rc_empty == 2:
        print(f"  ✓ 缺少关键文件时退出码为 2（而不是误报 OK）")
    else:
        failures.append(f"✗ 缺少关键文件时退出码应为 2，实际 {rc_empty}")

    shutil.rmtree(tmp, ignore_errors=True)

    print()
    if failures:
        print(f"发现 {len(failures)} 项问题（共 {checks} 项检查）：")
        for f in failures:
            print(f"  {f}")
        return 1

    print(f"全部 {checks} 项自测通过 —— 每条规则都被证明**会红**。")
    return 0


def write(root: Path, rel_path: str, content: str) -> None:
    (root / rel_path).write_text(content, encoding="utf-8")


def run_checker_subprocess(root: Path) -> int:
    import subprocess

    proc = subprocess.run(
        [sys.executable, str(CHECKER), str(root)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return proc.returncode


if __name__ == "__main__":
    sys.exit(main())
