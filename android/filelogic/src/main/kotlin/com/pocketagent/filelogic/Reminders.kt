package com.pocketagent.filelogic

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 提醒的时间计算。
 *
 * 为什么单独拎出来：模型给的时间是**人话**（`明天早上八点` 它未必会转成时间戳），
 * 而"什么时候响"必须是确定的、可测的。这里只做纯计算，不碰 AlarmManager。
 *
 * 支持的写法（够用为止，不追求自然语言全解析）：
 * - `09:00` / `9:00` —— **下一次**到点（今天还没过就是今天）
 * - `2026-11-21 09:00` / `2026-11-21T09:00`
 * - `+30m` / `+2h` / `+45s` / `+1d` —— 相对现在
 */
data class Reminder(
    val id: String,
    val text: String,
    val atMillis: Long,
    val createdMillis: Long,
)

sealed interface ReminderTime {
    data class Ok(val atMillis: Long) : ReminderTime
    data class Bad(val reason: String) : ReminderTime
}

object Reminders {

    /** 一次最多排这么多条：再多用户也管不过来，而且 AlarmManager 有配额。 */
    const val MAX_PENDING = 32

    /** 最少提前 5 秒，否则"设置完立刻就响"，用户会以为手滑。 */
    const val MIN_LEAD_MS = 5_000L

    /** 最多排到 365 天后：更远的提醒基本等于没设。 */
    const val MAX_AHEAD_MS = 365L * 24 * 60 * 60 * 1000

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val DATE_TIME_T = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    fun parse(raw: String, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): ReminderTime {
        val text = raw.trim()
        if (text.isEmpty()) return ReminderTime.Bad("要给我一个时间，例如 09:00、2026-11-21 09:00 或 +30m。")

        // 相对时间
        if (text.startsWith("+")) {
            val body = text.substring(1).trim()
            val unit = body.lastOrNull()?.lowercaseChar() ?: return ReminderTime.Bad("相对时间要带单位，例如 +30m、+2h、+1d。")
            val amount = body.dropLast(1).trim().toLongOrNull()
                ?: return ReminderTime.Bad("「$raw」里的数字我读不出来。")
            if (amount <= 0) return ReminderTime.Bad("时间要往前，不能是 0 或负数。")
            val delta = when (unit) {
                's' -> amount * 1000
                'm' -> amount * 60_000
                'h' -> amount * 3_600_000
                'd' -> amount * 24 * 3_600_000
                else -> return ReminderTime.Bad("单位只认 s/m/h/d，收到的是「$unit」。")
            }
            return check(nowMillis + delta, nowMillis, raw)
        }

        // 绝对日期时间
        runCatching { LocalDateTime.parse(text, DATE_TIME) }.getOrNull()?.let { dt ->
            return check(dt.atZone(zone).toInstant().toEpochMilli(), nowMillis, raw)
        }
        runCatching { LocalDateTime.parse(text, DATE_TIME_T) }.getOrNull()?.let { dt ->
            return check(dt.atZone(zone).toInstant().toEpochMilli(), nowMillis, raw)
        }

        // 只有时刻：取下一次
        val timeOnly = runCatching { LocalTime.parse(normaliseTime(text)) }.getOrNull()
        if (timeOnly != null) {
            val today = LocalDate.now(zone)
            var candidate = LocalDateTime.of(today, timeOnly).atZone(zone).toInstant().toEpochMilli()
            if (candidate <= nowMillis + MIN_LEAD_MS) {
                candidate = LocalDateTime.of(today.plusDays(1), timeOnly)
                    .atZone(zone).toInstant().toEpochMilli()
            }
            return check(candidate, nowMillis, raw)
        }

        return ReminderTime.Bad("「$raw」我读不出时间。写法：09:00、2026-11-21 09:00、+30m。")
    }

    private fun normaliseTime(raw: String): String {
        val parts = raw.split(':')
        if (parts.size != 2) return raw
        val h = parts[0].toIntOrNull() ?: return raw
        val m = parts[1].toIntOrNull() ?: return raw
        return "%02d:%02d".format(h, m)
    }

    private fun check(atMillis: Long, nowMillis: Long, raw: String): ReminderTime = when {
        atMillis - nowMillis < MIN_LEAD_MS -> ReminderTime.Bad("「$raw」离现在太近了（至少 5 秒后）。")
        atMillis - nowMillis > MAX_AHEAD_MS -> ReminderTime.Bad("「$raw」太远了，一年内的提醒才收。")
        else -> ReminderTime.Ok(atMillis)
    }

    /** 给模型看的"什么时候响"。 */
    fun describe(atMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(atMillis), zone)
        val minutes = ((atMillis - nowMillis) / 60_000).coerceAtLeast(0)
        val whenText = when {
            minutes < 1 -> "不到 1 分钟后"
            minutes < 60 -> "$minutes 分钟后"
            minutes < 60 * 24 -> "${minutes / 60} 小时 ${minutes % 60} 分钟后"
            else -> "${minutes / (60 * 24)} 天后"
        }
        val stamp = dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        return "$stamp（$whenText）"
    }
}
