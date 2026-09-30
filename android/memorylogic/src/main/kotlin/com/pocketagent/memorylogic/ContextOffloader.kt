package com.pocketagent.memorylogic

import com.pocketagent.agentlogic.FilterOutcome

/**
 * 上下文卸载器 —— 把大体积内容从上下文里搬出去、存到外部，再能原样取回来。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么需要它（需求③的另一半）
 * ═══════════════════════════════════════════════════════════════
 *
 * `AgentBudget` 是**被动熔断**：超预算就停，防的是任务跑飞。
 * 本类是**主动降耗**：根本不把那几万个字符塞进上下文，防的是跑得贵。
 * 两者互补，见架构文档 §2.3。
 *
 * 腾讯那组实测数据里短期记忆 token 降了 61%，靠的就是这一件事。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 顺序：隐私关卡永远在写入之前
 * ═══════════════════════════════════════════════════════════════
 *
 * 红线 §8.2-2。理由不是"规范如此"，而是一次真实事故的形状：
 * 虚拟屏实验里撞见过用户真实的微信聊天列表。**卸载是先落盘再看内容**，
 * 所以过滤一旦放到写入之后，隐私就已经在磁盘上了 ——
 * 而且没有任何一处会报错。
 *
 * 这里把顺序做进两处：
 *
 * 1. [offload] 的**第一步**就是 [PrivacyGate.review]，看都不看内容。
 * 2. [OffloadBlobStore.write] 只接受 [OffloadPayload]（构造私有），
 *    于是"绕过关卡直接写"在模块外编译不过。
 *
 * ═══════════════════════════════════════════════════════════════
 *  与 `:memory`（Room）的分工
 * ═══════════════════════════════════════════════════════════════
 *
 * 这里不落库、不碰数据库。L0 的元数据（哪一轮、什么时候、指向哪个引用）
 * 归 `:memory`；**大内容本身**走 [OffloadBlobStore]（SAF 目录）。
 * 本类只负责"决定写什么、编码成什么、怎么验回来"。
 */
class ContextOffloader(private val store: OffloadBlobStore) {

    /**
     * 卸载一份内容。
     *
     * 三步，顺序不可调换：
     *
     * 1. **过隐私关卡**。被否决 → [OffloadOutcome.Dropped]，**一个字节都不写**。
     * 2. 编码 + 记账（内容指纹、字节数、引用 id）。
     * 3. 落盘。
     *
     * @param now 卸载时刻。由调用方传入（内核不读时钟）。
     * @throws IllegalArgumentException 关卡自己抛出的硬失败（例如请求伪造了
     *   `alreadyFiltered` 标记）。**刻意不吞**：伪造标记本身就是 bug，
     *   静默修正会让它一直存在。
     */
    fun offload(draft: OffloadDraft, gate: PrivacyGate, now: Long): OffloadOutcome {
        // ── 步骤 1：隐私关卡。★ 必须是第一件事，不能有任何前置的写操作 ──
        val allowed = when (val decision = gate.review(draft.request)) {
            is FilterOutcome.Dropped -> return OffloadOutcome.Dropped(decision.reason)
            is FilterOutcome.Allowed -> decision
        }

        // ── 步骤 2：编码与记账 ──────────────────────────────────
        val encoded = OffloadCodec.encode(draft.kind, draft.taskId, draft.body, now)
        val ref = OffloadRef(
            // 按内容派生 id（不含时间），保证重试落回同一个文件。见 OffloadRef.idOf。
            id = OffloadRef.idOf(draft.kind, draft.taskId, draft.body),
            kind = draft.kind,
            taskId = draft.taskId,
            bytes = OffloadCodec.byteSize(encoded),
            sha256 = OffloadCodec.sha256(encoded),
            createdAt = now,
        )

        // ── 步骤 3：落盘。参数类型不给出"不过关卡"的可能 ──────────
        store.write(OffloadPayload.cleared(ref = ref, encoded = encoded, plan = allowed.plan))
        return OffloadOutcome.Stored(ref)
    }

    /**
     * 恢复一份内容。
     *
     * 三道检查，任何一道不过都报 [RecallOutcome.Corrupted]：
     *
     * 1. 文件在不在（不在 → [RecallOutcome.Missing]）
     * 2. 内容指纹与 [OffloadRef.sha256] 是否一致
     * 3. 解开外壳之后**重编码一次**，确认与读到的文本完全相同（往返闭合）
     *
     * 第 3 道看着冗余，但它挡的是最坏的一种失败：内容被"成功"恢复了，
     * 却不是当初那一份。用户不会知道，只会觉得助理记错了。
     */
    fun recall(ref: OffloadRef): RecallOutcome {
        val text = store.read(ref) ?: return RecallOutcome.Missing(ref)

        if (OffloadCodec.sha256(text) != ref.sha256) {
            return RecallOutcome.Corrupted(ref, "卸载内容指纹不符（文件被截断或被改写）")
        }

        return when (val decoded = OffloadCodec.decode(text)) {
            is DecodeResult.Failed -> RecallOutcome.Corrupted(ref, decoded.reason)
            is DecodeResult.Ok -> {
                val reencoded = OffloadCodec.encode(decoded.kind, decoded.taskId, decoded.body, decoded.createdAt)
                if (reencoded != text) {
                    RecallOutcome.Corrupted(ref, "卸载内容往返不闭合（重编码结果与原文不一致）")
                } else {
                    RecallOutcome.Restored(decoded.body)
                }
            }
        }
    }

    /** 进上下文的那一行占位索引。见 [OffloadPolicy.placeholderFor]。 */
    fun placeholder(ref: OffloadRef): String = OffloadPolicy.placeholderFor(ref)
}