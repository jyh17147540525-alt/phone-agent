#!/usr/bin/env python3
"""AAR 元数据体检 —— 读出一个依赖到底要求多高的 compileSdk / AGP。

为什么需要这个
==============

AndroidX 的每个 AAR 里都有一份 `META-INF/com/android/build/gradle/aar-metadata.properties`，
里面写着 `minCompileSdk`、`minAndroidGradlePluginVersion` 之类的硬门槛。构建时
AGP 的 `checkDebugAarMetadata` 任务会逐项核对，不满足就整个构建失败：

    Dependency 'androidx.compose.ui:ui-android:1.12.0' requires libraries and
    applications that depend on it to compile against version 37 or later of
    the Android APIs.

这个报错**只在构建时**才出现，而构建要跑四分钟。等到那时候才发现"哦，这个 BOM 太新了"，
一轮迭代就白烧了。

本脚本的作用：把门槛值在**下载依赖之前**读出来。升级任何 AndroidX 依赖前先跑一遍，
就知道该不该连带升 compileSdk / AGP。

用法：
    # 体检一组具体坐标
    python tools/verify/check_aar_metadata.py androidx.compose.ui:ui-android:1.12.0

    # 或从版本目录里挑一个 BOM 看它锁定的 compose 版本（见 --bom）
    python tools/verify/check_aar_metadata.py --bom 2026.08.00

退出码：0 = 全部读到了；1 = 有下载/解析失败。
"""

from __future__ import annotations

import io
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

GOOGLE_MAVEN = "https://dl.google.com/dl/android/maven2"
MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
METADATA_PATH = "META-INF/com/android/build/gradle/aar-metadata.properties"

REPOS = [GOOGLE_MAVEN, MAVEN_CENTRAL]


def fetch(url: str, timeout: int = 90) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "pocketagent-verify/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def read_aar_metadata(group: str, artifact: str, version: str) -> dict[str, str] | None:
    """下载 aar 并读出 aar-metadata.properties。找不到返回 None。"""
    path = group.replace(".", "/")
    name = f"{artifact}-{version}.aar"

    last_error: Exception | None = None
    for repo in REPOS:
        url = f"{repo}/{path}/{artifact}/{version}/{name}"
        try:
            data = fetch(url)
        except urllib.error.HTTPError as e:
            last_error = e
            continue
        except Exception as e:  # noqa: BLE001
            last_error = e
            continue

        try:
            with zipfile.ZipFile(io.BytesIO(data)) as zf:
                raw = zf.read(METADATA_PATH).decode("utf-8", "replace")
        except KeyError:
            return {}
        except Exception as e:  # noqa: BLE001
            last_error = e
            continue

        out: dict[str, str] = {}
        for line in raw.splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
        return out

    if last_error is not None:
        raise last_error
    return None


def resolve_bom(bom_version: str, artifact_filter: str | None = None) -> list[tuple[str, str]]:
    """读 compose-bom 的 pom，返回它锁定的 (group:artifact, version) 列表。"""
    url = (
        f"{GOOGLE_MAVEN}/androidx/compose/compose-bom/{bom_version}/"
        f"compose-bom-{bom_version}.pom"
    )
    root = ET.fromstring(fetch(url))

    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    out: list[tuple[str, str]] = []
    for dep in root.findall(".//m:dependency", ns):
        g = dep.findtext("m:groupId", default="", namespaces=ns)
        a = dep.findtext("m:artifactId", default="", namespaces=ns)
        v = dep.findtext("m:version", default="", namespaces=ns)
        if not (g and a and v):
            continue
        if artifact_filter and artifact_filter not in a:
            continue
        out.append((f"{g}:{a}", v))
    return out


def summarize(label: str, meta: dict[str, str]) -> tuple[bool, str]:
    """返回 (是否达标, 说明)。达标判据：minCompileSdk <= 36 且 AGP 门槛 <= 8.13.0。"""
    min_sdk = meta.get("minCompileSdk")
    min_agp = meta.get("minAndroidGradlePluginVersion") or meta.get(
        "minAndroidGradlePluginVersionForKotlinMultiplatform"
    )

    parts = []
    if min_sdk:
        parts.append(f"minCompileSdk={min_sdk}")
    if min_agp:
        parts.append(f"minAGP={min_agp}")
    if not parts:
        return True, "无门槛声明"

    ok = True
    if min_sdk and int(min_sdk) > 36:
        ok = False
    if min_agp:
        m = re.match(r"(\d+)\.(\d+)", min_agp)
        if m and (int(m.group(1)), int(m.group(2))) > (8, 13):
            ok = False

    return ok, "  ".join(parts)


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 1

    targets: list[tuple[str, str]] = []  # (坐标, 版本)

    if args[0] == "--bom":
        bom_version = args[1]
        filt = args[2] if len(args) > 2 else None
        deps = resolve_bom(bom_version, filt)
        print(f"compose-bom {bom_version} 锁定 {len(deps)} 个依赖"
              + (f"（过滤：{filt}）" if filt else ""))
        print()
        # 只体检 compose 的核心件，全量太慢
        keep = ("ui", "runtime", "material3", "foundation")
        deps = [d for d in deps if any(k in d[0] for k in keep)]
        for coord, ver in deps:
            targets.append((coord, ver))
    else:
        for raw in args:
            pieces = raw.split(":")
            if len(pieces) != 3:
                print(f"[SKIP] 坐标格式应为 group:artifact:version，收到：{raw}")
                continue
            targets.append((f"{pieces[0]}:{pieces[1]}", pieces[2]))

    failures = 0
    rows: list[tuple[str, str, str, bool]] = []

    for coord, ver in targets:
        group, artifact = coord.split(":", 1)
        label = f"{coord}:{ver}"
        try:
            meta = read_aar_metadata(group, artifact, ver)
        except Exception as e:  # noqa: BLE001
            rows.append((label, "读取失败", str(e)[:60], False))
            failures += 1
            continue

        if meta is None:
            rows.append((label, "找不到", "两个仓库都没有这个 aar", False))
            failures += 1
            continue

        ok, desc = summarize(label, meta)
        rows.append((label, desc, "", ok))
        if not ok:
            failures += 1

    width = max((len(r[0]) for r in rows), default=10)
    print(f"{'依赖'.ljust(width)}  判定  {'门槛'}")
    print("-" * (width + 30))
    for label, desc, err, ok in rows:
        mark = "✅" if ok else "❌"
        tail = f"{desc}  {err}".strip()
        print(f"{label.ljust(width)}  {mark}   {tail}")

    print()
    if failures:
        print(f"[FAIL] {failures} 项不满足当前工具链（compileSdk 36 / AGP 8.13.0）或读取失败。")
        return 1
    print("[OK] 全部依赖与当前工具链兼容。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
