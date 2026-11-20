package com.pocketagent.assistant

import com.pocketagent.voicelogic.VoiceNote
import java.io.File
import java.security.MessageDigest

/**
 * **语音条的缓存** —— 一条语音只合成一次。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三条理由
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **钱**：合成是按字符计费的。同一句听第二遍还再付一次，用户会觉得被坑。
 * 2. **时长**：条子上要写真实时长（[VoiceNote.pcmDurationMs]），
 *    而真实时长只有拿到字节之后才知道。
 * 3. **点了就响**：缓存命中时是"读文件 + 播"，不是"等网络"。
 *
 * ⚠️ 文件落在 `filesDir/voice_notes/`（应用私有），不写外部存储：
 *   说话内容比文件更敏感，没必要让别的应用读到。
 */
class VoiceNoteStore(private val dir: File) {

    init {
        runCatching { dir.mkdirs() }
    }

    /** 内容 + 音色 + 厂商一起做 key：换了音色必须重新合成。 */
    fun keyOf(text: String, voiceId: String?, providerId: String?): String {
        val raw = "${providerId.orEmpty()}|${voiceId.orEmpty()}|$text"
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun fileOf(key: String): File = File(dir, "$key.pcm")

    fun load(key: String): ByteArray? {
        val f = fileOf(key)
        if (!f.isFile || f.length() <= 0L) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    fun save(key: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        runCatching {
            // 先写临时文件再改名：中途被杀不会留下"半条语音"（听起来像卡带）。
            val tmp = File(dir, "$key.pcm.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(fileOf(key))
        }
    }

    /** 已缓存就给出真实时长（毫秒），没缓存返回 null（界面先显示估算值）。 */
    fun cachedDurationMs(key: String): Int? {
        val f = fileOf(key)
        if (!f.isFile || f.length() <= 0L) return null
        return VoiceNote.pcmDurationMs(f.length().toInt())
    }

    /** 一条回复切成几条、每条说什么。放在这里是为了让界面与合成共用同一份切法。 */
    fun plan(text: String): List<VoiceNote.Clip> = VoiceNote.plan(text)

    /** 清掉所有缓存（设置页"清空语音缓存"用）。 */
    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }
}
