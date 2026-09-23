#!/usr/bin/env python3
"""检查 Kotlin 源码里用户可见文案的 markdown 星号误用。

问题
====

Kotlin 字符串里的 `**粗体**` 是 markdown 语法，但 Android 的 `Text` / Compose
`Text` **都不解析 markdown** —— 它会原样显示两个星号。用户在界面上看到的是：

    ⚠️ 网上常见的 pm grant 那条命令对**这一项无效** —— ...

这个错误最坏的地方是**在写代码时看不出来**：

  · 编辑器不报错（它就是个普通字符串）
  · 编译器不报错（语法完全合法）
  · 离线测试不报错（没人会去断言"不该有星号"）
  · 代码评审也容易漏（`**` 在等宽字体里很不起眼）

只有**在真机上把那个页面打开**才看得见。2026-09-23 就是这么发现的：
`SettingsPermissionGuide` 的指引在能力页上显示出来，星号原样挂在句子中间。

于是全项目扫了一遍 —— 6 处（4 个文件）。这不是一次性的手误，
而是**这个项目反复踩的同一个坑**（`PermissionsScreen` 上一轮刚修过一处），
所以它值得一个静态检查，而不是又一条"记得别写星号"的注释。

判据
====

字符串字面量里，`**` 与**中文字符**相邻：

    "…对**这一项无效** ——"        ← 可疑（**紧邻中文）
    "$prefix****(32位)"            ← 正常
    "ProviderCredential(***, …)"   ← 正常
    "****"                         ← 正常

为什么用「紧邻中文」而不是「出现 `**`」：脱敏掩码（`****`、`(***, `）
在日志与 `toString` 里是**故意**的，而它们一律不含中文。这个判据在本项目上
把 13 处 `**` 精确切成 **6 处真问题 + 7 处掩码**。

⚠️ 它的代价是**漏掉纯英文的 markdown 粗体**（如 `**PM**`）。本项目目前没有
这种写法；真出现了，就把它当成"判据需要加一条"的信号，而不是默默放过。

用法：
    python tools/verify/check_ui_markdown.py android
退出码：0 = 干净；1 = 发现可疑行。
"""

from __future__ import annotations

import io
import os
import re
import sys

CJK = r"\u4e00-\u9fff"

# 两个方向都要查：`**` 紧邻中文的左边，或中文紧邻 `**` 的右边。
# 只查一个方向会漏掉 `"中文**"` 这种收尾（本项目里 6 处全是成对出现，
# 但收尾那一半在另一行，所以两个方向都必须覆盖）。
SUSPECT = re.compile(r"(\*\*[" + CJK + r"])|([" + CJK + r"]\*\*)")

SKIP_DIR_NAMES = {".gradle", ".build-trash", ".idea", "build", "generated"}


def string_literals(text: str):
    """扫出所有字符串字面量，返回 [(起始行号, 内容), ...]。

    ⚠️ 必须**剥掉注释**再找字面量：KDoc 里写 `**粗体**` 是完全正常的
    （markdown 就是给文档用的），报它等于制造噪音。
    `check_kt_quotes.py` 的第一版报出 135 处、其中 134 处是注释 —— 同样的坑。

    ⚠️ Kotlin 的块注释**可以嵌套**，所以用一个 depth 计数器而不是找最近的 `*/`。
    """
    out = []
    i, n = 0, len(text)
    line = 1
    while i < n:
        c = text[i]

        if c == "\n":
            line += 1
            i += 1
            continue

        # 行注释
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue

        # 块注释（可嵌套）
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            depth = 1
            i += 2
            while i < n and depth:
                if text[i] == "\n":
                    line += 1
                if text.startswith("/*", i):
                    depth += 1
                    i += 2
                    continue
                if text.startswith("*/", i):
                    depth -= 1
                    i += 2
                    continue
                i += 1
            continue

        # 原始字符串（三引号）—— 可以跨行
        if text.startswith('"""', i):
            start = line
            i += 3
            buf = []
            while i < n and not text.startswith('"""', i):
                if text[i] == "\n":
                    line += 1
                buf.append(text[i])
                i += 1
            i += 3
            out.append((start, "".join(buf)))
            continue

        # 普通字符串
        if c == '"':
            start = line
            i += 1
            buf = []
            while i < n and text[i] != '"':
                if text[i] == "\\":
                    buf.append(text[i : i + 2])
                    i += 2
                    continue
                if text[i] == "\n":
                    line += 1
                buf.append(text[i])
                i += 1
            i += 1
            out.append((start, "".join(buf)))
            continue

        # 字符字面量（跳过，别把它当字符串）
        if c == "'":
            i += 1
            while i < n and text[i] != "'":
                if text[i] == "\\":
                    i += 2
                    continue
                i += 1
            i += 1
            continue

        i += 1

    return out


def scan(root: str):
    """返回 [(相对路径, 起始行号, 字面量内容, [可疑片段, ...]), ...]。

    ⚠️ **每个字符串字面量只报一条**，而不是每个正则匹配报一条。
    `**这一项无效**` 会同时命中「`**` 紧邻中文」和「中文紧邻 `**`」两条规则，
    于是同一处粗体被报两遍 —— 修法却是同一个（改那个字面量）。
    报两遍会让"16 处"看起来像 16 个问题，实际只有 6 个。
    """
    hits = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIR_NAMES]
        for name in filenames:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            try:
                text = io.open(path, encoding="utf-8").read()
            except (OSError, UnicodeDecodeError):
                continue
            for start_line, body in string_literals(text):
                frags = []
                for m in SUSPECT.finditer(body):
                    if m.group(0) not in frags:
                        frags.append(m.group(0))
                if frags:
                    hits.append((os.path.relpath(path, root), start_line, body, frags))
    return hits


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else "android"
    if not os.path.isdir(root):
        print(f"目录不存在：{root}")
        return 1

    hits = scan(root)
    kt = sum(
        1
        for dp, dn, fn in os.walk(root)
        if not any(x in dp.split(os.sep) for x in SKIP_DIR_NAMES)
        for f in fn
        if f.endswith(".kt")
    )

    if not hits:
        print(f"OK ({kt} .kt 文件，无 markdown 星号误用)")
        return 0

    print(f"发现 {len(hits)} 处 markdown 星号误用（会在界面上原样显示）：\n")
    for rel, line, body, frags in hits:
        snippet = body.strip().replace("\n", " ")
        at = snippet.find(frags[0])
        if at > 30 or len(snippet) > 90:
            lo = max(0, at - 30)
            snippet = (
                ("…" if lo else "")
                + snippet[lo : lo + 90]
                + ("…" if lo + 90 < len(snippet) else "")
            )
        print(f"  {rel}:{line}")
        print(f"      {snippet}")
    print()
    print("改法：把 **粗体** 换成「直角引号」（本项目界面上强调短语的既有写法）。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
