#!/usr/bin/env python3
"""
把一个插件源仓库打包成可托管的静态站点。

═══════════════════════════════════════════════════════════════
  这个脚本解决什么问题
═══════════════════════════════════════════════════════════════

"插件市场没有服务端"这句话的另一半是：**得有人把静态 JSON 生成出来**。
本脚本把 `plugins-src/` 下的插件目录打成 `.pagent` 包，并生成 `index.json`。

输出目录（默认 `site/plugins/`）就是可以直接扔到 GitHub Pages 上的内容：

    site/plugins/index.json              ← 客户端订阅的就是这个地址
    site/plugins/skip-splash-ad-1.0.0.pagent
    site/plugins/focus-guard-1.0.0.pagent

═══════════════════════════════════════════════════════════════
  为什么打包必须是「可复现」的
═══════════════════════════════════════════════════════════════

zip 格式默认会把**当前时间**写进每个条目的头部。这意味着同一个插件目录，
今天打包和明天打包会得到**不同的字节**，于是 sha256 不同。

后果不是"不优雅"，而是实打实的故障：

  · 作者改了规则、重新打包、只提交了 .pagent 却忘了提交 index.json
    → 客户端下载下来的文件哈希对不上，**直接拒绝安装**，报"文件内容与市场
      声明的哈希不一致，可能是下载途中被替换"。用户会以为源被黑了。
  · 每次 CI 重建都产生一个新版本的文件，CDN 缓存全部失效
  · `--check` 模式无从实现 —— 没有基线可比

所以这里把时间戳、权限位、条目顺序、压缩级别**全部固定**，
让"同样的输入 → 同样的字节 → 同样的哈希"成立。

═══════════════════════════════════════════════════════════════
  用法
═══════════════════════════════════════════════════════════════

    python build_source.py                     # 打包 + 生成 index.json
    python build_source.py --check             # 只校验现有产物是否是最新的
    python build_source.py --base-url https://example.com/plugins
    python build_source.py --date 2026-09-21   # 固定 updatedAt，便于可复现构建

`--check` 适合放进 CI：产物与源不一致就退出码非 0，防止有人只改源不重新打包。
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import sys
import tempfile
import zipfile
from pathlib import Path

# ═══════════════════════════════════════════════════════════════
#  常量
# ═══════════════════════════════════════════════════════════════

HERE = Path(__file__).resolve().parent
SRC_DIR = HERE / "plugins-src"
DEFAULT_OUT = HERE / "site" / "plugins"

DEFAULT_BASE_URL = "https://pocketagent-community.github.io/plugins"

SOURCE_NAME = "PocketAgent 社区源"
SOURCE_AUTHOR = "PocketAgent 社区"

# 必须与 PluginManifest.CURRENT_API_VERSION 一致。
# 不一致时客户端会拒绝加载整个源，所以这个值写死在这里、不自动探测 ——
# 升 API 版本是个需要人决策的动作，不该由脚本悄悄替你做。
API_VERSION = 1

# 以 `_` 开头的目录是模板或草稿，不参与打包。
# `_template/` 里的 id 是占位符（community.yourname.your-plugin），
# 真打进去会让整个源校验失败。
SKIP_PREFIX = "_"

# ═══════════════════════════════════════════════════════════════
#  可复现打包
# ═══════════════════════════════════════════════════════════════

#: 固定的 zip 条目时间戳。取 1980-01-01 是因为这是 zip 格式能表示的最小时间
#: （DOS 时间从 1980 年起算），任何解压器都能正确处理。
FIXED_DATE_TIME = (1980, 1, 1, 0, 0, 0)

#: 固定的压缩级别。0-9，9 最省体积。必须固定，否则 zlib 换个级别字节就变了。
FIXED_COMPRESS_LEVEL = 9


def pack_bundle(plugin_dir: Path, out_path: Path) -> bytes:
    """
    把一个插件目录打成 `.pagent`（本质是 zip），返回文件字节。

    ⚠️ 包内**不含顶层目录**：`plugin.json` 必须在 zip 根，因为安装器是
        `File(staging, "plugin.json")` 去找的。多套一层目录会直接安装失败，
        报"插件包里没有找到 plugin.json"。
    """
    files = sorted(p for p in plugin_dir.rglob("*") if p.is_file())
    if not files:
        raise ValueError(f"{plugin_dir.name}：目录里没有任何文件")

    buf = out_path.open("wb")
    try:
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
            for f in files:
                arcname = f.relative_to(plugin_dir).as_posix()
                data = f.read_bytes()

                info = zipfile.ZipInfo(arcname, date_time=FIXED_DATE_TIME)
                info.compress_type = zipfile.ZIP_DEFLATED
                # 固定权限位与来源系统，否则同一份代码在 Windows 与 Linux 上
                # 打出来的字节不同（external_attr 编码方式不一样）
                info.external_attr = 0o644 << 16
                info.create_system = 3  # 3 = Unix
                zf.writestr(info, data, compresslevel=FIXED_COMPRESS_LEVEL)
    finally:
        buf.close()

    return out_path.read_bytes()


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# ═══════════════════════════════════════════════════════════════
#  源目录读取
# ═══════════════════════════════════════════════════════════════

class PluginSourceError(Exception):
    """源目录本身有问题 —— 这是作者的错，必须报出来而不是跳过"""


def load_plugin(plugin_dir: Path) -> dict:
    """
    读取并**初步**检查一个插件目录。

    ⚠️ 这里刻意**不做**完整校验。能力白名单、id 格式、禁止前缀、入口扩展名
       这些规则归 Kotlin 侧的 `PluginValidator` 管，而且已经有单测覆盖
       （`PluginValidatorTest` + `ExampleSourceTest`）。

       在 Python 里再实现一遍 = 同一套规则维护两份。两份一旦漂移，
       最糟的情况是脚本放行了校验器会拒绝的插件 —— 那时用户拿到的是
       "打包成功、安装失败"，比一开始就报错更难查。

       所以这里只检查**打包这件事本身需要的前提**：
         · plugin.json 存在且是合法 JSON 对象
         · 建索引需要的字段都在
         · entry 指向的文件真的在包里（否则装上去就是坏的）
    """
    manifest_path = plugin_dir / "plugin.json"
    if not manifest_path.is_file():
        raise PluginSourceError(f"{plugin_dir.name}：缺少 plugin.json")

    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        raise PluginSourceError(f"{plugin_dir.name}：plugin.json 不是合法 JSON —— {e}") from e

    if not isinstance(manifest, dict):
        raise PluginSourceError(f"{plugin_dir.name}：plugin.json 的顶层必须是一个对象")

    for key in ("id", "name", "version", "level", "capabilities", "entry"):
        if key not in manifest:
            raise PluginSourceError(f"{plugin_dir.name}：plugin.json 缺少必填字段「{key}」")

    if not isinstance(manifest["capabilities"], list):
        raise PluginSourceError(f"{plugin_dir.name}：capabilities 必须是数组")

    entry = manifest["entry"]
    if not isinstance(entry, str) or not (plugin_dir / entry).is_file():
        raise PluginSourceError(
            f"{plugin_dir.name}：entry 指向的「{entry}」在包里不存在 —— "
            f"装上去会是一个加载不了的插件"
        )

    return manifest


def build_index_entry(manifest: dict, download_url: str, digest: str) -> dict:
    """
    由清单生成索引条目。

    ⚠️ `sha256` 是**整个 .pagent 文件**的哈希，不是里面某个文件的哈希。
       客户端在解压之前先校验它（见 PluginInstaller 的"顺序即安全"），
       所以这里算错就等于所有插件都装不上。
    """
    entry = {
        "id": manifest["id"],
        "name": manifest["name"],
        "version": manifest["version"],
        "level": manifest["level"],
        "capabilities": manifest["capabilities"],
        "downloadUrl": download_url,
        "sha256": digest,
    }

    # 可选字段：有才写，没有就留给客户端默认值（null）。
    # 空字符串是**不能**写的 —— 客户端会把它当成"有描述，只是内容是空的"，
    # 界面上就会出现一个空白的描述行。
    if manifest.get("targetApps"):
        entry["targetApps"] = manifest["targetApps"]
    if manifest.get("description"):
        entry["description"] = manifest["description"]
    if manifest.get("author"):
        entry["author"] = manifest["author"]

    return entry


# ═══════════════════════════════════════════════════════════════
#  主流程
# ═══════════════════════════════════════════════════════════════

def build(out_dir: Path, base_url: str, date_str: str) -> dict[str, bytes]:
    """打包全部插件并生成 index.json，返回 {相对文件名: 字节}"""
    if not SRC_DIR.is_dir():
        raise SystemExit(f"找不到源目录：{SRC_DIR}")

    plugin_dirs = sorted(
        d for d in SRC_DIR.iterdir()
        if d.is_dir() and not d.name.startswith(SKIP_PREFIX)
    )
    if not plugin_dirs:
        raise SystemExit(f"{SRC_DIR} 下没有可打包的插件目录")

    out_dir.mkdir(parents=True, exist_ok=True)
    produced: dict[str, bytes] = {}
    entries: list[dict] = []

    print(f"打包 {len(plugin_dirs)} 个插件 → {out_dir}")
    print()

    for plugin_dir in plugin_dirs:
        manifest = load_plugin(plugin_dir)

        filename = f"{plugin_dir.name}-{manifest['version']}.pagent"
        target = out_dir / filename

        data = pack_bundle(plugin_dir, target)
        digest = sha256_hex(data)
        produced[filename] = data

        download_url = f"{base_url.rstrip('/')}/{filename}"
        entries.append(build_index_entry(manifest, download_url, digest))

        size_kb = len(data) / 1024
        print(f"  ✓ {manifest['name']}  {manifest['version']}  "
              f"{size_kb:6.1f} KB  sha256 {digest[:12]}…")

    index = {
        "name": SOURCE_NAME,
        "author": SOURCE_AUTHOR,
        "updatedAt": date_str,
        "apiVersion": API_VERSION,
        "plugins": entries,
    }
    index_bytes = (json.dumps(index, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    produced["index.json"] = index_bytes

    # 真写一份，让 --check 之外的行为符合直觉（跑完就能直接看到产物）
    (out_dir / "index.json").write_bytes(index_bytes)

    print()
    print(f"  ✓ index.json  已写入 {len(entries)} 个条目")
    return produced


def normalize_index(data: bytes) -> bytes:
    """
    把 index.json 里的 `updatedAt` 抹平，用于 --check 比对。

    `updatedAt` 是"这个源最后一次打包的日期"，它**每天都会变**。
    如果把它算进比对，`--check` 会在第二天无理由地失败 ——
    一个每天都会误报的检查，等于没有检查（这个教训在
    tools/verify/check_kt_quotes.py 上已经吃过一次：135 报 1 中的检查
    会被所有人直接忽略）。

    所以比对时只看**结构性内容**：条目、版本、哈希、下载地址。
    """
    try:
        obj = json.loads(data)
    except json.JSONDecodeError:
        return data
    obj.pop("updatedAt", None)
    return json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=True).encode("utf-8")


def check(out_dir: Path, base_url: str, date_str: str) -> int:
    """比对现有产物与源是否一致。返回退出码。"""
    if not out_dir.is_dir():
        print(f"[FAIL] 产物目录不存在：{out_dir}")
        print("       先跑一次不带 --check 的构建。")
        return 1

    with tempfile.TemporaryDirectory() as tmp:
        fresh = build(Path(tmp), base_url, date_str)

        existing = {p.name for p in out_dir.iterdir() if p.is_file()}
        expected = set(fresh)

        stale = sorted(existing - expected)
        missing = sorted(expected - existing)

        problems: list[str] = []

        for name in stale:
            problems.append(f"多余文件：{name}（源里已经没有它了，应该删掉）")
        for name in missing:
            problems.append(f"缺少文件：{name}（源里有，但产物里没有）")

        for name in sorted(expected & existing):
            disk = (out_dir / name).read_bytes()
            want = fresh[name]
            if name == "index.json":
                if normalize_index(disk) != normalize_index(want):
                    problems.append(
                        "index.json 与源不一致 —— 有人改了插件但没重新打包。\n"
                        "       这一条最危险：哈希对不上会让客户端拒绝安装，"
                        "而报错信息会说「可能是下载途中被替换」。"
                    )
            elif disk != want:
                problems.append(
                    f"{name} 的字节与源不一致（打包不可复现，或源已改动）"
                )

    if problems:
        print("[FAIL] 产物与源不一致：")
        print()
        for p in problems:
            print(f"  · {p}")
        print()
        print("  修复：python build_source.py")
        return 1

    print(f"[OK] 产物与源一致（{len(fresh)} 个文件）")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="把插件源打包成可托管的静态站点",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT,
                        help=f"输出目录（默认 {DEFAULT_OUT.relative_to(HERE)}）")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                        help="插件文件的下载地址前缀。必须是最终用户能访问到的绝对地址")
    parser.add_argument("--date", default=None,
                        help="写入 index.json 的 updatedAt（YYYY-MM-DD）。默认取今天")
    parser.add_argument("--check", action="store_true",
                        help="只校验产物是否最新，不修改任何文件")
    args = parser.parse_args()

    date_str = args.date or dt.date.today().isoformat()

    if args.check:
        return check(args.out, args.base_url, date_str)

    build(args.out, args.base_url, date_str)

    print()
    print("下一步：把输出目录的内容放到静态托管上，让它可以通过下面这个地址访问 ——")
    print(f"  {args.base_url.rstrip('/')}/index.json")
    print()
    print("⚠️ 这个地址必须与客户端内置的 BUILTIN_SOURCE_URL 一致，否则用户装好应用")
    print("   打开市场看到的还是「源加载失败」。见 android/.../AppContainer.kt。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
