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

    /** 两个点之间的关系。 */
    data class Edge(val fromAtomId: String, val toAtomId: String, val strength: Double)

    data class Graph(val nodes: List<Node>, val edges: List<Edge>) {
        val isEmpty: Boolean get() = nodes.isEmpty()
        val keyEvents: List<Node> get() = nodes.filter { it.isKeyEvent }
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
     * 连边。
     *
     * ★ 三条规则，按强度递减：
     *  1. **共用溯源**（两件事由同一段对话得出）→ 强度高
     *  2. **同类且时间相邻**（一天内）→ 中等
     *  3. 不连其他边 —— 全连会变成一团糊，看不出结构
     *
     * ⚠️ 上限 [MAX_EDGES_PER_NODE]：一个人气高的节点连 50 条边，
     *   画面上就是一根毛线球，反而看不出哪些是重点。
     */
    private fun buildEdges(atoms: List<Atom>): List<Edge> {
        val out = mutableListOf<Edge>()
        val perNode = HashMap<String, Int>()
        fun add(a: Atom, b: Atom, strength: Double) {
            if ((perNode[a.id] ?: 0) >= MAX_EDGES_PER_NODE) return
            if ((perNode[b.id] ?: 0) >= MAX_EDGES_PER_NODE) return
            out += Edge(a.id, b.id, strength)
            perNode[a.id] = (perNode[a.id] ?: 0) + 1
            perNode[b.id] = (perNode[b.id] ?: 0) + 1
        }
        for (i in atoms.indices) {
            for (j in i + 1 until atoms.size) {
                val a = atoms[i]
                val b = atoms[j]
                val shared = a.sourceTurnIds.any { it in b.sourceTurnIds }
                val sameKindClose = a.kind == b.kind &&
                    kotlin.math.abs(a.at - b.at) <= DAY_MS
                when {
                    shared -> add(a, b, 1.0)
                    sameKindClose -> add(a, b, 0.35)
                }
            }
        }
        return out
    }

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
