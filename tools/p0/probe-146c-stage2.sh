#!/bin/bash
# =============================================================================
# 1.4.6c 真机验证 —— 阶段 2
#
# 目标：证明 DshConfigPatch 产出的 `llm-deepseek:` settings 段**真的被 dsh 采纳**，
#       且 baseURL 生效、apiKeyEnv 能被解析、请求真的发到我们的地址。
#
# 手法：在容器内起一个桩 OpenAI 服务，把 dsh 的 baseURL 指过去。
#       若桩收到 POST /v1/chat/completions → 整条链路证明完毕。
#
# ⚠️ 安全：本脚本**不改** .credentials.yaml（用户的真 Key 在里面）。
#    token 走**启动环境**层 —— 这正是 dsh 文档写明的最高优先级来源
#    （"Credential lookup follows a fixed precedence: the launch environment wins"）。
#
# ⚠️ 输出重定向写在脚本内部 —— 在调用处写 `> file` 会被**外层的 Termux bash**
#    求值，而 Termux 里没有 /root，报 "No such file or directory"。
# =============================================================================
set -u
exec > /root/pa-stage2-out.txt 2>&1

export DSH_HOME=/root/.dsh
export PATH="/usr/local/bin:$PATH"

STUB_PORT=59999
STUB_LOG=/root/pa-stub.log
STUB_JS=/root/pa-stub.js
SETTINGS="$DSH_HOME/settings.yaml"
BAK="$SETTINGS.pa-stage2bak"
DUMP_BEFORE=/root/pa-dump-before.txt
DUMP_AFTER=/root/pa-dump-after.txt

echo "###################### 1. 基线 dump ######################"
echo "--- settings.yaml 现状 ---"
cat "$SETTINGS"
dsh --profile headless --dump-config > "$DUMP_BEFORE" 2>&1
echo "--- 基线 dump 行数 ---"
wc -l < "$DUMP_BEFORE"
echo "--- 基线 dump 里 llm-deepseek 附近 ---"
grep -n -i -A 12 "llm-deepseek" "$DUMP_BEFORE" | head -30
echo "--- 基线 dump 里有没有 59999（应为 0）---"
grep -c "59999" "$DUMP_BEFORE" || true

echo
echo "###################### 2. 写入我们的 settings 段 ######################"
cp -a "$SETTINGS" "$BAK"
# 逐行手术：把 llm-deepseek 段追加到末尾（与 DshConfigPatch.mergeIntoSettingsYaml
# 在"段不存在时"的行为一致）。原内容一个字节不动。
cat >> "$SETTINGS" <<'YML'

llm-deepseek:
  baseURL: "http://127.0.0.1:59999/v1"
  apiKeyEnv: "POCKETAGENT_LOCAL_TOKEN"
  models:
    - id: "pocketagent-auto"
YML
echo "--- 写入后 ---"
cat "$SETTINGS"

echo
echo "###################### 3. 写入后 dump（关键证据）######################"
dsh --profile headless --dump-config > "$DUMP_AFTER" 2>&1
echo "--- dump 里 llm-deepseek 附近 ---"
grep -n -i -A 12 "llm-deepseek" "$DUMP_AFTER" | head -40
echo "--- 关键字符串计数 ---"
echo "59999            : $(grep -c '59999' "$DUMP_AFTER" || true)"
echo "pocketagent-auto : $(grep -c 'pocketagent-auto' "$DUMP_AFTER" || true)"
echo "POCKETAGENT_...  : $(grep -c 'POCKETAGENT_LOCAL_TOKEN' "$DUMP_AFTER" || true)"
echo "--- dump 的差值（后 - 前，应体现我们的段）---"
diff "$DUMP_BEFORE" "$DUMP_AFTER" | head -40

echo
echo "###################### 4. 起桩服务 ######################"
cat > "$STUB_JS" <<'JS'
const http = require('http');
const fs = require('fs');
const PORT = 59999;
const LOG = '/root/pa-stub.log';
fs.writeFileSync(LOG, 'STUB-BOOTED\n');
http.createServer((req, res) => {
  let body = '';
  req.on('data', c => body += c);
  req.on('end', () => {
    fs.appendFileSync(LOG,
      'REQ ' + req.method + ' ' + req.url +
      ' auth=' + (req.headers.authorization || '-') +
      ' body=' + body.slice(0, 300) + '\n');
    const payload = {
      id: 'chatcmpl-stub', object: 'chat.completion',
      created: Math.floor(Date.now() / 1000),
      model: 'pocketagent-auto',
      choices: [{ index: 0, message: { role: 'assistant', content: 'stub-ok' }, finish_reason: 'stop' }],
      usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 }
    };
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(payload));
  });
}).listen(PORT, '127.0.0.1', () => fs.appendFileSync(LOG, 'LISTENING on ' + PORT + '\n'));
JS
node "$STUB_JS" >/root/pa-stub-node.log 2>&1 &
STUB_PID=$!
sleep 3
echo "stub pid=$STUB_PID"
echo "--- 桩日志（应有 LISTENING）---"
cat "$STUB_LOG" 2>&1

echo
echo "###################### 5. 发一次真实请求 ######################"
echo "--- A) 不带 token（若失败且提到凭据，证明 apiKeyEnv 确实被读取）---"
timeout 150 dsh --profile headless "1+1" 2>&1 | tail -25

echo
echo "--- B) 带 token（应成功走到桩）---"
POCKETAGENT_LOCAL_TOKEN="pa-local-test-token-123" timeout 200 dsh --profile headless "1+1" 2>&1 | tail -30

echo
echo "###################### 6. 桩收到了什么（决定性证据）######################"
cat "$STUB_LOG"

echo
echo "###################### 7. 清理 ######################"
kill "$STUB_PID" 2>/dev/null || true
cp -a "$BAK" "$SETTINGS"
rm -f "$BAK"
echo "--- 恢复后的 settings.yaml ---"
cat "$SETTINGS"
echo "--- 恢复后 dump 里还有没有 59999（应为 0）---"
dsh --profile headless --dump-config 2>&1 | grep -c "59999" || true
echo "STAGE2-DONE"
