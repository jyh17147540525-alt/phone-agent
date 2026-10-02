package com.pocketagent.mcp

import com.pocketagent.provider.gateway.dsh.DshConfigPatch

/**
 * 把能力桥的接入事实渲染成 **dsh profile 补丁层（`cordis.patch.yml`）** 的条目。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么是 cordis.patch.yml（而不是像网关那样写 settings.yaml）
 * ═══════════════════════════════════════════════════════════════
 *
 * MCP 客户端（`dsh-mcp-client`）的官方配置形态是**插件行**（`insert` 条目），
 * 而插件行的落点就是 profile 的补丁层 —— 真机上那份文件的注释原文：
 *
 * > Your patch layer for this dsh profile, applied after every bundle layer:
 * > a top-level YAML array of loader patch entries (… insert lists; `!!js` …)
 *
 * 与 `DshConfigPatch`（网关 → `settings.yaml`）是**两条不同的投递路径**，
 * 不要试图统一 —— 那会让"改 dsh 配置"这件事同时存在于两处。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三个要点
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **真 token 只以"一次性本地 token"的形态出现**（Bearer 头，随 App 重启轮换）。
 *    这与网关写进 `.credentials.yaml` 的是同一种东西，不是用户的 API Key。
 * 2. **渲染 = 纯字符串变换**（本模块，零 Android 依赖）；
 *    投递 = 另一件事（跨 UID 写容器，本期未解决，草稿由人取出）。
 * 3. **合并是逐行手术**：保住用户手写的其它 patch 条目与注释；
 *    遇到看不懂的结构**拒绝合并**（返回 [McpPatchMerge.CannotMerge]）而不是覆盖。
 *    同 `DshConfigPatch.mergeIntoCredentialsYaml` 的"我不处理我不理解的文件"。
 */
class DshMcpConfigPatch(
    /** 桥的 URL，形如 `http://127.0.0.1:12345/mcp`。取自 `McpHttpServer.mcpUrl`。 */
    val url: String,

    /** 本次启动的本地 token（**不是**用户的任何 Key）。 */
    val token: String,

    /** dsh 侧命名空间。工具将命名为 `mcp__<serverName>__screen_read`。 */
    val serverName: String = DEFAULT_SERVER_NAME,

    /** 单次工具调用的超时（毫秒）。**必须大于我们自己的 25 秒**，见分派器的注释。 */
    val toolCallTimeoutMs: Int = DEFAULT_TOOL_CALL_TIMEOUT_MS,
) {

    init {
        require(url.startsWith("http://127.0.0.1:")) {
            "url 必须是 http://127.0.0.1:<port>/… 形态（能力桥只监听回环）"
        }
        require(token.isNotBlank()) {
            "token 不能为空 —— 空 token 会让能力桥对所有请求回 401"
        }
        require(serverName.matches(Regex("[A-Za-z0-9_-]{1,32}"))) {
            "serverName 必须是 [A-Za-z0-9_-]{1,32}（dsh 侧的硬约束；写错会让插件加载失败）"
        }
        require(toolCallTimeoutMs in 1..600_000) {
            "toolCallTimeoutMs 越界：$toolCallTimeoutMs"
        }
    }

    /**
     * 渲染配置块（**含哨兵注释行**）。
     *
     * ⚠️ 哨兵行（[SENTINEL]）同时承担两个职责：告诉用户"这一块别手改"，
     *    以及让 [mergeIntoPatchFile] 能找到"我们的块"并原地替换（幂等）。
     *    改它会同时破坏这两件事 —— 它是一份常量，不是文案。
     *
     * ⚠️ `name:` 用**单引号**（与生态里所有示例一致）；其余值走
     *    `DshConfigPatch.yamlScalar`（无条件加双引号 —— token 是随机串，
     *    可能含 `:`/`#`/前导 `*` 等裸标量雷区，见那个函数的注释）。
     */
    fun entryBlock(): String = buildString {
        appendLine(SENTINEL)
        appendLine("- insert:")
        appendLine("    - id: $DEFAULT_PLUGIN_ID")
        appendLine("      name: '@deepseek-ai/dsh-mcp-client'")
        appendLine("      config:")
        appendLine("        serverName: ${DshConfigPatch.yamlScalar(serverName)}")
        appendLine("        transport: streamable-http")
        appendLine("        url: ${DshConfigPatch.yamlScalar(url)}")
        appendLine("        headers:")
        appendLine("          Authorization: ${DshConfigPatch.yamlScalar("Bearer $token")}")
        appendLine("        toolCallTimeoutMs: $toolCallTimeoutMs")
        append("        failOnStartupError: false")
    }

    /**
     * 渲染**给人取用的草稿文件**（用法说明注释 + 配置块）。
     *
     * 说明注释刻意写在文件里：它会随内容一起被拷进容器 —— 而下一个看到那份文件的人
     * （包括三个月后的我们）需要知道"这是怎么来的、哪些能改"。
     */
    fun draftDocument(): String = buildString {
        appendLine("# PocketAgent MCP 能力桥 —— 配置草稿（由应用生成）")
        appendLine("# 用法：把本文件内容放进手机 dsh 容器的 ~/.dsh/profiles/headless/cordis.patch.yml。")
        appendLine("# 若那份文件还是出厂状态（只有注释与 []），用下面整段替换 [] 即可；")
        appendLine("# 若已有其它条目，只把下面的 insert 块整段加进去，不要动别的。")
        appendLine(entryBlock())
    }

    /**
     * 把本块合并进一份既有的 `cordis.patch.yml` 全文。
     *
     * 四种情形（逐行手术，**其余字节不动**）：
     *
     * | 既有文件 | 动作 |
     * |---|---|
     * | null（不存在）/ 空 / 只有注释 | 直接给出本块 |
     * | 出厂状态（只有注释与 `[]`） | 用本块替换那一行 `[]`，注释保留 |
     * | 顶层数组（有其它 `- …` 条目） | 追加本块（**保序**，不动任何既有条目） |
     * | 含哨兵（我们以前写过） | **原地替换**本块 —— 这就是幂等性的实现 |
 *     | 其它（顶层非数组 / YAML 文档分隔符） | [McpPatchMerge.CannotMerge] —— 不猜 |
     *
     * ⚠️ 幂等是硬要求：用户多点一次按钮、应用反复重启重新投递，
     *    都不该让文件长出第二个 `insert` 块（dsh 侧那会让同名 serverName 冲突，
     *    而报错发生在插件加载时，指向"另一个 mcp-client 实例"——排查会很绕）。
     */
    fun mergeIntoPatchFile(existing: String?): McpPatchMerge {
        if (existing == null) return McpPatchMerge.Merged(entryBlock() + "\n")

        val normalized = existing.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split("\n").toMutableList()

        // ── (a) 已有本块 → 原地替换 ───────────────────────────────
        val sentinelIndex = lines.indexOfFirst { it.trimEnd() == SENTINEL }
        if (sentinelIndex >= 0) {
            // 本块的形状：哨兵行 + `- insert:`（顶格）+ 其后全部"缩进行/空行"。
            // ⚠️ 那个 `- insert:` 自己也是顶格行 —— 必须先显式吃掉它，
            //    再按"缩进或空行"吞剩余内容；否则扫描会在它身上立刻停下，
            //    结果是"新块插进来、旧块留在原地"（重复注册 serverName，静默）。
            var end = sentinelIndex + 1
            if (end < lines.size && lines[end].startsWith("- insert:")) end++
            while (end < lines.size) {
                val line = lines[end]
                val belongsToBlock = line.isBlank() || (line.isNotEmpty() && line[0].isWhitespace())
                if (!belongsToBlock) break
                end++
            }
            // 尾部的空行也归属本块（避免反复替换时空行越积越多）。
            var trimEnd = end
            while (trimEnd > sentinelIndex + 1 && lines[trimEnd - 1].isBlank()) trimEnd--

            lines.subList(sentinelIndex, trimEnd).clear()
            lines.addAll(sentinelIndex, entryBlock().split("\n"))
            return McpPatchMerge.Merged(lines.joinToString("\n").trimEnd('\n') + "\n")
        }

        // ── (b) 判断"我们看不看得懂" ──────────────────────────────
        val significant = lines.withIndex()
            .filter { (_, line) -> line.isNotBlank() && !line.trimStart().startsWith("#") }

        // YAML 文档分隔符：追加会静默变成"新文档"，语义不是数组追加 —— 拒绝。
        if (significant.any { (_, line) -> line.trim() == "---" }) {
            return McpPatchMerge.CannotMerge(
                "cordis.patch.yml 里含有 YAML 文档分隔符（---）—— 结构超出本工具的理解，拒绝合并。",
            )
        }

        return when {
            significant.isEmpty() -> McpPatchMerge.Merged(appendAtEnd(lines))

            significant.size == 1 && significant[0].value.trim() == "[]" -> {
                // 出厂状态：把 `[]` 那一行替换成我们的块（其余注释原样保留）。
                val index = significant[0].index
                lines.subList(index, index + 1).clear()
                lines.addAll(index, entryBlock().split("\n"))
                McpPatchMerge.Merged(lines.joinToString("\n").trimEnd('\n') + "\n")
            }

            significant[0].value.trimStart().startsWith("-") ->
                McpPatchMerge.Merged(appendAtEnd(lines))

            else -> McpPatchMerge.CannotMerge(
                "cordis.patch.yml 的顶层不是数组（可能是我们没见过的结构）—— 拒绝合并，避免破坏你已有的配置。",
            )
        }
    }

    /** 追加到文件末尾（去掉尾部空行后加一个空行分隔）。 */
    private fun appendAtEnd(lines: List<String>): String {
        val trimmed = lines.toMutableList()
        while (trimmed.isNotEmpty() && trimmed.last().isBlank()) trimmed.removeAt(trimmed.size - 1)
        val body = trimmed.joinToString("\n")
        return if (body.isEmpty()) entryBlock() + "\n" else body + "\n\n" + entryBlock() + "\n"
    }

    companion object {
        /**
         * 哨兵行。
         *
         * ⚠️ 它在 [mergeIntoPatchFile] 的**匹配逻辑**里出现（`== SENTINEL`），
         *    不是纯文案 —— 改它等于宣布"以前写的块不再被识别"（会变成追加第二块）。
         */
        const val SENTINEL: String = "# ─── PocketAgent MCP 能力桥（由应用生成，请勿手改本块）───"

        /** 插件实例 id（loader 内部标识；与 serverName 是两件事）。 */
        const val DEFAULT_PLUGIN_ID: String = "mcp-pocketagent"

        /**
         * 默认 `serverName`。
         *
         * 它决定模型看到的工具名：`mcp__phone__screen_read`。
         * ⚠️ 一旦真机用过就不要改 —— 改名会让模型侧的工具名全变（历史会话里的
         *    调用记录与提示词缓存前缀都对不上，这是 dsh 文档自己写明的纪律）。
         */
        const val DEFAULT_SERVER_NAME: String = "phone"

        /** 默认工具超时。必须大于分派器的 25 秒（设计文档 §附录）。 */
        const val DEFAULT_TOOL_CALL_TIMEOUT_MS: Int = 30_000
    }
}

/** 合并结果。 */
sealed interface McpPatchMerge {

    data class Merged(val text: String) : McpPatchMerge

    /**
     * 无法安全合并。
     *
     * ⚠️ 这不是"出错"，是**刻意放弃**：宁可原样保留用户的文件、把原因说清楚，
     *    也不能把"我们看懂了"当成前提去做破坏性写入（同凭据合并的 null 约定）。
     */
    data class CannotMerge(val reason: String) : McpPatchMerge
}
