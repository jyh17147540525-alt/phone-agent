package com.pocketagent.data

import android.content.Context
import com.pocketagent.voicelogic.VoiceTagSet
import com.pocketagent.voicelogic.VoiceTags
import timber.log.Timber

/**
 * 语音标签库的落盘与拉取。
 *
 * 每个厂商一套：换 Key 就换库，互不串味。
 * 拉不到就用内置兜底 —— 宁可少几个标签，也不能把编造的标签写进提示词。
 */
class VoiceTagStore(context: Context) {

    private val prefs = context.getSharedPreferences("voice_tags_v1", Context.MODE_PRIVATE)

    fun get(vendorId: String): VoiceTagSet? {
        val raw = prefs.getString(vendorId, null) ?: return null
        val parts = raw.split("\n", limit = 3)
        val tags = parts.firstOrNull()?.split("\u001F")?.filter { it.isNotBlank() }.orEmpty()
        val at = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val source = parts.getOrNull(2).orEmpty()
        return VoiceTagSet(vendorId, tags, at, source)
    }

    fun save(set: VoiceTagSet) {
        val value = set.tags.joinToString("\u001F") + "\n" + set.fetchedAtMs + "\n" + set.source
        prefs.edit().putString(set.vendorId, value).apply()
    }

    /** 当前生效的库：磁盘上的优先，没有就用内置。 */
    fun effective(vendorId: String): VoiceTagSet =
        get(vendorId) ?: VoiceTags.builtinFor(vendorId)

    /**
     * 联网把官方标签拉下来。失败不抛，只留痕。
     * @return 实际生效的库
     */
    suspend fun refresh(vendorId: String, fetch: suspend (String) -> String?): VoiceTagSet {
        val url = tagsUrlOf(vendorId)
        val target = url
        val body = if (target == null) {
            null
        } else {
            runCatching { fetch(target) }
                .onFailure { Timber.w(it, "语音标签拉取失败（%s）", target) }
                .getOrNull()
        }
        val parsed = if (body.isNullOrBlank()) {
            VoiceTags.builtinFor(vendorId)
        } else {
            val p = VoiceTags.parse(vendorId, body, System.currentTimeMillis(), target.orEmpty())
            if (p.tags.isEmpty()) VoiceTags.builtinFor(vendorId) else p
        }
        save(parsed)
        return parsed
    }

    private fun tagsUrlOf(vendorId: String): String? = when {
        vendorId.startsWith("breezeblue") -> "https://docs.breezeblue.ai/llms.txt"
        vendorId == "elevenlabs" -> "https://elevenlabs.io/docs/best-practices/prompting/eleven-v3"
        else -> null
    }
}
