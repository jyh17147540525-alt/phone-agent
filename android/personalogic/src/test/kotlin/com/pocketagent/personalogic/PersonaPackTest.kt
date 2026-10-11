package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人格包：越权字段拒绝、冲突检测、分级过滤、打包可复现（内容指纹）。
 */
class PersonaPackTest {

    private fun manifest(
        id: String = "p1",
        name: String = "傲娇室友",
        version: String = "1.0.0",
        kind: String = "persona-character",
        rating: String = "all-ages",
        claims: List<String> = listOf("称呼方式"),
    ) = PersonaPackManifest(
        id = id, name = name, version = version, kind = kind,
        rating = rating, claims = claims,
    )

    private fun accepted(
        id: String = "p1",
        claims: List<String> = listOf("称呼方式"),
    ) = (PersonaPackValidator.validate(manifest(id = id, claims = claims)) as PackVerdict.Accepted).pack

    private fun reasons(v: PackVerdict) = (v as PackVerdict.Rejected).reasons

    // ── ★ 越权字段拒绝 ──────────────────────────────────────────

    @Test
    fun `★ 声明安全骨骼字段的包被拒收，且原因与「没听懂」不同`() {
        // 合并成一句"不支持"会让一次**攻击尝试**与一次**口误**长得一模一样，
        // 用户也就无从判断"这个包是不是想动我的安全设置"。
        val v = PersonaPackValidator.validate(manifest(claims = listOf("refusePayment", "称呼方式")))
        val rs = reasons(v)
        assertTrue("必须报越权", rs.any { it.kind == RejectionKind.BONES_FIELD })
        assertTrue("文案要说明它属于安全底线", rs.any { it.detail.contains("安全底线") })
        assertFalse("越权不能与「没听懂」混淆", rs.any { it.kind == RejectionKind.UNKNOWN_FIELD })
    }

    @Test
    fun `中文骨骼别名同样被拦`() {
        val v = PersonaPackValidator.validate(manifest(claims = listOf("支付")))
        assertTrue(reasons(v).any { it.kind == RejectionKind.BONES_FIELD })
    }

    @Test
    fun `不认识的名字归入「没听懂」而不是越权`() {
        val v = PersonaPackValidator.validate(manifest(claims = listOf("心情", "称呼方式")))
        val rs = reasons(v)
        assertTrue(rs.any { it.kind == RejectionKind.UNKNOWN_FIELD })
        assertFalse("一个口误不该被说成攻击", rs.any { it.kind == RejectionKind.BONES_FIELD })
    }

    @Test
    fun `一个包同时越权与口误时两类原因都在`() {
        val v = PersonaPackValidator.validate(manifest(claims = listOf("refusePayment", "胡说八道")))
        val rs = reasons(v)
        assertTrue(rs.any { it.kind == RejectionKind.BONES_FIELD })
        assertTrue(rs.any { it.kind == RejectionKind.UNKNOWN_FIELD })
    }

    // ── ★ 未知键 fail-closed ───────────────────────────────────

    @Test
    fun `★ 清单里出现不认识的键时拒收（fail-closed）`() {
        // 人格包**结构上**没有 capabilities 字段 —— 但 JSON 里能塞进去。
        // 一个我们读不懂的键，很可能是将来某个版本的越权声明；静默忽略等于放行。
        val json = """
            {"id":"p1","name":"x","version":"1.0.0","kind":"persona-character",
             "rating":"all-ages","claims":["称呼方式"],"capabilities":["payment.pay"]}
        """.trimIndent()
        val v = PersonaPackValidator.validateJson(json)
        assertTrue(v is PackVerdict.Rejected)
        val rs = reasons(v)
        assertTrue(rs.any { it.kind == RejectionKind.UNKNOWN_KEY })
        assertTrue("理由要说明为什么不予安装", rs.any { it.detail.isNotBlank() })
    }

    @Test
    fun `干净的清单能通过 JSON 解析并接受`() {
        val json = """
            {"id":"p1","name":"傲娇室友","version":"1.0.0","kind":"persona-character",
             "rating":"all-ages","claims":["称呼方式","温度"]}
        """.trimIndent()
        val v = PersonaPackValidator.validateJson(json)
        assertTrue(v is PackVerdict.Accepted)
        val pack = (v as PackVerdict.Accepted).pack
        assertEquals(PersonaPackKind.CHARACTER, pack.kind)
        assertEquals(MaturityRating.ALL_AGES, pack.rating)
        assertEquals(setOf(PersonaField.ADDRESS_STYLE, PersonaField.TONE_WARMTH), pack.claims)
    }

    // ── 格式问题 ────────────────────────────────────────────────

    @Test
    fun `缺少必填项与未知类型都被拒`() {
        assertTrue(reasons(PersonaPackValidator.validate(manifest(id = " "))).any { it.kind == RejectionKind.MALFORMED })
        assertTrue(reasons(PersonaPackValidator.validate(manifest(version = ""))).any { it.kind == RejectionKind.MALFORMED })
        assertTrue(reasons(PersonaPackValidator.validate(manifest(kind = "persona-unknown"))).any { it.kind == RejectionKind.MALFORMED })
        assertTrue(reasons(PersonaPackValidator.validate(manifest(rating = "whatever"))).any { it.kind == RejectionKind.MALFORMED })
    }

    @Test
    fun `什么字段都不声明的包被拒`() {
        val v = PersonaPackValidator.validate(manifest(claims = emptyList()))
        assertTrue(reasons(v).any { it.kind == RejectionKind.EMPTY_CLAIMS })
    }

    @Test
    fun `三种包类型都能识别`() {
        for (kind in PersonaPackKind.entries) {
            val v = PersonaPackValidator.validate(manifest(kind = kind.id))
            assertTrue("$kind 应当可识别", v is PackVerdict.Accepted)
            assertEquals(kind, (v as PackVerdict.Accepted).pack.kind)
        }
    }

    // ── ★ 冲突检测：一个字段只能有一个占位者 ────────────────────

    @Test
    fun `★ 两个包争同一字段时检出冲突`() {
        val conflict = PersonaPackConflicts.detect(
            listOf(accepted("a"), accepted("b")),
        )
        assertEquals(1, conflict.size)
        assertEquals(PersonaField.ADDRESS_STYLE, conflict.single().field)
        assertEquals(listOf("a", "b"), conflict.single().holders)
    }

    @Test
    fun `字段不重叠时没有冲突`() {
        val conflicts = PersonaPackConflicts.detect(
            listOf(accepted("a", listOf("称呼方式")), accepted("b", listOf("口头禅"))),
        )
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `★ 未裁决时不给出生效集合，而是如实说还欠哪些裁决`() {
        val packs = listOf(accepted("a"), accepted("b"))
        val r = PersonaPackConflicts.resolve(packs, decisions = emptyMap())
        assertTrue("不猜：没裁决就不给生效集合", r.effective.isEmpty())
        assertEquals(listOf(PersonaField.ADDRESS_STYLE), r.pendingDecisions)
    }

    @Test
    fun `裁决后输掉的包整体禁用`() {
        val packs = listOf(accepted("a"), accepted("b"))
        val r = PersonaPackConflicts.resolve(packs, mapOf(PersonaField.ADDRESS_STYLE to "b"))
        assertEquals(listOf("b"), r.effective.map { it.manifest.id })
        assertTrue(r.pendingDecisions.isEmpty())
    }

    @Test
    fun `没有冲突时全部生效且不需要裁决`() {
        val packs = listOf(accepted("a", listOf("称呼方式")), accepted("b", listOf("口头禅")))
        val r = PersonaPackConflicts.resolve(packs, emptyMap())
        assertEquals(2, r.effective.size)
        assertTrue(r.pendingDecisions.isEmpty())
    }

    @Test
    fun `重复包 id 被检出`() {
        assertTrue(PersonaPackConflicts.duplicateIds(listOf(accepted("same"), accepted("same"))).isNotEmpty())
        assertTrue(PersonaPackConflicts.duplicateIds(listOf(accepted("a"), accepted("b"))).isEmpty())
    }

    // ── ★ 分级过滤必须是可见的 ─────────────────────────────────

    @Test
    fun `★ 过滤成熟内容时明确告知隐藏了几个`() {
        // 静默过滤会让用户以为"社区里就这些" —— 那是欺骗。
        val packs = listOf(
            accepted("a").copy(rating = MaturityRating.ALL_AGES),
            accepted("b").copy(rating = MaturityRating.MATURE),
        )
        val r = RatingFilterResult.apply(packs, showMature = false)
        assertEquals(1, r.visible.size)
        assertEquals(1, r.hidden.size)
        assertTrue("必须能说出隐藏了几个", r.notice()!!.contains("1 个"))
    }

    @Test
    fun `没有隐藏项时不产生噪音提示`() {
        val r = RatingFilterResult.apply(listOf(accepted("a")), showMature = false)
        assertNull("不该显示「隐藏了 0 个」", r.notice())
    }

    @Test
    fun `允许成熟内容时全都可见`() {
        val packs = listOf(accepted("a"), accepted("b").copy(rating = MaturityRating.MATURE))
        val r = RatingFilterResult.apply(packs, showMature = true)
        assertEquals(2, r.visible.size)
        assertTrue(r.hidden.isEmpty())
    }

    // ── ★ 打包可复现：内容指纹 ─────────────────────────────────

    @Test
    fun `★ 同样的清单得到同一个指纹`() {
        assertEquals(
            PersonaPackCanonical.fingerprint(manifest()),
            PersonaPackCanonical.fingerprint(manifest()),
        )
    }

    @Test
    fun `★ 声明的顺序不影响指纹`() {
        // 声明顺序不是包的身份的一部分。若它影响指纹，
        // 同一个包换个顺序就会被当成"另一个包"，订阅源比对与增量更新全部失效。
        val a = PersonaPackCanonical.fingerprint(manifest(claims = listOf("称呼方式", "口头禅")))
        val b = PersonaPackCanonical.fingerprint(manifest(claims = listOf("口头禅", "称呼方式")))
        assertEquals(a, b)
    }

    @Test
    fun `★ 空串与未填是两个不同的指纹`() {
        // 用空串占位会让"作者是空串"与"没有作者"撞成同一个指纹。
        val empty = PersonaPackCanonical.fingerprint(manifest().copy(author = ""))
        val absent = PersonaPackCanonical.fingerprint(manifest().copy(author = null))
        assertNotEquals(empty, absent)
    }

    @Test
    fun `内容不同则指纹不同`() {
        assertNotEquals(
            PersonaPackCanonical.fingerprint(manifest(version = "1.0.0")),
            PersonaPackCanonical.fingerprint(manifest(version = "1.0.1")),
        )
    }

    @Test
    fun `指纹是定长十六进制`() {
        val fp = PersonaPackCanonical.fingerprint(manifest())
        assertEquals(16, fp.length)
        assertTrue(fp.all { it in "0123456789abcdef" })
    }

    @Test
    fun `规范化文本顺序稳定`() {
        assertEquals(
            PersonaPackCanonical.canonicalize(manifest()),
            PersonaPackCanonical.canonicalize(manifest()),
        )
        // 首行固定从 id 开始 —— 顺序是手写的，不靠 Map 迭代
        assertTrue(PersonaPackCanonical.canonicalize(manifest()).startsWith("id=p1"))
    }

    // ── 展示名 ──────────────────────────────────────────────────

    @Test
    fun `类型与分级都有可展示的名字`() {
        for (k in PersonaPackKind.entries) assertTrue(k.label.isNotBlank())
        for (r in MaturityRating.entries) assertTrue(r.label.isNotBlank())
        for (r in RejectionKind.entries) assertTrue(r.label.isNotBlank())
    }
}
