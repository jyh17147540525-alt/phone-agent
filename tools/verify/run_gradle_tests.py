#!/usr/bin/env python3
"""跑 Gradle 侧的单元测试 —— 补上 `verify_all` 一直缺的那一块。

═══════════════════════════════════════════════════════════════
 ★ 为什么必须有这个脚本（2026-11-04 审计）
═══════════════════════════════════════════════════════════════

`verify_all.py` 的 12 项检查全绿的时候，`gradlew :mcp:test` 是**红的**：

    204 tests completed, 7 failed
    java.lang.NoSuchMethodError:
      'void com.pocketagent.personalogic.PersonaState.<init>(int, int,
       java.lang.String, int, kotlin.jvm.internal.DefaultConstructorMarker)'

原因不是代码写错，是**增量构建的 up-to-date 判定不可信**：
`PersonaState` 加了两个带默认值的参数（`hormones` / `physiology`），
Kotlin 的**桥接构造器签名跟着变**；`personalogic` 重编了，`mcp` 的 class
却因为 `org.gradle.caching=true` + 输出被判定 UP-TO-DATE 而没重编，
于是运行期 `NoSuchMethodError`。

`--rerun-tasks` 一跑就全绿 —— 这一类失败**只有真跑 Gradle 才看得见**，
而 `run_logic_tests.py` 用的是 kotlinc 一次性全量编译，天生不会遇到它。

⇒ 两个脚本管的不是同一件事，必须都跑：
   · `run_logic_tests.py` —— 逻辑对不对（快、全量编译、无增量）
   · 本脚本               —— **构建产物对不对**（慢、走真实增量管线）

═══════════════════════════════════════════════════════════════
 ★ 为什么读 JUnit XML 而不是解析控制台
═══════════════════════════════════════════════════════════════

Gradle **成功时不打印用例数**，只在失败时打 "N tests completed, M failed"。
按控制台判"通过"会踩两个坑：

  1. 任务判 UP-TO-DATE ⇒ 一行都不打 ⇒ 看起来像"0 个用例通过"；
  2. 而 UP-TO-DATE 恰恰是陈旧产物那种失败的样子 —— 测试**根本没跑**。

⇒ 现在固定 `--rerun-tasks`（保证真跑），然后读
`<module>/build/test-results/test/*.xml` 数用例与失败。
拿不到 XML 就判失败：**"没跑"不等于"通过"**。
"""

import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
ANDROID_DIR = os.path.join(REPO_ROOT, "android")

# 要跑的 Gradle 测试任务。⚠️ 加模块就往这里加 —— 但别把整个 `test` 塞进来：
# 那样一次要几分钟，人会开始跳过这一步，等于没加。
#
# ★ 2026-11-04 加 `:app:testDebugUnitTest`：两档汇合点（`TieredDispatchPort` /
#   `TieredScreenReader`）住在 `:app`，而 `:app` 有 Android 依赖，进不了
#   `run_logic_tests.py` 的零依赖编译 —— 于是那两个类的"档位选错也不报错"
#   的分支以前**只有编译验证**。它们现在由 Gradle 的 JVM 单测覆盖。
TASKS = [":mcp:test", ":app:testDebugUnitTest"]

# 增量产物陈旧的指纹。
STALE_MARKERS = ("NoSuchMethodError", "AbstractMethodError", "NoClassDefFoundError")

TIMEOUT_SEC = 900


def _result_dirs() -> list[str]:
    """`:mcp:test` → `mcp/build/test-results/test`；`:app:testDebugUnitTest` → `app/build/test-results/testDebugUnitTest`。"""
    out = []
    for t in TASKS:
        parts = t.split(":")
        mod, task = parts[1], parts[-1]
        out.append(os.path.join(ANDROID_DIR, mod, "build", "test-results", task))
    return out


def _gradlew() -> str:
    for name in ("gradlew.bat", "gradlew"):
        p = os.path.join(ANDROID_DIR, name)
        if os.path.isfile(p):
            return p
    return ""


def _java_home() -> str:
    """Gradle 8.13 不接受 JDK 25；没配就用项目约定的 17。"""
    cur = os.environ.get("JAVA_HOME", "")
    if cur and os.path.isdir(cur):
        major = _jdk_major(cur)
        if major is None or major <= 21:
            return cur
    for cand in (
        r"D:\AndroidDev\jdk\jdk-17.0.13+11",
        r"D:\AndroidDev\jdk\jdk-17",
    ):
        if os.path.isdir(cand):
            return cand
    return cur


def _jdk_major(home: str) -> int | None:
    try:
        out = subprocess.run(
            [os.path.join(home, "bin", "java"), "-version"],
            capture_output=True, text=True, timeout=20,
        )
        text = (out.stderr or "") + (out.stdout or "")
        m = re.search(r'version "(\d+)', text)
        return int(m.group(1)) if m else None
    except Exception:
        return None


def run() -> tuple[int, str]:
    gradlew = _gradlew()
    if not gradlew:
        return 127, "找不到 gradlew（android/ 下没有 wrapper）"

    env = dict(os.environ)
    jh = _java_home()
    if jh:
        env["JAVA_HOME"] = jh

    cmd = [gradlew, *TASKS, "--console=plain", "--offline",
           # ⚠️ 必须 rerun：UP-TO-DATE 时测试不跑，而"没跑"正是陈旧产物的样子
           "--rerun-tasks",
           # 路径含中文，必须跳过 AGP 的路径检查
           "-Pandroid.overridePathCheck=true"]
    if os.name == "nt":
        cmd = ["cmd", "/c", *cmd]

    try:
        proc = subprocess.run(
            cmd, cwd=ANDROID_DIR, env=env, timeout=TIMEOUT_SEC,
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )
    except subprocess.TimeoutExpired:
        return 124, f"超时（>{TIMEOUT_SEC}s）"
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def read_results() -> tuple[int, int, str]:
    """读 JUnit XML：返回 (用例数, 失败数, 第一条失败的用例名)。"""
    total = failed = 0
    first = ""
    for d in _result_dirs():
        for path in sorted(glob.glob(os.path.join(d, "TEST-*.xml"))):
            try:
                root = ET.parse(path).getroot()
            except Exception as e:  # 解析失败本身就是问题，别静默跳过
                return total, failed + 1, f"测试报告解析失败 {os.path.basename(path)}: {e}"
            suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
            for suite in suites:
                for case in suite.findall("testcase"):
                    total += 1
                    if case.find("failure") is not None or case.find("error") is not None:
                        failed += 1
                        if not first:
                            cls = case.get("classname", "")
                            first = f"{cls}.{case.get('name', '')}".strip(".")
    return total, failed, first


def first_stale_line(out: str) -> str:
    for line in out.splitlines():
        if any(m in line for m in STALE_MARKERS):
            return line.strip()
    return ""


def main() -> int:
    code, out = run()
    total, failed, first = read_results()

    if code == 0 and total > 0 and failed == 0:
        print(f"Gradle 测试通过（{total} 个用例，{', '.join(TASKS)}）")
        return 0

    if code == 0 and total == 0:
        print("Gradle 报告成功，但没有产出任何测试结果 —— 这条检查不算通过")
        print("  复现：cd android && gradlew " + " ".join(TASKS) + " --rerun-tasks")
        return 1

    stale = first_stale_line(out)
    if stale:
        print(f"Gradle 测试失败（产物陈旧）：{stale[:160]}")
        print("  ⚠️ NoSuchMethodError/AbstractMethodError 多半是增量构建拿到了旧 class。"
              "本脚本已强制 --rerun-tasks，所以这**不是**缓存问题，看模块签名是否真的一致。")
    elif first:
        print(f"Gradle 测试失败：{failed}/{total} 个用例没过 —— 首个 {first}")
    else:
        tail = [ln.strip() for ln in out.splitlines() if ln.strip()]
        print(f"Gradle 测试失败：{tail[-1] if tail else '（无输出）'}")
    print("  复现：cd android && gradlew " + " ".join(TASKS) + " --console=plain")
    return 1


if __name__ == "__main__":
    sys.exit(main())
