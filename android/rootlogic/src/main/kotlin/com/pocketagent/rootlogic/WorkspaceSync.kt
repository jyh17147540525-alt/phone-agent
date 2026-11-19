package com.pocketagent.rootlogic

/**
 * 工作区（SAF 目录）与容器 `/work` 之间的同步 —— **纯决策，不做 IO**。
 *
 * ## 为什么要同步而不是直接挂载
 *
 * 工作区是 SAF 的 tree URI（可能是 U 盘、网盘 provider），它**没有文件系统路径**，
 * 所以 `mount --bind` 挂不了；容器里也读不到 ContentResolver。
 * 唯一可行的做法是"进容器前拉下来、出容器后推回去"。
 *
 * ## 方向规则只有一条
 *
 * **谁新谁赢**（比较 `modifiedMs`）。同时间戳就不动 —— 两边一样时复制纯属浪费，
 * 而且会在"文件很大"时把一次对话拖成几十秒。
 *
 * ⚠️ 这不是冲突解决，是"最后写入者获胜"。两边同时改同一个文件时，后改的那份
 * 会盖掉另一份 —— 这一点必须写在界面上（见 [syncNote]），不能假装没有。
 *
 * ⚠️ 有上限。工作区可能是几个 GB 的相机目录，全量复制会把手机塞满。
 * 超限时**整次同步都不做**，只报一句原因 —— 部分同步比不同步更让人困惑
 * （"为什么这个文件在、那个不在"）。
 */
data class FileStamp(
    /** 相对路径，用 `/` 分隔，不带前导斜杠。 */
    val path: String,
    val size: Long,
    val modifiedMs: Long,
)

/** 同步方向：从工作区拉进 `/work`，或把 `/work` 推回工作区。 */
enum class SyncDirection { PULL, PUSH }

object WorkspaceSync {

    /** 一次最多同步多少个文件。 */
    const val MAX_FILES = 200

    /** 一次最多同步多少字节（64 MB）。 */
    const val MAX_BYTES = 64L * 1024 * 1024

    /** 单个文件上限（16 MB）—— 更大的东西不该在对话里来回搬。 */
    const val MAX_FILE_BYTES = 16L * 1024 * 1024

    /** 递归深度上限：正常文档目录 4 层就够，再深多半是工程目录。 */
    const val MAX_DEPTH = 6

    /**
     * 超出上限时返回一句人话，否则 null。
     *
     * 顺序讲讲究：**先报总数**（最直观），再报体积。
     */
    fun limitNote(count: Int, totalBytes: Long, biggestFile: Long = 0L): String? = when {
        biggestFile > MAX_FILE_BYTES ->
            "工作区里有一个 ${mb(biggestFile)} 的文件，超过单个文件的 ${mb(MAX_FILE_BYTES)} 上限，这次不同步。"
        count > MAX_FILES ->
            "工作区这次要同步 $count 个文件，超过 $MAX_FILES 个的上限，这次不同步（挪一部分再来）。"
        totalBytes > MAX_BYTES ->
            "工作区这次要同步 ${mb(totalBytes)}，超过 ${mb(MAX_BYTES)} 的上限，这次不同步。"
        else -> null
    }

    /**
     * 算出要做的动作。
     *
     * @param local `/work` 里的文件（相对路径 → 时间戳）
     * @param remote 工作区里的文件
     * @return 动作列表，**顺序稳定**（按路径排序），便于日志与测试
     */
    fun plan(local: List<FileStamp>, remote: List<FileStamp>): List<Pair<FileStamp, SyncDirection>> {
        val localBy = local.associateBy { it.path }
        val remoteBy = remote.associateBy { it.path }
        val paths = (localBy.keys + remoteBy.keys).sorted()
        val out = ArrayList<Pair<FileStamp, SyncDirection>>(paths.size)
        for (path in paths) {
            val l = localBy[path]
            val r = remoteBy[path]
            when {
                l == null && r != null -> out += r to SyncDirection.PULL
                l != null && r == null -> out += l to SyncDirection.PUSH
                l != null && r != null -> when {
                    r.modifiedMs > l.modifiedMs -> out += r to SyncDirection.PULL
                    l.modifiedMs > r.modifiedMs -> out += l to SyncDirection.PUSH
                    // 时间戳一样：内容多半也一样；就算不一样，也没法判断谁对
                    else -> Unit
                }
            }
        }
        return out
    }

    /** 一次同步的一句话结论（界面/日志用）。 */
    fun syncNote(actions: List<Pair<FileStamp, SyncDirection>>): String {
        if (actions.isEmpty()) return "工作区与 /work 已经一致，没有要同步的。"
        val pull = actions.count { it.second == SyncDirection.PULL }
        val push = actions.count { it.second == SyncDirection.PUSH }
        return "同步完成：拉进 $pull 个，推回 $push 个。"
    }

    /** 冲突口径必须写在界面上，不能让用户以为这是"合并"。 */
    fun conflictNote(): String =
        "两边都改过同一个文件时，后改的那份会盖掉另一份（按修改时间判断，不做三路合并）。"

    private fun mb(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        else -> "${bytes / 1024} KB"
    }
}
