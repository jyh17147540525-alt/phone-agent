package com.pocketagent.personalogic

/**
 * 一次 `tune` / `rollback` 的结果。
 *
 * ⚠️ **被拒绝不在这个类型里** —— 拒绝走 [PersonaRejectionException]。
 *    理由见该异常类的注释：拒绝意味着调用方写错了或发起了一次攻击，
 *    不该和"正常但未生效"混在同一个返回值里。
 */
sealed interface TuneResult {

    /** 已生效（显式指令一条即生效；隐式/行为攒够证据后也会走到这里） */
    data class Applied(val delta: PersonaDelta, val spec: PersonaSpec) : TuneResult

    /** 已记录进候选区，**尚未生效**。携带进度供 UI 展示"还差几条" */
    data class CandidateRecorded(
        val field: PersonaField,
        val newValue: String,
        val have: Int,
        val need: Int,
        val confidence: Double,
    ) : TuneResult

    /** 目标值与当前值相同，什么都没做。**不是失败** */
    data class AlreadyAtValue(val field: PersonaField, val value: String) : TuneResult

    /** 已回滚，携带回滚后的快照 */
    data class RolledBack(val delta: PersonaDelta, val spec: PersonaSpec) : TuneResult

    /**
     * 这条 delta 早就回滚过了。
     *
     * ⚠️ 单独一项而不是复用 [RolledBack]：回滚是**幂等**操作，
     *    用户连点两次"撤销"不该报错，但也不能假装"刚刚撤销成功" ——
     *    后者会让 UI 显示一个并不存在的状态变化。
     */
    data class AlreadyRolledBack(val deltaId: String) : TuneResult
}

/**
 * 人格微调器 —— 需求②的算法主体。
 *
 * ## 为什么是同步接口
 *
 * 本模块**零 IO、零 Android 依赖**：账本的持久化归 `:memory`，
 * 模型调用归 `:provider/gateway`。内核不碰这两者，因此它不需要
 * `suspend` —— 加上 `suspend` 只会让"这个函数会不会发网络请求"
 * 变成一个需要读实现才能回答的问题。
 *
 * 需要挂起语境（协程、Room 事务）的调用方通过 [asSuspend] 适配，
 * 见该函数的注释。
 *
 * ## 时间由调用方传入
 *
 * 所有写操作都要求显式传 `now: Long`。**刻意不在内部读时钟**：
 * - 「时间条件化偏好」（行为统计）必须能被离线测试钉住 ——
 *   内部读 `System.currentTimeMillis()` 就无法构造"23:00 之后"的场景；
 * - 一条变更的 `createdAt` 会参与回放排序，它必须是**调用时刻**，
 *   而不是"处理到这一条时的时刻"（排队的变更会因此乱序）。
 */
interface PersonaTuner {

    /** 当前人格快照。等价于"用完整账本回放一次"，无副作用 */
    fun currentSpec(): PersonaSpec

    /**
     * 微调一个白名单字段。
     *
     * @param ref 外部字段名解析后的归宿。传入 [PersonaFieldRef.Bones] /
     *   [PersonaFieldRef.Unknown] 会**抛异常 + 记审计**，不会静默忽略
     * @param newValue 新值原文（未归一，由本函数归一）
     * @param source 触发源
     * @param evidence 触发证据。显式指令放用户原话；隐式/行为放
     *   [EvidenceSignal] 的名字（可带 `@时间条件`，仅行为统计）
     * @param now 调用时刻（毫秒）
     * @throws PersonaRejectionException 硬拒绝
     */
    fun tune(
        ref: PersonaFieldRef,
        newValue: String,
        source: DeltaSource,
        evidence: List<String>,
        now: Long,
    ): TuneResult

    /**
     * 回滚一条变更（幂等）。
     *
     * @throws IllegalArgumentException 找不到该 id。**不静默返回成功** ——
     *   UI 拿到"已回滚"却其实什么都没回滚，是比报错更糟的结果
     */
    fun rollback(deltaId: String, now: Long): TuneResult

    /**
     * 反漂移检查。**纯查询，无副作用**。
     *
     * 刻意不在这里记审计：锚定询问是助理**看到 `needsAnchoring` 之后
     * 主动发起**的行为，该由发起方记。查询本身产生副作用的话，
     * "看一眼漂移情况"和"已经问了用户"就无法区分了。
     */
    fun driftCheck(spec: PersonaSpec): DriftReport

    /**
     * 可供助理在下一次对话里自然引用的变更。
     *
     * ⚠️ **只含 ACTIVE**。候选区绝不可被说出口 ——
     *    「你上次说我讲废话，我现在尽量一句说完」如果对应的还只是
     *    一条候选证据，那助理说了一句**它还没做到的承诺**。
     *    对用户来说，这比不说更糟：他在等着一个不会发生的改变。
     */
    fun recentChanges(n: Int): List<PersonaDelta>

    /**
     * 用持久化的账本重建内部状态（冷启动）。
     *
     * @throws IllegalArgumentException 账本内 id 重复
     */
    fun restore(deltas: List<PersonaDelta>)
}

/**
 * 挂起版本的人格微调器 —— 给协程 / Room 事务语境用。
 *
 * 方法集与 [PersonaTuner] **一一对应**，只是 tune/rollback/recentChanges
 * 是 `suspend`（因为它们会被包在数据库事务里）。
 */
interface SuspendPersonaTuner {

    suspend fun currentSpec(): PersonaSpec

    suspend fun tune(
        ref: PersonaFieldRef,
        newValue: String,
        source: DeltaSource,
        evidence: List<String>,
        now: Long,
    ): TuneResult

    suspend fun rollback(deltaId: String, now: Long): TuneResult

    fun driftCheck(spec: PersonaSpec): DriftReport

    suspend fun recentChanges(n: Int): List<PersonaDelta>

    suspend fun restore(deltas: List<PersonaDelta>)
}

/**
 * 把同步内核适配成挂起接口。
 *
 * ## 为什么是"包一层"而不是"内核直接写成 suspend"
 *
 * 内核做成 `suspend` 的话，离线测试就必须引入 `kotlinx-coroutines-test`
 * 并到处写 `runTest {}` —— 而本模块的 build 文件里没有这个依赖
 * （见生成器模板）。为了不因为一个测试依赖去改生成器脚本，
 * 内核保持同步、由这一层承担挂起语境的适配。
 *
 * ## 线程语义（重要）
 *
 * 这个适配层**不做 dispatcher 切换**：直接在当前线程上调用同步内核。
 * 内核内部有 `@Synchronized`，是线程安全的，但**不保证**在
 * `Dispatchers.Main` 上调用不会有卡顿 —— 内核的复杂度是 O(账本长度)，
 * 而账本长度在真实使用下是几十条。等 P5 引入 L2/L3 蒸馏时若账本显著
 * 变长，应在**调用方**用 `withContext(Dispatchers.Default)` 包住，
 * 而不是在这里偷偷切线程。
 */
fun PersonaTuner.asSuspend(): SuspendPersonaTuner = object : SuspendPersonaTuner {

    override suspend fun currentSpec(): PersonaSpec = this@asSuspend.currentSpec()

    override suspend fun tune(
        ref: PersonaFieldRef,
        newValue: String,
        source: DeltaSource,
        evidence: List<String>,
        now: Long,
    ): TuneResult = this@asSuspend.tune(ref, newValue, source, evidence, now)

    override suspend fun rollback(deltaId: String, now: Long): TuneResult =
        this@asSuspend.rollback(deltaId, now)

    override fun driftCheck(spec: PersonaSpec): DriftReport = this@asSuspend.driftCheck(spec)

    override suspend fun recentChanges(n: Int): List<PersonaDelta> = this@asSuspend.recentChanges(n)

    override suspend fun restore(deltas: List<PersonaDelta>) = this@asSuspend.restore(deltas)
}