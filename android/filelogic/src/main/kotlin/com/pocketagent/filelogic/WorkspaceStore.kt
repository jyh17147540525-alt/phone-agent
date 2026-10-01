package com.pocketagent.filelogic

/**
 * 工作区列表的**存取出口**。实现由 Android 侧提供。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么是两个方法，而不是"仓库"式的 add / remove / rename
 * ═══════════════════════════════════════════════════════════════
 *
 * 因为**工作区列表很小**（几个到几十个），而增删改本质上都是
 * "改一份列表再存回去"。做成 `add` / `remove` / `rename` 会带来一个
 * 真实的隐患：**并发调用时的读-改-写竞争** ——
 * 两个 `add` 同时发生，后写的那个会丢掉先写的。
 *
 * 收成 `load` + `save` 之后，竞争面被压到**调用方一处**，
 * 它可以在一个 `@Synchronized` 或 `Mutex` 里完成"读-改-写"，
 * 而不是让每个方法各自承担这个责任。
 *
 * ⇒ **一个出口比三个语义化方法更容易守住一致性。**
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 实现者的两条义务
 * ═══════════════════════════════════════════════════════════════
 *
 * **1. [save] 必须走 `AtomicTextFile`（在 `:core:common`），不要直接 `writeText`。**
 *    直接覆盖时进程可能在任何一刻消失，留下一个"能打开但内容不全"的文件 ——
 *    而它看起来完全正常，下一次 [load] 会报"损坏"，
 *    用户看到的是"我的工作区都没了"，真实原因却是写了一半。
 *
 * **2. [load] 在文件**不存在**时返回 [WorkspaceDecodeOutcome.Ok]（空列表），
 *    在文件**损坏**时返回 [WorkspaceDecodeOutcome.Failed]。**
 *    这两者必须区分开 —— 见 [WorkspaceCodec.decode] 的注释：
 *    合并成一种会让用户重新添加一遍工作区，而他以为数据没丢。
 */
interface WorkspaceStore {

    /**
     * 读入全部工作区。
     *
     * ⚠️ **不抛异常**。存储层的任何失败（文件不存在、权限、内容损坏）
     *    都要转成 [WorkspaceDecodeOutcome.Failed] 并带上原因 ——
     *    调用方要的是"能如实报告给用户"的结果，而不是一个需要
     *    在每个调用点重新翻译的异常。
     */
    fun load(): WorkspaceDecodeOutcome

    /**
     * 覆写全部工作区。
     *
     * @return 是否成功。**刻意不抛异常** —— 同 `AtomicTextFile.write` 的理由：
     *   调用链上没有任何人能处理它，而抛出去会把"保存没成功"这个
     *   **可解释的业务状态**变成崩溃。
     */
    fun save(workspaces: List<Workspace>): Boolean
}

/**
 * 内存实现 —— **仅供测试与预览**。
 *
 * ⚠️ 它的存在本身就是一条纪律的体现：让"不碰文件系统"的实现
 *    也能被塞进 [WorkspaceStore] 的位置，于是"列表增删改 + 去重 + 排序"
 *    这些**纯逻辑**可以在离线测试里跑完整流程，
 *    而不必为了测一个排序去起一个真实的文件。
 */
class InMemoryWorkspaceStore(
    initial: List<Workspace> = emptyList(),
) : WorkspaceStore {

    private var items: List<Workspace> = initial
    private var failed: String? = null
    private var saveFails = false

    /** 仅供测试：让下一次 [load] 模拟一次"存储损坏"。 */
    fun failNextLoadWith(reason: String) {
        failed = reason
    }

    /** 仅供测试：让下一次 [save] 失败。 */
    fun failNextSave() {
        saveFails = true
    }

    override fun load(): WorkspaceDecodeOutcome {
        failed?.let {
            failed = null
            return WorkspaceDecodeOutcome.Failed(it)
        }
        return WorkspaceDecodeOutcome.Ok(items)
    }

    override fun save(workspaces: List<Workspace>): Boolean {
        if (saveFails) {
            saveFails = false
            return false
        }
        items = workspaces
        return true
    }
}
