#!/bin/bash
# 1.4.6c 真机验证 —— 阶段 4：确认用户原有配置未被破坏 + 清理本次实验产物
set -u
exec > /root/pa-stage4-out.txt 2>&1

export DSH_HOME=/root/.dsh
export PATH="/usr/local/bin:$PATH"

echo "###################### 1. 配置完整性 ######################"
echo "--- settings.yaml（应与原始一致，无 llm-deepseek 段）---"
cat "$DSH_HOME/settings.yaml"
echo "--- 有无残留的 llm-deepseek 段（应为 0）---"
grep -c "^llm-deepseek:" "$DSH_HOME/settings.yaml" || true
echo "--- .credentials.yaml 结构（脱敏）---"
sed -e 's/sk-[A-Za-z0-9_-]*/sk-***REDACTED***/g' "$DSH_HOME/.credentials.yaml"
echo "--- 有无我们的 ref（应为 0）---"
grep -c "POCKETAGENT_LOCAL_TOKEN" "$DSH_HOME/.credentials.yaml" || true
echo "--- 备份文件是否还在（阶段2/3 应已删除）---"
ls -la "$DSH_HOME"/*.bak-pa "$DSH_HOME"/*.pa-stage3bak 2>&1 | head -5

echo
echo "###################### 2. 用户原有链路是否仍然可用 ######################"
echo "（走真实 DeepSeek。基线结论是 QUOTA: Insufficient Balance —— 不花钱）"
timeout 240 dsh --profile headless "1+1" 2>&1 | tail -20

echo
echo "###################### 3. 清理本次实验产物 ######################"
rm -f /root/pa-stub.js /root/pa-stub3.js /root/pa-stub.log /root/pa-stub3.log \
      /root/pa-stub-node.log /root/pa-stub3-node.log \
      /root/pa-dump-before.txt /root/pa-dump-after.txt \
      /root/pa-stage1.sh /root/pa-stage2.sh /root/pa-stage3.sh /root/pa-stage4.sh
echo "--- 残留检查（应无 pa-* 文件）---"
ls -la /root/pa-* 2>&1 | head -5
echo "--- 容器内 /root 现状 ---"
ls -a /root | head -25
echo "STAGE4-DONE"
