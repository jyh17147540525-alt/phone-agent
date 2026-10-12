package com.pocketagent.assistant

import com.pocketagent.agentlogic.PageKind
import com.pocketagent.agentlogic.UploadContext
import com.pocketagent.agentlogic.UploadPurpose
import com.pocketagent.agentlogic.UploadRequest
import com.pocketagent.core.common.AtomicTextFile
import com.pocketagent.mcp.OffloadDraftOutcome
import com.pocketagent.mcp.OffloadDraftPort
import com.pocketagent.mcp.ScreenCaptureOutcome
import com.pocketagent.mcp.ScreenDerive
import com.pocketagent.mcp.ScreenReaderPort
import com.pocketagent.mcp.ScreenSafetyOutcome
import com.pocketagent.mcp.ScreenSafetyPort
import com.pocketagent.mcp.ScreenSafetyQuery
import com.pocketagent.memorylogic.OffloadBlobStore
import com.pocketagent.memorylogic.OffloadDraft
import com.pocketagent.memorylogic.OffloadKind
import com.pocketagent.memorylogic.OffloadPayload
import com.pocketagent.memorylogic.OffloadRef
import java.io.File

/**
 * 记忆方向在 `:app` 侧的**全部接线**：落盘出口 + draft 构造端口。
 *
 * ═══════════════════════════════════════════════════════════════
 *  与 McpBridge.kt / ActionPorts.kt / PersonaPorts.kt 的关系
 * ═══════════════════════════════════════════════════════════════
 *
 * | 文件 | 方向 | 端口 |
 * |---|---|---|
 * | `McpBridge.kt`   | 读屏   | ScreenReaderPort / ScreenSafetyPort |
 * | `ActionPorts.kt` | 执行   | ActionSafetyPort / ActionDispatchPort |
 * | `PersonaPorts.kt`| 人格   | PersonaBriefPort |
 * | 本文件            | 记忆   | OffloadBlobStore / OffloadDraftPort |
 *
 * 四条纪律完全相同：**只搬运、不判定** ——
 * 判定在 `:memorylogic`（闸门与编码）与 `:mcp`（编排），都有离线测试；
 * 本文件里任何"顺手过滤一下"都会制造一份规则之外的影子规则。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它是 `:memorylogic` 的第一个消费方（2026-10-12）
 * ═══════════════════════════════════════════════════════════════
 *
 * 在此之前，`:memorylogic` 与 `:personalogic` 一样**没有任何模块引用** ——
 * 而它扛着一条红线：「卸载前必须过 `PrivacyFilter`，顺序不可颠倒」（架构 §8.2-2）。
 * 那条红线由 `OffloadPayload` 的**私有构造函数**保证（"不过关卡直接写"编译不过），
 * 但代码从没进过包 ⇒ **它从未被执行过一次**。
 * **「红线没跑过」与「红线没生效」在真机上长得一模一样。**
 */

/**
 * 落盘出口 —— 应用私有目录下的一个文件树。
 *
 * 路径直接照 [OffloadRef.relativePath]（`<taskId>/<kind>/<id>.json`），
 * 与本模块的契约一一对应，不做任何二次编排。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么是**应用私有目录**而不是设计稿写的 SAF 目录
 * ═══════════════════════════════════════════════════════════════
 *
 * 设计稿 §2.3 的原话是"大内容本身走 `OffloadBlobStore`（SAF 目录）"。
 * 这一版**刻意没走 SAF**，理由有两条：
 *
 * 1. **SAF 需要用户先授一个目录**（一次性交互）。当前这个能力刚接上第一个消费方，
 *    还没有任何界面能让用户理解"为什么要给助理一个目录"。
 *    在用户还不知道这东西是干嘛的时候要权限，只会训练他闭眼点同意。
 * 2. **应用私有目录其实更严**：其它 App 读不到它，用户也不能通过文件管理器翻到。
 *    SAF 的好处是**用户可见可删**（透明度），代价是暴露面更大。
 *
 * ⇒ 这是一次**明确的取舍**，不是省事。它记在协作信里等 `:app` 侧的
 *    「记忆管理页」（让用户看见并删除卸载内容）落地时一并重新评估 ——
 *    那才是 SAF 真正值钱的地方。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ `payload.plan` 在这里**做不了任何事**（这是刻意的，且必须说清楚）
 * ═══════════════════════════════════════════════════════════════
 *
 * [OffloadBlobStore.write] 的契约说"实现方拿到 plan 之后负责在真正落盘前执行裁剪/遮蔽"。
 * 但那个 plan 里的 [com.pocketagent.agentlogic.RedactRegion] 是**原图像素坐标** ——
 * 而这里落盘的是**一段不透明的文本**，里面没有任何区域信息，区域型遮蔽**无法执行**。
 *
 * 所以本实现**不做、也不假装做**遮蔽。真正保护正文的是另外两件事：
 * 1. 敏感页判定发生在更上游（`AndroidOffloadDraftPort`），敏感页根本到不了这里；
 * 2. 正文的唯一生产者 `screen_read` 在**序列化时**就已经照它自己的 plan 遮过输入框了。
 *
 * ⚠️ 若将来出现"带区域信息的卸载"（比如截图 OCR 的中间结果），
 *    这个实现就必须换 —— 换的信号是 `OffloadPayload` 里出现像素/区域。
 *    现在**不预支**：在没有第二个来源时设计区域遮蔽，等于猜一个还没问出口的需求。
 */
class FileOffloadBlobStore(
    /** 根目录。生产用 `context.filesDir/offload`。 */
    private val root: File,
) : OffloadBlobStore {

    override fun write(payload: OffloadPayload) {
        // ⚠️ 用 AtomicTextFile（先写临时文件再改名）而不是直接 writeText：
        //    进程在任何一刻消失都会留下**看起来正常的半截文件** ——
        //    而 recall 的指纹校验会把那种文件报成 Corrupted，
        //    用户看到的解释会是"内容损坏"，而不是"上次没写完"。
        //    原子写把这一类故障从根上消掉（同 `:core:common` 的既有理由）。
        AtomicTextFile.write(fileFor(payload.ref), payload.encoded)
    }

    override fun read(ref: OffloadRef): String? {
        val f = fileFor(ref)
        // ⚠️ 读失败返回 null（契约要求）—— 不抛异常。调用方（ContextOffloader）
        //    会把 null 报成 RecallOutcome.Missing，那是"文件不在"的正确说法。
        return runCatching { if (f.isFile) f.readText() else null }.getOrNull()
    }

    private fun fileFor(ref: OffloadRef): File = File(root, ref.relativePath)
}

/**
 * 构造 `OffloadDraft` 的端口实现 —— 卸载路径上**唯一**需要 Android 的地方。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 顺序：敏感判定在构造 draft **之前**
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 *   ① capture（读当前屏幕）
 *   ② ScreenSafetyPort 敏感判定 —— 一票否决，命中就不构造 draft
 *   ③ 构造 OffloadDraft（页面类型由 ② 的结论决定）
 * ```
 *
 * 与 `ScreenReadTool` 完全同构，理由也一样：把 ① 放到最后，
 * 敏感页的文本**已经在内存里流转过一遍**了，而且没有任何一处会报错。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 一个开放问题：`pageKind` 取自**当前**屏幕，而正文可能来自**更早**的一次读取
 * ═══════════════════════════════════════════════════════════════
 *
 * 两者在时间上可以不一致。当前的处置是**保守方向**的，理由如下 ——
 * 我认为它成立，但它值得被质疑（已写进协作信）：
 *
 * 本桥里"大正文"的唯一生产者就是 `screen_read`，而它在**产出时**已经拒过敏感页 ——
 * 所以正文本身不可能含支付页内容。剩下的不一致只可能来自模型自己编的正文，
 * 而那类正文没有"泄漏用户屏幕"的问题。
 *
 * ⚠️ 若将来出现第二个正文来源（截图 OCR / 剪贴板 / 其它 App 的内容），
 *    这条推理**不再成立** —— 那时需要给 `OffloadDraft` 加**来源标记**。
 *    现在不加：在没有第二个来源时定义来源标记的格式，等于猜一个还没问出口的需求。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 输入框区域**刻意传空**（与 `screen_read` 不同，理由如下）
 * ═══════════════════════════════════════════════════════════════
 *
 * `screen_read` 会把输入框区域传进 `UploadRequest`，因为它的**序列化步骤**
 * 能照着 plan 逐节点遮蔽 —— 那里有节点结构，遮得动。
 *
 * 本端口拿到的是**一段不透明字符串**，没有节点、没有区域。把区域传进去只会让
 * `plan.mask` 非空、看起来"遮过了"，而 `FileOffloadBlobStore` 其实什么都做不了 ——
 * 那正是本项目头号 bug 形态「**安静地少做一件事**」。
 *
 * ⇒ **宁可传空、并在注释里说清"这里没有做区域遮蔽"**，也不留一个假的 plan。
 *    正文的安全性由上游两道保证（敏感页判定 + `screen_read` 序列化时的遮蔽）。
 */
class AndroidOffloadDraftPort(
    private val reader: ScreenReaderPort,
    private val safety: ScreenSafetyPort,
) : OffloadDraftPort {

    override suspend fun draft(
        kind: OffloadKind,
        taskId: String,
        body: String,
    ): OffloadDraftOutcome {
        // ── ① 读当前屏幕 ────────────────────────────────────────
        val capture = when (val outcome = reader.capture()) {
            is ScreenCaptureOutcome.Ok -> outcome.dto

            is ScreenCaptureOutcome.Unavailable -> return OffloadDraftOutcome.Refused(
                "读屏能力当前不可用（${outcome.reason}），因此无法确认当前页面是否敏感 —— " +
                    "按保守策略，本次卸载已中止，一个字节都没有写。" +
                    "请确认已在系统设置中为本应用开启「辅助功能（无障碍）」，然后重试。",
            )

            is ScreenCaptureOutcome.Failed -> return OffloadDraftOutcome.Refused(
                "读屏失败（${outcome.reason}），无法确认当前页面是否敏感 —— " +
                    "按保守策略，本次卸载已中止，一个字节都没有写。可以重试一次。",
            )
        }

        // ── ② 敏感判定：一票否决（最高优先级，不看其它任何条件）──
        val verdict = safety.review(
            ScreenSafetyQuery(
                packageName = capture.packageName,
                visibleTexts = ScreenDerive.visibleTexts(capture),
                fields = ScreenDerive.inputFields(capture),
            ),
        )
        if (verdict is ScreenSafetyOutcome.Block) {
            return OffloadDraftOutcome.Refused(
                "${verdict.userMessage}（这是安全策略的最终结论，请不要重试；" +
                    "请把上面的原因如实告诉用户。卸载已中止，一个字节都没有写。）",
            )
        }

        // ── ③ 构造 draft。走到这里一定是普通页面（敏感页已在 ② 拦下）──
        return OffloadDraftOutcome.Ready(
            OffloadDraft(
                kind = kind,
                taskId = taskId,
                body = body,
                request = UploadRequest(
                    context = UploadContext(
                        // ⚠️ 与 ScreenReadTool 同一个口径：这条数据是发给 dsh 的，
                        //    审计/用量汇总时"dsh 卸了多少内容"要能一处对上。
                        providerId = PROVIDER_ID,
                        purpose = UploadPurpose.TASK_REASONING,
                    ),
                    // 文本路径：无障碍树没有像素，系统 UI 区域无从谈起。
                    requiresScreenshot = false,
                    pageKind = PageKind.NORMAL,
                    // ⚠️ 刻意传空，理由见类注释。**不要顺手把 inputFields 的区域填进来。**
                    inputFieldRegions = emptyList(),
                ),
            ),
        )
    }

    private companion object {
        /** 与 `ScreenReadTool.PROVIDER_ID` 同一个口径。 */
        const val PROVIDER_ID = "dsh"
    }
}
