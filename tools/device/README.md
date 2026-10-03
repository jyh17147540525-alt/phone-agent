# tools/device —— 真机验证工具（在 Windows 上运行）

## verify-fgs.cmd / verify_fgs.py

FGS 保活批次（2026-10-03）的**一键真机验证**。**双击 `verify-fgs.cmd` 即可**。

它自动完成：构建（`gradlew :app:assembleDebug`）→ 安装 → 重开无障碍 →
启动应用 → 引导你开桥 → **关键判据**（按 Home 退后台 20 秒后 `tools/list`
应返回 200；修复前这里是 12 秒超时）→ ⑦ 支付页反向验证 → 遮蔽目击 → 写报告。

**需要你在手机上做的两个手动操作**（脚本会在对应时刻停下来等你按回车）：

1. 设置 → dsh 集成 → 点「开启能力桥」（首次弹通知权限，请允许）；
2. 按提示把手机摆到指定界面（支付页 / 带输入框的界面）。

**结果**：`tools/device/reports/fgs-verify-<时间戳>.txt`（＋同名 `.json`）。
报告**不含**桥的令牌（所有落盘文本都脱敏），可以直接转发给 Claude / workbuddy。

## 命令行

```
python tools/device/verify_fgs.py [--adb <路径>] [--skip-build] [--skip-install]
                                  [--wait <秒>] [--yes] [--local-port <端口>]
```

- `--skip-build` / `--skip-install`：跳过构建 / 安装（重跑测试时用）；
- `--yes`：不等待回车（全自动档，仍需要手机上的手动操作）；
- `--wait`：后台等待秒数（默认 20）。

## 依赖与边界

- adb 的探测 / 编码 / 超时 / 错误分类复用 `tools/p0/p0kit/adb.py`（不重复造轮子）。
- 本脚本**只验证**，不做任何 git 操作、不改仓库内容（报告文件除外）。
