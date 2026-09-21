# -*- coding: utf-8 -*-
"""
P0 验证执行器 · 纯解析层

═══════════════════════════════════════════════════════════════
  为什么把解析单独拆出来
═══════════════════════════════════════════════════════════════

本工具的**全部价值**在于"在没有手机的时候也能确认自己没有写错"。
真机只有一个，而且每次实验都要手动操作几分钟 —— 如果解析逻辑
只能在真机上验证，那么每改一行代码就要占用一次真机时间，
最后一定是"跑一次能出数就算通过"，而错误会藏在没人看的地方。

所以这里有一条硬约束：

    **本模块不允许 import subprocess，不允许读写文件，
      不允许访问网络。只有纯函数：字符串进，结构出。**

抓取输出的事在 `adb.py`，判定的编排在 `experiments.py`。
这样解析层就能用 fixture 在毫秒级跑完全部边界情况。

═══════════════════════════════════════════════════════════════
  一个贯穿全篇的立场：**宁可返回 None，也不要猜**
═══════════════════════════════════════════════════════════════

所有解析函数在"看不懂"时一律返回 None，绝不返回一个默认值。
原因是这些返回值会直接进入"这个实验通过了吗"的判定 ——
一个猜出来的默认值会**伪装成一次成功的测量**。

最典型的例子是 `parse_meminfo_total_pss`：如果某版 Android 改了
输出格式，而我们返回 0，报告上会显示"内存占用 0 KB，远低于预算"。
那不是解析失败，那是**一个会让项目做出错误决策的假数据**。

代价是调用方必须处理 None。这是对的：None 会逼着人去查，
0 不会。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Optional


# ═══════════════════════════════════════════════════════════════
#  P0-2 · 受限设置（ACCESS_RESTRICTED_SETTINGS）
# ═══════════════════════════════════════════════════════════════

#: 受限设置的三种模式。语义来自 Android 13 引入的 appop 机制：
#:
#:   deny    —— 系统主动拦截。无障碍开关是灰的，点它会弹
#:              "为安全起见，此设置当前不可用"。**用户自己关不掉**，
#:              必须走"允许受限设置"（App 信息页右上角三点）或 appops 命令。
#:   ignore  —— 用户已经看过那个弹窗但还没授权。**此时无障碍开关依然是灰的**，
#:              但"允许受限设置"入口已经出现。这个状态很阴险：它看起来
#:              像"用户没操作"，实际上用户已经操作了一半。
#:   allow   —— 放行。等价于从应用商店安装的应用。
#:
#: 还有第四个可能值 `default`，表示这个 appop 从未被显式设置过。
#: 它**不等于 allow** —— 对侧载应用，系统在安装时就会把它置为 deny，
#: 所以读到 default 通常意味着"这台设备/这个版本没有实施该限制"。
#: 我们把它单独建模，不合并进 allow，因为两者的证据强度不同。
RESTRICTED_SETTINGS_MODES = ("allow", "ignore", "deny", "default")


@dataclass(frozen=True)
class RestrictedSettingsState:
    """`cmd appops get <pkg> ACCESS_RESTRICTED_SETTINGS` 的解析结果。"""

    mode: str
    #: 原始输出，报告里要原样展示 —— 判定依据必须可复核
    raw: str

    @property
    def is_allowed(self) -> bool:
        """
        无障碍开关**现在**能不能被用户打开。

        ⚠️ `ignore` 不算允许。它只表示"弹窗看过了"，
           开关仍然是灰的。把它当成允许是本模块最容易犯的错。
        """
        return self.mode in ("allow", "default")

    @property
    def is_blocked(self) -> bool:
        """被系统主动拦截 —— 需要走"允许受限设置"流程。"""
        return self.mode == "deny"

    @property
    def needs_user_action(self) -> bool:
        """用户还需要做点什么才能打开无障碍开关。"""
        return not self.is_allowed


_APPOP_MODE_RE = re.compile(
    r"ACCESS_RESTRICTED_SETTINGS\s*:\s*(?:mode\s*=\s*)?([A-Za-z_]+)"
)


def parse_restricted_settings(output: str) -> Optional[RestrictedSettingsState]:
    """
    解析 `cmd appops get <pkg> ACCESS_RESTRICTED_SETTINGS` 的输出。

    实测过的三种写法（不同 Android 版本 / 不同厂商）：
        ACCESS_RESTRICTED_SETTINGS: allow
        ACCESS_RESTRICTED_SETTINGS: mode=allow
        ACCESS_RESTRICTED_SETTINGS: deny
            deny; time=+1d2h3m4s5ms ago; duration=+123ms

    所以正则允许 `mode=` 前缀，且只取第一个匹配 ——
    第二行的历史记录里还会有一次 `deny`，不能让它覆盖当前值。

    返回 None 的情况：
      - 输出为空（应用没装，或者 appops 不认识这个 op）
      - 冒号后不是已知的四种模式之一

    ⚠️ **"应用没装"与"这个 Android 版本没有该 appop"必须由调用方区分。**
       本函数无法区分，因为两者都是空输出。调用方应先确认包已安装。
    """
    if not output or not output.strip():
        return None

    match = _APPOP_MODE_RE.search(output)
    if match is None:
        return None

    mode = match.group(1).strip().lower()
    if mode not in RESTRICTED_SETTINGS_MODES:
        return None

    return RestrictedSettingsState(mode=mode, raw=output.strip())


# ═══════════════════════════════════════════════════════════════
#  P0-2 · 已启用的无障碍服务
# ═══════════════════════════════════════════════════════════════

def parse_enabled_accessibility_services(output: str) -> set[str]:
    """
    解析 `settings get secure enabled_accessibility_services`。

    输出格式是**冒号分隔**的服务名列表：
        com.foo/.A11yService:com.bar/com.bar.Svc

    ⚠️ 三个必须处理的边界：

    1. 未设置时输出的是字符串 `null`，**不是空串**。直接 split(':')
       会得到 {'null'}，于是"没有任何服务"会被误判成"有一个叫 null 的服务"。
    2. 服务名本身可能被写成短形式 `com.foo/.Svc` 或全形式 `com.foo/com.foo.Svc`。
       两者指的是同一个服务，比较时必须归一化 —— 否则"我们检查的服务已启用"
       这个判定会因为写法不同而失败。
    3. 部分 ROM 在服务名后带参数，形如 `com.foo/.Svc:0`。冒号既作分隔符
       又作参数前缀，无法用 split 区分。**这是已知的歧义，本函数不做猜测** ——
       返回的集合里会同时出现 `com.foo/.Svc` 和 `0`，调用方用
       `is_service_enabled` 做归一化匹配即可，不会误判。
    """
    if not output:
        return set()

    text = output.strip()
    if text == "" or text.lower() == "null":
        return set()

    return {part.strip() for part in text.split(":") if part.strip()}


def normalize_service_name(name: str) -> str:
    """
    把无障碍服务名归一化成 `包名/类全名` 的形式。

        com.foo/.A11yService          → com.foo/com.foo.A11yService
        com.foo/com.foo.A11yService   → com.foo/com.foo.A11yService
        com.foo                       → com.foo            （无法补全，原样返回）

    为什么要归一化：`settings get` 里存的是什么形式，取决于当初是谁写的 ——
    系统设置界面写全名，某些 ROM 写短名。我们自己的引导代码也可能写。
    如果比较时不做归一化，"服务明明开着但程序说没开"这种 bug 会非常难查，
    因为它只在特定 ROM 上出现。
    """
    if "/" not in name:
        return name

    pkg, _, cls = name.partition("/")
    pkg = pkg.strip()
    cls = cls.strip()

    if cls.startswith("."):
        return f"{pkg}/{pkg}{cls}"

    # 已经是全名，或者类名在其他包下（罕见但合法），原样返回
    return f"{pkg}/{cls}"


def is_service_enabled(services: set[str], target: str) -> bool:
    """
    判断 `target` 是否在已启用集合里，按归一化后的名字比较。

    这是唯一应该被用来做判定的入口 —— 不要自己拿集合做 `in` 判断。
    """
    wanted = normalize_service_name(target)
    return any(normalize_service_name(s) == wanted for s in services)


# ═══════════════════════════════════════════════════════════════
#  P0-3 · 显示器与虚拟屏
# ═══════════════════════════════════════════════════════════════

#: 主屏的 displayId 恒为 0，这是 Android 的约定，不会变。
PRIMARY_DISPLAY_ID = 0


def parse_display_ids(output: str) -> set[int]:
    """
    从 `dumpsys display` 的输出里抓出所有 displayId。

    实测存在两种写法，必须都覆盖：

        mDisplayId=0
        DisplayInfo{"Built-in Screen", displayId 0, ...}

    为什么两种都要抓：`dumpsys display` 的输出在不同 Android 版本间
    变动很大，而且**同一个版本里也可能两种都出现**（DisplayManager 的
    内部状态和 DisplayInfo 是两段不同的输出）。只抓一种的话，
    漏掉的那个会让"虚拟屏创建成功了吗"这个问题得到错误答案。

    返回值是集合而非列表 —— 调用方关心的是"有没有出现新的 id"，
    不关心顺序，而且重复出现是正常的（同一个屏在多段输出里各出现一次）。
    """
    if not output:
        return set()

    ids: set[int] = set()

    for match in re.finditer(r"mDisplayId\s*=\s*(\d+)", output):
        ids.add(int(match.group(1)))

    for match in re.finditer(r"\bdisplayId[= ]+(\d+)", output):
        ids.add(int(match.group(1)))

    return ids


_OVERLAY_UNIQUE_ID_RE = re.compile(r'uniqueId\s*=\s*"overlay:(\d+)"')


def parse_overlay_ids(output: str) -> set[int]:
    """
    从 `dumpsys display` 里抓出**确认是 overlay 类型**的 displayId。

    为什么要在 `parse_display_ids` 之外再做一个：那个函数回答的是
    "有没有多出一个显示设备"，而这个回答的是"多出来的那个是不是
    我们创建的副屏"。

    差别在真实场景里是实质性的：`dumpsys display` 里还会出现
    `virtual:` 开头的设备（投屏、录屏、某些 App 自己建的虚拟屏）。
    如果只判断"多了个 id"，一次投屏就能让 EX-16 得到假的通过。

    判据用 `uniqueId="overlay:N"` —— 这是 Android 给通过
    `overlay_display_devices` 创建出来的设备分配的固定命名，
    与我们写入的设置一一对应。
    """
    if not output:
        return set()
    return {int(m.group(1)) for m in _OVERLAY_UNIQUE_ID_RE.finditer(output)}


def parse_overlay_display_setting(output: str) -> str:
    """
    解析 `settings get global overlay_display_devices`。

    这个设置就是开发者选项里的「模拟辅助显示设备」。值形如：

        null                      未设置（关）
        ""                        显式置空（也是关）
        1920x1080/240             一个副屏
        1920x1080/240;1280x720/160 两个副屏，分号分隔

    ⚠️ **`null` 与空串必须归一化成同一个值**，否则"测试前记录原值 →
    测试后恢复"这段代码会在原值是 `null` 时把设备设置成空串。
    两者对系统而言等价，但会留下一个**用户看不出差别、我们却以为改过**的
    差异 —— 下一次实验时原值记录就不可信了。

    返回规范化后的字符串，空表示"关闭"。
    """
    if output is None:
        return ""

    text = output.strip()
    if text.lower() == "null":
        return ""
    # settings 命令会把空值输出成 "null"；但某些版本输出成字面空串
    return text


def overlay_display_count(value: str) -> int:
    """副屏数量。空值 → 0。"""
    if not value:
        return 0
    return len([p for p in value.split(";") if p.strip()])


# ═══════════════════════════════════════════════════════════════
#  P0-4 · 内存
# ═══════════════════════════════════════════════════════════════

_TOTAL_ROW_RE = re.compile(r"^\s*TOTAL\s+(\d+)", re.MULTILINE)
_TOTAL_PSS_RE = re.compile(r"TOTAL\s+PSS:\s*(\d+)")


def parse_meminfo_total_pss(output: str) -> Optional[int]:
    """
    从 `dumpsys meminfo <pkg>` 里取 TOTAL PSS，单位 KB。

    两种写法都要覆盖，因为 Android 12 前后改了格式：

        Android 11 及更早（表格式）
            TOTAL    45678    40000      500      100    50000
                      ↑ Pss Total 是第一列

        Android 12+（标签式）
            TOTAL PSS: 45678    TOTAL RSS: 50000

    ⚠️ 表格式那行**必须以 TOTAL 开头且后面紧跟数字**。用 `startswith('TOTAL')`
       匹配会撞上表头里的 `TOTAL` 列名（"Pss Total" 被拆成两行输出时，
       第二行是 `Total    Dirty    Clean ...`），得到一个荒谬的大数字。

    ⚠️ **取不到就返回 None，绝不返回 0。** 见模块头的说明 ——
       0 会在报告里显示成"内存占用极低"，那是一个会误导决策的假数据。
    """
    if not output:
        return None

    match = _TOTAL_PSS_RE.search(output)
    if match is not None:
        return int(match.group(1))

    match = _TOTAL_ROW_RE.search(output)
    if match is not None:
        return int(match.group(1))

    return None


# ═══════════════════════════════════════════════════════════════
#  P0-5 · 能耗
# ═══════════════════════════════════════════════════════════════

_UID_POWER_RE = re.compile(r"^\s*Uid\s+u0a(\d+)\s*:\s*([\d.]+)", re.MULTILINE)
_BATTERY_LEVEL_RE = re.compile(r"^\s*level:\s*(\d+)", re.MULTILINE)
_CHARGE_COUNTER_RE = re.compile(r"^\s*Charge counter:\s*(\d+)", re.MULTILINE)


def parse_batterystats_uid_power(output: str) -> Optional[float]:
    """
    从 `dumpsys batterystats <pkg>` 里取该应用的估算功耗，单位 mAh。

    输出形如：

        Estimated power use (mAh):
            Capacity: 5000, Computed drain: 1234, ...
            Uid u0a123: 12.3 ( cpu=8.1 sensor=0.2 ... )

    ⚠️ 这里**可能有多行 Uid**（应用进程 + 它拉起的子进程，比如我们的
       Node 就是另一个进程、但可能共用 uid）。所以取的是**求和**，
       不是第一行。只取第一行会系统性低估 —— 而低估的后果是
       "能耗预算看起来很宽裕"，正是最危险的那种错误方向。

    ⚠️ 这是**系统的估算值**，不是测量值。它的模型对"屏幕亮着"的权重很高，
       对"CPU 短时高负载"的权重偏低。agent 任务恰好是后者。
       所以本函数的结果只能当**数量级参考**，报告里必须标注这一点。
    """
    if not output:
        return None

    values = [float(m.group(2)) for m in _UID_POWER_RE.finditer(output)]
    if not values:
        return None

    return round(sum(values), 3)


def parse_battery_level(output: str) -> Optional[int]:
    """`dumpsys battery` 的电量百分比。"""
    if not output:
        return None
    match = _BATTERY_LEVEL_RE.search(output)
    return int(match.group(1)) if match else None


def parse_charge_counter(output: str) -> Optional[int]:
    """
    `dumpsys battery` 的 `Charge counter:`，单位 µAh。

    ⚠️ **这个值在很多设备上是不可用的**：它要求电池驱动上报库仑计，
       而相当一部分机型直接返回 0 或不输出该行。返回 None 时不要
       反复重试 —— 换用 `dumpsys batterystats` 的估算值。

    ⚠️ 即便可用，它也有 **±1% 的量化误差**。5000mAh 电池上就是 ±50mAh，
       而我们要测的单次任务耗电大约在这个量级。**所以它只适合测长任务，
       不适合测单次 8 轮任务。** 这一条必须写进报告，否则一定会有人
       拿它测 5 分钟然后得出"任务不耗电"的结论。
    """
    if not output:
        return None
    match = _CHARGE_COUNTER_RE.search(output)
    if match is None:
        return None
    value = int(match.group(1))
    # 0 表示驱动不支持，等同于取不到
    return value if value > 0 else None


# ═══════════════════════════════════════════════════════════════
#  P0-1 · Node 运行时
# ═══════════════════════════════════════════════════════════════

#: 项目对 Node 的真实下限。**不是 18，也不是随便一个 20。**
#:
#: 依据来自研讨文档的实测：dsh 的核心包里用了 `toReversed()`，
#: 该方法在 Node 20 才进入稳定版。所以 18 一定不行。
#: 而 nodejs-mobile 封顶 18.20.4 且已 EOL —— 这是"不能用它"的硬理由。
MIN_NODE_MAJOR = 20

_NODE_VERSION_RE = re.compile(r"\bv?(\d+)\.(\d+)\.(\d+)\b")


def parse_node_version(output: str) -> Optional[tuple[int, int, int]]:
    """
    从 `node -v` 的输出里取版本号。

    正常输出是 `v24.18.0`。但我们要的是**能承受噪音**：
    用户很可能把整段终端输出贴过来（包括提示符、报错、`npm -v` 的结果），
    所以这里做全文搜索而不是 `strip()` 后严格匹配。

    ⚠️ 返回 None 表示"没找到版本号"，**不等于"Node 没装"**。
       用户可能只是没跑那条命令，或者粘贴时漏了。
       调用方必须区分这两种情况 —— 把"没测"报成"失败"会让人白折腾一遍。
    """
    if not output:
        return None

    match = _NODE_VERSION_RE.search(output)
    if match is None:
        return None

    return (int(match.group(1)), int(match.group(2)), int(match.group(3)))


def node_version_ok(version: Optional[tuple[int, int, int]]) -> bool:
    """是否满足项目的 Node 下限。None（没测到）不算通过。"""
    if version is None:
        return False
    return version[0] >= MIN_NODE_MAJOR


# ═══════════════════════════════════════════════════════════════
#  设备身份
# ═══════════════════════════════════════════════════════════════

def parse_getprop(output: str) -> dict[str, str]:
    """
    解析 `getprop` 的输出。

    格式是每行 `[key]: [value]`。方括号是字面量，不是正则元字符。
    """
    result: dict[str, str] = {}
    for line in (output or "").splitlines():
        match = re.match(r"^\[([^\]]+)\]:\s*\[(.*)\]$", line.strip())
        if match:
            result[match.group(1)] = match.group(2)
    return result


#: 项目首要适配机型。判定"设备对不对"时用 —— 在别的机器上跑出来的
#: 结论不能直接套用到 K60，必须让报告自己说清楚这一点。
EXPECTED_MODEL_HINTS = ("K60", "23013RK75C", "Redmi K60")

#: 骁龙 8+ Gen 1 对应的 ABI。
EXPECTED_ABI = "arm64-v8a"


def device_matches_target(props: dict[str, str]) -> bool:
    """
    这台设备是不是首要适配机型（红米 K60）。

    ⚠️ 不匹配**不代表实验无效**，只代表结论的适用范围要写清楚。
       报告里会据此加一条限定语，而不是拒绝出结论。
    """
    haystack = " ".join(
        props.get(k, "")
        for k in ("ro.product.model", "ro.product.marketname", "ro.product.device", "ro.product.name")
    )
    return any(hint.lower() in haystack.lower() for hint in EXPECTED_MODEL_HINTS)


def parse_free_storage_kb(output: str) -> Optional[int]:
    """
    解析 `df -k <path>` 的可用空间，单位 KB。

    `df` 的输出有表头，数据行形如：

        Filesystem      1K-blocks    Used Available Use% Mounted on
        /dev/block/dm-5  113246208 90000000  20000000  82% /data

    ⚠️ **按表头定位列号，不要按固定列数取。** 不同 Android 版本的
       `df` 输出列数不同（有的多一列 `1K-blocks` 别名），
       固定取第 4 列会取到 `Use%`，然后 int() 抛异常。

    ⚠️ Android 上 `df` 的可用空间**不等于可写空间**：还要看 inode
       和配额。这里只用来做"够不够放下 40MB 的 Node"的量级判断。
    """
    if not output:
        return None

    lines = [ln for ln in output.splitlines() if ln.strip()]
    if len(lines) < 2:
        return None

    header = lines[0].split()
    try:
        avail_idx = next(
            i for i, col in enumerate(header)
            if col.lower() in ("available", "avail")
        )
    except StopIteration:
        return None

    # 从最后一行往前找第一条能解析的数据行 —— `df` 有时会在末尾追加
    # 挂载失败的行，那些行没有数字
    for line in reversed(lines[1:]):
        cols = line.split()
        if len(cols) <= avail_idx:
            continue
        try:
            return int(cols[avail_idx])
        except ValueError:
            continue

    return None
