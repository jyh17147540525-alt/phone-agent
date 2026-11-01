#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
共享可变集合审计（质量清单第 1 条的可执行版）。

═══════════════════════════════════════════════════════════════
★★ 2026-11-01：把文档里的伪代码变成真能跑的检查
═══════════════════════════════════════════════════════════════

`docs/工程/质量清单-并发与共享状态.md` 第 1 条写着：

> 一个类级 `mutableListOf` / `mutableMapOf` 被 suspend 函数、Flow、
> 或多线程路径写入 ⇒ 并发写风险

它附了一段 PowerShell 片段，但**从没被执行过** —— 那就是"写了检查但
没有检查"。这个脚本把它变成可运行的。

═══════════════════════════════════════════════════════════════
为什么需要 baseline，而不是"发现就报错"
═══════════════════════════════════════════════════════════════

类级可变集合**多到无法全部消除**（缓存、缓冲、索引）。一上来就全红，
结果一定是被无视 —— 然后这个检查就死了。

⇒ 用 baseline：
· 已有的一律记进基线并写明"为什么它是安全的"
· **新增的**才报错
· 基线里的条目消失了也提示（说明有人顺手修了，基线该更新）

═══════════════════════════════════════════════════════════════
判定规则
═══════════════════════════════════════════════════════════════

一个类级可变集合被标记为**风险**，当且仅当：

1. 它在生产代码里（排除 test/ 与 build/）
2. 所在**文件**没有任何同步原语
   （`synchronized` / `Mutex` / `Concurrent*` / `CopyOnWrite` / `Atomic*`）
3. 所在**文件**出现并发上下文
   （`suspend fun` / `flow {` / `channelFlow` / `launch` / `Thread(`）
4. 这个位置不在 baseline 里

⚠️ 规则 2 与 3 是**文件级**的粗判，会有假阴性
   （同一个文件里 A 类有锁、B 类没锁会被一起放过）。
   **粗判是有意的**：宁可漏，不要因为满屏假阳性而让检查失效。
   真正的判断还是靠人 —— 这个脚本只负责"把可疑的地方摆出来"。

用法：
    python tools/verify/audit_shared_mutable.py            # 检查
    python tools/verify/audit_shared_mutable.py --list     # 只列出全部
"""

import os
import re
import sys

# ── 路径 ────────────────────────────────────────────────────────
HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SRC_ROOT = os.path.join(REPO_ROOT, "android")
BASELINE = os.path.join(HERE, "shared_mutable_baseline.txt")

# ── 规则 ────────────────────────────────────────────────────────

# 类级可变集合：4 空格缩进起（成员位置）
COLLECTION_RE = re.compile(
    r"^\s{4}(?:private\s+|internal\s+)?val\s+(\w+)\s*=\s*"
    r"(mutableListOf|mutableMapOf|mutableSetOf|ArrayList|HashMap|LinkedHashMap|"
    r"ArrayDeque|LinkedList)"
)

# ⚠️ 必须包含 `@Synchronized`。
#    第一版漏了它，于是 `CapabilityAuditLog` / `FileAuditLog` 被误判成
#    "无同步" —— 而那两处明明各有 4~5 个 `@Synchronized`。
#    一个会误报的检查等于没有检查：报几次错之后人就学会无视它了。
SYNC_RE = re.compile(
    r"synchronized\s*[({]|@Synchronized|\bMutex\b|Concurrent\w+|CopyOnWrite\w+"
    r"|Atomic\w+|@Volatile"
)

CONCURRENT_RE = re.compile(
    r"\bsuspend\s+fun\b|\bflow\s*\{|\bchannelFlow\s*\{|\blaunch\s*[({]|Thread\s*\("
)


def production_files():
    for root, dirs, files in os.walk(SRC_ROOT):
        dirs[:] = [d for d in dirs if d not in ("build", "test")]
        if os.sep + "test" + os.sep in root + os.sep:
            continue
        for f in files:
            if f.endswith(".kt"):
                yield os.path.join(root, f)


def scan():
    """返回 [(相对路径, 行号, 变量名, 类型, 有同步?, 有并发?)]"""
    findings = []
    for path in production_files():
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        lines = text.splitlines()
        has_sync = bool(SYNC_RE.search(text))
        has_conc = bool(CONCURRENT_RE.search(text))
        rel = os.path.relpath(path, REPO_ROOT).replace("\\", "/")

        for i, line in enumerate(lines, start=1):
            m = COLLECTION_RE.match(line)
            if m:
                findings.append(
                    (rel, i, m.group(1), m.group(2), has_sync, has_conc)
                )
    return findings


def load_baseline() -> dict[str, str]:
    """基线格式：<相对路径>:<变量名>  # 理由"""
    out: dict[str, str] = {}
    if not os.path.isfile(BASELINE):
        return out
    for raw in open(BASELINE, encoding="utf-8"):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, _, reason = line.partition("#")
        out[key.strip()] = reason.strip()
    return out


def main() -> int:
    list_only = "--list" in sys.argv
    findings = scan()

    risky = [f for f in findings if not f[4] and f[5]]

    if list_only:
        print(f"共 {len(findings)} 个类级可变集合，其中 {len(risky)} 个可疑：\n")
        for rel, ln, name, kind, has_sync, _ in findings:
            mark = "  " if has_sync else "⚠ "
            print(f"{mark}{rel}:{ln}  {name} = {kind}")
        return 0

    # ══════════════════════════════════════════════════════════
    #  ★ 把"文件级粗判"的假阴性**显式列出来**
    # ══════════════════════════════════════════════════════════
    #
    # 实测踩到：往 `GatewayCore.kt` 植入一个未同步的共享集合，
    # 脚本**没报** —— 因为那个文件里已经有 `synchronized`
    # （`InMemoryUsageRecorder` 的修复），而规则是**文件级**的，
    # 于是整个文件都被豁免。
    #
    # ⇒ 不假装它不存在。单独列出来，标成"需人眼确认"。
    #    这类不该让检查失败（数量多、多数是真安全），
    #    但也不能沉默 —— 沉默就是第二个"写了检查但没有检查"。
    file_level_exempt = [
        f for f in findings if f[4] and f[5]
    ]

    baseline = load_baseline()
    new_risks = []
    for rel, ln, name, kind, _has_sync, _has_conc in risky:
        key = f"{rel}:{name}"
        if key not in baseline:
            new_risks.append((rel, ln, name, kind))

    stale = [
        k for k in baseline
        if not any(f"{f[0]}:{f[2]}" == k for f in findings)
    ]

    print(f"类级可变集合 {len(findings)} 个｜无同步且文件内有并发 {len(risky)} 个"
          f"｜基线 {len(baseline)} 条")
    print()

    if file_level_exempt:
        print(f"⚠ {len(file_level_exempt)} 处**因同文件内有同步原语而被放过**"
              f"（文件级粗判，需人眼确认）：")
        for rel, ln, name, kind, _s, _c in file_level_exempt:
            print(f"  · {rel}:{ln}  {name} = {kind}")
        print("  说明：只要同一文件里**任何一处**用了 synchronized/Mutex/…，")
        print("        整个文件的集合都会被放过。这是刻意选粗的 ——")
        print("        宁可漏，也不要满屏假阳性让检查失效。")
        print()

    if new_risks:
        print(f"✗ {len(new_risks)} 处**新增**风险（基线里没有）：")
        for rel, ln, name, kind in new_risks:
            print(f"  · {rel}:{ln}  {name} = {kind}")
        print()
        print("处置方式（二选一）：")
        print("  1. 加同步（synchronized / Mutex / Concurrent* / CopyOnWrite）")
        print("  2. 确认它只被单协程访问 ⇒ 加进 shared_mutable_baseline.txt 并写明理由")
    else:
        print("✓ 没有新增风险")

    if stale:
        print()
        print(f"提示：{len(stale)} 条基线已失效（对应代码没了），可以删掉：")
        for k in stale[:10]:
            print(f"  · {k}")
        if len(stale) > 10:
            print(f"  …… 还有 {len(stale) - 10} 条")

    return 1 if new_risks else 0


if __name__ == "__main__":
    sys.exit(main())
