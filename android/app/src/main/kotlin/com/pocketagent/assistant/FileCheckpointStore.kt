package com.pocketagent.assistant

import com.pocketagent.agentlogic.CheckpointCodec
import com.pocketagent.agentlogic.CheckpointStore
import com.pocketagent.agentlogic.TaskCheckpoint
import timber.log.Timber
import java.io.File

/**
 * **检查点的文件实现** —— 把 `TaskCheckpoint` 落到应用私有目录。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-31：这个类补的是最后一块"装好了没接线"
 * ═══════════════════════════════════════════════════════════════
 *
 * `TaskCheckpoint` / `TaskCheckpointManager` 实现完整、有 25 条离线测试，
 * 但**零生产引用** —— 本项目第五次出现这个形态
 * （前四次：RelationshipStage / PaMotion.bouncy / OutputGuard /
 * PerceptionLadder）。
 *
 * 规划文档把中断恢复列为 P5 横切组件，写明：
 * "移动端中断频率极高，**恢复能力是产品形态的一部分**，不是补丁"。
 *
 * ⚠️ 手机上的中断比桌面频繁得多，而且是**外部的**：
 *   来电、切走、系统杀后台、电量优化。用户没法"等它跑完"。
 *   没有检查点，那些中断全部变成"这件事得从头再说一遍"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 本类只做**文件 IO**，编解码在 `:agentlogic/CheckpointCodec`
 * ═══════════════════════════════════════════════════════════════
 *
 * 编解码最初写在这里，但它**进不了离线验证器** —— 而那正是最需要
 * 测试的部分（引号转义、缺字段兼容、半行容错）。
 * ⇒ 搬到 `:agentlogic`，本类只剩读写文件。
 *
 * ★ 这条分工与项目其它地方一致：`:app` 只做 Android 相关的事，
 *   任何"判断"都往纯 Kotlin 层放。
 */
class FileCheckpointStore(
    private val file: File,
) : CheckpointStore {

    /**
     * 保存（覆盖同一个 taskId）。
     *
     * ⚠️ 幂等性是 `CheckpointStore` 的硬要求：同一 taskId 重复保存
     *    必须**覆盖**而不是追加。否则用户中断恢复一次就会看到两份记录。
     *
     * ★ 实现方式是"读全量 → 按 id 替换 → 整体重写"：
     *   检查点数量是个位数（同时活跃的任务很少），整体重写
     *   比"就地改某一行"简单得多，也不会留下空洞。
     */
    override suspend fun save(checkpoint: TaskCheckpoint) {
        runCatching {
            val all = readAll().filterNot { it.taskId == checkpoint.taskId } + checkpoint
            writeAll(all)
        }.onFailure {
            // ⚠️ 存检查点失败**不该影响任务本身** —— 它是"更好的恢复"，
            //    不是"能不能跑"的前提。失败只记日志。
            Timber.w(it, "检查点落盘失败（不影响任务）")
        }
    }

    override suspend fun load(taskId: String): TaskCheckpoint? =
        runCatching { readAll().firstOrNull { it.taskId == taskId } }
            .onFailure { Timber.w(it, "检查点读取失败") }
            .getOrNull()

    override suspend fun loadLatestIncomplete(): TaskCheckpoint? =
        runCatching {
            readAll()
                .filter { !it.finished }
                // ★ 按落盘时刻取最新 —— 用户在多个任务间切换时，
                //   要恢复的是**最后那个**，不是列表里第一个。
                .maxByOrNull { it.savedAtMs }
        }
            .onFailure { Timber.w(it, "检查点读取失败") }
            .getOrNull()

    /**
     * 删掉一个检查点。
     *
     * ★ 用途：用户明确说"不用恢复了"。删掉比标记 finished 干净 ——
     *   标记会让文件里留下一条永远不会被读的记录，而检查点文件
     *   没有清理机制（它靠"任务数很少"保持小）。
     */
    override suspend fun delete(taskId: String) {
        runCatching {
            val all = readAll().filterNot { it.taskId == taskId }
            writeAll(all)
        }.onFailure { Timber.w(it, "删除检查点失败") }
    }

    // ── 文件 IO ────────────────────────────────────────────────

    private fun writeAll(items: List<TaskCheckpoint>) {
        file.parentFile?.mkdirs()
        // ══════════════════════════════════════════════════════
        //  ★ 先写临时文件再原子改名
        // ══════════════════════════════════════════════════════
        // 直接覆盖时如果进程被杀，会留下一个**半截文件** ——
        // 那比没有文件更糟：下次读的时候整批解析失败，
        // 用户的所有未完成任务都"消失"了。
        //
        // ★ `renameTo` 在同一目录内是原子的（POSIX 保证）。
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(items.joinToString("\n") { CheckpointCodec.encode(it) }, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // 某些文件系统的 rename 会失败 ⇒ 退化成直接写，
            // 至少数据还在，只是失去了原子性。
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun readAll(): List<TaskCheckpoint> {
        if (!file.isFile) return emptyList()
        return file.readLines(Charsets.UTF_8)
            .asSequence()
            // ⚠️ **坏一行跳过，不毁全库**。进程在写盘中途被杀会留下半行，
            //    而那一行不该让其余检查点全部失效。
            .mapNotNull { line ->
                CheckpointCodec.decode(line) ?: run {
                    if (line.isNotBlank()) {
                        Timber.w("跳过一条无法解析的检查点：%s", line.take(40))
                    }
                    null
                }
            }
            .toList()
    }
}
