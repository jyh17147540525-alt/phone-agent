package com.pocketagent.personalogic

/**
 * 人格运行时 —— 把「设定」与「状态」装到一起，并对**时间**负责。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它补的是哪一维（以及为什么不是"又一个 Holder"）
 * ═══════════════════════════════════════════════════════════════
 *
 * 设定那一半**已经有主**了：[PersonaTuner]（实现 [DefaultPersonaTuner]）持有
 * 变更账本，并提供 `currentSpec()` = "用完整账本回放一次"。
 * 本类**不复制账本、不自己 derive** —— 它把 `currentSpec()` 原样转出去。
 * 复制一份账本的后果是两个真相来源，而它们必然漂移（用户改了口癖，一边生效一边没生效）。
 *
 * 本类真正补的是**状态 + 时间**这一维，而它此前在模块里没有任何载体：
 *
 * ```
 *   PersonaStateEngine   纯函数：state × event → state，state × elapsedMs → state
 *   PersonaRuntime       ★ 把纯函数接上"现在几点了"，并保证状态永远不是冻结的
 * ```
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 为什么 `currentState()` 会**推进时间**（这是刻意的副作用）
 * ═══════════════════════════════════════════════════════════════
 *
 * 状态会随时间向中性回落。若 `currentState()` 只读不推进，那么"上次推进"
 * 与"现在"之间的这段衰减**永远不会被算进去** —— 除非恰好有人调了 `tick()`。
 * 而在没有对话循环的今天，**没有人会调 `tick()`**：结果是一个几小时前
 * 情绪低落的助理，此刻仍报"情绪 30"，用户问「你怎么了」它答不上来，
 * 因为那个由头早就该过期了。
 *
 * ⇒ 读取即推进：**返回一个冻结在过去的状态，等于说一句假话**。
 *
 * ⚠️ 推进是**幂等**的：同一毫秒内连读两次，第二次的 `elapsed <= 0`，
 *    直接返回、连基准点都不移动（见 [tick] 的注释）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 状态**不进账本、不参与漂移**（与 [PersonaStateEngine] 同一条不变量）
 * ═══════════════════════════════════════════════════════════════
 *
 * 本类持有 [PersonaState]，但**从不把它交给 tuner** —— 签名里没有这样一条路。
 * 一旦状态能进账本，[PersonaDistance] 会把「今天心情差」算成「人格变了」，
 * 于是锚定询问会在用户刚被工作骂完时弹出来问"我是不是变了"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 时钟是注入的
 * ═══════════════════════════════════════════════════════════════
 *
 * 不直接调 `System.currentTimeMillis()` 的原因不是"为了测试"（虽然那也成立），
 * 而是**回落行为必须能被离线钉住**：4 小时回中、6 小时回中是产品判断，
 * 若它们只能在真机上靠"等四小时"验证，那就等于永远没被验证过。
 *
 * @param tuner 设定来源（账本 + 回放）。本类不复制它，只转发 `currentSpec()`
 * @param stateEngine 状态演化引擎。纯函数，无状态
 * @param clock 当前时刻（毫秒）。测试注入假时钟
 */
class PersonaRuntime(
    private val tuner: PersonaTuner,
    private val stateEngine: PersonaStateEngine = PersonaStateEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var state: PersonaState = PersonaState()

    /** 上次推进的时间基准。**初始为构造时刻**，不是 0 —— 否则首次读取会一次性衰减掉"自 1970 年以来的全部时间"。 */
    private var lastTickAtMs: Long = clock()

    /** 当前人格。等价于"用完整账本回放一次"，无副作用。 */
    fun currentSpec(): PersonaSpec = tuner.currentSpec()

    /**
     * 当前状态。**先推进到当下再返回**（见类注释：冻结的状态是假话）。
     *
     * ⚠️ 与 `tuner.currentSpec()` 不同，本方法**有副作用**（推进时间）。
     *    它被标了 `@Synchronized` 的原因同 [DefaultPersonaTuner]：
     *    "读到一半被改"会产出一个**从未存在过的状态**。
     */
    @Synchronized
    fun currentState(): PersonaState {
        tick()
        return state
    }

    /**
     * 把时间推进到当下。
     *
     * ⚠️ `elapsed <= 0` 时**不推进、也不移动基准点**。
     *    移动基准点会让一次时钟回拨（NTP 校正 / 用户改时间）把之后的
     *    衰减**全部吃掉** —— 状态会诡异地卡住不动，且没有任何报错。
     */
    @Synchronized
    fun tick(): PersonaState {
        val now = clock()
        val elapsed = now - lastTickAtMs
        if (elapsed <= 0) return state
        lastTickAtMs = now
        state = stateEngine.advance(state, elapsed)
        return state
    }

    /**
     * 一件事发生了：先推进时间，再按事件调整状态。
     *
     * ⚠️ [PersonaEvent.reason] 是必填的（由 [PersonaEvent] 的构造函数强制）——
     *    没有由头的情绪就是随机情绪，而随机情绪 = 崩人设。
     */
    @Synchronized
    fun apply(event: PersonaEvent): PersonaState {
        tick()
        state = stateEngine.apply(state, event)
        return state
    }

    /**
     * 状态的**只读快照**（不推进时间）。
     *
     * 用途是"我在别处已经推过了，这里只想看一眼"——例如体检报告要读状态、
     * 但它的读取不该改变状态。**别用它替代 [currentState]**：那会让衰减停摆。
     */
    @Synchronized
    fun stateSnapshot(): PersonaState = state
}
