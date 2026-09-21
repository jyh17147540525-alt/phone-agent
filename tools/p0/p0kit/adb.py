# -*- coding: utf-8 -*-
"""
P0 验证执行器 · adb 封装

职责边界：**只负责"把命令送到设备上并拿回文本"，不做任何解析、不做任何判定。**
判定在 `experiments.py`，解析在 `parse.py`。

═══════════════════════════════════════════════════════════════
  为什么错误要分类，而不是一律抛异常
═══════════════════════════════════════════════════════════════

adb 失败的方式有好几种，它们**对应完全不同的处置**：

    没有设备        → 插线 / 开 USB 调试 / 点"允许调试"
    adb 不在        → 配 ANDROID_HOME 或用 --adb 指定
    命令超时        → 设备卡住或命令本身很慢（`dumpsys` 经常几秒）
    命令返回非零    → 命令跑了但失败了（例如包没装）
    shell 报错文本  → 命令"成功"但输出里有错误（`cmd appops` 找不到包时
                      会返回 0 并打印一句错误）

如果一律抛异常，调用方只能 catch 一个大类，然后写
"出错了，请检查设备连接" —— 而真正的原因可能是"这个包没装"。
所以这里把失败建模成**返回值的一部分**（`ShellResult.ok`），
让调用方按类型分支。

最后一种（返回 0 但输出是错误）是最阴险的：它会让"包没装"看起来像
"appops 返回了空"，进而在报告里变成"未测到"而不是"前置未满足"。
`looks_like_error` 专门处理它。
"""

from __future__ import annotations

import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Sequence


#: adb 的候选位置。按顺序尝试。
#:
#: ⚠️ 本机实测：adb 在 `D:/AndroidDev/sdk/platform-tools/adb.exe`，
#:    而 `local.properties` 里的 `sdk.dir=D:/AndroidDev/sdk`。
#:    这里把常见位置全列出来，是为了让工具在别人的机器上也能跑 ——
#:    否则每换一台机器就要改一次代码，而"改代码才能用"的工具
#:    最终没人会用。
_ADB_CANDIDATES = (
    "D:/AndroidDev/sdk/platform-tools/adb.exe",
    "D:/AndroidDev/sdk/platform-tools/adb",
    os.path.expanduser("~/AppData/Local/Android/Sdk/platform-tools/adb.exe"),
    "/usr/local/bin/adb",
    "/opt/homebrew/bin/adb",
)

#: `dumpsys` 在低端机上可能跑好几秒，而 `batterystats` 更慢。
#: 15 秒是"足够宽松但不会让工具卡死"的折中。
DEFAULT_TIMEOUT = 15.0


class AdbNotFound(RuntimeError):
    """找不到 adb 可执行文件。"""


def find_adb(explicit: Optional[str] = None) -> str:
    """
    定位 adb。顺序：显式指定 → ANDROID_HOME/ANDROID_SDK_ROOT → 已知路径 → PATH。

    ⚠️ 显式指定排在最前，是为了让用户在非常规环境下能立刻绕开 ——
       而不是先花十分钟研究为什么自动探测失败。
    """
    if explicit:
        path = Path(explicit)
        if path.exists():
            return str(path)
        raise AdbNotFound(f"--adb 指定的路径不存在：{explicit}")

    for env_var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(env_var)
        if root:
            for name in ("adb.exe", "adb"):
                candidate = Path(root) / "platform-tools" / name
                if candidate.exists():
                    return str(candidate)

    for candidate in _ADB_CANDIDATES:
        if Path(candidate).exists():
            return candidate

    found = shutil.which("adb")
    if found:
        return found

    raise AdbNotFound(
        "找不到 adb。请用 --adb <路径> 指定，或设置 ANDROID_HOME。\n"
        "本机已知位置：D:/AndroidDev/sdk/platform-tools/adb.exe"
    )


@dataclass
class ShellResult:
    """
    一次 shell 命令的结果。

    ⚠️ `stdout` 与 `stderr` **刻意分开保留**。合并成一个流会让
       "命令的输出里恰好包含 ERROR 这个词"（比如 dumpsys 里有个叫
       ERROR 的状态字段）被误判成命令失败。
    """

    command: str
    returncode: int
    stdout: str
    stderr: str
    timed_out: bool = False

    @property
    def ok(self) -> bool:
        """命令本身是否成功执行（不代表输出内容符合预期）。"""
        return self.returncode == 0 and not self.timed_out

    @property
    def combined(self) -> str:
        """合并输出，用于展示。保留两个流的顺序信息不重要，够用即可。"""
        parts = [self.stdout]
        if self.stderr.strip():
            parts.append("--- stderr ---")
            parts.append(self.stderr)
        return "\n".join(p for p in parts if p is not None)

    def error_kind(self) -> Optional[str]:
        """
        把失败归类成一个人能直接处置的短标签。

        返回 None 表示命令成功。

        ⚠️ 这里的判断顺序是**从具体到宽泛**。`device unauthorized`
           必须排在 `unauthorized` 之前吗？不必 —— 两者都是同一处置。
           但 `no devices/emulators found` 必须排在任何泛化的
           "error" 判断之前，否则会被归成笼统的"未知错误"。
        """
        if self.timed_out:
            return "timeout"

        if self.returncode == 0:
            return None

        text = (self.stderr + "\n" + self.stdout).lower()

        if "no devices" in text or "device not found" in text:
            return "no-device"
        if "unauthorized" in text:
            return "unauthorized"
        if "more than one device" in text:
            return "multiple-devices"
        if "device offline" in text:
            return "device-offline"
        if "not found" in text:
            return "not-found"

        return "command-failed"

    def hint(self) -> str:
        """针对错误类型给一句可执行的话。"""
        kind = self.error_kind()
        return {
            "no-device": "设备没连上。插 USB 线，并在手机上把 USB 用途设为「传输文件」，"
                         "然后在开发者选项里打开 USB 调试。",
            "unauthorized": "手机上会弹「允许 USB 调试吗」，点允许（可勾选始终允许）。"
                            "如果没弹，在开发者选项里点「撤销 USB 调试授权」再重插。",
            "multiple-devices": "连了不止一台设备。加 --serial <序列号> 指定，"
                                "或用 `adb devices -l` 看序列号。",
            "device-offline": "设备处于 offline。拔插一次数据线，或 `adb kill-server` 后重试。",
            "timeout": f"命令超过 {DEFAULT_TIMEOUT:.0f} 秒没返回。"
                       "`dumpsys` 在负载高时确实会慢，可以先解锁屏幕再重试。",
            "not-found": "命令或目标不存在。多半是应用包名写错了，"
                         "或该 Android 版本没有这个命令。",
            "command-failed": "命令返回了非零。看上面的原始输出定位。",
        }.get(kind, "")


class Adb:
    """adb 会话。所有命令都通过它走，便于统一加超时和错误分类。"""

    def __init__(self, adb_path: str, serial: Optional[str] = None):
        self.adb_path = adb_path
        self.serial = serial

    # ── 构造命令 ────────────────────────────────────────────

    def _base(self) -> list[str]:
        cmd = [self.adb_path]
        if self.serial:
            cmd += ["-s", self.serial]
        return cmd

    # ── 执行 ────────────────────────────────────────────────

    def _run(self, args: Sequence[str], timeout: float) -> ShellResult:
        cmd = self._base() + list(args)
        printable = " ".join(args)

        try:
            proc = subprocess.run(
                cmd,
                capture_output=True,
                timeout=timeout,
                # ⚠️ 必须显式指定编码并用 errors="replace"。
                #    adb 的输出里会有 GBK 编码的中文（国产 ROM 的
                #    dumpsys 输出就是），Windows 默认按 GBK 解，
                #    而 Linux/macOS 按 UTF-8 解 —— 一旦解码失败，
                #    默认行为是**抛异常**，整个实验直接崩。
                #    用 replace 后最坏情况是几个乱码字符，不影响解析。
                encoding="utf-8",
                errors="replace",
            )
            return ShellResult(
                command=printable,
                returncode=proc.returncode,
                stdout=proc.stdout or "",
                stderr=proc.stderr or "",
            )
        except subprocess.TimeoutExpired as exc:
            return ShellResult(
                command=printable,
                returncode=-1,
                stdout=_decode(exc.stdout),
                stderr=_decode(exc.stderr),
                timed_out=True,
            )

    def shell(self, *args: str, timeout: float = DEFAULT_TIMEOUT) -> ShellResult:
        """
        执行 `adb shell <args...>`。

        ⚠️ 参数**逐个传**，不要拼成一个字符串。Android 的 shell 会
           对字符串再做一次分词，于是 `settings put global x "a b"`
           里的引号会被吃掉一层，写进去的值变成 `a`。
           逐个传时 adb 会自己做转义。
        """
        return self._run(["shell", *args], timeout=timeout)

    def raw(self, *args: str, timeout: float = DEFAULT_TIMEOUT) -> ShellResult:
        """执行不带 `shell` 前缀的 adb 命令（如 `adb devices`、`adb pull`）。"""
        return self._run(list(args), timeout=timeout)

    # ── 便捷方法 ────────────────────────────────────────────

    def devices(self) -> list[tuple[str, str]]:
        """返回 [(serial, state), ...]。state 常见值：device / unauthorized / offline。"""
        result = self.raw("devices")
        out: list[tuple[str, str]] = []
        for line in result.stdout.splitlines():
            line = line.strip()
            if not line or line.startswith("List of devices"):
                continue
            parts = line.split()
            if len(parts) >= 2:
                out.append((parts[0], parts[1]))
        return out

    def getprop(self, key: str) -> str:
        return self.shell("getprop", key).stdout.strip()

    def is_installed(self, package: str) -> bool:
        """
        包是否已安装。

        ⚠️ 用 `pm list packages <pkg>` 而不是 `pm path <pkg>`：
           后者在包不存在时返回非零，但某些 ROM 上返回 0 并打印空 ——
           两种行为都要能正确处理，而 list 的输出格式更稳定。

        ⚠️ 比较时要**精确匹配包名**。`pm list packages com.foo`
           做的是子串匹配，`com.foobar` 也会被列出来 ——
           于是"我们自己的包没装"会被误判成"装了"。
        """
        result = self.shell("pm", "list", "packages", package)
        if not result.ok:
            return False
        for line in result.stdout.splitlines():
            if line.strip() == f"package:{package}":
                return True
        return False


def _decode(data) -> str:
    """subprocess 超时异常里带的可能是 bytes。"""
    if data is None:
        return ""
    if isinstance(data, bytes):
        return data.decode("utf-8", errors="replace")
    return str(data)
