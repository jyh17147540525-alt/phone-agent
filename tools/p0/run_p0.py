# -*- coding: utf-8 -*-
"""
P0 验证执行器 · 命令行入口

用法（先看这个）：

    # 看有哪些实验、当前连了什么设备
    python tools/p0/run_p0.py probe

    # 跑全部能跑的实验
    python tools/p0/run_p0.py all

    # 只跑某几项
    python tools/p0/run_p0.py run P0-2 P0-4

    # 打印 Termux 里要执行的命令（P0-1 需要手动上机）
    python tools/p0/run_p0.py node-command

═══════════════════════════════════════════════════════════════
  设计取向：默认**不动设备状态**
═══════════════════════════════════════════════════════════════

P0-3 要写 `overlay_display_devices`，P0-2 可以选做重装，P0-5 可以清电池统计。
这三件事都会改变用户手机的状态。默认全部关闭，必须显式加参数才执行。

理由不是"保守"，而是**这五件事都是要反复跑很多次的**（改一次配置跑一次）。
一个"跑起来就会动我手机"的工具，用户跑第二次之前会犹豫 ——
而犹豫的结果就是数据不全。

所有改动都会在 finally 里恢复，并写进报告的 errors 段。
"""

from __future__ import annotations

import argparse
import datetime as _dt
import sys
from pathlib import Path

# 允许 `python tools/p0/run_p0.py` 直接跑（不必先 pip install）
sys.path.insert(0, str(Path(__file__).resolve().parent))

from p0kit import Adb, AdbNotFound, Report, Verdict, find_adb  # noqa: E402
from p0kit.experiments import (  # noqa: E402
    ALL_EXPERIMENTS,
    EXPERIMENT_DEPS,
    NODE_REPORT_COMMAND,
    Context,
    read_device_info,
)


def _now() -> str:
    return _dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def derive_a11y_service(package: str) -> str:
    """按包名派生无障碍服务名。

    debug 变体的 `applicationId` 带 `.debug` 后缀（`com.pocketagent.debug`），
    但服务类本身的包路径**不带**这个后缀 —— 它取决于源码里的 `package` 声明。
    所以派生规则是：`应用 ID / 去掉 .debug 的包路径 + .assistant.AgentAccessibilityService`。

    这条规则存在的理由：默认值写死成 `com.pocketagent/...` 时，
    用 debug 变体跑会得到「工具说没开、系统说开了」的假失败 ——
    因为 `enabled_accessibility_services` 里存的是应用 ID 开头的完整组件名。
    """
    base = package[: -len(".debug")] if package.endswith(".debug") else package
    return f"{package}/{base}.assistant.AgentAccessibilityService"


def build_context(args: argparse.Namespace) -> Context:
    return Context(
        package=args.package,
        a11y_service=args.a11y_service or derive_a11y_service(args.package),
        node_report=Path(args.node_report) if args.node_report else None,
        reinstall_apk=Path(args.reinstall_apk) if args.reinstall_apk else None,
        allow_display_changes=args.allow_display_changes,
        vd_targets=args.vd_target or [],
        mem_samples=args.mem_samples,
        mem_interval=args.mem_interval,
        reset_batterystats=args.reset_batterystats,
    )


# ═══════════════════════════════════════════════════════════════
#  probe：连得上吗、是什么设备
# ═══════════════════════════════════════════════════════════════

def cmd_probe(adb: Adb) -> int:
    devices = adb.devices()

    if not devices:
        print("没有检测到任何设备。")
        print()
        print("  1. 用 USB 线连接手机，并把 USB 用途设为「传输文件」")
        print("  2. 开发者选项 → 打开「USB 调试」")
        print("  3. 手机上会弹「允许 USB 调试吗」→ 点允许")
        print()
        print("  连上后用 `adb devices` 确认状态是 `device` 而不是 `unauthorized`。")
        return 2

    print(f"检测到 {len(devices)} 台设备：")
    for serial, state in devices:
        note = {
            "device": "就绪",
            "unauthorized": "未授权 —— 手机上的调试授权弹窗还没点允许",
            "offline": "离线 —— 拔插数据线重试",
        }.get(state, state)
        print(f"  {serial}  [{state}] {note}")

    ready = [s for s, st in devices if st == "device"]
    if len(ready) > 1:
        print()
        print("⚠️ 有多台就绪设备。加 `--serial <序列号>` 指定要测哪一台。")

    if not ready:
        return 2

    info = read_device_info(adb)
    print()
    print("设备信息：")
    for key, value in info.items():
        print(f"  {key}: {value}")

    from p0kit.parse import device_matches_target, parse_getprop
    props = parse_getprop(adb.shell("getprop").stdout)
    if not device_matches_target(props):
        print()
        print("⚠️ 这不是首要适配机型（红米 K60）。")
        print("   结论依然有效，但适用范围要写清楚 —— 别的机型/ROM 可能不同。")

    print()
    print("下一步：`python tools/p0/run_p0.py all`")
    return 0


# ═══════════════════════════════════════════════════════════════
#  run / all
# ═══════════════════════════════════════════════════════════════

def _resolve_selection(selection: list[str]) -> list[str]:
    """把 `all` 展开成全部，并校验 id 合法。"""
    if not selection or "all" in selection:
        return list(ALL_EXPERIMENTS)
    unknown = [s for s in selection if s not in ALL_EXPERIMENTS]
    if unknown:
        raise SystemExit(
            f"未知的实验 id：{', '.join(unknown)}\n"
            f"可用的有：{', '.join(ALL_EXPERIMENTS)}（或 all）"
        )
    # 按固定顺序，不按用户输入顺序 —— 依赖关系要求 P0-1/P0-2 先跑
    return [k for k in ALL_EXPERIMENTS if k in selection]


def cmd_run(adb: Adb, args: argparse.Namespace) -> int:
    ctx = build_context(args)
    ids = _resolve_selection(args.experiments)

    report = Report(device=read_device_info(adb), started_at=_now())

    for exp_id in ids:
        deps = EXPERIMENT_DEPS.get(exp_id, [])
        unmet = [d for d in deps if d not in ids]
        if unmet:
            report.skipped.append(
                f"{exp_id}：未选其前置实验 {', '.join(unmet)}。"
                "结果可能因前置未满足而失真，请自行判断。"
            )

        print(f"[{exp_id}] 执行中…", file=sys.stderr)
        try:
            result = ALL_EXPERIMENTS[exp_id](adb, ctx)
        except Exception as exc:  # noqa: BLE001
            # ⚠️ 单个实验崩溃不能让整轮终止 —— 后面还有四项要跑，
            #    而真机时间很宝贵。崩溃信息原样记进报告。
            from p0kit.model import Check, ExperimentResult
            result = ExperimentResult(
                id=exp_id,
                title=ALL_EXPERIMENTS[exp_id].__name__,
                why="",
                checks=[Check(
                    id=f"{exp_id}.crash",
                    title="实验执行时崩溃",
                    verdict=Verdict.UNKNOWN,
                    consequence="工具自身出错，本轮没拿到任何证据。",
                    next_step=f"把下面的异常贴给开发者：{type(exc).__name__}: {exc}",
                )],
                errors=[f"{type(exc).__name__}: {exc}"],
            )
        report.experiments.append(result)
        print(f"[{exp_id}] → {result.verdict.symbol}", file=sys.stderr)

    report.finished_at = _now()
    return _emit(report, args)


def _emit(report: Report, args: argparse.Namespace) -> int:
    markdown = report.to_markdown()

    if args.out:
        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(markdown, encoding="utf-8")
        print(f"报告已写入 {out_path}")

        json_path = out_path.with_suffix(".json")
        json_path.write_text(report.to_json(), encoding="utf-8")
        print(f"机器可读版本 {json_path}")
    else:
        print(markdown)

    # 退出码反映结论，便于接进脚本：
    #   0 全部 PASS
    #   1 有 FAIL（拿到了反面证据，需要做设计决策）
    #   2 有 BLOCKED/UNKNOWN（还没拿到证据，需要重跑或解决障碍）
    verdicts = {e.verdict for e in report.experiments}
    if Verdict.FAIL in verdicts:
        return 1
    if Verdict.BLOCKED in verdicts or Verdict.UNKNOWN in verdicts:
        return 2
    return 0


# ═══════════════════════════════════════════════════════════════
#  node-command：P0-1 的上机指引
# ═══════════════════════════════════════════════════════════════

def cmd_node_command() -> int:
    print("P0-1 需要在手机上手动跑一次。步骤：")
    print()
    print("  1. 安装 Termux")
    print("     ⚠️ 必须从 F-Droid 装（https://f-droid.org/packages/com.termux/）。")
    print("        Play 商店那份已废弃，装上去会各种报错。")
    print()
    print("  2. 在 Termux 里装 Node：")
    print("       pkg update && pkg install nodejs-lts")
    print()
    print("     ⚠️ 是 `nodejs-lts` 不是 `nodejs`。前者当前是 24.18.0，")
    print("        后者可能落后，而且从 24.13.0 起 npm 已从 lts 包中拆出，")
    print("        需要 npm 的话再 `pkg install npm`。")
    print()
    print("  3. 执行下面这段（会写一个文件到 Termux 家目录并打印出来）：")
    print()
    print("  ┌─────────────────────────────────────────────────────────")
    for line in NODE_REPORT_COMMAND.splitlines():
        print(f"  │ {line}")
    print("  └─────────────────────────────────────────────────────────")
    print()
    print("  4. 把打印出来的全部内容复制，存成一个文本文件，例如 node-report.txt")
    print("     然后：")
    print()
    print("       python tools/p0/run_p0.py run P0-1 --node-report node-report.txt")
    print()
    print("  也可以把文件放到手机共享存储 /sdcard/p0-node.txt，工具会自动去取。")
    print()
    print("  ⚠️ 如果 dsh 还没装，第 3 步里 dsh 那段会报 command not found，")
    print("     这是预期的 —— Node 版本和 toReversed 的结果依然有效。")
    print("     先回答「Node 能不能跑」，再回答「dsh 能不能跑」。")
    return 0


# ═══════════════════════════════════════════════════════════════
#  参数
# ═══════════════════════════════════════════════════════════════

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="run_p0",
        description="PocketAgent P0 验证执行器",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "退出码：0=全部通过  1=有实验不通过  2=有实验未测到或被阻塞\n"
            "（2 不是错误 —— 它表示还需要人做点什么）"
        ),
    )
    parser.add_argument("--adb", help="adb 可执行文件路径（默认自动探测）")
    parser.add_argument("--serial", help="设备序列号（连了多台时必须指定）")
    parser.add_argument("--package", default="com.pocketagent",
                        help="应用包名（debug 变体是 com.pocketagent.debug）")
    parser.add_argument(
        "--a11y-service",
        default=None,
        help=(
            "无障碍服务名，必须与 AndroidManifest.xml 里声明的一致。"
            "不指定时按 --package 自动派生："
            "<package>/<package 去掉 .debug 后缀>.assistant.AgentAccessibilityService"
        ),
    )

    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("probe", help="检查设备连接并打印设备信息")
    sub.add_parser("node-command", help="打印 P0-1 需要在 Termux 里执行的命令")

    run = sub.add_parser("run", help="执行指定的 P0 实验")
    run.add_argument("experiments", nargs="*", default=["all"],
                     help="实验 id（P0-1 … P0-5），或 all")
    _add_common(run)

    allp = sub.add_parser("all", help="执行全部 P0 实验")
    _add_common(allp)

    return parser


def _add_common(p: argparse.ArgumentParser) -> None:
    p.add_argument("--out", help="报告输出路径（.md）。不指定则打到标准输出")
    p.add_argument("--node-report", help="P0-1：Termux 输出保存成的本地文件")
    p.add_argument("--reinstall-apk",
                   help="P0-2：覆盖安装该 APK 以验证受限设置是否被重置（会动设备）")
    p.add_argument("--allow-display-changes", action="store_true",
                   help="P0-3：允许临时修改 overlay_display_devices（会动设备，结束会恢复）")
    p.add_argument("--vd-target", action="append", metavar="PKG/.ACTIVITY",
                   help="P0-3：要启到副屏的 Activity，可重复指定")
    p.add_argument("--mem-samples", type=int, default=5, help="P0-4：内存采样次数")
    p.add_argument("--mem-interval", type=float, default=2.0, help="P0-4：采样间隔秒数")
    p.add_argument("--reset-batterystats", action="store_true",
                   help="P0-5：先清空电池统计（会动设备）")


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    if args.command == "node-command":
        return cmd_node_command()

    try:
        adb_path = find_adb(args.adb)
    except AdbNotFound as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 3

    adb = Adb(adb_path, serial=args.serial)

    if args.command == "probe":
        return cmd_probe(adb)
    if args.command == "all":
        args.experiments = ["all"]
        return cmd_run(adb, args)
    if args.command == "run":
        return cmd_run(adb, args)

    return 3


if __name__ == "__main__":
    raise SystemExit(main())
