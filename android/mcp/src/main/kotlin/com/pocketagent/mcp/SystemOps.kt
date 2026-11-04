package com.pocketagent.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 系统级写操作的结果。
 *
 * ⚠️ 三档必须分开，因为它们要模型做的事完全不同：
 * · [Ok]        —— 成了，可以继续下一步
 * · [Rejected]  —— **本地校验没过**，这条命令一个字节都没执行（不是权限问题，是参数问题）
 * · [Failed]    —— 执行了但失败（权限、SELinux、目标不存在…），可以看原因重试一次
 *
 * 把 [Rejected] 混进 [Failed] 会让模型以为"权限不够"，于是去换命令反复撞墙 ——
 * 而真实原因是它给的包名/属性名不合法。
 */
sealed interface SystemOpOutcome {
    data class Ok(val output: String) : SystemOpOutcome
    data class Rejected(val reason: String) : SystemOpOutcome
    data class Failed(val reason: String) : SystemOpOutcome
}

/**
 * `:mcp` 与 root 通道之间的窄接口。
 *
 * ★ 刻意**不**在这里 import `:rootlogic`：`:mcp` 是纯工具层，工具不该知道
 *   "root 是怎么拿到的"（su / 常驻通道 / adb）。实现放在 `:app`，
 *   将来把 `su -c` 换成守护进程，只需要换一个实现类，工具一行不用改。
 */
interface SystemOpsPort {

    /** 读任意路径（`file.any`）。 */
    fun readFile(path: String): SystemOpOutcome

    /** 改系统设置（`settings.write`）。 */
    fun putSetting(namespace: String, key: String, value: String): SystemOpOutcome

    /** 改系统属性（`prop.set`）。 */
    fun setProp(name: String, value: String): SystemOpOutcome

    /** 停用应用（`pm.disable`）。 */
    fun disablePackage(packageName: String): SystemOpOutcome
}

/**
 * 四个系统级工具共用的参数规则 —— **纯逻辑，刻意可离线测试**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么校验要放在工具层，明明 RootActions 也校验
 * ═══════════════════════════════════════════════════════════════
 *
 * 两层校验不是重复，是**两种错误要报给两种人**：
 * · 这里报给**模型**："你给的 key 不合法，改参数" —— 它还有机会自己修好；
 * · `RootActions` 报给**审计**："这是注入尝试，已拦下" —— 它是最后一道闸。
 *
 * 只留后者，模型会看到一句含糊的"执行失败"，然后开始瞎猜。
 */
object SystemArgs {

    /** shell 里不安全的字符。管理界面永远不需要它们。 */
    private val UNSAFE = Regex("""[\s;'"`$|&<>\\]""")

    private val KEY = Regex("""^[A-Za-z0-9_.:\-]{1,128}$""")
    private val PROP = Regex("""^[A-Za-z0-9_.\-]{1,128}$""")
    private val PACKAGE = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")

    val NAMESPACES = setOf("system", "secure", "global")

    /**
     * 无论模型说什么都不许停用的包 —— 停了手机就废了。
     *
     * ⚠️ 这不是"危险名单"，是"停了之后**没有界面能改回来**"的名单：
     *    settings 一停，用户连设置页都进不去，只能恢复出厂。
     */
    private val PROTECTED_PACKAGES = mapOf(
        "android" to "系统框架本身",
        "com.android.systemui" to "状态栏与导航栏",
        "com.android.settings" to "设置应用 —— 停了就再也改不回来",
        "com.android.phone" to "电话与移动网络",
    )

    fun namespace(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it in NAMESPACES }

    fun key(raw: String?): String? =
        raw?.trim()?.takeIf { KEY.matches(it) }

    fun propName(raw: String?): String? =
        raw?.trim()?.takeIf { PROP.matches(it) }

    /**
     * 属性值允许空格和中文（`persist.sys.timezone` 就是 `Asia/Shanghai` 这种），
     * 但不允许引号、反斜杠、换行与 shell 元字符。
     */
    fun value(raw: String?): String? {
        val v = raw?.trim() ?: return null
        if (v.isEmpty() || v.length > 512) return null
        if (UNSAFE.containsMatchIn(v)) return null
        return v
    }

    /** 文件路径：允许 `/` `-` `.` `_` 与中文，不允许空白与 shell 元字符。 */
    fun path(raw: String?): String? {
        val p = raw?.trim() ?: return null
        if (p.isEmpty() || p.length > 512) return null
        if (!p.startsWith("/")) return null
        if (UNSAFE.containsMatchIn(p)) return null
        return p
    }

    fun packageName(raw: String?): String? =
        raw?.trim()?.takeIf { PACKAGE.matches(it) }

    /** @return 拒绝原因；null = 可以停用。 */
    fun protectedReason(pkg: String): String? = PROTECTED_PACKAGES[pkg]
}

/**
 * root 不可用时的统一回绝文案。
 *
 * ★ 必须是 [ToolOutcome.Refused]（最终结论），不是 Failed ——
 *   后者会让模型"换个参数再试试"，而它换什么参数都不会让手机长出 root。
 */
internal fun rootMissing(what: String): ToolOutcome = ToolOutcome.Refused(
    "$what 需要 root，而当前 root 通道不可用（手机没 root，或 Magisk 里还没给本应用授权）。" +
        "⚡ 这不是参数写错了 —— 「不要换个写法再试」。请告诉用户去检查 root 授权。",
)

/** 工具共用的三件事：走安全判定、把结局翻成 [ToolOutcome]。 */
internal suspend fun gateWrite(
    safety: ActionSafetyPort,
    target: String,
    purpose: String,
): ToolOutcome? {
    val verdict = safety.review(
        ActionSafetyQuery(
            packageName = "android",
            activityName = null,
            actionType = target,
            targetDescription = target.take(200),
            visibleTexts = listOf(purpose),
            inputFields = emptyList(),
            actionsInLastMinute = 0,
        ),
    )
    return when (verdict) {
        is ActionSafetyOutcome.Blocked -> ToolOutcome.Refused(
            "${verdict.userMessage}" +
                "⚡ 这是安全策略的最终结论，「不要重试、也不要换个写法绕过」。",
        )
        is ActionSafetyOutcome.ConfirmationRequired -> ToolOutcome.Refused(
            "${verdict.userMessage}" +
                "⚡ 这类操作需要用户亲自确认，而当前没有确认界面，所以我没有执行。",
        )
        ActionSafetyOutcome.Allowed -> null
    }
}

internal fun SystemOpOutcome.toToolOutcome(what: String): ToolOutcome = when (this) {
    is SystemOpOutcome.Ok -> ToolOutcome.Success(
        text = if (output.isBlank()) "$what：已执行" else "$what：\n$output",
    )
    // ⚠️ 参数问题 → Failed 而不是 Refused：模型**该**改参数重试一次。
    is SystemOpOutcome.Rejected -> ToolOutcome.Failed(
        "$what 没有执行：$reason。参数改对之后可以再试一次。",
    )
    is SystemOpOutcome.Failed -> ToolOutcome.Failed("$what 执行失败：$reason")
}

/**
 * `file_read` —— 读任意路径的文件（`file.any`）。
 *
 * 只读，所以**不走安全判定**（判定会让用户误以为"读文件也要审批"，从而习惯性点同意）；
 * 但仍有硬性拒绝：块设备、内存设备、内核调试接口 —— 读它们没有任何合理场景，
 * 而误读 `/dev/block` 下的块设备在少数机型上会直接把手机读挂。
 */
class FileReadTool(
    private val ops: SystemOpsPort,
    private val rootAvailable: () -> Boolean,
) : McpTool {

    override val name: String = "file_read"

    override val description: String =
        "读取手机上任意路径的文件内容（需要 root）。用于查看日志、配置文件、" +
            "或普通权限读不到的路径。输出超过 20000 字会被截断。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "绝对路径，例如 /data/local/tmp/x.xml")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        if (!rootAvailable()) return rootMissing("file_read")
        val path = SystemArgs.path(arguments["path"]?.jsonPrimitive?.content)
            ?: return ToolOutcome.Failed("path 必须是绝对路径，且不含空白或 shell 元字符")
        FORBIDDEN.firstOrNull { path.startsWith(it) }?.let {
            return ToolOutcome.Refused(
                "这个路径不在可读范围内（$it）：块设备与内核接口读错会让设备异常。" +
                    "⚡ 这是最终结论，换路径也不行。",
            )
        }
        val outcome = ops.readFile(path)
        if (outcome is SystemOpOutcome.Ok && outcome.output.length > MAX_CHARS) {
            return ToolOutcome.Success(
                text = "读取 $path：\n" + outcome.output.take(MAX_CHARS) +
                    "\n\n（已截断，原文 ${outcome.output.length} 字。需要后半段请用 offset 读法或换个更小的目标）",
            )
        }
        return outcome.toToolOutcome("读取 $path")
    }

    private companion object {
        const val MAX_CHARS = 20_000
        val FORBIDDEN = listOf(
            "/dev/block", "/dev/mem", "/dev/kmem", "/proc/self/mem", "/sys/kernel/debug",
        )
    }
}

/** `setting_write` —— 改系统设置（`settings.write`）。 */
class SettingWriteTool(
    private val ops: SystemOpsPort,
    private val safety: ActionSafetyPort,
    private val rootAvailable: () -> Boolean,
) : McpTool {

    override val name: String = "setting_write"

    override val description: String =
        "修改系统设置（settings put）。命名空间只能是 system / secure / global。" +
            "这类改动会影响系统行为，执行前先向用户说明改什么、为什么改。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("namespace") {
                put("type", "string")
                put("enum", buildJsonArray { SystemArgs.NAMESPACES.sorted().forEach { add(JsonPrimitive(it)) } })
            }
            putJsonObject("key") { put("type", "string") }
            putJsonObject("value") { put("type", "string") }
            putJsonObject("purpose") {
                put("type", "string")
                put("description", "一句话说明为什么改 —— 会写进审计记录")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("namespace")); add(JsonPrimitive("key"))
            add(JsonPrimitive("value")); add(JsonPrimitive("purpose"))
        }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        if (!rootAvailable()) return rootMissing("setting_write")
        val ns = SystemArgs.namespace(arguments["namespace"]?.jsonPrimitive?.content)
            ?: return ToolOutcome.Failed("namespace 只能是 system / secure / global")
        val key = SystemArgs.key(arguments["key"]?.jsonPrimitive?.content)
            ?: return ToolOutcome.Failed("key 只能包含字母、数字、下划线、点、冒号、连字符")
        val value = SystemArgs.value(arguments["value"]?.jsonPrimitive?.content)
            ?: return ToolOutcome.Failed("value 不能为空，且不能含引号/反斜杠/换行或 shell 元字符")
        val purpose = arguments["purpose"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (purpose.isEmpty()) return ToolOutcome.Failed("必须提供 purpose（审计记录要它）")

        gateWrite(safety, "settings put $ns $key", purpose)?.let { return it }

        return ops.putSetting(ns, key, value).toToolOutcome("修改设置 $ns/$key")
    }
}

/** `prop_write` —— 改系统属性（`prop.set`）。 */
class PropWriteTool(
    private val ops: SystemOpsPort,
    private val safety: ActionSafetyPort,
    private val rootAvailable: () -> Boolean,
) : McpTool {

    override val name: String = "prop_write"

    override val description: String =
        "修改系统属性（setprop）。属性名只能包含字母数字与 `_.-`，值不能含引号或 shell 元字符。" +
            "改错属性可能导致某些功能异常，执行前先向用户说明。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("name") { put("type", "string") }
            putJsonObject("value") { put("type", "string") }
            putJsonObject("purpose") { put("type", "string") }
        }
        putJsonArray("required") {
            add(JsonPrimitive("name")); add(JsonPrimitive("value")); add(JsonPrimitive("purpose"))
        }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        if (!rootAvailable()) return rootMissing("prop_write")
        val name = SystemArgs.propName(arguments["name"]?.jsonPrimitive?.content)
            ?: return ToolOutcome.Failed("属性名只能包含字母、数字、下划线、点、连字符")
        val value = SystemArgs.value(arguments["value"]?.jsonPrimitive?.content)
            ?: return ToolOutcome.Failed("属性值不能为空，且不能含引号/反斜杠/换行或 shell 元字符")
        val purpose = arguments["purpose"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (purpose.isEmpty()) return ToolOutcome.Failed("必须提供 purpose（审计记录要它）")

        gateWrite(safety, "setprop $name", purpose)?.let { return it }

        return ops.setProp(name, value).toToolOutcome("设置属性 $name")
    }
}

/**
 * `app_disable` —— 停用应用（`pm.disable`）。
 *
 * ⚠️ 这是四个里唯一**用户会立刻感知到**的操作：停用的应用从桌面消失，
 *   而且不会自己回来（要用 `pm enable` 或重装）。
 *   所以它额外做两件事：保护名单、以及回执里明确写出怎么撤销。
 */
class AppDisableTool(
    private val ops: SystemOpsPort,
    private val safety: ActionSafetyPort,
    private val rootAvailable: () -> Boolean,
) : McpTool {

    override val name: String = "app_disable"

    override val description: String =
        "停用一个应用（pm disable-user --user 0）。设备上的这个应用会从桌面消失，" +
            "「不会自己回来」—— 需要用 pm enable 或重装才能恢复。" +
            "系统关键应用（android / systemui / settings / phone）永远不允许停用。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("package") { put("type", "string") }
            putJsonObject("purpose") { put("type", "string") }
        }
        putJsonArray("required") { add(JsonPrimitive("package")); add(JsonPrimitive("purpose")) }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        if (!rootAvailable()) return rootMissing("app_disable")
        val rawPkg = arguments["package"]?.jsonPrimitive?.content?.trim().orEmpty()

        // ⚠️ 保护名单**必须在包名格式校验之前**：`android` 这种系统包名
        //    本身不满足"至少两段"的格式规则，先校验格式会让它落到
        //    "package 不合法"的 Failed —— 模型会以为改个写法就能停用系统框架。
        SystemArgs.protectedReason(rawPkg)?.let { why ->
            return ToolOutcome.Refused(
                "$rawPkg 是系统关键组件（$why），停用后手机会不可用。" +
                    "⚡ 这是最终结论，换个包名也不行。",
            )
        }

        val pkg = SystemArgs.packageName(rawPkg)
            ?: return ToolOutcome.Failed("package 必须是完整包名，例如 com.example.app")

        val purpose = arguments["purpose"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (purpose.isEmpty()) return ToolOutcome.Failed("必须提供 purpose（审计记录要它）")

        gateWrite(safety, "pm disable-user $pkg", purpose)?.let { return it }

        return when (val outcome = ops.disablePackage(pkg)) {
            is SystemOpOutcome.Ok -> ToolOutcome.Success(
                text = "已停用 $pkg（$purpose）。" +
                    "要恢复请执行 `pm enable $pkg`，或让用户重装该应用。",
            )
            else -> outcome.toToolOutcome("停用 $pkg")
        }
    }
}
