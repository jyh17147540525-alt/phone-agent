#!/usr/bin/env python3
"""检查 XML 文件里的注释是否含非法序列。

问题
====

XML 规范（[XML 1.0 §2.5](https://www.w3.org/TR/xml/#sec-comments)）规定：

> For compatibility, the string "`--`" (double-hyphen) MUST NOT occur
> within comments.

也就是说，注释**内容**里不能出现连续两个连字符。

这条规则在写注释时极易踩到，因为人会很自然地在注释里
"讲解这条规则本身"，或者画 `----- 分隔线 -----`，或者写：

    <!-- 注意：不能出现 <!-- 这种写法，块内注释要写成 <!- - ->  -->

上面这行**同时**包含了 `<!--`（提前闭合本注释）和 `--`（非法序列）。

真实事故
========

2026-09-22，`android/app/src/main/AndroidManifest.xml` 第 53-57 行
的注释在解释"块内不能出现 `<!--`"时写下了那两个字面字符，导致：

    ManifestMerger2$MergeFailureException: Error parsing AndroidManifest.xml
    Caused by: org.xml.sax.SAXParseException; lineNumber: 56; columnNumber: 24

⚠️ **报错信息在中文 Windows 控制台上是乱码**（实测输出 `עвַ "--"`），
定位全靠 `lineNumber`。

⚠️ 而且它**只在 Gradle 走到 manifest 处理时才炸**，纯 Kotlin 编译、
离线测试、静态检查全都发现不了 —— 属于"改一行注释，半天后构建失败"。

判据
====

扫描 `src/main/AndroidManifest.xml` 与 `src/**/res/**/*.xml` 的**注释块内部**：

    <!-- ... -- ... -->      ← 报错（注释内含 --）
    <!-- ... <!-- ... -->     ← 报错（注释内含 <!--，会提前闭合）

注释**外部**的 `--` 完全合法（如 `android:value="--flag"`），不检查。

已知非误报
==========

本项目里 `<!- ... ->` 这种写法是**故意**的规避手段（单连字符），
它不构成 `--`，不会报错 —— 那是正确的写法。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# 注释块：<!-- 到第一个 -->
COMMENT_RE = re.compile(r"<!--(.*?)-->", re.S)
# 未闭合的注释开头（也会导致解析失败）
UNCLOSED_RE = re.compile(r"<!--(?!.*?-->)", re.S)


def find_xml_files(android_root: Path) -> list[Path]:
    """AndroidManifest.xml + res 下的所有 xml。"""
    files: list[Path] = []
    for pattern in ("**/src/main/AndroidManifest.xml", "**/src/*/res/**/*.xml"):
        files.extend(android_root.glob(pattern))
    return sorted(set(files))


def check_file(path: Path) -> list[str]:
    problems: list[str] = []
    text = path.read_text(encoding="utf-8", errors="replace")

    for match in COMMENT_RE.finditer(text):
        body = match.group(1)
        line_no = text[: match.start()].count("\n") + 1

        if "<!--" in body:
            problems.append(
                f"行 {line_no}：注释内含 `<!--` 字面量，会让本注释提前闭合。"
                f"块内注释请写成 `!- ... -`"
            )
        if "--" in body:
            # 找到具体位置，方便定位
            offset = body.index("--")
            inner_line = body[:offset].count("\n")
            problems.append(
                f"行 {line_no + inner_line}：注释内含非法序列 `--`（XML 规范禁止）。"
                f"块内注释请写成 `!- ... -`"
            )

    # 未闭合：<!-- 之后没有任何 -->  —— 这类错误报错信息也是 SAXParseException
    if not problems:
        opens = text.count("<!--")
        closes = text.count("-->")
        if opens != closes:
            problems.append(
                f"注释开闭不配对：`<!--` 出现 {opens} 次，"
                f"`-->` 出现 {closes} 次"
            )

    return problems


def main() -> int:
    if len(sys.argv) > 1:
        android_root = Path(sys.argv[1]).resolve()
    else:
        # 默认：脚本所在位置的 ../../android
        android_root = (Path(__file__).resolve().parent.parent.parent / "android").resolve()

    if not android_root.is_dir():
        print(f"✗ 找不到 android 目录：{android_root}", file=sys.stderr)
        return 2

    files = find_xml_files(android_root)
    if not files:
        print(f"✗ 在 {android_root} 下没找到 XML 文件 —— 查找模式可能不对", file=sys.stderr)
        return 2

    total_problems = 0
    for path in files:
        problems = check_file(path)
        if problems:
            rel = path.relative_to(android_root.parent)
            print(f"\n✗ {rel}")
            for p in problems:
                print(f"    {p}")
            total_problems += len(problems)

    if total_problems:
        print(f"\n发现 {total_problems} 处 XML 注释问题。")
        print("这类错误只在 Gradle 处理 manifest / 资源时才会暴露，")
        print("且报错信息在中文控制台常是乱码 —— 建议就地修掉。")
        return 1

    print(f"[OK] 扫描 {len(files)} 个 XML 文件，注释均合法。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
