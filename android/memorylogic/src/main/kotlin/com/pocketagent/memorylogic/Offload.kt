package com.pocketagent.memorylogic

import com.pocketagent.agentlogic.FilterOutcome
import com.pocketagent.agentlogic.PrivacyFilter
import com.pocketagent.agentlogic.RedactionPlan
import com.pocketagent.agentlogic.UploadRequest

/**
 * 卸载内容的类别 —— 决定落盘的子目录，以及恢复时"这是什么"。
 *
 * 三类的共同点是**体积大**：工具原始结果、截图说明、超长对话正文。
 * 它们进上下文只会线性推高 token，而绝大部分内容在当轮之后再也用不到。
 */
enum class OffloadKind(val segment: String, val label: String) {
    TOOL_RESULT("tool-result", "工具原始结果"),
    SCREENSHOT("screenshot", "屏幕截图"),
    TURN_BODY("turn-body", "对话长正文"),
    ;

    companion object {
        /** 按落盘时的目录段名反查。⚠️ 不认识返回 null，让调用方报"损坏"而不是猜。 */
        fun bySegment(raw: String): OffloadKind? = entries.firstOrNull { it.segment == raw }
    }
}

/**
 * 一份已卸载内容的**引用** —— 它是回到原文的唯一钥匙。
 *
 * ⚠️ 刻意**不含正文**：引用会进上下文、会进日志、会被助理说出口。
 * 正文留在 [OffloadBlobStore] 里，通过 [ContextOffloader.recall] 取回。
 *
 * [sha256] / [bytes] 不是装饰：恢复时先对指纹，对不上就报
 * [RecallOutcome.Corrupted]。「读到半截文件当成功」正是本项目头号 bug 形态。
 */
data class OffloadRef(
    val id: String,
    val kind: OffloadKind,
    val taskId: String,
    /** 落盘内容的 UTF-8 字节数 */
    val bytes: Int,
    /** 落盘内容的 SHA-256（小写十六进制，64 位） */
    val sha256: String,
    val createdAt: Long,
) {
    init {
        require(id.startsWith(ID_PREFIX)) { "OffloadRef.id 必须以 $ID_PREFIX 开头：$id" }
        require(taskId.isNotBlank()) { "OffloadRef.taskId 不能为空 —— 恢复时要按任务找回去" }
        require(bytes > 0) { "bytes 必须为正：$bytes" }
        require(sha256.length == SHA256_HEX_LENGTH) { "sha256 必须是 $SHA256_HEX_LENGTH 位十六进制" }
    }

    /**
     * 落盘相对路径：`<taskId>/<kind>/<id>.json`。
     *
     * ⚠️ 用的是 [id]（内容指纹派生的短标识）而不是序号：**相同内容重复卸载会落到
     * 同一个文件**，于是重试天然幂等 —— 不会在用户存储里堆出一串一模一样的大文件。
     */
    val relativePath: String get() = "$taskId/${kind.segment}/$id.json"

    companion object {
        const val ID_PREFIX = "of-"

        /** 20 位十六进制 = 80 bit，与 [Atom.ID_LENGTH] 同一口径 */
        const val ID_LENGTH = 20

        const val SHA256_HEX_LENGTH = 64

        /**
         * 引用 id —— 由**内容本身**（类别 + 任务 + 正文）派生，**不含时间**。
         *
         * ⚠️ 刻意**不**拿编码后的文本去算：那里面带 `at`（卸载时刻），
         * 于是同一份内容在两次重试里会算出两个 id、落到两个文件 ——
         * 「重试幂等」就此失效，用户存储里会堆出一串一模一样的大文件。
         * **时间属于引用的元数据，不属于它的身份。**
         *
         * 含 [taskId] 是因为落盘路径本来就按任务分目录；同一份内容属于两个任务时
         * 各自留一份是对的，不该互相覆盖。
         */
        fun idOf(kind: OffloadKind, taskId: String, body: String): String =
            ID_PREFIX + Hashing.shortHex("${kind.segment}\u0000$taskId\u0000$body", ID_LENGTH)
    }
}

/**
 * **待**卸载的内容 —— 还没过隐私关卡，**不可直接落盘**。
 *
 * [request] 由 Android 层构造（页面类型、输入框区域、是否截图……都是它才量得出来的），
 * 记忆层只负责把它交给关卡。两者的分工就是「识别在 Android 层、决策在纯层」。
 */
data class OffloadDraft(
    val kind: OffloadKind,
    val taskId: String,
    val body: String,
    val request: UploadRequest,
) {
    init {
        require(taskId.isNotBlank()) { "OffloadDraft.taskId 不能为空" }
        require(body.isNotEmpty()) { "没有内容可卸载 —— 空正文不该走到这里" }
    }
}

/**
 * ★★ **已过隐私关卡**的内容。**构造函数私有**，只能由本模块产出。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这个类型为什么长这样
 * ═══════════════════════════════════════════════════════════════
 *
 * 红线 §8.2-2「卸载前必须过 `PrivacyFilter`，顺序不可颠倒」如果只写成一句
 * 注释 + 一次调用，它迟早会被绕过 —— 某天有人为了"补一个漏掉的分支"，
 * 直接在另一处 `store.write(...)` 就完事了。
 *
 * 所以这里把顺序做进**类型**：
 *
 * - [OffloadPayload] 只有私有构造函数，唯一的工厂 [cleared] 是 `internal`，
 *   而它**只被 [ContextOffloader.offload] 调用**，且调用点在任何写入之前。
 * - [OffloadBlobStore.write] 的参数类型就是它 —— 于是"不过关卡直接写"
 *   在模块外部**写不出来**（编译不过），而不是"不推荐"。
 *
 * [plan] 是关卡给出的遮蔽指令（要求 Android 层在落盘前裁剪/遮蔽哪些区域）。
 * 文本类卸载时它通常是 `RedactionPlan.isNoOp` —— 那也是**明确的结论**
 * "检查过且干净"，不是"没检查"。
 */
class OffloadPayload private constructor(
    val ref: OffloadRef,
    /** 编码后的落盘内容（带版本外壳，见 [OffloadCodec]） */
    val encoded: String,
    /** 关卡给出的遮蔽指令 —— 落盘前由 Android 层执行 */
    val plan: RedactionPlan,
) {
    internal companion object {
        internal fun cleared(ref: OffloadRef, encoded: String, plan: RedactionPlan): OffloadPayload =
            OffloadPayload(ref = ref, encoded = encoded, plan = plan)
    }
}

/** 一次卸载的结论。 */
sealed interface OffloadOutcome {

    /** 已落盘。[ref] 是回到原文的钥匙（可以进上下文）。 */
    data class Stored(val ref: OffloadRef) : OffloadOutcome

    /**
     * **被隐私关卡否决，什么都没写。**
     *
     * [reason] 必须能直接展示给用户（"执行过程可见"原则）——
     * 用户需要知道为什么这一步没继续。
     */
    data class Dropped(val reason: String) : OffloadOutcome
}

/** 一次恢复的结论。三分支缺一不可，任何一支缺失都会变成"静默的少做一件事"。 */
sealed interface RecallOutcome {

    /** 取回成功。[body] 与当初卸载的内容**逐字节一致**。 */
    data class Restored(val body: String) : RecallOutcome

    /** 存储里没有这个引用 —— 文件被用户删了，或者从来没写成功。 */
    data class Missing(val ref: OffloadRef) : RecallOutcome

    /** 内容对不上（截断 / 被改写 / 版本不认识）。**绝不能当成功返回。** */
    data class Corrupted(val ref: OffloadRef, val reason: String) : RecallOutcome
}

/**
 * 隐私关卡的**最小视角** —— 记忆层只知道"要过一次关卡"，不关心它怎么判。
 *
 * ⚠️ 为什么不直接依赖 `PrivacyFilter` 的具体实例：那样卸载路径就没法被离线
 * 观测"关卡到底有没有跑、跑在写入之前还是之后"。做成端口之后，
 * 测试可以塞一个会记账的关卡，把**顺序**钉死（见 `OffloadPrivacyOrderTest`）。
 *
 * 生产环境的接线只有一条：[PrivacyFilter.asPrivacyGate]。
 */
fun interface PrivacyGate {
    fun review(request: UploadRequest): FilterOutcome
}

/** 把真正的 `PrivacyFilter` 接到记忆层的关卡口上。 */
fun PrivacyFilter.asPrivacyGate(): PrivacyGate = PrivacyGate { review(it) }

/**
 * 落盘出口。实现在 Android 侧（SAF 目录）；本模块只声明契约。
 *
 * ⚠️ [write] 的参数是 [OffloadPayload]（构造私有）—— 这不是风格，
 * 是让"绕过关卡直接写"在类型层面不可能。实现方拿到 [OffloadPayload.plan]
 * 之后，负责在真正落盘前执行裁剪/遮蔽。
 */
interface OffloadBlobStore {

    fun write(payload: OffloadPayload)

    /** 返回**落盘的原始文本**（未解码），读不到返回 null。 */
    fun read(ref: OffloadRef): String?
}

/**
 * 降级策略：多大算"该卸载"。
 *
 * ★ 这是"保护用户 token"（需求③）里**主动降耗**的那一半 ——
 * 与 `AgentBudget` 的被动熔断互补：预算熔断防的是任务跑飞，
 * 卸载防的是上下文线性膨胀（见架构文档 §2.3）。
 */
object OffloadPolicy {

    /**
     * 默认阈值：单条正文超过 2000 字节（约 660 个汉字）就值得卸载。
     *
     * ⚠️ 按**字节**而不是字符数 —— 一个汉字 3 字节，按字符算会让中英文阈值差三倍，
     * 而用户完全看不出为什么同样长的一段话，英文的留下、中文的被搬走。
     */
    const val DEFAULT_THRESHOLD_BYTES = 2_000

    fun needsOffload(text: String, thresholdBytes: Int = DEFAULT_THRESHOLD_BYTES): Boolean {
        require(thresholdBytes >= 0) { "thresholdBytes 不能为负：$thresholdBytes" }
        return text.toByteArray(Charsets.UTF_8).size > thresholdBytes
    }

    /**
     * 进上下文的占位索引 —— 卸载之后，上下文里放的就是这一行。
     *
     * ⚠️ **绝不含正文**：它由 [OffloadRef] 拼出来，而引用里本来就没有正文。
     * 任何"顺手带上前 50 个字方便模型理解"的想法都会让卸载失效 ——
     * 那 50 个字 × 每轮一次，就是没卸载。
     */
    fun placeholderFor(ref: OffloadRef): String =
        "[已卸载「${ref.kind.label}」· ${ref.bytes} 字节 · ${ref.id}]"
}