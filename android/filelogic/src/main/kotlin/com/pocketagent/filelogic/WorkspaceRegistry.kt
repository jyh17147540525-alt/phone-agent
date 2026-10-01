package com.pocketagent.filelogic

/**
 * 一次工作区变更的结论。
 *
 * ⚠️ [Rejected] 与 [SaveFailed] 必须分开：
 * - [Rejected] 是**业务判断**（比如"这个目录已经加过了"）—— 用户改一下就行
 * - [SaveFailed] 是**存储失败** —— 用户改什么都没用，该重试或排查
 *
 * 合并成一句"操作失败"会让用户对着一个他无法解决的问题反复尝试。
 */
sealed interface WorkspaceChangeOutcome {

    data object Ok : WorkspaceChangeOutcome

    data class Rejected(val reason: String) : WorkspaceChangeOutcome

    data class SaveFailed(val reason: String) : WorkspaceChangeOutcome
}

/**
 * 工作区注册表 —— **"读-改-写"的唯一收口处**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 最重要的一条：**列表读不出来时，拒绝任何写入**
 * ═══════════════════════════════════════════════════════════════
 *
 * 这是本类存在的核心理由，也是最容易被"顺手优化掉"的地方。
 *
 * 一个想当然的实现是这样的：
 *
 * ```kotlin
 * val current = (store.load() as? Ok)?.workspaces ?: emptyList()   // ✗
 * store.save(current + newWorkspace)
 * ```
 *
 * 它看起来只是"读不出来就当空列表"，但后果是：
 * **存储文件损坏时，用户加一个新工作区 → 原有全部工作区被静默抹掉。**
 * 用户以为自己只是加了一个，实际上丢了全部 —— 而且没有任何提示。
 *
 * ⇒ 所以这里的选择是：**读不出来就什么都不做，如实报告**。
 *   用户看到"工作区列表读不出来，不能在其上追加"，
 *   他至少知道自己该先去处理那个问题，而不是在一个已经被抹掉的
 *   数据上继续操作。
 *
 * 这与 `WorkspaceCodec` 拒绝把损坏兜底成空列表是**同一条纪律的两端**：
 * 一端负责"不谎报没有数据"，另一端负责"不在谎报的基础上写数据"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  线程
 * ═══════════════════════════════════════════════════════════════
 *
 * 所有方法都标了 `@Synchronized`。理由见 [WorkspaceStore] 的注释：
 * 工作区列表很小，"增删改"本质都是"读一份列表再存回去"，
 * 并发调用时的读-改-写竞争会让后写的那个丢掉先写的。
 * 收口在这一层之后，调用方不必再各自加锁。
 */
class WorkspaceRegistry(private val store: WorkspaceStore) {

    /**
     * 读入全部工作区（按"最近打开"降序）。
     *
     * ⚠️ **不吞掉损坏** —— 直接把 `Failed` 交给调用方。
     *    界面要能显示"读不出来"，而不是显示"还没有工作区"。
     */
    @Synchronized
    fun list(): WorkspaceDecodeOutcome = store.load()

    /**
     * 加一个工作区。
     *
     * @param now 创建时刻。由调用方传入（内核不读时钟）。
     */
    @Synchronized
    fun add(root: ScopeRoot, now: Long): WorkspaceChangeOutcome {
        val current = readForWrite().getOrElse { reason ->
            return WorkspaceChangeOutcome.Rejected("现有工作区列表读不出来，不能在其上做修改（$reason）")
        }

        // 同一个目录授权两次是常见的误操作（用户在系统选择器里点了两次）。
        // 直接拒绝并说清楚，比"存两个一样的"好 —— 后者会让
        // "最近打开"排序里出现两个同名项，用户分不清哪个是哪个。
        if (current.any { it.id == root.id }) {
            return WorkspaceChangeOutcome.Rejected("这个目录已经在工作区里了：${root.displayName}")
        }

        return persist(current + Workspace.fromGrant(root, now))
    }

    /** 移除一个工作区。**只移除记录，不动磁盘上的任何文件。** */
    @Synchronized
    fun remove(id: String): WorkspaceChangeOutcome {
        val current = readForWrite().getOrElse { reason ->
            return WorkspaceChangeOutcome.Rejected("现有工作区列表读不出来，不能在其上做修改（$reason）")
        }
        if (current.none { it.id == id }) {
            return WorkspaceChangeOutcome.Rejected("找不到这个工作区：$id")
        }
        return persist(current.filterNot { it.id == id })
    }

    /** 标记一次打开（用于"最近使用"排序）。 */
    @Synchronized
    fun touch(id: String, now: Long): WorkspaceChangeOutcome {
        val current = readForWrite().getOrElse { reason ->
            return WorkspaceChangeOutcome.Rejected("现有工作区列表读不出来，不能在其上做修改（$reason）")
        }
        if (current.none { it.id == id }) {
            return WorkspaceChangeOutcome.Rejected("找不到这个工作区：$id")
        }
        return persist(current.map { if (it.id == id) it.openedAt(now) else it })
    }

    /** 改使用策略（能不能写、能不能删、产出目录、大小上限）。 */
    @Synchronized
    fun updatePolicy(id: String, policy: WorkspacePolicy): WorkspaceChangeOutcome {
        val current = readForWrite().getOrElse { reason ->
            return WorkspaceChangeOutcome.Rejected("现有工作区列表读不出来，不能在其上做修改（$reason）")
        }
        if (current.none { it.id == id }) {
            return WorkspaceChangeOutcome.Rejected("找不到这个工作区：$id")
        }
        return persist(current.map { if (it.id == id) it.copy(policy = policy) else it })
    }

    // ── 内部 ────────────────────────────────────────────────────

    /**
     * 读出来用于写入。
     *
     * ★★ 读不出来时返回 `failure` —— **绝不返回空列表**。
     *    返回空列表等于把用户的全部工作区当成"不存在"，然后覆盖掉。
     *    （用 `Result` 而不是一个可变的"上次失败原因"字段：
     *    后者是输出参数式的副作用，读起来要在两处之间来回跳，
     *    而且它不是线程安全的 —— 本类标了 `@Synchronized`，
     *    但那个字段会让"加锁"变成唯一的正确性来源，太脆。）
     */
    private fun readForWrite(): Result<List<Workspace>> = when (val loaded = store.load()) {
        is WorkspaceDecodeOutcome.Failed -> Result.failure(IllegalStateException(loaded.reason))
        is WorkspaceDecodeOutcome.Ok -> Result.success(loaded.workspaces)
    }

    private fun persist(next: List<Workspace>): WorkspaceChangeOutcome =
        if (store.save(next)) {
            WorkspaceChangeOutcome.Ok
        } else {
            WorkspaceChangeOutcome.SaveFailed("工作区列表保存失败（存储可能不可写）")
        }
}
