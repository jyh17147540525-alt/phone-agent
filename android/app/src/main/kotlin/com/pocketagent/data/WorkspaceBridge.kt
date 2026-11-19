package com.pocketagent.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.pocketagent.rootlogic.FileStamp
import com.pocketagent.rootlogic.SyncDirection
import com.pocketagent.rootlogic.WorkspaceSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * 工作台选中的那个目录 ↔ 容器 `/work`。
 *
 * ## 为什么要有它
 *
 * 工作台里选的「工作区」是 SAF 的 tree URI —— 用户授权的是 `PocketAgentTest`
 * 这种目录，不是一条文件系统路径。而 root 容器里的 `/work` 是一条真路径。
 * 两者之间没有共同语言：`mount --bind` 挂不了 SAF，容器里的 `ls /work`
 * 也读不到 ContentResolver。
 *
 * ⇒ 只能搬。进模型之前把工作区里的文件拉进 `/work`，一轮结束后把改过的推回去。
 *
 * ## 边界
 *
 * · **只在工作台、且选了工作区时**才同步；聊天页没有"工作区"这回事。
 * · 有上限（见 [WorkspaceSync.MAX_FILES] / [MAX_BYTES]），超限**整次跳过**并给原因。
 * · 方向按修改时间"谁新谁赢"，这是**覆盖**不是合并 —— 文案必须这么写。
 */
class WorkspaceBridge(
    private val context: Context,
    private val workDir: File,
    private val treeUri: () -> Uri?,
) {

    /**
     * 同步一次。这是**尽力而为**的：失败只记日志并返回 null，
     * 绝不能让"同步失败"把用户那一轮对话打断。
     */
    suspend fun sync(reason: String): String? = withContext(Dispatchers.IO) {
        val tree = treeUri() ?: return@withContext null
        runCatching {
            val remote = LinkedHashMap<String, Pair<FileStamp, Uri>>()
            walk(tree, "", 0, remote)
            val local = LinkedHashMap<String, FileStamp>()
            if (workDir.isDirectory) {
                workDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { f ->
                        val rel = f.relativeTo(workDir).invariantSeparatorsPath
                        local[rel] = FileStamp(rel, f.length(), f.lastModified())
                    }
            }

            val biggest = remote.values.maxOfOrNull { it.first.size } ?: 0L
            val totalBytes = remote.values.sumOf { it.first.size }
            WorkspaceSync.limitNote(remote.size, totalBytes, biggest)?.let { note ->
                Timber.i("工作区同步跳过（%s）：%s", reason, note)
                return@runCatching note
            }

            val actions = WorkspaceSync.plan(local.values.toList(), remote.values.map { it.first })
            var pulled = 0
            var pushed = 0
            for ((stamp, direction) in actions) {
                when (direction) {
                    SyncDirection.PULL -> {
                        val (_, docUri) = remote.getValue(stamp.path)
                        if (copyFrom(docUri, stamp.path, stamp.modifiedMs)) pulled++
                    }
                    SyncDirection.PUSH -> {
                        val file = File(workDir, stamp.path)
                        if (copyTo(tree, stamp.path, file, remote[stamp.path]?.second)) pushed++
                    }
                }
            }
            Timber.i("工作区同步（%s）：计划 %d 个动作，实拉 %d，实推 %d", reason, actions.size, pulled, pushed)
            // 报告**实际**搬成功的数量：计划了 5 个、只成功 3 个时说"5 个"就是骗人
            if (actions.isEmpty()) null else "同步完成：拉进 $pulled 个，推回 $pushed 个。"
        }.onFailure { Timber.w(it, "工作区同步失败（不影响这一轮）") }.getOrNull()
    }

    /** 递归列出 SAF 目录。跳过我们搬不动的 provider（拿不到 metadata 就算了）。 */
    private fun walk(
        tree: Uri,
        prefix: String,
        depth: Int,
        out: MutableMap<String, Pair<FileStamp, Uri>>,
    ) {
        if (depth > WorkspaceSync.MAX_DEPTH || out.size >= WorkspaceSync.MAX_FILES) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2).orEmpty()
                val modified = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                val size = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkSubtree(tree, docId, path, depth + 1, out)
                } else {
                    out[path] = FileStamp(path, size, modified) to docUri
                }
            }
        }
    }

    private fun walkSubtree(
        tree: Uri,
        parentDocId: String,
        prefix: String,
        depth: Int,
        out: MutableMap<String, Pair<FileStamp, Uri>>,
    ) {
        if (depth > WorkspaceSync.MAX_DEPTH || out.size >= WorkspaceSync.MAX_FILES) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2).orEmpty()
                val modified = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                val size = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                val path = "$prefix/$name"
                val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkSubtree(tree, docId, path, depth + 1, out)
                } else {
                    out[path] = FileStamp(path, size, modified) to docUri
                }
            }
        }
    }

    private fun copyFrom(docUri: Uri, relative: String, modifiedMs: Long): Boolean = runCatching {
        val target = File(workDir, relative)
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(docUri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return@runCatching false
        // ★ 必须把时间戳也对齐。不对齐的话刚拉下来的文件 mtime = 现在，
        //   比工作区里那份"新" ⇒ 下一轮又会被推回去，每次进出都白搬一遍
        //   （真机上看到的就是「进：拉 5」→「出：推 5」的循环）。
        if (modifiedMs > 0) target.setLastModified(modifiedMs)
        true
    }.onFailure { Timber.w(it, "拉取失败：%s", relative) }.getOrDefault(false)

    private fun copyTo(tree: Uri, relative: String, source: File, existing: Uri?): Boolean = runCatching {
        val resolver = context.contentResolver
        val target = existing ?: run {
            // 新文件：建在它的父目录下。父目录不存在就在**根目录**建（扁平化，够用了）
            val parentId = resolveParentDocId(tree, relative) ?: DocumentsContract.getTreeDocumentId(tree)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            DocumentsContract.createDocument(
                resolver,
                parentUri,
                guessMime(relative),
                relative.substringAfterLast('/'),
            )
        } ?: return@runCatching false
        resolver.openOutputStream(target, "wt")?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        } ?: return@runCatching false
        true
    }.onFailure { Timber.w(it, "推回失败：%s", relative) }.getOrDefault(false)

    /** 找到某个相对路径的父目录 docId（父目录不存在就返回 null，调用方用根目录）。 */
    private fun resolveParentDocId(tree: Uri, relative: String): String? = runCatching {
        val segs = relative.split('/').dropLast(1)
        if (segs.isEmpty()) return null
        var current = DocumentsContract.getTreeDocumentId(tree)
        for (seg in segs) {
            current = findChild(tree, current, seg) ?: return null
        }
        current
    }.getOrNull()

    private fun findChild(tree: Uri, parentDocId: String, name: String): String? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) return cursor.getString(0)
            }
        }
        null
    }.getOrNull()

    private fun guessMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }
}
