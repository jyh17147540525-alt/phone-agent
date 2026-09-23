package com.pocketagent.capability

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.pocketagent.filelogic.ChannelResult
import com.pocketagent.filelogic.DirEntry
import com.pocketagent.filelogic.EntryStat
import com.pocketagent.filelogic.FileChannel
import com.pocketagent.filelogic.FileChannelKind
import com.pocketagent.filelogic.FileReadResult
import com.pocketagent.filelogic.ScopeRoot
import com.pocketagent.filelogic.TextDecoding
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * T0-D 文件通道 —— [FileChannel] 的 SAF 实现。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这是第 0 档唯一"零前置条件"的通道
 * ═══════════════════════════════════════════════════════════════
 *
 * [FileChannelKind.SAF] 不需要任何权限：用户在系统文件选择器里点一次
 * 「使用这个文件夹」，我们就拿到一棵子树的长期访问权。
 * 对比另外两条：
 *
 * | 通道 | 前置条件 | 用户要做什么 |
 * |---|---|---|
 * | SAF | **无** | 点一次目录（就这一次） |
 * | APP_PRIVATE | 无 | 什么都做不了 —— 那是我们自己的目录，用户看不到 |
 * | SHIZUKU | Shizuku 已安装且**正在运行** | 装 App + 每次重启后重新激活 |
 *
 * ⇒ 这就是"文件办公"能作为第 0 档第一个落地能力的全部理由：
 *   它**不需要用户先成为高级用户**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 它够不到什么（必须如实告诉用户，不能让他以为"能管所有文件"）
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **只在你授权的那些目录里**。没授权的地方一律够不到 —— 这是安全模型，不是缺陷。
 * 2. **够不到 `Android/data`**。Android 11 起系统明确禁止 SAF 授权该目录
 *    （连选都选不了）。所以"整理某个 App 的导出目录"这件事
 *    只能等 Shizuku 通道。
 * 3. **够不到云盘的真实内容之外的任何东西**：如果用户授权的是网盘目录，
 *    每次操作都是一次网络往返，会慢 —— 见 [FileChannel] 里"SAF 在有些机型上很慢"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️⚠️ 为什么直接用 `DocumentsContract`，而**不用** `DocumentFile`
 * ═══════════════════════════════════════════════════════════════
 *
 * `DocumentFile` 是官方推荐的便利封装，但它的 `length()` 有一个
 * 本项目**不能接受**的行为：**拿不到大小时返回 `0`**。
 *
 * 而 [EntryStat.sizeBytes] 的契约是「**null ≠ 0** —— 实现方拿不到时必须传 null」，
 * 理由是 `0` 会让上层说出一句假话：「这个文件是空的」。
 *
 * 用 `DocumentFile` 就只能写 `length().takeIf { it > 0 }` —— 那会把
 * **一个真正 0 字节的空文件**也报成"大小未知"。也就是说：
 * 为了修一个假话，制造了另一个假话。
 *
 * 直接用 `DocumentsContract` 可以读 `COLUMN_SIZE` 并检查 `isNull()`，
 * 于是"空文件"与"提供者没说"被**如实分开**。附带好处是不引入新依赖。
 *
 * ⇒ 代价是本类要自己处理 Cursor 的列索引。下面 [PROJECTION] 与
 *   [metaFrom] 是唯一两处碰 Cursor 的地方，其余全部走 [metaOf]。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 本类**不做任何安全判定**
 * ═══════════════════════════════════════════════════════════════
 *
 * 见 [FileChannel] 的类注释：判定是 [com.pocketagent.filelogic.FileAccessDecider]
 * 的事，通道只负责"怎么动"。本类里唯一的拒绝性检查是**结构性**的
 * （路径里出现 `..`、token 不是 tree URI）—— 那是"输入不合规"，
 * 不是"我判断你不该动这个文件"。**它不能替代裁决器**：
 * 裁决器没放行的请求，根本不该走到这里。
 */
class SafFileChannel(
    private val context: Context,
) : FileChannel {

    private val resolver: ContentResolver get() = context.contentResolver

    override val kind: FileChannelKind = FileChannelKind.SAF

    /**
     * SAF 这个**机制**永远可用 —— 没有服务要激活、没有权限要持有。
     *
     * ⚠️ 所以这里恒为 `true`，而"这一条授权还有效吗"是**另一件事** ——
     *    它由每个操作自己去发现：授权被撤销时，
     *    `ContentResolver` 会抛 `SecurityException`，我们把它翻成
     *    [ChannelResult.Unavailable]（用户去重新选一次目录就能解决）。
     *
     * ⚠️ 这是本接口的一个**已知不足**：`isAvailable()` 没有 `root` 参数，
     *    所以它答不了"这条授权可用吗"。一个装了 3 条授权、其中 1 条被撤销的
     *    SAF 通道，全局看仍是"可用"的。将来若真要有调度器按通道挑，
     *    这个签名得改成 `isAvailable(root: ScopeRoot)`。
     *    现在把它写清楚，好过让它看起来像"授权检查已经做过了"。
     */
    override fun isAvailable(): Boolean = true

    /**
     * 优先级。数值越大越优先。
     *
     * ★ SAF 排在最前，理由是**用户的显式决定**：
     *   一条 SAF 授权是用户为**这个具体目录**做的一次选择。
     *   如果 Shizuku 通道能到同一个目录，用它去够就等于
     *   "用一个更宽的权限去做一件用户已经用更窄的权限批准过的事" ——
     *   而那正是本项目一直在避免的那种"顺手放宽"。
     *
     * ⚠️ 这个数字本身没有绝对含义，只在多条通道同时能服务同一目标时才有意义。
     *    目前只有本类一个实现，所以它暂时不参与任何比较。
     */
    override fun priority(): Int = PRIORITY

    // ══════════════════════════════════════════════════════════════
    //  stat
    // ══════════════════════════════════════════════════════════════

    override suspend fun stat(
        root: ScopeRoot,
        relativePath: String,
    ): ChannelResult<EntryStat> = io {
        val tree = treeUriOf(root) ?: return@io notAGrantedDirectory()
        val segments = segmentsOf(relativePath) ?: return@io malformed()

        val located = locate(tree, root, segments) ?: return@io ChannelResult.Ok(EntryStat(exists = false))
        val meta = metaOf(tree, located.documentId)
            ?: return@io ChannelResult.Failed(reason = "查不到「${located.name}」的信息")

        ChannelResult.Ok(
            EntryStat(
                exists = true,
                isDirectory = meta.isDirectory,
                sizeBytes = meta.sizeBytes,
                modifiedAt = meta.modifiedAt,
            ),
        )
    }

    // ══════════════════════════════════════════════════════════════
    //  list
    // ══════════════════════════════════════════════════════════════

    override suspend fun list(
        root: ScopeRoot,
        relativePath: String,
    ): ChannelResult<List<DirEntry>> = io {
        val tree = treeUriOf(root) ?: return@io notAGrantedDirectory()
        val segments = segmentsOf(relativePath) ?: return@io malformed()

        val located = locate(tree, root, segments)
            ?: return@io ChannelResult.Failed(reason = "找不到这个目录")

        val meta = metaOf(tree, located.documentId)
            ?: return@io ChannelResult.Failed(reason = "查不到「${located.name}」的信息")

        if (!meta.isDirectory) {
            // ⚠️ 不说"操作失败" —— 那句话对"你想列的其实是个文件"毫无指引。
            return@io ChannelResult.Failed(reason = "「${located.name}」是一个文件，不是目录")
        }

        val children = childrenOf(tree, located.documentId)
            ?: return@io ChannelResult.Failed(reason = "列不出「${located.name}」里的内容")

        ChannelResult.Ok(
            children.map { child ->
                DirEntry(
                    name = child.name ?: UNNAMED_ENTRY,
                    isDirectory = child.isDirectory,
                    sizeBytes = child.sizeBytes,
                    modifiedAt = child.modifiedAt,
                )
            },
        )
    }

    // ══════════════════════════════════════════════════════════════
    //  read
    // ══════════════════════════════════════════════════════════════

    override suspend fun read(
        root: ScopeRoot,
        relativePath: String,
        maxBytes: Int,
    ): ChannelResult<FileReadResult> = io {
        val tree = treeUriOf(root) ?: return@io notAGrantedDirectory()
        val segments = segmentsOf(relativePath) ?: return@io malformed()

        val located = locate(tree, root, segments)
            ?: return@io ChannelResult.Failed(reason = "找不到这个文件")

        val meta = metaOf(tree, located.documentId)
            ?: return@io ChannelResult.Failed(reason = "查不到「${located.name}」的信息")

        if (meta.isDirectory) {
            return@io ChannelResult.Failed(reason = "「${located.name}」是一个目录，不是文件")
        }

        // ── 第一道：用元数据里的大小提前拦 ────────────────────────
        // ⚠️ 它只是**提前收手**，不是保证 —— 见下面第二道。
        val declared = meta.sizeBytes
        if (declared != null && declared > maxBytes) {
            return@io ChannelResult.Ok(FileReadResult.TooLarge(declared, maxBytes))
        }

        // ── 第二道：读取本身就带上限 ─────────────────────────────
        // ★ 这一道**才是**真正的保证，而它的存在理由是具体的：
        //   `COLUMN_SIZE` 是提供者说的，它可能为 null、可能是过期的、
        //   也可能干脆是假的（有些云盘提供者在没缓存时给 0）。
        //   如果按"元数据说不大"就放心 `readBytes()`，
        //   一个 4GB 的文件会把整个字符串塞进内存 → OOM 崩溃，
        //   而崩溃现场完全指不到"读了太大的文件"。
        //
        // ⇒ 所以按"最多读 maxBytes + 1 字节"来读，**超出的部分根本不进内存**。
        val bytes = readAtMost(tree, located.documentId, maxBytes)
            ?: return@io ChannelResult.Failed(reason = "打不开「${located.name}」")

        if (bytes.size > maxBytes) {
            // 用**实际读到的大小**报，而不是元数据里的那个 ——
            // 两个数字不一致时，真实的是这个。
            return@io ChannelResult.Ok(FileReadResult.TooLarge(bytes.size.toLong(), maxBytes))
        }

        // ⚠️ 解码交给 `:filelogic` 的 [TextDecoding] —— 它是**通道无关**的规则
        //    （"宁可如实失败，也不给一份错的"），放在那边才能进离线跑器。
        //    本类是 Android 模块，进不去，所以它自己**不能**持有这条规则。
        ChannelResult.Ok(TextDecoding.decodeUtf8(bytes))
    }

    // ══════════════════════════════════════════════════════════════
    //  write
    // ══════════════════════════════════════════════════════════════

    /**
     * 写文本。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ⚠️⚠️ 这里**做不到** [FileChannel.write] 要求的那件事，必须说清楚
     * ═══════════════════════════════════════════════════════════════
     *
     * 接口要求「**必须走 `AtomicTextFile`**」。而 `AtomicTextFile` 的签名是
     * `write(target: java.io.File, ...)` —— 它靠的是**同一文件系统内的
     * `rename(2)` 是原子的**。
     *
     * **SAF 没有 `java.io.File`。** 文档由 `Uri` 标识，它可能在一个
     * 云盘提供者上，根本不对应任何本地文件。所以 `AtomicTextFile`
     * 在这里**结构上就用不了** —— 这不是"偷懒没走"，是没有那条路。
     *
     * ⇒ 于是本实现退到**同一策略的 SAF 版本**：先把**完整的新内容**
     *   落到一个临时文档里，再动旧文件。这样做的收益是具体的：
     *
     * ```
     * 直接覆盖（openOutputStream "wt"）：
     *   旧内容被截断 → 进程在这一刻消失 → 用户得到一个"能打开但少了一半"的文件
     * 先写临时文档：
     *   临时文档没写完整 → 旧文件**一个字节都没动**，我们直接放弃
     * ```
     *
     * ⚠️ 但**残余风险必须如实写出来**，不能假装它不存在：
     *
     * - 若提供者支持"改名时替换"（外部存储提供者走的就是 `rename(2)`，
     *   在 Linux 上是原子的），那整件事是原子的 —— 这是**常见情况**。
     * - 若提供者不支持，就退到"先删旧的、再改名"，**这一步不是原子的**。
     *   最坏情况是：旧文件已删、改名失败。此时**新内容仍然完整存在**，
     *   只是挂在一个临时名字下 —— 所以**不会丢数据**，但用户会看到
     *   一个奇怪的文件名。那种情况下的失败信息里会**明确写出那个名字**。
     *
     * ⇒ 一句话：**这里保证"不会丢内容"，但不保证"不会出现临时名字"。**
     *   接口那句"必须走 AtomicTextFile"在 SAF 上应改成"必须先把完整内容
     *   落定、再动旧文件" —— 这是本实现暴露出来的**契约问题**，
     *   已在文档里记为待收敛项。
     */
    override suspend fun write(
        root: ScopeRoot,
        relativePath: String,
        content: String,
    ): ChannelResult<Unit> = io {
        val tree = treeUriOf(root) ?: return@io notAGrantedDirectory()
        val segments = segmentsOf(relativePath) ?: return@io malformed()

        if (segments.isEmpty()) {
            return@io ChannelResult.Failed(reason = "不能把一个目录当成文件来写")
        }

        val parentSegments = segments.dropLast(1)
        val name = segments.last()

        // ── 父目录必须存在且是目录 ───────────────────────────────
        // ⚠️ 这里**不**自动建目录：`file.write` 的语义是"写一个文件"，
        //    "顺手把父目录也建了"会让一次笔误（路径多打一层）
        //    静默地在用户的存储里造出目录树。宁可失败并说清楚。
        val parent = locate(tree, root, parentSegments)
            ?: return@io ChannelResult.Failed(
                reason = "要写入的位置「${parentSegments.joinToString("/")}」不存在。" +
                    "PocketAgent 不会替你新建目录。",
            )

        val parentMeta = metaOf(tree, parent.documentId)
            ?: return@io ChannelResult.Failed(reason = "查不到目标目录的信息")

        if (!parentMeta.isDirectory) {
            return@io ChannelResult.Failed(reason = "要写入的位置不是一个目录")
        }

        // ── 目标当前是什么 ───────────────────────────────────────
        // ⚠️ 必须**在父目录的子项里按名字找**，绝不能自己拼一个 documentId。
        //    documentId 的格式是**提供者自己定的**：外部存储提供者用的是
        //    `primary:Documents/a.txt` 这种路径式的，而云盘提供者可能是一串
        //    不透明 id。拼出来的那个在外部存储上**恰好是对的** ——
        //    于是这个 bug 会在真机上"测试通过"，直到用户授权了一个网盘目录，
        //    然后表现为"写入新建了一个重名文件、原文件还在"。
        val existing = childrenOf(tree, parent.documentId)?.firstOrNull { it.name == name }

        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parent.documentId)
        val bytes = content.toByteArray(Charsets.UTF_8)

        // 保持原来的 MIME 类型：换掉它会让"用别的 App 打开"的行为悄悄改变。
        val mime = existing?.mimeType?.takeIf { it.isNotBlank() && it != DocumentsContract.Document.MIME_TYPE_DIR }
            ?: mimeTypeOf(name)

        if (existing == null) {
            writeNewFile(parentUri, name, mime, bytes)
        } else {
            replaceExistingFile(tree, parentUri, parent.documentId, name, mime, bytes, existing)
        }
    }

    /**
     * 新建一个文件。
     *
     * ★ 新建时**不需要临时文件**，而且原因很实在：
     *   目标本来就不存在，所以"写坏了"这件事**可以完全撤销** ——
     *   把刚建出来的那个文档删掉，用户就回到了原来的状态（什么都没有）。
     *
     *   而用临时文件反而更差：多一次 create + 一次 rename，
     *   中途失败还会在用户目录里留下一个临时文件。
     */
    private fun writeNewFile(
        parentUri: Uri,
        name: String,
        mime: String,
        bytes: ByteArray,
    ): ChannelResult<Unit> {
        val created = try {
            DocumentsContract.createDocument(resolver, parentUri, mime, name)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Timber.w(e, "SAF 新建文档失败")
            null
        } ?: return ChannelResult.Failed(reason = "这个目录不允许新建文件")

        val written = try {
            resolver.openOutputStream(created, "wt")?.use { out ->
                out.write(bytes)
                out.flush()
                true
            } ?: false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Timber.w(e, "SAF 写入新文件失败")
            false
        }

        if (written) return ChannelResult.Ok(Unit)

        // 写坏了 → **把刚建出来的那个删掉**，让用户回到原来的状态。
        // ⚠️ 删除失败也不能瞒着：那意味着目录里多了一个半截文件。
        val rolledBack = runCatching { DocumentsContract.deleteDocument(resolver, created) }
            .getOrDefault(false)

        return ChannelResult.Failed(
            reason = if (rolledBack) {
                "写入没有完成，已经把没写完的文件清掉了（这个文件本来不存在）。"
            } else {
                "写入没有完成，而且没能清掉那个没写完的文件「$name」—— " +
                    "你的目录里可能多了一个不完整的文件，请手动删掉它。"
            },
        )
    }

    /**
     * 覆盖一个已存在的文件。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 为什么是「先把旧的挪开」，而不是「改名时替换」
     * ═══════════════════════════════════════════════════════════════
     *
     * 2026-09-23 真机（K60 / Android 15 / `ExternalStorageProvider`）实测到两件事，
     * 合起来让"先试原子替换、失败再走退路"这个结构**两头落空**：
     *
     *   ① `renameDocument(temp, name)` 在 `name` **已存在**时既不报错也不替换，
     *      而是静默把临时文档改名成 `name (1)` 并返回成功 —— 见 [renamedTo]。
     *      ⇒ 而走"替换"这条分支时，`name` **必然**已存在。
     *   ② **改名是"消耗性"的**：第 ① 步之后 `tempUri` 就失效了（那个文档
     *      已经改叫别的名字了）。拿它再去改名会抛
     *      `IllegalArgumentException: Missing file for ...`。
     *
     * 于是"试替换 → 删旧的 → 再改名"这个顺序会变成：第 ① 步把临时文档
     * "消耗"掉，退路又拿着**已经失效的 uri** 去改名 ——
     * **而那时旧文件已经删了。用户的文件就这么没了。**（真的发生过一次）
     *
     * ⇒ 换成**每一步都能回滚**的顺序：
     *
     *   1. 把旧文件**改名挪开**（不是删掉）—— 内容仍然完整地躺在磁盘上
     *   2. 在新名字上**新建**文件（此时不撞名了，直接走 [writeNewFile]）
     *   3. 成功 → 删掉那个挪开的备份
     *      失败 → 把备份**改回原名**（完全回到原状）
     *
     * 代价是第 1 步与第 2 步之间 `name` 短暂不存在（用户那一刻看目录会发现
     * 它"不见了"）。这个窗口是能接受的 —— **而"内容真的没了"不能**。
     *
     * ⚠️ 与 [write] 的文件头合起来看：那里说的是"做不到 `AtomicTextFile`"，
     *    这里说的是"**连近似原子都做不到，只能做到可回滚**"。
     */
    /**
     * 一次改名**真的把它改成了期望的那个名字**吗。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 为什么"改名成功"不能只看返回值非 null
     * ═══════════════════════════════════════════════════════════════
     *
     * 2026-09-23 真机实测（K60 / Android 15 / `ExternalStorageProvider`）：
     *
     *   把临时文档改名成 `hello.txt`，而 `hello.txt` **已经存在**时，
     *   `DocumentsContract.renameDocument()` **既不抛异常、也不替换** ——
     *   它**静默地把临时文档改名成 `hello (1).txt`** 并返回一个非 null 的 Uri。
     *
     *   于是调用方看到"返回了非 null"就报告成功：
     *
     *   | | 实际发生的 |
     *   |---|---|
     *   | 用户以为 | `hello.txt` 被覆盖了 |
     *   | 实际上 | 旧文件完好，新内容在 `hello (1).txt` 里 —— **目录里凭空多出一个副本** |
     *
     * ⚠️ 这比"改名失败"糟得多：失败是可见的，而这个是**报告成功**。
     *    用户下一次 `file.list` 才会发现两个文件，而那时他已经不记得
     *    哪一份是新的了。
     *
     * ⇒ 判据必须是**改完之后它叫什么**，而不是"这一步有没有报错"。
     *
     * ⚠️ 为什么用 displayName 而不是比较 documentId：documentId 的格式是
     *    提供者自己定的。外部存储用路径式 id（改名后 id 会变），
     *    而云盘类提供者可能用不透明 id（**改名后 id 不变**）——
     *    在后一种上比较 id 会把"静默改名"误判成"替换成功"。
     */
    private fun renamedTo(tree: Uri, renamedUri: Uri, expectedName: String): Boolean {
        val id = runCatching { DocumentsContract.getDocumentId(renamedUri) }.getOrNull() ?: return false
        return metaOf(tree, id)?.name == expectedName
    }

    private fun replaceExistingFile(
        tree: Uri,
        parentUri: Uri,
        parentDocumentId: String,
        name: String,
        mime: String,
        bytes: ByteArray,
        existing: DocMeta,
    ): ChannelResult<Unit> {
        // ⚠️ 拿不到 documentId 就没法替换。这里**如实停下**，不要退回"按名字猜" ——
        //    猜错的后果是删掉一个**同名但不同**的东西。
        val existingDocumentId = existing.documentId
            ?: return ChannelResult.Failed(
                reason = "查不到要替换的那个文件的标识，所以没有动手（原来的文件还在）",
            )

        val existingUri = DocumentsContract.buildDocumentUriUsingTree(tree, existingDocumentId)
        val backupName = "$name$BACKUP_INFIX${UUID.randomUUID().toString().take(8)}"

        // ── 1. 把旧文件**改名挪开**（不是删掉！）─────────────────
        // ★ 关键在"挪开"而不是"删掉"：从这一步到第 3 步之间，
        //   旧内容**一直完整地躺在磁盘上**（只是换了名字）。
        //   所以后面无论哪一步失败，我们都能把它改回来。
        val moved = try {
            DocumentsContract.renameDocument(resolver, existingUri, backupName)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Timber.w(e, "SAF 挪开旧文件失败")
            null
        }

        if (moved == null || !renamedTo(tree, moved, backupName)) {
            return failWithoutMoving(tree, name, moved)
        }

        // ── 2. 在新名字上**新建**（此时 `name` 已经不撞名了）────
        // ★ 用 [writeNewFile] 而不是"建临时文档再改名"，是因为后者
        //   在这个提供者上**根本走不通** —— 见本方法的文件头。
        val created = writeNewFile(parentUri, name, mime, bytes)

        if (created is ChannelResult.Ok) {
            // ── 3. 成功 → 删掉备份 ───────────────────────────────
            val cleaned = runCatching { DocumentsContract.deleteDocument(resolver, moved) }
                .getOrDefault(false)

            // ⚠️ 结果**已经是对的**，所以这里不能报成失败 —— 那会让用户以为没写进去，
            //    于是他再写一次，而那是完全没有必要的。
            //    但那个备份文件的存在必须说出来：它占着用户的空间，
            //    名字又长得像垃圾，用户不知道能不能删。
            return if (cleaned) {
                ChannelResult.Ok(Unit)
            } else {
                ChannelResult.Failed(
                    reason = "「$name」已经换成新内容了。但旧的那一份没能删掉，" +
                        "还在同一个目录里叫「$backupName」，你可以自己删掉它。",
                )
            }
        }

        // ── 4. 没写成功 → **把旧文件放回去** ─────────────────────
        val restored = runCatching {
            DocumentsContract.renameDocument(resolver, moved, name)
        }.getOrNull()

        val restoredOk = restored != null && renamedTo(tree, restored, name)

        return ChannelResult.Failed(
            reason = if (restoredOk) {
                "没能把新内容写进「$name」，所以原来的文件已经放回去了。" +
                    "（它的内容一个字节都没动。）"
            } else {
                // 最坏情况：新文件没写成功，旧文件也没能改回原名。
                // ⚠️ 但**内容仍然完整** —— 只是名字变了。所以这句话必须
                //    把那个名字写出来，否则用户只知道"文件没了"，
                //    完全不知道去哪找。
                "没能把新内容写进「$name」，而且原来的文件没能改回原名 —— " +
                    "它现在叫「$backupName」，内容完好。把它改回「$name」就好。"
            },
        )
    }

    /**
     * 第 1 步（把旧文件挪开）没成，或者连挪开都被静默改名了。
     *
     * ⚠️ 两种情况**用户要做的事不同**，所以不能合成一句：
     *    · 挪开失败 → 原文件还在原名下，用户**什么都不用做**
     *    · 挪开成功但名字不对 → 用户要去把名字改回来
     */
    private fun failWithoutMoving(tree: Uri, name: String, moved: Uri?): ChannelResult<Unit> {
        if (moved == null) {
            return ChannelResult.Failed(
                reason = "动不了原来的「$name」，所以这次写入取消了。（原来的文件一个字节都没动。）",
            )
        }

        // 连 `backupName`（带随机后缀）都能撞名 —— 那就先把它改回 `name`。
        // 能改回去就等于什么都没发生。
        val back = runCatching { DocumentsContract.renameDocument(resolver, moved, name) }.getOrNull()
        val backOk = back != null && renamedTo(tree, back, name)

        if (backOk) {
            return ChannelResult.Failed(
                reason = "动不了原来的「$name」，所以这次写入取消了。（原来的文件一个字节都没动。）",
            )
        }

        val actual = runCatching { DocumentsContract.getDocumentId(moved) }
            .getOrNull()
            ?.let { metaOf(tree, it)?.name }

        return ChannelResult.Failed(
            reason = "这次写入取消了。原来的「$name」还在，但它现在叫" +
                "「${actual ?: "另一个名字"}」—— 内容完好，把名字改回来就好。",
        )
    }

    // ══════════════════════════════════════════════════════════════
    //  delete
    // ══════════════════════════════════════════════════════════════

    /**
     * 删除。
     *
     * ⚠️ 目录删除是**递归**的（由提供者实现，外部存储提供者会连内容一起删）。
     *    所以"里面还有 N 个子项"这件事必须由**裁决器在确认框里**说清楚 ——
     *    见 [com.pocketagent.filelogic.FileOperationRequest.descendantCount]。
     *
     * ⚠️ 本方法**没法**报告"到底删掉了什么**：接口签名是
     *    `ChannelResult<Unit>`，没有装那份清单的地方。
     *    这是 [FileChannel] 的一处**契约债务**（"必须在结果里如实报告删了什么"
     *    在类型上做不到）。现在的兜底是"删之前先说清有几个子项"，
     *    也就是把透明度放在**事前**而不是事后。已在文档里记为待收敛项。
     */
    override suspend fun delete(
        root: ScopeRoot,
        relativePath: String,
    ): ChannelResult<Unit> = io {
        val tree = treeUriOf(root) ?: return@io notAGrantedDirectory()
        val segments = segmentsOf(relativePath) ?: return@io malformed()

        if (segments.isEmpty()) {
            // 删掉授权根本身会让这条授权指向一个不存在的位置，
            // 而用户完全没有意识到自己刚做了什么。
            return@io ChannelResult.Failed(reason = "不能删掉授权的目录本身")
        }

        val located = locate(tree, root, segments)
            ?: return@io ChannelResult.Failed(reason = "找不到这个文件")

        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, located.documentId)
        val ok = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)

        if (ok) {
            ChannelResult.Ok(Unit)
        } else {
            ChannelResult.Failed(reason = "删不掉「${located.name}」（可能是只读目录，或文件被别的应用占着）")
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  move
    // ══════════════════════════════════════════════════════════════

    /**
     * 移动 / 重命名。**只在同一个授权根内**（跨根由裁决器拒绝）。
     *
     * SAF 没有"移动"原语，所以要分两种情况：
     *
     * | 情况 | 做法 | 是否原子 |
     * |---|---|---|
     * | 同一个父目录（= 重命名） | `renameDocument` | **是** |
     * | 不同父目录（= 真的移动） | 流式复制 + 删源 | 否 |
     *
     * ⚠️ 第二种的失败方向是**复制品残留**，不是数据丢失 ——
     *    因为源文件只有在复制**完整成功之后**才会被删。
     *    这与"先删源再复制"正相反，而那个顺序一旦中断就是真的丢文件。
     *
     * ⚠️ 复制走的是**流**，不经过 [read] 的 256 KB 上限 ——
     *    移动一个 200 MB 的视频是合理需求，而"读"它不是。
     */
    override suspend fun move(
        root: ScopeRoot,
        fromRelativePath: String,
        toRelativePath: String,
    ): ChannelResult<Unit> = io {
        val tree = treeUriOf(root) ?: return@io notAGrantedDirectory()

        val fromSegments = segmentsOf(fromRelativePath) ?: return@io malformed()
        val toSegments = segmentsOf(toRelativePath) ?: return@io malformed()

        if (fromSegments.isEmpty() || toSegments.isEmpty()) {
            return@io ChannelResult.Failed(reason = "移动的源和目标都必须是文件，不能是授权目录本身")
        }

        val source = locate(tree, root, fromSegments)
            ?: return@io ChannelResult.Failed(reason = "找不到要移动的文件")

        val destinationParent = locate(tree, root, toSegments.dropLast(1))
            ?: return@io ChannelResult.Failed(reason = "目标位置所在的目录不存在")

        val destinationParentMeta = metaOf(tree, destinationParent.documentId)
            ?: return@io ChannelResult.Failed(reason = "查不到目标目录的信息")

        if (!destinationParentMeta.isDirectory) {
            return@io ChannelResult.Failed(reason = "目标位置不是一个目录")
        }

        val newName = toSegments.last()
        val sourceUri = DocumentsContract.buildDocumentUriUsingTree(tree, source.documentId)

        // ── 情况一：同一个父目录 → 纯重命名 ──────────────────────
        if (source.parentDocumentId == destinationParent.documentId) {
            val renamed = runCatching { DocumentsContract.renameDocument(resolver, sourceUri, newName) }
                .getOrNull()

            // ⚠️ 与 [replaceExistingFile] 是**同一个坑**：目标名已存在时，
            //    提供者会静默把它改成「newName (1)」并返回非 null —— 见 [renamedTo]。
            //
            // ⚠️⚠️ 但这里**不能**像 `write` 那样把多出来的那个删掉 ——
            //    那个文件就是**用户要移动的东西**，删掉就是真丢数据。
            //    所以只能如实报错，并把系统实际用的名字告诉他。
            return@io when {
                renamed == null -> ChannelResult.Failed(
                    reason = "改不了名（可能已经有一个叫「$newName」的文件）",
                )

                renamedTo(tree, renamed, newName) -> ChannelResult.Ok(Unit)

                else -> {
                    val actual = runCatching { DocumentsContract.getDocumentId(renamed) }
                        .getOrNull()
                        ?.let { metaOf(tree, it)?.name }

                    ChannelResult.Failed(
                        reason = "这个位置已经有一个叫「$newName」的文件，系统没有替换它，" +
                            "而是把你要移动的文件改名成了「${actual ?: "另一个名字"}」。" +
                            "用 `file.list` 看一眼就能确认。",
                    )
                }
            }
        }

        // ── 情况二：跨目录 → 复制，成功之后才删源 ────────────────
        val destinationParentUri =
            DocumentsContract.buildDocumentUriUsingTree(tree, destinationParent.documentId)

        val sourceMeta = metaOf(tree, source.documentId)
            ?: return@io ChannelResult.Failed(reason = "查不到要移动的那个文件的信息")

        val mime = sourceMeta.mimeType?.takeIf { it.isNotBlank() } ?: mimeTypeOf(newName)

        val created = runCatching {
            DocumentsContract.createDocument(resolver, destinationParentUri, mime, newName)
        }.getOrNull()
            ?: return@io ChannelResult.Failed(
                reason = "目标目录里建不了文件（可能已经有一个叫「$newName」的东西）",
            )

        val copied = runCatching {
            resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(created, "wt")?.use { output ->
                    input.copyTo(output)
                    output.flush()
                    true
                } ?: false
            } ?: false
        }.getOrDefault(false)

        if (!copied) {
            // 撤掉复制品，源文件完好 —— 等于什么都没发生。
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            return@io ChannelResult.Failed(
                reason = "复制到新位置时失败了，已经撤掉没复制完的那一份（原来的文件还在）。",
            )
        }

        val sourceRemoved = runCatching { DocumentsContract.deleteDocument(resolver, sourceUri) }
            .getOrDefault(false)

        if (sourceRemoved) {
            ChannelResult.Ok(Unit)
        } else {
            // ⚠️ 复制成功、删源失败 → 用户现在**有两份**。
            //    这必须说出来：不说的话他会以为"移动完成了"，
            //    然后在两个地方各改一份，最后不知道该信哪个。
            ChannelResult.Failed(
                reason = "新位置已经有一份完整的「$newName」了，但原来那份没能删掉 —— " +
                    "现在两个位置都有这个文件，请手动删掉不要的那一份。",
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  定位与查询
    // ══════════════════════════════════════════════════════════════

    /** 定位到的一个文档。 */
    private data class DocRef(
        val documentId: String,
        /** 父目录的 documentId。根自身为 null。 */
        val parentDocumentId: String?,
        val name: String,
    )

    /**
     * 从授权根逐段往下走，找到 [segments] 指向的文档。
     *
     * 返回 `null` 表示**路径上有一段不存在** —— 那不是错误，是"还没有"。
     * 调用方按自己的语义翻译（[stat] 说 `exists = false`，[list] 说"找不到"）。
     *
     * ⚠️ 逐段走是**必须**的，不是选择：见 [FileChannel] 的义务 1 ——
     *    [ScopeRoot.path] 只是展示值（云盘目录根本没有本地路径），
     *    用 `path` 去拼一个 documentId 会得到一个"看起来对但指向别处"的结果。
     *    只有 [ScopeRoot.token]（tree URI）是可靠的起点。
     *
     * ⚠️ 代价是它**慢**：每段一次 `queryChildDocuments`，而每次都是一轮
     *    ContentResolver IPC。列一个 500 个文件的目录再往下找一层，
     *    就是两轮全表扫描。这是 [FileChannel] 注释里"SAF 在有些机型上很慢"
     *    的具体来源，也是将来 Shizuku 通道存在的理由之一。
     */
    private fun locate(tree: Uri, root: ScopeRoot, segments: List<String>): DocRef? {
        val rootDocumentId = DocumentsContract.getTreeDocumentId(tree)

        if (segments.isEmpty()) {
            return DocRef(documentId = rootDocumentId, parentDocumentId = null, name = root.displayName)
        }

        var parentId = rootDocumentId
        var current = DocRef(documentId = rootDocumentId, parentDocumentId = null, name = root.displayName)

        for (segment in segments) {
            val child = childrenOf(tree, parentId)?.firstOrNull { it.name == segment } ?: return null
            val childId = child.documentId ?: return null
            current = DocRef(documentId = childId, parentDocumentId = parentId, name = segment)
            parentId = childId
        }

        return current
    }

    /** 一个文档的元数据。 */
    private data class DocMeta(
        val documentId: String?,
        val name: String?,
        val isDirectory: Boolean,
        /** null = **提供者没说**，不是"空文件"。见 [EntryStat.sizeBytes]。 */
        val sizeBytes: Long?,
        val modifiedAt: Long?,
        val mimeType: String?,
    )

    private fun metaOf(tree: Uri, documentId: String): DocMeta? =
        queryOne(DocumentsContract.buildDocumentUriUsingTree(tree, documentId))

    private fun childrenOf(tree: Uri, parentDocumentId: String): List<DocMeta>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocumentId)
        return try {
            resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
                buildList { while (cursor.moveToNext()) add(metaFrom(cursor)) }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (denied: SecurityException) {
            throw denied // 交给 io {} 翻成 Unavailable
        } catch (e: Exception) {
            Timber.w(e, "SAF 列子项失败")
            null
        }
    }

    private fun queryOne(uri: Uri): DocMeta? = try {
        resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) metaFrom(cursor) else null
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (denied: SecurityException) {
        throw denied
    } catch (e: Exception) {
        Timber.w(e, "SAF 查询文档失败")
        null
    }

    /**
     * 从一行 Cursor 读出一个 [DocMeta]。
     *
     * ★ 这里是**唯一**把 `COLUMN_SIZE` / `COLUMN_LAST_MODIFIED` 变成
     *   `Long?` 的地方，也就是"null ≠ 0"这条契约真正落地的一行。
     *   全类只有这一处碰 Cursor 的列索引 —— 所以列顺序写错只可能错在这里，
     *   不会散落到六个方法里。
     */
    private fun metaFrom(cursor: Cursor): DocMeta {
        val mime = cursor.getStringOrNull(COLUMN_MIME_TYPE)
        return DocMeta(
            documentId = cursor.getStringOrNull(COLUMN_DOCUMENT_ID),
            name = cursor.getStringOrNull(COLUMN_DISPLAY_NAME),
            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
            // ⚠️ `isNull` 才是"提供者没说"。写成 `getLong(...)` 会把
            //    null 读成 0，于是"不知道多大"和"是个空文件"混成一种 ——
            //    而那正是本类放弃 `DocumentFile` 的原因。
            sizeBytes = cursor.getLongOrNull(COLUMN_SIZE),
            modifiedAt = cursor.getLongOrNull(COLUMN_LAST_MODIFIED)?.takeIf { it > 0 },
            mimeType = mime,
        )
    }

    private fun Cursor.columnIndexOrNull(name: String): Int = getColumnIndex(name)

    private fun Cursor.getStringOrNull(name: String): String? {
        val i = columnIndexOrNull(name)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.getLongOrNull(name: String): Long? {
        val i = columnIndexOrNull(name)
        return if (i < 0 || isNull(i)) null else getLong(i)
    }

    // ══════════════════════════════════════════════════════════════
    //  读取辅助
    // ══════════════════════════════════════════════════════════════

    /**
     * 最多读 [limit] + 1 字节。
     *
     * ⚠️ **不能**写成 `input.readBytes()`。那个写法在这里是危险的，
     *    而危险的方式很隐蔽：它在**正常路径上完全正确** ——
     *    直到有人让 agent 去读一个 4 GB 的文件，于是 OOM，
     *    而崩溃栈里只有 `readBytes`，指不到"读了太大的文件"。
     *
     * 读满 `limit + 1` 就立刻收手：多出来的那 1 个字节只有一个用途 ——
     * 让调用方能区分"刚好 limit"和"超过 limit"。
     */
    private fun readAtMost(tree: Uri, documentId: String, limit: Int): ByteArray? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream(minOf(limit + 1, CHUNK_BYTES))
                val chunk = ByteArray(CHUNK_BYTES)
                while (out.size() <= limit) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    out.write(chunk, 0, read)
                }
                out.toByteArray()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (denied: SecurityException) {
            throw denied
        } catch (e: Exception) {
            Timber.w(e, "SAF 读取失败")
            null
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  共用
    // ══════════════════════════════════════════════════════════════

    /**
     * 所有公开方法的统一外壳：切到 IO 线程，并把异常翻成结论。
     *
     * ⚠️ [SecurityException] 单独一支，因为它是**唯一**能区分
     *    "去重新授权"和"这次没成"的信号 —— 翻成 [ChannelResult.Failed]
     *    会让用户对着一个"操作失败"反复重试，而他要做的只是重新选一次目录。
     */
    private suspend inline fun <T> io(
        crossinline block: () -> ChannelResult<T>,
    ): ChannelResult<T> = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            // ⚠️ 必须原样抛出：吞掉它会让协程取消在这里断掉 ——
            //    表现是"点了停止，任务还在跑"，而且不报任何错。
            throw cancellation
        } catch (denied: SecurityException) {
            Timber.w(denied, "SAF 访问被拒（授权可能已被撤销）")
            ChannelResult.Unavailable(reason = REVOKED)
        } catch (illegal: IllegalArgumentException) {
            // `getTreeDocumentId` 对非 tree URI 抛的就是它 —— 见 [treeUriOf]。
            Timber.w(illegal, "SAF 的 token 不是合法的 tree URI")
            ChannelResult.Unavailable(reason = NOT_A_GRANTED_DIRECTORY)
        } catch (e: Exception) {
            // ⚠️ 只记异常**类型**。message 里可能带路径 ——
            //    而这条字符串会进审计日志，路径不进日志是本项目的硬纪律。
            ChannelResult.Failed(reason = "访问文件时出错：${e::class.simpleName}")
        }
    }

    /**
     * [ScopeRoot.token] → tree URI。
     *
     * ⚠️ 空 token **不是**"随便找个目录"的意思，而是"这条授权没有记下目录"。
     *    返回 null 让调用方说一句能指引用户的话，而不是在这里编一个默认值 ——
     *    编默认值的后果是它可能指向一个**用户从没授权过**的地方。
     */
    private fun treeUriOf(root: ScopeRoot): Uri? =
        root.token.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /**
     * 相对路径 → 段列表。返回 null 表示**结构上不可接受**。
     *
     * ⚠️ 这里拒绝 `..`，但**这不是安全判定**，它只是"输入不合规"：
     *    裁决器已经归一化过路径，正常永远不会走到这一支。
     *    拒绝它的理由是**报错质量** —— 拿 `..` 当普通名字去查会失败，
     *    而失败信息会写成"找不到名为「..」的文件"，很难查。
     *
     * ⚠️ 它也**不能替代**裁决器的越界检查：裁决器放行的请求才走到这里，
     *    而这里放行的请求不代表在授权范围内。
     */
    private fun segmentsOf(relativePath: String): List<String>? {
        val segments = relativePath.split('/').filter { it.isNotEmpty() && it != "." }
        return if (segments.any { it == ".." }) null else segments
    }

    private fun malformed(): ChannelResult<Nothing> =
        ChannelResult.Failed(reason = "这个路径里有不认识的写法（`..`），所以没有执行")

    private fun notAGrantedDirectory(): ChannelResult<Nothing> =
        ChannelResult.Unavailable(reason = NOT_A_GRANTED_DIRECTORY)

    /** 按扩展名猜 MIME。猜不到就按文本处理 —— 我们只会写文本。 */
    private fun mimeTypeOf(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isEmpty()) return DEFAULT_MIME
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: DEFAULT_MIME
    }

    companion object {
        /** 见 [priority]。 */
        const val PRIORITY: Int = 100

        /** 一次性读的块大小。16 KB 是 ContentResolver 的常见内部缓冲量级。 */
        private const val CHUNK_BYTES: Int = 16 * 1024

        /**
         * 覆盖前把旧文件**挪开**时用的中缀。
         *
         * 用户看到 `报告.md.old-a1b2c3d4` 时应当能猜到"这是刚才那一份旧的"。
         *
         * ⚠️ 它**不是** `.tmp-`：这个类里已经没有"临时文档"这种东西了 ——
         *   覆盖走的是「把旧的挪开 → 在新名字上新建 → 删备份」，
         *   见 [replaceExistingFile] 的文件头（那里说了为什么"临时文档 + 改名替换"
         *   在这个平台上根本走不通）。
         */
        private const val BACKUP_INFIX: String = ".old-"

        /** 提供者没给显示名时的占位。见 [list]。 */
        private const val UNNAMED_ENTRY: String = "(未命名)"

        private const val DEFAULT_MIME: String = "text/plain"

        private const val REVOKED: String =
            "这条目录授权已经失效了。到「第 0 档能力」里重新选一次目录就好。"

        private const val NOT_A_GRANTED_DIRECTORY: String =
            "这条能力还没有被你授权任何目录。到「第 0 档能力」里选一个目录就能用了。"

        // ⚠️ 列顺序**必须**与 `metaFrom` 里读的一致 —— 但这两处不在一起，
        //    所以这里用 `DocumentsContract.Document` 的常量而不是裸字符串：
        //    常量拼错是编译错误，裸字符串拼错是"这一列读出来是 null"。
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        private const val COLUMN_DOCUMENT_ID = DocumentsContract.Document.COLUMN_DOCUMENT_ID
        private const val COLUMN_DISPLAY_NAME = DocumentsContract.Document.COLUMN_DISPLAY_NAME
        private const val COLUMN_MIME_TYPE = DocumentsContract.Document.COLUMN_MIME_TYPE
        private const val COLUMN_SIZE = DocumentsContract.Document.COLUMN_SIZE
        private const val COLUMN_LAST_MODIFIED = DocumentsContract.Document.COLUMN_LAST_MODIFIED
    }
}
