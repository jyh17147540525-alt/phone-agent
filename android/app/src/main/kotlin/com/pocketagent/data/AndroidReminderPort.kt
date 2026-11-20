package com.pocketagent.data

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pocketagent.R
import com.pocketagent.filelogic.Reminder
import com.pocketagent.mcp.ReminderPort
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 提醒：AlarmManager 定时 + 通知。
 *
 * 三件事都是必须的，缺一个提醒就不会响：
 * 1. **AlarmManager** 而不是 Handler/协程延时 —— 进程会被杀，协程会一起没。
 * 2. **manifest 里声明 [ReminderReceiver]** —— 动态注册的接收器在应用被杀后收不到。
 * 3. **`setExactAndAllowWhileIdle` 可能抛 SecurityException**（Android 12+ 没给精确
 *    闹钟权限），所以要退回不精确的 `set`：宁可晚几分钟响，也不能静默失败。
 *
 * 存储用 SharedPreferences + JSON：提醒最多几十条，不值得为它开一张表。
 */
class AndroidReminderPort(context: Context) : ReminderPort {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override suspend fun schedule(text: String, atMillis: Long): String {
        val id = UUID.randomUUID().toString().take(8)
        val created = System.currentTimeMillis()
        save(read().filter { it.atMillis > created } + Reminder(id, text, atMillis, created))
        arm(Reminder(id, text, atMillis, created))
        return id
    }

    override suspend fun pending(): List<Reminder> {
        val now = System.currentTimeMillis()
        val all = read()
        // 过点但还没被广播消费的，不再展示（广播可能因为省电模式晚到）
        val alive = all.filter { it.atMillis > now - STALE_GRACE_MS }
        if (alive.size != all.size) save(alive)
        return alive
    }

    override suspend fun cancel(id: String): Boolean {
        val all = read()
        val hit = all.firstOrNull { it.id == id } ?: return false
        save(all.filterNot { it.id == id })
        appContext.getSystemService(AlarmManager::class.java)?.cancel(pendingIntentFor(hit))
        return true
    }

    private fun arm(reminder: Reminder) {
        val manager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val intent = pendingIntentFor(reminder)
        val triggerAt = reminder.atMillis
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
                manager.set(AlarmManager.RTC_WAKEUP, triggerAt, intent)
            } else {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
            }
        } catch (e: SecurityException) {
            // 没有精确闹钟权限：退成不精确，晚几分钟总比不响好
            runCatching { manager.set(AlarmManager.RTC_WAKEUP, triggerAt, intent) }
        }
    }

    private fun pendingIntentFor(reminder: Reminder): PendingIntent {
        val intent = Intent(appContext, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ID, reminder.id)
            putExtra(EXTRA_TEXT, reminder.text)
            // requestCode 必须每条不同，否则后设的会覆盖先设的
            data = android.net.Uri.parse("pocketagent://reminder/${reminder.id}")
        }
        return PendingIntent.getBroadcast(
            appContext,
            reminder.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun read(): List<Reminder> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                Reminder(
                    id = o.optString("id"),
                    text = o.optString("text"),
                    atMillis = o.optLong("at"),
                    createdMillis = o.optLong("created"),
                )
            }.filter { it.id.isNotEmpty() && it.text.isNotEmpty() }
        }.getOrElse { emptyList() }
    }

    private fun save(items: List<Reminder>) {
        val array = JSONArray()
        for (r in items) {
            array.put(JSONObject().apply {
                put("id", r.id)
                put("text", r.text)
                put("at", r.atMillis)
                put("created", r.createdMillis)
            })
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    companion object {
        const val ACTION_FIRE = "com.pocketagent.REMINDER_FIRE"
        const val EXTRA_ID = "id"
        const val EXTRA_TEXT = "text"
        private const val PREFS = "reminders_v1"
        private const val KEY_ITEMS = "items"
        /** 过了点还没被消费的，展示时先藏起来；广播晚到半小时也算正常。 */
        private const val STALE_GRACE_MS = 30L * 60 * 1000
    }
}

/** 到点由系统拉起（manifest 里声明，应用被杀也能收到）。 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidReminderPort.ACTION_FIRE) return
        val text = intent.getStringExtra(AndroidReminderPort.EXTRA_TEXT).orEmpty().ifEmpty { "该做这件事了" }
        val id = intent.getStringExtra(AndroidReminderPort.EXTRA_ID).orEmpty()

        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "到点提醒；关掉它提醒就只剩个响动" }
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("提醒")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        }

        // 响过就从待办里删掉，否则 list 会一直挂着一条过去的提醒
        context.getSharedPreferences("reminders_v1", Context.MODE_PRIVATE)
            .edit()
            .putString("items", strip(context.getSharedPreferences("reminders_v1", Context.MODE_PRIVATE).getString("items", "[]"), id))
            .apply()
    }

    private fun strip(rawJson: String?, id: String): String {
        if (rawJson.isNullOrBlank() || id.isEmpty()) return rawJson ?: "[]"
        return runCatching {
            val array = JSONArray(rawJson)
            val kept = JSONArray()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                if (o.optString("id") != id) kept.put(o)
            }
            kept.toString()
        }.getOrElse { rawJson }
    }

    private companion object {
        const val CHANNEL_ID = "reminders"
    }
}
