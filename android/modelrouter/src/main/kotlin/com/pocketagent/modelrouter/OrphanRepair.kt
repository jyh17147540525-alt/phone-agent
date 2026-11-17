package com.pocketagent.modelrouter

/**
 * 「模型绑的 Key 本机不在了」时，这些孤儿配置该接到哪条凭据。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么这值得单独一个对象 + 一堆测试
 * ═══════════════════════════════════════════════════════════════
 *
 * 2026-11-17 真机上出现过一个很难查的故障：应用分身（XSpace / user 999）
 * 里 22 条模型配置全都绑着一条**本机已删掉**的凭据。`useKey` 找不到凭据就
 * 直接返回 null，模型一次都没被调用，而界面报的却是"回复里外文太多"。
 * 用户以为模型在乱说英文，实际是根本没开口。
 *
 * 修法有两半：
 *  ① 调度层拒绝选"凭据已不在"的模型（`pickModel(knownCredentialIds = …)`）；
 *  ② 孤儿配置自动改绑到本机唯一那条对话用凭据 —— 用户不用手动重导。
 *
 * ②里真正危险的不是改绑这个动作，而是**判断能不能改绑**：
 *  - 本机一条对话用凭据都没有 ⇒ 改绑无处可去，必须什么都不做；
 *  - 本机有两条以上 ⇒ 我们不知道用户想接哪条（OpenAI 的模型接到 Anthropic
 *    的 Key 上只会换来另一个错误），此时**不猜**才是对的；
 *  - 恰好一条 ⇒ 唯一合理的目标，可以改。
 *
 * 这段判断放在纯 Kotlin 模块里，就能离线钉死"不猜"这条纪律 ——
 * 放在 `AppContainer` 里则只能靠真机验证。
 */
object OrphanRepair {

    /**
     * 该把孤儿配置接到哪条凭据。
     *
     * @param llmCredentialIds 本机所有**对话用**（`CredentialPurpose.LLM`）凭据 id
     * @return 唯一的目标 id；0 条或 2 条以上时返回 null（表示"不动"）
     */
    fun adoptionTarget(llmCredentialIds: List<String>): String? =
        llmCredentialIds.filter { it.isNotBlank() }.distinct().singleOrNull()

    /**
     * 这次调用是不是"该动但动不了"的情况（有多个候选）。
     *
     * 用来区分"没什么可修"和"我不敢猜" —— 后者值得在日志里留一句，
     * 否则下次再出同样的问题，日志里又是一片空白。
     */
    fun ambiguous(llmCredentialIds: List<String>): Boolean =
        adoptionTarget(llmCredentialIds) == null &&
            llmCredentialIds.count { it.isNotBlank() } > 1
}
