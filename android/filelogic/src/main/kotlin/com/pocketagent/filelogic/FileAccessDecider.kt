package com.pocketagent.filelogic

import java.util.Locale

/**
 * 文件操作的裁决器 —— **把"能不能动"这件事从"怎么动"里切出来**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  判定顺序（短路返回，**顺序不可调换**）
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 * 0. 有没有授权范围          ← 没有 → 引导去配置，而不是报"操作失败"
 * 1. 路径归一化              ← 失败 → 拒绝
 * 2. 范围判定                ← 不在任何授权根下 → 拒绝
 * 3. 位置黑名单              ← 在范围内但命中 → 拒绝
 * 4. MOVE 的目标同样过 1~3   ← 目标越界 → 拒绝
 * 5. 操作类型 → 放行/要确认   ← 确认标志**只在这一步被消费**
 * ```
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 为什么"确认"排在最后，而且**绝不能**跳过前面几步
 * ═══════════════════════════════════════════════════════════════
 *
 * 最自然的写法是：
 *
 * ```kotlin
 * if (request.confirmedByUser) return Allowed(...)   // ✗ 危险
 * ```
 *
 * 把"用户已确认"当作一个提前返回的理由。它读起来很合理 ——
 * 用户都点过确认了，还检查什么？
 *
 * **但那样一来，确认就变成了万能钥匙**：只要界面上任何一个确认框被点过一次，
 * 任何路径、任何操作都能通过。而具体的攻击路径是很现实的：
 *
 * ```
 * 1. agent 请求删除 /Documents/我的笔记.md
 * 2. 确认框弹出，用户看了，点了"确认"
 * 3. 在真正执行之前，agent 把路径改成 /Documents/.ssh/id_rsa
 * 4. 裁决器看到 confirmedByUser = true → 直接放行
 * ```
 *
 * 第 3 步不需要恶意 —— 它可以是模型的一次"修正"、一次重试、
 * 或者插件在两次调用之间改了参数。**用户确认的是他看到的那个路径，
 * 不是这个函数收到的那个路径。**
 *
 * 所以这里的纪律是：**归一化、范围、黑名单在每一次 `decide` 里都重新跑一遍**，
 * `confirmedByUser` 只是一个"如果结论是 RequireConfirmation，
 * 就把它降级为 Allowed"的开关。它不改变任何前置判定。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么黑名单排在范围**之后**（与 `DefaultSafetyGuard` 相反）
 * ═══════════════════════════════════════════════════════════════
 *
 * `DefaultSafetyGuard` 把内置黑名单排在第一位，理由是"它是防用户/插件
 * 把限制调松的单向阀"。**那条理由在这里不成立** —— 本模块里，
 * 范围与黑名单**都是拒绝**，不存在"谁覆盖谁"。两者都不参与任何覆盖逻辑。
 *
 * 既然都只是拒绝，那就该按**说明的准确度**排序：
 * 路径根本不在授权范围内时，说"你没授权这个位置"比说"这是密钥文件"
 * 更接近用户需要知道的事（前者他可以去授权，后者他去哪都没用）。
 *
 * ⚠️ 但有一件事是相同的：**黑名单不能被"把授权范围划大"绕过**。
 *    用户授权整块内部存储之后，`.ssh` 依然不可读 —— 见 [DenyRules]。
 */
class FileAccessDecider(
    private val scope: FileScope,

    /**
     * 通道是否支持跨授权根移动。
     *
     * SAF **不支持**（`moveDocument` 只在同一 tree 内工作），所以默认 false。
     * 将来接 Shizuku 通道时它可以是真的 —— 那时才把它打开，
     * **不要因为"以后可能用得上"就提前放宽**。
     */
    private val allowsCrossRootMove: Boolean = false,
) {

    fun decide(request: FileOperationRequest): FileAccessDecision {
        // ── 步骤 0：有没有授权范围 ──────────────────────────────
        // 这不是"错误"，是"还没配置"。分开的意义在于界面能给出正确的下一步：
        // 去选一个目录，而不是显示一个红色的"操作失败"。
        if (scope.isEmpty) {
            return FileAccessDecision.Denied(
                reason = DenyReason.NO_SCOPE,
                userMessage = "我还没有可以操作的文件位置。请先在「文件权限」里选一个目录，" +
                    "之后我才能读写里面的文件。",
            )
        }

        // ── 步骤 1：归一化（每次都跑）────────────────────────────
        val norm = when (val r = PathNormalizer.normalize(request.path)) {
            is PathNormalizer.Result.Rejected -> return FileAccessDecision.Denied(
                reason = DenyReason.MALFORMED_PATH,
                userMessage = "这个路径我看不懂，所以没有动它：${r.reason}",
            )

            is PathNormalizer.Result.Valid -> r.path
        }

        // ── 步骤 2：范围（每次都跑）──────────────────────────────
        val root = scope.rootContaining(norm) ?: return FileAccessDecision.Denied(
            reason = DenyReason.OUT_OF_SCOPE,
            userMessage = "「$norm」不在你授权的任何目录里，我不会去动它。" +
                "如果确实需要，请先在「文件权限」里把那个目录加进来。",
        )

        // ── 步骤 3：位置黑名单（每次都跑）────────────────────────
        scope.denyRules.matches(norm)?.let { hit ->
            return FileAccessDecision.Denied(
                reason = DenyReason.DENIED_LOCATION,
                userMessage = "${hit.userMessage}（位置：$norm）" +
                    "这一条不受授权范围影响，是固定的安全底线。",
            )
        }

        // ── 步骤 4：MOVE 的目标也要过 1~3（每次都跑）─────────────
        //
        // ⚠️ 这一步最容易被漏掉，因为"目标路径"是请求里第二个路径字段，
        //    而人写代码时的注意力全在"主路径"上。
        //    漏掉的后果很直接：`把 /Documents/a.md 移动到 /Documents/.ssh/id_rsa`
        //    —— 主路径完全合法，于是私钥被覆盖。
        var destNorm: String? = null
        if (request.op == FileOp.MOVE) {
            val rawDest = request.destinationPath
            if (rawDest.isNullOrBlank()) {
                return FileAccessDecision.Denied(
                    reason = DenyReason.MISSING_DESTINATION,
                    userMessage = "移动操作需要给出目标位置，但这次没有。",
                )
            }

            destNorm = when (val r = PathNormalizer.normalize(rawDest)) {
                is PathNormalizer.Result.Rejected -> return FileAccessDecision.Denied(
                    reason = DenyReason.MALFORMED_PATH,
                    userMessage = "目标路径我看不懂，所以没有动它：${r.reason}",
                )

                is PathNormalizer.Result.Valid -> r.path
            }

            val destRoot = scope.rootContaining(destNorm)
                ?: return FileAccessDecision.Denied(
                    reason = DenyReason.DESTINATION_OUT_OF_SCOPE,
                    userMessage = "目标位置「$destNorm」不在你授权的任何目录里，我不会往那儿写。",
                )

            scope.denyRules.matches(destNorm)?.let { hit ->
                return FileAccessDecision.Denied(
                    reason = DenyReason.DESTINATION_DENIED_LOCATION,
                    userMessage = "${hit.userMessage}（目标位置：$destNorm）",
                )
            }

            // 跨授权根的移动：在 SAF 上做不到原子操作，拆成"复制+删除"会留下
            // 两个副本或零个副本，两种都很难向用户解释。所以直接拒绝，
            // 并告诉用户分两步做 —— 那两步各自都是可见、可撤销的。
            if (!allowsCrossRootMove && destRoot.id != root.id) {
                return FileAccessDecision.Denied(
                    reason = DenyReason.CROSS_ROOT_MOVE,
                    userMessage = "「${root.displayName}」和「${destRoot.displayName}」是两个不同的授权目录，" +
                        "我没法一步把它们之间移动。可以先在「${root.displayName}」里复制一份，" +
                        "再删掉原来那个。",
                )
            }
        }

        // ── 步骤 5：操作类型裁决（确认标志只在这里被消费）────────
        val confirmReason = confirmReasonFor(request)

        val target = ResolvedTarget(
            op = request.op,
            normalizedPath = norm,
            relativePath = PathNormalizer.relativeTo(norm, root.path) ?: "",
            root = root,
            destinationNormalizedPath = destNorm,
            destinationRelativePath = destNorm?.let {
                PathNormalizer.relativeTo(it, root.path)
            },
        )

        if (confirmReason != null && !request.confirmedByUser) {
            return FileAccessDecision.RequireConfirmation(
                target = target,
                reason = confirmReason,
                userMessage = confirmMessage(confirmReason, request, norm, destNorm),
            )
        }

        return FileAccessDecision.Allowed(
            target = target,
            // ⚠️ 记的是"这次放行是不是确认换来的"，不是"这个操作危不危险"。
            //    审计日志要靠它回答"用户一共确认过多少次删除"。
            wasConfirmed = confirmReason != null,
        )
    }

    /**
     * 这个操作要不要确认。返回 null = 直接放行。
     *
     * ⚠️ 表格很短，但每一格都有代价：
     *
     * | 操作 | 目标状态 | 结论 |
     * |---|---|---|
     * | READ / LIST | — | 放行。读取可撤销（不改变任何东西） |
     * | WRITE | 不存在 | 放行。新建不打断用户 |
     * | WRITE | 已存在 | **确认**。这是覆盖，用户的原内容没了 |
     * | DELETE | 文件 | **确认** |
     * | DELETE | 目录 | **确认**（文案里要说清子项数） |
     * | MOVE | — | **确认**。含重命名 |
     */
    private fun confirmReasonFor(request: FileOperationRequest): ConfirmReason? = when (request.op) {
        FileOp.READ, FileOp.LIST -> null

        FileOp.WRITE ->
            if (request.targetExists) ConfirmReason.OVERWRITE_EXISTING else null

        FileOp.DELETE ->
            if (request.isDirectory) ConfirmReason.DELETE_DIRECTORY else ConfirmReason.DELETE_FILE

        FileOp.MOVE -> ConfirmReason.MOVE_OR_RENAME
    }

    /**
     * 生成确认文案。
     *
     * ⚠️ 三条要求，缺一条这段文案就失去意义：
     *  1. **说清要动的是哪个文件** —— 用文件名，不用完整路径（路径太长会挤掉别的信息，
     *     完整路径在确认页的列表里另有一行）
     *  2. **说清会发生什么** —— "原有的内容会被整体替换"而不是"确认写入吗"
     *  3. **说清能不能撤销** —— 用户判断"要不要点"靠的就是这一句
     */
    private fun confirmMessage(
        reason: ConfirmReason,
        request: FileOperationRequest,
        norm: String,
        destNorm: String?,
    ): String {
        val name = PathNormalizer.leafOf(norm)

        // 只在真正拿得到信息时才写进括号 —— 见 FileOperationRequest 的字段注释
        val meta = listOfNotNull(
            request.existingSizeBytes?.let { formatBytes(it) },
            request.existingModifiedAtText?.takeIf { it.isNotBlank() }?.let { "$it 修改" },
        )
        val metaText = if (meta.isEmpty()) "" else "（${meta.joinToString("，")}）"

        return when (reason) {
            ConfirmReason.OVERWRITE_EXISTING ->
                "「$name」已经存在$metaText。继续写入会把它的原有内容**整体替换掉**，" +
                    "而且没法撤销。要覆盖它吗？"

            ConfirmReason.DELETE_FILE ->
                "要删除「$name」$metaText。删掉之后没法从 PocketAgent 找回来" +
                    "（系统的回收站里可能会有，但我不保证）。要删除吗？"

            ConfirmReason.DELETE_DIRECTORY -> {
                val count = request.descendantCount
                val tail = "删掉之后没法从 PocketAgent 找回来。要删除吗？"
                when {
                    // 探测不到 —— 如实说，不猜
                    count == null -> "要删除文件夹「$name」。$tail"
                    count == 0 -> "要删除空文件夹「$name」。$tail"
                    else -> "要删除文件夹「$name」，里面还有 $count 个子项，" +
                        "它们会**一起被删掉**。$tail"
                }
            }

            ConfirmReason.MOVE_OR_RENAME -> {
                val destName = destNorm?.let { PathNormalizer.leafOf(it) } ?: "?"
                // 同一个父目录 → 是重命名；不同 → 是移动。两种说法用户的心智模型不同
                val sameParent = destNorm != null &&
                    PathNormalizer.parentOf(destNorm) == PathNormalizer.parentOf(norm)
                if (sameParent) {
                    "要把「$name」重命名成「$destName」。" +
                        "如果「$destName」已经存在，它会被替换掉。要继续吗？"
                } else {
                    "要把「$name」移动到「$destName」那里。" +
                        "如果目标位置已经有同名文件，它会被替换掉。要继续吗？"
                }
            }
        }
    }

    companion object {
        /**
         * 人类可读的字节数。
         *
         * ⚠️ 显式用 [Locale.ROOT]，不用默认 Locale。
         *    某些区域（德、法）的小数分隔符是逗号，`%.1f` 会输出「1,5 MB」，
         *    而这条字符串会进**测试断言**与**审计日志**。跟着系统区域漂移的
         *    输出没法被稳定断言，也没法被稳定比对。
         */
        internal fun formatBytes(bytes: Long): String = when {
            bytes < 0 -> "未知大小"
            bytes < 1024 -> "$bytes B"
            bytes < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
