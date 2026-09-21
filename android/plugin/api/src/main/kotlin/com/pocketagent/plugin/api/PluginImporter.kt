package com.pocketagent.plugin.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 本地插件导入流程 —— 从一段原始 JSON 到「可以给用户看的风险告知」。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么单独抽成一个对象
 * ═══════════════════════════════════════════════════════════════
 *
 * 因为**顺序本身就是安全设计**，而顺序最容易被后来的人改坏。
 *
 * 这个流程里有一步是反直觉的：**必须先宽松解析，再严格解析**。
 * 直接 `decodeFromString<PluginManifest>(json)` 看起来更简洁，但它会：
 *
 *   - 一个申请 `payment.pay` 的插件 → 报错"枚举解析失败"
 *   - 一个申请 `future.telepathy` 的插件 → 也报错"枚举解析失败"
 *
 * 两种**性质完全不同**的情况，在用户眼里变成同一句话。第一种是明确的越权
 * 尝试，应该被拦下并记审计；第二种只是本体太旧。用户分不清，就会得出
 * "这插件格式有问题"的错误结论。
 *
 * 所以顺序固定为四步，每一步的职责单一：
 *
 * ```
 *  ① 宽松解析，只取原始能力字符串   ← 唯一能看到 payment.pay 原文的地方
 *  ② inspectRawCapabilities()      ← 先看，再判：禁止前缀 → 拒绝
 *  ③ 严格反序列化                   ← 此时未知能力已被宽容序列化器丢弃
 *  ④ validate()                    ← 结构性校验，产出警告
 * ```
 *
 * 把它做成纯函数还有一个直接好处：**它能在没有 Android SDK 的机器上完整测试**。
 * 文件选择器、UI、Toast 那些测不了的部分，本来也不该混进来。
 *
 * ⚠️ 本对象**不做**任何文件 IO。调用方负责把文件读成字符串并做大小限制前的
 *    初步检查。这样它才保持零依赖、可测试。
 */
object PluginImporter {

    /**
     * 清单大小上限。
     *
     * 存在的理由不是"省内存"，而是**防止深度嵌套的 JSON 打爆调用栈**。
     * 解析器对嵌套深度没有硬限制，一个几 MB 的 `[[[[[...]]]]]` 就能让
     * `decodeFromString` 抛 StackOverflowError —— 而 Error 不是 Exception，
     * 常规的 try/catch 接不住，会直接把进程带走。
     *
     * 一个真实的插件清单通常不到 10 KB。256 KB 已经是三个数量级的宽容。
     */
    const val MAX_MANIFEST_CHARS = 256 * 1024

    /** 宽松解析：只为了看到原始字符串，不做任何判断 */
    private val LOOSE = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 严格反序列化：字段类型必须对得上，但未知能力由宽容序列化器丢弃 */
    private val STRICT = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
    }

    /**
     * 导入检查的结果。
     *
     * ⚠️ [Rejected] 与 [Malformed] 必须分开。前者是"这插件想干坏事"，
     *    后者是"这文件不是插件"。合成一个的话，用户看到"插件有问题"
     *    就分不清该不该换个来源再试。
     */
    sealed interface Outcome {

        /** 可以进入安装确认页。[notice] 就是那一页要显示的全部内容 */
        data class Ready(
            val manifest: PluginManifest,
            val notice: ImportRiskNotice,
            val warnings: List<ValidationIssue>,
        ) : Outcome

        /** 申请了禁止能力，或结构性校验不通过 —— 明确的拒绝，要记审计 */
        data class Rejected(val errors: List<ValidationIssue>) : Outcome

        /** 连插件清单都不是（JSON 语法错、缺必填字段、类型不对） */
        data class Malformed(val message: String) : Outcome
    }

    /**
     * 检查一段原始 JSON 是否可以作为插件导入。
     *
     * @param rawJson 插件清单文件的全文
     */
    fun inspect(rawJson: String): Outcome {
        if (rawJson.length > MAX_MANIFEST_CHARS) {
            return Outcome.Malformed(
                "清单过大（${rawJson.length} 字符，上限 $MAX_MANIFEST_CHARS）。" +
                    "正常的插件清单不到 10 KB，请确认选对了文件。"
            )
        }

        // ① 宽松解析，只取原始能力字符串
        val rawCapabilities = extractRawCapabilities(rawJson)
            ?: return Outcome.Malformed("文件内容不是一个 JSON 对象。请确认选的是插件清单文件。")

        // ② 先看，再判 —— 这是唯一能识别「申请禁止能力」的时机
        val capabilityIssues = PluginValidator.inspectRawCapabilities(rawCapabilities)
        val fatal = capabilityIssues.filter { it.severity == IssueSeverity.ERROR }
        if (fatal.isNotEmpty()) {
            return Outcome.Rejected(fatal)
        }

        // ③ 严格反序列化。未知能力在这一步被宽容序列化器丢弃，不会炸
        val manifest = try {
            STRICT.decodeFromString(PluginManifest.serializer(), rawJson)
        } catch (e: SerializationException) {
            // 用户给的文件，什么畸形内容都可能出现。这里绝不能把异常抛给界面
            return Outcome.Malformed(
                "插件清单缺少必填字段，或字段类型不对：${e.message.orEmpty().take(200)}"
            )
        }

        // ④ 结构性校验
        val validateWarnings = when (val result = PluginValidator.validate(manifest)) {
            is PluginValidationResult.Rejected -> return Outcome.Rejected(result.errors)
            is PluginValidationResult.Accepted -> result.warnings
        }

        // ②的警告（未知能力）与④的警告合并 —— 用户在确认页上应该一次看全，
        // 而不是先装完再发现有一条提示没看到
        val warnings = capabilityIssues + validateWarnings

        return Outcome.Ready(
            manifest = manifest,
            notice = ImportRiskNotice.forLocalImport(manifest, warnings),
            warnings = warnings,
        )
    }

    /**
     * 从原始 JSON 里抠出 capabilities 数组的**字符串原文**。
     *
     * 返回 null 表示"根本不是 JSON 对象"，调用方据此区分 Malformed。
     *
     * ⚠️ 这里刻意只收 `JsonPrimitive` 且必须是字符串的元素：
     *    写成 `{"id": "payment.pay"}` 这种对象形式会被跳过 —— 但它躲不过
     *    第③步的严格反序列化（`List<PluginCapability>` 收不下对象），
     *    结果是 Malformed。**跳过不等于放行。**
     */
    private fun extractRawCapabilities(rawJson: String): List<String>? {
        val root = try {
            LOOSE.parseToJsonElement(rawJson)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }

        val obj = root as? JsonObject ?: return null

        // 缺 capabilities 字段不算"不是插件"，交给第③步报缺失必填字段 ——
        // 那样错误信息更准确
        val array = obj["capabilities"] as? JsonArray ?: return emptyList()

        return array.mapNotNull { element ->
            val primitive = element as? JsonPrimitive ?: return@mapNotNull null
            if (!primitive.isString) return@mapNotNull null
            primitive.content
        }
    }
}
