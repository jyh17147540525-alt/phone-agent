package com.pocketagent.memorylogic

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 解码结论。失败必须带原因 —— 见 [OffloadCodec.decode]。 */
sealed interface DecodeResult {

    data class Ok(
        val kind: OffloadKind,
        val taskId: String,
        val body: String,
        val createdAt: Long,
    ) : DecodeResult

    data class Failed(val reason: String) : DecodeResult
}

/**
 * 卸载内容的编码 / 解码。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么不是"直接把正文写进文件"
 * ═══════════════════════════════════════════════════════════════
 *
 * 落盘的东西要能被**将来某个版本的自己**读回来。裸写正文有三个后果：
 *
 * 1. 无法判断"这个文件是什么"—— 恢复时拿到一段文本，不知道它是工具结果
 *    还是对话正文，只能靠目录名猜。
 * 2. 无法演进 —— 哪天要在旁边加一个"摘要"字段，老文件没有它就没人知道
 *    该按老格式还是新格式读。
 * 3. 无法区分"内容为空"和"文件坏了"。
 *
 * 所以带一层自描述外壳：版本号 + 类别 + 任务 + 正文 + 时间。
 *
 * ⚠️ **往返必须闭合**（[ContextOffloader.recall] 会真的验一遍）：
 * `encode(decode(x).body) == x`。不闭合意味着用户看到的"恢复内容"
 * 与当初存下的不是同一份 —— 这比恢复失败更糟，因为它看起来是成功的。
 */
object OffloadCodec {

    /**
     * 外壳版本。
     *
     * ⚠️ 读到不认识的版本要**报损坏**，不能"尽力解析"：一个擅自被新版本
     * 解释过的旧文件，可能把某个字段当成了别的东西，而这种错误是静默的。
     */
    const val VERSION = 1

    fun encode(kind: OffloadKind, taskId: String, body: String, createdAt: Long): String =
        JSON.encodeToString(
            OffloadEnvelope.serializer(),
            OffloadEnvelope(v = VERSION, kind = kind.segment, taskId = taskId, body = body, at = createdAt),
        )

    fun decode(text: String): DecodeResult {
        val envelope = try {
            JSON.decodeFromString(OffloadEnvelope.serializer(), text)
        } catch (e: SerializationException) {
            return DecodeResult.Failed("卸载内容外壳无法解析（文件可能被截断或不是本格式）")
        }
        if (envelope.v != VERSION) {
            return DecodeResult.Failed("卸载内容版本不认识：文件为 ${envelope.v}，本版本只认 $VERSION")
        }
        val kind = OffloadKind.bySegment(envelope.kind)
            ?: return DecodeResult.Failed("卸载内容类别不认识：${envelope.kind}")
        if (envelope.taskId.isBlank()) {
            return DecodeResult.Failed("卸载内容缺少 taskId")
        }
        return DecodeResult.Ok(kind = kind, taskId = envelope.taskId, body = envelope.body, createdAt = envelope.at)
    }

    /** 完整性指纹：对**落盘的那段文本**算，不对正文算（见 [OffloadRef.sha256]）。 */
    fun sha256(text: String): String = Hashing.sha256Hex(text)

    /** 落盘内容的 UTF-8 字节数。 */
    fun byteSize(text: String): Int = text.toByteArray(Charsets.UTF_8).size
}

/**
 * 落盘外壳。
 *
 * ⚠️ 字段名刻意短（`v` / `at`）：它会跟着**每一份**被卸载的大内容写一遍，
 * 而大内容可能有几万份。名字长了，光字段名就够再卸载一次。
 *
 * `ignoreUnknownKeys = true` 是为了**向前兼容读取**：新版本加了字段之后，
 * 老版本读到它不会炸。反过来（遇到不认识的版本号）必须拒绝，见 [OffloadCodec.decode]。
 */
@Serializable
private data class OffloadEnvelope(
    val v: Int,
    val kind: String,
    val taskId: String,
    val body: String,
    val at: Long,
)

private val JSON = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}