package com.pocketagent.data

import android.content.Context
import com.pocketagent.core.common.AtomicTextFile
import com.pocketagent.filelogic.Workspace
import com.pocketagent.filelogic.WorkspaceCodec
import com.pocketagent.filelogic.WorkspaceDecodeOutcome
import com.pocketagent.filelogic.WorkspaceStore
import java.io.File

/**
 * [WorkspaceStore] 的文件实现 —— 落在应用私有目录。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么不用 Room（`:memory` 是空的，看起来正合适）
 * ═══════════════════════════════════════════════════════════════
 *
 * 工作区列表的规模是**几个到几十个**，结构是**一个扁平的列表**。
 * 上 Room 要引入：实体 + DAO + 数据库类 + schema 导出 + 迁移测试 ——
 * 而迁移测试恰恰是这个项目里最贵的一类测试（见 `room-migration-offline-verify`）。
 *
 * 更关键的是：`:memory` 是给**记忆**准备的（L0 对话轮次、L1 原子、L2 场景、
 * L3 画像），它的 schema 会随记忆设计演进。让工作区元数据挤进同一套迁移，
 * 会让"改记忆结构"和"改工作区结构"互相绑架 —— 两个不相干的变更
 * 被迫一起做迁移。
 *
 * ⇒ 用 JSON 文件。它没有 schema 迁移的概念，而"版本外壳 + 损坏检测"
 *   已经在 [WorkspaceCodec] 里做完了。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 两条实现纪律
 * ═══════════════════════════════════════════════════════════════
 *
 * **1. 写必须走 [AtomicTextFile]，不能 `writeText`。**
 *    直接覆盖时进程可能在任何一刻消失，留下一个"能打开但内容不全"的文件 ——
 *    它看起来完全正常，下一次 [load] 会报"损坏"，
 *    而用户看到的是"我的工作区都没了"，真实原因却是**写了一半**。
 *
 * **2. [load] 要区分"文件不存在"与"文件损坏"。**
 *    不存在 = 正常的初始状态 → 空列表；
 *    损坏 = 要如实报告 → `Failed`。
 *    合并成一种会让用户重新添加一遍工作区，**而他以为数据没丢**。
 */
class FileWorkspaceStore(private val file: File) : WorkspaceStore {

    override fun load(): WorkspaceDecodeOutcome {
        // ★ 不存在 ≠ 损坏。首次启动时这个文件本来就不存在。
        if (!file.exists()) return WorkspaceDecodeOutcome.Ok(emptyList())

        return try {
            WorkspaceCodec.decode(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            // 读失败（权限、IO）与内容损坏都归到 Failed —— 两者对用户
            // 是同一件事："工作区列表读不出来"，而都不该表现成"没有工作区"。
            WorkspaceDecodeOutcome.Failed("读取工作区存储失败：${e.message ?: e::class.simpleName}")
        }
    }

    override fun save(workspaces: List<Workspace>): Boolean =
        // AtomicTextFile 返回 Boolean 且刻意不抛异常 —— 与本接口的约定一致
        AtomicTextFile.write(file, WorkspaceCodec.encode(workspaces))

    companion object {
        /** 落在 `context.filesDir` 下 —— 用户看不到，也不会被"清理缓存"误删。 */
        const val FILE_NAME = "workspaces.json"

        fun default(context: Context): FileWorkspaceStore =
            FileWorkspaceStore(File(context.filesDir, FILE_NAME))
    }
}
