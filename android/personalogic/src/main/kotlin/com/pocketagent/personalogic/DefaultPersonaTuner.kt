package com.pocketagent.personalogic

/**
 * [PersonaTuner] 的默认实现 —— 「三源触发 + 置信度门 + 变更账本」的落地。
 *
 * ═══════════════════════════════════════════════════════════════
 *  一次 `tune` 的完整流程（顺序不可换）
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 * ① 字段归宿判定     骨骼 → 拒绝；未知 → 拒绝；白名单 → 继续
 * ② 归一 + 合法性    轴越界 / 非法 → 拒绝（不钳制）
 * ③ 与当前值比对     相同 → NO_CHANGE，什么都不落
 * ④ 作废旧候选链     同字段出现了不同的候选值 → 旧链全部作废
 * ⑤ 算置信度         本条的 + 同桶历史候选的 → 概率或合并
 * ⑥ 过门             条数够 && 置信度够 → ACTIVE；否则 CANDIDATE
 * ⑦ 落账本 + 落审计
 * ```
 *
 * ## ①③ 两步为什么不合并
 *
 * 字段判定必须**先于**归一：一个叫 `refuse_payment` 的字段，归一后
 * 得到的"值"是没有意义的（它压根不是轴）。先判字段，才能保证
 * 后面碰到的每个值都属于一个已知的 `kind`。反过来的话，要么得给
 * 骨骼字段编造一个 kind，要么就得在归一里再判一次 —— 两条路都会
 * 让"这个字段到底是什么"出现第二个真相来源。
 *
 * ## ④ 的必要性：防"被两句互相矛盾的话改坏"
 *
 * 候选桶的键是 `(字段, 值)`（见 [PersonaDelta.candidateKey]），所以
 * 不同值天然分成不同的桶、不会互相计数。但**旧桶还留在账本里**：
 * 用户过几天又说回原来的值，两条链会各自复活。作废旧链让"用户当前
 * 想要的值"始终只有一条链在攒证据。
 *
 * ⚠️ 作废是**删除**（候选从未生效，没有需要保留的历史），
 *    而回滚是**改状态**（已生效的变更必须留痕）。两者的区别是
 *    "有没有发生过"。
 *
 * ## 同步与线程安全
 *
 * 全部写操作与 `currentSpec()` 都标了 `@Synchronized`。账本是可变
 * 列表，"读到一半被改"会产出**一个从未存在过的中间人格**。
 *
 * @param preset 基础人格模板
 * @param auditSink 审计落库出口。null 表示只保留内存缓冲
 * @param gate 候选区闸门。可注入自定义阈值（P5 调手感时用）
 */
class DefaultPersonaTuner(
    private val preset: PersonaPreset,
    auditSink: ((PersonaAuditEvent) -> Unit)? = null,
    private val gate: CandidateGate = CandidateGate(),
) : PersonaTuner {

    /** 变更账本。含 CANDIDATE / ACTIVE / ROLLED_BACK 三种状态的全部条目 */
    private val ledger = mutableListOf<PersonaDelta>()

    /** 单调递增的序号。回放排序与 id 生成都用它，见 [restore] */
    private var nextSequence = 1

    /** 审计日志。对调用方只读 */
    val audit: PersonaAuditLog = PersonaAuditLog(auditSink)

    /** 账本快照（含候选与已回滚）。供透明度报告页用 */
    @Synchronized
    fun ledgerSnapshot(): List<PersonaDelta> = ledger.toList()

    @Synchronized
    override fun currentSpec(): PersonaSpec = PersonaSpec.derive(preset, ledger)

    @Synchronized
    override fun tune(
        ref: PersonaFieldRef,
        newValue: String,
        source: DeltaSource,
        evidence: List<String>,
        now: Long,
    ): TuneResult {
        // ── ① 字段归宿判定 ────────────────────────────────────
        val field = when (ref) {
            is PersonaFieldRef.Bones -> rejectWithAudit(
                audit,
                PersonaRejectionReason.PROTECTED_BONES,
                ref.raw,
                "这是安全骨骼，永远不可微调。骨架之外的说话方式都可以商量，但它不行。",
                now,
            )

            is PersonaFieldRef.Unknown -> rejectWithAudit(
                audit,
                PersonaRejectionReason.NOT_WHITELISTED,
                ref.raw,
                "这个字段不在可微调白名单内，无法识别要改什么。",
                now,
            )

            is PersonaFieldRef.Whitelisted -> ref.field
        }

        // ── ② 归一 + 合法性（越界不钳制）───────────────────────
        val normalized = field.normalize(newValue) ?: rejectWithAudit(
            audit,
            PersonaRejectionReason.INVALID_VALUE,
            field.name,
            "值「$newValue」对「${field.label}」不合法" +
                if (field.kind == ValueKind.AXIS) "（必须是 ${ToneAxes.MIN}..${ToneAxes.MAX} 的整数）" else "",
            now,
        )

        // ── ③ 与当前值比对 ────────────────────────────────────
        val oldValue = currentSpec().valueOf(field)
        if (oldValue == normalized) {
            audit.record(PersonaAuditEvents.noChange(field, normalized, now))
            return TuneResult.AlreadyAtValue(field, normalized)
        }

        // ── ⑤ 先算本条证据的置信度（非法证据在此被拒，不留半条账）──
        val ownConfidence = try {
            gate.confidenceOf(source, evidence)
        } catch (e: IllegalArgumentException) {
            rejectWithAudit(
                audit,
                PersonaRejectionReason.INVALID_EVIDENCE,
                field.name,
                e.message ?: "证据不合法",
                now,
            )
        }

        // ── ④ 作废同字段、不同值的候选链 ───────────────────────
        val conflicting = ledger.filter {
            it.isCandidate() && it.field == field && it.newValue != normalized
        }
        if (conflicting.isNotEmpty()) {
            ledger.removeAll(conflicting)
            audit.record(PersonaAuditEvents.candidateCleared(field, conflicting.size, now))
        }

        // ── ⑥ 过门 ────────────────────────────────────────────
        val sameBucket = ledger.filter {
            it.isCandidate() && it.field == field && it.newValue == normalized
        }
        val observedCount = sameBucket.size + 1
        val combined = gate.combine(sameBucket.map { it.confidence } + ownConfidence)
        val decision = gate.decide(source, combined, observedCount)

        val delta = PersonaDelta(
            id = PersonaDelta.idOf(nextSequence),
            field = field,
            oldValue = oldValue,
            newValue = normalized,
            source = source,
            evidence = evidence.map { it.trim() },
            // 存**本条自身**的置信度，不存合并值 —— 合并值要能被增量重算
            confidence = ownConfidence,
            state = if (decision.upgrade) DeltaState.ACTIVE else DeltaState.CANDIDATE,
            createdAt = now,
        )
        nextSequence++
        ledger += delta

        // ── ⑦ 落审计 ──────────────────────────────────────────
        return if (decision.upgrade) {
            audit.record(PersonaAuditEvents.applied(delta, now))
            TuneResult.Applied(delta, currentSpec())
        } else {
            audit.record(
                PersonaAuditEvents.candidateRecorded(
                    field = field,
                    newValue = normalized,
                    have = decision.observedCount,
                    need = decision.requiredCount,
                    confidence = decision.confidence,
                    source = source,
                    now = now,
                ),
            )
            TuneResult.CandidateRecorded(
                field = field,
                newValue = normalized,
                have = decision.observedCount,
                need = decision.requiredCount,
                confidence = decision.confidence,
            )
        }
    }

    @Synchronized
    override fun rollback(deltaId: String, now: Long): TuneResult {
        val index = ledger.indexOfFirst { it.id == deltaId }
        require(index >= 0) { "找不到要回滚的变更：$deltaId" }

        val delta = ledger[index]
        if (delta.state == DeltaState.ROLLED_BACK) {
            return TuneResult.AlreadyRolledBack(deltaId)
        }

        // ★ 原地改状态，不追加反向 delta —— 见 DeltaState.ROLLED_BACK 的注释
        val rolled = delta.copy(state = DeltaState.ROLLED_BACK)
        ledger[index] = rolled

        audit.record(PersonaAuditEvents.rolledBack(rolled, now))
        return TuneResult.RolledBack(rolled, currentSpec())
    }

    /**
     * 反漂移检查（纯查询）。
     *
     * `spec` 由调用方传入而不是内部取 `currentSpec()`：这样调用方可以
     * 对**任意一份快照**问"它漂移了吗"（例如界面上预览"回到最初会怎样"），
     * 而不仅限于当前状态。
     */
    @Synchronized
    override fun driftCheck(spec: PersonaSpec): DriftReport =
        PersonaDistance.report(spec, ledger.filter { it.isActive() })

    @Synchronized
    override fun recentChanges(n: Int): List<PersonaDelta> {
        require(n >= 0) { "n 不能为负" }
        return ledger.filter { it.isActive() }
            .sortedWith(compareByDescending<PersonaDelta> { it.createdAt }.thenByDescending { it.id })
            .take(n)
    }

    @Synchronized
    override fun restore(deltas: List<PersonaDelta>) {
        val ids = deltas.map { it.id }
        require(ids.size == ids.toSet().size) { "恢复的账本里存在重复 id" }

        ledger.clear()
        ledger.addAll(deltas)

        // ★ 序号必须推到历史最大值之后。否则新 delta 会与历史 delta
        //   撞 id，之后 rollback 会回滚到**错误的那一条** ——
        //   而它表现的只是"撤销了但没变化"。
        nextSequence = (deltas.mapNotNull { PersonaDelta.sequenceOf(it.id) }.maxOrNull() ?: 0) + 1
    }
}