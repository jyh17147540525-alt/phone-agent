package com.pocketagent.provider.gateway.http

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 本地网关的访问令牌（BYOK 文档 §4.4 第 ③④ 条）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它防的是**同一台手机上的其他 App**，不是网络攻击
 * ═══════════════════════════════════════════════════════════════
 *
 * 这是本类最容易被误解的地方。网关只监听 `127.0.0.1`（第 ① 条），
 * 所以外部网络碰不到它 —— 但 **Android 上 localhost 没有进程隔离**：
 * 任何一个有 INTERNET 权限的 App 都能连 `127.0.0.1:<port>`。
 *
 * 于是威胁模型是：
 *
 * | 攻击者 | 能做什么 |
 * |---|---|
 * | 同一个用户装的另一个 App | 扫端口 → 连上 → **用用户的 Key 免费调模型** |
 * | 局域网内其他设备 | 碰不到（只听 loopback） |
 * | 应用商店审核 | 看不到（不上架） |
 *
 * 一个能连上网关的恶意 App，花的是**用户自己的钱**，而且日志里
 * 只会显示"用户调用了模型"—— 用户完全无从发现。这就是为什么
 * ③④ 两条（随机端口 + 本地 token）不能省。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ token 强度：32 字节（决策 D-N 已定）
 * ═══════════════════════════════════════════════════════════════
 *
 * 256 位随机。成本为零（一次 `SecureRandom.nextBytes`），而它把
 * "暴力猜 token"从"可行"变成"不可能"：即使攻击者能每秒发 10^9 次
 * 请求（本地回环也不现实），期望时间也是 10^58 年。
 *
 * ⚠️ 相比之下，**端口随机不能替代 token**。端口只有 65535 个，
 *    扫一遍是秒级的事；它只提高了"被发现"的成本，不构成鉴权。
 *    两者是**纵深防御**的两层，不是二选一。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么比较必须用常量时间（第 ④ 条）
 * ═══════════════════════════════════════════════════════════════
 *
 * `a == b` 在 Kotlin 里对 String 是**逐字符比较、遇不同即返回**。
 * 这意味着"前 3 个字符对了"比"第 1 个字符就错了"要多花一点时间 ——
 * 差异只有纳秒级，但**攻击者可以在本地回环上测出来**（没有网络抖动，
 * 而且可以发几百万次取统计量）。这就把 256 位的搜索空间逐步降成
 * 逐字符的 16 次猜测。
 *
 * `MessageDigest.isEqual` 是 JDK 提供的常量时间实现，它总是比完
 * 整个数组。**这就是不用 `contentEquals` 的理由。**
 *
 * ⚠️ 注意 `MessageDigest.isEqual` 在长度不同时**会提前返回** ——
 *    这本身是无害的（长度不是秘密：格式固定，且攻击者可以靠发送
 *    不同长度的 token 直接观察出长度），所以先比长度是安全的。
 *    真正要防的是**同长度下的逐字节时序泄漏**。
 */
class GatewayTokenProvider(
    /**
     * 随机源。**默认必须是 [SecureRandom]** ——
     * `kotlin.random.Random` 是可预测的（它就是个线性同余），
     * 拿它生成鉴权 token 等于没有鉴权。
     *
     * 允许注入只为了测试能拿到确定性序列。
     */
    private val random: SecureRandom = SecureRandom(),
) {

    /**
     * 生成一个新 token（每次启动一次，见 BYOK §4.4 第 ③ 条）。
     *
     * ⚠️ **刻意不缓存、不做成 `val`**：调用方拿到的是"这一次启动的
     *    token"，而它**只应该存在于内存中** —— 不落盘、不进日志、
     *    不进 SharedPreferences。落盘的 token 会跨重启复用，
     *    而"每次启动换新的"正是为了让泄露窗口不超过一次启动。
     *
     * @return 十六进制字符串（64 个字符 = 32 字节）
     */
    fun issue(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    /**
     * 校验一个候选 token。**常量时间比较**。
     *
     * @param expected 本次启动的 token（[issue] 的产物）
     * @param candidate 请求带来的 token；null = 没带
     */
    fun matches(expected: String, candidate: String?): Boolean {
        // ⚠️ 这一层用普通比较是**安全**的：长度不是秘密。
        //    而且提前返回在"多带/少带字符"这种容易被扫出来的错误上
        //    反而更快 —— 我们不需要为它付出常量时间的代价。
        if (candidate == null) return false
        if (candidate.length != expected.length) return false

        val a = expected.toByteArray(Charsets.UTF_8)
        val b = candidate.toByteArray(Charsets.UTF_8)

        // ★ 常量时间：这个函数总是比完整个数组才返回，
        //   不会在第一个不同的字节处短路。
        return MessageDigest.isEqual(a, b)
    }

    companion object {
        /**
         * token 字节数。
         *
         * ⚠️ 32 不是"够长就行"的随手选择 —— 它是决策 D-N 定下的值，
         *    理由见类注释。改小它需要重新审视威胁模型。
         */
        const val TOKEN_BYTES = 32

        /**
         * 从 `Authorization` 头里取出 token。
         *
         * 兼容两种写法，因为 **dsh 坚持要给一个像 Key 的东西**
         * （BYOK §2.4）：
         * - `Authorization: Bearer <token>` —— OpenAI 风格，dsh 的默认
         * - `Authorization: <token>` —— 少数客户端直接塞
         *
         * ⚠️ 也接受 `x-api-key`（Anthropic 风格）—— 因为 dsh 的
         *    `dsh-llm-pi-ai` 支持 `anthropic-messages` 协议，那条路径
         *    会用它。少了这个分支的表现是"切成 Anthropic 协议就连不上"，
         *    而错误是 401，很容易被误判成"Key 配错了"。
         *
         * @return 取到的 token；null = 完全没有可用的头
         */
        fun extract(headers: Map<String, String>): String? {
            // ⚠️ HTTP 头名**大小写不敏感**（RFC 9110 §5.1）——
            //    按精确大小写查会被"客户端发的是 `authorization`"
            //    这种完全合法的情况打进 401，且很难查。
            var authorization: String? = null
            var apiKey: String? = null

            for ((name, value) in headers) {
                when {
                    name.equals("Authorization", ignoreCase = true) ->
                        authorization = value

                    name.equals("x-api-key", ignoreCase = true) ->
                        apiKey = value
                }
            }

            // ⚠️ 这个判定必须**只认准 `Bearer` 这个前缀词**，不能靠"裁完
            //    之后还剩下什么"来推断。第一版写成先裁前缀再 trim，
            //    结果是 `Authorization: Bearer `（后面什么都没有）裁完变成
            //    空串 → 回落到"裸 Authorization"分支 → 返回了字符串
            //    `"Bearer"`。它虽然照样校验失败，但会污染日志与排查方向。
            val bearer = authorization
                ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
                ?.substring(BEARER_PREFIX.length)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            // 裸 `Authorization: <token>`（不带 Bearer 前缀）。
            // ⚠️ 这里要**显式排除** `Bearer`（带不带空格都算）——
            //    见上面那段注释，靠裁完的余量推断会漏掉"只有前缀"的情况。
            val raw = authorization
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.takeIf { !it.equals(BEARER_WORD, ignoreCase = true) }
                ?.takeIf { !it.startsWith(BEARER_PREFIX, ignoreCase = true) }

            return bearer ?: raw ?: apiKey
        }

        private const val BEARER_PREFIX = "Bearer "
        private const val BEARER_WORD = "Bearer"

        /**
         * 字节数组 → 十六进制小写。
         *
         * ⚠️ 用 `String.format` 而不是 `BigInteger(1, bytes).toString(16)` ——
         *    后者会**丢掉前导零**（`[0x00, 0x01]` → `"1"` 而不是 `"0001"`），
         *    于是 token 长度不固定。表现是"偶尔有几个 token 校验总失败"，
         *    约每 256 次出现一次，是最难复现的那类 bug。
         */
        private fun ByteArray.toHex(): String {
            val out = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xFF
                out.append(HEX[v ushr 4])
                out.append(HEX[v and 0x0F])
            }
            return out.toString()
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
