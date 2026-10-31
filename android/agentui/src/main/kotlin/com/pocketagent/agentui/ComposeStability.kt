package com.pocketagent.agentui

/**
 * **Compose 稳定性规则** —— 哪些类型会让重组变多。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这条规则值得单独成文件
 * ═══════════════════════════════════════════════════════════════
 *
 * Compose 的跳过重组依赖**类型稳定性**：如果它不能确定某个参数"值相等
 * 就真的相等"，就会把它当成不稳定的，**每次父级重组都重新组合它**。
 *
 * 而 `List` / `Set` / `Map` / `ByteArray` 都是**不稳定类型** ——
 * 它们的 `equals` 要么是接口（不知道实现），要么是引用比较。
 *
 * ⚠️ 真实代价：`TasksViewModel` 的 state 里有 `List<AgentTaskUi>`。
 *    每次任意一个任务变化，整个列表是**新实例** ⇒ Compose 认为变了
 *    ⇒ **消息列表整屏重组**。用户看到的是"输入框卡了一下"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 本项目已踩过的同一个坑（两次）
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. `pendingImages` 用 `Map<taskId, ByteArray>` 而不是塞进
 *    `AgentTaskUi` —— 因为 `ByteArray.equals` 是**引用比较**，
 *    放进 data class 会让 `copy()` 之后"值看起来变了"。
 *    （那是靠直觉避开的，没有规则文档）
 * 2. `Attachment` 里带 `PreparedImage?`（含 `ByteArray`）——
 *    同类问题，同样靠直觉。
 *
 * ⇒ 这两次都躲过了，但**没有东西阻止第三个人再写一次**。
 *   本文件的规则就是把这两次的经验变成可检查的。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三种合法解法，代价不同
 * ═══════════════════════════════════════════════════════════════
 *
 * | 解法 | 代价 | 什么时候用 |
 * |---|---|---|
 * | `@Immutable` 标注 | 零运行时成本，但**你要保证**列表真的不被改 | 只读的 UI 状态（绝大多数情况） |
 * | `kotlinx.collections.immutable` | 加依赖，但类型层面保证 | 需要真的持久化不可变（本项目未引入） |
 * | `@Stable` + 手写 `equals` | 要自己写对，容易错 | 有自定义相等语义的类 |
 *
 * ★ 本项目选**第一种**：这些状态类都是"渲染用的快照"，
 *   没有任何一处会去改它们的列表。标 `@Immutable` 是**陈述事实**，
 *   不是"骗编译器"。
 *
 * ⚠️ 但如果将来真有人改了（比如 `state.plugins.add(...)`），
 *   `@Immutable` 会让 Compose **不重组**，界面停止更新 ——
 *   那是个很隐蔽的 bug。
 *   ⇒ 所以规则里附带一条：**持有不可变集合的状态类必须是 `val`**，
 *     而且列表只能整体替换。见 [isSafeImmutableCandidate]。
 */
object ComposeStability {

    /**
     * 会让 Compose 判定为**不稳定**的类型简名。
     *
     * ⚠️ 只收真正不稳定的。`String` / 基本类型 / 枚举都是稳定的，
     *   把它们算进来会让规则失去意义（报一堆假警报，然后没人看）。
     */
    private val UNSTABLE_TYPES: Set<String> = setOf(
        "List", "MutableList",
        "Set", "MutableSet",
        "Map", "MutableMap",
        "Collection", "Iterable",
        "ByteArray", "IntArray", "LongArray", "FloatArray", "DoubleArray",
        "Array",
    )

    /**
     * 一个字段的稳定性评估。
     *
     * @param name 字段名
     * @param typeName 类型的**简名**（`List`、`String?`、`MyData`…）
     * @param isVal 是不是 `val`（`var` 一定不稳定）
     */
    data class FieldIssue(
        val name: String,
        val typeName: String,
        /** 为什么会不稳定 —— 要能直接说给人听。 */
        val reason: String,
        /** 建议怎么改。 */
        val suggestion: String,
    )

    /**
     * 这个类型名是不是 Compose 认为不稳定的。
     *
     * @param typeName 简名，允许带 `?`（可空不影响稳定性判定）
     * @param customUnstable 调用方声明的"本项目里同样不稳定的自有类型"
     */
    fun isUnstableType(
        typeName: String,
        customUnstable: Set<String> = emptySet(),
    ): Boolean {
        val bare = typeName.trim().removeSuffix("?").trim()
        if (bare in UNSTABLE_TYPES) return true
        // ★ 自定义类型：调用方要显式列出（比如含 ByteArray 的 data class）。
        //   ⚠️ 不做传递推导 —— 那需要解析整个类型图，而这里是纯字符串层面。
        //      误推导比不推导更糟（会报假警报，然后规则被忽略）。
        return bare in customUnstable
    }

    /**
     * 检查一个 UI 状态类。
     *
     * @param className 类名（只用于消息）
     * @param fields 字段列表：名字 → 类型简名
     * @param hasImmutableAnnotation 是否已经标了 `@Immutable` / `@Stable`
     * @param customUnstable 本项目里同样不稳定的自有类型
     *
     * @return 问题列表。**空 = 这个类可以安全地跳过重组**。
     *
     * ⚠️ 已标 `@Immutable` 的类直接返回空 —— 那是调用方的**显式承诺**，
     *   本函数不该推翻它（否则规则会和人的判断打架）。
     */
    fun auditClass(
        className: String,
        fields: Map<String, String>,
        hasImmutableAnnotation: Boolean,
        customUnstable: Set<String> = emptySet(),
    ): List<FieldIssue> {
        if (hasImmutableAnnotation) return emptyList()

        return fields.mapNotNull { (name, type) ->
            if (isUnstableType(type, customUnstable)) {
                FieldIssue(
                    name = name,
                    typeName = type,
                    reason = "$className.$name 是 $type —— Compose 视为不稳定，" +
                        "父级每次重组都会重新组合它",
                    suggestion = "给 $className 标 @Immutable（如果这些集合只读），" +
                        "或改用持久化不可变集合",
                )
            } else {
                null
            }
        }
    }

    /**
     * 这个类**能不能**安全地标 `@Immutable`。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★★ 为什么这条检查必须存在
     * ═══════════════════════════════════════════════════════════
     *
     * `@Immutable` 是**对编译器的承诺**，不是运行时检查。
     * 标了之后 Compose **不再比较**这个参数 —— 直接当作没变。
     *
     * ⚠️ 所以如果那个 List 之后被 `add` 了，界面**永远不更新**。
     *   那不是"慢"，是"坏了"，而且极难查（代码看起来完全正常）。
     *
     * ⇒ 只有"所有字段都是 val"的类才能安全承诺不可变。
     *   有任何一个 `var` 就不行 —— 哪怕它实际没被改过。
     */
    fun isSafeImmutableCandidate(fieldModifiers: Map<String, Boolean>): Boolean {
        // fieldModifiers: 字段名 → 是否 val
        return fieldModifiers.all { (_, isVal) -> isVal }
    }

    /**
     * 给出一句**能贴进 review** 的结论。
     *
     * ★ 规则的价值在于"能被行动"。一长串警告没人看，
     *   一句"这个类标 @Immutable 就能省掉每次整屏重组"才有人改。
     */
    fun summarize(className: String, issues: List<FieldIssue>): String {
        if (issues.isEmpty()) return "$className：稳定，无需处理"
        val fields = issues.joinToString("、") { it.name }
        return "$className：${issues.size} 个不稳定字段（$fields）—— " +
            "导致父级每次重组都会重新组合它"
    }
}
