# Claude 桌面版工作区 VM 修复方案 v1.0

> **执行者**：本机另一名具备完整命令权限的助手（workbuddy）。
> **背景**：Claude 桌面版自身的隔离沙盒（Linux VM）起不来，而 Claude 助手没有命令执行权，修不了自己 → 由你代修。
> 全部命令为 Windows PowerShell；标注「管理员」的步骤需管理员权限。**先读第 8 节禁令，再跑第 5 节只读诊断，最后按第 6 节动手。**

---

## 1. 一句话

Claude 桌面版的**工作区 Linux VM** 启动失败。直接原因：VM 磁盘目录里的文件（当前是 `sessiondata.vhdx`）**带多条硬链接**，被应用的安全校验拒绝。
修复目标只有一个：**把每个 VM 文件恢复成「单链接的普通物理文件」**——任何形式的链接都不能留；`sessiondata.vhdx` 可以直接弃旧重建。

## 2. 现状与判读

报错原文（按时间顺序，第二条为当前状态）：

1. `failed to set VHDX path: VHDX file not found: C:\Users\Mikel.Daniela\AppData\Local\Claude-3p\vm_bundles\claudevm.bundle\rootfs.vhdx`
2. `configure: C:\Users\Mikel.Daniela\AppData\Local\Claude-3p\vm_bundles\claudevm.bundle\sessiondata.vhdx has multiple hard links, refusing to open`

判读：

- 路径 `…\Claude-3p\vm_bundles\claudevm.bundle\` 就是应用期望的目录（它在里面找到了文件，只是拒绝了）。
- 应用的校验规则（已确证）：**硬链接数 > 1 的文件 → 拒绝**（原话 `has multiple hard links, refusing to open`）；**symlink / junction → 同样拒绝**。
  ⇒ 社区那种「用 `mklink /H` 把文件接到另一条路径」的修法在这个应用面前必然失败，**这次的坑很可能就是链接式修复留下的**。
- 报错 1→2 的变化说明该目录此前已被动过（rootfs 从"找不到"变成不再报缺失）——所以修复时要**逐个文件检查**，不要只处理报错点名的那个。
- 参考（外部，已核实）：
  - GitHub issue #69378：同类错误（`has multiple hard links` / `is a symlink or junction`）；其中有人实测**物理复制（不用任何链接）**可让 VM 正常启动。
    https://github.com/anthropics/claude-code/issues/69378
  - GitHub issue #93518：相邻失败形态（VHDX not found + junction 被拒）。
    https://github.com/anthropics/claude-code/issues/93518

## 3. 目标终态

`C:\Users\Mikel.Daniela\AppData\Local\Claude-3p\vm_bundles\claudevm.bundle\` 下：

- 应用所需的每个 VM 文件（至少 `rootfs.vhdx`、`sessiondata.vhdx`，以诊断实际清单为准）：
  **存在于该目录 + 全机只有这一条链接**（`fsutil hardlink list` 只输出一行）。
- 目录链（bundle 目录及其上级 `vm_bundles`、`Claude-3p`、`Local`）上**没有 junction / symlink**。
- `sessiondata.vhdx` 例外规则：允许弃旧、原地新建空白 VHDX（会话数据，可弃）。
- `rootfs.vhdx` 等**不可丢内容**：只清理链接或补独立副本，**绝不能把最后一条链接也删掉**（那是数据本身）。

## 4. 前置操作

1. 看磁盘余量（后面可能需要在别处生成 GB 级副本）：`Get-PSDrive C | Select Name, Free`
2. **完全退出 Claude 桌面版**：托盘图标 → 退出；确认任务管理器里 Claude 相关进程都没了。
   ⚠️ 生成本方案的那个 Claude 会话会随之中断——这是预期行为，修完重开即可。
3. （管理员）找到并停止 VM 服务：
   ```powershell
   Get-Service | Where-Object { "$($_.Name) $($_.DisplayName)" -match "owork|laude" } | Select Name, Status, DisplayName
   Stop-Service "<上一步列出的服务名>"    # 可能是 CoworkVMService，以枚举结果为准
   ```

## 5. 只读诊断（不改任何东西，先跑完再动手）

```powershell
$b = "C:\Users\Mikel.Daniela\AppData\Local\Claude-3p\vm_bundles\claudevm.bundle"

"===== 1) bundle 目录内容 ====="
Get-ChildItem -LiteralPath $b -Force | Select Name, Length, LastWriteTime, Mode | Format-Table -AutoSize

"===== 2) 目录链 reparse 检查（Junction/Symlink）====="
$p = $b
for ($i = 0; $i -lt 4; $i++) { "--- $p"; fsutil reparsepoint query "$p" 2>&1 | Select -First 3; $p = Split-Path -Parent $p }

"===== 3) 每个文件的全部硬链接 ====="
Get-ChildItem -LiteralPath $b -File -Force | ForEach-Object { "--- $($_.Name)"; fsutil hardlink list "$($_.FullName)" }

"===== 4) 全用户找其它 Claude 相关 .vhdx/.vhd 副本（可能要 1-3 分钟）====="
Get-ChildItem "$env:USERPROFILE\AppData" -Recurse -Force -ErrorAction SilentlyContinue -Include *.vhdx,*.vhd |
  Where-Object { $_.FullName -match "Claude" } | Select FullName, Length, LastWriteTime | Format-Table -AutoSize

"===== 5) 候选服务 ====="
Get-Service | Where-Object { "$($_.Name) $($_.DisplayName)" -match "owork|laude" } | Select Name, Status, DisplayName | Format-Table -AutoSize
```

## 6. 判读与修复（先停应用与服务，再操作）

**情形 A：某文件链接数 = 1 且在 canonical 目录** → 无需处理。

**情形 B：某文件有多条链接**（`fsutil hardlink list` 输出多行）：
原则：**只保留 canonical 目录（`$b` 下）那一条，其余名字全部删除**。
删除链接名 ≠ 删除数据——只要还剩至少一条链接，数据就还在。只删 fsutil 列出的路径，不要手打猜测路径：

```powershell
fsutil hardlink list "$b\sessiondata.vhdx"        # 先看：哪几条
Remove-Item -LiteralPath "<非 $b 下 的 多 余 路 径>"  # 逐条删，别删 $b 下那条
fsutil hardlink list "$b\sessiondata.vhdx"        # 复核：应只剩一行
```
删不动（占用/只读）：确认应用与服务都已停；必要时 `attrib -R "<文件>"` 后重试。

**情形 C：`sessiondata.vhdx` 修完仍被拒，或想一步到位** → 弃旧重建（参考修复记录里的做法）：
```powershell
# 1) 把它每个链接名都删掉（文件彻底消失；会话数据可弃，这是唯一允许删光的文件）
# 2) 新建空白 1GB 动态 VHDX：
New-VHD -Path "$b\sessiondata.vhdx" -SizeBytes 1GB -Dynamic
# 若没有 New-VHD（无 Hyper-V 模块）→ 用 diskpart（管理员）：
#    写 dp.txt，一行：
#    create vdisk file="C:\Users\Mikel.Daniela\AppData\Local\Claude-3p\vm_bundles\claudevm.bundle\sessiondata.vhdx" maximum=1024 type=expandable
#    执行：diskpart /s dp.txt
```

**情形 D：某文件在 canonical 目录缺失** → 从诊断第 4 步找到的副本**物理复制**过来（`Copy-Item` 产生的就是独立单链接文件）：
```powershell
Copy-Item -LiteralPath "<找到的副本>" -Destination "$b\<文件名>"
```
全机都没有副本 → 第 9 节兜底。

**情形 E：目录链上某级是 junction/symlink**（诊断第 2 步输出不是 "is not a reparse point"）→ 少见，先取证（保存 reparse 输出），再把内容**物理复制**到目标位置、删除 junction、换成普通目录；做完重新诊断。

## 7. 验证

```powershell
$b = "C:\Users\Mikel.Daniela\AppData\Local\Claude-3p\vm_bundles\claudevm.bundle"
Get-ChildItem -LiteralPath $b -File -Force |
  ForEach-Object { "{0}: {1} link(s)" -f $_.Name, @(fsutil hardlink list "$($_.FullName)").Count }
# 期望：每个文件都是 1 link
```

1. 每个文件链接数 = 1 ✓
2. 启动服务（管理员 `Start-Service "<服务名>"`）或直接重开 Claude 桌面版。
3. **端到端判据：在 Claude 里让它执行一条命令（如 `echo ok`）——能返回输出才算修好。**
   ⚠️ 别拿"应用没报错"当判据——它区分不了成功与失败（这个项目的既有纪律：证据必须能区分成败）。
4. 若出现**新的**报错：全文拿回来，按同一逻辑处理——说"找不到 X 路径"就在 X 路径放**物理副本**；说"谁是链接"就把链接换成物理文件。循环到第 3 步通过为止。

## 8. 禁令（本次问题的成因，别再犯）

- 禁止一切链接式修复：`mklink`（含 /H、/J）、`New-Item -ItemType HardLink/SymbolicLink/Junction`、junction 工具、任何"镜像路径"脚本。
- 禁止用会保留/重放链接的备份、同步、去重工具去"恢复"该目录；普通 `Copy-Item` 复制是安全的。
- 别动 `rootfs.vhdx` 的**最后一条链接**；`sessiondata.vhdx` 是唯一允许删除重建的文件。
- 所有文件操作都在应用与服务停止后进行。

## 9. 兜底

关键文件全机无副本、或内容可疑（如 0 字节）：**先复制**整个 `vm_bundles` 目录留档（复制，不要移动），然后走应用自身的修复通道——检查/更新 Claude 桌面版到最新版（此类 VM 问题属应用侧 bug，新版可能已修），必要时重装以获得全新 bundle。
官方文档：https://claude.com/docs/third-party/claude-desktop/overview
