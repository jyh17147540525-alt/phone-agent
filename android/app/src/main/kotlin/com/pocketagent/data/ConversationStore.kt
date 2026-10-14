package com.pocketagent.data

import com.pocketagent.core.database.dao.SessionDao
import com.pocketagent.core.database.dao.TaskDao
import com.pocketagent.core.database.dao.TurnDao
import com.pocketagent.core.database.entity.SessionEntity
import com.pocketagent.core.database.entity.TurnEntity
import com.pocketagent.memorylogic.SessionBoundary
import com.pocketagent.memorylogic.Turn
import com.pocketagent.memorylogic.TurnChain
import com.pocketagent.memorylogic.TurnPair
import com.pocketagent.memorylogic.TurnPairing
import com.pocketagent.memorylogic.TurnRole
import java.util.UUID

/**
 * 会话与轮次的落库封装。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它解决的是「软件不能正常使用」里最硬的一条
 * ═══════════════════════════════════════════════════════════════
 *
 * 在此之前 `TasksViewModel.tasks` 是一个 `mutableStateListOf` —— **进程一死全没**。
 * 而且 `ChatPort.send` 每次只收到**当前这一句** ⇒ 助理不记得你三句话前说过什么。
 *
 * 本类把两件事一起解决：**历史不丢** + **模型能看到上下文**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 分层：本类只做 IO，判断全在 `:memorylogic`
 * ═══════════════════════════════════════════════════════════════
 *
 * 「要不要开新会话」「当前路径是哪几条」「怎么配成问答对」都是**纯函数**，
 * 住在 `:memorylogic` 里、进得了离线验证器。
 * 本类只负责把它们和 Room 接起来 —— 这样那些判断**在没设备的机器上也能被测到**。
 *
 * ⚠️ 时间与 id 都由构造参数注入：测试里可以给假钟与确定性 id。
 *    生产用系统钟与 UUID。
 */
class ConversationStore(
    private val sessions: SessionDao,
    private val turns: TurnDao,
    private val tasks: TaskDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val idleTimeoutMs: Long = SessionBoundary.DEFAULT_IDLE_TIMEOUT_MS,
) {

    /**
     * 取**当前会话**；没有、或上一次发言已超空闲阈值 ⇒ 开一个新的。
     *
     * ⚠️ 阈值判定用 [SessionBoundary]（纯函数，有测试）。
     *    特别是"时钟回拨"那一条：它决定用户的历史会不会**看起来消失**。
     */
    suspend fun currentSession(): SessionEntity {
        val now = clock()
        val latest = sessions.listActive(limit = 1).firstOrNull()
        if (latest != null && !SessionBoundary.shouldStartNewSession(latest.lastMessageAt, now, idleTimeoutMs)) {
            return latest
        }
        return createSession(now)
    }

    /** 显式开一个新会话（界面的「新对话」按钮）。 */
    suspend fun newSession(): SessionEntity = createSession(clock())

    private suspend fun createSession(now: Long): SessionEntity {
        val s = SessionEntity(
            id = newId(),
            // ⚠️ 占位标题。真正的标题来自 §3.3 的两级生成（首轮启发式 → L1 提取时顺带）。
            //    这里**刻意不写"新对话"** —— 那个词在列表里出现十次就完全没信息量。
            //    用时间戳让用户至少能分辨"哪个是刚才那个"。
            title = "对话 ${formatClock(now)}",
            titleSource = TITLE_SOURCE_AUTO,
            createdAt = now,
            updatedAt = now,
            lastMessageAt = now,
            turnCount = 0,
            pinned = false,
            archived = false,
            activeLeafTurnId = null,
        )
        sessions.insert(s)
        return s
    }

    /**
     * 追加一轮，并维护会话的聚合字段。
     *
     * ⚠️ 三步（插轮次 / 更新会话计数 / 更新当前叶子）**必须一起做** ——
     *    少做第三步，界面恢复时会退回"按时间全取"，
     *    于是**被放弃的分支也会显示出来**，而用户明明切走了。
     *
     * ⚠️ 这里**不开事务**：`insert` 与两条 `UPDATE` 之间失败，
     *    最坏结果是"轮次在库里但计数少 1"—— 可接受且可自愈。
     *    真正的隐私风险（删不干净）在 [deleteSession] 那条路径上，那里有 `@Transaction`。
     */
    suspend fun append(
        sessionId: String,
        role: TurnRole,
        text: String,
        parentTurnId: String? = null,
    ): Turn {
        val now = clock()
        val id = newId()
        turns.insert(
            TurnEntity(
                id = id,
                sessionId = sessionId,
                role = role.name,
                text = text,
                at = now,
                tokenCount = null, // 算不出就是 null —— 不填 0（0 是"确知为零"的断言）
                parentTurnId = parentTurnId,
                superseded = false,
            ),
        )
        sessions.touchAfterTurn(id = sessionId, lastMessageAt = now, updatedAt = now)
        // ★ 每插一轮就把它设为当前叶子 —— 追加永远发生在当前路径的末端。
        sessions.setActiveLeaf(id = sessionId, turnId = id, updatedAt = now)
        return Turn(id, sessionId, role, text, now, parentTurnId, superseded = false)
    }

    /**
     * 当前会话的**当前路径**（按时间升序）。
     *
     * ⚠️ 先取全部再走 [TurnChain] —— **不在 SQL 里过滤**。
     *    在 SQL 里过滤会把树拍平成链（设计稿 §3.5 警告的那件事）。
     */
    suspend fun currentPath(sessionId: String): List<Turn> {
        val all = turns.listBySession(sessionId).map { it.toDomain() }
        val leaf = sessions.byId(sessionId)?.activeLeafTurnId
        return TurnChain.currentPath(all, leaf)
    }

    /**
     * 给模型看的**最近 N 轮**（按时间升序，可直接拼进 messages）。
     *
     * ⚠️ 取的是**当前路径**的最近 N 条，不是"按时间最近 N 条"——
     *    后者会把被放弃的分支也喂给模型，于是助理会**记得用户否掉的那个版本**。
     */
    suspend fun recentForModel(sessionId: String, limit: Int): List<Turn> =
        currentPath(sessionId).takeLast(limit)

    /** 启动时恢复界面用：当前路径配成问答对。 */
    suspend fun restorePairs(sessionId: String): List<TurnPair> =
        TurnPairing.pair(currentPath(sessionId))

    /**
     * 删除会话**并带走它的轮次与执行记录**。
     *
     * ⚠️ 这是**隐私路径**：用户以为删干净了，而磁盘上还留着他说的每一句话。
     *    走 `SessionDao.deleteCascade`（带 `@Transaction`），
     *    **不依赖外键级联** —— SQLite 的 `PRAGMA foreign_keys` 默认是关的。
     */
    suspend fun deleteSession(sessionId: String) = sessions.deleteCascade(sessionId)

    suspend fun renameSession(sessionId: String, title: String) {
        val now = clock()
        // ⚠️ 改名**只动 title 与 updatedAt**，不动 lastMessageAt ——
        //    否则"改个名字"会让会话跳到列表最前面。
        sessions.updateTitle(
            id = sessionId,
            title = title,
            titleSource = TITLE_SOURCE_USER,
            updatedAt = now,
        )
    }

    companion object {
        /** 标题来源。⚠️ 字面量，与 `SessionEntity.titleSource` 的取值域一致 */
        const val TITLE_SOURCE_AUTO = "AUTO"

        /** ⚠️ 用户改过名的会话**永不自动覆盖** —— 见设计稿 §3.3 */
        const val TITLE_SOURCE_USER = "USER"

        /** `HH:mm`。只用于占位标题，不做本地化 */
        private fun formatClock(ms: Long): String {
            val totalMinutes = (ms / 60_000) % (24 * 60)
            val h = (totalMinutes / 60).toString().padStart(2, '0')
            val m = (totalMinutes % 60).toString().padStart(2, '0')
            return "$h:$m"
        }
    }
}

/**
 * 实体 → 域对象。
 *
 * ⚠️ `role` 用 `valueOf` 会**在遇到未知值时抛异常**（比如将来加了新角色、用户降级版本）。
 *    这里**降级成 USER** 而不是崩：一条角色认不出的历史记录，
 *    显示成"用户说的"总比整页崩掉好 —— 而且它仍在库里，没丢。
 */
private fun TurnEntity.toDomain(): Turn = Turn(
    id = id,
    sessionId = sessionId,
    role = runCatching { TurnRole.valueOf(role) }.getOrDefault(TurnRole.USER),
    text = text,
    at = at,
    parentTurnId = parentTurnId,
    superseded = superseded,
)
