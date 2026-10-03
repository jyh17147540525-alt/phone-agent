#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FGS 保活 · 真机验证一键脚本（**在 Windows 上运行**）

═══════════════════════════════════════════════════════════════
 它解决什么问题
═══════════════════════════════════════════════════════════════

2026-10-03 真机复验发现：App 退到后台后进程被冷冻（cached app freezer），
MCP 服务器**在听但不响应**（12 秒超时、0 响应）—— 而 screen_read 的
真实场景恰恰是"读别的 App、自己在后台"。修复（FGS 保活）的验收，
必须用真机跑下面这条**关键判据**：

    按 Home 让 App 进后台 → 等 20 秒 → 请求 tools/list → **应返回 200**

修复前这里是超时。本脚本把"构建 → 安装 → 重开无障碍 → 引导开桥 →
前台/后台判据 → ⑦反向安全验证 → 遮蔽目击 → 取证报告"整条链自动化，
只留下**两次手机上的手动操作**（点开桥、摆好被测界面）。

═══════════════════════════════════════════════════════════════
 怎么跑
═══════════════════════════════════════════════════════════════

    tools\\device\\verify-fgs.cmd                 ← 双击这一个就行（推荐）
    # 或手动：
    python tools/device/verify_fgs.py [选项]

    选项：
      --adb <路径>        指定 adb（默认按 P0 工具的候选列表探测）
      --skip-build        跳过 gradle 构建（重跑测试时用）
      --skip-install      跳过安装
      --wait <秒>         后台等待秒数（默认 20）
      --yes               不等待回车（全自动档；仍需要手机上的手动操作）
      --local-port <端口> 本地转发端口（默认自动挑 38999 起）

═══════════════════════════════════════════════════════════════
 结果在哪
═══════════════════════════════════════════════════════════════

    tools/device/reports/fgs-verify-<时间戳>.txt / .json

    ⚠️ 报告**不包含**桥的令牌（token）—— 它只在内存里活一次，
       所有落盘文本都经过脱敏。报告可以直接交给任何人。

═══════════════════════════════════════════════════════════════
 与既有工具的关系
═══════════════════════════════════════════════════════════════

adb 的探测/编码/超时/错误分类全部复用 `tools/p0/p0kit/adb.py`
（那里踩过的坑：Windows GBK 解码、空参数被 shell 吃掉、错误分类），
本脚本**不重新发明**这些。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "p0"))

from p0kit.adb import Adb, AdbNotFound, find_adb  # noqa: E402

PKG = "com.pocketagent.debug"
# ⚠️ 组件名的两半是**两个不同的包**：applicationId（com.pocketagent.debug）
#    与代码命名空间（com.pocketagent）。写成 f"{PKG}/{PKG}.assistant…"
#    会拼出一个永不存在的组件 —— 实测拼错的版本会把无障碍悄悄写坏。
A11Y_COMPONENT = f"{PKG}/com.pocketagent.assistant.AgentAccessibilityService"
ACTIVITY = f"{PKG}/com.pocketagent.MainActivity"
APK_REL = "android/app/build/outputs/apk/debug/app-debug.apk"
DRAFT_REL = "files/mcp/cordis.patch.yml"
REQUEST_TIMEOUT = 12.0

MASK_MARKER = "[已遮蔽]"

# 输出编码兜底：Windows 控制台/重定向到管道时的乱码与异常都不能让脚本崩
try:
    sys.stdout.reconfigure(errors="replace")  # type: ignore[attr-defined]
    sys.stderr.reconfigure(errors="replace")  # type: ignore[attr-defined]
except Exception:
    pass


# ═══════════════════════════════════════════════════════════════
#  记录与脱敏
# ═══════════════════════════════════════════════════════════════

TOKEN_RE = re.compile(r"Bearer\s+\S+")


def redact(text: str) -> str:
    """令牌绝不落盘 —— 报告要能被随手转发。"""
    return TOKEN_RE.sub("Bearer <已隐去>", text)


class Recorder:
    def __init__(self) -> None:
        self.rows: list[dict] = []
        self.console: list[str] = []

    def log(self, msg: str = "") -> None:
        line = redact(msg)
        print(line, flush=True)
        self.console.append(line)

    def record(self, phase: str, name: str, status: str, detail: str = "", evidence: str = "") -> None:
        """
        status ∈ PASS / FAIL / WARN / INFO / SKIP

        WARN 与 FAIL 的区别：
          FAIL = 判据明确不满足（例如后台请求超时）
          WARN = 结果不符合预期，但可能是环境/操作问题，需要人看原文（例如
                 "拒绝了，但不是安全拒绝的文案"）
        """
        self.rows.append(
            {
                "phase": phase,
                "name": name,
                "status": status,
                "detail": redact(detail),
                "evidence": redact(evidence)[:4000],
            }
        )
        mark = {"PASS": "OK ", "FAIL": "FAIL", "WARN": "WARN", "INFO": "INFO", "SKIP": "SKIP"}.get(status, "????")
        line = f"  [{mark}] {name}" + (f" —— {redact(detail)}" if detail else "")
        print(line, flush=True)
        self.console.append(line)

    def verdict(self) -> str:
        if any(r["status"] == "FAIL" for r in self.rows):
            return "有失败项"
        if any(r["status"] == "WARN" for r in self.rows):
            return "通过，但有需要人工确认的项"
        return "通过"


# ═══════════════════════════════════════════════════════════════
#  HTTP（MCP 请求）
# ═══════════════════════════════════════════════════════════════

def rpc(port: int, token: str, method: str, params, rpc_id: int) -> dict:
    """
    向 127.0.0.1:<port>/mcp 发一次 JSON-RPC。

    ⚠️ 必须绕开系统代理（`ProxyHandler({})`）—— "localhost" 请求被带进
       某个企业代理是经典的事故现场，而这里的 127.0.0.1 是 adb forward
       打到手机上的端口，只有直连才通。
    """
    body: dict = {"jsonrpc": "2.0", "id": rpc_id, "method": method}
    if params is not None:
        body["params"] = params
    data = json.dumps(body).encode("utf-8")

    req = urllib.request.Request(f"http://127.0.0.1:{port}/mcp", data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")
    req.add_header("Authorization", f"Bearer {token}")

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    t0 = time.monotonic()
    try:
        with opener.open(req, timeout=REQUEST_TIMEOUT) as resp:
            text = resp.read().decode("utf-8", "replace")
            ms = int((time.monotonic() - t0) * 1000)
            return {"ok": True, "status": resp.status, "ms": ms, "text": text}
    except Exception as exc:  # 超时 / 拒绝连接 / 协议错误，都如实记下
        ms = int((time.monotonic() - t0) * 1000)
        return {"ok": False, "status": 0, "ms": ms, "error": f"{type(exc).__name__}: {exc}", "text": ""}


def parse_rpc(text: str):
    try:
        return json.loads(text)
    except Exception:
        return None


def node_count(text: str) -> str:
    m = re.search(r"nodeCount[=:]\s*(\d+)", text or "")
    return m.group(1) if m else "?"


# ═══════════════════════════════════════════════════════════════
#  主流程
# ═══════════════════════════════════════════════════════════════

def main() -> int:
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("--adb", default=None)
    ap.add_argument("--skip-build", action="store_true")
    ap.add_argument("--skip-install", action="store_true")
    ap.add_argument("--wait", type=int, default=20)
    ap.add_argument("--yes", action="store_true")
    ap.add_argument("--local-port", type=int, default=0)
    args = ap.parse_args()

    rec = Recorder()
    started = datetime.now()

    rec.log("═" * 64)
    rec.log(" PocketAgent · FGS 保活真机验证")
    rec.log(f" 时间：{started:%Y-%m-%d %H:%M:%S}    仓库：{ROOT}")
    rec.log("═" * 64)

    # ── P0 前置：adb 与设备 ────────────────────────────────────
    try:
        adb_path = find_adb(args.adb)
    except AdbNotFound as exc:
        rec.record("P0", "找到 adb", "FAIL", str(exc))
        return finish(rec, args, started, fatal="找不到 adb")

    adb = Adb(adb_path)
    devices = adb.devices()
    ready = [(s, st) for (s, st) in devices if st == "device"]
    if not ready:
        detail = f"adb version={adb_path}；devices={devices!r}。{adb.raw('devices').combined[:400]}"
        rec.record("P0", "设备就绪", "FAIL", "没有处于 device 状态的设备", detail)
        return finish(rec, args, started, fatal="没有可用设备")
    serial = ready[0][0]
    if len(ready) > 1:
        rec.record("P0", "设备就绪", "WARN", f"检测到多台设备，使用第一台 {serial}")
        adb = Adb(adb_path, serial=serial)
    else:
        rec.record("P0", "设备就绪", "PASS", f"serial={serial}")

    model = adb.getprop("ro.product.model") or "?"
    osver = adb.getprop("ro.build.version.release") or "?"
    rec.record("P0", "设备信息", "INFO", f"{model} / Android {osver}")

    def pause(prompt: str) -> None:
        if args.yes:
            rec.log(f"（--yes：不等回车）{prompt}")
            time.sleep(1)
            return
        try:
            input(f"\n>>> {prompt}\n>>> 完成后按回车继续… ")
        except EOFError:
            rec.log("（没有交互输入，按已完成后继续）")

    # ── P1 构建 ───────────────────────────────────────────────
    if args.skip_build:
        rec.record("P1", "gradle 构建", "SKIP", "--skip-build")
    else:
        gradlew = ROOT / "android" / ("gradlew.bat" if os.name == "nt" else "gradlew")
        if not gradlew.exists():
            rec.record("P1", "gradle 构建", "FAIL", f"找不到 {gradlew}")
            return finish(rec, args, started, fatal="找不到 gradlew")
        rec.log(f"\n── P1 构建：{gradlew.name} :app:assembleDebug（可能要几分钟）──")
        try:
            proc = subprocess.run(
                ["cmd", "/c", str(gradlew), ":app:assembleDebug", "-Pandroid.overridePathCheck=true"],
                cwd=str(ROOT / "android"),
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=1800,
            )
            out = (proc.stdout or "") + ("\n" + proc.stderr if proc.stderr else "")
        except subprocess.TimeoutExpired as exc:
            out = str(exc)
            proc = None  # type: ignore[assignment]
        tail = redact(out)[-3500:]
        if proc is not None and proc.returncode == 0 and "BUILD SUCCESSFUL" in (proc.stdout or ""):
            rec.record("P1", "gradle 构建", "PASS", "BUILD SUCCESSFUL", tail[-1200:])
        else:
            rec.record(
                "P1",
                "gradle 构建",
                "FAIL",
                "构建失败 —— 看证据末尾的报错；JDK 版本必须是 17（不要 21+）",
                tail,
            )
            return finish(rec, args, started, fatal="构建失败")

    # ── P2 安装 ───────────────────────────────────────────────
    apk = ROOT / APK_REL
    if args.skip_install:
        rec.record("P2", "安装 APK", "SKIP", "--skip-install")
    else:
        if not apk.exists():
            rec.record("P2", "安装 APK", "FAIL", f"找不到 {apk}（构建没产出？）")
            return finish(rec, args, started, fatal="找不到 APK")
        r = adb.raw("install", "-r", str(apk), timeout=300)
        if "Success" in r.stdout:
            rec.record("P2", "安装 APK", "PASS", "install Success")
        else:
            rec.record("P2", "安装 APK", "FAIL", "install 未报 Success", r.combined[:1200])
            return finish(rec, args, started, fatal="安装失败")

    # ── P3 无障碍 ─────────────────────────────────────────────
    cur = adb.shell("settings", "get", "secure", "enabled_accessibility_services").stdout.strip()
    if A11Y_COMPONENT in cur:
        rec.record("P3", "无障碍服务", "PASS", "已在启用列表中", cur)
    else:
        new_val = (cur + ":" + A11Y_COMPONENT) if cur and cur not in ("null", "") else A11Y_COMPONENT
        adb.shell("settings", "put", "secure", "enabled_accessibility_services", new_val)
        adb.shell("settings", "put", "secure", "accessibility_enabled", "1")
        again = adb.shell("settings", "get", "secure", "enabled_accessibility_services").stdout.strip()
        if A11Y_COMPONENT in again:
            rec.record("P3", "无障碍服务", "PASS", "已开启（覆盖安装后按既有约定重开）", again)
        else:
            rec.record("P3", "无障碍服务", "FAIL", "写入后读不回来", again)

    # ── P4 启动应用 + 手动开桥 ────────────────────────────────
    adb.shell("am", "start", "-n", ACTIVITY)
    pause(
        "请在手机上：设置 → dsh 集成 → 点击「开启能力桥」（首次会弹通知权限，请允许）。"
    )

    # ── P5 读取草稿（端口 + 令牌）─────────────────────────────
    draft = ""
    for attempt in range(6):
        r = adb.shell("run-as", PKG, "cat", DRAFT_REL)
        if "127.0.0.1:" in r.stdout:
            draft = r.stdout
            break
        if args.yes:
            time.sleep(3)
        else:
            pause("还读不到能力桥草稿 —— 确认已点开「开启能力桥」。")
    port_m = re.search(r"127\.0\.0\.1:(\d+)", draft)
    tok_m = re.search(r"Bearer\s+([A-Za-z0-9._~\-]+)", draft)
    if not port_m or not tok_m:
        rec.record("P5", "读取桥草稿", "FAIL", "读不到草稿或解析不出端口/令牌（桥没开？）", draft[:600])
        return finish(rec, args, started, fatal="桥草稿不可读")
    remote_port = int(port_m.group(1))
    token = tok_m.group(1)
    rec.record("P5", "读取桥草稿", "PASS", f"端口={remote_port}（令牌已隐去）")

    # ── P6 端口转发 ───────────────────────────────────────────
    local_port = 0
    for lp in ([args.local_port] if args.local_port else range(38999, 38990, -1)):
        r = adb.raw("forward", f"tcp:{lp}", f"tcp:{remote_port}")
        # 成功 = 退出码 0 且没有输出 —— adb forward 的失败文案不统一
        #（"cannot bind listener: …" 不含 "error" 这个词），不许做子串猜测。
        if r.returncode == 0 and not r.stderr.strip() and not r.stdout.strip():
            local_port = lp
            break
    if not local_port:
        rec.record("P6", "adb forward", "FAIL", "转发建立失败", r.combined[:400] if 'r' in locals() else "")
        return finish(rec, args, started, fatal="forward 失败")
    rec.record("P6", "adb forward", "PASS", f"127.0.0.1:{local_port} → 手机 :{remote_port}")

    try:
        # ── P7 前台基线 ───────────────────────────────────────
        r1 = rpc(local_port, token, "initialize",
                 {"protocolVersion": "2025-11-25", "capabilities": {},
                  "clientInfo": {"name": "verify-fgs", "version": "1.0"}}, 1)
        r2 = rpc(local_port, token, "tools/list", None, 2)
        fg_ok = r2["ok"] and "result" in (parse_rpc(r2["text"]) or {})
        rec.record(
            "P7", "前台基线（tools/list）",
            "PASS" if fg_ok else "FAIL",
            f"HTTP {r2['status']} / {r2['ms']}ms" + ("" if r2["ok"] else f" / {r2.get('error')}"),
            (r2["text"] or r2.get("error", ""))[:800] + ("\n--- init ---\n" + r1["text"][:400] if r1.get("text") else ""),
        )
        if not fg_ok:
            rec.record("P7", "前台基线", "INFO", "前台都连不上就先别测后台了（可能桥没开/端口错）")
            return finish(rec, args, started, fatal="前台基线失败")

        # ── P8 ★关键回归：退后台后仍应应答 ────────────────────
        rec.log(f"\n── P8 ★关键判据：按 Home 退后台 → 等 {args.wait} 秒 → 再请求 ──")
        rec.log("   （修复前：这里 12 秒超时、0 响应。现在应该是 200。）")
        adb.shell("input", "keyevent", "KEYCODE_HOME")
        time.sleep(max(1, args.wait))
        r3 = rpc(local_port, token, "tools/list", None, 3)
        bg_ok = r3["ok"] and "result" in (parse_rpc(r3["text"]) or {})
        rec.record(
            "P8", f"后台 {args.wait}s 后 tools/list（★核心判据）",
            "PASS" if bg_ok else "FAIL",
            f"HTTP {r3['status']} / {r3['ms']}ms" + ("" if r3["ok"] else f" / {r3.get('error')}"),
            (r3["text"] or r3.get("error", ""))[:800],
        )

        # 退后台状态下再读一次屏（真实场景：读的是"别的 App"）
        r4 = rpc(local_port, token, "tools/call", {"name": "screen_read", "arguments": {}}, 4)
        o4 = parse_rpc(r4["text"]) or {}
        bg_read_ok = r4["ok"] and "result" in o4 and not (o4.get("result") or {}).get("isError", False)
        rec.record(
            "P8", "后台状态下 screen_read（读别的 App）",
            "PASS" if bg_read_ok else "FAIL",
            f"HTTP {r4['status']} / {r4['ms']}ms，nodeCount={node_count(r4['text'])}",
            (r4["text"] or r4.get("error", ""))[:800],
        )

        # 保活服务的旁证（dumpsys 快照）
        svc = adb.shell("dumpsys", "activity", "services", PKG, timeout=30)
        lines = [ln.strip() for ln in svc.stdout.splitlines()
                 if ("AgentForegroundService" in ln) or ("isForeground" in ln) or ("foregroundServiceType" in ln)]
        rec.record(
            "P8", "保活服务状态（dumpsys 旁证）",
            "INFO" if lines else "WARN",
            f"匹配到 {len(lines)} 行",
            "\n".join(lines[:12])[:1200],
        )
        ntf = adb.shell("dumpsys", "notification", "--noredact", timeout=30)
        nlines = [ln.strip() for ln in ntf.stdout.splitlines()
                  if "dsh_keepalive" in ln or ("com.pocketagent.debug" in ln and "PocketAgent" in ln)]
        rec.record(
            "P8", "常驻通知（dsh_keepalive）",
            "INFO" if nlines else "WARN",
            f"匹配到 {len(nlines)} 行" + ("" if nlines else " —— 看不到通知？检查通知权限是否被拒"),
            "\n".join(nlines[:10])[:1200],
        )

        # ── P9 ⑦ 反向安全验证 ─────────────────────────────────
        pause(
            "⑦ 反向安全验证：请把手机停在**支付/收银类界面**（无法用支付页时，锁屏也可）。"
        )
        r5 = rpc(local_port, token, "tools/call", {"name": "screen_read", "arguments": {}}, 5)
        o5 = parse_rpc(r5["text"]) or {}
        res5 = o5.get("result") or {}
        refused = bool(res5.get("isError")) and r5["ok"]
        safety_text = json.dumps(res5, ensure_ascii=False)[:600]
        looks_safety = any(k in safety_text for k in ("安全", "支付", "敏感", "拒绝"))
        rec.record(
            "P9", "⑦ 敏感页应被拒绝",
            "PASS" if (refused and looks_safety) else ("WARN" if refused or r5["ok"] else "FAIL"),
            f"HTTP {r5['status']} / {r5['ms']}ms；isError={res5.get('isError')}",
            (r5["text"] or r5.get("error", ""))[:900],
        )

        # ── P10 遮蔽目击 ──────────────────────────────────────
        pause(
            "遮蔽目击：请打开一个**带输入框**的界面，在输入框里打下「test123」四个字，停在那个界面。"
        )
        r6 = rpc(local_port, token, "tools/call", {"name": "screen_read", "arguments": {}}, 6)
        text6 = r6["text"] or ""
        leaked = "test123" in text6
        masked = MASK_MARKER in text6
        rec.record(
            "P10", "遮蔽目击（输入框内容）",
            "PASS" if (masked and not leaked) else ("FAIL" if leaked else "WARN"),
            f"masked={masked} / leaked={leaked} / HTTP {r6['status']} / {r6['ms']}ms",
            text6[:900],
        )
    finally:
        adb.raw("forward", "--remove", f"tcp:{local_port}")

    return finish(rec, args, started)


# ═══════════════════════════════════════════════════════════════
#  报告
# ═══════════════════════════════════════════════════════════════

def finish(rec: Recorder, args, started: datetime, fatal: str = "") -> int:
    ended = datetime.now()
    verdict = "中止：" + fatal if fatal else rec.verdict()

    lines = []
    lines.append("PocketAgent · FGS 保活真机验证报告")
    lines.append(f"开始 {started:%Y-%m-%d %H:%M:%S} / 结束 {ended:%Y-%m-%d %H:%M:%S}")
    lines.append(f"结论：{redact(verdict)}")
    lines.append("")
    lines.append("== 逐项 ==")
    for r in rec.rows:
        lines.append(f"[{r['status']}] {r['phase']} · {r['name']} —— {r['detail']}")
        if r["evidence"]:
            for ev in r["evidence"].splitlines():
                lines.append("    | " + ev)
    lines.append("")
    lines.append("== 控制台日志 ==")
    lines.extend("  " + ln for ln in rec.console)

    reports_dir = ROOT / "tools" / "device" / "reports"
    reports_dir.mkdir(parents=True, exist_ok=True)
    stamp = f"{started:%Y%m%d-%H%M%S}"
    txt_path = reports_dir / f"fgs-verify-{stamp}.txt"
    json_path = reports_dir / f"fgs-verify-{stamp}.json"
    txt_path.write_text(redact("\n".join(lines)), encoding="utf-8")
    json_path.write_text(
        json.dumps(
            {"started": started.isoformat(), "ended": ended.isoformat(), "verdict": redact(verdict),
             "rows": rec.rows},
            ensure_ascii=False, indent=2,
        ),
        encoding="utf-8",
    )

    print()
    print("═" * 64)
    print(f"结论：{redact(verdict)}")
    print(f"报告：{txt_path}")
    print(f"     {json_path}")
    print("把上面这份报告（或它的内容）交回给 Claude / workbuddy 即可。")
    print("═" * 64)

    bad = fatal or any(r["status"] == "FAIL" for r in rec.rows)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
