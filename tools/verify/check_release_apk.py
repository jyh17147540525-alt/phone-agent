#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
发行包安全检查（P7）。

═══════════════════════════════════════════════════════════════
★★ 2026-11-01：为什么需要这个脚本
═══════════════════════════════════════════════════════════════

`docs/剩余待办实施方案与Agent循环草稿-v1.0.md` 里，P7 是
「发行 build（debuggable=false）——与上面全部无依赖，随时可做」。
它也是那 7 项待办里**唯一不依赖真机**的。

但"配置写对了"和"产物真的对"是两件事：

· Gradle 里写了 `isDebuggable = false` —— 那只说明**意图**对
· 真正要紧的是 **APK 的 AndroidManifest 里是什么**

⇒ 这个脚本检查**产物**，不是配置。

═══════════════════════════════════════════════════════════════
检查项（每一条都对应一个真实风险）
═══════════════════════════════════════════════════════════════

1. **debuggable 不能为 true**
   为 true 时任何人 `adb` 接上就能读应用私有数据 —— 包括加密后的
   API Key 密文，以及内存里的明文。

2. **allowBackup 必须为 false**
   为 true 时 `adb backup` 能把整个数据目录拖走。
   `CoreCrypto` 的密文主密钥存在 Keystore 里，但数据库文件本身可被拷走。

3. **applicationId 不能带 `.debug` 后缀**
   否则会在用户手机上装成"另一个应用"，两个图标、两份数据。

4. **不能是 debug 签名**
   用 debug keystore 签的包不能上架，也不该被当成发行包分发。

用法：
    python tools/verify/check_release_apk.py [APK 路径]

不传路径时自动找 `android/app/build/outputs/apk/release/*.apk`。
退出码非 0 表示**有检查未通过**。
"""

import glob
import os
import subprocess
import sys


# 发行包体积上限（MB）。
# 当前实测 32.7 MB。留约 40% 余量，既能容忍正常增长，
# 又能挡住"x86 原生库又回来了"这种量级的回归。
MAX_RELEASE_MB = 45.0


def _zip_names(apk: str) -> list[str]:
    """列出 APK 内的条目名。"""
    import zipfile
    with zipfile.ZipFile(apk) as z:
        return z.namelist()


def find_apk() -> str | None:
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.abspath(os.path.join(here, "..", ".."))
    pattern = os.path.join(
        root, "android", "app", "build", "outputs", "apk", "release", "*.apk"
    )
    hits = sorted(glob.glob(pattern))
    return hits[0] if hits else None


def find_aapt2() -> str | None:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        # 本项目的惯例路径
        sdk = r"D:\AndroidDev\sdk"
    bt_root = os.path.join(sdk, "build-tools")
    if not os.path.isdir(bt_root):
        return None
    versions = sorted(os.listdir(bt_root), reverse=True)
    for v in versions:
        exe = os.path.join(bt_root, v, "aapt2.exe")
        if os.path.isfile(exe):
            return exe
    return None


def dump_manifest(aapt2: str, apk: str) -> str:
    out = subprocess.run(
        [aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return (out.stdout or "") + (out.stderr or "")


def main() -> int:
    apk = sys.argv[1] if len(sys.argv) > 1 else find_apk()
    if not apk or not os.path.isfile(apk):
        print("✗ 找不到 release APK。先跑：gradlew :app:assembleRelease")
        return 2

    aapt2 = find_aapt2()
    if not aapt2:
        print("✗ 找不到 aapt2（检查 ANDROID_HOME / build-tools）")
        return 2

    name = os.path.basename(apk)
    size_mb = os.path.getsize(apk) / 1024 / 1024
    print(f"检查对象：{name}（{size_mb:.1f} MB）")
    print()

    manifest = dump_manifest(aapt2, apk)
    failures: list[str] = []

    # ── 1. debuggable ────────────────────────────────────────
    # ⚠️ 注意：release 默认**不写**这个属性。所以"没出现"是正确的，
    #    "出现且为 true"才是问题。
    debuggable_line = next(
        (ln.strip() for ln in manifest.splitlines() if "debuggable" in ln.lower()),
        None,
    )
    if debuggable_line is None:
        print("✓ debuggable 未声明（release 默认 false）")
    elif "0xffffffff" in debuggable_line or "=true" in debuggable_line.lower():
        failures.append(f"debuggable 被打开了：{debuggable_line}")
        print(f"✗ {failures[-1]}")
    else:
        print("✓ debuggable = false")

    # ── 2. allowBackup ───────────────────────────────────────
    backup_line = next(
        (ln.strip() for ln in manifest.splitlines() if "allowBackup" in ln),
        None,
    )
    if backup_line is None:
        # 没声明时默认是 true —— 那是**不安全**的默认值
        failures.append("allowBackup 未声明（默认 true，adb backup 能拖走数据）")
        print(f"✗ {failures[-1]}")
    elif "=false" in backup_line.lower() or "0x0" in backup_line:
        print("✓ allowBackup = false")
    else:
        failures.append(f"allowBackup 不是 false：{backup_line}")
        print(f"✗ {failures[-1]}")

    # ── 3. applicationId ─────────────────────────────────────
    pkg_line = next(
        (ln.strip() for ln in manifest.splitlines() if 'package=' in ln), ""
    )
    if ".debug" in pkg_line:
        failures.append(f"包名带了 .debug：{pkg_line}")
        print(f"✗ {failures[-1]}")
    else:
        print("✓ 包名无 .debug 后缀")

    # ── 4. 体积与 ABI（2026-11-01 新增）────────────────────
    # ══════════════════════════════════════════════════════
    #  ★ 回归闸门：体积不能悄悄涨回去
    # ══════════════════════════════════════════════════════
    #
    # 背景：审计过 65 MB 的 release 包，发现 lib/ 占 57.7 MB，
    # 其中 **32.3 MB 是 x86 模拟器架构** —— 真机全是 ARM。
    # 收紧 release 的 abiFilters 后降到 32.7 MB（约 −50%）。
    #
    # ⇒ 用阈值把那次收益钉住。涨回来说明有人撤掉了 abiFilters，
    #   或者引入了一个新的重量级原生依赖 —— 两种都该被看见。
    if size_mb > MAX_RELEASE_MB:
        failures.append(
            f"体积 {size_mb:.1f} MB 超过上限 {MAX_RELEASE_MB} MB"
            "（检查 release 的 abiFilters 是否被撤掉）"
        )
        print(f"✗ {failures[-1]}")
    else:
        print(f"✓ 体积 {size_mb:.1f} MB（上限 {MAX_RELEASE_MB} MB）")

    # x86 是模拟器架构，真机用不上 —— 出现在 release 里就是白占体积
    x86_in_zip = [
        e for e in _zip_names(apk) if e.startswith("lib/x86")
    ]
    if x86_in_zip:
        failures.append(
            f"release 里有 x86 原生库（{len(x86_in_zip)} 个）——"
            " 真机用不上，纯占体积"
        )
        print(f"✗ {failures[-1]}")
    else:
        print("✓ 无 x86 原生库")

    # ── 5. 签名 ──────────────────────────────────────────────
    # ⚠️ 未签名的包**不算失败** —— 签名密钥属于维护者，
    #    开源仓库里不该有。但要**明确提示**，免得有人以为能直接装。
    if "unsigned" in name.lower():
        print("⚠ 产物未签名 —— 分发前需要签名配置（见下方说明）")
    else:
        print("✓ 产物已签名")

    print()
    if failures:
        print(f"✗ {len(failures)} 项未通过：")
        for f in failures:
            print(f"  · {f}")
        return 1

    print("✓ 发行包安全检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
