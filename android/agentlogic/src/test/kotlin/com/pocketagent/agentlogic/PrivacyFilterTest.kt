package com.pocketagent.agentlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PrivacyFilter` 的测试。
 *
 * ## 为什么这个组件的测试格外重要
 *
 * 这里每一条规则错掉的后果都是**静默的**：
 *
 * | 规则错了 | 症状 |
 * |---|---|
 * | 敏感页面没被拦 | 支付页截图被上传，**用户完全不知情** |
 * | 系统 UI 未知时没丢弃 | 通知栏内容（常常就是聊天消息）随图上传 |
 * | 插件分析没被收紧 | 第三方插件拿到屏幕内容，**我们无法追溯它发去哪** |
 * | 输入框没被遮 | 用户正在输入的密码/验证码进了第三方服务器 |
 * | 伪造标记没被拒 | 任何人加一行 `alreadyFiltered = true` 即可绕过整个关卡 |
 *
 * **没有一条会抛异常、会崩溃、会在真机上"一眼看出来"。**
 * 只有确定性离线测试能钉住它们 —— 这正是本组件被写进纯逻辑模块的原因。
 */
class PrivacyFilterTest {

    private val statusBar = RedactRegion(left = 0, top = 0, right = 1440, bottom = 100)
    private val navBar = RedactRegion(left = 0, top = 3140, right = 1440, bottom = 3200)

    private fun filter(
        systemUi: SystemUiRegions = SystemUiRegions(statusBar, navBar),
        policy: FilterPolicy = FilterPolicy(),
    ) = PrivacyFilter(systemUi, policy)

    private fun screenshotRequest(
        purpose: UploadPurpose = UploadPurpose.TASK_REASONING,
        pageKind: PageKind = PageKind.NORMAL,
        inputs: List<RedactRegion> = emptyList(),
        messages: List<RedactRegion> = emptyList(),
        qualityWanted: Boolean = false,
    ) = UploadRequest(
        context = UploadContext(providerId = "test-provider", purpose = purpose),
        requiresScreenshot = true,
        pageKind = pageKind,
        inputFieldRegions = inputs,
        messageListRegions = messages,
        qualityReductionWanted = qualityWanted,
    )

    private fun textRequest(
        purpose: UploadPurpose = UploadPurpose.TASK_REASONING,
        inputs: List<RedactRegion> = emptyList(),
    ) = UploadRequest(
        context = UploadContext(providerId = "test-provider", purpose = purpose),
        requiresScreenshot = false,
        inputFieldRegions = inputs,
    )

    private fun allowed(request: UploadRequest): RedactionPlan {
        val outcome = filter().review(request)
        assertTrue("本应允许上传，实际被丢弃：$outcome", outcome is FilterOutcome.Allowed)
        return (outcome as FilterOutcome.Allowed).plan
    }

    private fun droppedReason(request: UploadRequest): String {
        val outcome = filter().review(request)
        assertTrue("本应丢弃，实际被允许：$outcome", outcome is FilterOutcome.Dropped)
        return (outcome as FilterOutcome.Dropped).reason
    }

    // ══ ★★ 第一组：敏感页面一票否决 ══════════════════════════════

    @Test
    fun `敏感页面必须丢弃且不进任何分支`() {
        val reason = droppedReason(screenshotRequest(pageKind = PageKind.SENSITIVE))

        // 理由必须能让用户看懂 —— "执行过程可见"是项目原则
        assertTrue(
            "丢弃理由应说明是敏感页面且已中止，实际：$reason",
            reason.contains("敏感") && reason.contains("中止"),
        )
    }

    @Test
    fun `敏感页面即使无输入框无消息也要丢弃`() {
        // ★ 反向验证：不能因为"没检测到可疑区域"就放行。
        //   支付页的风险来自页面本身，不来自我们识别出的某个控件。
        val reason = droppedReason(
            screenshotRequest(pageKind = PageKind.SENSITIVE, inputs = emptyList(), messages = emptyList()),
        )

        assertTrue(reason.contains("敏感"))
    }

    @Test
    fun `敏感页面连纯文本上传也拒绝`() {
        // ★ 敏感页面的裁决**先于**"需不需要像素"的判断。
        //   若顺序反过来，纯文本请求会走 step 3 提前返回而被放行 ——
        //   支付页的无障碍树里同样有金额与卡号后四位。
        val reason = droppedReason(
            UploadRequest(
                context = UploadContext(providerId = "p", purpose = UploadPurpose.TASK_REASONING),
                requiresScreenshot = false,
                pageKind = PageKind.SENSITIVE,
            ),
        )

        assertTrue("敏感页面应先于文本分支被拦下，实际理由：$reason", reason.contains("敏感"))
    }

    // ══ ★★ 第二组：插件分析一律收紧 ══════════════════════════════

    @Test
    fun `插件分析不允许上传截图`() {
        val reason = droppedReason(screenshotRequest(purpose = UploadPurpose.PLUGIN_ANALYSIS))

        assertTrue("插件截图应被拒绝，实际：$reason", reason.contains("插件"))
    }

    @Test
    fun `插件分析即使策略放宽也不允许截图`() {
        // ★★ 关键：`policy.allowScreenshots = true` 是调用方可配的，
        //    而插件分析的底线**不该由调用方决定**。
        //    若这里用了 policy 判断，放宽配置就会静默打开插件的截图权限。
        val permissive = filter(policy = FilterPolicy(allowScreenshots = true))
        val outcome = permissive.review(screenshotRequest(purpose = UploadPurpose.PLUGIN_ANALYSIS))

        assertTrue("插件截图的拒绝不应受调用方配置影响", outcome is FilterOutcome.Dropped)
    }

    @Test
    fun `插件分析的纯文本请求仍然允许`() {
        // 反向验证：收紧的是"截图"这一项能力，不是把插件整体封死。
        // 过度封锁会让插件功能不可用，那是另一种失真。
        val plan = allowed(textRequest(purpose = UploadPurpose.PLUGIN_ANALYSIS))

        assertTrue("纯文本无像素可降", !plan.reduceQuality)
    }

    // ══ ★★ 第三组：系统 UI 未知 → 保守丢弃 ══════════════════════

    @Test
    fun `系统 UI 未知时丢弃整张截图`() {
        val outcome = filter(systemUi = SystemUiRegions.unknown).review(screenshotRequest())

        assertTrue("无法确定系统 UI 区域时应保守丢弃", outcome is FilterOutcome.Dropped)
        val reason = (outcome as FilterOutcome.Dropped).reason
        assertTrue("理由应说明是系统 UI 未知，实际：$reason", reason.contains("系统 UI"))
    }

    @Test
    fun `系统 UI 未知不影响纯文本上传`() {
        // ★ 边界：文本没有像素，系统栏信息与它无关。
        //   若这里也丢弃，会让"无障碍可用但截图权限未授"的用户完全无法使用 —— 
        //   而那种情况下我们本来就没打算上传任何像素。
        val plan = allowed(textRequest())

        assertNotNull(plan)
    }

    @Test
    fun `只想关掉系统UI裁剪时未知区域不再阻断`() {
        // policy.stripSystemUi = false 表示调用方明确表示不处理系统栏。
        // 此时"不知道系统栏在哪"就不再是阻断条件 —— 但也意味着**什么都不裁**。
        // ★ 这是一个刻意的、需要显式配置才能进入的状态，不会是默认值。
        val noStrip = filter(
            systemUi = SystemUiRegions.unknown,
            policy = FilterPolicy(stripSystemUi = false),
        )
        val outcome = noStrip.review(screenshotRequest())

        assertTrue(outcome is FilterOutcome.Allowed)
        assertTrue("明确不裁剪时计划应为空操作", (outcome as FilterOutcome.Allowed).plan.isNoOp)
    }

    @Test
    fun `只有刘海已知也算已知`() {
        // ★ 部分已知 ≠ 未知。只要有任一系统 UI 区域量到了，就可以继续，
        //   因为裁剪是"按已知区域裁"，漏裁的风险由其余策略承担。
        //   ⚠️ 这条与上一条的差别很细微，必须钉住，否则后来者可能改成"必须全部已知"，
        //      而"全部已知"在某些机型上永远不成立 → 过滤永远丢弃 → 功能不可用。
        val cutout = RedactRegion(0, 0, 400, 80)
        val partial = filter(systemUi = SystemUiRegions(statusBar = null, navigationBar = null, displayCutout = cutout))

        val plan = (partial.review(screenshotRequest()) as FilterOutcome.Allowed).plan

        assertEquals("应裁掉已知的刘海区域", 1, plan.cropOut.size)
        assertEquals(listOf(cutout), plan.cropOut)
    }

    // ══ ★★ 第四组：遮蔽计划的内容 ════════════════════════════════

    @Test
    fun `正常路径同时裁剪系统UI与遮蔽输入框`() {
        val input = RedactRegion(100, 1500, 1340, 1650)
        val plan = allowed(screenshotRequest(inputs = listOf(input)))

        assertEquals("应裁掉状态栏与导航栏", 2, plan.cropOut.size)
        assertEquals("应遮蔽输入框", listOf(input), plan.mask)
    }

    @Test
    fun `消息列表区域会被遮蔽`() {
        val msg = RedactRegion(0, 300, 1440, 2800)
        val plan = allowed(screenshotRequest(messages = listOf(msg)))

        assertEquals(listOf(msg), plan.mask)
    }

    @Test
    fun `关闭输入框遮蔽后输入框不再进入计划`() {
        val input = RedactRegion(100, 1500, 1340, 1650)
        val f = filter(policy = FilterPolicy(maskInputFields = false))

        val plan = (f.review(screenshotRequest(inputs = listOf(input))) as FilterOutcome.Allowed).plan

        assertTrue("明确关闭后不应遮蔽输入框", plan.mask.isEmpty())
    }

    @Test
    fun `裁剪与遮蔽是两个独立列表`() {
        // ★ 语义必须区分：
        //   裁掉 = 这些像素**完全不上传**（状态栏）
        //   遮蔽 = 像素上传但被覆盖（输入框，保留布局信息供模型理解页面结构）
        //   若混成一个列表，Android 层就无法区分该裁还是该遮。
        val input = RedactRegion(100, 1500, 1340, 1650)
        val plan = allowed(screenshotRequest(inputs = listOf(input)))

        assertTrue("状态栏不应出现在 mask 里", plan.mask.none { it == statusBar })
        assertTrue("输入框不应出现在 cropOut 里", plan.cropOut.none { it == input })
    }

    // ══ ★★ 第五组：降质是流量手段，不是隐私手段 ══════════════════

    @Test
    fun `降质只在调用方要求且策略允许时开启`() {
        assertTrue(allowed(screenshotRequest(qualityWanted = true)).reduceQuality)
        assertFalse(allowed(screenshotRequest(qualityWanted = false)).reduceQuality)
    }

    @Test
    fun `策略禁止降质时即使调用方要求也不降`() {
        val f = filter(policy = FilterPolicy(allowQualityReduction = false))
        val plan = (f.review(screenshotRequest(qualityWanted = true)) as FilterOutcome.Allowed).plan

        assertFalse(plan.reduceQuality)
    }

    @Test
    fun `纯文本上传永不降质`() {
        // 没有像素，降质无从谈起。若这里返回 true，Android 层会去处理一个不存在的 bitmap。
        assertFalse(allowed(textRequest()).reduceQuality)
    }

    @Test
    fun `干净请求的计划是明确的无操作`() {
        // ★★ isNoOp 的语义是"检查过且干净"，**不是"没检查"**。
        //    这两者在日志和 UI 上看起来一样，但性质完全相反。
        val plan = allowed(screenshotRequest())

        assertFalse("系统栏已知时应产出裁剪指令，而非空操作", plan.isNoOp)
        assertTrue(plan.cropOut.isNotEmpty())
    }

    // ══ ★★ 第六组：绕过检测 ═════════════════════════════════════

    @Test
    fun `声称已过滤却无计划时硬失败`() {
        // ★★ 这是本组件唯一的"抛异常"路径，且是刻意的：
        //    伪造标记本身就是 bug，静默"退回重新过滤"会让这个 bug 一直存在 ——
        //    而且会让真正的绕过尝试看起来像一次正常的重复过滤。
        val forged = UploadRequest(
            context = UploadContext("p", UploadPurpose.TASK_REASONING, alreadyFiltered = true),
            requiresScreenshot = true,
            priorPlan = null, // ← 声称过滤过，却拿不出计划
        )

        try {
            filter().review(forged)
            throw AssertionError("伪造的 alreadyFiltered 标记必须被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "异常信息应指明疑似绕过，实际：${e.message}",
                e.message!!.contains("绕过 PrivacyFilter"),
            )
        }
    }

    @Test
    fun `携带计划的已过滤请求可以放行`() {
        // 反向验证：正常的"上游已过滤"流程不该被误伤 ——
        // 否则真实链路（Perception → Filter → Gateway）会被硬失败打断。
        val legit = UploadRequest(
            context = UploadContext("p", UploadPurpose.TASK_REASONING, alreadyFiltered = true),
            requiresScreenshot = true,
            priorPlan = RedactionPlan(cropOut = listOf(statusBar)),
        )

        assertTrue(filter().allows(legit))
    }

    @Test
    fun `allows 与 review 结论一致`() {
        // ★ 两个入口必须等价。若 allows 单独实现一份判断，
        //   两条路径迟早会出现分歧，而分歧的那一条会静默放行。
        val cases = listOf(
            screenshotRequest(),
            screenshotRequest(pageKind = PageKind.SENSITIVE),
            screenshotRequest(purpose = UploadPurpose.PLUGIN_ANALYSIS),
            textRequest(),
        )

        cases.forEach { req ->
            val byReview = filter().review(req) is FilterOutcome.Allowed
            assertEquals("allows 与 review 结论不一致：$req", byReview, filter().allows(req))
        }
    }

    // ══ 第七组：RedactRegion 数据完整性 ══════════════════════════

    @Test
    fun `坐标颠倒的矩形会被拒绝`() {
        try {
            RedactRegion(left = 100, top = 0, right = 50, bottom = 10)
            throw AssertionError("左右颠倒的矩形应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("左右坐标颠倒"))
        }
    }

    @Test
    fun `零面积矩形合法`() {
        // 边界：某种机型上系统栏可能量出高度 0（全屏沉浸模式）。
        // 它合法，只是裁了等于没裁 —— 不该抛异常让整个流程挂掉。
        val zero = RedactRegion(0, 0, 1440, 0)

        assertEquals(0L, zero.area)
    }

    @Test
    fun `区域面积按长宽计算`() {
        assertEquals(1440L * 100L, statusBar.area)
        assertEquals(100, statusBar.height)
    }
}
