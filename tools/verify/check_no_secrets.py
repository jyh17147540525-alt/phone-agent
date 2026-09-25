#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
密钥泄露守卫 —— 推送前扫描**已跟踪文件**，抓「不该进仓库的凭据」。

═══════════════════════════════════════════════════════════════
 ★ 为什么需要它
═══════════════════════════════════════════════════════════════

本项目是 **BYOK**（用户自带 Key）形态：用户的 API Key 是**我们唯一不能碰的东西**。
架构上已经保证「Key 永不落明文」（Keystore + AES-GCM + SQLCipher，不进日志/备份），
但**架构约束管不住「有人往代码里贴了一行测试用的真 Key」**。

这类事故的特征和本项目反复吃亏的那一类完全一致：
  · 提交时**不报错**（git 不在乎）
  · CI **不失败**（除非有这条检查）
  · 唯一线索是**很久以后**有人拿它去跑了一笔账单

⇒ 所以必须在**推送前**变成一条能自动跑的检查，而不是靠"提交前记得看一眼"。

═══════════════════════════════════════════════════════════════
 ★ 判据：fail closed（失败即关闭）
═══════════════════════════════════════════════════════════════

⚠️ 本检查**故意不做"熵值够高才算真密钥"这类聪明判断** —— 那会引入漏报。

规则只有两条：
  1. 命中密钥模式 且 **不在已知假值白名单**  ⇒ 报错
  2. 敏感文件名被 git 跟踪                  ⇒ 报错

**白名单必须人工维护**：新出现一个疑似值 → 检查变红 → 人确认它是测试夹具后才加进去。
这个"麻烦"是**故意的** —— 它保证「放行」永远是一个有意识的动作。

⚠️ 反过来的设计（"像假的就放行"）一旦判断错，代价是**用户的 Key 被推到公开仓库**。
   两边代价不对称，所以选麻烦的那边。

用法：
    python tools/verify/check_no_secrets.py            # 扫全部已跟踪文件
    python tools/verify/check_no_secrets.py --staged   # 只扫暂存区（pre-commit 用）
"""

import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

# ═══════════════════════════════════════════════════════════════
#  敏感文件名 —— 被跟踪即报错（无论内容）
# ═══════════════════════════════════════════════════════════════

SENSITIVE_NAME_PATTERNS = [
    (re.compile(r"^\.env(\..+)?$"), "环境变量文件"),
    (re.compile(r".*\.(jks|keystore|p12|pfx|pem|key)$", re.I), "密钥/证书文件"),
    (re.compile(r"^(secrets|credentials?|signing|keystore)\.(properties|json|ya?ml|toml)$", re.I), "凭据配置"),
    (re.compile(r".*\.token$", re.I), "token 文件"),
    (re.compile(r"^local\.properties$"), "本机路径配置（含 SDK 路径）"),
    (re.compile(r"^(id_rsa|id_ed25519|id_ecdsa)$"), "SSH 私钥"),
]

# ═══════════════════════════════════════════════════════════════
#  密钥内容模式
# ═══════════════════════════════════════════════════════════════

SECRET_PATTERNS = [
    ("OpenAI / DeepSeek / 兼容风格", re.compile(r"\bsk-[A-Za-z0-9_-]{20,}")),
    ("Anthropic", re.compile(r"\bsk-ant-[A-Za-z0-9_-]{20,}")),
    ("GitHub PAT", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}")),
    ("AWS Access Key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("腾讯云 SecretId", re.compile(r"\bAKID[A-Za-z0-9]{13,}\b")),
    ("Google API Key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("Slack Token", re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}")),
    ("JWT", re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}")),
    ("私钥块", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("明文赋值的 key/secret", re.compile(
        r"""(?ix)\b(api[_-]?key|secret[_-]?key|access[_-]?token|auth[_-]?token)\b
            \s*[:=]\s*["'][A-Za-z0-9_\-]{24,}["']""")),
]

# ═══════════════════════════════════════════════════════════════
#  已知假值白名单 —— **新增必须人工确认**
#
#  这些是单元测试里用来验证「脱敏逻辑生效」的固定夹具，
#  特征是**明显的占位模式**（连续字母表 / 递增数字 / 字面写着 placeholder），
#  且**全部位于 `src/test/` 下**。确认记录见提交信息。
# ═══════════════════════════════════════════════════════════════

KNOWN_FAKE = {
    # LogSanitizerTest：连续字母表 + 递增数字，用于验证脱敏
    "sk-abcdefghijklmnopqrstuvwxyz",
    "sk-proj-abcdefghijklmnopqrstuvwxyz0123456789",
    "sk-ant-api03-abcdefghijklmnopqrstuvwxyz",
    # OpenAiCompatProviderTest：占位值
    "sk-abcdef1234567890",
    # DshConfigPatchTest：字面写着 "not-a-real-key"
    "sk-placeholder-not-a-real-key",
}

# ═══════════════════════════════════════════════════════════════
#  扫描范围
# ═══════════════════════════════════════════════════════════════

SCAN_SUFFIXES = {
    ".kt", ".java", ".kts", ".gradle", ".xml", ".json", ".yaml", ".yml",
    ".properties", ".md", ".txt", ".toml", ".sh", ".ps1", ".py", ".pro",
    ".cfg", ".ini", ".html", ".js", ".ts",
}

MAX_BYTES = 2 * 1024 * 1024  # 跳过超大文件（避免扫进二进制）


def extract_value(matched: str) -> str:
    """
    取出「真正的密钥值」。

    ⚠️ 不能用子串匹配（`fake in val`）—— 那会让一个短的白名单值
    意外放行一个**包含它**的真密钥（例如白名单里有 `sk-abc`，
    真密钥 `sk-abc<真实随机串>` 就会被静默放行）。
    ⇒ 先剥掉赋值型模式带进来的引号，再**精确比对**。
    """
    quoted = re.findall(r"""["']([^"']+)["']""", matched)
    return quoted[-1] if quoted else matched.strip()


def tracked_files(staged_only: bool):
    if staged_only:
        out = subprocess.run(
            ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR"],
            cwd=REPO, capture_output=True, text=True, check=True,
        ).stdout
    else:
        out = subprocess.run(
            ["git", "ls-files"], cwd=REPO, capture_output=True, text=True, check=True,
        ).stdout
    return [line.strip() for line in out.splitlines() if line.strip()]


def scan():
    files = tracked_files("--staged" in sys.argv)
    name_hits = []
    secret_hits = []
    scanned = 0

    for rel in files:
        # ① 敏感文件名
        base = Path(rel).name
        for pat, why in SENSITIVE_NAME_PATTERNS:
            if pat.match(base):
                name_hits.append((rel, why))
                break

        # ② 内容
        p = REPO / rel
        if p.suffix.lower() not in SCAN_SUFFIXES:
            continue
        try:
            raw = p.read_bytes()
        except OSError:
            continue
        if len(raw) > MAX_BYTES or b"\x00" in raw[:4096]:
            continue  # 太大或疑似二进制
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            continue

        scanned += 1
        for label, pat in SECRET_PATTERNS:
            for m in pat.finditer(text):
                val = extract_value(m.group(0))
                if val in KNOWN_FAKE:      # 精确比对，不做子串匹配（见 extract_value 注释）
                    continue
                line_no = text[: m.start()].count("\n") + 1
                secret_hits.append((rel, line_no, label, val[:60]))

    return files, scanned, name_hits, secret_hits


def main():
    files, scanned, name_hits, secret_hits = scan()

    if not name_hits and not secret_hits:
        print(f"OK (扫描 {len(files)} 个已跟踪文件 / 其中 {scanned} 个文本文件，"
              f"未发现疑似密钥)")
        print("   白名单条目：%d 个（新增需人工确认，见 KNOWN_FAKE）" % len(KNOWN_FAKE))
        return 0

    print("!! 发现可能泄露的凭据 —— 推送前必须处理\n")

    if name_hits:
        print("【敏感文件名被跟踪】")
        for rel, why in name_hits:
            print(f"  {rel}    （{why}）")
        print("  → 从索引移除：git rm --cached <file>，并确认 .gitignore 已覆盖\n")

    if secret_hits:
        print("【疑似密钥内容】")
        for rel, line_no, label, val in secret_hits:
            print(f"  {rel}:{line_no}  [{label}]  {val}...")
        print(
            "\n  → 若是真密钥：立即作废它，然后从**所有历史提交**里清除"
            "（git filter-repo，光删当前文件没用）\n"
            "  → 若是测试夹具：确认后加进本文件顶部的 KNOWN_FAKE 白名单\n"
            "     ⚠️ 白名单是**人工放行**的关口，不要为了让它变绿而随便加"
        )

    return 1


if __name__ == "__main__":
    sys.exit(main())
