#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
行尾符守卫 —— 抓「本次改动把 LF 文件改成了 CRLF」。

═══════════════════════════════════════════════════════════════
 ★ 为什么需要它
═══════════════════════════════════════════════════════════════

2026-09-23 实测踩了一次：用 Python 的 `write_text` 改源码（Windows 上
`read_text` 把 CRLF 归一成 LF、`write_text` 又把 LF 写成 CRLF），
**8 个原本纯 LF 的文件被整体翻成 CRLF**。表现极具迷惑性：

  · 编译通过、测试全绿 —— 因为换行符不影响 Kotlin
  · 提交信息里的数字却很难看（`3656 deletions`），看着像大改

⚠️ 它属于本项目反复吃亏的那一类：**不报错、不失败，只是安静地改坏了一件东西**。
   而它唯一的线索是 `git diff --numstat` 的增删量与"真实改动行数"不成比例 ——
   那个数字在提交前才看，太晚了。

⇒ 所以把它变成一个能自动跑的检查，而不是靠"记得看一眼 diff"。

═══════════════════════════════════════════════════════════════
 ★ 判据：只看**本次改动**，不碰历史遗留
═══════════════════════════════════════════════════════════════

⚠️ 仓库里**本来就有**若干 CRLF 文件（docs 下生成的 HTML、几个早期 .kt、
   几个 build.gradle.kts）。所以**不能**写成"全仓不许有 CRLF" ——
   那会立刻红一片，而红的全是无关的历史文件，等于把这条检查废掉。

判据只针对**相对某个基线版本被改动过的文件**，且要求：

  改动前 0 个 CRLF  →  改动后 >0 个 CRLF   ⇒  是我们引入的，报错

用法：
    python tools/verify/check_line_endings.py            # 基线 HEAD（提交前跑，正常流程）
    python tools/verify/check_line_endings.py <rev>      # 基线指定提交

★★ 基线的选择是**这个检查能不能生效的关键**，而它有一个反直觉之处：

  · **提交前**跑（基线 = HEAD）：正确 —— HEAD 还是改动前那一版。
  · **已经把改坏的那次提交了**再跑：**抓不到**。因为 HEAD 里装的
    就是被改坏的版本，工作区与它一致，那个文件根本不算"改动过"。

  ⇒ 如果你怀疑某次**已提交**的改动改坏了行尾，基线必须指到**那次提交之前**：

        python tools/verify/check_line_endings.py <那次提交>~1

⚠️ 这条限制是本质的，不是实现偷懒：判断"行尾风格变了没有"必须有一个
   "变化之前"的参照物，而 git 手里只有 HEAD / 索引。
   所以这个检查的**正确位置是提交前**，不是事后补救。
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

# 只看这些后缀 —— 二进制与生成物不参与
SUFFIXES = {
    ".kt", ".kts", ".md", ".py", ".xml", ".json",
    ".pro", ".properties", ".html", ".gradle", ".toml", ".yml", ".yaml",
}


def git(*args: str) -> bytes:
    return subprocess.run(
        ["git", *args], cwd=REPO, capture_output=True,
    ).stdout


def crlf_of(blob: bytes) -> int:
    return blob.count(b"\r\n")


def main() -> int:
    base = sys.argv[1] if len(sys.argv) > 1 else "HEAD"

    # ⚠️ 用 `--name-only` 而不是 `--numstat`：这里要的是"哪些文件被动过"，
    #    行数统计交给下面自己算（因为要按字节比，不能信 git 的换行处理）。
    changed = [
        line.strip()
        for line in git("diff", "--name-only", base).decode("utf-8", "replace").splitlines()
        if line.strip()
    ]

    introduced: list[tuple[str, int, int]] = []
    for rel in changed:
        path = REPO / rel
        if not path.exists() or path.suffix not in SUFFIXES:
            continue

        before = git("show", f"{base}:{rel}")
        if not before:
            continue  # 新增文件：没有"改动前"，跳过

        before_crlf = crlf_of(before)
        after_crlf = crlf_of(path.read_bytes())

        # ⚠️ 判据是"**增多**"，不是"从 0 变成非 0"。
        #    只看 0→N 会漏掉"本来就半 CRLF、又被翻了一批"的情况，
        #    而那种文件在 diff 里同样是一整片红。
        if after_crlf > before_crlf:
            introduced.append((rel, before_crlf, after_crlf))

    if not introduced:
        print(f"OK (对比 {base}：{len(changed)} 个改动文件，未发现 CRLF 增多)")
        if base == "HEAD" and not changed:
            print("   ⚠️ 但对比 HEAD 时**没有**改动文件 —— 如果你刚提交过，")
            print("      这次检查等于没查。基线请指到那次提交之前。")
        return 0

    print(f"发现 {len(introduced)} 个文件的 CRLF 变多了（相对 {base}）：\n")
    for rel, before_crlf, after_crlf in introduced:
        delta = after_crlf - before_crlf
        # ⚠️ 把行数也打出来：delta 接近文件总行数 = 整个文件被翻了一遍，
        #    那正是 `write_text` 的签名式破坏。
        total = len((REPO / rel).read_bytes().split(b"\n"))
        mark = "  ← 整个文件被翻了一遍" if delta >= total - 2 else ""
        print(f"  {before_crlf:5d} → {after_crlf:5d} CRLF (+{delta})   {rel}{mark}")

    print(
        "\n改法：按**字节**读写，不要用文本模式。\n"
        "  raw = p.read_bytes()\n"
        "  text = raw.decode('utf-8')\n"
        "  p.write_bytes(text.replace(old, new).encode('utf-8'))\n"
        "\n"
        "修复（同样按字节）：\n"
        "  p.write_bytes(p.read_bytes().replace(b'\\r\\n', b'\\n'))\n"
        "\n"
        "⚠️ 修完必须证明**内容没丢** —— 与基线逐字节比对（去掉行尾差异）：\n"
        "  git show <base>:<file> | 去掉 \\r → 必须与工作区完全相同\n"
        "\n"
        "⚠️ 只修自己本轮动过的文件，不要去顺手统一历史遗留的 CRLF 文件。"
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
