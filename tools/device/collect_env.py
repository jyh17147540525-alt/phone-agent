#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
设备环境采集 —— **一次性把手机的关键环境抓下来存进仓库**（在 Windows 上运行）。

═══════════════════════════════════════════════════════════════
 为什么需要它
═══════════════════════════════════════════════════════════════

真机是**稀缺资源**（要插线 / 要供电 / 要人在旁边）。而开发过程中反复要问的
往往是同一批问题："屏幕多宽？""无障碍能力有哪些？""这个包什么版本？
HyperOS 有没有拦？"——**每次都要重新接手机去查**是最贵的做法。

⇒ 跑一次，把答案落进 `tools/device/reports/env-<时间戳>.txt/.json`，
  之后**脱离手机也能开发**：查档案即可。

═══════════════════════════════════════════════════════════════
 怎么跑
═══════════════════════════════════════════════════════════════

    tools\\device\\collect-env.cmd              ← 双击（推荐）
    python tools/device/collect_env.py          # 同样效果

    # 无线调试：先让手机开「无线调试」并用 adb connect，再指定 serial
    python tools/device/collect_env.py --serial 192.168.137.165:5555

    可选：
      --adb <路径>        指定 adb
      --serial <序列号>   多设备时指定（也能直接传 `IP:端口`）
      --json-only         只写 JSON
      --include-serial    不遮蔽序列号（默认遮蔽，只留后 4 位）

═══════════════════════════════════════════════════════════════
 边界（老实说）
═══════════════════════════════════════════════════════════════

- **只读**：只跑 `getprop` / `settings get` / `dumpsys` / `pm` / `wm` 这类查询，
  不修改手机上的任何东西。
- **抓不到的东西会如实标记 `（查询失败）`，不猜**。判据同项目纪律：
  一个"看起来有值但其实是默认值"的字段比空着更危险。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "p0"))

from p0kit.adb import Adb, AdbNotFound, find_adb  # noqa: E402

PKG = "com.pocketagent.debug"
A11Y_COMPONENT = f"{PKG}/com.pocketagent.assistant.AgentAccessibilityService"

try:
    sys.stdout.reconfigure(errors="replace")  # type: ignore[attr-defined]
    sys.stderr.reconfigure(errors="replace")  # type: ignore[attr-defined]
except Exception:
    pass


# ═══════════════════════════════════════════════════════════════
#  要采集什么
# ═══════════════════════════════════════════════════════════════
#
# 分组是**按"开发时什么时候会想问它"**分的，不是按命令类型分的 ——
# 这样查档案的人能直接翻到对应那一段。

def collect(adb: Adb, serial: str, mask_serial: bool) -> dict:
    groups: list[dict] = []

    def prop(key: str) -> str:
        out = adb.getprop(key).strip()
        return out if out else "（查询失败）"

    def shell(*args: str, timeout: float = 20.0) -> str:
        r = adb.shell(*args, timeout=timeout)
        text = r.stdout.strip()
        return text if text else "（查询失败）"

    # ── ① 设备标识 ──────────────────────────────────────────────
    groups.append({
        "group": "① 设备标识",
        "note": "写死进文档的那几个字段 —— 型号/代号/构建指纹决定了很多兼容性判断",
        "items": [
            ("型号（ro.product.model）", prop("ro.product.model")),
            ("市场名", prop("ro.product.marketname")),
            ("设备代号", prop("ro.product.device")),
            ("品牌", prop("ro.product.brand")),
            ("构建指纹", prop("ro.build.fingerprint")),
            ("构建号", prop("ro.build.display.id")),
            ("序列号", _mask(serial) if mask_serial else serial),
        ],
    })

    # ── ② 系统与版本 ────────────────────────────────────────────
    groups.append({
        "group": "② 系统与版本",
        "note": "Android 版本决定 API 行为（前台服务类型、权限模型）；HyperOS 版本决定厂商策略",
        "items": [
            ("Android 版本", prop("ro.build.version.release")),
            ("API 级别", prop("ro.build.version.sdk")),
            ("安全补丁", prop("ro.build.version.security_patch")),
            ("HyperOS / MIUI 版本", prop("ro.miui.ui.version.name")),
            ("HyperOS 完整版本", prop("ro.build.version.incremental")),
            ("SoC", prop("ro.soc.model")),
            ("ABI", prop("ro.product.cpu.abi")),
            ("CPU 核数", shell("cat", "/proc/cpuinfo").count("processor")
             if shell("cat", "/proc/cpuinfo") != "（查询失败）" else "（查询失败）"),
            ("内核", prop("ro.kernel.version")),
        ],
    })

    # ── ③ 屏幕 ──────────────────────────────────────────────────
    groups.append({
        "group": "③ 屏幕",
        "note": "坐标点击、悬浮球贴边、截图尺寸都要它；密度影响 UI 尺寸换算",
        "items": [
            ("分辨率（wm size）", shell("wm", "size")),
            ("密度（wm density）", shell("wm", "density")),
            # ⚠️ 这是**当前** fps，不是面板峰值刷新率 —— 名字别骗人。
            ("当前帧率（dumpsys display）", _grep_first(
                shell("dumpsys", "display", timeout=30),
                r"fps=\s*([\d.]+)",
            )),
        ],
    })

    # ── ④ 资源 ──────────────────────────────────────────────────
    groups.append({
        "group": "④ 资源",
        "note": "内存决定能不能长期驻留；存储决定插件包与容器能不能放得下",
        "items": [
            ("内存（MemTotal）", _grep_first(shell("cat", "/proc/meminfo"), r"MemTotal:\s*(.+)")),
            ("内存（MemAvailable）", _grep_first(shell("cat", "/proc/meminfo"), r"MemAvailable:\s*(.+)")),
            ("data 分区（df -h /data）", _line_with(shell("df", "-h", "/data"), "/data")),
            ("电池电量", shell("dumpsys", "battery").split("level:")[1].split()[0]
             if "level:" in shell("dumpsys", "battery") else "（查询失败）"),
            ("Charge counter（µAh）", _grep_first(shell("dumpsys", "battery"), r"Charge counter:\s*(\d+)")),
        ],
    })

    # ── ⑤ 开发与调试状态 ────────────────────────────────────────
    groups.append({
        "group": "⑤ 开发与调试状态",
        "note": "无线调试要 `service.adb.tcp.port`；`ro.debuggable=1` 才能 run-as",
        "items": [
            ("无线调试端口（service.adb.tcp.port）", prop("service.adb.tcp.port")),
            ("调试属性（ro.debuggable）", prop("ro.debuggable")),
            ("ro.secure", prop("ro.secure")),
            ("已连接设备（adb devices -l）", adb.raw("devices", "-l").stdout.strip().replace(serial, _mask(serial) if mask_serial else serial)),
            ("受限设置（ACCESS_RESTRICTED_SETTINGS）", _grep_first(
                # ⚠️ 这条是 appops，不是 dumpsys role（第一次写错，查不到）。
                #    命令出处见 tools/p0/p0kit/experiments.py 的 P0-2.b。
                shell("cmd", "appops", "get", PKG, "ACCESS_RESTRICTED_SETTINGS", timeout=30),
                r"ACCESS_RESTRICTED_SETTINGS:\s*(\S+)",
            )),
        ],
    })

    # ── ⑥ 我们的应用与依赖工具 ──────────────────────────────────
    groups.append({
        "group": "⑥ 应用与依赖工具",
        "note": "版本号决定「手机上装的是哪一版」—— 判据是 versionCode，不是 lastUpdateTime",
        "items": [
            (f"{PKG} 版本", _app_version(adb, PKG)),
            (f"{PKG} 安装路径", shell("pm", "path", PKG).replace("\n", " ")),
            ("Termux 版本", _app_version(adb, "com.termux")),
            ("Shizuku 版本", _app_version(adb, "moe.shizuku.privileged.api")),
        ],
    })

    # ── ⑦ 无障碍 ────────────────────────────────────────────────
    #
    # ⚠️ 能力位只在**服务已启用**时才有意义。服务关着时 dumpsys 里根本没有那一行 ——
    #    直接报「查询失败」会让人以为"查不到"，而真相是"还没有东西可查"。
    #    这两种情况对下一步动作的指向完全不同，必须分开说。
    a11y_enabled = shell("settings", "get", "secure", "accessibility_enabled").strip()
    a11y_services = shell("settings", "get", "secure", "enabled_accessibility_services")
    service_on = A11Y_COMPONENT in a11y_services
    a11y_dump = shell("dumpsys", "accessibility", timeout=30)
    groups.append({
        "group": "⑦ 无障碍",
        "note": "能力位（capabilities）决定能不能截图；组件名两半分属不同包（applicationId vs 命名空间）",
        "items": [
            ("无障碍总开关", a11y_enabled),
            ("已启用服务", a11y_services),
            ("我们期望的组件名", A11Y_COMPONENT),
            ("服务是否在启用列表里", "是" if service_on else "否"),
            ("能力位（capabilities）",
             _capabilities(a11y_dump) if service_on
             else "（服务未启用，无从读取 —— 覆盖安装会重置无障碍，需重新开启）"),
            ("有效显示器数量", _grep_first(a11y_dump, r"(\d+)\s+valid display")),
        ],
    })

    # ── ⑧ 后台与省电（HyperOS 的重点）──────────────────────────
    groups.append({
        "group": "⑧ 后台与省电",
        "note": "FGS 保活挡得住「冷冻」，挡不住厂商强杀 —— 这几项决定后者会不会发生",
        "items": [
            ("电池优化白名单（我们的包）", _battery_whitelist(adb, PKG)),
            ("deviceidle 白名单", shell("dumpsys", "deviceidle", "whitelist", timeout=30)),
            ("待机策略（standby bucket）", _grep_first(
                shell("am", "get-standby-bucket", PKG, timeout=30),
                r"(\d+)",
            )),
            ("HyperOS 自启动",
             "（无法通过 adb 查询 —— 需在「设置 → 应用 → 权限 → 自启动」人工确认）"),
            ("HyperOS 省电策略",
             "（无法通过 adb 查询 —— 需在「设置 → 应用 → 省电策略」人工确认，建议「无限制」）"),
        ],
    })

    # ── ⑨ 容器与 dsh ────────────────────────────────────────────
    #
    # ⚠️ 路径**不写死一个候选**：proot-distro 的安装根目录在不同版本/不同装法下
    #    会变。先试标准路径，不行就把 Termux 的 home 列出来让人一眼看出实际布局 ——
    #    比硬报「查询失败」有用得多。
    proot_ls = shell("run-as", "com.termux", "ls",
                     "/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/")
    if proot_ls == "（查询失败）":
        proot_ls = "标准路径为空；Termux home 内容：" + shell(
            "run-as", "com.termux", "ls", "/data/data/com.termux/files/home/").replace("\n", " ")

    groups.append({
        "group": "⑨ 容器与 dsh（脑）",
        "note": "dsh 跑在 Termux 的 proot 里；跨 UID 写不进它的私有目录是**已知约束**",
        "items": [
            ("Termux 是否可 run-as", "是" if _run_as_works(adb) else "否"),
            ("proot 发行版目录", proot_ls.replace("\n", " ")),
            ("Termux home 内容", shell(
                "run-as", "com.termux", "ls", "-a",
                "/data/data/com.termux/files/home/").replace("\n", " ")),
            ("dsh profile（headless）", shell(
                "run-as", "com.termux", "ls",
                "/data/data/com.termux/files/home/.dsh/profiles/headless/").replace("\n", " ")),
            ("cordis.patch.yml 是否为空", _patch_state(adb)),
        ],
    })

    return {
        "collectedAt": datetime.now().isoformat(timespec="seconds"),
        "serial": _mask(serial) if mask_serial else serial,
        "groups": groups,
    }


# ═══════════════════════════════════════════════════════════════
#  小工具
# ═══════════════════════════════════════════════════════════════

def _mask(serial: str) -> str:
    """序列号只留后 4 位 —— 与 P0 报告的既有做法一致。"""
    s = serial or ""
    return "****" + s[-4:] if len(s) > 4 else "(短)"


def _grep_first(text: str, pattern: str) -> str:
    import re
    m = re.search(pattern, text or "")
    if not m:
        return "（查询失败）"
    return " ".join(g for g in m.groups() if g) if m.groups() else m.group(0)


def _capabilities(dump: str) -> str:
    """
    能力位。

    ⚠️ 别把"服务已启用但 dump 里找不到"报成「查询失败」—— 那会让人以为
       是查询姿势不对，而真相是**得人工看原文**。两种情况指向不同动作。
    """
    import re
    m = re.search(r"capabilities[=:]\s*([0-9a-fA-Fx]+)", dump or "")
    if not m:
        return "（服务已启用，但 dump 里找不到能力位 —— 需人工看 dumpsys accessibility 原文）"
    raw = m.group(1)
    try:
        value = int(raw, 16) if raw.lower().startswith("0x") else int(raw)
    except ValueError:
        return raw
    # 128 = FLAG_REQUEST_TAKE_SCREENSHOT —— 没有它就读不了屏幕（只有树）
    screenshot = "含截图位(128)" if value & 128 else "⚠️ 不含截图位(128)"
    return f"{value}（{screenshot}）"


def _line_with(text: str, needle: str) -> str:
    """返回第一条含 [needle] 的行 —— 用于 `df` 这类"整行才是答案"的输出。

    ⚠️ 不硬解字段：`df` 的列格式在不同 ROM 上会多一列少一列（项目里已经
       踩过一次：`df_with_extra_column.txt` 那个 fixture 就是为此留的），
       原样给出整行比"按列切出来一个错的值"诚实。
    """
    for line in (text or "").splitlines():
        if needle in line:
            return " ".join(line.split())
    return "（查询失败）"


def _app_version(adb: Adb, package: str) -> str:
    out = adb.shell("dumpsys", "package", package, timeout=30).stdout
    if not out or "Unable to find package" in out:
        return "（未安装）"
    import re
    version_name = re.search(r"versionName=(\S+)", out)
    version_code = re.search(r"versionCode=(\d+)", out)
    if not version_name and not version_code:
        return "（未安装）"
    name = version_name.group(1) if version_name else "?"
    code = version_code.group(1) if version_code else "?"
    return f"versionName={name} versionCode={code}"


def _battery_whitelist(adb: Adb, package: str) -> str:
    out = adb.shell("dumpsys", "deviceidle", "whitelist", timeout=30).stdout
    if package in out:
        return "在白名单里"
    if "whitelist" in out.lower() or out.strip():
        return "不在白名单里"
    return "（查询失败）"


def _run_as_works(adb: Adb) -> bool:
    r = adb.shell("run-as", "com.termux", "id", timeout=20)
    return r.returncode == 0 and "uid=" in r.stdout


def _patch_state(adb: Adb) -> str:
    path = "/data/data/com.termux/files/home/.dsh/profiles/headless/cordis.patch.yml"
    r = adb.shell("run-as", "com.termux", "cat", path, timeout=20)
    if r.returncode != 0 or not r.stdout.strip():
        return "（读不到 / 文件不存在）"
    body = r.stdout.strip()
    if body in ("[]", ""):
        return "空（[]）—— 说明配置从未投递过"
    return f"有 {len(body.splitlines())} 行内容（含 MCP 桥配置的可能性大）"


# ═══════════════════════════════════════════════════════════════
#  输出
# ═══════════════════════════════════════════════════════════════

def write_reports(data: dict, root: Path, json_only: bool) -> tuple[Path, Path]:
    reports = root / "tools" / "device" / "reports"
    reports.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    json_path = reports / f"env-{stamp}.json"
    txt_path = reports / f"env-{stamp}.txt"

    json_path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")

    if not json_only:
        lines = [
            "PocketAgent · 设备环境档案",
            f"采集时间：{data['collectedAt']}    序列号：{data['serial']}",
            "",
        ]
        for g in data["groups"]:
            lines.append(f"== {g['group']} ==")
            if g.get("note"):
                lines.append(f"   （{g['note']}）")
            for label, value in g["items"]:
                value = str(value).replace("\n", " / ")
                lines.append(f"   {label}: {value}")
            lines.append("")
        lines.append("提示：把这份文件的内容交给 Claude，即可更新 docs/设备档案-v1.0.md。")
        txt_path.write_text("\n".join(lines), encoding="utf-8")
    return txt_path, json_path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--adb", default=None)
    ap.add_argument("--serial", default=None)
    ap.add_argument("--json-only", action="store_true")
    ap.add_argument("--include-serial", action="store_true")
    args = ap.parse_args()

    try:
        adb_path = find_adb(args.adb)
    except AdbNotFound as exc:
        print(f"[ERROR] {exc}")
        return 1

    adb = Adb(adb_path, serial=args.serial)
    devices = adb.devices()
    ready = [(s, st) for (s, st) in devices if st == "device"]
    offline = [s for (s, st) in devices if st == "offline"]

    if args.serial:
        serial = args.serial
    elif len(ready) == 1:
        serial = ready[0][0]
    elif not ready:
        print("[ERROR] 没有处于 device 状态的设备。")
        print("        无线调试：先在手机开启「无线调试」，再 `adb connect <IP>:5555`。")
        if offline:
            print(f"        ⚠️ 但有 {len(offline)} 台处于 offline：{offline}")
            print("        offline 通常是连接残留：`adb disconnect <地址>` 后重新 connect。")
        print(f"        当前 devices 输出：{devices!r}")
        return 1
    else:
        print(f"[ERROR] 有 {len(ready)} 台设备同时处于 device 状态：{[s for s, _ in ready]}")
        print("        必须显式指定，否则 adb 会对每条命令报「more than one device」。")
        print("        用法：--serial <序列号或 IP:端口>")
        return 1

    # ★★ 带着 serial 重建 adb 对象 —— 这一步不能省。
    #
    # 踩过的坑（2026-10-11 第一次跑就中招）：算出了该用哪台设备，
    # 却没回填给 adb 对象，于是每条命令仍不带 `-s`。
    # 而 adb 判定"多于一台"时**把 offline 的也算进去** ——
    # 症状是**所有字段整齐地变成「查询失败」**，看起来像"手机没连上"，
    # 实际是"连上了、但命令全被 adb 拒绝"。
    adb = Adb(adb_path, serial=serial)

    if offline:
        print(f"[i] 另有 {len(offline)} 台 offline 设备（{offline}），已用 -s 指定，不影响采集。")

    print(f"正在采集设备环境：{serial} …")
    t0 = time.time()
    data = collect(adb, serial, mask_serial=not args.include_serial)
    txt_path, json_path = write_reports(data, ROOT, args.json_only)

    print(f"完成，用时 {time.time() - t0:.1f}s")
    if txt_path.exists():
        print(f"报告：{txt_path}")
    print(f"     {json_path}")
    print("把报告内容交回给 Claude，即可更新 docs/设备档案-v1.0.md。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
