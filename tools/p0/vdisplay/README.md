# 虚拟屏实验脚本（VDM 路线）

> 配套文档：`docs/无感虚拟屏方案存档与交接-v1.0.md`（**先读它，再动这里**）

这组脚本用来在真机上复现「VDM 路线」的虚拟屏行为。它们**不是产品代码**，
而是**证据生成器** —— 目的是让任何接手人都能独立验证文档里的结论，而不是只能相信文档。

---

## 为什么要有这组脚本

这份方案的结论（尤其 `flags=85003` 是最优解）**全部来自真机实验**。
如果只留文档不留脚本，接手人面对的就是一堆「据说」。

> 教训：本项目曾有一份协作组方案，主张「跨应用跳转不落主屏**机制不可达**」。
> 因为脚本留在设备上（`md5` 可核对），我们才得以**真复现**并推翻它。
> **能复现，才有资格谈对错。**

---

## 文件

| 文件 | 作用 |
|---|---|
| `src/VdCreate.java` | 协作组原始脚本。用 mode 位组合 5 个 flag（`PUBLIC`/`PRESENTATION`/`OWN_CONTENT_ONLY`/`AUTO_MIRROR`/null-surface） |
| `src/VdOwnGroup.java` | **本项目脚本**。直接传**十进制 flags**，可表达任意组合（`OWN_DISPLAY_GROUP`、`OWN_FOCUS` 等） |
| `build.ps1` | 编译打包（`javac` → `d8` → `vd.jar`）。**必须在 PowerShell 里跑** |
| `mp_display_probe.sh` | MediaProjection 副屏探测 |

---

## 编译

⚠️ 本机环境注意：`cmd /c` 会被安全策略拦截，**用 PowerShell 工具跑 `build.ps1`**。

```powershell
# 依赖：Android SDK build-tools（aapt2 / d8 / zipalign / apksigner）+ JDK 17
# JDK 路径示例：C:\Program Files\Microsoft\jdk-25.0.3.9-hotspot\bin
pwsh -File tools/p0/vdisplay/build.ps1
```

产物推到设备：

```bash
adb push vd2.jar /data/local/tmp/
adb shell "chmod 644 /data/local/tmp/vd2.jar"
```

---

## 运行

### 建屏（推荐：`flags=85003`）

```bash
adb shell "cd /data/local/tmp && CLASSPATH=/data/local/tmp/vd2.jar \
  app_process /system/bin VdOwnGroup 85003 > /data/local/tmp/vd_out.txt 2>&1 &"
sleep 5
adb shell "head -3 /data/local/tmp/vd_out.txt"
# 期望：SURFACE created / CREATED flags=85003 displayId=N name=vd-owngroup state=2
```

`flags=85003` = `PUBLIC|PRESENTATION|OWN_CONTENT_ONLY|TRUSTED|OWN_DISPLAY_GROUP|OWN_FOCUS|STEAL_TOP_FOCUS_DISABLED`

### 三条必做校验

```bash
N=<上一步的 displayId>

# ① 无障碍是否收录（唯一有效判据；uiautomator dump 测不了虚拟屏）
adb shell "dumpsys accessibility | grep 'valid display'"
# 期望：2 valid displays: 0, N

# ② flag 实际落地（确认 FLAG_PRIVATE 不在 —— 它才是无障碍的开关）
adb shell "dumpsys display" | grep -o 'DisplayDeviceInfo{"vd-[^}]*}' | grep -o 'FLAG_[A-Z_]*'

# ③ 跨应用跳转落点
adb shell "am start --display N -n com.android.settings/.Settings"
adb shell "input -d N tap <x> <y>"
adb shell "dumpsys window windows | grep -B1 'mDisplayId=N'"
```

---

## 清理（**必做**）

```bash
adb shell "kill -9 \$(pidof VdOwnGroup)"
adb shell "dumpsys display | grep -c vd-"     # 期望 0
```

> ⚠️ **虚拟屏不会随进程退出自动清理**（应用崩溃/被杀时也不会）。
> 产品化时必须实现孤儿屏检测，见交接文档 §5.4。

---

## 踩坑清单（都真踩过）

| 现象 | 原因 | 处理 |
|---|---|---|
| `IllegalArgumentException: Public display must not be marked as SHOW_WHEN_LOCKED_INSECURE` | `PUBLIC` 与 `CAN_SHOW_WITH_INSECURE_KEYGUARD`（bit 5）**互斥** | 用 `PUBLIC` 就必须去掉 bit 5 |
| 传了「去掉 `OWN_CONTENT_ONLY`」的值，dump 里它还在 | 非 `AUTO_MIRROR` 时**系统强制补上** | 别白费力气，这是设计 |
| `NO_FRAME yet` 一直刷 | 页面静止时**本就不出帧**，属正常 | 不要据此判断渲染失败 |
| `am start --display N` 没反应 | 目标 App 在主屏已是 top-most，**intent 被投给了主屏那个实例** | 先让主屏前台换成别的 App |
| 点击坐标总是偏 | HyperOS 给虚拟屏 App 加了 `Miui Caption` + **scale 1.43~3.0** | **1:1 只在首次冷启动时成立**，需实测标定 |
| 后台 `&` 起的进程被 SIGTERM | 本机 Bash 工具对后台任务的处理 | 用 `app_process` 直连方式，或前台等待 |

---

## ⚠️ 隐私警告

像素帧里可能出现**用户真实私人内容**（实测曾读到完整微信聊天列表）。

**在任何读屏代码落地之前**，必须先就位：
1. `PrivacyFilter`；
2. 帧来源校验（确认目标 App 已绘制，不能只看 display 存在）；
3. 帧内容分级（聊天 / 支付 / 验证码 → 丢弃）。

详见交接文档 §4.2。
