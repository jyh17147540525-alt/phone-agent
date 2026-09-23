#!/bin/bash
# =============================================================================
# 1.4.6c 真机验证 —— 阶段 3
#
# 阶段 2 已证明：settings.yaml 的 llm-deepseek 段被采纳、baseURL 生效、
#               apiKeyEnv 从**启动环境**解析成功。
#
# 阶段 3 要证明的是**我们产品实际会走的那条路**：
#   · token 放在 .credentials.yaml 的 refs 里（不是环境变量）
#   · 桩返回**真正的 SSE 流**（阶段 2 的桩返回非流式 JSON，
#     导致 dsh 报 STREAM_CLOSED —— 这本身是个有价值的发现：dsh 默认要流式）
#
# 成功判据：dsh 把桩的回答打印出来（而不是报错）。
#
# ⚠️ 本脚本会**临时修改** .credentials.yaml（用户的真 Key 在里面），
#    但先备份、结束前恢复，且只**插入一行**、不动其它任何内容。
# =============================================================================
set -u
exec > /root/pa-stage3-out.txt 2>&1

export DSH_HOME=/root/.dsh
export PATH="/usr/local/bin:$PATH"

STUB_PORT=59998
STUB_LOG=/root/pa-stub3.log
STUB_JS=/root/pa-stub3.js
SETTINGS="$DSH_HOME/settings.yaml"
CREDS="$DSH_HOME/.credentials.yaml"
SET_BAK="$SETTINGS.pa-stage3bak"
CRED_BAK="$CREDS.pa-stage3bak"
REF_NAME=POCKETAGENT_LOCAL_TOKEN
REF_VALUE=pa-file-token-456

echo "###################### 0. 备份 ######################"
cp -a "$SETTINGS" "$SET_BAK"
cp -a "$CREDS" "$CRED_BAK"
echo "已备份 settings.yaml / .credentials.yaml"

echo
echo "###################### 1. 改 settings.yaml ######################"
cat >> "$SETTINGS" <<YML

llm-deepseek:
  baseURL: "http://127.0.0.1:$STUB_PORT/v1"
  apiKeyEnv: "$REF_NAME"
  models:
    - id: "pocketagent-auto"
YML
tail -8 "$SETTINGS"

echo
echo "###################### 2. 往 .credentials.yaml 的 refs 插入一行 ######################"
# 只在 refs: 那一行之后插一条，其它内容原样保留。
# 脱敏打印：真 Key 用 *** 替掉，绝不落到日志里。
awk -v ref="$REF_NAME" -v val="$REF_VALUE" \
  '{ print } /^refs:/ { print "  " ref ": " val }' "$CRED_BAK" > "$CREDS"
echo "--- 插入后（已脱敏）---"
sed -e 's/sk-[A-Za-z0-9_-]*/sk-***REDACTED***/g' "$CREDS"
echo "--- 行数对比：备份 vs 现在 ---"
wc -l < "$CRED_BAK"
wc -l < "$CREDS"

echo
echo "###################### 3. 起 SSE 桩（模仿我们网关的线格式）######################"
cat > "$STUB_JS" <<'JS'
const http = require('http');
const fs = require('fs');
const PORT = 59998;
const LOG = '/root/pa-stub3.log';
fs.writeFileSync(LOG, 'STUB3-BOOTED\n');

function sseFrame(obj) {
  return 'data: ' + JSON.stringify(obj) + '\n\n';
}

http.createServer((req, res) => {
  let body = '';
  req.on('data', c => body += c);
  req.on('end', () => {
    let parsed = {};
    try { parsed = JSON.parse(body); } catch (e) {}
    const wantsStream = parsed.stream === true;
    fs.appendFileSync(LOG,
      'REQ ' + req.method + ' ' + req.url +
      ' auth=' + (req.headers.authorization || '-') +
      ' stream=' + wantsStream + '\n');

    if (!wantsStream) {
      // 非流式：与网关的 bufferedChat 同形
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        id: 'chatcmpl-stub3', object: 'chat.completion',
        created: Math.floor(Date.now() / 1000), model: 'pocketagent-auto',
        choices: [{ index: 0, message: { role: 'assistant', content: 'PA-STUB-ANSWER-42' }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 }
      }));
      return;
    }

    // 流式：与网关的 SseResponseWriter 同形
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
      'Connection': 'close'
    });
    const base = {
      id: 'chatcmpl-stub3', object: 'chat.completion.chunk',
      created: Math.floor(Date.now() / 1000), model: 'pocketagent-auto'
    };
    const words = ['PA', '-STUB', '-ANSWER', '-42'];
    let i = 0;
    const timer = setInterval(() => {
      if (i < words.length) {
        res.write(sseFrame({
          ...base,
          choices: [{ index: 0, delta: { content: words[i] }, finish_reason: null }]
        }));
        i++;
      } else {
        clearInterval(timer);
        res.write(sseFrame({
          ...base,
          choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
          usage: { prompt_tokens: 1, completion_tokens: 4, total_tokens: 5 }
        }));
        res.write('data: [DONE]\n\n');
        res.end();
      }
    }, 30);
  });
}).listen(PORT, '127.0.0.1', () => fs.appendFileSync(LOG, 'LISTENING on ' + PORT + '\n'));
JS
node "$STUB_JS" >/root/pa-stub3-node.log 2>&1 &
STUB_PID=$!
sleep 3
echo "stub pid=$STUB_PID"
echo "--- 桩日志 ---"
cat "$STUB_LOG" 2>&1

echo
echo "###################### 4. 关键：不带任何环境变量发请求 ######################"
echo "（token 只能来自 .credentials.yaml 的 refs —— 若成功，证明我们产品的写入路径有效）"
env -u "$REF_NAME" timeout 240 dsh --profile headless "1+1" 2>&1 | tail -30

echo
echo "###################### 5. 桩收到了什么（决定性证据）######################"
cat "$STUB_LOG"

echo
echo "###################### 6. 清理恢复 ######################"
kill "$STUB_PID" 2>/dev/null || true
cp -a "$SET_BAK" "$SETTINGS"
cp -a "$CRED_BAK" "$CREDS"
rm -f "$SET_BAK" "$CRED_BAK"
echo "--- 恢复后 settings.yaml ---"
cat "$SETTINGS"
echo "--- 恢复后 .credentials.yaml（已脱敏）---"
sed -e 's/sk-[A-Za-z0-9_-]*/sk-***REDACTED***/g' "$CREDS"
echo "--- 恢复后是否还有我们的 ref（应为 0）---"
grep -c "$REF_NAME" "$CREDS" || true
echo "--- 恢复后是否还有 59998（应为 0）---"
grep -c "59998" "$SETTINGS" || true
echo "STAGE3-DONE"
