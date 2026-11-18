# 给做「作息与在场」的协作组：三条提醒

> 留信人：负责 **agent 能力** 的那条线（`agentlogic/orchestration`、`:mcp` 工具、`:rootlogic` 通道）。
> 我不是来审你们的模块的 —— 你们那套 `Presence` 我只在**核对接线**时读过一遍（因为 L3 渲染在
> `PersonaAssembler` 里，和我这边的提示词层数有关）。
> 下面三条都是在核对过程中**顺手发现的真问题**，附行号与建议，改不改由你们定。

---

## 0. 先说核对结果：你们的声明都属实

| 声明 | 结果 |
|---|---|
| `presence` 传进 L3，且两处早退都带 `presenceLine == null` | ✅ `PersonaAssembler.kt:82 / 220 / 241 / 274 / 284 / 331` |
| 只有「在场」模式开 | ✅ `ChatMode.kt:102 / 125 / 159`（false / false / true） |
| 主动消息两处门禁判 `switches.presence` | ✅ `AppContainer.kt:3777 / 3930`，`:4113` 写明"本函数不判" |
| 时区不写死 | ✅ 传 `localOffsetMinutesOf(nowMs)` |
| 19 条 `PresenceEngineTest` + 两条接线测试 | ✅ 计数与名字都对得上 |
| 测试数 4469 | ✅ 当时基线正确（现在 4518，差的是我这两天的 Linux 工作区，与你们无关） |

---

## 1.（建议合并前改）热聊期间说自己在洗澡，而且"听见提示音"

- 位置：`PersonaAssembler.kt:247` `hotChatMinutes = 0` + `Presence.kt:293` 的文案
- 现场：21:35 用户连续聊了十分钟，她一边回一边拿到
  「这个点你一般在洗澡（水还开着）。**是听见提示音才腾出手看的**」
- 为什么是问题："听见提示音"在**持续对话**里是假的 —— 用户根本没发通知。
  这正是你们自己写的第一条纪律（"聊到一半她说去洗澡"是最刺眼的一种坏）。

**建议**：`renderState` / `promptLine` 多收一个 `inHotChat: Boolean`，SHOWER 在热聊时换一句
（例："刚冲完，手还湿着，回得短"）。**不要**因此去掉 presence —— 去掉就回到"永远 AT_HAND"。

---

## 2.（建议改）`beat` 与饭点重复，可以不靠真机验

- 你们把"beat 写厨房 + Presence 说吃饭"列为待真机观察。
- 其实在 `renderState` 的 `parts` 拼接处就能确定性去重：L4 文本含「厨房 / 吃 / 饭」时不再追加 `presenceLine`，
  再加一条测试即可。比上线后看"会不会重复"便宜。

---

## 3.（建议改口径）`SLEEPING` 的语气，别再加第三段提示词

- `Presence.kt:297` 对 `SLEEPING` 返回 `null`，于是深夜被叫醒时她回得跟白天一样利索 —— 这个洞是真的。
- 但补法建议**不要**在 Presence 里加一段：会和 `PhysiologyEngine` 的"凌晨困"抢同一件事，
  而两边同时说"你困/你被吵醒"的表现是模型重复交代。
- **建议**：单一来源 —— `SLEEPING` 时让 Physiology 那条**替换**成"被提示音吵醒，先看清是谁"，
  Presence 继续 `null`。

另外你们列的 #3（久坐 + 饭后同现）我同意是风险，但解法不是真机试，而是给"身体状态"加一条
**全局最多一条**的优先级 —— 这条以后加新状态也管用。

---

## 4. 仓库卫生（这条不归你们，但顺手说）

`.rl-src/`（6 个文件 / 0.2 MB）和 `.rl-readme.md` 现在是**未跟踪、也不在 `.gitignore`** 的状态，
躺在仓库根目录。要留就挪进 `docs/参考/real-life/` 并写清来源与**上游许可**（GPL 项目里引别家代码，
署名与许可是硬要求）；不留就 ignore 掉。现在是"半个 vendored 上游"，下一个人会以为是我们自己的模块。

---

## 附：我这边与你们**没有交叉**的部分

- 我改的是：`agentlogic/orchestration/*`（工具目录、计划与验收、SuperAgent）、`:mcp` 的 `linux_exec`/`linux_read`、
  `:rootlogic` 的 `LinuxEnv`/`LinuxCommands`/`DisplaySessions`。
- 你们的 `Presence.kt` / `ChatMode.kt` / `PersonaAssembler.kt` **我一行没动**；
  如果第 1 条的文案你们同意，我可以只出"热聊时的 SHOWER 文案 + 一条测试"，`Presence.kt` 的语义仍由你们维护。
