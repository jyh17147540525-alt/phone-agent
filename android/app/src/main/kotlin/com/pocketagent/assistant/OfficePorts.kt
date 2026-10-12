package com.pocketagent.assistant

import android.content.Context
import com.pocketagent.agentlogic.OfficeToolExecutor
import com.pocketagent.agentlogic.OfficeToolOutcome
import com.pocketagent.capability.SafDirectoryGrants
import com.pocketagent.capability.SafFileChannel
import com.pocketagent.filelogic.ChannelResult
import com.pocketagent.filelogic.DirEntry
import com.pocketagent.filelogic.DocumentFormat
import com.pocketagent.filelogic.FileChannel
import com.pocketagent.filelogic.FileReadResult
import com.pocketagent.filelogic.OfficeRequest
import com.pocketagent.filelogic.OfficeText
import com.pocketagent.filelogic.ScopeRoot
import com.pocketagent.filelogic.WorkspacePolicy
import com.pocketagent.mcp.OfficeWorkspace
import com.pocketagent.mcp.OfficeWorkspacePort

/**
 * 办公/文件方向在 `:app` 侧的**全部接线**：工作区解析 + 工具执行器。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它补上的是哪一段断链
 * ═══════════════════════════════════════════════════════════════
 *
 * 接之前（实测）：
 *
 * ```
 *   :filelogic   OfficeTool 定义 ✅   OfficeToolValidator ✅   XlsxWriter/DocxWriter ✅
 *                     ↓ 只被 ↓
 *   :agentlogic  OfficeAgentLoop …… 但它自己零引用
 *                     ↓ 需要一个 ↓
 *   （缺）       OfficeToolExecutor —— **一个没有实现的端口**
 * ```
 *
 * ⇒ 链条断在"最后一百米"：**工具定义与校验都写好了，但没有人真的去碰文件系统**。
 *   本文件就是那最后一百米。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它给 `:capability` 的 SAF 授权找到了第二个用途
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户授权给本应用的那个目录，此前只有「第 0 档能力」页的手点按钮能用。
 * 而 dsh 跑在 Termux 容器里、**UID 不同**，够不到那棵树 ——
 * 于是这里成为 dsh 唯一一条"在用户授权的目录里读写"的路径。
 * **权限没有被放大**：它一直在用户授的那棵树里。
 */

/**
 * 已解析出的工作区 —— 根 + 策略 + 名字。
 *
 * ⚠️ 三个字段**一起**从同一个地方解析出来，不许任何一方各自去猜。
 *    "写入目录到底是哪个"这种不一致在真机上表现为"文件写到别处去了"，
 *    而没有任何一处会报错。
 */
data class ResolvedOfficeWorkspace(
    val name: String,
    val root: ScopeRoot,
    val policy: WorkspacePolicy,
)

/**
 * 从 SAF 授权解析出当前工作区。
 *
 * ⚠️ 当前只取**第一个**授权目录 —— 因为「命名工作区」的注册表（`WorkspaceStore`）
 *    还没有接进 `AppContainer`（`files/workspaces` 在设备上不存在，实测确认）。
 *    多目录支持要等那件事落地；现在**如实只支持一个**，
 *    而不是"假装支持多个然后随机挑一个"。
 */
class SafOfficeWorkspaceResolver(
    private val grants: SafDirectoryGrants,
    /** 策略。当前是默认值 —— 见 [DEFAULT_POLICY] 的注释。 */
    private val policy: WorkspacePolicy = DEFAULT_POLICY,
) {

    fun resolve(): ResolvedOfficeWorkspace? {
        val root = grants.grantedRoots().firstOrNull() ?: return null
        return ResolvedOfficeWorkspace(
            name = root.displayName.ifBlank { "已授权目录" },
            root = root,
            policy = policy,
        )
    }

    companion object {
        /**
         * 默认策略：可写、**不可删**、产出落在 `输出/`、单文件 4 MB。
         *
         * ⚠️ 这不是随手取的默认值，`WorkspacePolicy()` 的构造默认就是它 ——
         *    这里显式写出来，是为了让"我们用了什么策略"在这一屏可见，
         *    而不是藏在另一个模块的 `companion` 里。
         */
        val DEFAULT_POLICY = WorkspacePolicy()

        /** 生产入口：用应用的 SAF 授权。 */
        fun of(context: Context): SafOfficeWorkspaceResolver =
            SafOfficeWorkspaceResolver(SafDirectoryGrants(context))
    }
}

/** 把解析器接到 `:mcp` 的工作区端口上（给模型看"能不能干活、规矩是什么"）。 */
class SafOfficeWorkspacePort(
    private val resolver: SafOfficeWorkspaceResolver,
) : OfficeWorkspacePort {
    override fun current(): OfficeWorkspace? {
        val resolved = resolver.resolve() ?: return null
        return OfficeWorkspace(name = resolved.name, policy = resolved.policy)
    }
}

/**
 * 工具执行器 —— `:filelogic` 的 [OfficeRequest] → 真实文件系统。
 *
 * ⚠️ 到这里时**路径已经过校验**（`OfficeToolValidator` 保证了相对路径、
 *    无穿越、写操作落在产出目录下）。所以本类**不重复判定** ——
 *    在这里"顺手再检查一下"会制造一份规则之外的影子规则，
 *    而漂移的方向永远是松的那一份生效。
 *
 * ⚠️ 每个分支都必须返回**含关键信息的摘要**（写了哪个文件、几行）——
 *    它会进 `OfficeRunResult.trace` 回给模型，也会作为工具结果进上下文。
 *    "成功"两个字等于什么都没说。
 */
class SafOfficeToolExecutor(
    private val resolver: SafOfficeWorkspaceResolver,
    private val channel: FileChannel,
) : OfficeToolExecutor {

    override suspend fun execute(request: OfficeRequest): OfficeToolOutcome {
        val workspace = resolver.resolve()
            ?: return OfficeToolOutcome.Failed(
                "用户还没有授权任何目录，所以现在没有可操作的工作区。" +
                    "请引导用户打开本应用的「设置 → 第 0 档能力」，授权一个目录后再试。",
            )

        return when (request) {
            is OfficeRequest.ListFiles -> listFiles(workspace, request)
            is OfficeRequest.ReadText -> readText(workspace, request)
            is OfficeRequest.WriteText -> writeText(workspace, request)
            is OfficeRequest.MakeTable -> makeTable(workspace, request)
        }
    }

    // ── 只读 ────────────────────────────────────────────────────

    private suspend fun listFiles(
        workspace: ResolvedOfficeWorkspace,
        request: OfficeRequest.ListFiles,
    ): OfficeToolOutcome {
        val path = request.relativePath.ifEmpty { "." }
        return when (val result = channel.list(workspace.root, path)) {
            is ChannelResult.Ok -> {
                val entries = result.value
                if (entries.isEmpty()) {
                    OfficeToolOutcome.Ok("目录「$path」是空的。")
                } else {
                    // ⚠️ 目录在前、名字排序 —— 模型靠这个列表决定下一步读哪个文件，
                    //    顺序稳定能让它的规划在不同轮次之间可比。
                    val sorted = entries.sortedWith(
                        compareByDescending<DirEntry> { it.isDirectory }.thenBy { it.name },
                    )
                    val lines = sorted.joinToString("\n") { entry ->
                        val kind = if (entry.isDirectory) "目录" else "文件"
                        val size = entry.sizeBytes?.let { " · $it 字节" } ?: ""
                        "- $kind：${entry.name}$size"
                    }
                    OfficeToolOutcome.Ok(
                        "目录「$path」下 ${entries.size} 项：\n$lines\n" +
                            "（只列直接子项，不递归。要看子目录请再调一次并带上它的路径。）",
                    )
                }
            }

            is ChannelResult.Unavailable -> OfficeToolOutcome.Failed(
                "文件通道不可用（${result.reason}）。请引导用户去「设置 → 第 0 档能力」重新授权目录。",
            )

            is ChannelResult.Failed -> OfficeToolOutcome.Failed(
                "列目录失败：${result.reason}",
            )
        }
    }

    private suspend fun readText(
        workspace: ResolvedOfficeWorkspace,
        request: OfficeRequest.ReadText,
    ): OfficeToolOutcome {
        return when (val result = channel.read(workspace.root, request.relativePath, request.maxBytes)) {
            is ChannelResult.Ok -> when (val value = result.value) {
                is FileReadResult.Text ->
                    OfficeToolOutcome.Ok(
                        "已读取「${request.relativePath}」（${value.content.toByteArray(Charsets.UTF_8).size} 字节）：\n" +
                            value.content,
                    )

                // ★ 三种读结果分开报。合并成一句"读不了"会让模型不知道该换个文件还是该放弃。
                is FileReadResult.TooLarge -> OfficeToolOutcome.Failed(
                    "文件太大：${value.sizeBytes} 字节，超过本次上限 ${value.limitBytes} 字节。" +
                        "请只读取需要的那一部分（本工具暂不支持分段读取），或换一个小一些的文件。",
                )

                is FileReadResult.NotUtf8 -> OfficeToolOutcome.Failed(
                    "文件不是 UTF-8 文本（${value.reason}），本工具**不会尽力解码** —— " +
                        "尽力解码的结果是乱码，而模型会把乱码当成内容。请换一个文本文件。",
                )
            }

            is ChannelResult.Unavailable -> OfficeToolOutcome.Failed(
                "文件通道不可用（${result.reason}）。请引导用户重新授权目录。",
            )

            is ChannelResult.Failed -> OfficeToolOutcome.Failed("读取失败：${result.reason}")
        }
    }

    // ── 写 ──────────────────────────────────────────────────────

    private suspend fun writeText(
        workspace: ResolvedOfficeWorkspace,
        request: OfficeRequest.WriteText,
    ): OfficeToolOutcome {
        return when (val result = channel.write(workspace.root, request.relativePath, request.content)) {
            is ChannelResult.Ok -> OfficeToolOutcome.Ok(
                "已写入「${request.relativePath}」（${request.content.toByteArray(Charsets.UTF_8).size} 字节）。",
            )

            is ChannelResult.Unavailable -> OfficeToolOutcome.Failed(
                "文件通道不可用（${result.reason}）。请引导用户重新授权目录。",
            )

            is ChannelResult.Failed -> OfficeToolOutcome.Failed("写入失败：${result.reason}")
        }
    }

    /**
     * 生成表格。
     *
     * ═══════════════════════════════════════════════════════════
     *  ⚠️ 只有 CSV 真的能写；XLSX 目前**写不出去**，如实报错
     * ═══════════════════════════════════════════════════════════
     *
     * `XlsxWriter.write(...)` 返回的是 **`ByteArray`**，而
     * `FileChannel.write` 的签名是 `(root, relativePath, content: String)` ——
     * **通道只支持文本**，全模块没有字节重载（实测确认）。
     *
     * 而 `OfficeToolValidator` 是**允许** `format = xlsx` 的 ——
     * 也就是说"校验说可以、执行写不出"。这是一处真实的**能力缺口**，
     * 不是本文件的 bug。
     *
     * ⇒ 这里**如实报错并说明成因**，而不是：
     *   · 静默降级成 csv（用户要的是 xlsx，拿到 csv 会以为"Excel 打不开是 Excel 的问题"）；
     *   · 把二进制当字符串写（写出去的是一个损坏的文件，且**没有任何一处会报错**）。
     *   两者都是本项目头号 bug 形态「安静地少做一件事」。
     *
     * 补法（留给下一批）：给 `FileChannel` 加一个字节写入重载
     * （SAF 侧走 `openOutputStream`），然后这里改两行。**在补上之前，缺口必须可见。**
     */
    private suspend fun makeTable(
        workspace: ResolvedOfficeWorkspace,
        request: OfficeRequest.MakeTable,
    ): OfficeToolOutcome {
        if (request.format != DocumentFormat.CSV) {
            return OfficeToolOutcome.Failed(
                "暂时写不了 ${request.format.displayName}：文件通道目前只支持**文本**写入，" +
                    "而 ${request.format.extension} 是二进制格式（需要通道支持字节写入，尚未接线）。" +
                    "请改用 `format: csv` —— 它同样能被 Excel / WPS 打开。",
            )
        }

        // CSV 是文本：走 OfficeText 生成（它自带 BOM、CRLF、以及**公式中和**）
        val csv = OfficeText.toCsv(header = request.header, rows = request.rows)
        return when (val result = channel.write(workspace.root, request.relativePath, csv)) {
            is ChannelResult.Ok -> OfficeToolOutcome.Ok(
                "已生成表格「${request.relativePath}」：" +
                    "${request.rows.size} 行 × ${request.header.size} 列（CSV，${csv.toByteArray(Charsets.UTF_8).size} 字节）。",
            )

            is ChannelResult.Unavailable -> OfficeToolOutcome.Failed(
                "文件通道不可用（${result.reason}）。请引导用户重新授权目录。",
            )

            is ChannelResult.Failed -> OfficeToolOutcome.Failed("写入表格失败：${result.reason}")
        }
    }
}
