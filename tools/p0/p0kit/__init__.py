# -*- coding: utf-8 -*-
"""
P0 验证执行器

把《移动端约束分析》§4.1 里那五项「验证而非开发」的 P0 事项，
做成能在电脑上跑、能留档、能被复核的东西。

    P0-1  Node 20+ 在 K60 上跑起来
    P0-2  EX-13 侧载应用的受限设置
    P0-3  EX-16 虚拟屏可行性
    P0-4  内存预算实测
    P0-5  能耗基线实测

分层：

    adb.py          只负责把命令送到设备、拿回文本
    parse.py        只负责把文本变成结构（纯函数，可离线单测）
    experiments.py  只负责判定（调用上面两者）
    model.py        结果模型与报告渲染

入口是上一层的 `run_p0.py`。
"""

from .adb import Adb, AdbNotFound, ShellResult, find_adb
from .model import Check, Evidence, ExperimentResult, Report, Verdict

__all__ = [
    "Adb",
    "AdbNotFound",
    "ShellResult",
    "find_adb",
    "Check",
    "Evidence",
    "ExperimentResult",
    "Report",
    "Verdict",
]
