#!/usr/bin/env python3
"""版本目录引用对账。

为什么需要这个检查
==================

Gradle 的版本目录（libs.versions.toml）在 Kotlin DSL 里生成的是**类型安全访问器**：
`androidx-core-ktx` 这个别名对应 `libs.androidx.core.ktx`。访问器是**编译期**解析的，
所以只要有一个模块写了 toml 里不存在的别名，**配置阶段**就直接失败：

    Unresolved reference: nonexistent

而这个失败与"该模块是否参与本次构建"无关 —— Gradle 要配置所有 include 进来的模块。
26 个模块里任何一个写错，`./gradlew :app:assembleDebug` 都跑不起来，
报错行号还指向那个你根本没在用的模块。这个检查就是为了在构建之前把它揪出来。

别名 → 访问器的转换规则（容易记错，这里固化下来）：
    `-`  →  `.`        androidx-core-ktx      → libs.androidx.core.ktx
    驼峰保持不变        composeBom             → libs.composeBom
    `_`  →  `.`        （Gradle 两种都接受，本仓库统一用 `-`）

用法：
    python tools/verify/check_version_catalog.py android
退出码：0 = 全部对账通过；1 = 存在未定义的引用。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# ── 解析 toml（不引入第三方依赖，只需处理 [versions] / [libraries] / [plugins]）──
SECTION_RE = re.compile(r"^\s*\[([^\]]+)\]\s*$")
KEY_RE = re.compile(r"^\s*([A-Za-z0-9_\-]+)\s*=")


def parse_catalog(path: Path) -> dict[str, set[str]]:
    """返回 {'versions': {...}, 'libraries': {...}, 'plugins': {...}}（别名已转成访问器片段）。"""
    sections: dict[str, set[str]] = {"versions": set(), "libraries": set(), "plugins": set()}
    current: str | None = None

    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.split("#", 1)[0].rstrip()
        if not stripped:
            continue

        m = SECTION_RE.match(stripped)
        if m:
            current = m.group(1).strip()
            continue

        if current in sections:
            m = KEY_RE.match(stripped)
            if m:
                # `-` 和 `_` 都变成 `.`
                sections[current].add(m.group(1).replace("-", ".").replace("_", "."))

    return sections


# ── 扫描源码里的 libs.* 引用 ──────────────────────────────────────────────
# 只匹配 libs.<a>.<b>... 形式的访问器。libs.versions.<name>.get() 单独处理。
LIBS_REF_RE = re.compile(r"\blibs\.((?:[A-Za-z0-9_]+\.)*[A-Za-z0-9_]+)")

# ⚠️ 方法调用尾缀白名单。
#
# `libs.versions.compileSdk.get()` 里的 `.get` 是**方法调用**，不是目录层级。
# 正则分不清这两者，会把 `versions.compileSdk.get` 整个当成访问器路径，
# 于是去 [versions] 里找一个叫 "compileSdk.get" 的条目 —— 当然找不到，
# 然后报一个纯属虚构的错。这个脚本第一版就是这么误报的。
#
# 处理方式：匹配失败时逐段剥掉尾部的已知方法名再试。
METHOD_TAILS = {"get", "getOrNull", "getOrElse", "orNull", "orElse"}


def resolve_accessor(ref: str) -> str:
    """剥掉尾部的方法调用名，返回真正的访问器路径。"""
    parts = ref.split(".")
    while len(parts) > 1 and parts[-1] in METHOD_TAILS:
        parts.pop()
    return ".".join(parts)


def scan_references(build_files: list[Path]) -> dict[str, list[str]]:
    """返回 {访问器路径: [出现的文件...]}。"""
    refs: dict[str, list[str]] = {}

    for f in build_files:
        text = f.read_text(encoding="utf-8")
        for line in text.splitlines():
            code = line.split("//", 1)[0]
            for m in LIBS_REF_RE.finditer(code):
                ref = resolve_accessor(m.group(1))
                refs.setdefault(ref, []).append(str(f))

    return refs


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "android")
    catalog_path = root / "gradle" / "libs.versions.toml"

    if not catalog_path.is_file():
        print(f"[FAIL] 找不到版本目录：{catalog_path}")
        return 1

    catalog = parse_catalog(catalog_path)
    build_files = sorted(root.glob("**/build.gradle.kts"))
    refs = scan_references(build_files)

    print(f"版本目录：{catalog_path}")
    print(f"  versions  {len(catalog['versions']):3d} 条")
    print(f"  libraries {len(catalog['libraries']):3d} 条")
    print(f"  plugins   {len(catalog['plugins']):3d} 条")
    print(f"扫描 {len(build_files)} 个 build.gradle.kts，发现 {len(refs)} 种 libs.* 引用")
    print()

    problems: list[tuple[str, str, list[str]]] = []

    for ref, files in sorted(refs.items()):
        parts = ref.split(".")
        head = parts[0]

        # libs.versions.<name> —— 版本访问器
        if head == "versions":
            name = ".".join(parts[1:])
            if name not in catalog["versions"]:
                problems.append((ref, "versions", files))
            continue

        # libs.plugins.<alias>
        if head == "plugins":
            alias = ".".join(parts[1:])
            if alias not in catalog["plugins"]:
                problems.append((ref, "plugins", files))
            continue

        # libs.<alias> —— 库访问器
        if ref not in catalog["libraries"]:
            problems.append((ref, "libraries", files))

    if not problems:
        print("[OK] 全部 libs.* 引用都能在版本目录里找到定义。")
        return 0

    print(f"[FAIL] 发现 {len(problems)} 处引用未在版本目录中定义：\n")
    for ref, section, files in problems:
        print(f"  libs.{ref}")
        print(f"      应定义在 [{section}]，当前缺失")
        uniq = sorted(set(files))
        for f in uniq[:3]:
            print(f"      ← {f}")
        if len(uniq) > 3:
            print(f"      ← ...另有 {len(uniq) - 3} 个文件")
        print()

    print("提示：访问器规则是 `-` → `.`，例如别名 androidx-core-ktx 对应 libs.androidx.core.ktx。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
