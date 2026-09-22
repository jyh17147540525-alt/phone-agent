package com.pocketagent.keymgmt

import com.pocketagent.core.crypto.EncryptedBlob
import com.pocketagent.core.database.dao.CredentialDao
import com.pocketagent.core.database.dao.UsageDao
import com.pocketagent.core.database.entity.CredentialCheckStatus
import com.pocketagent.core.database.entity.CredentialEntity
import com.pocketagent.core.database.entity.CredentialPurpose
import com.pocketagent.core.database.entity.UsageRecordEntity
import com.pocketagent.provider.api.TokenUsage
import com.pocketagent.provider.gateway.Consumer
import com.pocketagent.provider.gateway.CredentialDecryptException
import com.pocketagent.provider.gateway.UsageRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关两个实现的接线测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这里守的是"接错了但编译得过"的那一类错误
 * ═══════════════════════════════════════════════════════════════
 *
 * `GatewayCredentialSource` 与 `RoomUsageRecorder` 的代码本身很短，
 * 短到容易觉得"没什么好测的"。但它们处在两条**说不出话的失败路径**上：
 *
 * - 凭据解密接错（比如误用 `withDecryptedKey`）→ 交出一个已被清零的数组
 *   → 上游 401 → 用户去查"Key 是不是过期了"，而 Key 明明好着
 * - 记账接了非空列 → 上游没给 usage 时 INSERT 失败 → 统计页一片空白，
 *   而那个异常会被网关的 `onCompletion` 吞掉，**没有任何提示**
 *
 * 两者都不抛异常到用户面前，也不影响"答案能不能流出来"。所以只能靠测试。
 */
class GatewayWiringTest {

    // ─────────────────────────────────────────────────────────────
    //  假件
    // ─────────────────────────────────────────────────────────────

    /**
     * 只实现 `decrypt` 的假解密器。
     *
     * ⚠️ 这里能用 `CredentialDecryptor` 而不是 mockk 掉 `CryptoManager`，
     *    是因为生产代码依赖的是那个窄接口 —— 见 `CredentialDecryptor` 的注释。
     *    需要保留的断言有两条：**解密被调了几次**、**收到的 blob 是什么**。
     */
    private class FakeDecryptor(
        private val plain: ByteArray = "sk-secret-value".toByteArray(),
        private val throwOnDecrypt: Throwable? = null,
    ) : CredentialDecryptor {
        var decryptCount = 0
            private set
        var lastBlob: EncryptedBlob? = null
            private set

        override fun decrypt(blob: EncryptedBlob): ByteArray {
            decryptCount++
            lastBlob = blob
            throwOnDecrypt?.let { throw it }
            return plain
        }
    }

    /** 只实现 `byId` 的假 Dao。其余方法都不可达 —— 用了就说明接线接错了 */
    private class FakeCredentialDao(private val entity: CredentialEntity?) : CredentialDao {
        override suspend fun byId(id: String): CredentialEntity? = entity

        override fun observeAll() = throw UnsupportedOperationException("本测试不该订阅列表")
        override suspend fun all() = throw UnsupportedOperationException()
        override suspend fun defaultCredential() = throw UnsupportedOperationException()
        override suspend fun defaultByPurpose(purpose: String) = throw UnsupportedOperationException()
        override fun observeByPurpose(purpose: String) = throw UnsupportedOperationException()
        override suspend fun countByPurpose(purpose: String) = throw UnsupportedOperationException()
        override suspend fun count() = throw UnsupportedOperationException()
        override suspend fun upsert(entity: CredentialEntity) = throw UnsupportedOperationException()
        override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
        override suspend fun deleteAll() = throw UnsupportedOperationException()
        override suspend fun updateCheckResult(
            id: String,
            checkedAtMillis: Long,
            status: CredentialCheckStatus,
            detail: String?,
            modelCount: Int?,
        ) = throw UnsupportedOperationException()

        override suspend fun clearDefaultFlag(purpose: String) = throw UnsupportedOperationException()
        override suspend fun markDefault(id: String) = throw UnsupportedOperationException()
        override suspend fun rename(id: String, label: String) = throw UnsupportedOperationException()
    }

    /** 只记录 insert 的假 Dao */
    private class FakeUsageDao : UsageDao {
        val inserted = mutableListOf<UsageRecordEntity>()
        var throwOnInsert: Throwable? = null

        override suspend fun insert(record: UsageRecordEntity) {
            throwOnInsert?.let { throw it }
            inserted += record
        }

        override fun observeRecent(limit: Int) = throw UnsupportedOperationException()
        override suspend fun totalTokensForModel(modelConfigId: String) = 0
        override suspend fun failureCountFor(modelConfigId: String) = 0
        override suspend fun countSince(sinceMillis: Long) = 0
        override suspend fun count() = 0
        override suspend fun deleteAll() = Unit
    }

    private fun credentialEntity(
        id: String = "cred-1",
        providerId: String = "deepseek",
        baseUrlOverride: String? = null,
    ) = com.pocketagent.core.database.entity.CredentialEntity(
        id = id,
        providerId = providerId,
        purpose = com.pocketagent.core.database.entity.CredentialPurpose.LLM,
        label = "主力",
        ciphertext = byteArrayOf(9, 9),
        iv = byteArrayOf(8, 8),
        keyLength = 16,
        baseUrlOverride = baseUrlOverride,
        createdAtMillis = 1000,
        lastCheckedAtMillis = null,
        lastStatus = null,
        lastStatusDetail = null,
        modelCount = null,
        isDefault = true,
    )

    // ─────────────────────────────────────────────────────────────
    //  GatewayCredentialSource
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `凭据不存在时返回 null 而不是抛异常`() = kotlinx.coroutines.runBlocking {
        // ⚠️ "用户删了一条 Key、而某个模型还挂着它"是**正常状态**。
        //    抛异常会让调用方必须写 try/catch 才能给出正确提示，
        //    而漏写的后果是用户看到一句"程序出错了"。
        val source = GatewayCredentialSource(
            dao = FakeCredentialDao(entity = null),
            decryptor = FakeDecryptor(),
        )

        assertNull(source.resolve("不存在的 id"))
    }

    @Test
    fun `解出的凭据带着 providerId 与明文 Key`() = kotlinx.coroutines.runBlocking {
        val source = GatewayCredentialSource(
            dao = FakeCredentialDao(entity = credentialEntity(providerId = "openrouter")),
            decryptor = FakeDecryptor(),
        )

        val resolved = source.resolve("cred-1")

        assertNotNull(resolved)
        assertEquals("openrouter", resolved!!.providerId)
        assertEquals("sk-secret-value", resolved.apiKey.decodeToString())
    }

    @Test
    fun `解出的明文 Key 没有被提前清零`() = kotlinx.coroutines.runBlocking {
        // ═══════════════════════════════════════════════════════════
        //  ★★ 这是本类最重要的一条
        // ═══════════════════════════════════════════════════════════
        //
        // 若实现误用 `CryptoManager.withDecryptedKey`（它的语义是
        // "用完自动清零"），返回的数组会**已经全 0** —— 而长度是对的，
        // 所以没有任何地方会报错。表现是：
        //
        //     上游返回 401 → 用户去查"Key 是不是过期了"
        //
        // 而真实原因是我们在交出去之前就把它擦了。
        // 这种"数据合法但内容没了"的失败，是最难从症状反推原因的一类。
        val source = GatewayCredentialSource(
            dao = FakeCredentialDao(entity = credentialEntity()),
            decryptor = FakeDecryptor(),
        )

        val resolved = source.resolve("cred-1")!!

        assertTrue(
            "明文 Key 不能被提前清零，实际：${resolved.apiKey.toList()}",
            resolved.apiKey.any { it != 0.toByte() },
        )
    }

    @Test
    fun `解密失败时抛 CredentialDecryptException 而不是返回 null`() = kotlinx.coroutines.runBlocking {
        // ⚠️ 必须与"凭据不存在"分开。合并成 null 的表现是：
        //    用户换机后打开应用，所有 Key 都显示"未配置"，
        //    于是他把 11 家厂商的 Key 全部重填一遍 ——
        //    而真实原因是主密钥变了，重填是对的，但他不知道。
        val source = GatewayCredentialSource(
            dao = FakeCredentialDao(entity = credentialEntity()),
            decryptor = FakeDecryptor(throwOnDecrypt = IllegalArgumentException("bad tag")),
        )

        val ex = runCatching { source.resolve("cred-1") }.exceptionOrNull()

        assertTrue("实际 $ex", ex is CredentialDecryptException)
        // 原始异常要保留为 cause —— 排查时需要它
        assertNotNull("应保留原始异常作为 cause", ex!!.cause)
    }

    @Test
    fun `解密的失败原因里不含密文内容`() = kotlinx.coroutines.runBlocking {
        // ⚠️ 异常最终可能被日志或上报带走。`CredentialDecryptException`
        //    的 message 是固定字符串，不拼任何 blob 内容 —— 这条钉住它。
        val source = GatewayCredentialSource(
            dao = FakeCredentialDao(entity = credentialEntity()),
            decryptor = FakeDecryptor(throwOnDecrypt = IllegalArgumentException("tag mismatch at 0xDEADBEEF")),
        )

        val ex = runCatching { source.resolve("cred-1") }.exceptionOrNull() as CredentialDecryptException

        assertEquals("credential cannot be decrypted", ex.message)
        assertTrue(
            "异常 message 不得透传底层细节",
            !ex.message!!.contains("DEADBEEF"),
        )
    }

    @Test
    fun `baseUrlOverride 透传给凭据`() = kotlinx.coroutines.runBlocking {
        val source = GatewayCredentialSource(
            dao = FakeCredentialDao(entity = credentialEntity(baseUrlOverride = "https://my.proxy/v1")),
            decryptor = FakeDecryptor(),
        )

        assertEquals("https://my.proxy/v1", source.resolve("cred-1")!!.baseUrlOverride)
    }

    @Test
    fun `解密收到的 blob 用的是库里那两列`() = kotlinx.coroutines.runBlocking {
        // ⚠️ 把 ciphertext 和 iv 传反了是个**编译得过**的错误（都是 ByteArray），
        //    而表现是"所有 Key 都解不开" —— 看起来像主密钥坏了。
        val decryptor = FakeDecryptor()
        val source = GatewayCredentialSource(
            dao = FakeCredentialDao(entity = credentialEntity()),
            decryptor = decryptor,
        )

        source.resolve("cred-1")

        val blob = decryptor.lastBlob
        assertNotNull(blob)
        assertTrue("ciphertext 应是库里的 ciphertext", blob!!.ciphertext.contentEquals(byteArrayOf(9, 9)))
        assertTrue("iv 应是库里的 iv", blob.iv.contentEquals(byteArrayOf(8, 8)))
    }

    // ─────────────────────────────────────────────────────────────
    //  RoomUsageRecorder
    // ─────────────────────────────────────────────────────────────

    private fun usageRecord(
        consumer: Consumer = Consumer.AgentLoop,
        upstream: TokenUsage? = TokenUsage(inputTokens = 118, outputTokens = 30),
        failure: String? = null,
        warning: String? = null,
    ) = UsageRecord(
        modelConfigId = "m1",
        providerId = "deepseek",
        consumer = consumer,
        inputTokens = 120,
        outputTokens = 34,
        upstreamUsage = upstream,
        failure = failure,
        warning = warning,
    )

    @Test
    fun `记账写入的是完整映射`() = kotlinx.coroutines.runBlocking {
        val dao = FakeUsageDao()
        RoomUsageRecorder(dao) { 5000L }.record(usageRecord(consumer = Consumer.Dsh))

        val e = dao.inserted.single()
        assertEquals("m1", e.modelConfigId)
        assertEquals("deepseek", e.providerId)
        assertEquals("Dsh", e.consumer)
        assertEquals(120, e.inputTokens)
        assertEquals(34, e.outputTokens)
        assertEquals(118, e.upstreamInputTokens)
        assertEquals(30, e.upstreamOutputTokens)
        assertNull(e.failure)
        assertNull(e.warning)
        assertEquals(5000L, e.createdAtMillis)
    }

    @Test
    fun `consumer 存的是枚举名而不是序号`() = kotlinx.coroutines.runBlocking {
        // ⚠️ 存 ordinal 的话，枚举成员一重排，**历史记录的含义会整体错位**，
        //    而且编译期没有任何提示。存名字至少能在改名时被一眼看出来。
        val dao = FakeUsageDao()
        RoomUsageRecorder(dao).record(usageRecord(consumer = Consumer.Probe))

        assertEquals("Probe", dao.inserted.single().consumer)
    }

    @Test
    fun `上游没给 usage 时两列为 null 而不是 0`() = kotlinx.coroutines.runBlocking {
        // ⚠️ 用 0 代替 null 会让"我们的估算偏了多少"算不出来 ——
        //    0 会被当成真实值参与统计，把"没数据"读成"估多了"。
        val dao = FakeUsageDao()
        RoomUsageRecorder(dao).record(usageRecord(upstream = null, failure = "timeout"))

        val e = dao.inserted.single()
        assertNull("上游没给 usage 时该列为 null", e.upstreamInputTokens)
        assertNull(e.upstreamOutputTokens)
        // 估算值仍然要写 —— 那是"上游没数据"时唯一的参考
        assertEquals(120, e.inputTokens)
    }

    @Test
    fun `上游给了 usage 但值为 0 时不能被当成没给`() = kotlinx.coroutines.runBlocking {
        // 边界：上游明确说"输入 0 个 token"。这与"上游没返回 usage"
        // 是**不同的信息**，两者都不能丢。
        val dao = FakeUsageDao()
        RoomUsageRecorder(dao).record(
            usageRecord(upstream = TokenUsage(inputTokens = 0, outputTokens = 0))
        )

        val e = dao.inserted.single()
        assertEquals(0, e.upstreamInputTokens)
        assertEquals(0, e.upstreamOutputTokens)
    }

    @Test
    fun `写入失败时吞掉异常而不是抛出去`() = kotlinx.coroutines.runBlocking {
        // ═══════════════════════════════════════════════════════════
        //  ★★ 这是本类最重要的一条
        // ═══════════════════════════════════════════════════════════
        //
        // 网关调用 record 的位置是流的 `onCompletion` 回调里。
        // `onCompletion` 中抛出的异常会**取代**原本的完成原因：
        //
        //   · 用户点了取消 → 取消被"记账失败"盖掉 → 上层看到的是
        //     "写数据库失败"，与用户的操作毫无关系
        //   · 一次成功的对话 → 被一个失败的 INSERT 变成"调用失败" →
        //     用户看到红字，而答案明明已经完整地流出来了
        //
        // 代价是"账本可能缺一条"，那比"成功被报成失败"轻得多。
        val dao = FakeUsageDao().apply {
            throwOnInsert = android.database.sqlite.SQLiteException("disk full")
        }

        // 必须不抛 —— 抛了本用例就红
        RoomUsageRecorder(dao).record(usageRecord())

        assertTrue("写入确实被尝试过", dao.inserted.isEmpty())
    }

    @Test
    fun `任意异常都被吞掉含 Error 之外的一切`() = kotlinx.coroutines.runBlocking {
        // 连 RuntimeException 这种"明显是自己 bug"的也要吞 ——
        // 记账不该成为对话失败的原因。日志留痕即可。
        val dao = FakeUsageDao().apply {
            throwOnInsert = IllegalStateException("nobody expected this")
        }

        RoomUsageRecorder(dao).record(usageRecord())
    }

    @Test
    fun `警告与失败原因都被落库`() = kotlinx.coroutines.runBlocking {
        // ⚠️ warning 必须落库：不存的话，"用户的预算对某个模型静默失效"
        //    这件事事后完全无法追溯，而它造成的损失是真金白银。
        val dao = FakeUsageDao()
        RoomUsageRecorder(dao).record(
            usageRecord(failure = "timeout", warning = "「本地模型」没有标注价格，预算上限对它不生效")
        )

        val e = dao.inserted.single()
        assertEquals("timeout", e.failure)
        assertTrue(e.warning!!.contains("不生效"))
    }

    // ─────────────────────────────────────────────────────────────
    //  读回方向的映射
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `未知的 consumer 值退回任务执行而不是抛异常`() {
        // ⚠️ 用 `valueOf` 会在枚举改名/删成员后直接抛 ——
        //    表现是"升级应用后用量页打不开"，而用户完全不知道
        //    是历史数据的问题。
        val entity = UsageRecordEntity(
            id = 7,
            modelConfigId = "m1",
            providerId = "deepseek",
            consumer = "SomethingRemovedInV2",
            inputTokens = 1,
            outputTokens = 2,
            upstreamInputTokens = null,
            upstreamOutputTokens = null,
            failure = null,
            warning = null,
            createdAtMillis = 1,
        )

        val snapshot = entity.toDomain()

        assertSame(Consumer.AgentLoop, snapshot.consumer)
        assertEquals(7L, snapshot.id)
    }

    @Test
    fun `读回时保留 id 与两列上游用量`() {
        val entity = UsageRecordEntity(
            id = 42,
            modelConfigId = "m1",
            providerId = "openrouter",
            consumer = "Dsh",
            inputTokens = 10,
            outputTokens = 20,
            upstreamInputTokens = 9,
            upstreamOutputTokens = 18,
            failure = "rate limited",
            warning = null,
            createdAtMillis = 1234,
        )

        val snapshot = entity.toDomain()

        assertEquals(42L, snapshot.id)
        assertEquals("openrouter", snapshot.providerId)
        assertSame(Consumer.Dsh, snapshot.consumer)
        assertEquals(9, snapshot.upstreamInputTokens)
        assertEquals(18, snapshot.upstreamOutputTokens)
        assertEquals("rate limited", snapshot.failure)
    }
}
