# -*- coding: utf-8 -*-
"""
P0 验证执行器 · 五项实验的检查逻辑

每个 `run_p0_N` 函数接收一个已连接的 `Adb` 和一个 `Context`，
返回一个 `ExperimentResult`。它们**不做 I/O 之外的事** ——
所有解析都调用 `parse.py`，所有判定都写在下面，便于单独审阅。

═══════════════════════════════════════════════════════════════
  贯穿全篇的三条纪律
═══════════════════════════════════════════════════════════════

1. **改动设备设置前先存原值，且在 finally 里恢复。**
   P0-3 会往 `overlay_display_devices` 里写值。如果中途抛异常而没恢复，
   用户手机上会凭空多出一个副屏，而且**他不知道是我们干的**。
   这类"工具留下的副作用"是最伤害信任的一种 bug。

2. **命令失败要区分 BLOCKED 与 UNKNOWN。**
   包没装 = BLOCKED（前置不满足，要人去装）；
   输出看不懂 = UNKNOWN（要人重跑或我们改解析）。
   见 `model.py` 的说明。

3. **不替用户下结论。**
   报告写"这个值是多少、对应的判定标准是什么、因此判定是什么"，
   而不是直接写"没问题"。判定标准来自设计文档，抄进报告里，
   这样标准变了的时候报告也说得清是按哪一版判的。
"""

from __future__ import annotations

import re
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from .adb import Adb, ShellResult
from .model import Check, Evidence, ExperimentResult, Verdict, truncate
from . import parse


# ═══════════════════════════════════════════════════════════════
#  配置
# ═══════════════════════════════════════════════════════════════

#: 无障碍服务名。**必须与 AndroidManifest.xml 里声明的完全一致** ——
#: 否则本工具会一直报告"服务未启用"，而用户明明在设置里开了它。
#: 名字不一致时的症状是"工具说没开、系统说开了"，非常难查。
DEFAULT_A11Y_SERVICE = "com.pocketagent/com.pocketagent.assistant.AgentAccessibilityService"

#: 内存预算。来自《移动端约束分析》§2.2：
#: App 80–120MB + Node 30–40MB + dsh 40–70MB = 常驻 150–230MB。
#: 豆包的 aikernel 是 160MB —— 我们超过它就是明确的劣势。
MEMORY_BUDGET_MIN_MB = 150
MEMORY_BUDGET_MAX_MB = 230

#: Node 与 dsh 落盘后的大致占用。用来判断存储是否够。
#: Node 单 ABI 30–40MB + dsh 及其 node_modules 约 60–80MB，
#: 再留一倍余量给运行期产物。
STORAGE_NEEDED_MB = 300


@dataclass
class Context:
    """一次运行的全部可配置项。"""

    package: str = "com.pocketagent"
    a11y_service: str = DEFAULT_A11Y_SERVICE

    #: 用户从 Termux 复制出来的输出文件（P0-1）
    node_report: Optional[Path] = None

    #: 要重装以验证"覆盖安装是否重置受限设置"的 APK（P0-2，显式开启）
    reinstall_apk: Optional[Path] = None

    #: 是否允许改动设备显示设置（P0-3，显式开启）
    allow_display_changes: bool = False

    #: 虚拟屏测试的目标 Activity，形如 `pkg/.Activity`
    vd_targets: list[str] = field(default_factory=lambda: [
        "com.android.settings/.Settings",
    ])

    #: P0-4 采样次数与间隔
    mem_samples: int = 5
    mem_interval: float = 2.0

    #: P0-5 是否重置电池统计（会清空历史，显式开启）
    reset_batterystats: bool = False


# ═══════════════════════════════════════════════════════════════
#  小工具
# ═══════════════════════════════════════════════════════════════

def _fail(result: ShellResult) -> Optional[str]:
    """
    命令失败时返回一句带处置建议的描述；成功返回 None。

    把 `error_kind` 和 `hint` 拼在一起 —— 只报"命令失败"没有用，
    用户需要知道"失败成什么样、现在该干什么"。
    """
    if result.ok:
        return None
    kind = result.error_kind()
    return f"`{result.command}` 失败（{kind}）。{result.hint()}"


def _ev(label: str, value, raw: str) -> Evidence:
    return Evidence(label=label, value=value, raw=truncate(raw))


def _blocked(id_: str, title: str, why: str, next_step: str) -> Check:
    return Check(id=id_, title=title, verdict=Verdict.BLOCKED,
                 consequence=why, next_step=next_step)


# ═══════════════════════════════════════════════════════════════
#  设备身份（所有实验共用）
# ═══════════════════════════════════════════════════════════════

def read_device_info(adb: Adb) -> dict[str, str]:
    """读设备身份信息。这些值会写进报告的头部，让结论带上适用条件。"""
    keys = {
        "型号": "ro.product.model",
        "市场名": "ro.product.marketname",
        "Android 版本": "ro.build.version.release",
        "API 级别": "ro.build.version.sdk",
        "ABI": "ro.product.cpu.abi",
        "SoC": "ro.soc.model",
        "MIUI/HyperOS": "ro.miui.ui.version.name",
    }
    info: dict[str, str] = {}
    for label, key in keys.items():
        value = adb.getprop(key).strip()
        if value:
            info[label] = value
    return info


# ═══════════════════════════════════════════════════════════════
#  P0-1 · Node 20+ 在 K60 上跑起来
# ═══════════════════════════════════════════════════════════════

#: 给用户贴进 Termux 的命令。写在代码里而不是 README 里，
#: 是为了让它和解析逻辑**永远在一起** —— 改了标记就必然要改解析，
#: 而改了 README 没人会发现解析坏了。
#:
#: 标记用 `###P0KIT <字段>`，解析时按它切段。
NODE_REPORT_COMMAND = r"""
{ echo "###P0KIT node_version"; node -v; \
  echo "###P0KIT to_reversed"; node -e 'console.log(JSON.stringify([1,2,3].toReversed()))'; \
  echo "###P0KIT abi"; node -e 'console.log(process.arch)'; \
  echo "###P0KIT dsh"; dsh --profile headless "1+1" 2>&1 | tail -20; \
} > ~/p0-node.txt 2>&1; cat ~/p0-node.txt
""".strip()


def parse_node_report(text: str) -> dict[str, str]:
    """
    解析用户从 Termux 贴回来的分段报告。

    格式：
        ###P0KIT node_version
        v24.18.0
        ###P0KIT to_reversed
        [3,2,1]

    ⚠️ 段内可能有**多行**（dsh 的输出就是），所以不能按行配对，
       要按标记切块。
    """
    sections: dict[str, str] = {}
    current: Optional[str] = None
    buffer: list[str] = []

    for line in (text or "").splitlines():
        marker = re.match(r"^###P0KIT\s+(\S+)\s*$", line.strip())
        if marker:
            if current is not None:
                sections[current] = "\n".join(buffer).strip()
            current = marker.group(1)
            buffer = []
        elif current is not None:
            buffer.append(line)

    if current is not None:
        sections[current] = "\n".join(buffer).strip()

    return sections


def run_p0_1(adb: Adb, ctx: Context) -> ExperimentResult:
    result = ExperimentResult(
        id="P0-1",
        title="Node 20+ 在 K60 上跑起来",
        why="路线 C（dsh 移植）的唯一前置关卡。这一项不过，整套 dsh 方案作废，"
            "必须退回路线 B。",
    )

    # ── 检查 1：设备是否为目标机型 ────────────────────────
    props_raw = adb.shell("getprop").stdout
    props = parse.parse_getprop(props_raw)
    is_target = parse.device_matches_target(props)
    abi = props.get("ro.product.cpu.abi", "")

    device_check = Check(
        id="P0-1.a",
        title="设备与 ABI 符合预期",
        verdict=Verdict.PASS if (is_target and abi == parse.EXPECTED_ABI) else Verdict.UNKNOWN,
        evidence=[
            _ev("型号", props.get("ro.product.model"), ""),
            _ev("ABI", abi, ""),
            _ev("是否首要适配机型", is_target, ""),
        ],
        caveat="" if is_target else (
            "这不是红米 K60。结论只能说明「这个机型上 Node 能/不能跑」，"
            "不能直接当作 K60 的结论。"
        ),
    )
    if abi and abi != parse.EXPECTED_ABI:
        device_check.verdict = Verdict.FAIL
        device_check.consequence = (
            f"设备 ABI 是 {abi}，而目标是 {parse.EXPECTED_ABI}。"
            "我们只打算打包 arm64-v8a 的 Node，其他 ABI 上跑不了。"
        )
        device_check.next_step = "换用 arm64 设备，或确认是否要额外打包其他 ABI（会显著增大 APK）。"
    result.checks.append(device_check)

    # ── 检查 2：存储空间 ─────────────────────────────────
    df_result = adb.shell("df", "-k", "/data")
    free_kb = parse.parse_free_storage_kb(df_result.stdout)
    needed_kb = STORAGE_NEEDED_MB * 1024

    if free_kb is None:
        storage_check = Check(
            id="P0-1.b", title="存储空间充足",
            verdict=Verdict.UNKNOWN,
            evidence=[_ev("df -k /data 可用空间", None, df_result.combined)],
            next_step="df 的输出格式没认出来。把上面的原始输出贴给开发者，改解析。",
        )
    else:
        free_mb = free_kb // 1024
        ok = free_kb >= needed_kb
        storage_check = Check(
            id="P0-1.b", title="存储空间充足",
            verdict=Verdict.PASS if ok else Verdict.FAIL,
            evidence=[
                _ev("可用空间 (MB)", free_mb, ""),
                _ev("需求 (MB)", STORAGE_NEEDED_MB, ""),
            ],
            consequence="" if ok else (
                "空间不足会让 Node 解包或 dsh 装依赖失败，"
                "而失败信息通常是「磁盘空间不足」这种一眼看不出根因的形态。"
            ),
            next_step="" if ok else f"至少腾出 {STORAGE_NEEDED_MB - free_mb} MB。",
        )
    result.checks.append(storage_check)

    # ── 检查 3~5：Node 实测 ──────────────────────────────
    report_text = _read_node_report(adb, ctx)

    if report_text is None:
        result.checks.append(_blocked(
            id_="P0-1.c", title="Node 版本 ≥ 20",
            why="没有拿到 Termux 里的实测输出。**这一项是路线 C 的成败关键，"
                "不能靠推测填上。**",
            next_step=(
                "在手机上装 Termux（必须从 F-Droid 装，Play 版已废弃），然后：\n"
                "  1. `pkg update && pkg install nodejs`\n"
                "  2. 执行下面这段并把输出存成文件：\n"
                f"```\n{NODE_REPORT_COMMAND}\n```\n"
                "  3. 用 `--node-report <文件>` 把输出交给本工具"
            ),
        ))
        return result

    sections = parse_node_report(report_text)

    # 3. Node 版本
    version = parse.parse_node_version(sections.get("node_version", ""))
    version_ok = parse.node_version_ok(version)
    result.checks.append(Check(
        id="P0-1.c",
        title=f"Node 版本 ≥ {parse.MIN_NODE_MAJOR}",
        verdict=Verdict.PASS if version_ok else (Verdict.FAIL if version else Verdict.UNKNOWN),
        evidence=[
            _ev("node -v 输出", version, sections.get("node_version", "")),
            _ev("下限", parse.MIN_NODE_MAJOR, ""),
        ],
        consequence="" if version_ok else (
            "dsh 的核心包用了 `toReversed()`，该方法 Node 20 才稳定。"
            "低于 20 直接不可用 —— 而 nodejs-mobile 封顶 18.20.4 且已 EOL，"
            "所以没有「降级用 nodejs-mobile」这条退路。"
        ),
        next_step="" if version_ok else (
            "Termux 的 `nodejs-lts` 当前是 24.18.0。若这里读到低于 20，"
            "说明装的是旧包（`nodejs` 而非 `nodejs-lts`），先升级。"
        ),
    ))

    # 4. toReversed 实际可用
    rev_raw = sections.get("to_reversed", "")
    rev_ok = "[3,2,1]" in rev_raw.replace(" ", "")
    result.checks.append(Check(
        id="P0-1.d",
        title="`toReversed()` 实际可用",
        verdict=Verdict.PASS if rev_ok else Verdict.UNKNOWN,
        evidence=[_ev("输出应为 [3,2,1]", rev_raw.strip(), rev_raw)],
        consequence="",
        next_step="" if rev_ok else (
            "版本号够但方法不存在，说明版本号是伪造的或是裁剪过的构建 —— "
            "这种情况极少见但必须排除，因为它是「看起来通过实际不通过」的典型。"
        ),
    ))

    # 5. dsh 本身
    dsh_raw = sections.get("dsh", "")
    dsh_ok = _dsh_looks_ok(dsh_raw)
    result.checks.append(Check(
        id="P0-1.e",
        title="dsh 能实际执行一次任务",
        verdict=Verdict.PASS if dsh_ok else Verdict.FAIL if dsh_raw else Verdict.UNKNOWN,
        evidence=[_ev("dsh --profile headless \"1+1\"", dsh_raw.strip()[:400], dsh_raw)],
        consequence="" if dsh_ok else (
            "Node 能跑但 dsh 跑不起来，路线 C 依然不成立 —— "
            "Node 只是载体，dsh 才是要跑的东西。"
        ),
        next_step="" if dsh_ok else (
            "看上面原始输出里的报错。常见原因：dsh 依赖的原生模块没有 Android 预编译版本"
            "（需要交叉编译），或依赖了 bash/pty 等 Termux 之外的东西。"
        ),
    ))

    return result


def _read_node_report(adb: Adb, ctx: Context) -> Optional[str]:
    """
    取 Node 实测输出。两个来源，按可靠性排序：

    1. `--node-report` 指定的本地文件（用户自己粘贴的）
    2. 设备上的 `/sdcard/p0-node.txt`

    本地文件优先，因为它一定是最新的。设备上那份可能是上一次的 ——
    而这会让报告显示"通过"而实际上用户刚改了配置没重跑。
    陈旧数据伪装成新鲜数据是这里最大的风险，所以顺序不能反。
    """
    if ctx.node_report:
        path = Path(ctx.node_report)
        if path.exists():
            return path.read_text(encoding="utf-8", errors="replace")

    device_file = adb.shell("cat", "/sdcard/p0-node.txt")
    if device_file.ok and device_file.stdout.strip():
        return device_file.stdout

    return None


def _dsh_looks_ok(output: str) -> bool:
    """
    dsh 是否真的跑出了结果。

    ⚠️ 这里**不做精确匹配**，因为 dsh 的输出格式会变，而且 1+1 的结果
       可能是 "2"、可能是 "答案是 2"、也可能是 JSON。写死匹配必然误判。

    采用的判据是"有输出 且 不含明显错误特征"。这是**弱判据**，
    所以报告里会带上完整原始输出让人复核 —— 弱判据 + 完整证据
    比强判据 + 无证据更安全，因为前者错了人能看出来。
    """
    if not output or not output.strip():
        return False
    lowered = output.lower()
    error_markers = (
        "command not found", "no such file", "cannot find module",
        "segmentation fault", "permission denied", "eacces", "enomem",
    )
    return not any(marker in lowered for marker in error_markers)


# ═══════════════════════════════════════════════════════════════
#  P0-2 · EX-13 侧载受限设置
# ═══════════════════════════════════════════════════════════════

def run_p0_2(adb: Adb, ctx: Context) -> ExperimentResult:
    result = ExperimentResult(
        id="P0-2",
        title="EX-13 侧载应用的受限设置解除流程",
        why="决定产品形态是否成立。若侧载场景下拿不到无障碍，"
            "整个「社区分发 + 无障碍读屏」的方案在 Android 13+ 上不成立。",
    )

    # ── 检查 1：应用是否已安装 ───────────────────────────
    installed = adb.is_installed(ctx.package)
    if not installed:
        result.checks.append(_blocked(
            id_="P0-2.a", title="应用已安装",
            why="没装就谈不上受限设置。",
            next_step=(
                f"安装 APK：`adb install -r <apk>`（当前包名 `{ctx.package}`）。\n"
                "注意 debug 变体的包名是 `com.pocketagent.debug`，"
                "用 --package 指定。"
            ),
        ))
        return result

    result.checks.append(Check(
        id="P0-2.a", title="应用已安装",
        verdict=Verdict.PASS,
        evidence=[_ev("包名", ctx.package, "")],
    ))

    # ── 检查 2：受限设置的当前模式 ───────────────────────
    appops = adb.shell("cmd", "appops", "get", ctx.package, "ACCESS_RESTRICTED_SETTINGS")
    state = parse.parse_restricted_settings(appops.combined)

    if state is None:
        # 输出为空有两种原因，必须分开报
        if not appops.ok:
            result.checks.append(_blocked(
                id_="P0-2.b", title="读取 ACCESS_RESTRICTED_SETTINGS",
                why="命令没跑成功，拿不到受限设置的状态。",
                next_step=_fail(appops) or "检查设备连接。",
            ))
        else:
            result.checks.append(Check(
                id="P0-2.b", title="读取 ACCESS_RESTRICTED_SETTINGS",
                verdict=Verdict.UNKNOWN,
                evidence=[_ev("appops 输出", None, appops.combined)],
                consequence=(
                    "这个 appop 是 Android 13 引入的。读不到它有两种可能："
                    "① 设备是 Android 12 及以下，没有这个机制（好消息，但也意味着"
                    "测不出高版本的行为）；② 输出格式变了。"
                ),
                next_step="先看上面的 Android 版本。若 ≥ 13，把原始输出贴给开发者改解析。",
            ))
    else:
        if state.is_allowed:
            verdict = Verdict.PASS
            consequence = (
                f"当前是 `{state.mode}`，无障碍开关**可以被用户打开**。"
                "注意：这只说明权限层面放行了，不代表服务已经启用（见下一项）。"
            )
            next_step = ""
        elif state.is_blocked:
            verdict = Verdict.FAIL
            consequence = (
                "当前是 `deny` —— **系统正在主动拦截**。这正是 v2.0 方案担心的"
                "那个风险，且它已经在本机的侧载安装上复现了。"
                "必须走「应用信息 → 右上角三点 → 允许受限设置」才能解除。"
            )
            next_step = (
                "手动走一遍解除流程并记录步骤数与耗时（这是 EX-13 的核心产出）：\n"
                "  设置 → 应用管理 → 找到本应用 → 右上角 ⋮ → 允许受限设置 → 验证指纹\n"
                "然后重新跑本工具，看模式是否变成 `allow`。"
            )
        else:  # ignore
            verdict = Verdict.BLOCKED
            consequence = (
                "当前是 `ignore` —— 用户**已经看过**那个「为安全起见…」的弹窗，"
                "但还没授权。此时开关仍然是灰的，但「允许受限设置」入口已经出现。\n"
                "⚠️ 这个状态容易被误读成「用户没操作」，实际是「操作了一半」。"
            )
            next_step = "去应用信息页走完「允许受限设置」。"

        result.checks.append(Check(
            id="P0-2.b",
            title="受限设置模式",
            verdict=verdict,
            evidence=[
                _ev("ACCESS_RESTRICTED_SETTINGS", state.mode, state.raw),
            ],
            consequence=consequence,
            next_step=next_step,
        ))

    # ── 检查 3：无障碍服务是否已启用 ─────────────────────
    settings_result = adb.shell("settings", "get", "secure", "enabled_accessibility_services")
    enabled = parse.parse_enabled_accessibility_services(settings_result.stdout)

    if not settings_result.ok:
        result.checks.append(_blocked(
            id_="P0-2.c", title="无障碍服务已启用",
            why="读不到已启用的无障碍服务列表。",
            next_step=_fail(settings_result) or "检查设备连接。",
        ))
    else:
        service_on = parse.is_service_enabled(enabled, ctx.a11y_service)
        result.checks.append(Check(
            id="P0-2.c",
            title="无障碍服务已启用",
            verdict=Verdict.PASS if service_on else Verdict.FAIL,
            evidence=[
                _ev("已启用服务", sorted(enabled) or "（无）", settings_result.stdout),
                _ev("我们在找的服务", ctx.a11y_service, ""),
            ],
            consequence="" if service_on else (
                "服务没启用。**但先别急着下结论** —— 如果本 APK 的清单里"
                "压根没声明无障碍服务，那么无论用户怎么操作都开不了，"
                "这一项测的就不是「受限设置」而是「我们还没写这个功能」。"
            ),
            next_step="" if service_on else (
                f"确认 APK 里声明了 `{ctx.a11y_service}`。"
                "若没有声明，EX-13 的完整流程无法验证（见下一项）。"
            ),
        ))

    # ── 检查 4：清单里到底有没有无障碍服务 ───────────────
    #
    # ⚠️ 这一项是补上来的，因为它是**最容易把整个实验带偏的坑**：
    #    当前 APK 的 AndroidManifest.xml 里，无障碍相关的声明全部是注释。
    #    于是 P0-2.c 必然失败，而失败原因跟「受限设置」毫无关系 ——
    #    是「我们还没写这个服务」。不把这件事单独检出来，
    #    报告会给出一个指向错误方向的结论。
    pkg_dump = adb.shell("dumpsys", "package", ctx.package)
    declares_a11y = _declares_accessibility_service(pkg_dump.stdout, ctx.package)

    result.checks.append(Check(
        id="P0-2.d",
        title="APK 清单里声明了无障碍服务",
        verdict=Verdict.PASS if declares_a11y else Verdict.BLOCKED,
        evidence=[
            _ev("清单中声明无障碍服务", declares_a11y,
                _extract_service_lines(pkg_dump.stdout)),
        ],
        consequence="" if declares_a11y else (
            "**EX-13 现在测不了。** 受限设置这个机制只在「应用试图启用一个"
            "无障碍服务」时才被触发。没有服务声明，就没有可被拦截的对象 —— "
            "测出来的任何结果都不反映真实场景。\n"
            "这是前置条件缺失，不是实验失败。"
        ),
        next_step="" if declares_a11y else (
            "先在 AndroidManifest.xml 里加一个最小的 AccessibilityService 声明"
            "（连同 res/xml 的服务配置），重新打包安装，再跑本实验。\n"
            "它是 EX-13 的**实验器材**，不是产品功能 —— 这条例外需要显式说明。"
        ),
    ))

    # ── 检查 5（可选）：覆盖安装是否重置 ─────────────────
    if ctx.reinstall_apk:
        result.checks.append(_reinstall_reset_check(adb, ctx))

    return result


def _declares_accessibility_service(package_dump: str, package: str) -> bool:
    """
    从 `dumpsys package <pkg>` 的输出里判断是否声明了无障碍服务。

    判据：出现了 `android.permission.BIND_ACCESSIBILITY_SERVICE`。
    这是无障碍服务在清单里唯一的声明方式（作为 service 的 permission 属性），
    所以它的存在等价于"声明了至少一个无障碍服务"。

    ⚠️ 不能靠搜包名判断 —— 包名在 dumpsys package 里出现几十次，
       包括 activity、provider、receiver。必须搜那个权限名。
    """
    if not package_dump:
        return False
    return "android.permission.BIND_ACCESSIBILITY_SERVICE" in package_dump


def _extract_service_lines(package_dump: str) -> str:
    """把 dumpsys package 里跟无障碍相关的行摘出来，作为证据。"""
    if not package_dump:
        return ""
    hits = [
        line.strip()
        for line in package_dump.splitlines()
        if "ACCESSIBILITY" in line.upper() or "AccessibilityService" in line
    ]
    if not hits:
        return "（dumpsys package 输出里没有任何无障碍服务相关行）"
    return "\n".join(hits[:20])


def _reinstall_reset_check(adb: Adb, ctx: Context) -> Check:
    """
    覆盖安装后，受限设置是否被重置。

    ⚠️ 这是本工具**唯一会改动设备状态且不能完全回滚**的操作：
       `adb install -r` 保留数据，但会重新触发系统的安装流程，
       而 appop 正是安装流程设置的 —— 所以这个实验本身有副作用。
       因此它必须由用户显式用 `--reinstall-apk` 开启，绝不默认执行。
    """
    before_result = adb.shell("cmd", "appops", "get", ctx.package, "ACCESS_RESTRICTED_SETTINGS")
    before = parse.parse_restricted_settings(before_result.combined)

    apk_path = Path(ctx.reinstall_apk)
    if not apk_path.exists():
        return _blocked(
            id_="P0-2.e", title="覆盖安装后是否重置",
            why="指定的 APK 不存在。",
            next_step=f"检查路径：{apk_path}",
        )

    install = adb.raw("install", "-r", str(apk_path), timeout=180.0)
    if not install.ok or "Success" not in install.stdout:
        return _blocked(
            id_="P0-2.e", title="覆盖安装后是否重置",
            why="重装失败，无法比较。",
            next_step=f"{_fail(install) or ''}\n原始输出：{install.combined[:500]}",
        )

    after_result = adb.shell("cmd", "appops", "get", ctx.package, "ACCESS_RESTRICTED_SETTINGS")
    after = parse.parse_restricted_settings(after_result.combined)

    if before is None or after is None:
        return Check(
            id="P0-2.e", title="覆盖安装后是否重置",
            verdict=Verdict.UNKNOWN,
            evidence=[
                _ev("重装前", before.mode if before else None, before.raw if before else ""),
                _ev("重装后", after.mode if after else None, after.raw if after else ""),
            ],
            next_step="两次读取至少有一次没解析出来，无法比较。",
        )

    reset = before.mode != after.mode
    return Check(
        id="P0-2.e",
        title="覆盖安装后是否重置",
        verdict=Verdict.FAIL if reset else Verdict.PASS,
        evidence=[
            _ev("重装前", before.mode, before.raw),
            _ev("重装后", after.mode, after.raw),
        ],
        consequence=(
            "**重装了，受限设置被重置回 "
            f"`{after.mode}`。** 这意味着每次更新版本，用户都要重新走一遍"
            "「允许受限设置」+「开启无障碍」。对一个靠应用内自更新分发的应用，"
            "这是留存杀手 —— 而 v2.0 方案里把它列为「头号风险」正是为此。"
            "必须在更新流程里加显式引导，并在更新后主动检测并提示。"
        ) if reset else (
            "重装后模式不变，说明本机型/本版本不会因覆盖安装而重置。"
            "这是好消息，但**只对这一个机型和版本成立** —— 换 ROM 或升系统都要重测。"
        ),
        next_step=(
            "把这条结论写进更新流程的设计约束：更新后必须检测无障碍是否还在，"
            "不在就立刻引导用户重新开启。"
        ) if reset else "",
    )


# ═══════════════════════════════════════════════════════════════
#  P0-3 · EX-16 虚拟屏可行性
# ═══════════════════════════════════════════════════════════════

#: 副屏参数。1920x1080 @ 240dpi 是开发者选项里最常用的组合，
#: 足够放下一个正常布局的 App，又不会让分辨率高到拖慢渲染。
VD_SPEC = "1920x1080/240"


def run_p0_3(adb: Adb, ctx: Context) -> ExperimentResult:
    result = ExperimentResult(
        id="P0-3",
        title="EX-16 虚拟屏可行性",
        why="决定「不影响用户使用」这个核心卖点是否成立。"
            "若失败，产品必须降级为小窗/全屏接管。",
    )

    if not ctx.allow_display_changes:
        result.checks.append(_blocked(
            id_="P0-3.a", title="虚拟屏创建与启动",
            why="本实验需要临时修改设备的 `overlay_display_devices` 设置。"
                "工具默认不碰设备状态，需要显式开启。",
            next_step="确认后加 `--allow-display-changes` 重跑。"
                      "工具会在结束时恢复原值。",
        ))
        return result

    # ── 保存原值。⚠️ 必须在任何写入之前 ──────────────────
    original_raw = adb.shell("settings", "get", "global", "overlay_display_devices")
    original = parse.parse_overlay_display_setting(original_raw.stdout)

    try:
        result.checks.extend(_vd_checks(adb, ctx))
    finally:
        # ⚠️ 恢复必须放在 finally 里。中途任何异常都不能让用户
        #    手机上多出一个来路不明的副屏。
        restore = adb.shell("settings", "put", "global", "overlay_display_devices", original)
        if not restore.ok:
            result.errors.append(
                "⚠️ 未能恢复 overlay_display_devices！"
                f"当前值应为 `{original}`，请手动执行："
                f"`adb shell settings put global overlay_display_devices {original!r}`"
            )

    return result


def _vd_checks(adb: Adb, ctx: Context) -> list[Check]:
    checks: list[Check] = []

    before_ids = parse.parse_display_ids(adb.shell("dumpsys", "display").stdout)

    # ── 创建副屏 ────────────────────────────────────────
    put = adb.shell("settings", "put", "global", "overlay_display_devices", VD_SPEC)
    if not put.ok:
        checks.append(_blocked(
            id_="P0-3.a", title="创建虚拟屏",
            why="写入 overlay_display_devices 失败。",
            next_step=f"{_fail(put) or ''}\n"
                      "adb shell 以 shell 身份运行，本应有 WRITE_SECURE_SETTINGS。"
                      "若这里失败，Shizuku 大概率也不行 —— 那本身就是重要结论。",
        ))
        return checks

    # 系统创建 overlay display 需要一点时间
    time.sleep(2.0)
    after_dump = adb.shell("dumpsys", "display").stdout
    after_ids = parse.parse_display_ids(after_dump)
    overlay_ids = parse.parse_overlay_ids(after_dump)
    new_ids = after_ids - before_ids

    # ⚠️ 判据用 overlay 类型，而不是"有没有多出 id"。
    #    `dumpsys display` 里还有 `virtual:` 设备（投屏、录屏），
    #    只看"多了个 id"的话，一次投屏就能让这个实验假通过。
    if not (overlay_ids & new_ids):
        checks.append(Check(
            id="P0-3.a", title="创建虚拟屏",
            verdict=Verdict.FAIL,
            evidence=[
                _ev("设置写入", VD_SPEC, put.combined),
                _ev("创建前的 displayId", sorted(before_ids), ""),
                _ev("创建后的 displayId", sorted(after_ids), ""),
                _ev("其中 overlay 类型的", sorted(overlay_ids) or "（无）", ""),
            ],
            consequence=(
                "设置写进去了，但没有出现 **overlay 类型**的新显示设备。"
                "（如果只是多了别的 id，那多半是投屏/录屏建的 virtual display，"
                "不算数。）**「模拟辅助显示设备」这个机制在本机型/本版本上不生效。**"
            ),
            next_step=(
                "手动验证：开发者选项 → 模拟辅助显示设备 → 选一个分辨率，"
                "看主屏上是否出现一个副屏窗口。若手动也不出现，"
                "EX-16 直接判否，产品降级为小窗/全屏模式。"
            ),
        ))
        return checks

    vd_id = min(overlay_ids & new_ids)
    checks.append(Check(
        id="P0-3.a", title="创建虚拟屏",
        verdict=Verdict.PASS,
        evidence=[
            _ev("新 displayId", vd_id, ""),
            _ev("创建前", sorted(before_ids), ""),
            _ev("创建后", sorted(after_ids), ""),
        ],
    ))

    # ── 把 App 启到副屏 ─────────────────────────────────
    launched: list[str] = []
    failures: list[str] = []
    for target in ctx.vd_targets:
        start = adb.shell("am", "start", "--display", str(vd_id), "-n", target)
        if start.ok and "Error" not in start.stdout and "Exception" not in start.stdout:
            launched.append(target)
        else:
            failures.append(f"{target}: {start.combined.strip()[:200]}")

    if not ctx.vd_targets:
        checks.append(Check(
            id="P0-3.b", title="把第三方 App 启到副屏",
            verdict=Verdict.UNKNOWN,
            evidence=[_ev("目标", "（未指定）", "")],
            next_step="用 `--vd-target pkg/.Activity` 指定至少一个要测的 App。",
        ))
    else:
        ok = len(launched) > 0
        checks.append(Check(
            id="P0-3.b",
            title="把第三方 App 启到副屏",
            verdict=Verdict.PASS if ok else Verdict.FAIL,
            evidence=[
                _ev("成功", launched or "（无）", ""),
                _ev("失败", failures or "（无）", "\n".join(failures)),
            ],
            consequence="" if ok else (
                "**`am start --display` 起不来 App。** 这可能是目标 Activity 的"
                "`resizeableActivity` 声明问题，也可能是本 ROM 禁止跨显示启动。"
                "若换几个不同来源的 App 都是同样结果，EX-16 判否。"
            ),
            next_step="" if ok else (
                "换 `--vd-target` 再试几个：一个现代 App、一个老 App、一个金融类。"
                "设计文档要求至少 3 个主流 App 能启动才算通过。"
            ),
        ))

    # ── 截图 ────────────────────────────────────────────
    shot_path = f"/sdcard/p0-vd-{vd_id}.png"
    shot = adb.shell("screencap", "-d", str(vd_id), "-p", shot_path)
    checks.append(Check(
        id="P0-3.c", title="副屏截图",
        verdict=Verdict.PASS if shot.ok else Verdict.FAIL,
        evidence=[_ev("screencap 输出", shot.stdout.strip() or "（无输出即成功）", shot.combined)],
        consequence="" if shot.ok else (
            "副屏截不了图。感知层在虚拟屏模式下就拿不到画面 —— "
            "要么改用无障碍树（可能同样受限），要么虚拟屏方案不完整。"
        ),
        next_step="" if shot.ok else "看原始输出里的报错。常见是权限或该 display 不支持 GPU 合成。",
    ))

    # ── 输入注入 ────────────────────────────────────────
    tap = adb.shell("input", "-d", str(vd_id), "tap", "100", "100")
    checks.append(Check(
        id="P0-3.d", title="副屏输入注入",
        verdict=Verdict.PASS if tap.ok else Verdict.FAIL,
        evidence=[_ev("input tap 输出", tap.stdout.strip() or "（无输出即成功）", tap.combined)],
        consequence="" if tap.ok else (
            "输入注不进去，执行层在副屏上完全失效 —— 虚拟屏就只剩「看」没有「做」。"
        ),
        next_step="" if tap.ok else "看原始输出。部分 ROM 需要 `input -d` 之外的方式。",
    ))

    return checks


# ═══════════════════════════════════════════════════════════════
#  P0-4 · 内存预算实测
# ═══════════════════════════════════════════════════════════════

def run_p0_4(adb: Adb, ctx: Context) -> ExperimentResult:
    result = ExperimentResult(
        id="P0-4",
        title="内存预算实测",
        why=f"设计文档估算常驻 {MEMORY_BUDGET_MIN_MB}–{MEMORY_BUDGET_MAX_MB}MB，"
            "而豆包的 aikernel 是 160MB。超了就是明确的劣势。",
    )

    # ── 系统总内存 ──────────────────────────────────────
    meminfo = adb.shell("cat", "/proc/meminfo")
    total_kb = _proc_meminfo_value(meminfo.stdout, "MemTotal")
    avail_kb = _proc_meminfo_value(meminfo.stdout, "MemAvailable")

    result.checks.append(Check(
        id="P0-4.a", title="设备总内存与可用量",
        verdict=Verdict.PASS if total_kb else Verdict.UNKNOWN,
        evidence=[
            _ev("MemTotal (MB)", total_kb // 1024 if total_kb else None, ""),
            _ev("MemAvailable (MB)", avail_kb // 1024 if avail_kb else None, ""),
        ],
        caveat=(
            "这是**空闲状态**的可用量。真正的风险不是「够不够」，"
            "而是「系统在压力下会不会杀我们」—— 那要看 HyperOS 的"
            "后台策略（EX-15），不是看这个数字。"
        ),
    ))

    # ── 应用内存采样 ────────────────────────────────────
    if not adb.is_installed(ctx.package):
        result.checks.append(_blocked(
            id_="P0-4.b", title="应用常驻内存",
            why="应用没装，测不到。",
            next_step=f"安装 APK 后用 `--package` 指定实际包名（debug 变体是 {ctx.package}.debug）。",
        ))
        return result

    samples: list[int] = []
    raw_last = ""
    for index in range(ctx.mem_samples):
        out = adb.shell("dumpsys", "meminfo", ctx.package)
        raw_last = out.combined
        pss = parse.parse_meminfo_total_pss(out.stdout)
        if pss is not None:
            samples.append(pss)
        if index < ctx.mem_samples - 1:
            time.sleep(ctx.mem_interval)

    if not samples:
        result.checks.append(Check(
            id="P0-4.b", title="应用常驻内存",
            verdict=Verdict.UNKNOWN,
            evidence=[_ev("dumpsys meminfo", None, raw_last)],
            consequence="没解析出 TOTAL PSS，测不到占用。",
            next_step="把原始输出贴给开发者 —— Android 12 前后这个格式改过，可能需要补解析分支。",
        ))
        return result

    peak_mb = max(samples) // 1024
    avg_mb = (sum(samples) // len(samples)) // 1024
    within = peak_mb <= MEMORY_BUDGET_MAX_MB

    result.checks.append(Check(
        id="P0-4.b",
        title="应用常驻内存",
        verdict=Verdict.PASS if within else Verdict.FAIL,
        evidence=[
            _ev(f"峰值 PSS (MB)，{len(samples)} 次采样", peak_mb, ""),
            _ev("平均 PSS (MB)", avg_mb, ""),
            _ev("预算上限 (MB)", MEMORY_BUDGET_MAX_MB, ""),
        ],
        consequence="" if within else (
            f"峰值 {peak_mb}MB 已超过 {MEMORY_BUDGET_MAX_MB}MB 的预算上限。"
            "而这还**只是 App 自己** —— Node 与 dsh 是另外的进程，"
            "加起来会远超豆包的 160MB。"
        ),
        next_step="" if within else (
            "优先看 D-J（Node 按需启动）能否落地：空闲时不驻留 Node，"
            "冷启动延迟换常驻内存。"
        ),
        caveat=(
            "⚠️ 这测的是**空闲**的 App。真正要测的是「一次完整 agent 任务期间」的峰值 —— "
            "那时才有截图缓冲、无障碍节点树、模型请求体同时在内存里。"
            "任务跑起来之前，这个数字只能当**下界**。"
        ),
    ))

    return result


def _proc_meminfo_value(text: str, key: str) -> Optional[int]:
    """从 /proc/meminfo 里取一个 kB 值。"""
    match = re.search(rf"^{key}:\s*(\d+)\s*kB", text or "", re.MULTILINE)
    return int(match.group(1)) if match else None


# ═══════════════════════════════════════════════════════════════
#  P0-5 · 能耗基线
# ═══════════════════════════════════════════════════════════════

def run_p0_5(adb: Adb, ctx: Context) -> ExperimentResult:
    result = ExperimentResult(
        id="P0-5",
        title="能耗基线实测",
        why="设计文档 §2.4 的能耗全是**估算**（8 轮约 0.07% 电量）。"
            "这一项必须实测，否则「轮次优化」这个核心论点没有数据支撑。",
    )

    battery = adb.shell("dumpsys", "battery")
    level = parse.parse_battery_level(battery.stdout)
    counter = parse.parse_charge_counter(battery.stdout)

    result.checks.append(Check(
        id="P0-5.a", title="电池读数可用性",
        verdict=Verdict.PASS if counter is not None else Verdict.UNKNOWN,
        evidence=[
            _ev("电量 (%)", level, ""),
            _ev("Charge counter (µAh)", counter, battery.stdout if counter is None else ""),
        ],
        consequence="" if counter is not None else (
            "**这台设备不提供库仑计读数。** 意味着无法用「充放电计数差」"
            "做直接测量，只能退回 `dumpsys batterystats` 的**估算值**。"
            "估算值的模型对屏幕亮度的权重过高、对短时 CPU 峰值过低 —— "
            "而 agent 任务恰好是后者。结论的精度会明显下降，必须写进报告。"
        ),
        caveat=(
            "即便有读数，它的量化误差约 ±1%。5000mAh 电池上是 ±50mAh，"
            "而单次 8 轮任务预计只耗几十 mAh —— **所以它测不了单次任务，"
            "只能测长任务（比如连续跑 30 分钟）**。"
        ),
    ))

    # ── batterystats 估算值 ─────────────────────────────
    if ctx.reset_batterystats:
        reset = adb.shell("dumpsys", "batterystats", "--reset")
        if not reset.ok:
            result.errors.append(f"batterystats --reset 失败：{reset.combined[:300]}")

    if adb.is_installed(ctx.package):
        stats = adb.shell("dumpsys", "batterystats", ctx.package, timeout=30.0)
        power = parse.parse_batterystats_uid_power(stats.stdout)

        result.checks.append(Check(
            id="P0-5.b",
            title="应用累计估算功耗",
            verdict=Verdict.PASS if power is not None else Verdict.UNKNOWN,
            evidence=[
                _ev("估算功耗 (mAh)", power, stats.combined if power is None else _uid_block(stats.stdout)),
            ],
            consequence=(
                "这是**累计值**，不是单次任务的值。要得到单次任务能耗，"
                "需要在任务前后各读一次做差 —— 而差值会被 ±1% 的量化误差淹没。"
                "所以正确做法是：跑 10 次同样的任务，取平均。"
            ),
            next_step=(
                "① 记录当前值 → ② 连续跑 10 次同样的 8 轮任务 → "
                "③ 再读一次 → ④ 除以 10。"
                "用 `--reset-batterystats` 可以先清零，让读数更干净。"
            ),
        ))
    else:
        result.checks.append(_blocked(
            id_="P0-5.b", title="应用累计估算功耗",
            why="应用没装，测不到。",
            next_step="安装 APK 后重跑。",
        ))

    return result


def _uid_block(output: str) -> str:
    """把 batterystats 里的 Estimated power use 段摘出来作为证据。"""
    lines = output.splitlines()
    for index, line in enumerate(lines):
        if "Estimated power use" in line:
            return "\n".join(lines[index:index + 12])
    return truncate(output, 1500)


# ═══════════════════════════════════════════════════════════════
#  注册表
# ═══════════════════════════════════════════════════════════════

ALL_EXPERIMENTS = {
    "P0-1": run_p0_1,
    "P0-2": run_p0_2,
    "P0-3": run_p0_3,
    "P0-4": run_p0_4,
    "P0-5": run_p0_5,
}

#: 依赖关系。P0-3 依赖 P0-2（拿不到无障碍，虚拟屏也没有操作通道）；
#: P0-4/P0-5 依赖 P0-1（没有 Node 就没有要测的常驻进程）。
#: 这里只用于**报告里给出提示**，不做强制拦截 ——
#: 强行禁止会让用户无法单独重跑某一项。
EXPERIMENT_DEPS = {
    "P0-3": ["P0-2"],
    "P0-4": ["P0-1"],
    "P0-5": ["P0-1"],
}
