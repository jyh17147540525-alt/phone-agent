#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
纯 Kotlin 逻辑验证器 —— 在没有 Android SDK 的机器上也能跑单测。

═══════════════════════════════════════════════════════════════
 为什么需要这个东西
═══════════════════════════════════════════════════════════════

本项目的绝大多数高风险逻辑（SSE 解析、密钥脱敏、token 估算、费用计算、
动作协议序列化）都在**不依赖 Android 框架**的纯 Kotlin 模块里。
但它们所在的 Gradle 模块声明了 `com.android.library` 插件，
没有 Android SDK 就编译不了 —— 于是这些代码在装上 Android Studio 之前
完全无法验证，只能靠肉眼读。

这个脚本绕开 Gradle 与 AGP，直接用 Kotlin 命令行编译器把这几个模块的
源码抓出来单独编译并跑 JUnit，从而把"能不能编译""逻辑对不对"这两件事
从"能不能搭 Android 环境"里解耦出来。

═══════════════════════════════════════════════════════════════
 用法
═══════════════════════════════════════════════════════════════

    python tools/verify/run_logic_tests.py                # 全量
    python tools/verify/run_logic_tests.py --clean        # 清掉依赖缓存重下
    python tools/verify/run_logic_tests.py --keep-work    # 保留中间产物便于排查

依赖（Kotlin 编译器 + 运行库，约 70MB）下载到用户级缓存目录，不进仓库。

═══════════════════════════════════════════════════════════════
 边界
═══════════════════════════════════════════════════════════════

- 只验证**纯 Kotlin** 模块。任何 import android.* 的源码都无法在此编译，
  这是设计使然，不是缺陷 —— 那些代码的正确性只能靠真机。
- 不替代 `./gradlew test`。等 Android SDK 就位后，Gradle 才是唯一权威。
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

# ═══════════════════════════════════════════════════════════════
#  配置
# ═══════════════════════════════════════════════════════════════

REPO_ROOT = Path(__file__).resolve().parents[2]

MAVEN = "https://repo1.maven.org/maven2"

KOTLIN_VERSION = "2.2.20"
COROUTINES_VERSION = "1.10.2"
SERIALIZATION_VERSION = "1.9.0"
OKHTTP_VERSION = "5.1.0"
OKIO_VERSION = "3.16.0"
JUNIT_VERSION = "4.13.2"

# 运行编译器本身需要的 jar（不是被测代码的依赖）
COMPILER_JARS = [
    f"org/jetbrains/kotlin/kotlin-compiler-embeddable/{KOTLIN_VERSION}/kotlin-compiler-embeddable-{KOTLIN_VERSION}.jar",
    f"org/jetbrains/kotlin/kotlin-stdlib/{KOTLIN_VERSION}/kotlin-stdlib-{KOTLIN_VERSION}.jar",
    f"org/jetbrains/kotlin/kotlin-script-runtime/{KOTLIN_VERSION}/kotlin-script-runtime-{KOTLIN_VERSION}.jar",
    f"org/jetbrains/kotlin/kotlin-daemon-embeddable/{KOTLIN_VERSION}/kotlin-daemon-embeddable-{KOTLIN_VERSION}.jar",
    f"org/jetbrains/kotlin/kotlin-reflect/{KOTLIN_VERSION}/kotlin-reflect-{KOTLIN_VERSION}.jar",
    "org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar",
    "org/jetbrains/annotations/13.0/annotations-13.0.jar",
    # 编译器自身要用它（CoreApplicationEnvironment 会加载 CoroutineScope），
    # 少了会抛 NoClassDefFoundError —— 报错信息完全指不到这里，踩过一次
    f"org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/{COROUTINES_VERSION}/kotlinx-coroutines-core-jvm-{COROUTINES_VERSION}.jar",
]

# kotlinx.serialization 编译器插件 —— 少了它 @Serializable 生成不出序列化器
SERIALIZATION_PLUGIN = (
    f"org/jetbrains/kotlin/kotlin-serialization-compiler-plugin-embeddable/"
    f"{KOTLIN_VERSION}/kotlin-serialization-compiler-plugin-embeddable-{KOTLIN_VERSION}.jar"
)

# 被测代码的依赖（全部进编译期与运行期 classpath）
LIBRARY_JARS = [
    f"org/jetbrains/kotlin/kotlin-stdlib/{KOTLIN_VERSION}/kotlin-stdlib-{KOTLIN_VERSION}.jar",
    f"org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/{COROUTINES_VERSION}/kotlinx-coroutines-core-jvm-{COROUTINES_VERSION}.jar",
    f"org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/{SERIALIZATION_VERSION}/kotlinx-serialization-core-jvm-{SERIALIZATION_VERSION}.jar",
    f"org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/{SERIALIZATION_VERSION}/kotlinx-serialization-json-jvm-{SERIALIZATION_VERSION}.jar",
    f"com/squareup/okhttp3/okhttp-jvm/{OKHTTP_VERSION}/okhttp-jvm-{OKHTTP_VERSION}.jar",
    f"com/squareup/okio/okio-jvm/{OKIO_VERSION}/okio-jvm-{OKIO_VERSION}.jar",
    f"junit/junit/{JUNIT_VERSION}/junit-{JUNIT_VERSION}.jar",
    "org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar",
]

# 参与验证的模块。
#
# ⚠️ 新增模块时必须确认它**零 Android 依赖**（不 import android.* / androidx.*），
#    否则编译会失败。这不是可以放宽的条件。
MODULES = [
    "android/provider/api",
    "android/provider/openai-compat",
    "android/core/network",
]

# JDK 17+ 跑 IntelliJ 平台编译器需要的模块开放
ADD_OPENS = [
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.nio.charset=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.text=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
]

C_OK = "\033[92m"
C_FAIL = "\033[91m"
C_DIM = "\033[90m"
C_BOLD = "\033[1m"
C_OFF = "\033[0m"


def _supports_color() -> bool:
    if os.environ.get("NO_COLOR"):
        return False
    if not sys.stdout.isatty():
        return False
    if os.name == "nt" and not os.environ.get("WT_SESSION"):
        # 老 conhost 不认 ANSI，除非已启用 VT
        return bool(os.environ.get("ANSICON"))
    return True


USE_COLOR = _supports_color()


def c(text: str, color: str) -> str:
    return f"{color}{text}{C_OFF}" if USE_COLOR else text


def step(msg: str) -> None:
    print(f"\n{c('▸', C_BOLD)} {c(msg, C_BOLD)}")


def info(msg: str) -> None:
    print(f"  {msg}")


def warn(msg: str) -> None:
    print(f"  {c('!', C_FAIL)} {msg}")


# ═══════════════════════════════════════════════════════════════
#  依赖获取
# ═══════════════════════════════════════════════════════════════


def default_deps_dir() -> Path:
    """依赖缓存放用户级目录，避免污染仓库（仓库里放 70MB 二进制是灾难）。"""
    override = os.environ.get("POCKETAGENT_VERIFY_DEPS")
    if override:
        return Path(override)
    return Path.home() / ".workbuddy-ai" / "binaries" / "kotlin-verify" / "deps"


def default_work_dir() -> Path:
    return Path.home() / ".workbuddy-ai" / "binaries" / "kotlin-verify" / "work"


def fetch(rel_path: str, deps_dir: Path) -> Path:
    """下载（或复用缓存）一个 Maven 构件，返回本地路径。"""
    target = deps_dir / Path(rel_path).name
    if target.exists() and target.stat().st_size > 0:
        return target

    url = f"{MAVEN}/{rel_path}"
    tmp = target.with_suffix(target.suffix + ".part")
    info(f"下载 {target.name}")
    try:
        with urllib.request.urlopen(url, timeout=180) as resp, open(tmp, "wb") as fh:
            shutil.copyfileobj(resp, fh)
    except Exception as exc:  # noqa: BLE001 — 这里就是要给出人话报错
        tmp.unlink(missing_ok=True)
        raise SystemExit(
            f"\n{c('依赖下载失败', C_FAIL)}：{url}\n  {exc}\n\n"
            f"  检查网络，或手动把该 jar 放到：{deps_dir}\n"
        ) from exc

    tmp.replace(target)
    return target


def ensure_deps(deps_dir: Path, clean: bool) -> tuple[list[Path], list[Path], Path]:
    if clean and deps_dir.exists():
        step(f"清理依赖缓存 {deps_dir}")
        shutil.rmtree(deps_dir, ignore_errors=True)
    deps_dir.mkdir(parents=True, exist_ok=True)

    step(f"准备依赖（缓存目录 {deps_dir}）")
    compiler = [fetch(p, deps_dir) for p in COMPILER_JARS]
    plugin = fetch(SERIALIZATION_PLUGIN, deps_dir)
    libs = [fetch(p, deps_dir) for p in LIBRARY_JARS]
    info(f"编译器 {len(compiler)} 个 jar，运行库 {len(libs)} 个 jar，插件 1 个")
    return compiler, libs, plugin


# ═══════════════════════════════════════════════════════════════
#  源码收集
# ═══════════════════════════════════════════════════════════════


def collect_sources(repo_root: Path) -> tuple[list[Path], list[str]]:
    """收集待编译源码，并推导出测试类全限定名。"""
    main_sources: list[Path] = []
    test_sources: list[Path] = []
    test_classes: list[str] = []

    for module in MODULES:
        module_dir = repo_root / module
        if not module_dir.is_dir():
            raise SystemExit(f"模块目录不存在：{module_dir}")

        main_sources += sorted(module_dir.glob("src/main/kotlin/**/*.kt"))
        module_tests = sorted(module_dir.glob("src/test/kotlin/**/*.kt"))
        test_sources += module_tests

        for f in module_tests:
            fqcn = derive_test_class(f)
            if fqcn:
                test_classes.append(fqcn)

    if not main_sources:
        raise SystemExit("没有找到任何源码，MODULES 配置可能不对")

    return main_sources + test_sources, test_classes


def derive_test_class(path: Path) -> str | None:
    """从 .kt 文件推出类全限定名。只认「文件名 == 主类名」这种常规写法。"""
    text = path.read_text(encoding="utf-8", errors="replace")

    pkg = None
    for line in text.splitlines():
        m = re.match(r"^\s*package\s+([\w.]+)\s*$", line)
        if m:
            pkg = m.group(1)
            break

    stem = path.stem
    if not stem.endswith("Test"):
        return None

    return f"{pkg}.{stem}" if pkg else stem


# ═══════════════════════════════════════════════════════════════
#  编译与运行
# ═══════════════════════════════════════════════════════════════


def find_java() -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.exists():
            return str(candidate)
    found = shutil.which("java")
    if not found:
        raise SystemExit(
            "找不到 java。请安装 JDK 17 或更高版本，并确保它在 PATH 里。"
        )
    return found


def java_version(java: str) -> str:
    try:
        out = subprocess.run(
            [java, "-version"], capture_output=True, text=True, timeout=30
        )
        return (out.stderr or out.stdout).strip().splitlines()[0]
    except Exception:  # noqa: BLE001
        return "未知"


def compile_kotlin(
    java: str, compiler_cp: list[Path], lib_cp: list[Path], plugin: Path,
    sources: list[Path], out_dir: Path,
) -> bool:
    step("编译 Kotlin 源码")

    cmd = [
        java, *ADD_OPENS,
        "-Dfile.encoding=UTF-8",
        "-cp", os.pathsep.join(str(p) for p in compiler_cp),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib",
        "-classpath", os.pathsep.join(str(p) for p in lib_cp),
        "-jvm-target", "17",
        "-Xplugin=" + str(plugin),
        "-d", str(out_dir),
        *[str(s) for s in sources],
    ]

    info(f"源文件 {len(sources)} 个")
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                            errors="replace")

    output = (result.stdout or "") + (result.stderr or "")
    if output.strip():
        for line in output.strip().splitlines():
            # 过滤掉噪音，但保留真正的错误
            if line.strip():
                print(f"    {line}")

    if result.returncode != 0:
        warn(f"编译失败（退出码 {result.returncode}）")
        return False

    info(c("编译通过", C_OK))
    return True


def run_junit(java: str, lib_cp: list[Path], out_dir: Path, test_classes: list[str]) -> bool:
    step("运行单元测试")

    if not test_classes:
        warn("没有发现测试类")
        return False

    for name in test_classes:
        info(f"· {name}")

    cmd = [
        java, *ADD_OPENS,
        # 不设这三个，中文测试名在 Windows 控制台上会变成乱码
        # （JVM 默认按系统 ANSI 代码页输出，而源码是 UTF-8）
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
        "-cp", os.pathsep.join([str(out_dir)] + [str(p) for p in lib_cp]),
        "org.junit.runner.JUnitCore",
        *test_classes,
    ]

    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                            errors="replace")
    output = (result.stdout or "") + (result.stderr or "")

    for line in output.rstrip().splitlines():
        print(f"    {line}")

    if result.returncode != 0:
        warn("测试未全部通过")
        return False

    info(c("全部通过", C_OK))
    return True


# ═══════════════════════════════════════════════════════════════
#  主流程
# ═══════════════════════════════════════════════════════════════


def main() -> int:
    parser = argparse.ArgumentParser(
        description="在没有 Android SDK 的机器上编译并测试纯 Kotlin 模块",
    )
    parser.add_argument("--clean", action="store_true", help="清掉依赖缓存后重新下载")
    parser.add_argument("--keep-work", action="store_true", help="保留编译中间产物")
    parser.add_argument("--deps", type=Path, default=None, help="依赖缓存目录")
    parser.add_argument("--work", type=Path, default=None, help="编译产物目录")
    parser.add_argument("--repo", type=Path, default=None, help="仓库根目录")
    args = parser.parse_args()

    repo_root = (args.repo or REPO_ROOT).resolve()
    deps_dir = (args.deps or default_deps_dir()).resolve()
    work_dir = (args.work or default_work_dir()).resolve()
    out_dir = work_dir / "classes"

    print(c("PocketAgent · 纯 Kotlin 逻辑验证", C_BOLD))
    print(f"  仓库      {repo_root}")
    print(f"  依赖缓存  {deps_dir}")

    java = find_java()
    info(f"JDK       {java_version(java)}")

    compiler_cp, lib_cp, plugin = ensure_deps(deps_dir, args.clean)

    sources, test_classes = collect_sources(repo_root)

    if out_dir.exists():
        shutil.rmtree(out_dir, ignore_errors=True)
    out_dir.mkdir(parents=True, exist_ok=True)

    ok = compile_kotlin(java, compiler_cp, lib_cp, plugin, sources, out_dir)
    if ok:
        ok = run_junit(java, lib_cp, out_dir, test_classes)

    if not args.keep_work and out_dir.exists():
        shutil.rmtree(out_dir, ignore_errors=True)

    print()
    if ok:
        print(c("✓ 验证通过", C_OK))
        return 0

    print(c("✗ 验证失败", C_FAIL))
    return 1


if __name__ == "__main__":
    sys.exit(main())
