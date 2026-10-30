package com.pocketagent.memorylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **星云绽放**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 动画最容易出的错是"看起来能跑但节奏错了"
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户要的是"从中间初始节点开始向外扩散，给一种记忆加载出来的感觉"。
 * 这句话里有三个可以直接判真假的东西：
 *
 *  ① **从初始节点开始** ⇒ 有一颗粒子在第 0 毫秒就出现，且只有它
 *  ② **向外** ⇒ 同一时刻亮起来的点，离种子越近越先亮（不是随机）
 *  ③ **加载感** ⇒ 有点东西**先**立住，再铺开（不是全部同时出现）
 *
 * 排版、曲线、时长这些都能用数字判。所以它们必须在离线验证器里，
 * 不能靠肉眼看 Canvas。
 */
class NebulaBloomTest {

    private fun node(id: String, x: Double, y: Double, z: Double, weight: Double = 0.5) =
        MemoryGraph.Node(
            atom = Atom(
                id = id,
                text = "text-$id",
                kind = AtomKind.FACT,
                confidence = 0.8,
                sourceTurnIds = listOf("t1"),
                at = 1_000L,
            ),
            x = x, y = y, z = z, weight = weight,
        )

    // ═══════════════════════════════════════════════════════════
    //  ① 从"初始节点"开始
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `种子点在第零毫秒就开始`() {
        assertEquals(0L, NebulaBloom.startDelayMs(isSeed = true, distanceFromSeed = 0f))
        // ⚠️ 即使距离算错了（比如传了 1f），种子也不该被推迟 ——
        //    它必须是最先出现的那个，否则"从初始节点开始"就不成立。
        assertEquals(0L, NebulaBloom.startDelayMs(isSeed = true, distanceFromSeed = 1f))
    }

    @Test
    fun `非种子点一定晚于种子`() {
        for (d in listOf(0f, 0.01f, 0.5f, 1f)) {
            val delay = NebulaBloom.startDelayMs(isSeed = false, distanceFromSeed = d)
            assertTrue("距离 $d 的点不该和种子同时出现（delay=$delay）", delay > 0L)
        }
    }

    @Test
    fun `初始节点取最重的那条`() {
        val graph = MemoryGraph.Graph(
            nodes = listOf(
                node("a", 0.0, 0.0, 1.0, weight = 0.4),
                node("b", 0.0, 0.0, 0.0, weight = 0.95),
                node("c", 1.0, 0.0, 0.0, weight = 0.6),
            ),
            edges = emptyList(),
        )
        assertEquals("最重的应当被选为初始节点", "b", graph.seed?.atom?.id)
    }

    @Test
    fun `最重的有并列时取最早的那条`() {
        // ★ 记忆是从某一天开始长起来的 —— 最早的核比最新的核更像"源头"。
        val early = node("early", 0.0, 0.0, 0.0, weight = 0.9).let {
            it.copy(atom = it.atom.copy(at = 100L))
        }
        val late = node("late", 0.5, 0.0, 0.0, weight = 0.9).let {
            it.copy(atom = it.atom.copy(at = 900L))
        }
        val graph = MemoryGraph.Graph(listOf(late, early), emptyList())
        assertEquals("并列时应当取更早的", "early", graph.seed?.atom?.id)
    }

    @Test
    fun `初始节点是确定的`() {
        // ⚠️ 同样的输入必须永远给出同一个种子：每次打开第一颗星都不同，
        //    会让"这是我的记忆"变成"这是一个动画"。
        val nodes = listOf(
            node("a", 0.0, 0.0, 0.0, 0.7),
            node("b", 0.3, 0.0, 0.0, 0.7),
            node("c", 0.6, 0.0, 0.0, 0.7),
        )
        val first = MemoryGraph.Graph(nodes, emptyList()).seed?.atom?.id
        val second = MemoryGraph.Graph(nodes.reversed(), emptyList()).seed?.atom?.id
        assertEquals("顺序不该影响种子", first, second)
        assertNotNull(first)
    }

    @Test
    fun `空图没有初始节点`() {
        assertNull(MemoryGraph.Graph(emptyList(), emptyList()).seed)
    }

    // ═══════════════════════════════════════════════════════════
    //  ② 向外扩散（不是随机出现）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `越远的点开始得越晚`() {
        val delays = listOf(0.1f, 0.3f, 0.6f, 1f)
            .map { NebulaBloom.startDelayMs(isSeed = false, distanceFromSeed = it) }
        // 严格递增 —— 这是"从内向外"的唯一硬证据
        assertEquals("延迟必须随距离单调递增：$delays", delays, delays.sorted())
        assertEquals("四个不同距离应得到四个不同延迟：$delays", 4, delays.distinct().size)
    }

    @Test
    fun `最远的点也不会超过总时长`() {
        val far = NebulaBloom.startDelayMs(isSeed = false, distanceFromSeed = 1f)
        assertTrue(
            "最远的点开始后必须还有时间生长（$far + ${NebulaBloom.GROW_MS} > ${NebulaBloom.TOTAL_MS}）",
            far + NebulaBloom.GROW_MS <= NebulaBloom.TOTAL_MS,
        )
    }

    @Test
    fun `距离归一化到单位球直径`() {
        val center = node("c", 0.0, 0.0, 0.0)
        val surface = node("s", 1.0, 0.0, 0.0)
        val far = node("f", -1.0, 0.0, 0.0)

        assertEquals("同一点距离为零", 0f, NebulaBloom.normalizedDistance(center, center), 0.0001f)
        assertEquals("单位球表面 = 0.5", 0.5f, NebulaBloom.normalizedDistance(center, surface), 0.0001f)
        // ⚠️ 直径两端必须是 1.0 —— 不除以 2 的话最外圈永远到不了错峰上限，
        //    表现为"外圈的点几乎同时冒出来"，扩散感消失。
        assertEquals("直径两端 = 1.0", 1f, NebulaBloom.normalizedDistance(surface, far), 0.0001f)
    }

    @Test
    fun `距离是对称的`() {
        val a = node("a", 0.2, -0.4, 0.6)
        val b = node("b", -0.5, 0.1, -0.3)
        assertEquals(
            NebulaBloom.normalizedDistance(a, b),
            NebulaBloom.normalizedDistance(b, a),
            0.0001f,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  ③ 加载感：先起手，再铺开
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `种子点亮之后有一段起手停顿`() {
        // ★ 这一停就是"加载感"的来源。没有它只是"点同时变多"。
        assertTrue("必须有起手停顿", NebulaBloom.SEED_HOLD_MS > 0L)
        // ⚠️ 但不能长到让人以为卡了：200ms 内没有变化人就开始怀疑没点到。
        assertTrue(
            "起手停顿不能长到像卡住（现在 ${NebulaBloom.SEED_HOLD_MS}ms）",
            NebulaBloom.SEED_HOLD_MS <= 200L,
        )
    }

    @Test
    fun `第零毫秒只有种子可见`() {
        // 第 0 毫秒：种子进度应为 0（刚开始），其余点都还没开始。
        assertEquals(0f, NebulaBloom.progressAt(0L, 0L), 0.0001f)
        for (d in listOf(0.1f, 0.5f, 1f)) {
            val delay = NebulaBloom.startDelayMs(isSeed = false, d)
            assertFalse("距离 $d 的点在第 0 毫秒不该出现", NebulaBloom.progressAt(0L, delay) > 0f)
        }
    }

    @Test
    fun `起手停顿结束时非种子点仍未出现`() {
        // 这一条钉住"停顿"是真的停顿，不是被别的点填满。
        val t = NebulaBloom.SEED_HOLD_MS
        val nearDelay = NebulaBloom.startDelayMs(isSeed = false, 0.01f)
        assertTrue("停顿刚结束时最近的邻点也不该已经长出来", NebulaBloom.progressAt(t, nearDelay) <= 0.01f)
    }

    @Test
    fun `尾声时整团就位`() {
        for (d in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val delay = NebulaBloom.startDelayMs(isSeed = false, d)
            assertEquals("距离 $d 的点在总时长处必须完全就位", 1f, NebulaBloom.progressAt(NebulaBloom.TOTAL_MS, delay), 0.0001f)
        }
        assertEquals("种子当然也就位", 1f, NebulaBloom.progressAt(NebulaBloom.TOTAL_MS, 0L), 0.0001f)
    }

    @Test
    fun `进度不会超过一也不会小于零`() {
        assertEquals(0f, NebulaBloom.progressAt(-5_000L, 0L), 0.0001f)
        assertEquals(1f, NebulaBloom.progressAt(999_999L, 0L), 0.0001f)
    }

    @Test
    fun `总时长覆盖所有点的完整生长`() {
        // ⚠️ 这条防"最后几颗星永远在飞"：总时长必须 >= 最晚开始 + 生长时长。
        val worst = NebulaBloom.startDelayMs(isSeed = false, 1f) + NebulaBloom.GROW_MS
        assertTrue(
            "总时长 ${NebulaBloom.TOTAL_MS} 必须覆盖最坏情况 $worst",
            NebulaBloom.TOTAL_MS >= worst,
        )
    }

    @Test
    fun `动画会结束`() {
        // ⚠️ 用 >= 而不是 ==：帧间隔不固定，判相等会让动画永远不结束
        //    （一直每帧重绘，白白耗电）。
        assertFalse(NebulaBloom.isFinished(0L))
        assertFalse(NebulaBloom.isFinished(NebulaBloom.TOTAL_MS - 1))
        assertTrue(NebulaBloom.isFinished(NebulaBloom.TOTAL_MS))
        assertTrue(NebulaBloom.isFinished(NebulaBloom.TOTAL_MS + 5_000L))
    }

    // ═══════════════════════════════════════════════════════════
    //  曲线与视觉量
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `缓出曲线端点正确且单调`() {
        assertEquals(0f, NebulaBloom.easeOutCubic(0f), 0.0001f)
        assertEquals(1f, NebulaBloom.easeOutCubic(1f), 0.0001f)
        // 超界要夹住（帧回调可能给出略超的值）
        assertEquals(0f, NebulaBloom.easeOutCubic(-1f), 0.0001f)
        assertEquals(1f, NebulaBloom.easeOutCubic(2f), 0.0001f)
        // 单调不减
        var prev = -1f
        for (i in 0..20) {
            val v = NebulaBloom.easeOutCubic(i / 20f)
            assertTrue("缓出必须单调：在第 $i 步回落了", v >= prev)
            prev = v
        }
    }

    @Test
    fun `缓出是启动快末尾稳`() {
        // ★ 缓出（不是缓入）：从中心飞出去要"启动快、末尾稳"。
        //   缓入会得到"先慢慢挪、然后猛地撞到位"，像物理引擎没调好。
        val half = NebulaBloom.easeOutCubic(0.5f)
        assertTrue("到一半时应已走完大半路程（现在 $half）", half > 0.75f)
    }

    @Test
    fun `位置从球心长到自己的位置`() {
        // ★ 乘在世界坐标上 ⇒ 世界坐标乘 0 就是球心。
        //   所有点因此天然从正中间出发，且旋转/缩放同时生效。
        assertEquals(0f, NebulaBloom.radiusFactor(0f), 0.0001f)
        assertEquals(1f, NebulaBloom.radiusFactor(1f), 0.0001f)
    }

    @Test
    fun `尺寸出生时不是零`() {
        // ⚠️ 起始为 0 会让第一帧完全看不见 —— 表现为"闪一下"，
        //    而不是"长出来"。
        assertTrue("出生尺寸不能是 0（现在 ${NebulaBloom.SIZE_AT_BIRTH}）", NebulaBloom.SIZE_AT_BIRTH > 0f)
        assertEquals(NebulaBloom.SIZE_AT_BIRTH, NebulaBloom.sizeFactor(0f), 0.0001f)
        assertEquals(1f, NebulaBloom.sizeFactor(1f), 0.0001f)
    }

    @Test
    fun `尺寸单调增长`() {
        var prev = -1f
        for (i in 0..20) {
            val v = NebulaBloom.sizeFactor(i / 20f)
            assertTrue("尺寸必须单调增长：在第 $i 步回落了", v >= prev)
            prev = v
        }
    }

    @Test
    fun `淡入只占生长前段`() {
        // ★ 全程半透明会让点像"幽灵"。前段淡入、之后不透明才是"实化"。
        assertEquals(0f, NebulaBloom.alpha(0f), 0.0001f)
        assertTrue("开光时应当已经不太透明", NebulaBloom.alpha(NebulaBloom.FADE_PORTION) >= 0.99f)
        // 之后就一直是 1
        assertEquals(1f, NebulaBloom.alpha(0.8f), 0.0001f)
        assertEquals(1f, NebulaBloom.alpha(1f), 0.0001f)
    }

    @Test
    fun `淡入比例不能占满全程`() {
        assertTrue(
            "淡入比例必须小于 1（现在 ${NebulaBloom.FADE_PORTION}）",
            NebulaBloom.FADE_PORTION < 1f,
        )
        assertTrue(NebulaBloom.FADE_PORTION > 0f)
    }

    // ═══════════════════════════════════════════════════════════
    //  可点性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `飞行途中不可点`() {
        // ⚠️ 不能拿 progress > 0 判可点：刚飞出来的点都在球心附近叠成一团，
        //    用户点到的永远不是他看见的那颗。
        assertFalse("刚出现时不该可点", NebulaBloom.isTappable(0.01f))
        assertFalse("一半路程时不该可点", NebulaBloom.isTappable(0.4f))
        assertTrue("就位后可点", NebulaBloom.isTappable(1f))
    }

    @Test
    fun `可点门槛不能让落位的点变得点不到`() {
        // 门槛太高（如 0.95）会让"看起来已经停了的点"点不动。
        assertTrue("门槛不能过高", NebulaBloom.isTappable(0.7f))
    }

    // ═══════════════════════════════════════════════════════════
    //  页面转场与动画的相位关系
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `整段绽放必须长于页面转场`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条测试来自一次真机测量
        // ══════════════════════════════════════════════════════
        // NavHost 的进入转场是 380ms（`PaDuration.Page`）。
        // 绽放若在这一帧就开始计时，用户看到的第一眼已经是 400ms 之后 ——
        // 种子点"先在中间亮起来、停一下"那一段正好整个落在转场里。
        //
        // 真机证据（screencap 连拍 + 亮点计数）：
        //   第一帧 789 个亮点（已不是"只有种子"）
        //   第二帧 3041（几乎就位）
        //   之后 3080/3075/3166（稳定）
        // ⇒ 绽放确实在跑，但**在页面可见之前就跑完了**。
        //
        // 修法是在 UI 层先等转场结束再起表。而这条测试守的是更根本的一点：
        // **绽放总时长本身必须明显长于转场**，否则就算相位对齐了，
        // 用户也只看得到尾巴。
        val pageTransitionMs = 380L // = 设计系统 `PaDuration.Page`
        assertTrue(
            "绽放 ${NebulaBloom.TOTAL_MS}ms 必须明显长于转场 ${pageTransitionMs}ms",
            NebulaBloom.TOTAL_MS > pageTransitionMs,
        )
        // 起手停顿 + 错峰的可见部分也要够长，否则"从内向外"看不出来。
        assertTrue(
            "起手+错峰必须留出可见的扩散过程",
            NebulaBloom.SEED_HOLD_MS + NebulaBloom.MAX_STAGGER_MS > pageTransitionMs,
        )
    }

    @Test
    fun `绽放总时长不至于长到让人等`() {
        // ⚠️ 上限也要有：超过一秒半的"加载动画"就不再是加载感，是卡。
        assertTrue(
            "总时长 ${NebulaBloom.TOTAL_MS}ms 过长（人不该盯着加载超过 1.5 秒）",
            NebulaBloom.TOTAL_MS <= 1_500L,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  「先闪一下铺满、再重新绽放」
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `进度表初值必须是零而不是空表`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条测试守的是一个真机现象
        // ══════════════════════════════════════════════════════
        // 用户："打开记忆星云的一瞬间，是绽放着的形态，但过了这一瞬间，
        //        就进入你写好的进程了，从一个点绽放开来。"
        //
        // 真机证据（screenrecord 20fps 逐帧）：
        //   帧16~23  lit≈2080 minD≈30   ← 星图**已经全部铺满**，约 400ms
        //   帧24     lit=167  minD=282  ← 骤降，重新开始绽放
        //
        // 根因：`bloomById` 初值是 `emptyMap()`，而投影写的是
        //   `bloomById[id] ?: 1f` —— **查不到当作"已就位"** ⇒ 首帧画满；
        //   协程填表后点缩回球心 ⇒ 重新绽放。
        //
        // ⇒ 兜底值必须是 0。下面直接验 [NebulaBloom.bloomOf] 的语义。
        val nodes = listOf(
            node("a", 0.0, 0.0, 0.0),
            node("b", 0.5, 0.0, 0.0),
            node("c", -0.5, 0.3, 0.2),
        )
        val table = NebulaBloom.emptyTable(nodes)

        assertEquals("完整表必须覆盖每个点", nodes.size, table.size)
        assertTrue("初值必须全是 0", table.values.all { it == 0f })
        // 每个点查出来都是 0 —— 首帧不会有任何点被画出来
        for (n in nodes) {
            assertEquals("「${n.atom.id}」首帧必须是 0 进度", 0f, NebulaBloom.bloomOf(table, n.atom.id), 0.0001f)
        }
    }

    @Test
    fun `查不到的点一律当作还没出现`() {
        // ══════════════════════════════════════════════════════
        //  ★★ 这条是这个 bug 的**直接封印**
        // ══════════════════════════════════════════════════════
        // 旧实现是 `?: 1f`。只要把兜底从 1f 改成 0f，这一条就从红变绿。
        // 换句话说：把它改回 `?: 1f`，这条测试立刻失败 —— 它有牙齿。
        //
        // ⚠️ "查不到 = 还没出现"这个语义必须钉死。写成"已就位"会让
        //    **首帧**（表还没建）把整张图画满，然后收缩、再绽放。
        val empty = emptyMap<String, Float>()
        assertEquals(
            "空表里查任何点都必须是 0（还没出现），绝不能是 1（已就位）",
            0f,
            NebulaBloom.bloomOf(empty, "anything"),
            0.0001f,
        )

        val partial = mapOf("a" to 1f)
        assertEquals("表里有的是 1", 1f, NebulaBloom.bloomOf(partial, "a"), 0.0001f)
        assertEquals("表里没有的仍是 0", 0f, NebulaBloom.bloomOf(partial, "b"), 0.0001f)
    }

    @Test
    fun `脏进度会被夹住`() {
        val table = mapOf("a" to 5f, "b" to -3f, "c" to 0.5f)
        assertEquals(1f, NebulaBloom.bloomOf(table, "a"), 0.0001f)
        assertEquals(0f, NebulaBloom.bloomOf(table, "b"), 0.0001f)
        assertEquals(0.5f, NebulaBloom.bloomOf(table, "c"), 0.0001f)
    }

    @Test
    fun `首帧不该有任何点已就位`() {
        // ★ 用户看到的是"铺满"，等价于"首帧就有进度=1 的点"。
        //   用完整空表时，首帧所有点都是 0 ⇒ 一条都不会画。
        val nodes = listOf(node("a", 0.0, 0.0, 0.0), node("b", 0.4, 0.0, 0.0))
        val table = NebulaBloom.emptyTable(nodes)
        val anyReady = nodes.any { NebulaBloom.bloomOf(table, it.atom.id) > 0f }
        assertFalse("首帧不该有任何点可见（任何可见点都等于「已经铺满」）", anyReady)
    }

    @Test
    fun `不存在任何时刻让所有点同时已就位后倒退`() {
        // ══════════════════════════════════════════════════════
        //  ★ 单调性：进度在时间轴上不许倒退
        // ══════════════════════════════════════════════════════
        // "铺满 → 收缩 → 重新绽放"的本质就是**进度倒退**。
        // 只要每个点的进度单调不减，就不可能先满后空。
        val delays = listOf(
            NebulaBloom.startDelayMs(true, 0f),
            NebulaBloom.startDelayMs(false, 0.15f),
            NebulaBloom.startDelayMs(false, 0.45f),
            NebulaBloom.startDelayMs(false, 0.8f),
            NebulaBloom.startDelayMs(false, 1f),
        )

        for ((i, d) in delays.withIndex()) {
            var prev = -1f
            var t = 0L
            while (t <= NebulaBloom.TOTAL_MS + 100) {
                val p = NebulaBloom.progressAt(t, d)
                assertTrue(
                    "点 #$i 在 ${t}ms 出现进度倒退（$prev → $p）—— 那就是「先满后空」",
                    p >= prev - 0.0001f,
                )
                prev = p
                t += 5
            }
        }
    }
}
