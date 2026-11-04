#!/usr/bin/env python3
"""按 DSH skill 文件系统加载器的真实规则校验 SKILL.md。

规则对齐 D:\\dsh桌面版\\resources\\app\\dsh\\node_modules\\@deepseek-ai\\dsh-skill-filesystem\\lib\\index.js
（parseFrontmatter / parseSkillFile / parseInvocationPolicy / discoverRoot）：

1. 文件首行必须恰为 `---`（允许行尾 \r）。
2. 必须存在闭合行 `---`。
3. front matter 必须是 YAML 映射（不是列表/标量）。
4. name、description 均须为非空字符串。
5. name 须匹配 /^[a-z0-9]+(?:-[a-z0-9]+)*$/。
6. 禁用遗留键：disableModelInvocation / modelInvocable / userInvocable。
7. disable-model-invocation / user-invocable 若存在，须可解析为布尔
   （bool 或 1/0/"1"/"0"/true/false/yes/no/on/off，字符串大小写不敏感）。
8. discoverRoot 只扫描 root 下 depth-1：子目录里的 SKILL.md，或 root 下直接放的 *.md。
   名字为 .system 的目录被跳过。

用法：
    python tools/verify/check_skills.py                       # 扫 ~/.dsh/skills
    python tools/verify/check_skills.py <dir> [<dir> ...]      # 扫指定根目录
    python tools/verify/check_skills.py --json                # 机器可读

★ 2026-11-04 整合：这个脚本原来躺在 tools/ 根下，只能靠人记得手跑。
  技能被静默丢弃（BOM、front matter 不合法）时**没有任何代码会红**，
  正是本项目反复出现的「建好了没接线」。现在它是 verify_all 的第 13 条检查：
  跑不通就红，不必靠记忆。
  默认根目录 ~/.dsh/skills 不存在时只会报 skipped 并返回 0 ——
  这样别人的机器上跑 verify_all 不会因为没装技能而失败。
"""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.stdout.write("ERROR: PyYAML not installed (pip install pyyaml)\n")
    raise SystemExit(2)

SKILL_NAME = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
LEGACY_KEYS = {
    "disableModelInvocation": "disable-model-invocation",
    "modelInvocable": "disable-model-invocation",
    "userInvocable": "user-invocable",
}
BOOL_KEYS = ("disable-model-invocation", "user-invocable")
TRUE_STRINGS = {"true", "yes", "on", "1"}
FALSE_STRINGS = {"false", "no", "off", "0"}


def default_root() -> Path:
    home = os.environ.get("DSH_HOME") or str(Path.home() / ".dsh")
    return Path(home) / "skills"


def split_frontmatter(text: str):
    """返回 (data, body, error)。error 非空表示会被加载器丢弃。"""
    if not text:
        return None, None, "empty file"
    first_end = text.find("\n")
    if first_end < 0:
        return None, None, "missing YAML frontmatter (single line file)"
    if text[:first_end].rstrip("\r") != "---":
        return None, None, "first line is not '---'"
    start = first_end + 1
    pos = start
    while pos <= len(text):
        nxt = text.find("\n", pos)
        line_end = len(text) if nxt < 0 else nxt
        if text[pos:line_end].rstrip("\r") == "---":
            block = text[start:pos]
            body = "" if nxt < 0 else text[nxt + 1 :]
            try:
                data = yaml.safe_load(block)
            except Exception as exc:  # noqa: BLE001 - report the parser message verbatim
                return None, None, f"invalid YAML frontmatter: {exc}"
            if not isinstance(data, dict):
                return None, None, "frontmatter is not a YAML mapping"
            return data, body, None
        if nxt < 0:
            return None, None, "missing closing '---' line"
        pos = nxt + 1
    return None, None, "missing closing '---' line"


def check_bool(data: dict, key: str):
    if key not in data:
        return None
    value = data[key]
    if isinstance(value, bool):
        return None
    if isinstance(value, int) and value in (0, 1):
        return None
    if isinstance(value, str):
        low = value.strip().lower()
        if low in TRUE_STRINGS or low in FALSE_STRINGS:
            return None
        return f'frontmatter field "{key}" must be a boolean (got {value!r})'
    return f'frontmatter field "{key}" must be a boolean (got {type(value).__name__})'


def validate(path: Path):
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return {
            "name": None,
            "ok": False,
            "reasons": ["file is not valid UTF-8 (loader would reject as non-text)"],
        }
    data, body, err = split_frontmatter(text)
    if err:
        reasons = [err]
        if text.startswith("\ufeff"):
            reasons.insert(
                0,
                "file starts with a UTF-8 BOM: DSH compares the first line literally, so "
                "'.\\ufeff---' != '---' and the whole skill is silently dropped — strip the BOM",
            )
        return {"name": None, "ok": False, "reasons": reasons}

    reasons = []
    name = data.get("name")
    desc = data.get("description")
    if not (isinstance(name, str) and name.strip()):
        reasons.append("missing/empty `name`")
    elif not SKILL_NAME.match(name):
        reasons.append(f'name "{name}" fails /^[a-z0-9]+(?:-[a-z0-9]+)*$/')
    if not (isinstance(desc, str) and desc.strip()):
        reasons.append("missing/empty `description`")
    for legacy, canonical in LEGACY_KEYS.items():
        if legacy in data:
            reasons.append(f'legacy field "{legacy}" is rejected; use "{canonical}"')
    for key in BOOL_KEYS:
        msg = check_bool(data, key)
        if msg:
            reasons.append(msg)

    stripped_body = body.strip()
    if not reasons and not stripped_body:
        reasons.append("body is empty after front matter (skill would load but carry no instructions)")

    return {
        "name": name if isinstance(name, str) else None,
        "ok": not reasons,
        "reasons": reasons,
        "whenToUse": isinstance(data.get("whenToUse"), str),
        "body_chars": len(stripped_body),
        "has_metadata": isinstance(data.get("metadata"), dict),
    }


def discover(roots):
    """按 discoverRoot 的 depth-1 规则列出候选 SKILL.md。"""
    found = []
    for root in roots:
        if not root.is_dir():
            found.append((root, None, "root directory does not exist"))
            continue
        for entry in sorted(root.iterdir(), key=lambda p: p.name.lower()):
            if entry.name == ".system":
                continue
            if entry.is_dir():
                candidate = entry / "SKILL.md"
                found.append((root, candidate, None if candidate.is_file() else "directory has no SKILL.md"))
            elif entry.is_file() and entry.suffix.lower() == ".md":
                found.append((root, entry, None))
            else:
                found.append((root, None, "not a directory and not a .md file (ignored)"))
    return found


def main(argv):
    as_json = "--json" in argv
    targets = [a for a in argv if not a.startswith("--")]
    roots = [Path(t).expanduser() for t in targets] if targets else [default_root()]

    rows = []
    for root, candidate, skip in discover(roots):
        if candidate is None:
            rows.append({"root": str(root), "path": None, "ok": None, "note": skip})
            continue
        result = validate(candidate)
        result["root"] = str(root)
        result["path"] = str(candidate)
        rows.append(result)

    if as_json:
        sys.stdout.write(json.dumps(rows, ensure_ascii=False, indent=2))
        return 0

    ok = [r for r in rows if r.get("ok") is True]
    bad = [r for r in rows if r.get("ok") is False]
    skipped = [r for r in rows if r.get("ok") is None]

    lines = []
    for row in rows:
        if row.get("path") is None:
            lines.append(f"[--] {row['root']}: {row['note']}")
            continue
        mark = "OK " if row["ok"] else "BAD"
        lines.append(f"[{mark}] {row['name'] or '(no name)'}  <- {row['path']}")
        for reason in row.get("reasons", []):
            lines.append(f"       ! {reason}")
    lines.append("")
    lines.append(f"total={len(rows)} ok={len(ok)} bad={len(bad)} skipped={len(skipped)}")
    sys.stdout.write("\n".join(lines) + "\n")
    return 0 if not bad else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
