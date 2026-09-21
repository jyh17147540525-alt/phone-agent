# -*- coding: utf-8 -*-
"""
P0 验证执行器 · 离线单测

═══════════════════════════════════════════════════════════════
  这些测试在证明什么，以及不证明什么
═══════════════════════════════════════════════════════════════

**证明**：给定 fixture 里的输入，解析函数和判定逻辑给出的结果是我们想要的。
**不证明**：真实设备的输出就是 fixture 里那样（见 fixtures/README.md）。

所以每个用例的 docstring 都写清楚"这条防的是什么 bug"。
一个不说清防护目标的测试，改坏的时候没人知道该不该修。

跑法：

    cd tools/p0 && python -m unittest discover -s tests -v
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT))

from p0kit import parse  # noqa: E402

FIXTURES = _ROOT / "fixtures"


def fixture(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


# ═══════════════════════════════════════════════════════════════
#  受限设置（P0-2 的核心）
# ═══════════════════════════════════════════════════════════════

class TestRestrictedSettings(unittest.TestCase):

    def test_allow(self):
        state = parse.parse_restricted_settings(fixture("appops_allow.txt"))
        self.assertIsNotNone(state)
        self.assertEqual(state.mode, "allow")
        self.assertTrue(state.is_allowed)
        self.assertFalse(state.is_blocked)

    def test_deny(self):
        """deny 是侧载安装后的预期初值，也是本实验最想看到的那个信号。"""
        state = parse.parse_restricted_settings(fixture("appops_deny.txt"))
        self.assertEqual(state.mode, "deny")
        self.assertTrue(state.is_blocked)
        self.assertTrue(state.needs_user_action)

    def test_ignore_is_not_allowed(self):
        """
        ⚠️ 这条是整个模块里最容易写错的地方。

        `ignore` 只表示"用户看过那个弹窗了"，**无障碍开关仍然是灰的**。
        把它当成允许，报告会告诉用户"已经可以了"，然后用户去开开关发现开不了 ——
        于是他会怀疑是自己操作错了，再试一遍。一个说错话的报告比没有报告更糟。
        """
        state = parse.parse_restricted_settings(fixture("appops_ignore.txt"))
        self.assertEqual(state.mode, "ignore")
        self.assertFalse(state.is_allowed, "ignore 不能算允许")
        self.assertTrue(state.needs_user_action)

    def test_history_line_does_not_override_current(self):
        """
        带 `--history` 风格的输出里，历史行同样含 `deny`。

        如果正则用了 `findall` 或者贪婪匹配，历史行可能覆盖当前值。
        这里当前值是 deny、历史里也有 deny，所以**要故意构造一个
        "当前与历史不同"的情形**才测得出来 —— 见下一个用例。
        """
        state = parse.parse_restricted_settings(fixture("appops_deny_with_history.txt"))
        self.assertEqual(state.mode, "deny")

    def test_first_match_wins_when_history_differs(self):
        """当前是 allow、历史里是 deny —— 必须取 allow（第一处）。"""
        text = (
            "ACCESS_RESTRICTED_SETTINGS: allow\n"
            "\tdeny; time=+1d ago; duration=+1ms\n"
        )
        state = parse.parse_restricted_settings(text)
        self.assertEqual(state.mode, "allow")

    def test_mode_prefix_variant(self):
        """部分 Android 版本输出 `mode=allow` 而不是裸 `allow`。"""
        state = parse.parse_restricted_settings(fixture("appops_mode_prefix.txt"))
        self.assertEqual(state.mode, "allow")

    def test_empty_output_is_none(self):
        """
        空输出必须返回 None。

        场景：应用没装，或该 Android 版本没有这个 appop。
        返回一个默认值（比如 "allow"）会让报告显示"不受限"，
        而实际上**我们什么都没测到**。
        """
        self.assertIsNone(parse.parse_restricted_settings(fixture("appops_empty.txt")))
        self.assertIsNone(parse.parse_restricted_settings(""))
        self.assertIsNone(parse.parse_restricted_settings(None))

    def test_unknown_mode_is_none(self):
        """
        出现没见过的模式时返回 None，而不是原样返回那个字符串。

        原因：调用方是按 allow/deny/ignore 三分支处理的。原样返回
        会让它掉进 else 分支 —— 那通常被写成"其余情况视为允许"，
        于是 Android 将来新增一个模式，我们就会误报"不受限"。
        """
        self.assertIsNone(parse.parse_restricted_settings(fixture("appops_unknown_mode.txt")))


# ═══════════════════════════════════════════════════════════════
#  无障碍服务列表（P0-2）
# ═══════════════════════════════════════════════════════════════

class TestAccessibilityServices(unittest.TestCase):

    def test_multi_service_split(self):
        services = parse.parse_enabled_accessibility_services(fixture("settings_a11y_multi.txt"))
        self.assertEqual(len(services), 2)

    def test_null_is_empty_not_a_service_named_null(self):
        """
        ⚠️ 未设置时 `settings get` 输出的是字符串 `null`，不是空串。

        直接 split(':') 会得到 {'null'} —— 于是"没有任何服务"
        变成了"有一个叫 null 的服务"，而所有 `in` 判断都会照常工作，
        这个 bug 能藏很久。
        """
        services = parse.parse_enabled_accessibility_services(fixture("settings_a11y_null.txt"))
        self.assertEqual(services, set())

    def test_short_form_matches_full_form(self):
        """
        短形式 `pkg/.Svc` 与全形式 `pkg/pkg.Svc` 是同一个服务。

        不做归一化的话，"服务明明开着但工具说没开"这种 bug
        只会在特定 ROM 上出现 —— 因为存成哪种形式取决于当初是谁写的。
        """
        services = parse.parse_enabled_accessibility_services(fixture("settings_a11y_short_form.txt"))
        self.assertTrue(parse.is_service_enabled(
            services, "com.pocketagent/com.pocketagent.assistant.AgentAccessibilityService"
        ))

    def test_normalize(self):
        self.assertEqual(
            parse.normalize_service_name("com.foo/.Svc"), "com.foo/com.foo.Svc")
        self.assertEqual(
            parse.normalize_service_name("com.foo/com.foo.Svc"), "com.foo/com.foo.Svc")
        # 没有斜杠时无法补全，原样返回 —— 不猜
        self.assertEqual(parse.normalize_service_name("com.foo"), "com.foo")

    def test_not_enabled(self):
        services = parse.parse_enabled_accessibility_services("com.other/.Svc")
        self.assertFalse(parse.is_service_enabled(services, "com.pocketagent/.X"))


# ═══════════════════════════════════════════════════════════════
#  显示器（P0-3）
# ═══════════════════════════════════════════════════════════════

class TestDisplay(unittest.TestCase):

    def test_no_overlay_only_primary(self):
        ids = parse.parse_display_ids(fixture("display_no_overlay.txt"))
        self.assertEqual(ids, {0})

    def test_with_overlay(self):
        text = fixture("display_with_overlay.txt")
        ids = parse.parse_display_ids(text)
        self.assertIn(0, ids)
        self.assertIn(2, ids)
        self.assertEqual(parse.parse_overlay_ids(text), {2})

    def test_overlay_ids_empty_when_none(self):
        self.assertEqual(parse.parse_overlay_ids(fixture("display_no_overlay.txt")), set())

    def test_virtual_display_is_not_overlay(self):
        """
        ⚠️ 这条防的是 EX-16 的**假通过**。

        `dumpsys display` 里还会有 `uniqueId="virtual:..."` 的设备
        （投屏、录屏、某些 App 自建的虚拟屏）。如果判据只看
        "有没有多出 displayId"，一次投屏就能让实验显示通过。
        所以 overlay 的判定必须认 `uniqueId="overlay:N"` 这个固定命名。
        """
        text = (
            'DisplayDeviceInfo{"Built-in Screen": uniqueId="local:123", 1080 x 2400, type INTERNAL}\n'
            'DisplayDeviceInfo{"Screen recording": uniqueId="virtual:com.android.screenrecord", '
            '1080 x 2400, type VIRTUAL}\n'
            "  mDisplayId=0\n"
            "  mDisplayId=3\n"
        )
        self.assertEqual(parse.parse_display_ids(text), {0, 3})
        self.assertEqual(parse.parse_overlay_ids(text), set(),
                         "virtual display 不能被当成 overlay")

    def test_overlay_setting_normalization(self):
        """
        `null` 与空串必须归一化成同一个值。

        否则"记录原值 → 恢复"这段代码会在原值是 `null` 时写回一个空串，
        留下一个用户看不出、我们却以为改过的差异 —— 下次的原值就不可信了。
        """
        self.assertEqual(parse.parse_overlay_display_setting("null\n"), "")
        self.assertEqual(parse.parse_overlay_display_setting(""), "")
        self.assertEqual(parse.parse_overlay_display_setting("  null  "), "")

    def test_overlay_setting_value(self):
        self.assertEqual(
            parse.parse_overlay_display_setting("1920x1080/240\n"), "1920x1080/240")
        self.assertEqual(parse.overlay_display_count("1920x1080/240"), 1)
        self.assertEqual(parse.overlay_display_count("1920x1080/240;1280x720/160"), 2)
        self.assertEqual(parse.overlay_display_count(""), 0)


# ═══════════════════════════════════════════════════════════════
#  内存（P0-4）
# ═══════════════════════════════════════════════════════════════

class TestMeminfo(unittest.TestCase):

    def test_android12_label_form(self):
        self.assertEqual(
            parse.parse_meminfo_total_pss(fixture("meminfo_android12plus.txt")), 45678)

    def test_android11_table_form(self):
        """
        Android 11 及更早是表格式，TOTAL 行的第一列就是 Pss Total。

        ⚠️ 注意 fixture 里表头有 `Total    Dirty    Clean` 这样一行，
           它**不以 TOTAL 开头**（是 `Total` 首字母大写），
           所以不会被误匹配。若正则用了 IGNORECASE，就会匹配到表头行，
           得到一个荒谬的数字。这条用例就是钉住这一点。
        """
        self.assertEqual(
            parse.parse_meminfo_total_pss(fixture("meminfo_android11.txt")), 45678)

    def test_missing_total_returns_none_not_zero(self):
        """
        ⚠️ **这是本模块最重要的一条。**

        取不到 TOTAL 时返回 0 会在报告里显示"内存占用 0 KB，远低于预算" ——
        那不是解析失败，那是一个**会让项目做出错误决策的假数据**。
        None 会逼着人去查，0 不会。
        """
        self.assertIsNone(parse.parse_meminfo_total_pss(fixture("meminfo_no_total.txt")))
        self.assertIsNone(parse.parse_meminfo_total_pss(""))

    def test_table_header_alone_does_not_match(self):
        """只有表头没有数据行时，不能从表头里抠出一个数字。"""
        header_only = (
            "                   Pss  Private  Private  SwapPss      Rss\n"
            "                 Total    Dirty    Clean    Dirty    Total\n"
            "                ------   ------   ------   ------   ------\n"
        )
        self.assertIsNone(parse.parse_meminfo_total_pss(header_only))


# ═══════════════════════════════════════════════════════════════
#  能耗（P0-5）
# ═══════════════════════════════════════════════════════════════

class TestBattery(unittest.TestCase):

    def test_multi_uid_sums(self):
        """
        ⚠️ 多条 Uid 行必须**求和**，不是取第一条。

        应用和它拉起的子进程（我们的 Node 就是另一个进程）可能共用 uid。
        只取第一条会系统性低估 —— 而低估的方向是"能耗看起来很宽裕"，
        正是最危险的那种错误方向。

        fixture 里 u0a312 出现两次（12.345 + 3.210），u0a105 一次（0.500），
        合计 16.055。注意 u0a105 是**别的应用**的 uid，
        它只是因为 `dumpsys batterystats <pkg>` 会带上相关进程才出现。
        求和口径是"这个包里所有 Uid 行"，与 dumpsys 的过滤行为一致。
        """
        power = parse.parse_batterystats_uid_power(fixture("batterystats_multi_uid.txt"))
        self.assertAlmostEqual(power, 16.055, places=3)

    def test_no_power_section_returns_none(self):
        self.assertIsNone(
            parse.parse_batterystats_uid_power(fixture("batterystats_no_power.txt")))
        self.assertIsNone(parse.parse_batterystats_uid_power(""))

    def test_charge_counter_available(self):
        self.assertEqual(
            parse.parse_charge_counter(fixture("battery_with_counter.txt")), 4350000)
        self.assertEqual(parse.parse_battery_level(fixture("battery_with_counter.txt")), 87)

    def test_charge_counter_zero_means_unsupported(self):
        """
        ⚠️ `Charge counter: 0` 表示**驱动不支持**，等同于取不到。

        如果原样返回 0，报告会显示"充放电计数 0 µAh"，然后有人拿它做差
        得到 0 —— 结论是"任务不耗电"。这是库仑计缺失设备上最容易犯的错。
        """
        self.assertIsNone(parse.parse_charge_counter(fixture("battery_no_counter.txt")))
        # 电量百分比仍然要能读到 —— 缺库仑计不影响它
        self.assertEqual(parse.parse_battery_level(fixture("battery_no_counter.txt")), 87)


# ═══════════════════════════════════════════════════════════════
#  Node（P0-1）
# ═══════════════════════════════════════════════════════════════

class TestNode(unittest.TestCase):

    def test_version_parse(self):
        self.assertEqual(parse.parse_node_version("v24.18.0\n"), (24, 18, 0))
        self.assertEqual(parse.parse_node_version("v20.0.0"), (20, 0, 0))

    def test_version_in_noisy_output(self):
        """
        用户很可能把整段终端输出贴过来，而不是只贴 `node -v` 那一行。
        全文搜索而不是严格匹配，就是为了这个。
        """
        noisy = (
            "~ $ node -v\n"
            "v24.18.0\n"
            "~ $ npm -v\n"
            "10.8.2\n"
        )
        self.assertEqual(parse.parse_node_version(noisy), (24, 18, 0))

    def test_version_none_when_absent(self):
        """
        返回 None 表示"没找到版本号"，**不等于"Node 没装"**。
        调用方必须区分"没测到"和"测了且不合格"。
        """
        self.assertIsNone(parse.parse_node_version(""))
        self.assertIsNone(parse.parse_node_version("bash: node: command not found"))

    def test_min_version_boundary(self):
        """
        下限是 20，依据是 dsh 用了 `toReversed()`。
        19 不算通过，20 才算 —— 边界必须钉死，否则将来有人"顺手放宽"。
        """
        self.assertFalse(parse.node_version_ok((18, 20, 4)))
        self.assertFalse(parse.node_version_ok((19, 9, 9)))
        self.assertTrue(parse.node_version_ok((20, 0, 0)))
        self.assertTrue(parse.node_version_ok((24, 18, 0)))
        self.assertFalse(parse.node_version_ok(None))

    def test_report_sections(self):
        from p0kit.experiments import parse_node_report
        sections = parse_node_report(fixture("node_report_ok.txt"))
        self.assertEqual(sections["node_version"].strip(), "v24.18.0")
        self.assertEqual(sections["to_reversed"].strip(), "[3,2,1]")
        self.assertEqual(sections["abi"].strip(), "arm64")

    def test_report_multiline_section(self):
        """
        dsh 的输出是多行的，必须整段归到 dsh 段里，而不是按行配对。
        """
        from p0kit.experiments import parse_node_report
        sections = parse_node_report(fixture("node_report_dsh_failed.txt"))
        self.assertIn("Cannot find module", sections["dsh"])
        self.assertIn("loader:1145", sections["dsh"])

    def test_low_version_report(self):
        from p0kit.experiments import parse_node_report
        sections = parse_node_report(fixture("node_report_low_version.txt"))
        self.assertEqual(parse.parse_node_version(sections["node_version"]), (18, 20, 4))
        self.assertFalse(parse.node_version_ok(
            parse.parse_node_version(sections["node_version"])))


# ═══════════════════════════════════════════════════════════════
#  设备身份与存储
# ═══════════════════════════════════════════════════════════════

class TestDevice(unittest.TestCase):

    def test_getprop(self):
        props = parse.parse_getprop(fixture("getprop_k60.txt"))
        self.assertEqual(props["ro.product.model"], "23013RK75C")
        self.assertEqual(props["ro.product.cpu.abi"], "arm64-v8a")

    def test_target_match(self):
        self.assertTrue(parse.device_matches_target(parse.parse_getprop(fixture("getprop_k60.txt"))))
        self.assertFalse(parse.device_matches_target(
            {"ro.product.model": "Pixel 8", "ro.product.marketname": ""}))

    def test_df_standard(self):
        self.assertEqual(parse.parse_free_storage_kb(fixture("df_data.txt")), 20000000)

    def test_df_column_position_varies(self):
        """
        ⚠️ 按表头找列号，不要按固定列数取。

        两个 fixture 的表头不同（`1K-blocks` vs `1024-blocks`，
        `Use%` vs `Capacity`），但可用空间都在 `Available` 列。
        固定取第 4 列的话，第二个 fixture 会取到 `82%` 然后抛异常。
        """
        self.assertEqual(parse.parse_free_storage_kb(fixture("df_with_extra_column.txt")), 20000000)

    def test_df_garbage(self):
        self.assertIsNone(parse.parse_free_storage_kb(""))
        self.assertIsNone(parse.parse_free_storage_kb("Filesystem\n"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
