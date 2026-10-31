package com.pocketagent.mcp

import com.pocketagent.agentlogic.PrivacyFilter
import com.pocketagent.agentlogic.RedactRegion
import com.pocketagent.agentlogic.RedactionPlan
import com.pocketagent.agentlogic.SystemUiRegions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `screen_shot` 的**隐私计划必须真的被执行**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个测试存在的理由：两个"静默失效"
 * ═══════════════════════════════════════════════════════════════
 *
 * 2026-10-31 上线时有两个 bug，**都不会报错、都不会让测试变红**：
 *
 * 1. **`systemUi = unknown` ⇒ 100% 拒绝**
 *    `unknown` 的语义是"不知道系统栏在哪"，而 `PrivacyFilter`
 *    对未知的处置是丢弃整张图。于是这个工具从接上那天起**一张图都发不出去**，
 *    每次调用都返回"无法确定系统 UI 区域"——文案很客气，功能是零。
 *
 * 2. **`Allowed` 之后把 `plan` 丢了 ⇒ 通过了但不裁**
 *    `ScreenReadTool` 把 `plan.mask` 传给了序列化器；
 *    而 `ScreenShotTool` 拿到 `Allowed` 就 `Unit`，直接采集像素。
 *    ⇒ 状态栏（通知标题）、输入框（密码/验证码）**原样进图**。
 *
 * ⚠️ 第 2 条比第 1 条危险得多：日志会显示"已通过隐私过滤"，
 *    而实际上什么都没遮 —— 一个看起来生效、实际没有的关卡。
 */
class ScreenShotToolRedactionTest {

    /** 一台 1080×2400 的竖屏手机：状态栏 80px、导航栏 80px。 */
    private val knownSystemUi = SystemUiRegions(
        statusBar = RedactRegion(0, 0, 1080, 80),
        navigationBar = RedactRegion(0, 2320, 1080, 2400),
    )

    // ── ① 计划必须传下去 ─────────────────────────────────────

    @Test
    fun `通过隐私关卡时，裁剪计划必须传给采集器`() = runBlocking {
        var received: RedactionPlan? = null
        val tool = toolWith(
            systemUi = knownSystemUi,
            capturer = ScreenShotPort { plan ->
                received = plan
                ScreenShotOutcome.Ok(ByteArray(1024))
            },
        )

        tool.call(buildJsonObject {})

        assertNotNull("采集器必须收到计划 —— 收不到就意味着没人裁也没人遮", received)
        val plan = received!!
        assertEquals("状态栏与导航栏都该在裁剪列表里", 2, plan.cropOut.size)
        assertTrue(
            "顶部状态栏必须被裁掉",
            plan.cropOut.any { it.top == 0 && it.bottom == 80 },
        )
        assertTrue(
            "底部导航栏必须被裁掉",
            plan.cropOut.any { it.bottom == 2400 && it.top == 2320 },
        )
    }

    @Test
    fun `输入框遮蔽也必须传下去`() = runBlocking {
        var received: RedactionPlan? = null
        // 页面里有一个密码输入框
        val dto = captureDto(
            nodes = listOf(
                UiNodeDto(
                    nodeId = "n1",
                    className = "android.widget.EditText",
                    text = "123456",
                    contentDescription = null,
                    hintText = "请输入密码",
                    bounds = ScreenRect(100, 800, 900, 900),
                    clickable = true,
                    longClickable = false,
                    editable = true,
                    scrollable = false,
                    checkable = false,
                    checked = false,
                    enabled = true,
                    focused = true,
                    visible = true,
                    selected = false,
                    viewIdResourceName = null,
                    depth = 3,
                    password = true,
                ),
            ),
        )
        val tool = toolWith(
            systemUi = knownSystemUi,
            dto = dto,
            capturer = ScreenShotPort { plan ->
                received = plan
                ScreenShotOutcome.Ok(ByteArray(1024))
            },
        )

        tool.call(buildJsonObject {})

        assertNotNull(received)
        val plan = received!!
        assertTrue(
            "密码框必须进 mask —— 否则明文验证码会原样进图",
            plan.mask.isNotEmpty(),
        )
    }

    // ── ② 系统栏未知必须拒绝，且**不能先采集** ────────────────

    @Test
    fun `系统栏未知时必须拒绝，且不得采集像素`() = runBlocking {
        var captured = false
        val tool = toolWith(
            systemUi = SystemUiRegions.unknown,
            capturer = ScreenShotPort {
                captured = true
                ScreenShotOutcome.Ok(ByteArray(1024))
            },
        )

        val outcome = tool.call(buildJsonObject {})

        assertTrue("未知系统栏必须拒绝", outcome is ToolOutcome.Refused)
        assertTrue(
            "拒绝时**不能**采集 —— 采了像素就已经进内存，'反正不发'不是理由",
            !captured,
        )
    }

    // ── ③ 敏感页必须在读树之后、采集之前就停 ──────────────────

    @Test
    fun `敏感页命中时不得采集像素`() = runBlocking {
        var captured = false
        val tool = ScreenShotTool(
            reader = object : ScreenReaderPort {
                override suspend fun capture() = ScreenCaptureOutcome.Ok(captureDto())
            },
            safety = object : ScreenSafetyPort {
                override fun review(query: ScreenSafetyQuery) =
                    ScreenSafetyOutcome.Block("这是支付页面")
            },
            capturer = ScreenShotPort {
                captured = true
                ScreenShotOutcome.Ok(ByteArray(1024))
            },
            privacyFilter = { PrivacyFilter(systemUi = knownSystemUi) },
        )

        val outcome = tool.call(buildJsonObject {})

        assertTrue("敏感页必须被拒", outcome is ToolOutcome.Refused)
        assertTrue("敏感页必须在采集之前停下", !captured)
    }

    // ── 夹具 ────────────────────────────────────────────────

    private fun toolWith(
        systemUi: SystemUiRegions,
        dto: ScreenCaptureDto = captureDto(),
        capturer: ScreenShotPort,
    ) = ScreenShotTool(
        reader = object : ScreenReaderPort {
            override suspend fun capture() = ScreenCaptureOutcome.Ok(dto)
        },
        safety = object : ScreenSafetyPort {
            override fun review(query: ScreenSafetyQuery) = ScreenSafetyOutcome.Pass
        },
        capturer = capturer,
        privacyFilter = { PrivacyFilter(systemUi = systemUi) },
    )

    private fun captureDto(nodes: List<UiNodeDto> = emptyList()) = ScreenCaptureDto(
        packageName = "com.android.settings",
        activityName = "com.android.settings.MainSettings",
        width = 1080,
        height = 2400,
        source = CaptureSource.ACCESSIBILITY,
        confidence = 1f,
        capturedAtMs = 0L,
        nodes = nodes,
    )
}
