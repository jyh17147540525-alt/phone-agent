package com.pocketagent.plugin.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地插件导入流程的测试。
 *
 * 覆盖的是**顺序**：宽松解析 → 能力检查 → 严格反序列化 → 结构校验。
 * 这个顺序本身就是安全设计，所以每一条边界都要钉住。
 */
class PluginImporterTest {

    /**
     * 复用的 Json 实例。
     *
     * 每次 `Json { }` 都要重新构建一遍模块描述符，在测试里循环创建会被编译器
     * 直接点名（redundant creation of Json format）—— 生产代码里更是如此。
     */
    private val lenientJson = Json { ignoreUnknownKeys = true }
    private val encodingJson = Json { encodeDefaults = true }

    // ═══════════════════════════════════════════════════════════
    //  夹具
    // ═══════════════════════════════════════════════════════════

    private fun manifestJson(
        id: String = "community.example.demo",
        name: String = "示例插件",
        version: String = "1.0.0",
        level: String = "L1_RULES",
        capabilitiesJson: String = """["screen.read"]""",
        entry: String = "rules/main.json",
        allowedHostsJson: String = "[]",
    ) = """
{
  "id": "$id",
  "name": "$name",
  "version": "$version",
  "level": "$level",
  "capabilities": $capabilitiesJson,
  "entry": "$entry",
  "allowedHosts": $allowedHostsJson,
  "description": "测试用插件"
}
"""

    private fun ready(json: String): PluginImporter.Outcome.Ready {
        val outcome = PluginImporter.inspect(json)
        assertTrue("预期可以导入，实际是 $outcome", outcome is PluginImporter.Outcome.Ready)
        return outcome as PluginImporter.Outcome.Ready
    }

    // ═══════════════════════════════════════════════════════════
    //  一、正常路径
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `合法清单可以导入`() {
        val result = ready(manifestJson())

        assertEquals("community.example.demo", result.manifest.id)
        assertEquals(listOf(PluginCapability.SCREEN_READ), result.manifest.capabilities)
    }

    @Test
    fun `导入结果自带风险告知`() {
        val result = ready(manifestJson())

        assertTrue("标题不能为空", result.notice.title.isNotBlank())
        assertTrue("摘要要提到插件名", result.notice.summary.contains("示例插件"))
        assertTrue("风险条目不能为空", result.notice.bullets.isNotEmpty())
    }

    @Test
    fun `风险提示逐条列出插件声明的能力`() {
        val result = ready(
            manifestJson(capabilitiesJson = """["screen.read","action.click"]""")
        )

        assertEquals(
            "应按风险降序排列",
            listOf(PluginCapability.SCREEN_READ, PluginCapability.ACTION_CLICK),
            result.notice.declaredCapabilities,
        )
        assertTrue(
            "每条能力都要有给用户看的大白话说明：${result.notice.bullets}",
            result.notice.bullets.any { it.contains("读取当前屏幕") },
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  二、拒绝路径 —— 明确的越权尝试
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `申请禁止能力的清单被拒绝`() {
        val outcome = PluginImporter.inspect(
            manifestJson(capabilitiesJson = """["screen.read","payment.pay"]""")
        )

        assertTrue("应被拒绝，实际是 $outcome", outcome is PluginImporter.Outcome.Rejected)
        val rejected = outcome as PluginImporter.Outcome.Rejected
        assertTrue(
            "原因必须说清是禁止能力，而不是含糊的格式错误：${rejected.errors}",
            rejected.errors.any { it.message.contains("永不开放") },
        )
    }

    @Test
    fun `大写与空白的支付能力同样被拦下`() {
        val outcome = PluginImporter.inspect(
            manifestJson(capabilitiesJson = """["  SCREEN.READ  ","Payment.Pay"]""")
        )

        assertTrue("大小写不能成为绕过手段，实际是 $outcome", outcome is PluginImporter.Outcome.Rejected)
    }

    @Test
    fun `入口路径穿越的清单被拒绝`() {
        val outcome = PluginImporter.inspect(manifestJson(entry = "../../../data/data/x.json"))

        assertTrue("应被拒绝，实际是 $outcome", outcome is PluginImporter.Outcome.Rejected)
    }

    // ═══════════════════════════════════════════════════════════
    //  三、兼容路径 —— 未知能力不该毁掉整个插件
    // ═══════════════════════════════════════════════════════════

    /**
     * ★ 核心回归用例。
     *
     * 曾经把「未知能力」判成 ERROR，于是两条路都走歪：市场把整个条目丢掉，
     * 本地导入直接抛解析异常。而真正的原因只是**本体太旧**。
     */
    @Test
    fun `未知能力不阻断导入但会提示`() {
        val result = ready(
            manifestJson(capabilitiesJson = """["screen.read","future.telepathy"]""")
        )

        assertEquals("未知能力应被丢弃", listOf(PluginCapability.SCREEN_READ), result.manifest.capabilities)
        assertTrue(
            "必须提示存在未知能力，不能悄悄丢掉：${result.warnings}",
            result.warnings.any {
                it.field == "capabilities" && it.severity == IssueSeverity.WARNING
            },
        )
    }

    @Test
    fun `未知能力在反序列化时被丢弃而不是抛异常`() {
        // 直接测宽容序列化器：这是"两条路径行为一致"的地基
        val manifest = lenientJson
            .decodeFromString<PluginManifest>(
                manifestJson(capabilitiesJson = """["screen.read","future.telepathy"]""")
            )

        assertEquals(listOf(PluginCapability.SCREEN_READ), manifest.capabilities)
    }

    @Test
    fun `清单序列化后能力仍是可读的 id 字符串`() {
        val manifest = lenientJson
            .decodeFromString<PluginManifest>(manifestJson())

        val text = encodingJson.encodeToString(PluginManifest.serializer(), manifest)

        assertTrue(
            "清单是要给作者手写和排错的，能力字段不能变成枚举序号：$text",
            text.contains("\"screen.read\""),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  四、畸形输入 —— 与「越权」必须分开报
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `不是 JSON 时报格式错误而不是拒绝`() {
        val outcome = PluginImporter.inspect("这不是 JSON，是我随便打的字")

        assertTrue("应是 Malformed，实际是 $outcome", outcome is PluginImporter.Outcome.Malformed)
    }

    @Test
    fun `缺少必填字段时报格式错误`() {
        val outcome = PluginImporter.inspect("""{"id":"community.a.b","name":"残缺"}""")

        assertTrue("应是 Malformed，实际是 $outcome", outcome is PluginImporter.Outcome.Malformed)
    }

    @Test
    fun `过大的清单被拒`() {
        val outcome = PluginImporter.inspect("x".repeat(PluginImporter.MAX_MANIFEST_CHARS + 1))

        assertTrue("应是 Malformed，实际是 $outcome", outcome is PluginImporter.Outcome.Malformed)
        assertTrue(
            "要说明是体积问题：${(outcome as PluginImporter.Outcome.Malformed).message}",
            outcome.message.contains("过大"),
        )
    }

    /**
     * 绕过尝试：把能力写成对象 `{"id":"payment.pay"}`。
     *
     * 宽松解析只收字符串，所以这一步抓不到它；但严格反序列化也收不下对象。
     * 结论必须是**导入失败**，而不是"没看到禁止能力所以放行"。
     * 跳过不等于放行 —— 这条测试就是钉住这句话。
     */
    @Test
    fun `对象形式的能力不能绕过检查`() {
        val outcome = PluginImporter.inspect(
            manifestJson(capabilitiesJson = """[{"id":"payment.pay"}]""")
        )

        assertFalse("绝不能放行，实际是 $outcome", outcome is PluginImporter.Outcome.Ready)
    }

    // ═══════════════════════════════════════════════════════════
    //  五、风险提示的强度分级
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `极高风险插件要求手动输入确认词`() {
        val result = ready(manifestJson(capabilitiesJson = """["screen.capture"]"""))

        assertTrue(
            "截屏属于极高风险，必须让用户读一遍再确认，而不是点一下同意",
            result.notice.requireTypedConfirmation,
        )
        assertEquals(PluginCapability.RiskLevel.CRITICAL, result.notice.highestRisk)
    }

    @Test
    fun `低风险插件不要求输入确认词`() {
        val result = ready(manifestJson(capabilitiesJson = """["app.launch"]"""))

        assertFalse("只是打开应用，不该给用户制造无意义的摩擦", result.notice.requireTypedConfirmation)
    }

    @Test
    fun `网络能力在风险提示里点名具体域名`() {
        val result = ready(
            manifestJson(
                capabilitiesJson = """["network.request"]""",
                allowedHostsJson = """["api.example.com"]""",
            )
        )

        assertTrue(
            "只说「可能联网」是废话，要写清发给谁：${result.notice.bullets}",
            result.notice.bullets.any { it.contains("api.example.com") },
        )
    }
}
