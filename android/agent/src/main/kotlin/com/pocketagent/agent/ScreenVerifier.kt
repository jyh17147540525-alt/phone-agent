package com.pocketagent.agent

/**
 * **结果校验器** —— `Verifier` 的核心判定逻辑（纯 Kotlin，可离线测）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：`Verifier` 此前也是零实现
 * ═══════════════════════════════════════════════════════════════
 *
 * `AgentOrchestrator` 声明了 `Verifier.verify(...)`，全代码库没人实现它。
 * 本类补上其中**可以离线验证的那一半**：给定前后两份屏幕摘要，
 * 判断动作有没有生效。Android 侧只需把快照映射成 [ScreenDigest]。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 判定顺序是刻意的（每一条都有测试）
 * ═══════════════════════════════════════════════════════════════
 *
 * | 顺序 | 判据 | 结论 |
 * |---|---|---|
 * | 1 | 出现崩溃/无响应字样 | [VerifyResult.Unexpected] |
 * | 2 | 前台包名变了 | [VerifyResult.Unexpected] |
 * | 3 | 期望提到的文本**从无到有** | [VerifyResult.Success] |
 * | 4 | 期望提到的文本**从有到无** | [VerifyResult.Success] |
 * | 5 | **屏幕完全没变** | [VerifyResult.Failed] |
 * | 6 | 变了但看不出是否达成期望 | [VerifyResult.Unknown] |
 *
 * ⚠️ 第 1、2 条必须**排在前后**：崩溃对话框出现时页面内容也变了，
 *    若先判"文本出现"会把崩溃误判成成功。
 *
 * ⚠️ 第 5 条是本类最重要的纪律：
 *    **"什么都没变"绝不能判成成功。** 点了没反应是最常见的失败，
 *    把它判成成功等于告诉模型"你做到了"，然后它继续往下走。
 */
object ScreenVerifier {

    /**
     * 屏幕摘要 —— **纯数据**，由 Android 侧从快照映射。
     *
     * ⚠️ 刻意不含 `Bitmap`：那会把 Android 依赖带进 `:agent`，
     *    使它无法进入离线验证器（见 `SnapshotView` 的注释）。
     */
    data class ScreenDigest(
        /** 当前前台包名 */
        val packageName: String,
        /** 当前 Activity（可能为 null，如游戏/Flutter） */
        val activityName: String? = null,
        /** 屏幕上看得见的文本 */
        val visibleTexts: List<String> = emptyList(),
        /** 节点总数。用于判断"页面是否真的变了" */
        val nodeCount: Int = 0,
    ) {
        /** 全部小写文本，便于大小写不敏感比较。 */
        fun lowerTexts(): List<String> = visibleTexts.map { it.lowercase().trim() }

        /**
         * 内容指纹 —— 只用于"变没变"的粗判。
         *
         * ⚠️ 文本必须**排序后**再参与计算：
         *    列表重排不代表页面变了。顺序敏感会把"没变化"误判成"变了"，
         *    于是判定落到 Unknown 而不是明确的 Failed。
         */
        fun fingerprint(): Int {
            var h = packageName.hashCode()
            h = 31 * h + (activityName?.hashCode() ?: 0)
            h = 31 * h + nodeCount
            for (t in lowerTexts().sorted()) h = 31 * h + t.hashCode()
            return h
        }
    }

    /**
     * 崩溃/无响应特征词。
     *
     * ⚠️ 中英双语都要有 —— 系统语言是中文时弹的是「已停止」，
     *    英文时是 "has stopped"。只认一种会漏判，
     *    而漏判的后果是把它判成 Success。
     */
    private val CRASH_MARKERS = listOf(
        "已停止", "无响应", "屡次停止", "崩溃",
        "has stopped", "isn't responding", "keeps stopping", "not responding",
    )

    /**
     * 没有区分度的词 —— 出现在期望里也不拿来判"变了"。
     *
     * ⚠️ 它们在任何页面上都可能出现，拿它们当判据等于没有判定。
     */
    private val STOP_WORDS = setOf(
        "点击", "打开", "关闭", "然后", "之后", "出现", "消失", "应该",
        "成功", "页面", "按钮", "元素", "这个", "那个", "现在", "已经",
    )

    /**
     * 校验。
     *
     * @param expectation 这一步**期望**发生什么（模型自己说的，自然语言）
     * @param before 动作前的屏幕
     * @param after 动作后的屏幕
     */
    fun verify(
        expectation: String,
        before: ScreenDigest,
        after: ScreenDigest,
    ): VerifyResult {
        // ── 1. 崩溃优先：它让页面"变了"，但那是坏消息 ──────────
        val crash = after.lowerTexts().firstOrNull { t ->
            CRASH_MARKERS.any { t.contains(it) }
        }
        if (crash != null) {
            return VerifyResult.Unexpected("操作后出现崩溃/无响应提示：$crash")
        }

        // ── 2. 跳到别的应用 ────────────────────────────────────
        if (before.packageName.isNotBlank() &&
            after.packageName.isNotBlank() &&
            before.packageName != after.packageName
        ) {
            return VerifyResult.Unexpected(
                "前台应用从 ${before.packageName} 变成了 ${after.packageName}",
            )
        }

        // ── 3 / 4. 期望里提到的可见文本，前后有没有变化 ─────────
        //
        // ⚠️ 不能把期望"切成词"再去找：**中文没有词边界**。
        //    "打开设置页" 切出来就是整串，永远匹配不上屏幕上的"设置"。
        // ⇒ 反过来做：在**屏幕上真实存在的文本**里，找出哪些被期望提到了。
        val beforeSet = before.lowerTexts().toSet()
        val afterSet = after.lowerTexts().toSet()
        val mentioned = mentionedTexts(expectation, beforeSet + afterSet)

        for (k in mentioned) {
            val wasThere = k in beforeSet
            val isThere = k in afterSet
            if (!wasThere && isThere) return VerifyResult.Success
            // 期望的东西消失了 —— 例如"关闭对话框"
            if (wasThere && !isThere) return VerifyResult.Success
        }

        // ── 5. 什么都没变 ⇒ 失败 ──────────────────────────────
        if (before.fingerprint() == after.fingerprint()) {
            return VerifyResult.Failed("屏幕没有任何变化 —— 这个动作没有生效")
        }

        // ── 6. 变了，但看不出是否达成期望 ─────────────────────
        return VerifyResult.Unknown(
            "屏幕有变化，但期望「${expectation.take(40)}」里没有提到任何可见文本",
        )
    }

    /**
     * 在**候选文本**里找出哪些被 [expectation] 提到了。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 为什么是"提到"而不是"切词"
     * ═══════════════════════════════════════════════════════════
     *
     * 中文没有词边界。把期望切成词（"打开设置页" → 整串）永远匹配不上
     * 屏幕上的"设置"。反过来在真实可见文本里找"被提到"，才是对的。
     *
     * ⚠️ 三条过滤，每条都有理由：
     *  · 长度 < 2：单字（"点"、"关"）在任何页面都出现，没有区分度
     *  · 停用词：同上
     *  · 大小写：统一小写比较
     */
    fun mentionedTexts(
        expectation: String,
        candidates: Collection<String>,
    ): List<String> {
        val exp = expectation.lowercase().trim()
        if (exp.isEmpty()) return emptyList()
        return candidates
            .map { it.lowercase().trim() }
            .filter { it.length >= 2 }
            .filter { it !in STOP_WORDS }
            .distinct()
            .filter { exp.contains(it) }
    }
}
