#!/usr/bin/env python3
"""
检查模块之间的项目依赖有没有漏声明。

═══════════════════════════════════════════════════════════════
  为什么需要这个脚本
═══════════════════════════════════════════════════════════════

各模块的 `build.gradle.kts` 由 `gen_module_build_files.py` 生成，
而那个生成器**只会硬编码一条 `project(":core:common")`** ——
它没有表达"模块 A 要用模块 B 的类型"的机制。

后果不是"少个依赖"这么轻。看实际发生的：

  · `:provider:openai-compat` 从 `com.pocketagent.provider.api` 导入 13 个符号，
    却没声明 `:provider:api`
  · `:agent` 用了 `:action` 的 `ElementRef` 和 `:perception` 的类型，两个都没声明

这些模块**编译不过**，但错误藏了很久，因为：

  · `tools/verify/run_logic_tests.py` 把所有模块塞进**同一次 kotlinc 调用**，
    跨模块引用就"顺便"解析成功了 —— 漏声明的依赖在那里根本看不出来
  · `:app:assembleDebug` 能过，是因为 `app/build.gradle.kts` 里的模块依赖
    是**注释掉**的，它其实没有真正编译这些模块
  · 于是只有 `./gradlew test`（它编译每个模块的测试源码）才会暴露问题 ——
    而那是八分钟一轮的反馈循环

这个脚本把那个八分钟循环压成两秒。

═══════════════════════════════════════════════════════════════
  判据
═══════════════════════════════════════════════════════════════

Gradle 的 `implementation` 依赖**不传递**：A 依赖 B、B 依赖 C，
A 并不能看见 C 的类型。所以每个模块都必须**直接**声明它用到的每一个模块。

脚本据此对账：

  ① 扫每个模块的源码，收集它声明的包（`package X`）→ 得到「包 → 模块」映射
  ② 扫每个模块的 `import com.pocketagent.*` → 按最长前缀匹配到模块
  ③ 与 `build.gradle.kts` 里声明的 `project(":...")` 对账
  ④ 报出「用了但没声明」和「声明了但没用到」

用法：

    python tools/verify/check_module_deps.py android
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path

# 复用 check_kt_quotes.py 的注释剥离实现，不重复造。
#
# ⚠️ 这里必须剥注释，理由有两个，都是踩过的：
#
#   1. **注释掉的依赖会被当成真依赖。** app/build.gradle.kts 里有一整块
#      `// implementation(project(":perception"))` —— M0 阶段刻意注释掉的。
#      不剥注释的话，脚本会报"app 声明了 15 个用不到的模块"，
#      而那是设计意图，不是问题。**一个满屏误报的检查等于没有检查。**
#
#   2. **KDoc 里的类名会被当成真引用。** ActionExecutor.kt 的注释里写了
#      `[com.pocketagent.perception.UiNode.nodeId]`，但它其实不需要
#      `:perception` 依赖 —— 注释不参与编译。不剥掉就会凭空多报一个依赖，
#      而"多加一个依赖"看起来无害，实际上会让模块图越来越乱。
sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_kt_quotes import strip_comments  # noqa: E402

PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)\s*$", re.M)
IMPORT_RE = re.compile(r"^\s*import\s+(com\.pocketagent\.[\w.]+)\s*$", re.M)

#: 代码里的全限定引用，如 `val ref: com.pocketagent.action.ElementRef`。
#: 不是所有跨模块引用都写成 import —— :agent 就是这么用 :action 的，
#: 只看 import 会漏掉它。
FQ_REF_RE = re.compile(r"\bcom\.pocketagent\.[\w.]+")

PROJECT_DEP_RE = re.compile(r'project\(\s*"(:[\w:/-]+)"\s*\)')


def module_of(build_file: Path, android_root: Path) -> str:
    """build.gradle.kts 的路径 → Gradle 模块路径，如 :provider:api"""
    rel = build_file.parent.relative_to(android_root)
    parts = rel.parts
    return ":" + ":".join(parts)


def collect_sources(module_dir: Path) -> list[Path]:
    """主源码 + 测试源码。

    测试源码也要收 —— `./gradlew test` 会编译它们，漏了依赖一样编译不过。
    而且 `implementation` 声明的主依赖**本来就在测试编译 classpath 上**，
    所以两边可以共用同一份"已声明"集合，不需要区分 implementation 与
    testImplementation。
    """
    out: list[Path] = []
    for src_set in ("src/main/kotlin", "src/main/java",
                    "src/test/kotlin", "src/test/java"):
        d = module_dir / src_set
        if d.is_dir():
            out += [p for p in d.rglob("*.kt")]
            out += [p for p in d.rglob("*.java")]
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="检查模块间项目依赖是否漏声明")
    parser.add_argument("android_root", type=Path, help="android 目录")
    args = parser.parse_args()

    root: Path = args.android_root
    if not (root / "settings.gradle.kts").is_file():
        raise SystemExit(f"这里不像 android 工程根目录：{root}")

    build_files = sorted(
        p for p in root.rglob("build.gradle.kts")
        if "build" not in p.relative_to(root).parts[:-1]
    )
    if not build_files:
        raise SystemExit("没有找到任何 build.gradle.kts")

    # ── ① 每个模块声明了哪些包 ─────────────────────────────
    module_packages: dict[str, set[str]] = {}
    module_sources: dict[str, list[Path]] = {}

    for bf in build_files:
        mod = module_of(bf, root)
        sources = collect_sources(bf.parent)
        module_sources[mod] = sources
        pkgs: set[str] = set()
        for src in sources:
            try:
                text = src.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            pkgs.update(PACKAGE_RE.findall(text))
        module_packages[mod] = pkgs

    # 包 → 模块。一个包只应属于一个模块；冲突时报出来
    package_owner: dict[str, str] = {}
    conflicts: list[str] = []
    for mod, pkgs in module_packages.items():
        for pkg in pkgs:
            owner = package_owner.get(pkg)
            if owner is None:
                package_owner[pkg] = mod
            elif owner != mod:
                conflicts.append(f"包 {pkg} 同时出现在 {owner} 与 {mod}")

    def resolve(pkg: str) -> str | None:
        """把包名解析成模块 —— 按最长前缀匹配"""
        parts = pkg.split(".")
        for i in range(len(parts), 2, -1):
            candidate = ".".join(parts[:i])
            if candidate in package_owner:
                return package_owner[candidate]
        return None

    # ── ② 对账 ────────────────────────────────────────────
    problems: list[str] = []
    notes: list[str] = []

    for bf in build_files:
        mod = module_of(bf, root)

        # 剥注释再找 project(...) —— 否则被注释掉的依赖会被当成真依赖
        build_text = strip_comments(bf.read_text(encoding="utf-8", errors="replace"))
        declared = {d for d in PROJECT_DEP_RE.findall(build_text)}

        used: dict[str, set[str]] = defaultdict(set)  # 模块 → 触发它的引用
        for src in module_sources[mod]:
            try:
                content = src.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            # 同样剥注释：KDoc 里的类名不构成依赖
            code = strip_comments(content)

            refs = set(IMPORT_RE.findall(code))
            refs.update(FQ_REF_RE.findall(code))

            for ref in refs:
                pkg = ref.rsplit(".", 1)[0]  # 去掉类名
                target = resolve(pkg)
                if target and target != mod:
                    used[target].add(ref)

        missing = set(used) - declared
        unused = declared - set(used) - {":core:common"}

        for target in sorted(missing):
            sample = sorted(used[target])[:3]
            more = f" 等 {len(used[target])} 处" if len(used[target]) > 3 else ""
            problems.append(
                f"{mod} 缺少 {target}\n"
                f"        用到了：{', '.join(sample)}{more}\n"
                f"        在 {bf.relative_to(root)} 的 dependencies 里补：\n"
                f'            implementation(project("{target}"))'
            )

        if unused:
            notes.append(
                f"{mod} 声明了但源码里没用到：{', '.join(sorted(unused))}"
            )

    # ── ③ 输出 ────────────────────────────────────────────
    print(f"扫描 {len(build_files)} 个模块，"
          f"共 {sum(len(s) for s in module_sources.values())} 个源文件")
    print()

    if conflicts:
        print("[WARN] 同一个包被多个模块声明（映射可能不准）：")
        for c in conflicts:
            print(f"  · {c}")
        print()

    if notes:
        print("提示（不阻塞）：")
        for n in notes:
            print(f"  · {n}")
        print()

    if problems:
        print(f"[FAIL] 发现 {len(problems)} 处漏声明的模块依赖：")
        print()
        for p in problems:
            print(f"  · {p}")
            print()
        print("  这些模块在 Gradle 下编译不过，但离线测试跑器看不出来 ——")
        print("  它把所有模块塞进同一次 kotlinc 调用，跨模块引用会顺便解析成功。")
        return 1

    print("[OK] 所有跨模块引用都有对应的 project 依赖声明。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
