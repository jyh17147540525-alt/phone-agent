# -*- coding: utf-8 -*-
"""内置 Python 后端的执行包装。

★ 为什么要在 Python 侧包一层，而不是 Kotlin 直接拼代码：
  用户代码里可能有任意引号、反斜杠、三引号。把它塞进一段 Python 源码字符串里
  早晚会出现"看起来对、实际被转义吃掉"的注入。这里改成**base64 传入**，
  Kotlin 侧只做编码，Python 侧只做解码 —— 中间没有任何一层需要理解代码内容。

★ 为什么结果回 JSON 而不是三路返回：
  Chaquopy 的 `callAttr` 只能拿一个返回值。JSON 是唯一还能保留
  "退出码 / 标准输出 / 标准错误"三件事的最小形状。
"""

import base64
import contextlib
import io
import json
import sys
import traceback


def run_b64(code_b64: str) -> str:
    """执行 base64 编码的代码，返回 {"status", "out", "err"} 的 JSON。"""
    try:
        code = base64.b64decode(code_b64).decode("utf-8")
    except Exception:
        return json.dumps({"status": 1, "out": "", "err": "代码解码失败（不是合法的 UTF-8 文本）"}, ensure_ascii=False)

    out = io.StringIO()
    err = io.StringIO()
    status = 0
    scope = {"__name__": "__main__", "__builtins__": __builtins__}

    try:
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            exec(compile(code, "<linux_exec>", "exec"), scope)
    except SystemExit as exit_signal:
        # `sys.exit(3)` 之类：尊重它给的码，而不是一律当失败
        status = exit_signal.code if isinstance(exit_signal.code, int) else 1
    except BaseException:
        status = 1
        err.write(traceback.format_exc())

    return json.dumps({"status": status, "out": out.getvalue(), "err": err.getvalue()}, ensure_ascii=False)


def version() -> str:
    return sys.version.split()[0]
