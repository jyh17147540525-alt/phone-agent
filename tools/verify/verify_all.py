#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
**一个人口，跑完所有检查。**

═══════════════════════════════════════════════════════════════
★★ 2026-11-01：为什么需要这个
═══════════════════════════════════════════════════════════════

`tools/verify` 下曾有 16 个脚本，而此前几轮**只跑了一个**
（`run_logic_tests.py`）。另外 15 个从没执行过 ——
第一次全跑就逮到 4 个真问题（界面里的 markdown 星号、
测试夹具被当成真密钥、我自己引入的 1 处 CRLF、签名缺口）。

**建了 16 个检查、只跑 1 个，等于只有 1 个检查。**

⇒ 这个脚本把所有**不需要人工参数**的检查串起来，一条命令跑完，
失败就退出码非 0。它自己不做检查，只负责调度与汇总。

═══════════════════════════════════════════════════════════════
设计取舍
═══════════════════════════════════════════════════════════════

**① 全部跑完再汇总，不快速失败**
   一次就知道有几处坏了，比修一处重跑一次快得多。

**② 每个脚本只打印最后一行的要点**
   完整输出太吵；要点足够判断。需要细看时单独跑那个脚本。

**③ 需要前置条件的检查会被"跳过"，不报失败**
   · `check_release_apk.py` 需要先 `assembleRelease`
   · `check_line_endings.py` 需要一个早于本轮的基线
   跳过时说清**怎么补**，而不是静默略过。

**④ 输出对齐、带耗时**
   一个跑 3 分钟的入口如果不告诉你哪个慢，下次就不想再跑。

用法：
    python tools/verify/verify_all.py            # 跑全部
    python tools/verify/verify_all.py --fast     # 跳过最慢的（逻辑测试）
    python tools/verify/verify_all.py --list     # 只列出会跑什么
"""

import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def _resolve_apk() -> str | None:
    p = os.path.join(
        REPO_ROOT, "android", "app", "build", "outputs", "apk", "release",
    )
    if not os.path.isdir(p):
        return None
    hits = [f for f in os.listdir(p) if f.endswith(".apk")]
    return os.path.join(p, hits[0]) if hits else None


# ═══════════════════════════════════════════════════════════════
#  检查清单
# ═══════════════════════════════════════════════════════════════
#
# 每一条：(脚本, 参数, 一句话说明, 是否慢)
CHECKS: list[tuple[str, list[str], str, bool]] = [
    ("run_logic_tests.py", [], "离线编译 + 跑全部逻辑测试", True),
    ("check_kt_quotes.py", [], "Kotlin 中文文案里的 ASCII 引号误用", False),
    ("check_xml_comments.py", [], "XML 注释合法性（-- 等）", False),
    ("check_version_catalog.py", [], "libs.* 引用都有定义", False),
    ("check_no_secrets.py", [], "已跟踪文件里没有疑似密钥", False),
    ("check_module_deps.py", ["android"], "跨模块引用都有 project 声明", False),
    ("check_ui_markdown.py", ["android"], "界面文案里没有 markdown 星号", False),
    ("check_release_readiness.py", ["android"], "发行就绪的 9 组规则", False),
    ("audit_test_naming.py", [], "测试类名可被跑起来（不会被静默跳过）", False),
    ("audit_shared_mutable.py", [], "类级可变集合没有新增的并发风险", False),
]


def run_one(script: str, args: list[str]) -> tuple[int, str, float]:
    path = os.path.join(HERE, script)
    if not os.path.isfile(path):
        return 127, "脚本不存在", 0.0

    started = time.time()
    proc = subprocess.run(
        [sys.executable, path, *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        cwd=REPO_ROOT,
    )
    elapsed = time.time() - started

    out = (proc.stdout or "") + (proc.stderr or "")
    lines = [ln.strip() for ln in out.splitlines() if ln.strip()]
    summary = lines[-1] if lines else "（无输出）"
    # 失败时给出更有信息量的行。
    #
    # ⚠️ 优先级是**从"有数字的结论"到"建议"**：
    #    多数检查脚本最后一行是"改法：……"，那不是**发现**，是**建议**。
    #    摘要把建议当结论，等于告诉人"出了什么问题"却答成"该怎么改"。
    if proc.returncode != 0:
        for pattern in ("✗", "发现", "!!", "[R", "error", "错误"):
            hit = next((ln for ln in reversed(lines) if pattern in ln), None)
            if hit:
                summary = hit
                break
    return proc.returncode, summary, elapsed


def main() -> int:
    fast = "--fast" in sys.argv
    list_only = "--list" in sys.argv

    if list_only:
        print("会跑这些检查：\n")
        for script, args, desc, slow in CHECKS:
            mark = "（慢）" if slow else ""
            print(f"  {script:32} {desc}{mark}")
        print("\n另有两条需要前置条件，默认不跑：")
        print("  check_release_apk.py          需先 assembleRelease")
        print("  check_line_endings.py <rev>   需一个早于本轮的基线")
        return 0

    print("PocketAgent · 全量检查")
    print(f"  android 根  {os.path.join(REPO_ROOT, 'android')}")
    print()

    results: list[tuple[str, str, int, str, float]] = []

    for script, args, desc, slow in CHECKS:
        if slow and fast:
            results.append((script, desc, 0, "跳过（--fast）", 0.0))
            print(f"· {script:32} 跳过（--fast）")
            continue
        print(f"· {script:32} 跑…", end="", flush=True)
        code, summary, elapsed = run_one(script, args)
        results.append((script, desc, code, summary, elapsed))
        mark = "✓" if code == 0 else "✗"
        print(f"\r· {script:32} {mark} {elapsed:5.1f}s  {summary}")

    # ── 需要前置条件的两条 ──────────────────────────────────
    extra: list[tuple[str, str, int, str, float]] = []

    apk = _resolve_apk()
    if apk:
        print(f"· {'check_release_apk.py':32} 跑…", end="", flush=True)
        code, summary, elapsed = run_one("check_release_apk.py", [apk])
        extra.append(("check_release_apk.py", "发行包安全检查", code, summary, elapsed))
        mark = "✓" if code == 0 else "✗"
        print(f"\r· {'check_release_apk.py':32} {mark} {elapsed:5.1f}s  {summary}")
    else:
        extra.append((
            "check_release_apk.py", "发行包安全检查", 0,
            "跳过：没有 release APK（先跑 assembleRelease）", 0.0,
        ))
        print(f"· {'check_release_apk.py':32} 跳过：没有 release APK")

    # check_line_endings 需要一个早于本轮的基线；HEAD 自身等于没查
    code, summary, elapsed = run_one("check_line_endings.py", ["HEAD~60"])
    extra.append(("check_line_endings.py", "CRLF 未增多", code, summary, elapsed))
    mark = "✓" if code == 0 else "✗"
    print(f"· {'check_line_endings.py':32} {mark} {elapsed:5.1f}s  {summary}")

    all_results = results + extra
    failed = [r for r in all_results if r[2] != 0]
    skipped = [r for r in all_results if "跳过" in r[3]]

    print()
    total_time = sum(r[4] for r in all_results)
    print(f"跑了 {len(all_results) - len(skipped)} 条 / 共 {len(all_results)} 条"
          f"｜失败 {len(failed)}｜跳过 {len(skipped)}｜耗时 {total_time:.0f}s")

    if failed:
        print()
        print(f"✗ {len(failed)} 条未通过：")
        for script, desc, _code, summary, _t in failed:
            print(f"  · {script}（{desc}）")
            print(f"    {summary}")
        print()
        print("单独重跑那一条看完整输出。")
        return 1

    print()
    print("✓ 全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
