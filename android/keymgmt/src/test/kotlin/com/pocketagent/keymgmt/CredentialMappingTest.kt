package com.pocketagent.keymgmt

import com.pocketagent.core.database.entity.CredentialCheckStatus
import com.pocketagent.core.database.entity.CredentialEntity
import com.pocketagent.provider.api.KeyValidationResult
import com.pocketagent.provider.api.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭据映射与输入校验的单元测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这一批用例守的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * 这里没有一行代码会"崩"。它们的错误形态全是**安静地做错事**：
 *
 *  · 把"网络不通"说成"Key 无效" → 用户去删一个完好的 Key，然后永远修不好
 *  · 把 `http://127.0.0.1.evil.com` 判成本机地址 → 用户的 Key 明文发到外网
 *  · 把从未校验的状态渲染成"可用" → 用户以为配好了，实际发不出请求
 *
 * 这三类都不会抛异常、不会让编译失败，只会在真实使用中造成伤害。
 * 所以必须由测试钉死。
 */
class CredentialMappingTest {

    private fun entity(
        lastStatus: CredentialCheckStatus? = null,
        label: String = "",
        providerId: String = "deepseek",
    ) = CredentialEntity(
        id = "id-1",
        providerId = providerId,
        label = label,
        ciphertext = byteArrayOf(1, 2, 3),
        iv = byteArrayOf(4, 5, 6),
        keyLength = 32,
        baseUrlOverride = null,
        createdAtMillis = 1_000L,
        lastCheckedAtMillis = null,
        lastStatus = lastStatus,
        lastStatusDetail = null,
        modelCount = null,
        isDefault = false,
    )

    // ═══════════════════════════════════════════════════════════
    //  一、Key 输入的格式自检
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `空 Key 被拒绝`() {
        assertNotNull(CredentialInput.validateKey(""))
        assertNotNull(CredentialInput.validateKey("   "))
    }

    @Test
    fun `太短的 Key 被拒绝，且信息里带出实际长度`() {
        val tooShort = "sk-123" // 6 个字符
        val reason = CredentialInput.validateKey(tooShort)

        assertNotNull(reason)
        // 断言里用长度本身，不写死数字：写死的话改了输入忘改断言，
        // 测试会以"文案不对"的样子失败，而真问题是测试与输入脱节了
        assertTrue(
            "应当告诉用户实际长度：$reason",
            reason!!.contains("只有 ${tooShort.length} 个字符"),
        )
    }

    @Test
    fun `正好达到长度下限的 Key 通过`() {
        assertNull(CredentialInput.validateKey("12345678"))
    }

    @Test
    fun `内部含空白的 Key 被拒绝`() {
        // 典型事故：从网页复制时带进了换行，或者连着复制了两行
        assertNotNull(CredentialInput.validateKey("sk-abc def123456"))
        assertNotNull(CredentialInput.validateKey("sk-abc\ndef123456"))
    }

    @Test
    fun `首尾空白会被容忍，不算错误`() {
        // 粘贴时多带一个空格太常见了，为这个报错只会让用户困惑
        assertNull(CredentialInput.validateKey("  sk-abcdef123456  "))
    }

    @Test
    fun `正常的 Key 通过`() {
        assertNull(CredentialInput.validateKey("sk-proj-abcdefghijklmnopqrstuvwxyz0123456789"))
    }

    // ═══════════════════════════════════════════════════════════
    //  二、自定义地址 —— 含一条安全用例
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `留空的地址表示用默认值，不算错误`() {
        assertNull(CredentialInput.validateBaseUrl(null))
        assertNull(CredentialInput.validateBaseUrl(""))
        assertNull(CredentialInput.validateBaseUrl("   "))
    }

    @Test
    fun `https 地址通过`() {
        assertNull(CredentialInput.validateBaseUrl("https://api.example.com/v1"))
    }

    @Test
    fun `远程的 http 地址被拒绝`() {
        // 明文 http 会让 Key 在网络上传送时被看到
        assertNotNull(CredentialInput.validateBaseUrl("http://api.example.com/v1"))
    }

    @Test
    fun `本机 http 地址通过`() {
        // 本地模型就跑在 http://127.0.0.1 上，强制 https 会让整个
        // 本地推理场景不可用
        assertNull(CredentialInput.validateBaseUrl("http://127.0.0.1:11434/v1"))
        assertNull(CredentialInput.validateBaseUrl("http://localhost:1234/v1"))
        assertNull(CredentialInput.validateBaseUrl("http://[::1]:8080/v1"))
    }

    @Test
    fun `冒充本机地址的外网域名必须被拒绝`() {
        // ⚠️ 这条是本文件最重要的一条用例。
        //
        //    一个"聪明"的实现会用 `contains("127.0.0.1")` 判断本机，
        //    于是 `http://127.0.0.1.evil.com` 会被放行 ——
        //    而那个域名的解析权完全在攻击者手里。
        //    用户的 Key 就会被明文发到攻击者的服务器上。
        //
        //    只认 127.0.0.1 / localhost / [::1] 三种**完整**写法，
        //    宁可漏判（用户填了别的本机写法被拒，改一下即可），
        //    也不能误判。
        assertNotNull(CredentialInput.validateBaseUrl("http://127.0.0.1.evil.com/v1"))
        assertNotNull(CredentialInput.validateBaseUrl("http://localhost.evil.com/v1"))
        assertNotNull(CredentialInput.validateBaseUrl("http://notlocalhost/v1"))
        assertNotNull(CredentialInput.validateBaseUrl("http://127.0.0.1.attacker.net:8080/v1"))
    }

    @Test
    fun `不以 http 开头的地址被拒绝`() {
        assertNotNull(CredentialInput.validateBaseUrl("ftp://example.com"))
        assertNotNull(CredentialInput.validateBaseUrl("api.example.com/v1"))
        assertNotNull(CredentialInput.validateBaseUrl("//example.com"))
    }

    // ═══════════════════════════════════════════════════════════
    //  三、校验结论映射
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `有效 Key 映射为 VALID 并带出模型数`() {
        val outcome = KeyValidationResult.Valid(modelCount = 42, latencyMs = 120).toOutcome()

        assertEquals(CredentialCheckStatus.VALID, outcome.status)
        assertEquals(42, outcome.modelCount)
        assertTrue(outcome.detail!!.contains("42"))
    }

    @Test
    fun `鉴权失败映射为 INVALID`() {
        assertEquals(
            CredentialCheckStatus.INVALID,
            KeyValidationResult.Invalid.toOutcome().status,
        )
    }

    @Test
    fun `余额不足映射为 NO_BALANCE 且文案劝用户不要换 Key`() {
        val outcome = KeyValidationResult.NoBalance(null).toOutcome()

        assertEquals(CredentialCheckStatus.NO_BALANCE, outcome.status)
        // Key 本身是好的，只是没钱了。文案必须说清楚，
        // 否则用户的第一反应是删掉这个 Key 换一个 —— 白费功夫
        assertTrue("文案应说明不用换 Key：${outcome.detail}", outcome.detail!!.contains("不用换"))
    }

    @Test
    fun `余额不足时优先使用服务商给的说明`() {
        val outcome = KeyValidationResult.NoBalance("Your credit balance is too low").toOutcome()

        assertEquals("Your credit balance is too low", outcome.detail)
    }

    @Test
    fun `限流映射为 RATE_LIMITED，带重试秒数时文案里体现`() {
        val withSeconds = KeyValidationResult.RateLimited(30).toOutcome()
        assertEquals(CredentialCheckStatus.RATE_LIMITED, withSeconds.status)
        assertTrue(withSeconds.detail!!.contains("30"))

        val withoutSeconds = KeyValidationResult.RateLimited(null).toOutcome()
        assertEquals(CredentialCheckStatus.RATE_LIMITED, withoutSeconds.status)
    }

    @Test
    fun `不可达映射为 UNREACHABLE 且明确说明这不代表 Key 有问题`() {
        val outcome = KeyValidationResult.Unreachable("DNS 解析失败").toOutcome()

        assertEquals(CredentialCheckStatus.UNREACHABLE, outcome.status)
        // ⚠️ 这是整个映射里最要紧的一句话。
        //    用户看到"校验失败"就会去删 Key，而真实原因只是网络不通。
        assertTrue("文案必须澄清与 Key 无关：${outcome.detail}", outcome.detail!!.contains("不代表 Key 有问题"))
        assertTrue("应当带出具体原因：${outcome.detail}", outcome.detail!!.contains("DNS 解析失败"))
    }

    @Test
    fun `UNREACHABLE 与 INVALID 必须是两个不同的状态`() {
        // 把这两种失败合并成一个，就制造了一个用户无法自救的困境
        val unreachable = KeyValidationResult.Unreachable("网络错误").toOutcome().status
        val invalid = KeyValidationResult.Invalid.toOutcome().status

        assertFalse(unreachable == invalid)
    }

    // ═══════════════════════════════════════════════════════════
    //  四、异常兜底映射
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `只有 AuthFailed 能证明 Key 是坏的`() {
        assertEquals(
            CredentialCheckStatus.INVALID,
            ProviderException.AuthFailed("401").toOutcome().status,
        )
    }

    @Test
    fun `网络类异常一律归为无法判定，而不是 Key 无效`() {
        // ⚠️ Timeout / NoBalance 是**无参构造的 class**，不是 object，
        //    所以必须写 ()。写成 `ProviderException.Timeout.toOutcome()`
        //    会被当成对类名的引用，编译期报 Unresolved reference 'toOutcome'。
        //    这里刻意不改成 object：object 型异常是单例，栈帧在类初始化那一刻
        //    就被固定，真正抛出的位置会从堆栈里消失 —— 排查时比少写一对括号更贵。
        val statuses = listOf(
            ProviderException.NetworkError(RuntimeException("timeout")).toOutcome().status,
            ProviderException.Timeout().toOutcome().status,
            ProviderException.ProtocolError("unexpected json").toOutcome().status,
            ProviderException.ContentFiltered("blocked").toOutcome().status,
        )

        statuses.forEach { status ->
            assertEquals(
                "网络/协议类错误不能让用户以为 Key 坏了",
                CredentialCheckStatus.UNREACHABLE,
                status,
            )
        }
    }

    @Test
    fun `余额与限流异常各自映射到对应状态`() {
        assertEquals(
            CredentialCheckStatus.NO_BALANCE,
            ProviderException.NoBalance().toOutcome().status,
        )
        assertEquals(
            CredentialCheckStatus.RATE_LIMITED,
            ProviderException.RateLimited(15).toOutcome().status,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  五、实体到领域模型
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `从未校验过的实体映射为 UNCHECKED 而不是 null`() {
        // 数据库里 null 表示"从未校验"。领域模型里必须显式化成枚举，
        // 否则每个使用方都要处理一次 null，而漏掉的那一处
        // 会把"从未校验"渲染成"可用"
        val domain = entity(lastStatus = null).toDomain("DeepSeek")

        assertEquals(CredentialCheckStatus.UNCHECKED, domain.status)
    }

    @Test
    fun `没起名字时用服务商名兜底`() {
        val domain = entity(label = "").toDomain("DeepSeek")

        assertEquals("DeepSeek", domain.effectiveLabel)
    }

    @Test
    fun `起了名字时优先用用户的名字`() {
        val domain = entity(label = "主力 Key").toDomain("DeepSeek")

        assertEquals("主力 Key", domain.effectiveLabel)
    }

    @Test
    fun `INVALID 与 NO_BALANCE 需要提醒用户处理`() {
        assertTrue(entity(lastStatus = CredentialCheckStatus.INVALID).toDomain("X").needsAttention)
        assertTrue(entity(lastStatus = CredentialCheckStatus.NO_BALANCE).toDomain("X").needsAttention)
    }

    @Test
    fun `限流与不可达不需要提醒处理，因为等一等就好了`() {
        assertFalse(entity(lastStatus = CredentialCheckStatus.RATE_LIMITED).toDomain("X").needsAttention)
        assertFalse(entity(lastStatus = CredentialCheckStatus.UNREACHABLE).toDomain("X").needsAttention)
    }

    @Test
    fun `从未校验过的凭据视为可用，不能拦住用户`() {
        // 用户刚粘贴完 Key 就点了"保存"，此时还没校验。
        // 如果 usable 为 false，应用会拒绝使用一个可能完全正常的 Key ——
        // 而"先存下来，校验结果单独反馈"正是本项目的设计选择
        assertTrue(entity(lastStatus = null).toDomain("X").usable)
        assertTrue(entity(lastStatus = CredentialCheckStatus.VALID).toDomain("X").usable)
    }

    @Test
    fun `确认无效的凭据不可用`() {
        assertFalse(entity(lastStatus = CredentialCheckStatus.INVALID).toDomain("X").usable)
    }

    @Test
    fun `实体转字符串不泄漏密文内容`() {
        // data class 默认的 toString 会打印 ByteArray 的内容摘要。
        // 密文本身不算秘密，但它一旦进了日志，就多了一处不该有的痕迹
        val text = entity().toString()

        assertTrue("应当包含 id 便于排查：$text", text.contains("id-1"))
        assertFalse("不应打印密文内容：$text", text.contains("[1, 2, 3]"))
    }
}
