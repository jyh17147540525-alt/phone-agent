package com.pocketagent.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.pocketagent.legacy.ProactivePlanner
import timber.log.Timber

/**
 * **角色主动来找你说话** —— 通知通道。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户要的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * "app 加入通知功能，角色可以自行给用户发消息，不用等用户发消息后
 *  才能被动回复。"
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 什么时候**不**发，比什么时候发重要得多
 * ═══════════════════════════════════════════════════════════════
 *
 * 判断全部交给 `:legacy` 的 `ProactivePlanner`（11 条离线测试钉住），
 * 本文件只做三件事：**算事实 → 问能不能发 → 发出去**。
 *
 * 这么分的理由：一旦"要不要打扰用户"这条判断长在 Android 代码里，
 * 它就没法被离线验证，而它恰恰是**最容易越界**的那一条。
 *
 * ⚠️ 通知一旦被用户关掉就再也叫不回来了 —— 所以宁可少发。
 *    这里额外加了一道：**每条都带自己的 id**，用户能各自划掉，
 *    而不是被一个不断更新的通知反复骚扰。
 */
class ProactiveNotifier(private val context: Context) {

    /**
     * 检查一次，必要时发一条。
     *
     * @param facts 由调用方提供的可观测事实（见 [ProactivePlanner.Facts]）
     * @param lineFor 生成这句话（按人设）。**可能失败** —— 失败就不发，
     *   绝不拿一句模板硬凑：一句不像她的话，比不发更伤。
     * @return 真正发出去的那句话；null = 这次没发（含所有 Skip 原因）
     */
    suspend fun tick(
        facts: ProactivePlanner.Facts,
        lineFor: suspend (ProactivePlanner.Tone) -> String?,
    ): String? {
        val decision = ProactivePlanner.decide(facts)
        if (decision is ProactivePlanner.Decision.Skip) {
            Timber.d("主动消息跳过：%s", decision.reason)
            return null
        }
        val tone = (decision as ProactivePlanner.Decision.Send).tone
        val line = lineFor(tone)?.trim()?.takeIf { it.isNotBlank() } ?: run {
            Timber.w("主动消息没有生成出内容（tone=%s），本次不发", tone)
            return null
        }
        return if (post(line)) line else null
    }

    /** 发一条通知。@return 是否真的发出去了 */
    private fun post(line: String): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "角色消息",
                    // ★ IMPORTANCE_DEFAULT 而不是 HIGH：HIGH 会**打断**用户
                    //   （横幅 + 声音）。角色找你说话是"有人在等你"，
                    //   不是"系统告警"。HIGH 用两次就会被关掉。
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "角色自己来找你说话时用这条通道"
                },
            )
        }
        // ⚠️ Android 13+ 需要用户授权 POST_NOTIFICATIONS。没授权就**安静地不发** ——
        //    弹一句"请开启通知权限"是在为角色的需求向用户施压。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Timber.i("主动消息跳过：用户没给通知权限")
                return false
            }
        }
        val intent = Intent(context, Class.forName("com.pocketagent.MainActivity")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(titleFor(line))
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        return runCatching {
            // ★ 每条一个 id：用户可以各自划掉，而不是被同一个通知反复更新。
            nm.notify((System.currentTimeMillis() % 100_000).toInt(), n)
            true
        }.getOrElse {
            Timber.w(it, "主动消息发送失败")
            false
        }
    }

    /**
     * 通知标题 = 角色名。
     *
     * ⚠️ 不写"PocketAgent"：用户看到的是"她来找我了"，
     *    而不是"某个应用发了一条通知"。这一条很小，但它决定了这条通知
     *    是"一个人"还是"一个 App"。
     */
    private fun titleFor(@Suppress("UNUSED_PARAMETER") line: String): String = personaName ?: "她"

    /** 角色名（由容器注入；null = 还没设自定义角色）。 */
    var personaName: String? = null

    private companion object {
        const val CHANNEL_ID = "persona_messages"
    }
}
