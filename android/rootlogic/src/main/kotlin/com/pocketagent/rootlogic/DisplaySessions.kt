package com.pocketagent.rootlogic

/**
 * 虚拟屏会话表 —— "谁占了哪块屏、上面跑的是哪个 App"。
 *
 * ## 为什么需要一张表，而不是每次现建一块屏
 *
 * 虚拟屏是**稀有资源**：安卓 14+ 才稳定支持并行，实际能开的块数只有 1–3 块，
 * 每块都在渲染、都在吃内存和电。而任务会失败、会被取消、会留下半死不活的屏。
 * 没有一张表，重复建屏会在几分钟内把额度和内存吃光，然后表现为"莫名其妙开不了屏"。
 *
 * ## 三条硬规则
 *
 * 1. **同一 App 只归一个会话**。两个任务同时操作微信，剪贴板和输入法会互相打架，
 *    最后两边都说"点错了"。
 * 2. **块数有上限**。到上限不是失败，是"排队"——调用方等一个会话被关掉。
 * 3. **关闭顺序不能换**：先 `am force-stop`，再收屏。反过来系统会把 Task 挪回主屏，
 *    用户会看到应用自己蹦到脸前（真机 2026-10-27 实测）。
 *
 * ## 为什么每个方法都同步
 *
 * 并行的任务会同时来借屏（`ResourceScheduler` 就是为这个存在的）。
 * `claim` 是"先查同一 App → 再找空位 → 再写入"三步，不加锁时两个任务可能拿到同一块 id，
 * 于是两个任务往同一块屏上点 —— 表现是"两个任务互相点对方的东西"，而且日志里看不出来。
 * 锁的粒度就取这张表：它本身很小，抢锁的时间远小于一次读屏。
 *
 * ⚠️ 这张表只管"分配与顺序"，不碰 Android API：建屏与收屏发生在应用进程里，
 *    命令（拉 App / 点 / 抓帧 / 停 App）走 [DisplayCommands] 交给通道执行。
 *    这样它能在离线被完整测出来，而真机上出错的那部分只剩"系统到底配合不配合"。
 */
class DisplaySessions(
    /** 能同时开几块。安卓 14+ 最多 3，默认 2（多数中端机 2 块就开始掉帧）。 */
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    /** 首个可用副屏 id。0 是用户主屏，永远不用。 */
    private val firstDisplayId: Int = DisplayCommands.MIN_DISPLAY_ID,
) {
    init {
        require(maxSessions in 1..3) { "并行会话数必须在 1..3，实际：$maxSessions" }
        require(firstDisplayId >= DisplayCommands.MIN_DISPLAY_ID) { "首个副屏 id 必须 ≥ 1" }
    }

    /** 一个会话。 */
    data class Session(
        val displayId: Int,
        /** 归谁用（包名）。没拉起之前为 null —— 建屏可以早于知道要开哪个 App。 */
        val packageName: String? = null,
        /** 是否已经把 App 拉进去了。 */
        val launched: Boolean = false,
    )

    /** 申请结果。到上限不是错误，是"暂时借不到"。 */
    sealed interface Claim {
        data class Granted(val displayId: Int) : Claim

        /** 这个 App 已经在别的会话里跑着了。 */
        data class AlreadyOwned(val displayId: Int, val packageName: String) : Claim

        /** 借不到：所有块都在用。 */
        data class Busy(val occupied: List<Int>) : Claim
    }

    /**
     * 关闭计划：命令 + 应用侧动作。
     *
     * ⚠️ [forceStop] 为 null 表示"还没拉起过 App"——那种情况下不需要 force-stop，
     *    但**仍然需要收屏**（否则那块屏会一直占着）。
     */
    data class ClosePlan(
        val displayId: Int,
        val packageName: String?,
        val forceStop: List<String>?,
    ) {
        /** 顺序：先停 App，再收屏。 */
        val steps: List<String> = buildList {
            if (forceStop != null) add("执行：" + forceStop.joinToString(" "))
            add("收屏：display $displayId（应用侧 release）")
        }
    }

    private val sessions = LinkedHashMap<Int, Session>()

    /** 现在活着的会话。 */
    @Synchronized
    fun occupied(): List<Session> = sessions.values.toList()

    @Synchronized
    fun sessionOf(displayId: Int): Session? = sessions[displayId]

    /**
     * 给某个 App 申请一块屏。
     *
     * @param packageName 要跑哪个 App；null 表示"先占一块屏，具体 App 待定"。
     */
    @Synchronized
    fun claim(packageName: String?): Claim {
        if (packageName != null) {
            sessions.values.firstOrNull { it.packageName == packageName }?.let {
                return Claim.AlreadyOwned(it.displayId, packageName)
            }
        }
        val free = freeDisplayId()
            ?: return Claim.Busy(sessions.keys.toList())
        sessions[free] = Session(displayId = free, packageName = packageName)
        return Claim.Granted(free)
    }

    /**
     * 把 App 拉到已申请的屏上，返回要执行的命令。
     *
     * @throws IllegalArgumentException 屏不存在 / 组件名非法（由 [DisplayCommands] 抛）
     */
    @Synchronized
    fun launch(displayId: Int, packageName: String, component: String): List<String> {
        requireSession(displayId)
        val command = DisplayCommands.launch(displayId, component)
        sessions[displayId] = Session(displayId = displayId, packageName = packageName, launched = true)
        return command
    }

    /** 在某块屏上点一下。 */
    @Synchronized
    fun tap(displayId: Int, x: Int, y: Int): List<String> {
        requireSession(displayId)
        return DisplayCommands.input(displayId, listOf("tap", x.toString(), y.toString()))
    }

    /** 抓某块屏的画面。 */
    @Synchronized
    fun capture(displayId: Int, path: String): List<String> {
        requireSession(displayId)
        return DisplayCommands.capture(displayId, path)
    }

    /**
     * 关掉一块屏，并把它从表里移除。
     *
     * @throws IllegalArgumentException 这块屏从来没申请过
     */
    @Synchronized
    fun close(displayId: Int): ClosePlan {
        val session = sessions.remove(displayId)
            ?: throw IllegalArgumentException("display $displayId 没有会话")
        return ClosePlan(
            displayId = displayId,
            packageName = session.packageName,
            forceStop = session.packageName
                ?.takeIf { session.launched }
                ?.let { DisplayCommands.forceStop(it) },
        )
    }

    /** 关掉全部（进程退出、用户按停时用）。顺序与逐个关一致。 */
    @Synchronized
    fun closeAll(): List<ClosePlan> = sessions.keys.toList().map { close(it) }

    private fun freeDisplayId(): Int? =
        generateSequence(firstDisplayId) { it + 1 }
            .take(maxSessions)
            .firstOrNull { it !in sessions }

    private fun requireSession(displayId: Int) {
        require(sessions.containsKey(displayId)) { "display $displayId 没有会话" }
    }

    companion object {
        const val DEFAULT_MAX_SESSIONS = 2
    }
}
