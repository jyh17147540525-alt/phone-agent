# -*- coding: utf-8 -*-
"""
P0 验证执行器 · 结果模型与报告渲染

═══════════════════════════════════════════════════════════════
  为什么判定结果要用四态而不是布尔
═══════════════════════════════════════════════════════════════

"通过 / 不通过"看起来够用，但它会**系统性地把两件完全不同的事
混成一件**：

  · 测了，结果是坏的      → 需要改设计
  · 压根没测成            → 需要再跑一次

后者被塞进"不通过"里，会让项目在明明没拿到证据的时候，
以为已经拿到了反面证据。对 P0 这种"结论决定产品形态"的实验，
这个区别是致命的 —— 它可能导致一个可行的方案被误判为不可行。

所以用四态：

    PASS      拿到证据，且支持假设
    FAIL      拿到证据，且推翻假设
    BLOCKED   想测但被挡住了（权限不够、设备没连、前置未满足）
    UNKNOWN   没拿到证据（命令超时、输出格式不认识、用户没做那一步）

`BLOCKED` 与 `UNKNOWN` 的区分同样重要：前者要人去解决一个具体障碍，
后者要人重跑一次。合并成"未知"会让"设备没连上"这种小事
看起来像"实验结果不确定"这种大事。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field, asdict
from enum import Enum
from typing import Any, Optional


class Verdict(str, Enum):
    """单个检查项的判定。"""

    PASS = "PASS"
    FAIL = "FAIL"
    BLOCKED = "BLOCKED"
    UNKNOWN = "UNKNOWN"

    @property
    def symbol(self) -> str:
        return {
            Verdict.PASS: "通过",
            Verdict.FAIL: "不通过",
            Verdict.BLOCKED: "被阻塞",
            Verdict.UNKNOWN: "未测到",
        }[self]

    @property
    def is_conclusive(self) -> bool:
        """是否拿到了能支持决策的证据。"""
        return self in (Verdict.PASS, Verdict.FAIL)


@dataclass
class Evidence:
    """
    一条证据。

    ⚠️ **`raw` 是必填的。** 没有原始输出的判定等于没有判定 ——
       报告的使用者（未来的自己、协作者）必须能自己复核，
       而不是只能相信一个结论。
    """

    #: 人话描述这一条在测什么
    label: str
    #: 从原始输出里解析出来的值，None 表示没解析出来
    value: Any
    #: 命令的原始输出（可能被截断，见 truncate）
    raw: str

    def render(self) -> str:
        shown = "（未解析出）" if self.value is None else repr(self.value)
        return f"{self.label}: {shown}"


@dataclass
class Check:
    """一个 P0 实验里的单项检查。"""

    id: str
    title: str
    verdict: Verdict
    evidence: list[Evidence] = field(default_factory=list)
    #: 这个结论对项目意味着什么 —— 必须写，否则报告只是一堆数字
    consequence: str = ""
    #: 下一步该做什么（尤其是 FAIL / BLOCKED / UNKNOWN 时）
    next_step: str = ""
    #: 结论的适用限定（例如"这不是 K60，结论需重新验证"）
    caveat: str = ""


@dataclass
class ExperimentResult:
    """一个 P0 实验（P0-1 ~ P0-5）的完整结果。"""

    id: str
    title: str
    #: 这个实验"为什么是 P0" —— 从设计文档里抄下来，让报告自带上下文
    why: str
    checks: list[Check] = field(default_factory=list)
    #: 非致命的异常信息（命令失败、超时等），原样保留供排查
    errors: list[str] = field(default_factory=list)

    @property
    def verdict(self) -> Verdict:
        """
        整个实验的汇总判定。

        汇总规则（顺序即优先级）：
          1. 只要有一项 FAIL          → FAIL
          2. 否则只要有一项 BLOCKED   → BLOCKED
          3. 否则只要有一项 UNKNOWN   → UNKNOWN
          4. 否则（全部 PASS）        → PASS

        ⚠️ **FAIL 优先于 BLOCKED。** 直觉上"被挡住"更严重，但对本项目
           而言相反：FAIL 是**拿到了反面证据**，可以直接做设计决策；
           BLOCKED 只是还没拿到证据，设计决策还得悬着。
           报告的第一行要反映"现在能做什么决策"，而不是"有多难"。

        ⚠️ 空 checks → UNKNOWN，不是 PASS。一个什么都没测的实验
           **不是通过的实验**。
        """
        if not self.checks:
            return Verdict.UNKNOWN
        verdicts = {c.verdict for c in self.checks}
        if Verdict.FAIL in verdicts:
            return Verdict.FAIL
        if Verdict.BLOCKED in verdicts:
            return Verdict.BLOCKED
        if Verdict.UNKNOWN in verdicts:
            return Verdict.UNKNOWN
        return Verdict.PASS

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "title": self.title,
            "why": self.why,
            "verdict": self.verdict.value,
            "checks": [
                {
                    "id": c.id,
                    "title": c.title,
                    "verdict": c.verdict.value,
                    "evidence": [asdict(e) for e in c.evidence],
                    "consequence": c.consequence,
                    "next_step": c.next_step,
                    "caveat": c.caveat,
                }
                for c in self.checks
            ],
            "errors": self.errors,
        }


@dataclass
class Report:
    """一次完整运行的结果。"""

    device: dict[str, str] = field(default_factory=dict)
    started_at: str = ""
    finished_at: str = ""
    experiments: list[ExperimentResult] = field(default_factory=list)
    #: 本次运行没能执行的实验及原因
    skipped: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "device": self.device,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "experiments": [e.to_dict() for e in self.experiments],
            "skipped": self.skipped,
        }

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False, indent=2)

    def to_markdown(self) -> str:
        """
        渲染成人读的报告。

        刻意不用表格做主体：证据里的原始输出可能很长，塞进表格
        会被折行折到不可读。用标题 + 列表，让每条证据独立成块。
        """
        lines: list[str] = []
        lines.append("# P0 验证报告")
        lines.append("")

        if self.started_at:
            lines.append(f"- 开始：{self.started_at}")
        if self.finished_at:
            lines.append(f"- 结束：{self.finished_at}")

        if self.device:
            lines.append("")
            lines.append("## 设备")
            lines.append("")
            for key, value in self.device.items():
                lines.append(f"- {key}：{value}")

        lines.append("")
        lines.append("## 结论速览")
        lines.append("")
        if not self.experiments:
            lines.append("（没有执行任何实验）")
        for exp in self.experiments:
            lines.append(f"- **{exp.id}** {exp.title} → **{exp.verdict.symbol}**")

        if self.skipped:
            lines.append("")
            lines.append("## 未执行")
            lines.append("")
            for item in self.skipped:
                lines.append(f"- {item}")

        for exp in self.experiments:
            lines.append("")
            lines.append(f"## {exp.id} · {exp.title}")
            lines.append("")
            lines.append(f"**判定：{exp.verdict.symbol}**")
            lines.append("")
            lines.append(f"**为什么是 P0**：{exp.why}")
            lines.append("")

            for check in exp.checks:
                lines.append(f"### {check.id} {check.title}")
                lines.append("")
                lines.append(f"- 判定：**{check.verdict.symbol}**")
                for ev in check.evidence:
                    lines.append(f"- {ev.render()}")
                if check.caveat:
                    lines.append(f"- ⚠️ 限定：{check.caveat}")
                if check.consequence:
                    lines.append("")
                    lines.append(f"**影响**：{check.consequence}")
                if check.next_step:
                    lines.append("")
                    lines.append(f"**下一步**：{check.next_step}")
                lines.append("")

                # 原始输出单独折叠在最后，供复核
                for ev in check.evidence:
                    if ev.raw.strip():
                        lines.append(f"<details><summary>原始输出：{ev.label}</summary>")
                        lines.append("")
                        lines.append("```")
                        lines.append(ev.raw.rstrip())
                        lines.append("```")
                        lines.append("")
                        lines.append("</details>")
                        lines.append("")

            if exp.errors:
                lines.append("### 运行期异常")
                lines.append("")
                for err in exp.errors:
                    lines.append(f"- `{err}`")
                lines.append("")

        lines.append("---")
        lines.append("")
        lines.append(
            "本报告由 `tools/p0/run_p0.py` 生成。"
            "判定逻辑与解析逻辑均为纯函数，已由 `tools/p0/tests/` 离线覆盖；"
            "但**解析器面对的是真机输出**，首次在新型号/新 Android 版本上运行时，"
            "请人工复核原始输出与解析值是否一致。"
        )
        return "\n".join(lines)


def truncate(text: str, limit: int = 4000) -> str:
    """
    截断过长的原始输出。

    ⚠️ 截断时**保留头尾**而不是只留头部。这些输出的关键信息经常在
       末尾（`df` 的数据行、`dumpsys` 的汇总行），只留头部等于
       把最有用的部分丢掉。
    """
    if text is None:
        return ""
    if len(text) <= limit:
        return text

    head = limit // 2
    tail = limit - head
    omitted = len(text) - limit
    return (
        text[:head]
        + f"\n\n…（此处省略 {omitted} 字符）…\n\n"
        + text[-tail:]
    )
