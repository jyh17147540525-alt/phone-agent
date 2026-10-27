package com.pocketagent.memorylogic

/**
 * **记忆的读取端** —— 把 [Atom] 变成一句能写进系统提示词的话。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这件事必须单独一个文件，而不是在 `AppContainer` 里拼字符串
 * ═══════════════════════════════════════════════════════════════
 *
 * "记住一个人"在产品上听起来是一件事，实际是**三段彼此独立**的事：
 *
 * | 段 | 做什么 | 归属 |
 * |---|---|---|
 * | 写入 | 从对话里抽出原子 | [AtomExtractor]（已实现，需要 LLM） |
 * | 筛选 | 丢掉不该记的 | [MemoryGate]（已实现） |
 * | **读取** | 挑出"这次该想起哪几条" | **本文件** |
 *
 * 前两段之前都写完了，但**没有任何消费方** —— 所以助理至今零记忆。
 * 而第三段恰恰是最容易做错的一段：它不是"把全部记忆塞进提示词"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 全量注入的三个后果，全都是静默的
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **提示词膨胀**：`PersonaAssembler` 有预算，超了它会裁层 ——
 *    而被裁的是"骨骼"还是"记忆"，用户看不出来，只感觉"这角色今天不太对"。
 * 2. **答非所问**：用户问"今天好累"，模型却想起三条"他喜欢蓝色"——
 *    于是回答里塞了一句不相关的话，用户以为它没在听。
 * 3. **记忆越久越吵**：三个月后库里有 400 条，每条都进提示词，
 *    模型只能靠猜哪条重要。**记得越多，越像不记得。**
 *
 * ⇒ 所以这里只做一件事：**按这句话挑**。不相关就不出现。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 排序是**确定性的**，不是随机/打分排序
 * ═══════════════════════════════════════════════════════════════
 *
 * 同一句话、同一个库，任何时候跑出来必须**同一条结果**。
 * 用随机或时间抖动打破平手，会让"为什么这次它没想起那件事"变成
 * 不可复现的谜 —— 而这种谜最后都会被解释成"它根本不在乎我"。
 *
 * 平手用 `id` 字典序打破，不是随机数。
 */
object MemoryRecall {

    /** 默认最多想起几条。 */
    const val DEFAULT_LIMIT: Int = 6

    /**
     * 进提示词的字符上限。
     *
     * ⚠️ 比条数上限更重要：6 条长句和 6 条短语差一个数量级。
     *    只限条数的话，一次抽取出三条 200 字的"阶段结论"就能吃掉整个预算。
     */
    const val MAX_SECTION_CHARS: Int = 800

    /**
     * 低于这个分数就不出现。
     *
     * ★ 定在 0.25 的理由：`CONSTRAINT` 的基础分是 0.35，所以
     *   **约束类永远会通过**（"他不能吃辣"这种事不该因为话题不相关就被忘掉）；
     *   而一条既不相关、又没置信度、又旧的 `FACT` 会掉到 0 附近。
     */
    const val MIN_SCORE: Double = 0.25

    /** 各类型的**基础分** —— 见 [AtomKind] 的注释：四类是闭集，权重也是闭集。 */
    private fun kindBase(kind: AtomKind): Double = when (kind) {
        // 约束永远有效：它描述的是"不能做的事"，与话题无关
        AtomKind.CONSTRAINT -> 0.35
        // 偏好是"你是谁"的证据，但只在相关时才该冒出来
        AtomKind.PREFERENCE -> 0.22
        AtomKind.STAGE_CONCLUSION -> 0.12
        AtomKind.FACT -> 0.0
    }

    /**
     * 这个类型是否**必须**和这句话有字面重合才能被想起来。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 为什么需要这条硬规则（2026-10-28 被自己的测试揭出来）
     * ═══════════════════════════════════════════════════════════
     *
     * 上一版只用"基础分 + 置信度 + 新鲜度"打分，结果**任何一条高置信度的
     * 事实都会越过阈值**：一条 `confidence=0.8` 的"他住在杭州"，
     * 在用户问"今天股市怎么样"时拿到 0 + 0.1 + 0.16 = 0.26 > 0.25 —— 出现了。
     *
     * 这正是本文件最怕的那种错：不相关的话被塞进回答，用户以为它没在听。
     * ⇒ 事实/偏好/阶段结论**没有字面重合就不出现**；只有约束可以无条件出现。
     *
     * ⚠️ 这与"漏记"是两件事：宁可这次没想起，不要这次想起不该想的。
     */
    private fun requiresCoverage(kind: AtomKind): Boolean = kind != AtomKind.CONSTRAINT

    /**
     * 挑出这一轮该想起的原子。
     *
     * @param query 用户这一句（空串 = 没有线索，只有基础分在起作用）
     * @param atoms 库里的全部原子
     * @param nowMs 当前时间（用于新鲜度加权；**显式传入而不是读时钟**，
     *   否则这个函数没法被离线测试)
     */
    fun rank(
        query: String,
        atoms: List<Atom>,
        nowMs: Long,
        limit: Int = DEFAULT_LIMIT,
    ): List<Atom> {
        if (atoms.isEmpty() || limit <= 0) return emptyList()
        val q = gramSet(query)
        val scored = atoms.map { atom -> atom to score(atom, q, nowMs) }
            .filter { (atom, s) ->
                s >= MIN_SCORE &&
                    // 见 requiresCoverage 的注释：这一条比阈值更硬
                    (!requiresCoverage(atom.kind) || coverage(q, gramSet(atom.text)) > 0.0)
            }
            .sortedWith(
                compareByDescending<Pair<Atom, Double>> { it.second }
                    // 同分时新的优先（"上次说的"比"三个月前说的"更该被想起）
                    .thenByDescending { it.first.at }
                    // 再同分按 id —— **确定性**，见文件头
                    .thenBy { it.first.id },
            )
        // ⚠️ 按 id 去重：同一个 id 的两份是"合并前的形态"（见 Atom.merge），
        //    当成两条会让提示词里出现两遍几乎一样的话。
        val distinct = scored.map { it.first }.distinctBy { it.id }
        return trimToBudget(distinct, limit)
    }

    /**
     * 生成写进系统提示词的那一段。
     *
     * @return **空串 = 这一轮什么都不用想起**。调用方据此决定加不加这一段 ——
     *   返回一句"（没有相关记忆）"是错的：模型会以为自己在失忆。
     */
    fun promptSection(
        query: String,
        atoms: List<Atom>,
        nowMs: Long,
        limit: Int = DEFAULT_LIMIT,
    ): String {
        val picked = rank(query, atoms, nowMs, limit)
        if (picked.isEmpty()) return ""
        return buildString {
            appendLine("## 你记得的事")
            appendLine("下面是你**已经知道的**关于对方的事，不是对方这一句里说的。")
            appendLine("自然地用，不要复述成清单，也不要每条都用上 —— 想不起来就别说。")
            for (a in picked) {
                appendLine("- （${a.kind.label}）${a.text.trim()}")
            }
        }.trimEnd()
    }

    // ── 打分 ────────────────────────────────────────────────────

    private fun score(atom: Atom, queryGrams: Set<String>, nowMs: Long): Double {
        val coverage = coverage(queryGrams, gramSet(atom.text))
        val freshness = freshness(atom.at, nowMs)
        // ⚠️ 三个因子是**加权和**而不是连乘：连乘会让"不相关"直接清零，
        //    于是约束类也会消失（0 × 什么都没了）。约束必须能靠基础分活下来。
        return coverage * 0.45 + kindBase(atom.kind) + freshness * 0.10 + atom.confidence * 0.20
    }

    /**
     * 查询词被这条记忆覆盖了多少。
     *
     * ★ 用**二元组（bigram）**而不是分词：中文没有空格，而本项目不引分词库
     *   （`PURE_KOTLIN` 模块不许加第三方依赖，且分词词典会让行为随版本漂移）。
     *   二元组在这个场景够用：它抓住的是"字面重合"，而记忆检索要的正是字面重合。
     *
     * ⚠️ 分母是**查询的二元组数**，不是记忆的：我们要问的是
     *   "这句话里有多少线索被这条记忆解释到了"。
     */
    private fun coverage(queryGrams: Set<String>, atomGrams: Set<String>): Double {
        if (queryGrams.isEmpty() || atomGrams.isEmpty()) return 0.0
        val hit = queryGrams.count { it in atomGrams }
        return hit.toDouble() / queryGrams.size
    }

    /**
     * 新鲜度（0..1），按天线性衰减。
     *
     * ★ 衰减到 0 而不是趋近 0：一个月前的记忆不该仅仅因为"新"就被优先，
     *   但也不该被永久判死 —— 它仍然能靠 covered 和基础分进入。
     */
    private fun freshness(atMs: Long, nowMs: Long): Double {
        if (atMs <= 0L) return 0.0
        val ageDays = ((nowMs - atMs).coerceAtLeast(0L)) / 86_400_000.0
        return (1.0 - ageDays / FRESHNESS_DAYS).coerceIn(0.0, 1.0)
    }

    // ── 预算 ────────────────────────────────────────────────────

    /** 按顺序装，装不下就停 —— 停在**边界之前**，不截断半句话。 */
    private fun trimToBudget(picked: List<Atom>, limit: Int): List<Atom> {
        val out = mutableListOf<Atom>()
        var chars = 0
        for (a in picked) {
            if (out.size >= limit) break
            val cost = a.text.length + 8 // 前缀 "（偏好）" 的余量
            if (out.isNotEmpty() && chars + cost > MAX_SECTION_CHARS) break
            out += a
            chars += cost
        }
        return out
    }

    /** 归一化成二元组集合。只留字母与数字（中文照留），去掉标点与空白。 */
    private fun gramSet(text: String): Set<String> {
        val cleaned = buildString {
            for (c in text) {
                if (c.isLetterOrDigit()) append(c.lowercaseChar())
            }
        }
        if (cleaned.length < 2) return emptySet()
        val out = HashSet<String>(cleaned.length)
        for (i in 0 until cleaned.length - 1) {
            out += cleaned.substring(i, i + 2)
        }
        return out
    }

    /** 新鲜度的线性衰减窗口：超过这么多天就不再贡献分数。 */
    private const val FRESHNESS_DAYS: Double = 45.0
}

/**
 * 原子记忆的**持久化端口**。
 *
 * ★ 故意是"整表读 / 整表写"而不是增删改三条：
 *   原子库的量级是几百条（见 [MemoryRecall.MAX_SECTION_CHARS] 的推演），
 *   一次全读全写在这个量级上是几十 KB —— 而"增量更新"要求存储层实现
 *   合并语义，那正是 [Atom.merge] 已经定好的规则，两处实现必然漂移。
 *
 * ⚠️ 本接口在**纯 Kotlin** 模块里，所以不能出现 `DataStore` / `File`。
 *    `:app` 那边用 DataStore 实现它。
 */
interface AtomStore {

    /** 读回全部原子。**读不出来时返回空表，不抛** —— 记忆坏了不该让应用起不来。 */
    fun loadAll(): List<Atom>

    /** 整表覆盖写。 */
    fun replaceAll(atoms: List<Atom>)

    /**
     * 把新抽出来的原子并进库里，返回合并后的全表。
     *
     * ★ 合并规则**只写在这里一份**：同 id 走 [Atom.merge]（置信度取高、
     *   溯源取并集、时间取更早），新 id 直接写入。
     *   让调用方各写一遍的话，两处迟早不一致 —— 而不一致的表现是
     *   "同一条记忆有时在有时不在"，没人能查。
     */
    fun mergeIn(incoming: List<Atom>): List<Atom> {
        if (incoming.isEmpty()) return loadAll()
        val byId = LinkedHashMap<String, Atom>()
        for (a in loadAll()) byId[a.id] = a
        for (a in incoming) {
            val old = byId[a.id]
            byId[a.id] = if (old == null) a else Atom.merge(old, a)
        }
        val merged = byId.values.toList()
        replaceAll(merged)
        return merged
    }
}
