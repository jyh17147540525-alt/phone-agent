#!/usr/bin/env bash
# MP 虚拟屏 + am stack move-task 可行性探测
# 目的：验证 L2「正常启动后搬任务」是否真的不可用
export MSYS_NO_PATHCONV=1
ADB="D:/AndroidDev/sdk/platform-tools/adb.exe"

echo "=========================================="
echo "步骤 0: 当前 display 列表"
echo "=========================================="
$ADB shell dumpsys display 2>/dev/null | grep -oE 'mDisplayId=[0-9]+' | sort -u

echo ""
echo "=========================================="
echo "步骤 1: 找微信 taskId（当前在 display 0）"
echo "=========================================="
$ADB shell am stack list 2>&1 | grep -E "taskId=.*com.tencent.mm"

echo ""
echo "=========================================="
echo "步骤 2: am display 子命令帮助"
echo "=========================================="
$ADB shell am display help 2>&1 | head -20
