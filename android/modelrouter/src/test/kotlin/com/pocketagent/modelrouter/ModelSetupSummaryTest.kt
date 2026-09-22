package com.pocketagent.modelrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型配置体检（[summarizeModelSetup]）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  测试策略：主要测"不该说什么"
 * ═══════════════════════════════════════════════════════════════
 *
 * 这个函数的价值不在于"能算出 worker 列表"（那是一行 filter），
 * 而在于**它什么时候不说话**。
 *
 * 一个永远显示"你缺轻量档"的界面，和一个永远显示"一切正常"的绿条一样，
 * 会被用户彻底无视 —— 我们的检查工具已经在这上面踩过五次
 * （见 `tools/verify/` 的注释）。所以这里大量断言 [ModelSetupSummary.blockers]
 * **为空**。
 */
class ModelSetupSummaryTest {

    private fun model(
        id: String,
        tier: ModelTier = ModelTier.STANDARD,
        roles: Set<ModelRole> = setOf(ModelRole.WORKER),
        enabled: Boolean = true,
        inputPrice: Double? = null,
        outputPrice: Double? = null,
    ) = ModelConfig(
        id = id,
        label = "",
        credentialId = "cred",
        modelId = id,
        modelDisplayName = id,
        tier = tier,
        inputPricePerMillion = inputPrice,
        outputPricePerMillion = outputPrice,
        roles = roles,
        enabled = enabled,
    )

    // ── 分组 ─────────────────────────────────────────────────────

    @Test
    fun `按角色分成 worker 与 scheduler`() {
        val summary = summarizeModelSetup(
            listOf(
                model("w", roles = setOf(ModelRole.WORKER)),
                model("s", roles = setOf(ModelRole.SCHEDULER)),
                model("both", roles = setOf(ModelRole.WORKER, ModelRole.SCHEDULER)),
            )
        )

        assertEquals(listOf("w", "both"), summary.workers.map { it.id })
        assertEquals(listOf("s", "both"), summary.schedulers.map { it.id })
    }

    @Test
    fun `被禁用的模型不出现在 worker 与 scheduler 里`() {
        val summary = summarizeModelSetup(
            listOf(model("off", enabled = false))
        )

        assertTrue(summary.workers.isEmpty())
        assertTrue(summary.schedulers.isEmpty())
        assertEquals(listOf("off"), summary.disabled.map { it.id })
    }

    @Test
    fun `byTier 按轻量到重型排序而不是按添加顺序`() {
        // ⚠️ 界面按档位顺序展示。若实现用 groupBy 的默认键序，
        //    就会变成"用户先配了重型就排最前"—— 排列看起来很随机。
        val summary = summarizeModelSetup(
            listOf(
                model("h", tier = ModelTier.HEAVY),
                model("l", tier = ModelTier.LIGHT),
                model("m", tier = ModelTier.STANDARD),
            )
        )

        assertEquals(
            listOf(ModelTier.LIGHT, ModelTier.STANDARD, ModelTier.HEAVY),
            summary.byTier.keys.toList(),
        )
    }

    @Test
    fun `byTier 只含可用档位`() {
        val summary = summarizeModelSetup(listOf(model("only-light", tier = ModelTier.LIGHT)))

        assertEquals(setOf(ModelTier.LIGHT), summary.byTier.keys)
    }

    @Test
    fun `重复 id 只保留首次出现且不改变顺序`() {
        val summary = summarizeModelSetup(
            listOf(
                model("a", tier = ModelTier.LIGHT),
                model("b", tier = ModelTier.HEAVY),
                model("a", tier = ModelTier.HEAVY),
            )
        )

        assertEquals(listOf("a", "b"), summary.all.map { it.id })
        assertEquals(ModelTier.LIGHT, summary.all[0].tier)
    }

    // ── runnable ─────────────────────────────────────────────────

    @Test
    fun `有可用 worker 时 runnable 为真`() {
        assertTrue(summarizeModelSetup(listOf(model("w"))).runnable)
    }

    @Test
    fun `只有调度者时 runnable 为假`() {
        // 这是个很容易搞错的状态：界面上"有模型、已启用"，
        // 但一个任务都跑不起来。
        val summary = summarizeModelSetup(listOf(model("s", roles = setOf(ModelRole.SCHEDULER))))

        assertFalse(summary.runnable)
        assertTrue(summary.hasScheduler)
    }

    @Test
    fun `完全没有模型时 runnable 为假`() {
        assertFalse(summarizeModelSetup(emptyList()).runnable)
    }

    @Test
    fun `没有调度者不影响 runnable`() {
        // 调度者是**可选**的：难度判断走本地启发式，零成本零延迟
        val summary = summarizeModelSetup(listOf(model("w", roles = setOf(ModelRole.WORKER))))

        assertTrue(summary.runnable)
        assertFalse(summary.hasScheduler)
        assertTrue("没有调度者不该产生任何提醒", summary.blockers.isEmpty())
    }

    // ── blockers：该说什么 ───────────────────────────────────────

    @Test
    fun `一个模型都没有时给出配置引导`() {
        assertEquals(listOf("还没有配置任何模型"), summarizeModelSetup(emptyList()).blockers)
    }

    @Test
    fun `全部只当调度者时指出最具体的原因`() {
        // 说"没有执行模型"虽然也对，但用户看着满屏"已启用"会困惑。
        // 最具体的原因才能指路。
        val summary = summarizeModelSetup(
            listOf(
                model("s1", roles = setOf(ModelRole.SCHEDULER)),
                model("s2", roles = setOf(ModelRole.SCHEDULER)),
            )
        )

        assertEquals(listOf("所有模型都只被设为调度者了，还没有能执行任务的模型"), summary.blockers)
    }

    @Test
    fun `全部被禁用时提示去启用`() {
        val summary = summarizeModelSetup(listOf(model("a", enabled = false)))

        assertEquals(listOf("模型都被禁用了，至少启用一个才能跑任务"), summary.blockers)
    }

    @Test
    fun `启用但完全没挂角色时提示去设置用途`() {
        // roleMask = 0。界面上它是"已启用"的，但调度时会被静默跳过。
        // 这是最容易被用户误认为"配好了"的状态，必须明确指路。
        val summary = summarizeModelSetup(listOf(model("a", roles = emptySet())))

        assertEquals(listOf("有模型还没设置用途，去指定它用来执行任务"), summary.blockers)
    }

    @Test
    fun `调度者与无角色混合时优先说调度者`() {
        // 两种原因同时存在时，说更具体的那个：
        // "只挂了调度者"是用户的**明确选择**，比"忘了挂角色"更可能
        val summary = summarizeModelSetup(
            listOf(
                model("s", roles = setOf(ModelRole.SCHEDULER)),
                model("z", roles = emptySet()),
            )
        )

        assertEquals(listOf("所有模型都只被设为调度者了，还没有能执行任务的模型"), summary.blockers)
    }

    @Test
    fun `禁用的与只挂调度者混合时优先说只挂调度者`() {
        val summary = summarizeModelSetup(
            listOf(
                model("s", roles = setOf(ModelRole.SCHEDULER)),
                model("off", enabled = false),
            )
        )

        assertEquals(listOf("所有模型都只被设为调度者了，还没有能执行任务的模型"), summary.blockers)
    }

    @Test
    fun `多 worker 缺档位时提示会派给相近档位`() {
        val summary = summarizeModelSetup(
            listOf(
                model("l", tier = ModelTier.LIGHT),
                model("h", tier = ModelTier.HEAVY),
            )
        )

        assertTrue(summary.blockers.single().contains("均衡"))
        assertTrue(summary.blockers.single().contains("派给相近档位"))
    }

    // ── blockers：不该说什么（同样重要） ─────────────────────────

    @Test
    fun `只有一个 worker 时不提档位缺口`() {
        // ⚠️ 单模型用户看到"你缺轻量档、缺重型档"只会困惑 ——
        //    他压根没用调度模式，而且他也没打算用。
        val summary = summarizeModelSetup(listOf(model("only", tier = ModelTier.STANDARD)))

        assertTrue("单模型用户不该看到档位提醒", summary.blockers.isEmpty())
    }

    @Test
    fun `三档齐全时没有任何提醒`() {
        val summary = summarizeModelSetup(
            listOf(
                model("l", tier = ModelTier.LIGHT),
                model("m", tier = ModelTier.STANDARD),
                model("h", tier = ModelTier.HEAVY),
            )
        )

        assertTrue(summary.blockers.isEmpty())
    }

    @Test
    fun `多 worker 全在同一档位时仍提示缺档`() {
        // ⚠️ 这条我最初写反了，实现跑出来才发现。
        //
        //    当时的想法是"用户配三个重型模型，显然是同档比价，提醒没意义"。
        //    但实际后果是：这种配置下**每一个简单任务都会被派给重型模型** ——
        //    用户会持续为一个"打开设置"级别的任务付最贵的价格。
        //    而他多半不知道自己可以再配一个便宜档来省这笔钱。
        //
        //    "提醒没意义"的假设是错的：提醒恰恰是这个场景下最有用的信息。
        val summary = summarizeModelSetup(
            listOf(
                model("h1", tier = ModelTier.HEAVY),
                model("h2", tier = ModelTier.HEAVY),
            )
        )

        assertEquals(1, summary.blockers.size)
        assertTrue(summary.blockers.single().contains("轻量"))
        assertTrue(summary.blockers.single().contains("均衡"))
    }

    @Test
    fun `多 worker 只缺一档时只提那一档`() {
        // 提示要具体到缺哪一档 —— 笼统的"档位不全"等于没说
        val summary = summarizeModelSetup(
            listOf(
                model("l", tier = ModelTier.LIGHT),
                model("m", tier = ModelTier.STANDARD),
            )
        )

        assertTrue(summary.blockers.single().contains("重型"))
        assertFalse(summary.blockers.single().contains("轻量"))
    }

    // ── noRole ───────────────────────────────────────────────────

    @Test
    fun `启用了但无角色会被单独标出`() {
        val summary = summarizeModelSetup(listOf(model("z", roles = emptySet())))

        assertEquals(listOf("z"), summary.noRole.map { it.id })
        assertFalse(summary.runnable)
    }

    @Test
    fun `禁用的无角色模型不算 noRole`() {
        // 已经禁用了，再提醒"它没有角色"是噪声
        val summary = summarizeModelSetup(listOf(model("z", roles = emptySet(), enabled = false)))

        assertTrue(summary.noRole.isEmpty())
    }

    // ── cheapestFor ──────────────────────────────────────────────

    @Test
    fun `cheapestFor 选出估算成本最低的`() {
        val cheap = model("cheap", inputPrice = 0.1, outputPrice = 0.1)
        val pricey = model("pricey", inputPrice = 5.0, outputPrice = 5.0)

        assertEquals("cheap", listOf(pricey, cheap).cheapestFor()?.id)
    }

    @Test
    fun `cheapestFor 忽略缺价格的模型`() {
        // ⚠️ 缺价格 ≠ 免费。若实现把 null 当 0，
        //    本地未知模型会永远被选成"最便宜"，而它可能恰恰最贵。
        val unknown = model("unknown")
        val known = model("known", inputPrice = 1.0, outputPrice = 1.0)

        assertEquals("known", listOf(unknown, known).cheapestFor()?.id)
    }

    @Test
    fun `cheapestFor 全都缺价格时返回 null 而不是随便挑一个`() {
        // 返回列表第一个会让界面显示一个**假的最低价**
        assertNull(listOf(model("a"), model("b")).cheapestFor())
    }

    @Test
    fun `cheapestFor 空列表返回 null`() {
        assertNull(emptyList<ModelConfig>().cheapestFor())
    }

    @Test
    fun `cheapestFor 对 0 成本的本地模型正常工作`() {
        val local = model("local", inputPrice = 0.0, outputPrice = 0.0)
        val paid = model("paid", inputPrice = 0.5, outputPrice = 0.5)

        assertEquals("local", listOf(paid, local).cheapestFor()?.id)
    }
}
