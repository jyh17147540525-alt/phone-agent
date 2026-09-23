#!/usr/bin/env python3
"""检查 Kotlin 源码里中文文案的引号误用。

问题
====

中文文案里想写引号时，很容易打成 ASCII 双引号 `"`。但在 Kotlin 里 `"` 是
字符串定界符，于是：

    "具体"用这些能力做什么"由插件决定。"

会被解析成「字符串 + 标识符 + 字符串」，直接编译失败。

这个错误之所以危险，是因为**它在编辑器里不显眼** —— 中文全角引号和 ASCII
引号在等宽字体下几乎一样宽，肉眼扫过去完全正常。而且报错行指向的往往是
后面几行，让人以为是别的地方出了问题。

⚠️ 第一版脚本报出 135 处，其中 134 处是 KDoc 注释里的中文引号 ——
那些完全无害（注释不参与编译）。**一个 135 报 1 中的检查等于没用**，
所以这里做了真正的块注释状态跟踪。

判据
====

只检查**代码**（剥掉注释之后），中文字符紧邻 ASCII 双引号：

    中"中     ← 可疑

正常写法不会被误判：

    中"       字符串结尾，后面是逗号/括号/运算符
    "中       字符串开头
    中" + "中 拼接，引号后面是空格

用法：
    python tools/verify/check_kt_quotes.py android
退出码：0 = 干净；1 = 发现可疑行。
"""

from __future__ import annotations

import io
import pathlib
import re
import sys

CJK = r"\u4e00-\u9fff\u3000-\u303f\uff00-\uffef"

# 中文字符 → ASCII 双引号 → 中文字符
SUSPICIOUS = re.compile(rf"[{CJK}]\"[{CJK}]")


def strip_comments(source: str) -> str:
    """剥掉块注释与行注释，只留代码。

    按字符扫描而不是按行处理 —— 因为块注释可以跨行，也可以在代码中间开始和结束。
    """
    out: list[str] = []
    i = 0
    n = len(source)

    while i < n:
        two = source[i : i + 2]

        if two == "/*":
            end = source.find("*/", i + 2)
            if end == -1:
                break  # 未闭合的块注释，后面全算注释
            # ⚠️ **必须保留注释内部的换行数**，否则后面所有行号都会前移。
            #
            #    第一版写成 `out.append(" ")` —— 它把跨行块注释里的换行一起吞了。
            #    后果不是"漏报"，而是**报错指向错误的行**：
            #    清理后的第 N 行 ≠ 原文件的第 N 行，而脚本用 `lineno` 去
            #    `text.splitlines()` 里取原文，于是打印出来的是**另一行**。
            #
            #    实测（2026-09-23）：真实违规在 CapabilityGuardTest.kt:290 与
            #    CapabilityAuditLogTest.kt:215，脚本报的却是 269 与 191 ——
            #    而它打印的那两行内容完全合法。照着改会改错地方，
            #    而且改完检查还是红的（因为真正的那两行没动）。
            #
            #    ⇒ 检查器自身的定位错误，比"不检查"更坏：它给出的是一条
            #      **看起来可执行、但方向错**的指引。
            #
            #    末尾补一个空格是为了避免把注释两侧的代码粘在一起
            #    （`a/*x*/b` 不能变成 `ab`）—— 它不引入额外换行，所以行号不受影响。
            out.append("\n" * source.count("\n", i, end + 2) + " ")
            i = end + 2
            continue

        if two == "//":
            end = source.find("\n", i)
            if end == -1:
                break
            i = end  # 保留换行
            continue

        # 字符串字面量整体跳过 —— 里面的 // 和 /* 都不是注释
        if source[i] == '"':
            j = i + 1
            while j < n:
                if source[j] == "\\":
                    j += 2
                    continue
                if source[j] == '"':
                    break
                if source[j] == "\n":
                    break  # 未闭合的字符串，交给编译器报错
                j += 1
            out.append(source[i : j + 1])
            i = j + 1
            continue

        out.append(source[i])
        i += 1

    return "".join(out)


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "android")

    if not root.is_dir():
        print(f"[FAIL] 目录不存在：{root}")
        return 1

    files = sorted(root.rglob("*.kt"))
    hits: list[tuple[pathlib.Path, int, str]] = []

    for path in files:
        text = io.open(path, encoding="utf-8").read()
        cleaned = strip_comments(text)

        for lineno, line in enumerate(cleaned.splitlines(), 1):
            if SUSPICIOUS.search(line):
                original = text.splitlines()[lineno - 1] if lineno <= len(text.splitlines()) else ""
                hits.append((path, lineno, original.rstrip()))

    if not hits:
        print(f"[OK] 扫描 {len(files)} 个 .kt 文件，未发现中文文案中的 ASCII 引号误用。")
        return 0

    print(f"[FAIL] 发现 {len(hits)} 处引号误用（已排除注释）：\n")
    for path, lineno, line in hits:
        print(f"  {path}:{lineno}")
        print(f"      {line.strip()}")
    print()
    print("提示：中文文案里的引号请用「」（U+300C / U+300D）。")
    print("      ASCII 双引号在 Kotlin 里是字符串定界符，会让字符串提前终止。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
