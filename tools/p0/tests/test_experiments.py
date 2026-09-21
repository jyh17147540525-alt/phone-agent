# -*- coding: utf-8 -*-
"""
P0 验证执行器 · 判定逻辑单测（用假 adb）

═══════════════════════════════════════════════════════════════
  为什么必须测判定，而不只是测解析
═══════════════════════════════════════════════════════════════

解析对了但判定错了，报告依然是错的。而且判定错得更隐蔽 ——
它不会抛异常，只会给出一个方向相反的结论。

本文件用 `FakeAdb` 按命令回放预设输出，于是可以在**没有手机**的情况下
验证：给定这些设备状态，工具会得出什么结论。

重点覆盖三类判定错误：

  1. **该说"没测到"的说了"不通过"** —— 让用户白折腾一遍
  2. **该说"不通过"的说了"没测到"** —— 让问题被忽略
  3. **依赖链断了却给出了肯定结论** —— 最危险，例如清单里没有无障碍服务，
     却把"服务未启用"报成"受限设置拦住了"

跑法：`cd tools/p0 && python -m unittest discover -s tests -v`
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT))

from p0kit import Verdict  # noqa: E402
from p0kit.adb import ShellResult  # noqa: E402
from p0kit.experiments import Context, run_p0_1, run_p0_2, run_p0_4  # noqa: E402

FIXTURES = _ROOT / "fixtures"


def fixture(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


class FakeAdb:
    """
    按命令回放输出的假 adb。

    `responses` 的键是命令的**空格拼接形式**，值可以是：
      · str           → 输出，返回码 0
      · (str, int)    → 输出 + 返回码
      · ShellResult   → 原样返回

    没命中的命令返回空输出 + 返回码 0，并在 `calls` 里留痕 ——
    这样"工具问了什么"是可检查的，而不只是"工具答了什么"。
    """

    def __init__(self, responses: dict | None = None, installed: bool = True):
        self.responses = responses or {}
        self.installed = installed
        self.calls: list[str] = []
        self.writes: list[tuple[str, str]] = []

    def _lookup(self, key: str) -> ShellResult:
        value = self.responses.get(key)
        if value is None:
            return ShellResult(key, 0, "", "")
        if isinstance(value, ShellResult):
            return value
        if isinstance(value, tuple):
            text, code = value
            return ShellResult(key, code, text, "")
        return ShellResult(key, 0, value, "")

    def shell(self, *args, timeout=15.0) -> ShellResult:
        key = " ".join(args)
        self.calls.append(key)
        return self._lookup(key)

    def raw(self, *args, timeout=15.0) -> ShellResult:
        key = " ".join(args)
        self.calls.append(key)
        return self._lookup(key)

    def getprop(self, key: str) -> str:
        result = self.shell("getprop", key)
        return result.stdout.strip()

    def is_installed(self, package: str) -> bool:
        return self.installed

    def devices(self):
        return [("FAKESERIAL", "device")]


def all_checks(result):
    return {c.id: c for c in result.checks}


# ═══════════════════════════════════════════════════════════════
#  P0-1
# ═══════════════════════════════════════════════════════════════

class TestP0_1(unittest.TestCase):

    def _ctx(self, report: str | None) -> Context:
        ctx = Context()
        if report is not None:
            path = FIXTURES / report
            ctx.node_report = path
        return ctx

    def _adb(self) -> FakeAdb:
        return FakeAdb({
            "getprop": fixture("getprop_k60.txt"),
            "df -k /data": fixture("df_data.txt"),
        })

    def test_all_pass(self):
        result = run_p0_1(self._adb(), self._ctx("node_report_ok.txt"))
        self.assertEqual(result.verdict, Verdict.PASS)
        self.assertEqual(all_checks(result)["P0-1.c"].verdict, Verdict.PASS)
        self.assertEqual(all_checks(result)["P0-1.e"].verdict, Verdict.PASS)

    def test_no_report_is_blocked_not_failed(self):
        """
        ⚠️ 没拿到 Termux 输出时必须报 BLOCKED，不能报 FAIL。

        报 FAIL 会让人以为"Node 跑不起来"，而实际上**根本没测**。
        这个区别决定了用户接下来是"去改方案"还是"去装个 Termux" ——
        方向完全不同。
        """
        result = run_p0_1(self._adb(), self._ctx(None))
        self.assertEqual(result.verdict, Verdict.BLOCKED)
        self.assertEqual(all_checks(result)["P0-1.c"].verdict, Verdict.BLOCKED)
        # 而且必须给出可执行的下一步
        self.assertIn("Termux", all_checks(result)["P0-1.c"].next_step)

    def test_low_node_version_fails(self):
        result = run_p0_1(self._adb(), self._ctx("node_report_low_version.txt"))
        self.assertEqual(all_checks(result)["P0-1.c"].verdict, Verdict.FAIL)
        self.assertEqual(result.verdict, Verdict.FAIL)

    def test_dsh_failure_is_distinguished_from_node_failure(self):
        """
        ⚠️ Node 通过但 dsh 失败，是两个不同的结论。

        Node 只是载体，dsh 才是要跑的东西。把两者混为一谈会让
        "Node 装好了"被当成"路线 C 可行"。
        """
        result = run_p0_1(self._adb(), self._ctx("node_report_dsh_failed.txt"))
        checks = all_checks(result)
        self.assertEqual(checks["P0-1.c"].verdict, Verdict.PASS, "Node 版本应通过")
        self.assertEqual(checks["P0-1.d"].verdict, Verdict.PASS, "toReversed 应通过")
        self.assertEqual(checks["P0-1.e"].verdict, Verdict.FAIL, "dsh 应不通过")
        self.assertEqual(result.verdict, Verdict.FAIL)

    def test_wrong_abi_fails(self):
        adb = FakeAdb({
            "getprop": "[ro.product.cpu.abi]: [armeabi-v7a]\n"
                       "[ro.product.model]: [23013RK75C]\n",
            "df -k /data": fixture("df_data.txt"),
        })
        result = run_p0_1(adb, self._ctx("node_report_ok.txt"))
        self.assertEqual(all_checks(result)["P0-1.a"].verdict, Verdict.FAIL)

    def test_low_storage_fails(self):
        adb = FakeAdb({
            "getprop": fixture("getprop_k60.txt"),
            "df -k /data": "Filesystem 1K-blocks Used Available Use% Mounted on\n"
                           "/dev/block/dm-5 113246208 113000000 246208 99% /data\n",
        })
        result = run_p0_1(adb, self._ctx("node_report_ok.txt"))
        self.assertEqual(all_checks(result)["P0-1.b"].verdict, Verdict.FAIL)

    def test_non_target_device_gets_caveat(self):
        """不是 K60 时要加限定语，但**不因此判失败** —— 结论依然有参考价值。"""
        adb = FakeAdb({
            "getprop": "[ro.product.model]: [Pixel 8]\n"
                       "[ro.product.cpu.abi]: [arm64-v8a]\n",
            "df -k /data": fixture("df_data.txt"),
        })
        result = run_p0_1(adb, self._ctx("node_report_ok.txt"))
        self.assertEqual(all_checks(result)["P0-1.a"].verdict, Verdict.UNKNOWN)
        self.assertIn("K60", all_checks(result)["P0-1.a"].caveat)


# ═══════════════════════════════════════════════════════════════
#  P0-2
# ═══════════════════════════════════════════════════════════════

class TestP0_2(unittest.TestCase):

    def _adb(self, appops: str, a11y: str, declares: bool) -> FakeAdb:
        pkg_dump = (
            "Packages:\n  Package [com.pocketagent]:\n"
            "    Service Resolver Table:\n"
            + ("      android.permission.BIND_ACCESSIBILITY_SERVICE\n" if declares else "")
            + "    User 0:\n"
        )
        return FakeAdb({
            "cmd appops get com.pocketagent ACCESS_RESTRICTED_SETTINGS": appops,
            "settings get secure enabled_accessibility_services": a11y,
            "dumpsys package com.pocketagent": pkg_dump,
        })

    def test_not_installed_is_blocked(self):
        adb = FakeAdb(installed=False)
        result = run_p0_2(adb, Context())
        self.assertEqual(result.verdict, Verdict.BLOCKED)
        self.assertEqual(len(result.checks), 1, "没装就不该继续往下测")

    def test_deny_is_fail(self):
        """deny 就是 v2.0 担心的那个风险复现了 —— 必须明确报 FAIL。"""
        adb = self._adb(fixture("appops_deny.txt"), "", declares=True)
        result = run_p0_2(adb, Context())
        self.assertEqual(all_checks(result)["P0-2.b"].verdict, Verdict.FAIL)
        self.assertIn("deny", all_checks(result)["P0-2.b"].consequence)

    def test_ignore_is_blocked_not_pass(self):
        """
        ⚠️ ignore 必须报 BLOCKED（还差用户一步），**不能报 PASS**。

        ignore 时无障碍开关仍然是灰的。报 PASS 会让用户去开开关、
        发现开不了、然后怀疑自己。
        """
        adb = self._adb(fixture("appops_ignore.txt"), "", declares=True)
        result = run_p0_2(adb, Context())
        self.assertEqual(all_checks(result)["P0-2.b"].verdict, Verdict.BLOCKED)

    def test_allow_passes(self):
        a11y = "com.pocketagent/com.pocketagent.assistant.AgentAccessibilityService"
        adb = self._adb(fixture("appops_allow.txt"), a11y + "\n", declares=True)
        result = run_p0_2(adb, Context())
        self.assertEqual(all_checks(result)["P0-2.b"].verdict, Verdict.PASS)
        self.assertEqual(all_checks(result)["P0-2.c"].verdict, Verdict.PASS)
        self.assertEqual(all_checks(result)["P0-2.d"].verdict, Verdict.PASS)

    def test_missing_a11y_declaration_is_blocked(self):
        """
        ⚠️ **本文件最重要的一条。**

        当前 APK 的清单里无障碍声明全是注释。这会导致：
          · P0-2.c（服务已启用）必然失败
          · 而失败原因跟"受限设置"毫无关系 —— 是"我们还没写这个服务"

        如果不单独检出这件事，报告会把"我们没写功能"误导成
        "系统拦住了我们"，进而让项目去查一个不存在的问题。
        """
        adb = self._adb(fixture("appops_deny.txt"), "", declares=False)
        result = run_p0_2(adb, Context())
        checks = all_checks(result)
        self.assertEqual(checks["P0-2.d"].verdict, Verdict.BLOCKED)
        self.assertIn("测不了", checks["P0-2.d"].consequence)
        self.assertIn("AndroidManifest", checks["P0-2.d"].next_step)

    def test_appops_unreadable_is_unknown_not_fail(self):
        """
        读不到 appops 时不能报 FAIL —— 那是在说"受限设置拦住了我们"，
        而我们其实什么都不知道。Android 12 及以下根本没有这个 appop。
        """
        adb = self._adb("", "", declares=True)
        result = run_p0_2(adb, Context())
        self.assertEqual(all_checks(result)["P0-2.b"].verdict, Verdict.UNKNOWN)

    def test_appops_command_failure_is_blocked(self):
        adb = self._adb("", "", declares=True)
        adb.responses["cmd appops get com.pocketagent ACCESS_RESTRICTED_SETTINGS"] = (
            "", 1)
        result = run_p0_2(adb, Context())
        self.assertEqual(all_checks(result)["P0-2.b"].verdict, Verdict.BLOCKED)


# ═══════════════════════════════════════════════════════════════
#  P0-4
# ═══════════════════════════════════════════════════════════════

class TestP0_4(unittest.TestCase):

    def test_memory_within_budget(self):
        adb = FakeAdb({
            "cat /proc/meminfo": "MemTotal:       16000000 kB\n"
                                 "MemAvailable:    4000000 kB\n",
            "dumpsys meminfo com.pocketagent": fixture("meminfo_android12plus.txt"),
        })
        ctx = Context(mem_samples=2, mem_interval=0)
        result = run_p0_4(adb, ctx)
        # 45678 KB ≈ 44 MB，远低于 230MB 上限
        self.assertEqual(all_checks(result)["P0-4.b"].verdict, Verdict.PASS)

    def test_memory_over_budget(self):
        big = "        TOTAL PSS: 300000    TOTAL RSS: 320000\n"
        adb = FakeAdb({
            "cat /proc/meminfo": "MemTotal: 16000000 kB\n",
            "dumpsys meminfo com.pocketagent": big,
        })
        ctx = Context(mem_samples=1, mem_interval=0)
        result = run_p0_4(adb, ctx)
        # 300000 KB ≈ 292 MB > 230MB
        self.assertEqual(all_checks(result)["P0-4.b"].verdict, Verdict.FAIL)

    def test_unparseable_meminfo_is_unknown(self):
        adb = FakeAdb({
            "cat /proc/meminfo": "MemTotal: 16000000 kB\n",
            "dumpsys meminfo com.pocketagent": fixture("meminfo_no_total.txt"),
        })
        ctx = Context(mem_samples=1, mem_interval=0)
        result = run_p0_4(adb, ctx)
        self.assertEqual(all_checks(result)["P0-4.b"].verdict, Verdict.UNKNOWN)

    def test_idle_caveat_always_present(self):
        """
        ⚠️ 无论通过与否，都必须带上"这只是空闲值"的限定。

        真正要测的是任务期间的峰值。把空闲值当结论会让项目
        在内存预算上产生虚假的安全感。
        """
        adb = FakeAdb({
            "cat /proc/meminfo": "MemTotal: 16000000 kB\n",
            "dumpsys meminfo com.pocketagent": fixture("meminfo_android12plus.txt"),
        })
        ctx = Context(mem_samples=1, mem_interval=0)
        result = run_p0_4(adb, ctx)
        self.assertIn("空闲", all_checks(result)["P0-4.b"].caveat)


# ═══════════════════════════════════════════════════════════════
#  汇总判定的优先级
# ═══════════════════════════════════════════════════════════════

class TestVerdictAggregation(unittest.TestCase):

    def test_fail_beats_blocked(self):
        """
        ⚠️ FAIL 优先于 BLOCKED。

        直觉上"被挡住"更严重，但对本项目相反：FAIL 是**拿到了反面证据**，
        可以直接做设计决策；BLOCKED 只是还没拿到证据，决策还得悬着。
        报告的第一行要反映"现在能做什么决策"，而不是"有多难"。
        """
        from p0kit.model import Check, ExperimentResult
        exp = ExperimentResult(id="X", title="t", why="", checks=[
            Check(id="a", title="", verdict=Verdict.PASS),
            Check(id="b", title="", verdict=Verdict.BLOCKED),
            Check(id="c", title="", verdict=Verdict.FAIL),
        ])
        self.assertEqual(exp.verdict, Verdict.FAIL)

    def test_empty_is_unknown_not_pass(self):
        """一个什么都没测的实验**不是通过的实验**。"""
        from p0kit.model import ExperimentResult
        exp = ExperimentResult(id="X", title="t", why="")
        self.assertEqual(exp.verdict, Verdict.UNKNOWN)

    def test_all_pass(self):
        from p0kit.model import Check, ExperimentResult
        exp = ExperimentResult(id="X", title="t", why="", checks=[
            Check(id="a", title="", verdict=Verdict.PASS),
            Check(id="b", title="", verdict=Verdict.PASS),
        ])
        self.assertEqual(exp.verdict, Verdict.PASS)


# ═══════════════════════════════════════════════════════════════
#  adb 错误分类
# ═══════════════════════════════════════════════════════════════

class TestAdbErrors(unittest.TestCase):

    def test_no_device(self):
        r = ShellResult("x", 1, "", "error: no devices/emulators found")
        self.assertEqual(r.error_kind(), "no-device")
        self.assertIn("USB", r.hint())

    def test_unauthorized(self):
        r = ShellResult("x", 1, "", "error: device unauthorized.")
        self.assertEqual(r.error_kind(), "unauthorized")

    def test_timeout(self):
        r = ShellResult("x", -1, "", "", timed_out=True)
        self.assertEqual(r.error_kind(), "timeout")

    def test_success_has_no_error(self):
        self.assertIsNone(ShellResult("x", 0, "ok", "").error_kind())

    def test_stdout_containing_error_word_is_not_a_failure(self):
        """
        ⚠️ 判断失败只看返回码，不看输出内容。

        `dumpsys` 的输出里完全可能出现 `ERROR` 这个词（比如某个
        状态字段就叫 ERROR）。用"输出里含 error"来判失败会误报。
        """
        r = ShellResult("x", 0, "mLastErrorState=ERROR\n", "")
        self.assertIsNone(r.error_kind())
        self.assertTrue(r.ok)


if __name__ == "__main__":
    unittest.main(verbosity=2)
