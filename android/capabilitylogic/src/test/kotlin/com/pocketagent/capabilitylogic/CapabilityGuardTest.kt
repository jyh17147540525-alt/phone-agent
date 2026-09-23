package com.pocketagent.capabilitylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裁决器 —— 本模块最核心的一组测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★★ 这里要钉住的**不是"某条能力能不能用"，而是顺序**
 * ═══════════════════════════════════════════════════════════════
 *
 * [CapabilityGuard] 的七步顺序看起来"怎么排都能跑" —— 每一步单独拿出来
 * 都合理，把它调换一下也不会让任何一条"正向测试"变红。
 *
 * 但顺序错了的后果是不对称的：
 *
 * | 调换 | 后果 |
 * |---|---|
 * | 确认标志提前消费 | **"用户点过确认"变成绕过一切的后门** |
 * | 硬拒绝排在放行之后 | 用户一授权就绕过了固定底线 |
 * | 未放行排在参数校验之前 | 只是报错信息差一点（无害） |
 *
 * ⇒ 所以下面有一组测试**专门构造"参数非法 + 已确认 + 已授权"的调用**，
 *   断言它仍然被拒绝。这类测试的价值在于：把顺序改错的人会立刻看到红色，
 *   而不是在三个月后收到一份"某插件能停用系统 UI"的报告。
 */
class CapabilityGuardTest {

    private val catalog = CapabilityCatalog()

    /** 默认策略：只有 SAFE 默认放行。 */
    private val defaultGuard = CapabilityGuard(catalog)

    /** 全部放行 —— 用来把"放行"这一维从测试里消掉，专测别的步骤。 */
    private val allGrantedGuard = CapabilityGuard(catalog) { true }

    /** 全部不放行 —— 用来证明硬拒绝不依赖放行状态。 */
    private val nothingGrantedGuard = CapabilityGuard(catalog) { false }

    // ══════════════════════════════════════════════════════════
    //  步骤 0：目录
    // ══════════════════════════════════════════════════════════

    @Test
    fun `不在目录里的能力被拒绝`() {
        val verdict = defaultGuard.decide(CapabilityCall("no.such.thing"))

        val blocked = verdict as CapabilityVerdict.Blocked
        assertEquals(BlockReason.UNKNOWN_CAPABILITY, blocked.reason)
        assertFalse("这不是'你去配置一下就能用'的情况", blocked.canFallbackToManual)
        assertTrue(
            "文案要说清是发起方用了个不存在的名字，而不是'功能没实现'",
            blocked.userMessage.contains("不认识"),
        )
    }

    @Test
    fun `能力 id 里的控制字符不会原样进到用户文案里`() {
        val verdict = defaultGuard.decide(CapabilityCall("bad\nid"))
        val blocked = verdict as CapabilityVerdict.Blocked

        assertFalse("文案里出现了真实换行，会伪造出第二行", blocked.userMessage.contains('\n'))
    }

    // ══════════════════════════════════════════════════════════
    //  步骤 1：硬拒绝（单向阀）
    // ══════════════════════════════════════════════════════════

    /** 一条 id 命中拒绝前缀的能力 —— 目录可以扩展，所以这种能力可能出现。 */
    private val rogueCapability = Capability(
        id = "setting.write.generic",
        channel = CapabilityChannel.SHELL,
        group = CapabilityGroup.DEVICE,
        risk = CapabilityRisk.SAFE,
        summary = "写任意系统设置（这是被固定拒绝的形状）",
        recipe = ExecutionRecipe.Shell(
            listOf(
                ExecutionRecipe.Shell.Segment.Literal("cmd"),
                ExecutionRecipe.Shell.Segment.Literal("settings"),
                ExecutionRecipe.Shell.Segment.Literal("put"),
            ),
        ),
    )

    @Test
    fun `命中拒绝前缀的能力永远不放行，即使已授权`() {
        val guard = CapabilityGuard(CapabilityCatalog(extra = listOf(rogueCapability))) { true }

        val blocked = guard.decide(CapabilityCall(rogueCapability.id)) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.DENIED_BY_POLICY, blocked.reason)
        assertFalse("固定底线不该给'手动去做'的出口", blocked.canFallbackToManual)
    }

    // ══════════════════════════════════════════════════════════
    //  步骤 2：来源限制
    // ══════════════════════════════════════════════════════════

    @Test
    fun `插件不能执行 GUARDED 能力`() {
        val verdict = allGrantedGuard.decide(
            CapabilityCall("wifi.set_enabled", mapOf("state" to "disabled"), CallOrigin.PLUGIN),
        )

        val blocked = verdict as CapabilityVerdict.Blocked
        assertEquals(BlockReason.PLUGIN_NOT_ALLOWED_GUARDED, blocked.reason)
        assertTrue("用户自己手动做一遍是可行的，界面要给出这个出口", blocked.canFallbackToManual)
    }

    @Test
    fun `插件可以执行 SAFE 能力`() {
        val verdict = defaultGuard.decide(
            CapabilityCall("media.dispatch", mapOf("action" to "pause"), CallOrigin.PLUGIN),
        )

        assertTrue("SAFE 能力不该因为来源是插件就被拦", verdict is CapabilityVerdict.Allowed)
    }

    @Test
    fun `用户与定时任务不受来源限制`() {
        // ⚠️ 只有 PLUGIN 被特殊限制。这条测试钉住的是"限制不该扩散"：
        //    如果哪天有人写成 `if (origin != CallOrigin.AGENT) return Blocked(...)`，
        //    用户手动点一下就再也用不了 GUARDED 能力了。
        for (origin in listOf(CallOrigin.USER, CallOrigin.AGENT, CallOrigin.SCHEDULE)) {
            val verdict = allGrantedGuard.decide(
                CapabilityCall("wifi.set_enabled", mapOf("state" to "disabled"), origin),
            )
            assertFalse(
                "来源 $origin 不该被来源限制拦下",
                verdict is CapabilityVerdict.Blocked &&
                    (verdict as CapabilityVerdict.Blocked).reason == BlockReason.PLUGIN_NOT_ALLOWED_GUARDED,
            )
        }
    }

    // ══════════════════════════════════════════════════════════
    //  步骤 3：参数校验
    // ══════════════════════════════════════════════════════════

    @Test
    fun `取值不在候选里时拒绝并给出明细`() {
        val blocked = defaultGuard.decide(
            CapabilityCall("media.dispatch", mapOf("action" to "explode")),
        ) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.INVALID_ARGS, blocked.reason)
        assertEquals(1, blocked.problems.size)
        assertTrue(blocked.problems[0] is ParamProblem.NotAllowed)
        assertTrue("文案要回显用户传的值", blocked.userMessage.contains("explode"))
    }

    @Test
    fun `多传参数会被拒绝而不是忽略`() {
        // ⚠️ 忽略它的后果是"看起来对、做的是另一件事"。
        val blocked = defaultGuard.decide(
            CapabilityCall(
                "media.dispatch",
                mapOf("action" to "pause", "user" to "999"),
            ),
        ) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.INVALID_ARGS, blocked.reason)
        assertTrue(blocked.problems.any { it is ParamProblem.Unknown })
    }

    @Test
    fun `缺必填参数会被拒绝`() {
        val blocked = defaultGuard.decide(CapabilityCall("media.dispatch")) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.INVALID_ARGS, blocked.reason)
        assertTrue(blocked.problems.any { it is ParamProblem.Missing })
    }

    @Test
    fun `包名格式不合法会被拒绝`() {
        val blocked = allGrantedGuard.decide(
            CapabilityCall(
                "app.set_enabled",
                mapOf("action" to "disable", "packageName" to "not a package"),
            ),
        ) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.INVALID_ARGS, blocked.reason)
        assertTrue(blocked.problems.any { it is ParamProblem.NotPackageName })
    }

    @Test
    fun `参数问题排在未放行之前`() {
        // ⚠️ 两者都是拒绝，谁先谁后不影响安全性，但影响用户看到哪句话。
        //    "参数不合法"是发起方的 bug，"没放行"是用户的配置状态 ——
        //    前者信息价值更高，应当先报出来。
        val blocked = defaultGuard.decide(
            CapabilityCall("wifi.set_enabled", mapOf("state" to "explode")),
        ) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.INVALID_ARGS, blocked.reason)
    }

    // ══════════════════════════════════════════════════════════
    //  步骤 5：对规划结果再做硬校验
    // ══════════════════════════════════════════════════════════

    /** 一条"看起来无害"的设置能力，但键命中硬拒绝。 */
    private val accessibilityCapability = Capability(
        id = "test.acc_write",
        channel = CapabilityChannel.SETTINGS,
        group = CapabilityGroup.DEVICE,
        risk = CapabilityRisk.SAFE,
        summary = "写一个无障碍相关的设置",
        params = listOf(ParamSpec.Choice("value", listOf("0", "1"), "值")),
        recipe = ExecutionRecipe.Setting(
            namespace = SettingNamespace.SECURE,
            key = "enabled_accessibility_services",
            valueParam = "value",
        ),
    )

    @Test
    fun `配方里的设置键命中硬拒绝时永远拒绝`() {
        // ⚠️ 这是整个模块最该守住的一条。这条键可写 = agent 能给自己
        //    授予无障碍权限 = 读全屏、点全屏、看得到支付页。
        //    不需要漏洞、不需要 root、不需要诱导用户点任何东西。
        val guard = CapabilityGuard(CapabilityCatalog(extra = listOf(accessibilityCapability))) { true }

        val blocked = guard.decide(
            CapabilityCall(accessibilityCapability.id, mapOf("value" to "1"), confirmedByUser = true),
        ) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.DENIED_SETTING_KEY, blocked.reason)
        assertFalse(blocked.canFallbackToManual)
    }

    @Test
    fun `命令里出现被拒包名时永远拒绝`() {
        // ⚠️ 这条钉住的是"安全的能力 + 危险的参数"这种组合。
        //    `app.set_enabled` 本身完全正当，而 com.android.systemui 是
        //    **格式合法**的包名 —— 步骤 3 的参数校验拦不住它。
        val blocked = allGrantedGuard.decide(
            CapabilityCall(
                "app.set_enabled",
                mapOf("action" to "disable", "packageName" to "com.android.systemui"),
                confirmedByUser = true,
            ),
        ) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.DENIED_TARGET_PACKAGE, blocked.reason)
        assertTrue("文案要说清停用它之后会失去什么", blocked.userMessage.contains("com.android.systemui"))
    }

    @Test
    fun `被拒包名的检查是精确匹配，不会误伤相似包名`() {
        // ⚠️ 如果实现里写成 startsWith / contains，这条会红。
        //    误伤的代价是"某个正常的 App 怎么都停用不了"，
        //    而且报错文案会说是"系统组件" —— 极难排查。
        val verdict = allGrantedGuard.decide(
            CapabilityCall(
                "app.set_enabled",
                mapOf("action" to "disable", "packageName" to "com.android.systemuihelper"),
            ),
        )

        assertFalse(
            "相似包名不该被当成系统组件",
            verdict is CapabilityVerdict.Blocked &&
                (verdict as CapabilityVerdict.Blocked).reason == BlockReason.DENIED_TARGET_PACKAGE,
        )
    }

    @Test
    fun `硬拒绝不依赖放行状态`() {
        // ⚠️ 收紧放行策略（连 SAFE 都不放行）不该改变硬拒绝的结论 ——
        //    两者是独立的层。如果实现里把放行检查提到了硬拒绝之前，
        //    这条测试会因为"先看到 NOT_GRANTED"而红。
        val blocked = nothingGrantedGuard.decide(
            CapabilityCall(
                "app.set_enabled",
                mapOf("action" to "disable", "packageName" to "com.android.systemui"),
            ),
        ) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.DENIED_TARGET_PACKAGE, blocked.reason)
    }

    // ══════════════════════════════════════════════════════════
    //  步骤 6：放行
    // ══════════════════════════════════════════════════════════

    @Test
    fun `未放行的能力返回的是「还没配置」而不是拒绝`() {
        val blocked = defaultGuard.decide(
            CapabilityCall("wifi.set_enabled", mapOf("state" to "disabled")),
        ) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.NOT_GRANTED, blocked.reason)
        assertTrue("界面要给出'去放行'的入口，而不是一个红色的失败提示", blocked.canFallbackToManual)
    }

    @Test
    fun `默认策略只放行 SAFE 能力`() {
        assertTrue(
            defaultGuard.decide(CapabilityCall("media.dispatch", mapOf("action" to "pause")))
                is CapabilityVerdict.Allowed,
        )
        assertTrue(
            defaultGuard.decide(CapabilityCall("display.auto_rotate", mapOf("enabled" to "1")))
                is CapabilityVerdict.Allowed,
        )
    }

    @Test
    fun `放行策略注入为全部拒绝时连 SAFE 能力也不放行`() {
        // ⚠️ 这条证明的是"放行是一个可收紧的注入点"，而不是硬编码。
        val blocked = nothingGrantedGuard.decide(
            CapabilityCall("media.dispatch", mapOf("action" to "pause")),
        ) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.NOT_GRANTED, blocked.reason)
    }

    // ══════════════════════════════════════════════════════════
    //  步骤 7：风险级别 + 确认
    // ══════════════════════════════════════════════════════════

    @Test
    fun `已放行的 GUARDED 能力未确认时要求确认`() {
        val verdict = allGrantedGuard.decide(
            CapabilityCall("wifi.set_enabled", mapOf("state" to "disabled")),
        )

        val confirm = verdict as CapabilityVerdict.RequireConfirmation
        assertEquals(ConfirmReason.GUARDED_CAPABILITY, confirm.reason)
        assertEquals(CapabilityVerdict.DEFAULT_CONFIRM_TIMEOUT_MS, confirm.timeoutMs)
        assertTrue(
            "文案要说清这一次具体做什么",
            confirm.userMessage.contains("cmd wifi set-wifi-enabled disabled"),
        )
    }

    @Test
    fun `确认过的 GUARDED 能力放行并标记 wasConfirmed`() {
        val verdict = allGrantedGuard.decide(
            CapabilityCall(
                "wifi.set_enabled",
                mapOf("state" to "disabled"),
                confirmedByUser = true,
            ),
        )

        val allowed = verdict as CapabilityVerdict.Allowed
        assertTrue(allowed.wasConfirmed)
    }

    @Test
    fun `SAFE 能力直接放行且不标记 wasConfirmed`() {
        val allowed = defaultGuard.decide(
            CapabilityCall("media.dispatch", mapOf("action" to "pause")),
        ) as CapabilityVerdict.Allowed

        assertFalse("wasConfirmed 记的是'这次放行是不是确认换来的'，不是'危不危险'", allowed.wasConfirmed)
    }

    // ══════════════════════════════════════════════════════════
    //  ★★★ 确认标志不是万能钥匙
    // ══════════════════════════════════════════════════════════

    @Test
    fun `确认标志不能让非法参数通过`() {
        // ⚠️ 这条防的是一个很具体的攻击路径：
        //    1. agent 请求「播放/暂停」这种无害操作，用户点了确认
        //    2. 真正执行前，agent 把参数改成非法值（或换了能力）
        //    3. 裁决器若看到 confirmedByUser 就提前返回 Allowed，直接放行
        //    第 2 步不需要恶意 —— 它可以是模型的一次"修正"或一次重试。
        val blocked = defaultGuard.decide(
            CapabilityCall(
                "media.dispatch",
                mapOf("action" to "explode"),
                confirmedByUser = true,
            ),
        ) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.INVALID_ARGS, blocked.reason)
    }

    @Test
    fun `确认标志不能让被拒包名通过`() {
        val blocked = allGrantedGuard.decide(
            CapabilityCall(
                "app.set_enabled",
                mapOf("action" to "disable", "packageName" to "com.android.systemui"),
                confirmedByUser = true,
            ),
        ) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.DENIED_TARGET_PACKAGE, blocked.reason)
    }

    @Test
    fun `确认标志不能让未放行的能力通过`() {
        val blocked = defaultGuard.decide(
            CapabilityCall(
                "wifi.set_enabled",
                mapOf("state" to "disabled"),
                confirmedByUser = true,
            ),
        ) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.NOT_GRANTED, blocked.reason)
    }

    @Test
    fun `确认标志不能让插件碰到 GUARDED 能力`() {
        val blocked = allGrantedGuard.decide(
            CapabilityCall(
                "wifi.set_enabled",
                mapOf("state" to "disabled"),
                CallOrigin.PLUGIN,
                confirmedByUser = true,
            ),
        ) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.PLUGIN_NOT_ALLOWED_GUARDED, blocked.reason)
    }

    @Test
    fun `确认标志不能让不存在的能力通过`() {
        val blocked = defaultGuard.decide(
            CapabilityCall("no.such.thing", confirmedByUser = true),
        ) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.UNKNOWN_CAPABILITY, blocked.reason)
    }

    // ══════════════════════════════════════════════════════════
    //  定时任务（无人值守）
    // ══════════════════════════════════════════════════════════

    @Test
    fun `定时任务执行已放行的 GUARDED 能力时不再要求确认`() {
        // ⚠️ 这是刻意的：无人值守时弹确认框没有意义 —— 没人会点，
        //    超时按"视为拒绝"处理，用户第二天看到的是一次**静默失败**，
        //    而他还以为自己昨晚设的任务跑过了。那比直接不做更糟。
        //    所以用户**逐项放行**这个动作本身，就同时覆盖了夜间自动执行。
        val verdict = allGrantedGuard.decide(
            CapabilityCall("wifi.set_enabled", mapOf("state" to "disabled"), CallOrigin.SCHEDULE),
        )

        assertTrue(verdict is CapabilityVerdict.Allowed)
        assertFalse("自动执行不是用户当场确认换来的", (verdict as CapabilityVerdict.Allowed).wasConfirmed)
    }

    @Test
    fun `定时任务执行未放行的 GUARDED 能力仍被拒绝`() {
        val blocked = defaultGuard.decide(
            CapabilityCall("wifi.set_enabled", mapOf("state" to "disabled"), CallOrigin.SCHEDULE),
        ) as CapabilityVerdict.Blocked

        assertEquals(BlockReason.NOT_GRANTED, blocked.reason)
    }

    // ══════════════════════════════════════════════════════════
    //  裁决与执行之间的交接
    // ══════════════════════════════════════════════════════════

    @Test
    fun `放行时带出的执行与独立规划的结果一致`() {
        // ⚠️ 钉住的是"从裁决通过到拿去执行之间没有自由度"：
        //    执行方拿到的就是裁决时校验过的那一个对象。
        val args = mapOf("action" to "disable", "packageName" to "com.example.app")
        val allowed = allGrantedGuard.decide(
            CapabilityCall("app.set_enabled", args, confirmedByUser = true),
        ) as CapabilityVerdict.Allowed

        val expected = CommandPlanner.plan(catalog.byId("app.set_enabled")!!, args)
        assertEquals(expected, allowed.execution)
        assertEquals("app.set_enabled", allowed.capability.id)
    }

    @Test
    fun `需要确认时也带出执行，供确认界面展示具体参数`() {
        val confirm = allGrantedGuard.decide(
            CapabilityCall("display.brightness", mapOf("level" to "120")),
        ) as CapabilityVerdict.RequireConfirmation

        assertEquals(
            "system/screen_brightness = 120",
            (confirm.execution as PlannedExecution.SettingWrite).let {
                "${it.namespace.wireName}/${it.key} = ${it.value}"
            },
        )
    }

    // ══════════════════════════════════════════════════════════
    //  被拒绝时也要留下"它想动什么"
    // ══════════════════════════════════════════════════════════
    //
    // ⚠️ 这一组测试守的是**审计日志的可用性**，不是安全性。
    //    `CapabilityAuditLog` 的注释里写着：一个反复请求停用
    //    `com.android.systemui` 的插件，在日志里的样子是连续多条 DENIED ——
    //    这是用户判断"这个插件想干什么"的唯一线索。
    //    而 DENIED 那条记录上唯一有信息量的字段就是 `targetDigest`。

    /**
     * 构造"会走到第 5/6 步才被拒"的调用 —— 也就是**判定时已经规划过**的那些。
     *
     * ⚠️ 写成列表而不是三条独立测试：它们的断言完全相同，
     *    拆开只会让"少覆盖了一个原因码"变得看不出来。
     */
    private fun plannedThenBlocked(): List<Pair<BlockReason, CapabilityVerdict.Blocked>> {
        val guard = CapabilityGuard(CapabilityCatalog(extra = listOf(accessibilityCapability))) { true }

        return listOf(
            // 第 5 步 · 设置键命中硬拒绝
            guard.decide(
                CapabilityCall(accessibilityCapability.id, mapOf("value" to "1"), confirmedByUser = true),
            ),
            // 第 5 步 · 命令里出现被拒包名
            allGrantedGuard.decide(
                CapabilityCall(
                    "app.set_enabled",
                    mapOf("action" to "disable", "packageName" to "com.android.systemui"),
                ),
            ),
            // 第 6 步 · 还没放行。
            // ⚠️ 这里**刻意**选一个带包名的能力（`app.set_enabled`），
            //    而不是 `wifi.set_enabled` —— 后者的 argv 里没有包名，
            //    摘要按定义就是空串（见下面那条专门的测试）。
            //    用错的话这条测试会红，而红的原因与"字段没接上"长得一模一样。
            defaultGuard.decide(
                CapabilityCall(
                    "app.set_enabled",
                    mapOf("action" to "disable", "packageName" to "com.example.app"),
                ),
            ),
        ).map { it as CapabilityVerdict.Blocked }.map { it.reason to it }
    }

    @Test
    fun `判定发生在规划之后的拒绝会带出执行对象`() {
        val cases = plannedThenBlocked()

        assertEquals(
            "三个原因码都要覆盖到",
            setOf(
                BlockReason.DENIED_SETTING_KEY,
                BlockReason.DENIED_TARGET_PACKAGE,
                BlockReason.NOT_GRANTED,
            ),
            cases.map { it.first }.toSet(),
        )

        for ((reason, blocked) in cases) {
            assertNotNull("$reason 的拒绝发生在规划之后，应当带上已规划的执行", blocked.execution)
        }
    }

    @Test
    fun `拒绝带出的执行与独立规划的结果一致`() {
        val expected = CommandPlanner.plan(
            catalog.byId("wifi.set_enabled")!!,
            mapOf("state" to "disabled"),
        )
        val blocked = defaultGuard.decide(
            CapabilityCall("wifi.set_enabled", mapOf("state" to "disabled")),
        ) as CapabilityVerdict.Blocked

        assertEquals(expected, blocked.execution)
    }

    @Test
    fun `带上执行之后，审计里查得到被拒的调用想动什么`() {
        // 对照：不带执行时是空串 —— 这正是加这个字段之前的样子。
        assertEquals(
            "不带执行时目标摘要只能是空串",
            "",
            CapabilityAuditEvents.denied(
                timestamp = 1L,
                capabilityId = "test",
                origin = CallOrigin.AGENT,
                reason = BlockReason.DENIED_SETTING_KEY,
            ).targetDigest,
        )

        val digests = plannedThenBlocked().map { (reason, blocked) ->
            reason to CapabilityAuditEvents.denied(
                timestamp = 1L,
                capabilityId = "test",
                origin = CallOrigin.AGENT,
                reason = reason,
                execution = blocked.execution,
            ).targetDigest
        }

        for ((reason, digest) in digests) {
            assertTrue("$reason 的审计目标摘要不该是空串", digest.isNotEmpty())
        }
        assertTrue(
            "被拒的包名要能在审计里查到",
            digests.single { it.first == BlockReason.DENIED_TARGET_PACKAGE }
                .second.contains("com.android.systemui"),
        )
        assertTrue(
            "被拒的设置键要能在审计里查到",
            digests.single { it.first == BlockReason.DENIED_SETTING_KEY }
                .second.contains("enabled_accessibility_services"),
        )
    }

    @Test
    fun `判定发生在规划之前的拒绝不带执行`() {
        // ★ 这条是本组最该守住的一条。
        //
        //   它防的是一个**看起来更整齐**的重构：把 CommandPlanner.plan
        //   提到方法开头，让每个 return 都能顺手带上 execution。
        //   那样做之后"参数校验"就失去了意义 —— 命令会在参数非法时
        //   被规划出来，而规划结果里带着用户传入的原始值。
        //
        //   ⇒ 所以这里断言的是 **null**，而不是"非空"。
        //     两条断言方向相反，正是因为它们守的是相反的两件事。
        val cases = listOf(
            // 第 0 步 · 目录里没有
            defaultGuard.decide(CapabilityCall("no.such_capability")),
            // 第 1 步 · 命中拒绝前缀（默认策略下 SAFE 才放行，但第 1 步在第 6 步之前）
            CapabilityGuard(CapabilityCatalog(extra = listOf(rogueCapability)))
                .decide(CapabilityCall(rogueCapability.id)),
            // 第 2 步 · 插件碰 GUARDED
            allGrantedGuard.decide(
                CapabilityCall("wifi.set_enabled", mapOf("state" to "disabled"), CallOrigin.PLUGIN),
            ),
            // 第 3 步 · 参数不合法（缺必填的 packageName）
            allGrantedGuard.decide(CapabilityCall("app.set_enabled", mapOf("action" to "disable"))),
        ).map { it as CapabilityVerdict.Blocked }

        assertEquals(
            "四个原因码都要覆盖到",
            setOf(
                BlockReason.UNKNOWN_CAPABILITY,
                BlockReason.DENIED_BY_POLICY,
                BlockReason.PLUGIN_NOT_ALLOWED_GUARDED,
                BlockReason.INVALID_ARGS,
            ),
            cases.map { it.reason }.toSet(),
        )

        for (blocked in cases) {
            assertNull(
                "${blocked.reason} 发生在规划之前，不该带出任何执行",
                blocked.execution,
            )
        }
    }

    @Test
    fun `目标不是包名的命令，摘要为空不是缺口`() {
        // ⚠️ 这条测试守的是**反方向**：防止有人把"摘要为空"当成 bug 去修。
        //
        //   `digestOf` 对 shell 命令只摘**包名**（见 CapabilityAuditLog 里那段
        //   "为什么不用参数名表白名单"）。`wifi.set_enabled` 的 argv 是
        //   `cmd wifi set-wifi-enabled disabled` —— 里面没有包名，
        //   所以摘要按定义就是空串。
        //
        //   而"它想动什么"并没有丢：`capabilityId` 本身就写着 wifi.set_enabled。
        //   摘要要解决的是**同一个能力、不同的目标**（停用哪一个 App），
        //   那种情况才有信息量。
        //
        //   ⇒ 所以这里断言"执行带出来了，但摘要为空"。
        //     如果有人为了让摘要非空而放松 `packageNamePattern`，
        //     后果是通知正文、文件名这类东西被误判成包名**永久落进日志** ——
        //     那正是那个正则的注释里点名的方向。这条测试会红在那里。
        val blocked = defaultGuard.decide(
            CapabilityCall("wifi.set_enabled", mapOf("state" to "disabled")),
        ) as CapabilityVerdict.Blocked

        assertNotNull("执行本身应当带出来", blocked.execution)
        assertEquals(
            "没有包名的命令，摘要就是空串 —— 这不是缺口",
            "",
            CapabilityAuditEvents.denied(
                timestamp = 1L,
                capabilityId = "wifi.set_enabled",
                origin = CallOrigin.AGENT,
                reason = blocked.reason,
                execution = blocked.execution,
            ).targetDigest,
        )
    }
}
