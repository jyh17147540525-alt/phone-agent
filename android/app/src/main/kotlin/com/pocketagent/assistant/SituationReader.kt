package com.pocketagent.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import com.pocketagent.legacy.SituationalContext
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * **此刻的处境** —— 地点、时间、天气。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户要的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * "能获取用户的地址，还能知道用户所在的时区现在是几点，还能知道天气预报。"
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三条红线，都是"宁可拿不到"
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **定位只取「大概在哪」**：用 `getLastKnownLocation` 的粗略定位，
 *    不做持续定位、不请求精确定位权限。角色说"今天冷，多穿点"不需要知道你
 *    在哪个门牌号 —— 而持续定位是**要付电量和隐私代价**的。
 * 2. **天气走开源接口且不传身份**：Open-Meteo 无需 Key、不需要账号，
 *    请求里只有经纬度（还会被四舍五入到 0.1°）。不上报任何标识。
 * 3. **任何一步失败就当"不知道"**：拿不到坐标 / 拿不到天气，
 *    就退回 `WeatherKind.UNKNOWN`，**绝不编** ——
 *    编一句"今天好像要下雨"，用户淋湿了会想起来是谁说的。
 */
class SituationReader(private val context: Context) {

    /** 上次取到天气的时刻与结果（30 分钟缓存，避免每次 tick 都发请求）。 */
    @Volatile
    private var cachedAt: Long = 0L

    @Volatile
    private var cached: Pair<SituationalContext.WeatherKind, Int?>? = null

    /** 现在几点（**设备时区**，不是服务器时间）。 */
    fun currentHour(): Int = SituationalContext.DayPhase.let {
        java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    }

    /**
     * 读一次处境。
     *
     * @return 天气类别 + 温度。null = 没拿到（**不要**拿默认值冒充）。
     */
    fun weather(): Pair<SituationalContext.WeatherKind, Int?>? {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < CACHE_MS) return it }

        val coords = lastLocation() ?: return null
        val result = runCatching { fetchOpenMeteo(coords.first, coords.second) }.getOrNull()
        if (result != null) {
            cached = result
            cachedAt = now
        }
        return result
    }

    /**
     * 上一次已知位置（**粗略**）。
     *
     * ⚠️ 没有定位权限时返回 null，而不是抛异常 —— 用户拒绝授权是正常状态，
     *    不是错误。没有位置就不提天气，其余功能照常。
     */
    private fun lastLocation(): Pair<Double, Double>? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Timber.d("没有粗略定位权限，跳过天气")
            return null
        }
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val loc = runCatching {
            lm.getProviders(true)
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull() ?: return null
        // ★ 四舍五入到 0.1°（约 11 公里）：够用来查天气，不足以定位到楼。
        val lat = Math.round(loc.latitude * 10.0) / 10.0
        val lon = Math.round(loc.longitude * 10.0) / 10.0
        return lat to lon
    }

    /**
     * Open-Meteo 当前天气。
     *
     * ★ 选它的理由：**不要 Key**。本项目是 BYOK，让用户为了"今天冷不冷"
     *   再去注册一个天气服务账号是不合理的。
     */
    private fun fetchOpenMeteo(lat: Double, lon: Double): Pair<SituationalContext.WeatherKind, Int?>? {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code&timezone=auto",
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        return try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            // ⚠️ 手写解析而不是引 JSON 库：这是唯一一个外部接口，
            //    为一个字段引一个反序列化层不值得（且本项目已有手写 JSON 的先例）。
            val code = Regex(""""weather_code"\s*:\s*(-?\d+)""").find(body)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            val temp = Regex(""""temperature_2m"\s*:\s*(-?\d+(?:\.\d+)?)""").find(body)
                ?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            if (code == null) return null
            SituationalContext.WeatherKind.ofWmo(code) to temp?.toInt()
        } catch (t: Throwable) {
            Timber.d("天气请求失败：%s", t.message)
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private companion object {
        const val CACHE_MS = 30 * 60 * 1000L
    }
}
