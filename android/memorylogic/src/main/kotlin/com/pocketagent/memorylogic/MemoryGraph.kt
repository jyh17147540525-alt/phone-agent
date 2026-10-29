package com.pocketagent.memorylogic

/**
 * **记忆星图** —— 把一堆原子记忆排成一张可以转着看的 3D 图。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户要的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * "在 app 内长期记忆的选项里，用户点开后会出现一个 3D 的记忆节点可交互
 *  记忆团……重点事件由大的异色光点标注，用户点击对应的光点会出现相关的
 *  记忆事件。"
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么排布要写成**纯函数**
 * ═══════════════════════════════════════════════════════════════
 *
 * 力导向图的常见写法是"每帧随机抖动 + 迭代到稳定"。那有两个后果：
 *
 *  1. **不可测**。同一堆记忆每次打开位置都不同，用户会以为"它记住了新的"
 *     或者"这条怎么跑到那边去了"。位置在这里是**认知锚点**，
 *     不该每次都变。
 *  2. **不可复现**。"那条重要的事在右上角"这句话，第二次打开就不成立了。
 *
 * ⇒ 这里用**确定性初始化**（黄金角球面分布）+ 固定轮数迭代，
 *   不带任何随机数。同样的输入永远得到同一张图。
 *
 * ⚠️ 力导向的复杂度是 O(n²)。记忆库可能上千条，所以这里**只取**
 *   [MAX_NODES] 条（按重要度），其余不画 —— 一千个点的图人也看不懂。
 */
object MemoryGraph {

    /** 最多画多少个点。超过这个数，人眼已经分不清了。 */
    const val MAX_NODES: Int = 220

    /** 布局迭代轮数。固定值，不是"跑到收敛" —— 收敛与否本身就是不可复现的。 */
    const val ITERATIONS: Int = 90

    /** 图上一个点。 */
    data class Node(
        val atom: Atom,
        val x: Double,
        val y: Double,
        val z: Double,
        /**
         * 重要度 0..1。
         *
         * ★ 它同时决定**点的大小**和**颜色**：用户要的"重点事件由大的异色
         *   光点标注"就是靠这个值。所以它必须来自记忆本身的属性，
         *   不能是随机或"第几条"。
         */
        val weight: Double,
    ) {
        /** 是否"重点事件"（会画成大的异色光点）。 */
        val isKeyEvent: Boolean get() = weight >= KEY_THRESHOLD
    }

    /**
     * 两个点之间的**关系类型**。
     *
     * ★ 2026-10-29 用户指出的错误：
     *   "每个节点之间的连接你没有根据因果关系来分。"
     *
     *   上一版连边用的是「共用溯源」和「同类且时间相邻」——
     *   那只是**共现**（出现在同一段对话里），不是**因果**。
     *   共现连出来的图是一团"什么都连着"的毛线球，
     *   用户看不出"因为什么，所以什么"。
     *
     * ⇒ 现在每条边都必须说明**它是什么关系**，而不只是"它们有关":
     */
    enum class Relation(val label: String) {
        /** 前因 → 后果（有文本上的因果线索） */
        CAUSES("导致"),

        /** 约束的成因（一条事实解释了一条约束/偏好从哪来） */
        EXPLAINS("解释"),

        /** 只是先后发生（有语料连着，但没有因果线索） */
        PRECEDES("先于"),
    }

    /**
     * 一条边，**有方向**：`from` 是因，`to` 是果。
     *
     * ⚠️ 有方向这件事在数据上必须表达出来：无向边画出来的图
     *    天然暗示"互相影响"，而"因为胃病所以不能吃辣"是单向的。
     */
    data class Edge(
        val fromAtomId: String,
        val toAtomId: String,
        val strength: Double,
        val relation: Relation,
    )

    /** 一个点的**因**与**果**（界面用来回答"为什么会这样"）。 */
    fun causesOf(graph: Graph, atomId: String): List<Atom> =
        graph.edges.filter { it.toAtomId == atomId }
            .mapNotNull { e -> graph.nodes.firstOrNull { it.atom.id == e.fromAtomId }?.atom }

    fun effectsOf(graph: Graph, atomId: String): List<Atom> =
        graph.edges.filter { it.fromAtomId == atomId }
            .mapNotNull { e -> graph.nodes.firstOrNull { it.atom.id == e.toAtomId }?.atom }

    data class Graph(val nodes: List<Node>, val edges: List<Edge>) {
        val isEmpty: Boolean get() = nodes.isEmpty()
        val keyEvents: List<Node> get() = nodes.filter { it.isKeyEvent }

        /**
         * **初始节点** —— 星云绽放时第一颗亮起来的点。
         *
         * ═══════════════════════════════════════════════════════════
         *  ★ 用户要的"从中间初始节点开始向外扩散"
         * ═══════════════════════════════════════════════════════════
         *
         * 挑选规则是**确定的**，不随机 —— 理由同文件头：
         * 位置和身份在用户心里都是锚点，每次打开第一颗星都不一样
         * 会让"这是我的记忆"变成"这是一个动画"。
         *
         * 优先级：
         *  ① 最重的那个（通常是一条 CONSTRAINT 或阶段结论）——
         *     它本来就是这段记忆的核心
         *  ② 同分时取**最早**的那条：记忆是从某一天开始长起来的，
         *     最早的核心比最新的核心更符合"源头"的感觉
         *  ③ 再同分按 id：保证纯函数
         *
         * ⚠️ 空图返回 null —— 调用方不该对空图谈"初始节点"。
         */
        val seed: Node?
            get() = nodes.minWithOrNull(
                compareByDescending<Node> { it.weight }
                    .thenBy { it.atom.at }
                    .thenBy { it.atom.id },
            )
    }

    /** 重点事件的门槛。 */
    const val KEY_THRESHOLD: Double = 0.72

    /**
     * 建图。
     *
     * @param atoms 全部原子（未排序也可）
     */
    fun build(atoms: List<Atom>): Graph {
        if (atoms.isEmpty()) return Graph(emptyList(), emptyList())

        // ① 先按重要度取前 N 条 —— 画不下的点连边都画不出来。
        val picked = atoms
            .map { it to weightOf(it) }
            .sortedWith(
                compareByDescending<Pair<Atom, Double>> { it.second }
                    // 同分按时间新→旧，再按 id：**确定性**，见文件头
                    .thenByDescending { it.first.at }
                    .thenBy { it.first.id },
            )
            .take(MAX_NODES)

        // ② 确定性初始化：黄金角球面分布。
        //    ★ 用它而不是随机点：均匀、可复现，而且第一眼就像"星云"。
        val n = picked.size
        val positions = Array(n) { i ->
            val t = if (n == 1) 0.5 else i.toDouble() / (n - 1)
            // 从 -1 到 1 的 z，决定纬度
            val z = 1.0 - 2.0 * t
            val r = kotlin.math.sqrt((1.0 - z * z).coerceAtLeast(0.0))
            val theta = i * GOLDEN_ANGLE
            doubleArrayOf(r * kotlin.math.cos(theta), r * kotlin.math.sin(theta), z)
        }

        val edges = buildEdges(picked.map { it.first })

        // ③ 力导向迭代（确定性）
        val indexOf = picked.mapIndexed { i, p -> p.first.id to i }.toMap()
        repeat(ITERATIONS) {
            relax(positions, edges, indexOf)
        }
        normalize(positions)

        val nodes = picked.mapIndexed { i, (atom, w) ->
            Node(
                atom = atom,
                x = positions[i][0],
                y = positions[i][1],
                z = positions[i][2],
                weight = w,
            )
        }
        return Graph(nodes, edges)
    }

    /**
     * 把整团记忆**归一化到单位球内**。
     *
     * ⚠️ 这一步不是美观问题，是**能不能看见**的问题：
     *    力导向迭代结束时的尺度取决于点数和边的密度 —— 60 条记忆和 200 条
     *    记忆会得到差好几倍的半径，于是同一套渲染参数在一种情况下挤成一坨、
     *    在另一种情况下全都跑到屏幕外（真机第一次就是这样：只看得见几颗孤星）。
     *
     * ⇒ 除以最大半径，让"世界坐标 1.0 = 团的边缘"成为**不变式**。
     */
    private fun normalize(pos: Array<DoubleArray>) {
        var maxR = 1e-9
        for (p in pos) {
            val r = kotlin.math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
            if (r > maxR) maxR = r
        }
        for (p in pos) {
            p[0] /= maxR
            p[1] /= maxR
            p[2] /= maxR
        }
    }

    /**
     * 重要度。
     *
     * ★ 三条来源，都是记忆**本身的事实**：
     *   · 类别权重 —— 约束/阶段结论比零散事实重要
     *   · 置信度 —— 模型越确定越重要
     *   · 溯源广度 —— 由很多轮对话支撑的一条，比一句话来的更"重"
     */
    fun weightOf(atom: Atom): Double {
        val kindWeight = when (atom.kind) {
            AtomKind.CONSTRAINT -> 1.0
            AtomKind.STAGE_CONCLUSION -> 0.85
            AtomKind.PREFERENCE -> 0.7
            AtomKind.FACT -> 0.5
        }
        val breadth = (atom.sourceTurnIds.size / 6.0).coerceIn(0.0, 1.0)
        return (
            kindWeight * 0.5 +
                atom.confidence * 0.3 +
                breadth * 0.2
            ).coerceIn(0.0, 1.0)
    }

    /**
     * 连边 —— **按因果关系，不只按共现**。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★ 2026-10-29 用户指出的错误
     * ═══════════════════════════════════════════════════════════════
     *
     * 上一版只有「共用溯源」和「同类且时间相邻」。那是**共现**，不是因果：
     * 一段对话里同时提到"喜欢猫"和"住在杭州"，它们被连成一条线，
     * 而两者之间其实没有任何关系。图因此长成一团什么都连着的毛线球。
     *
     * ⇒ 现在按优先级判四件事，每条边都带 [Relation]：
     *
     * | 判据 | 关系 | 例子 |
     * |---|---|---|
     * | 因果词（因为/所以/导致/于是…）且**时间在前** | CAUSES | "因为胃疼" → "不能吃辣" |
     * | 事实解释了某条约束/偏好的来源 | EXPLAINS | "他做过胃镜" → "他不能吃辣" |
     * | 同时提到、且**同类**、时间相邻一天内 | PRECEDES | "喜欢猫" → "养了两只猫" |
     * | 其余 | **不连** | —— |
     *
     * ⚠️ 方向必须真的判对：`CAUSES` 的箭头是"因 → 果"。
     *   判反了比不连还糟 —— 用户会看到"不能吃辣，所以他胃疼"。
     */
    private fun buildEdges(atoms: List<Atom>): List<Edge> {
        val out = mutableListOf<Edge>()
        val perNode = HashMap<String, Int>()

        // 先按时间升序：有了顺序，才能判"谁在前"。
        val ordered = atoms.sortedWith(compareBy({ it.at }, { it.id }))

        fun add(from: Atom, to: Atom, strength: Double, relation: Relation) {
            if ((perNode[from.id] ?: 0) >= MAX_EDGES_PER_NODE) return
            if ((perNode[to.id] ?: 0) >= MAX_EDGES_PER_NODE) return
            out += Edge(from.id, to.id, strength, relation)
            perNode[from.id] = (perNode[from.id] ?: 0) + 1
            perNode[to.id] = (perNode[to.id] ?: 0) + 1
        }

        for (i in ordered.indices) {
            for (j in i + 1 until ordered.size) {
                val earlier = ordered[i]
                val later = ordered[j]
                val shared = earlier.sourceTurnIds.any { it in later.sourceTurnIds }

                // ① 因果：后一条带因果词，且前一条是它提到的对象 → 因在前
                if (shared && hasCausalCue(later.text)) {
                    add(earlier, later, 1.0, Relation.CAUSES)
                    continue
                }
                // 因果词出现在前一条里也算（"因为X，所以后来Y"）
                if (shared && hasCausalCue(earlier.text) && !hasCausalCue(later.text)) {
                    add(earlier, later, 0.9, Relation.CAUSES)
                    continue
                }

                // ② 解释：一条 FACT 解释了后面某条 CONSTRAINT/PREFERENCE
                val explains = shared &&
                    earlier.kind == AtomKind.FACT &&
                    (later.kind == AtomKind.CONSTRAINT || later.kind == AtomKind.PREFERENCE)
                if (explains) {
                    add(earlier, later, 0.85, Relation.EXPLAINS)
                    continue
                }

                // ③ 只是先后：同类、一天内、**而且文字上真的在说同一件事**
                //
                // ⚠️ 这里原来只看"同类 + 一天内"，于是"他喜欢猫"和
                //   "他住在杭州"也被连成一条线 —— 那还是共现，不是关系。
                //   加了文字重合之后，只有真的在说同一个对象的才连得上。
                val adjacent = shared &&
                    earlier.kind == later.kind &&
                    (later.at - earlier.at) <= DAY_MS &&
                    textsOverlap(earlier.text, later.text)
                if (adjacent) {
                    add(earlier, later, 0.35, Relation.PRECEDES)
                }
                // ④ 其余不连 —— 宁可不画，也不要画一条假关系
            }
        }
        return out
    }

    /**
     * 这段话里有没有**因果线索**。
     *
     * ⚠️ 刻意用保守的词表：宁可漏判（退化成"先于"），
     *   也不要把"我喜欢狗，但是不养"判成因果 —— 那条边会让用户
     *   在图上看到一条不存在的关系，而图上没有任何东西能纠正他。
     */
    fun hasCausalCue(text: String): Boolean = CAUSAL_CUES.any { text.contains(it) }

    /**
     * 两段文字是否在说**同一个对象**（二元组重合率）。
     *
     * ★ 用二元组而不是分词：中文没空格，而本项目不引分词库
     *   （纯 Kotlin 模块不许加第三方依赖，且词典会让行为随版本漂移）。
     *   `MemoryRecall` 里的记忆检索用的也是同一套做法。
     *
     * ⚠️ 阈值 0.18 刻意偏低：这一条只用来挡掉"完全无关却被共现连起来"，
     *   不需要精确判断同义。宁可多留几条弱边，也不要把真的关联判掉。
     */
    fun textsOverlap(a: String, b: String): Boolean {
        val ga = grams(a)
        val gb = grams(b)
        if (ga.isEmpty() || gb.isEmpty()) return false
        val hit = ga.count { it in gb }
        return hit.toDouble() / minOf(ga.size, gb.size) >= 0.18
    }

    private fun grams(text: String): Set<String> {
        val cleaned = buildString {
            for (c in text) if (c.isLetterOrDigit()) append(c.lowercaseChar())
        }
        if (cleaned.length < 2) return emptySet()
        val out = HashSet<String>(cleaned.length)
        for (i in 0 until cleaned.length - 1) out += cleaned.substring(i, i + 2)
        return out
    }

    private val CAUSAL_CUES = listOf(
        "因为", "所以", "导致", "于是", "因此", "结果", "害得", "才",
        "由于", "既然", "以至于", "弄得",
    )

    /**
     * 一轮力学松弛。
     *
     * ⚠️ 所有点都动、且每轮结果只依赖上一轮 —— 顺序不影响结果，
     *    这是"确定性"的另一半（如果边算边改位置，遍历顺序就会影响结果）。
     */
    private fun relax(
        pos: Array<DoubleArray>,
        edges: List<Edge>,
        indexOf: Map<String, Int>,
    ) {
        val n = pos.size
        val delta = Array(n) { DoubleArray(3) }

        // 斥力：所有点互相推开（避免挤成一坨）
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dx = pos[i][0] - pos[j][0]
                var dy = pos[i][1] - pos[j][1]
                var dz = pos[i][2] - pos[j][2]
                var d2 = dx * dx + dy * dy + dz * dz
                if (d2 < MIN_DIST_SQ) d2 = MIN_DIST_SQ
                val f = REPULSION / d2
                val d = kotlin.math.sqrt(d2)
                dx /= d; dy /= d; dz /= d
                delta[i][0] += dx * f; delta[i][1] += dy * f; delta[i][2] += dz * f
                delta[j][0] -= dx * f; delta[j][1] -= dy * f; delta[j][2] -= dz * f
            }
        }

        // 引力：有边的点互相拉近
        for (e in edges) {
            val i = indexOf[e.fromAtomId] ?: continue
            val j = indexOf[e.toAtomId] ?: continue
            var dx = pos[j][0] - pos[i][0]
            var dy = pos[j][1] - pos[i][1]
            var dz = pos[j][2] - pos[i][2]
            val d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6)
            val f = ATTRACTION * e.strength * d
            dx /= d; dy /= d; dz /= d
            delta[i][0] += dx * f; delta[i][1] += dy * f; delta[i][2] += dz * f
            delta[j][0] -= dx * f; delta[j][1] -= dy * f; delta[j][2] -= dz * f
        }

        // 向心 + 应用
        for (i in 0 until n) {
            delta[i][0] -= pos[i][0] * CENTERING
            delta[i][1] -= pos[i][1] * CENTERING
            delta[i][2] -= pos[i][2] * CENTERING
            for (k in 0..2) {
                pos[i][k] += delta[i][k].coerceIn(-MAX_STEP, MAX_STEP)
            }
        }
    }

    // ── 参数（都是常数，不是随机）────────────────────────────
    private const val MAX_EDGES_PER_NODE = 6
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val GOLDEN_ANGLE = 2.399963229728653
    private const val REPULSION = 0.0022
    private const val ATTRACTION = 0.02
    private const val CENTERING = 0.012
    private const val MAX_STEP = 0.08
    private const val MIN_DIST_SQ = 1e-4
}
