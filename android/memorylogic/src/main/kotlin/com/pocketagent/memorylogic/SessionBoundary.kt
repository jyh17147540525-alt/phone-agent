package com.pocketagent.memorylogic

/**
 * 会话边界判定。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么需要"边界"这个概念
 * ═══════════════════════════════════════════════════════════════
 *
 * 在此之前 UI 里根本没有"会话" —— 一条消息一张卡，进程一死全没。
 * 而 `Turn.sessionId` **早就存在**，注释写着「跨会话的区分（以及「上次我们说到」）靠它」
 * ⇒ **记忆层一直在等一个会话概念，UI 层从来没给过它。**
 *
 * ⚠️ 边界判据只有两条：**用户主动开新对话** 或 **空闲超时**。
 *    **刻意不做"话题检测"** —— 它要么花一次 LLM 调用，要么不准，两个代价都不值得。
 *
 * ★ 本对象是**纯函数、零 IO、零 Android 依赖** ⇒ 进得了离线验证器。
 *   时间由调用方传入（同 `AtomExtractor` 的纪律：内核不读时钟）。
 */
object SessionBoundary {

    /**
     * 空闲多久算"上一次对话结束了"。
     *
     * 30 分钟是**产品判断**，不是技术常数：
     * - 太短：手机上"想起来再说一句"很常见，会把一段连续思路切成好几段，
     *   而每一段都会各自触发一次 L1 提取（**花用户的钱**）
     * - 太长：会把两天里毫不相干的事并成一段，L2 归纳出来的场景会变成一锅粥
     *
     * ⚠️ 用户可配。这个值只是默认。
     */
    const val DEFAULT_IDLE_TIMEOUT_MS: Long = 30 * 60 * 1000L

    /**
     * 该不该开一个新会话？
     *
     * @param lastActivityAt 上一次发言的时间。**null = 从来没说过话** ⇒ 开新的
     * @param now 当前时间（由调用方传入，内核不读时钟）
     * @param idleTimeoutMs 空闲阈值
     *
     * ⚠️ `now < lastActivityAt` 时返回 `false`（不新开）。
     *    时钟回拨（用户改系统时间、NTP 校正）会让它发生 ——
     *    此时**沿用旧会话**比"凭空开一个新的"更接近用户预期，
     *    而后者会让他的历史**看起来消失了**（其实在另一个会话里）。
     */
    fun shouldStartNewSession(
        lastActivityAt: Long?,
        now: Long,
        idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    ): Boolean {
        if (lastActivityAt == null) return true
        if (now < lastActivityAt) return false
        return now - lastActivityAt >= idleTimeoutMs
    }
}

/**
 * 从树里取「当前路径」。
 *
 * ⚠️ 会话是一棵**树**（`turn.parentTurnId`），不是一条链 ——
 *    「重新生成 / 改写上一条」会分叉，而**旧分支要保留**（用户随时能切回去对比）。
 *    `activeLeafTurnId` 指向当前采纳的那条路径的**叶子**。
 *
 * ★ 纯函数。输入是**全部**轮次（含被放弃的分支），输出是当前路径。
 *   在 SQL 里过滤会把树拍平成链 —— 那正是设计稿 §3.5 警告的事。
 */
object TurnChain {

    /**
     * @param all 该会话的**全部**轮次（任意顺序）
     * @param activeLeafId 当前路径的叶子。null / 找不到 ⇒ 退化成"按时间顺序全取"
     * @return 按时间升序的当前路径
     *
     * ⚠️ `activeLeafId` 找不到时**不报错、不返回空** —— 退化成按时间全取。
     *    空列表会让界面显示"没有对话"，而用户的历史**明明还在库里** ——
     *    那是最坏的一种表现（看起来像数据丢了）。
     *    真出这种情况（比如引用了被删的轮次）时，"多显示一点"远比"显示没有"安全。
     */
    fun currentPath(all: List<Turn>, activeLeafId: String?): List<Turn> {
        val byId = all.associateBy { it.id }
        val leaf = activeLeafId?.let { byId[it] } ?: return all.sortedBy { it.at }

        // 沿 parentTurnId 上溯到根，再反转
        val path = ArrayDeque<Turn>()
        val seen = HashSet<String>()
        var cur: Turn? = leaf
        while (cur != null) {
            // ⚠️ 环检测：数据坏了（A 的父是 B、B 的父是 A）时，
            //    没有这个集合就是**死循环** —— 而它发生在主线程上会直接 ANR。
            if (!seen.add(cur.id)) break
            path.addFirst(cur)
            cur = cur.parentTurnId?.let { byId[it] }
        }
        return path.toList()
    }
}

/**
 * 把轮次配成「问 → 答」对，给界面用。
 *
 * ⚠️ 只配对，**不改顺序、不丢数据**：
 *   - 连续的 USER 轮次（用户连发两句）：第一句配 `null`（还没答），第二句配它自己的答案
 *   - 末尾的 USER 轮次没有答案 ⇒ `reply == null`（界面显示"正在等回复…"）
 *   - TOOL 轮次**不单独成对**，挂到它前面那对的 `reply` 上（界面目前不展示它，
 *     但**不能丢** —— 丢了界面与库就对不上了）
 */
object TurnPairing {

    /**
     * @param turns 当前路径上的轮次，**按时间升序**
     */
    fun pair(turns: List<Turn>): List<TurnPair> {
        val out = mutableListOf<TurnPair>()
        var pendingPrompt: Turn? = null
        val toolNotes = mutableListOf<String>()

        for (t in turns) {
            when (t.role) {
                TurnRole.USER -> {
                    // 上一句还没等到答案就又发了一句 ⇒ 把它作为一个"没有答案"的对收掉。
                    // ⚠️ 不能把它丢掉：用户**确实说过**那句话。
                    if (pendingPrompt != null) {
                        out += TurnPair(pendingPrompt, null, emptyList())
                        toolNotes.clear()
                    }
                    pendingPrompt = t
                }

                TurnRole.ASSISTANT -> {
                    val p = pendingPrompt
                    if (p != null) {
                        out += TurnPair(p, t, toolNotes.toList())
                        pendingPrompt = null
                        toolNotes.clear()
                    }
                    // 没有待答的提问 ⇒ 孤立的回答（不该发生，但**不丢**）
                    else out += TurnPair(null, t, toolNotes.toList())
                }

                TurnRole.TOOL -> toolNotes += t.text
            }
        }

        // 末尾还没答的那一句
        pendingPrompt?.let { out += TurnPair(it, null, emptyList()) }

        return out
    }
}

/**
 * 一问一答。
 *
 * ⚠️ `prompt` 可空：孤儿回答（没有配对的提问）也要能表达出来 ——
 *    丢掉它会让界面少显示一句话，而库里明明有。
 */
data class TurnPair(
    val prompt: Turn?,
    val reply: Turn?,
    /** 这一轮里发生的工具调用（界面暂不展示，但**必须带着**，否则与库对不上） */
    val toolNotes: List<String>,
)
