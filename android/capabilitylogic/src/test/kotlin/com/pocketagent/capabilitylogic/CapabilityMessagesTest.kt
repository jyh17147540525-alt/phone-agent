package com.pocketagent.capabilitylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「判定结果 → 用户看到的那句话」的测试。
 *
 * ⚠️ 这一组测试盯的全是**在界面上看起来正常、但会把人引向错误动作**的写法：
 *    · 把"还差一步配置"写成"执行失败" → 用户反复重试
 *    · 把"还没放行"标成红色失败 → 用户以为功能坏了
 *    · 把"通道接受了"写成"设置已经改了" → 用户相信一件没被验证的事
 */
class CapabilityMessagesTest {

    // ── 成功 ──────────────────────────────────────────────────────

    @Test
    fun `成功是 Success，标题是「已经做完了」`() {
        val outcome = executed(ChannelResult.Succeeded(previousValue = "0"))

        assertEquals(CapabilityOutcomeTone.Success, CapabilityMessages.toneOf(outcome))
        assertEquals("已经做完了", CapabilityMessages.titleOf(outcome))
    }

    @Test
    fun `成功时不会说「设置已经改了」这种没被验证的话`() {
        // ★ 通道只保证"它接受了这次写入"。系统仍可能静默忽略
        //   （写进去了、没报错、值没变）。回读校验是下一步的事 ——
        //   在那之前说"已完成"就是一句假话。
        val detail = CapabilityMessages.detailOf(executed(ChannelResult.Succeeded()))

        assertTrue("要说清是哪一层接受了：$detail", detail.contains("通道接受了"))
        assertFalse("不能断言系统真的改了：$detail", detail.contains("已经改"))
        assertFalse(detail.contains("生效"))
    }

    @Test
    fun `成功且读到过原值时，告诉用户可以改回来`() {
        // 原值本身**不进界面**（它是来自用户或系统的值），但"有原值"这件事
        // 要告诉用户 —— 那正是 SAFE 级别"可逆"的兑现方式。
        val withPrev = CapabilityMessages.detailOf(executed(ChannelResult.Succeeded("1")))
        val without = CapabilityMessages.detailOf(executed(ChannelResult.Succeeded(null)))

        assertTrue(withPrev.contains("可以改回来"))
        assertFalse(without.contains("可以改回来"))
    }

    // ── ★★ 读到的东西必须显示出来 ────────────────────────────────

    @Test
    fun `读到的内容必须真的显示出来`() {
        // ★★ 2026-09-23 真机目击：`payload` 字段加了、runner 也填了，
        //    但 detailOf 不消费它 —— 真机上 `file.read` 显示的是
        //    「已经做完了 / 通道接受了这次操作」，读到的内容一个字都没有。
        //
        //    ⚠️ 这不是"显示得不好看"，是**这个能力对用户等于不存在**：
        //       读文件的价值 100% 在内容上。
        val outcome = executed(ChannelResult.Succeeded(payload = "第一行\n第二行"))

        val detail = CapabilityMessages.detailOf(outcome)

        assertTrue("读到的内容没有显示出来：$detail", detail.contains("第一行"))
        assertTrue("多行内容要完整保留：$detail", detail.contains("第二行"))
    }

    @Test
    fun `有内容时就不再补一句「通道接受了这次操作」`() {
        // 内容本身就是结果。再补一句套话只会把正文挤下去。
        val detail = CapabilityMessages.detailOf(
            executed(ChannelResult.Succeeded(payload = "目录  sub\n文件  a.txt  22 字节")),
        )

        assertFalse("有内容时不该再说套话：$detail", detail.contains("通道接受了"))
        assertTrue(detail.contains("a.txt"))
    }

    @Test
    fun `内容太长时截断，并且说清只显示了前面多少`() {
        // 通道一次能读几万字节量级，整段交给 Text 排版会让界面卡住 ——
        // 而"卡住"看起来像设备慢，比"截断"难排查得多。
        val huge = "字".repeat(5000)

        val detail = CapabilityMessages.detailOf(executed(ChannelResult.Succeeded(payload = huge)))

        assertTrue("要说清被截断了：$detail", detail.contains("只显示了前面"))
        assertTrue("截断后的正文不该超过上限太多：${detail.length}", detail.length < 1400)
        assertFalse("不该把 5000 字全塞进来：${detail.length}", detail.length > 4000)
    }

    @Test
    fun `截断不会把一个 emoji 切成两半`() {
        // ⚠️ 一个 emoji 在 Kotlin 字符串里是**两个** Char（代理对）。
        //    正好切在中间会留下一个孤立的 high surrogate，渲染成一个 �。
        //
        //    危害不是难看，是**极难被报上来**：用户看到"一大段正常文本里
        //    有一个乱码字符"，会以为原文件本来就有问题。
        //
        // ⚠️ 这里的 1199 / 1200 与 PREVIEW_LIMIT 绑定。改常量时这条会红 ——
        //    那正是提醒你回来重算，不是测试写错了。
        val payload = "a".repeat(1199) + "\uD83D\uDE00" + "tail"

        val detail = CapabilityMessages.detailOf(executed(ChannelResult.Succeeded(payload = payload)))

        assertFalse("留下了一个孤立的 high surrogate：$detail", detail.contains('\uD83D'))
        assertFalse("留下了一个孤立的 low surrogate：$detail", detail.contains('\uDE00'))
        assertTrue("emoji 之前的正文要保住：${detail.length}", detail.startsWith("a".repeat(1199)))
    }

    @Test
    fun `恰好等于上限的内容不截断`() {
        // 边界：长度正好 1200 时不该出现"只显示了前面 1200 个字符"这种
        // 自相矛盾的说明（等于没截）。
        val exact = "b".repeat(1200)

        val detail = CapabilityMessages.detailOf(executed(ChannelResult.Succeeded(payload = exact)))

        assertEquals("恰好到上限时原样返回", exact, detail)
    }

    @Test
    fun `没有内容时仍然说「通道接受了这次操作」`() {
        // 回归：设备开关类没有 payload，不能因为这次改动把它们的话弄丢。
        val detail = CapabilityMessages.detailOf(executed(ChannelResult.Succeeded(previousValue = "0")))

        assertTrue("没内容时仍要说清是哪一层接受了：$detail", detail.contains("通道接受了"))
        assertTrue(detail.contains("可以改回来"))
    }

    // ── ★ 两种「没做成」必须分开 ──────────────────────────────────

    @Test
    fun `通道没准备好是 Warning 且标题是「还差一步配置」`() {
        // ★ 写成"执行失败"的后果：用户反复重试 —— 而这条要的是去跑一条
        //   adb 命令，重试一百次也不会变。
        val outcome = executed(
            ChannelResult.Unavailable(
                reason = ChannelUnavailableReason.PERMISSION_DENIED,
                detail = "在电脑上执行一次：adb shell pm grant …",
            ),
        )

        assertEquals(CapabilityOutcomeTone.Warning, CapabilityMessages.toneOf(outcome))
        assertEquals("还差一步配置", CapabilityMessages.titleOf(outcome))
        assertEquals(
            "正文必须原样带上判定层给的那条命令",
            "在电脑上执行一次：adb shell pm grant …",
            CapabilityMessages.detailOf(outcome),
        )
    }

    @Test
    fun `真的失败了是「试过了，没成」`() {
        val outcome = executed(ChannelResult.Failed(reason = "系统拒绝了这次改写"))

        assertEquals(CapabilityOutcomeTone.Warning, CapabilityMessages.toneOf(outcome))
        assertEquals("试过了，没成", CapabilityMessages.titleOf(outcome))
    }

    @Test
    fun `两种没做成的标题必须不同`() {
        // ★ 这一条是上一组的"反向保险"：合并成一句"操作失败"时，
        //   上面两条里至少有一条会红，但那条红的归因不如这一条直接。
        assertNotEquals(
            CapabilityMessages.titleOf(
                executed(ChannelResult.Unavailable(ChannelUnavailableReason.PORT_NOT_CONFIGURED)),
            ),
            CapabilityMessages.titleOf(executed(ChannelResult.Failed(reason = "x"))),
        )
    }

    @Test
    fun `Unavailable 且没有补充说明时，用原因码自带的说法`() {
        // 不允许出现空正文 —— 一个只有标题的横幅等于什么都没说。
        val detail = CapabilityMessages.detailOf(
            executed(ChannelResult.Unavailable(ChannelUnavailableReason.SERVICE_NOT_RUNNING)),
        )

        assertEquals(ChannelUnavailableReason.SERVICE_NOT_RUNNING.displayName, detail)
        assertTrue(detail.isNotBlank())
    }

    @Test
    fun `shell 失败的退出码会带上`() {
        val detail = CapabilityMessages.detailOf(
            executed(ChannelResult.Failed(reason = "命令返回非零", exitCode = 2)),
        )
        assertTrue("退出码是排查的起点：$detail", detail.contains("2"))
    }

    // ── ★★ 「还没放行」不是失败 ───────────────────────────────────

    @Test
    fun `还没放行是 Info 而不是 Danger`() {
        // ★★ 本文件最要紧的一条。
        //    NOT_GRANTED 是**唯一一个用户操作能改变的原因码** ——
        //    把它标成红色失败，用户会以为功能坏了，而他要做的
        //    只是在界面上点一下「放行」。
        val outcome = CapabilityOutcome.Blocked(
            CapabilityVerdict.Blocked(
                reason = BlockReason.NOT_GRANTED,
                userMessage = "「设置屏幕亮度。」还没有被你放行，所以我没有执行。",
                canFallbackToManual = true,
            ),
        )

        assertEquals(CapabilityOutcomeTone.Info, CapabilityMessages.toneOf(outcome))
        assertEquals("还没有放行这一条", CapabilityMessages.titleOf(outcome))
    }

    @Test
    fun `还没放行的下一步是「放行」，不是「重试」`() {
        val outcome = CapabilityOutcome.Blocked(
            CapabilityVerdict.Blocked(
                reason = BlockReason.NOT_GRANTED,
                userMessage = "…",
                canFallbackToManual = true,
            ),
        )

        assertEquals(NextStep.GRANT, CapabilityMessages.nextStepOf(outcome))
    }

    @Test
    fun `硬拒绝是 Danger，而且没有下一步`() {
        // 命中硬拒绝清单 / 参数非法 / 插件越界 —— 用户做什么都改不了。
        // 给一个按钮等于让他白点。
        val outcome = CapabilityOutcome.Blocked(
            CapabilityVerdict.Blocked(
                reason = BlockReason.DENIED_BY_POLICY,
                userMessage = "「…」属于固定不允许的操作。",
                canFallbackToManual = false,
            ),
        )

        assertEquals(CapabilityOutcomeTone.Danger, CapabilityMessages.toneOf(outcome))
        assertEquals(NextStep.NONE, CapabilityMessages.nextStepOf(outcome))
    }

    @Test
    fun `参数非法的正文取自判定层，含具体问题`() {
        // ⚠️ 不在这一层重写文案 —— 判定层那句话里有"哪个参数、哪个值、
        //    允许范围是什么"，重写一遍必然丢信息。
        val message = "这次调用的参数不合法，所以没有执行：「level」的值「999」超出范围（1 到 255）"
        val outcome = CapabilityOutcome.Blocked(
            CapabilityVerdict.Blocked(
                reason = BlockReason.INVALID_ARGS,
                userMessage = message,
                canFallbackToManual = false,
            ),
        )

        assertEquals(message, CapabilityMessages.detailOf(outcome))
        assertEquals(CapabilityOutcomeTone.Danger, CapabilityMessages.toneOf(outcome))
    }

    // ── 确认与下一步 ──────────────────────────────────────────────

    @Test
    fun `需要确认是 Info，下一步是「确认」`() {
        val outcome = CapabilityOutcome.NeedsConfirmation(
            CapabilityVerdict.RequireConfirmation(
                capability = catalog.byId(SAFE_ID)!!,
                execution = CommandPlanner.plan(catalog.byId(SAFE_ID)!!, argsOf(SAFE_ID)),
                reason = ConfirmReason.GUARDED_CAPABILITY,
                userMessage = "要执行「…」。这一次具体是：…",
            ),
        )

        assertEquals(CapabilityOutcomeTone.Info, CapabilityMessages.toneOf(outcome))
        assertEquals("需要你确认", CapabilityMessages.titleOf(outcome))
        assertEquals(NextStep.CONFIRM, CapabilityMessages.nextStepOf(outcome))
    }

    @Test
    fun `成功的下一步是无事可做`() {
        assertEquals(
            NextStep.NONE,
            CapabilityMessages.nextStepOf(executed(ChannelResult.Succeeded())),
        )
    }

    @Test
    fun `「去配置」与「重试」是两种不同的下一步`() {
        // ★ 合成一个"重试"按钮的后果：用户在权限没给的情况下反复点，
        //   每次都得到同一句话，而他该做的是去跑那条命令。
        assertEquals(
            NextStep.GO_CONFIGURE,
            CapabilityMessages.nextStepOf(
                executed(ChannelResult.Unavailable(ChannelUnavailableReason.PERMISSION_DENIED)),
            ),
        )
        assertEquals(
            NextStep.RETRY,
            CapabilityMessages.nextStepOf(executed(ChannelResult.Failed(reason = "x"))),
        )
    }

    @Test
    fun `四种没做成的情形一共用了三种色调`() {
        // 防止有人图省事全标红。红色只有一个语义：**用户做什么都没用**。
        val tones = setOf(
            CapabilityMessages.toneOf(executed(ChannelResult.Succeeded())),
            CapabilityMessages.toneOf(
                executed(ChannelResult.Unavailable(ChannelUnavailableReason.PORT_NOT_CONFIGURED)),
            ),
            CapabilityMessages.toneOf(executed(ChannelResult.Failed(reason = "x"))),
            CapabilityMessages.toneOf(
                CapabilityOutcome.Blocked(
                    CapabilityVerdict.Blocked(BlockReason.NOT_GRANTED, "…", true),
                ),
            ),
        )

        assertEquals(
            setOf(
                CapabilityOutcomeTone.Success,
                CapabilityOutcomeTone.Warning,
                CapabilityOutcomeTone.Info,
            ),
            tones,
        )
    }

    // ── ★ Executed 里不能装「要确认」 ──────────────────────────────

    @Test
    fun `Executed 里装了 NeedsConfirmation 时当场抛错，而不是兜一句假话`() {
        // ⚠️⚠️ 这不是"防御性编程"，而是一个**真实会发生的**错法：
        //    `CapabilityRuntime` 里那个 `when` 如果忘了给 `NeedsConfirmation`
        //    单独一支，它就会**静默落进 `Executed`** ——
        //    界面上表现是"已经做完了"，而用户点的那一下确认框**根本没被回答**，
        //    操作也没发生，日志里连一条记录都没有。
        //
        //    四个入口各错一种（色调不对 / 说假话 / 编结果 / 不给按钮），
        //    而**没有一个会报错**。所以四个都要炸 ——
        //    少炸一个，就少一条发现路径。
        val outcome = executed(
            ChannelResult.NeedsConfirmation(
                userMessage = "要删掉「工资单.xlsx」，确定吗？",
                reason = "DELETE_FILE",
                timeoutMs = 30_000,
            ),
        )

        assertThrows(IllegalStateException::class.java) { CapabilityMessages.toneOf(outcome) }
        assertThrows(IllegalStateException::class.java) { CapabilityMessages.titleOf(outcome) }
        assertThrows(IllegalStateException::class.java) { CapabilityMessages.detailOf(outcome) }
        assertThrows(IllegalStateException::class.java) { CapabilityMessages.nextStepOf(outcome) }
    }

    @Test
    fun `「要确认」的正确走法是顶层结局，不是 Executed`() {
        // 对照组：走对了的时候，四个入口给出的是**中性**的答案 ——
        // 不红、不绿、不报错，且有一个"去回答"的入口。
        // 这一条与上一条合起来才说明"炸"是**因为走错了路**，
        // 而不是因为这个分支根本没法处理。
        val outcome = CapabilityOutcome.NeedsConfirmation(
            CapabilityVerdict.RequireConfirmation(
                capability = catalog.byId(SAFE_ID)!!,
                execution = CommandPlanner.plan(catalog.byId(SAFE_ID)!!, argsOf(SAFE_ID)),
                reason = ConfirmReason.OPERATION_AFFECTS_FILES,
                userMessage = "要删掉「工资单.xlsx」，确定吗？",
            ),
        )

        assertEquals(CapabilityOutcomeTone.Info, CapabilityMessages.toneOf(outcome))
        assertEquals("需要你确认", CapabilityMessages.titleOf(outcome))
        assertEquals("要删掉「工资单.xlsx」，确定吗？", CapabilityMessages.detailOf(outcome))
        assertEquals(NextStep.CONFIRM, CapabilityMessages.nextStepOf(outcome))
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private fun executed(result: ChannelResult) = CapabilityOutcome.Executed(
        verdict = CapabilityVerdict.Allowed(
            capability = catalog.byId(SAFE_ID)!!,
            execution = CommandPlanner.plan(catalog.byId(SAFE_ID)!!, argsOf(SAFE_ID)),
            wasConfirmed = false,
        ),
        result = result,
    )

    private fun argsOf(id: String): Map<String, String> =
        catalog.byId(id)!!.params.associate { spec ->
            spec.name to when (spec) {
                is ParamSpec.Choice -> spec.values.first()
                is ParamSpec.IntIn -> spec.range.first.toString()
                else -> "1"
            }
        }

    private val catalog = CapabilityCatalog()

    private companion object {
        /** 一个 SAFE 的、参数固定的能力 —— 用它把测试的注意力留在消息映射上。 */
        const val SAFE_ID = "display.auto_rotate"
    }
}
