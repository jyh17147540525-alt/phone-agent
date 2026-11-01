#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
测试命名审计 —— 防止"测试文件存在但从不运行"。

═══════════════════════════════════════════════════════════════
★★ 2026-11-01：这个坑本项目踩过
═══════════════════════════════════════════════════════════════

离线验证器 `run_logic_tests.py` 的做法是：**从文件名推出类全限定名**
（`derive_test_class`），然后按那个名字去跑。于是：

· 文件 `FooTest.kt` 里声明的类必须叫 `FooTest`
· 否则它**不会被跑** —— 而且没有任何报错

⚠️ 本项目真的踩过：
  「Test class in same .kt as production class is silently not run
   (verifier derives class name from filename)」

⇒ 一个"测试文件"在磁盘上存在、看起来覆盖了某模块，
   实际上从来没执行过。这比没有测试更糟 ——
   它让人**以为**有覆盖。

═══════════════════════════════════════════════════════════════
判定规则
═══════════════════════════════════════════════════════════════

对每个 `*Test.kt`：

1. 从文件名推出期望类名（去 `.kt`）
2. 在文件里找 `class <期望类名>` / `object <期望类名>`
3. 找不到 ⇒ **报错**（这个文件不会被跑）

另外检查：
4. 一个文件里出现**多个** `*Test` 类 ⇒ 提醒
   （只有与文件名同名的那个会跑，其余静默跳过）

用法：
    python tools/verify/audit_test_naming.py
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SRC_ROOT = os.path.join(REPO_ROOT, "android")

CLASS_DECL = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public\s+|internal\s+|private\s+)?"
    r"(?:abstract\s+|open\s+|sealed\s+|final\s+)?"
    r"(?:class|object)\s+(\w+)",
    re.MULTILINE,
)


def test_files():
    for root, dirs, files in os.walk(SRC_ROOT):
        dirs[:] = [d for d in dirs if d != "build"]
        if os.sep + "test" + os.sep not in root + os.sep:
            continue
        for f in files:
            if f.endswith("Test.kt"):
                yield os.path.join(root, f)


def main() -> int:
    missing: list[tuple[str, str]] = []
    multiple: list[tuple[str, list[str]]] = []
    ok = 0

    for path in sorted(test_files()):
        stem = os.path.basename(path)[: -len(".kt")]
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        rel = os.path.relpath(path, REPO_ROOT).replace("\\", "/")

        names = CLASS_DECL.findall(text)

        if stem not in names:
            missing.append((rel, stem))
            continue

        # 除了与文件名同名的那个，还有别的 *Test 类吗
        others = [n for n in names if n != stem and n.endswith("Test")]
        if others:
            multiple.append((rel, others))

        ok += 1

    total = ok + len(missing)
    print(f"测试文件 {total} 个｜命名一致 {ok} 个｜有问题 {len(missing)} 个")
    print()

    rc = 0

    if missing:
        rc = 1
        print(f"✗ {len(missing)} 个测试文件**不会被跑**（类名与文件名不一致）：")
        for rel, stem in missing:
            print(f"  · {rel}")
            print(f"    期望类名：{stem} —— 文件里找不到这个声明")
        print()
        print("⚠️ 验证器与 Gradle 都按文件名/类名去找测试。")
        print("   名字对不上就等于**没有测试**，而且不会有任何报错。")

    if multiple:
        if rc == 0:
            rc = 0  # 只是提醒，不失败
        print()
        print(f"⚠️ {len(multiple)} 个文件里有**多个** *Test 类：")
        for rel, others in multiple:
            print(f"  · {rel}")
            print(f"    额外类：{', '.join(others)}")
        print()
        print("   ✅ 2026-11-01 之后**验证器已经能发现这些类**（不再静默跳过）。")
        print("   ⚠️ 仍建议拆成独立文件：一个文件多个测试类时，失败报告只能指到")
        print("      文件、指不到具体哪一组，排查成本更高。")

    if rc == 0:
        print("✓ 所有测试文件的命名都能被跑起来")

    return rc


if __name__ == "__main__":
    sys.exit(main())
