# -*- coding: utf-8 -*-
"""把方案 Markdown 转成带样式的单文件 HTML（深色主题，适配 IDE）。

用法：
    python build_html.py                      # 转换默认的 v1.0 方案
    python build_html.py <src.md> [out.html]  # 转换指定文件
"""
import re
import sys
import pathlib

import markdown

DEFAULT_SRC = pathlib.Path(r"D:\手机agent开发\docs\手机AI智能体助手-项目方案-v1.0.md")

if len(sys.argv) > 1:
    SRC = pathlib.Path(sys.argv[1])
else:
    SRC = DEFAULT_SRC
OUT = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else SRC.with_suffix(".html")

text = SRC.read_text(encoding="utf-8")

# 文档标题：取第一个 H1，回退到文件名
m = re.search(r"^#\s+(.+)$", text, re.MULTILINE)
DOC_TITLE = m.group(1).strip() if m else SRC.stem

# 去掉开头的目录区块（HTML 版单独生成侧边目录，避免重复）
text = re.sub(r"## 目录\n\n(?:- \[.*?\n)+", "", text, count=1)

md = markdown.Markdown(
    extensions=[
        "tables",
        "fenced_code",
        "codehilite",
        "toc",
        "attr_list",
        "sane_lists",
        "md_in_html",
    ],
    extension_configs={
        "codehilite": {"guess_lang": False, "noclasses": False},
        "toc": {"toc_depth": "2-3", "permalink": False},
    },
)

body = md.convert(text)
toc = md.toc

# 修复代码块里的方块/箭头字符，保证等宽显示
body = body.replace("<table>", '<div class="tw"><table>').replace("</table>", "</table></div>")

HTML = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{DOC_TITLE}</title>
<style>
:root {{
  --bg: #14161a;
  --bg-soft: #1a1d22;
  --panel: #1e2228;
  --panel-2: #23272f;
  --border: #2e343d;
  --text: #e6e9ee;
  --text-dim: #9aa4b2;
  --text-faint: #6b7684;
  --accent: #6ea8fe;
  --accent-2: #7ee0c0;
  --warn: #f0b429;
  --danger: #f2685c;
  --ok: #57c98a;
  --code-bg: #15181d;
}}
* {{ box-sizing: border-box; }}
html {{ scroll-behavior: smooth; }}
body {{
  margin: 0;
  background: var(--bg);
  color: var(--text);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
               "Hiragino Sans GB", "Microsoft YaHei", "Source Han Sans SC", sans-serif;
  font-size: 15.5px;
  line-height: 1.85;
  -webkit-font-smoothing: antialiased;
}}
.wrap {{ display: flex; max-width: 1500px; margin: 0 auto; }}
aside {{
  width: 300px; flex: 0 0 300px;
  position: sticky; top: 0; height: 100vh; overflow-y: auto;
  padding: 28px 18px 60px 28px;
  border-right: 1px solid var(--border);
  background: var(--bg-soft);
  font-size: 13.5px;
}}
aside .brand {{
  font-size: 12px; letter-spacing: .12em; text-transform: uppercase;
  color: var(--text-faint); margin-bottom: 4px;
}}
aside .title {{ font-size: 15px; font-weight: 700; color: var(--text); margin-bottom: 20px; line-height: 1.5; }}
aside ul {{ list-style: none; padding-left: 0; margin: 0; }}
aside ul ul {{ padding-left: 14px; margin-top: 4px; }}
aside li {{ margin: 2px 0; }}
aside a {{
  color: var(--text-dim); text-decoration: none; display: block;
  padding: 4px 8px; border-radius: 6px; border-left: 2px solid transparent;
  transition: .15s;
}}
aside a:hover {{ color: var(--text); background: var(--panel-2); border-left-color: var(--accent); }}
main {{ flex: 1; min-width: 0; padding: 44px 56px 120px; max-width: 1080px; }}

h1 {{
  font-size: 30px; line-height: 1.35; margin: 0 0 8px; font-weight: 800;
  letter-spacing: -.01em;
}}
h2 {{
  font-size: 22px; margin: 56px 0 18px; padding-bottom: 10px;
  border-bottom: 1px solid var(--border); font-weight: 700; scroll-margin-top: 20px;
}}
h3 {{ font-size: 17.5px; margin: 34px 0 12px; font-weight: 700; color: #dfe5ee; scroll-margin-top: 20px; }}
h4 {{ font-size: 15.5px; margin: 24px 0 10px; font-weight: 700; color: var(--text-dim); }}
p {{ margin: 12px 0; }}
a {{ color: var(--accent); }}
strong {{ color: #fff; font-weight: 700; }}
hr {{ border: none; border-top: 1px solid var(--border); margin: 40px 0; }}
blockquote {{
  margin: 18px 0; padding: 14px 20px;
  background: linear-gradient(90deg, rgba(110,168,254,.10), rgba(110,168,254,.02));
  border-left: 3px solid var(--accent);
  border-radius: 0 8px 8px 0; color: #cfd6e0;
}}
blockquote p {{ margin: 6px 0; }}

ul, ol {{ padding-left: 24px; margin: 12px 0; }}
li {{ margin: 5px 0; }}
li > ul, li > ol {{ margin: 5px 0; }}

.tw {{ overflow-x: auto; margin: 18px 0; border: 1px solid var(--border); border-radius: 10px; }}
table {{ border-collapse: collapse; width: 100%; font-size: 14px; background: var(--panel); }}
th, td {{ padding: 9px 14px; text-align: left; border-bottom: 1px solid var(--border); vertical-align: top; }}
th {{ background: var(--panel-2); font-weight: 700; color: #fff; white-space: nowrap; font-size: 13.5px; }}
tr:last-child td {{ border-bottom: none; }}
tbody tr:hover {{ background: rgba(110,168,254,.045); }}

code {{
  font-family: "JetBrains Mono", "Cascadia Code", Consolas, "SF Mono", Menlo, monospace;
  font-size: 13px; background: var(--code-bg); padding: 2px 6px;
  border-radius: 5px; color: #8fd3ff; border: 1px solid var(--border);
}}
pre {{
  background: var(--code-bg); border: 1px solid var(--border); border-radius: 10px;
  padding: 18px 20px; overflow-x: auto; margin: 18px 0;
  font-size: 12.8px; line-height: 1.65;
}}
pre code {{
  background: none; border: none; padding: 0; color: #cfd8e3;
  white-space: pre; font-family: "JetBrains Mono", "Cascadia Code", Consolas, "SF Mono", Menlo, monospace;
}}
.codehilite {{ background: var(--code-bg); border: 1px solid var(--border); border-radius: 10px; margin: 18px 0; }}
.codehilite pre {{ border: none; margin: 0; background: none; }}
.codehilite .k, .codehilite .kd, .codehilite .kn {{ color: #ff7b9c; }}
.codehilite .s, .codehilite .s1, .codehilite .s2 {{ color: #7ee0c0; }}
.codehilite .c1, .codehilite .cm {{ color: #5f6b7a; font-style: italic; }}
.codehilite .n, .codehilite .nf, .codehilite .nc {{ color: #8fd3ff; }}
.codehilite .m, .codehilite .mi {{ color: #f0b429; }}

@media (max-width: 1000px) {{
  aside {{ display: none; }}
  main {{ padding: 28px 20px 80px; }}
  body {{ font-size: 15px; }}
  h1 {{ font-size: 24px; }}
  h2 {{ font-size: 19px; }}
}}
::-webkit-scrollbar {{ width: 10px; height: 10px; }}
::-webkit-scrollbar-track {{ background: var(--bg); }}
::-webkit-scrollbar-thumb {{ background: #333a45; border-radius: 5px; }}
::-webkit-scrollbar-thumb:hover {{ background: #414a58; }}
</style>
</head>
<body>
<div class="wrap">
  <aside>
    <div class="brand">Project Plan</div>
    <div class="title">{DOC_TITLE}</div>
    {toc}
  </aside>
  <main>
{body}
  </main>
</div>
</body>
</html>
"""

OUT.write_text(HTML, encoding="utf-8")
print("OK ->", OUT)
print("size:", len(HTML), "chars")
