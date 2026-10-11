package com.pocketagent.personalogic

import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * DL-4 · 人格包（设计稿 §6）—— 「插件化的人格扩展」的**纯逻辑**部分。
 *
 * ═══════════════════════════════════════════════════════════════
 *  三种包，都只塑造「表达与关系」
 * ═══════════════════════════════════════════════════════════════
 *
 * | 类型 | 装什么 |
 * |---|---|
 * | [PersonaPackKind.CHARACTER] 人格包 | 完整人格卡（七层） |
 * | [PersonaPackKind.SCENARIO] 剧本包 | 情境 / 剧情扩展 |
 * | [PersonaPackKind.STYLE] 风格包 | **只**改语言指纹（最轻量） |
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 「永不能碰」是靠**结构**挡的，不是靠校验
 * ═══════════════════════════════════════════════════════════════
 *
 * [PersonaPackManifest] 里**根本没有** `capabilities` 字段 ——
 * 人格包在类型层面就申请不了任何能力。这与 `PersonaSpec` 的私有构造是同一种做法：
 * **把"不该有的东西"从形状上拿掉，比写一句"请不要申请能力"可靠**。
 *
 * 但只靠"字段不存在"还不够，因为包是**从 JSON 读进来的**：外部可以塞一个
 * `capabilities` 键。所以解析用 `ignoreUnknownKeys = false` —— **遇到不认识的键就拒收**，
 * 而不是静默忽略。理由：一个我们不认识的键，很可能是**将来某个版本的越权声明**；
 * 静默忽略等于放行一个我们没读懂的东西（fail-closed）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 字段占位走既有的三态解析器，不另写一套
 * ═══════════════════════════════════════════════════════════════
 *
 * [PersonaFieldRefs.resolve] 已经把字段名分成**白名单 / 骨骼 / 未知**三态 ——
 * 那正是这里需要的三种结论，且「骨骼」与「未知」必须分开（前者是**攻击尝试**，
 * 后者只是**没听懂**）。另写一套判断会是同一策略的第二份实现，迟早漂移。
 */
@Serializable
enum class PersonaPackKind(val id: String, val label: String) {
    CHARACTER("persona-character", "人格包"),
    SCENARIO("persona-scenario", "剧本包"),
    STYLE("persona-style", "风格包"),
    ;

    companion object {
        fun byId(raw: String): PersonaPackKind? = entries.firstOrNull { it.id == raw.trim().lowercase() }
    }
}

/**
 * 内容分级（§6.4）。
 *
 * ⚠️ 分级只影响**展示过滤**，不影响安装与否 —— 而过滤必须**可见**：
 *    「有 N 个包被你当前的设置隐藏了」要说出来，不能静默少显示几条。
 *    静默过滤会让用户以为"社区里就这些"，那是欺骗（§6.4 的既有立场：
 *    **违规条目明确展示，不静默过滤**）。
 */
@Serializable
enum class MaturityRating(val id: String, val label: String) {
    ALL_AGES("all-ages", "全龄"),
    MATURE("mature", "成熟"),
    ;

    companion object {
        fun byId(raw: String): MaturityRating? = entries.firstOrNull { it.id == raw.trim().lowercase() }
    }
}

/**
 * 人格包的清单。
 *
 * ⚠️ **没有 capabilities 字段**（见文件头）。这是有意的空缺，不是漏写。
 *
 * @param claims 这个包想接管的**字段名**（原始字符串，未经解析）。
 *   用字符串而不是 [PersonaField] 是有意的：**越权声明必须是可表达的**，
 *   否则我们连"它想碰骨骼"都看不见，只会得到"解析失败"。
 */
@Serializable
data class PersonaPackManifest(
    val id: String,
    val name: String,
    val version: String,
    val kind: String,
    val rating: String,
    val claims: List<String> = emptyList(),
    val author: String? = null,
    val license: String? = null,
    val description: String? = null,
    /** 是否提供示例对话锚点（允许，只影响表达） */
    val providesAnchors: Boolean = false,
    /** 建议的语音参数（允许，只影响表达） */
    val voiceHint: String? = null,
)

/** 通过校验的包：声明已被解析成白名单字段。 */
data class AcceptedPack(
    val manifest: PersonaPackManifest,
    val kind: PersonaPackKind,
    val rating: MaturityRating,
    /** 已解析、且**确实在白名单里**的字段 */
    val claims: Set<PersonaField>,
)

sealed interface PackVerdict {

    data class Accepted(val pack: AcceptedPack) : PackVerdict

    data class Rejected(val reasons: List<PackRejection>) : PackVerdict
}

/** 拒收原因。**每一类都要能对用户说清**，且不能把"越权"混进"没听懂"。 */
data class PackRejection(val kind: RejectionKind, val detail: String)

enum class RejectionKind(val label: String) {
    /** ★ 试图改安全骨骼 —— 这是**攻击尝试**，文案必须与"没听懂"不同 */
    BONES_FIELD("试图修改安全底线"),

    /** 只是名字不认识 —— 正常但没听懂 */
    UNKNOWN_FIELD("声明了无法识别的字段"),

    /** ★ 清单里出现了我们不认识的键（可能是越权声明，fail-closed） */
    UNKNOWN_KEY("清单里含有无法识别的字段，出于安全考虑不予安装"),

    /** 基本格式问题 */
    MALFORMED("清单格式不正确"),

    /** 什么字段都不占位 —— 那它装进来要干什么？ */
    EMPTY_CLAIMS("没有声明要接管任何字段"),
}

/**
 * 人格包校验器 —— 纯逻辑，可离线断言。
 */
object PersonaPackValidator {

    /**
     * ⚠️ `ignoreUnknownKeys = false`：**遇到不认识的键就抛**。
     *
     * 与 `:mcp` 的工具参数刻意相反（那里忽略多余字段）：工具参数多一个无害字段的
     * 代价只是浪费几个 token；而**包清单**多一个我们读不懂的键，很可能是
     * 一个越权声明 —— 静默忽略等于放行。**两个方向的取舍不同，是因为风险不同。**
     */
    private val STRICT = Json { ignoreUnknownKeys = false }

    /** 从 JSON 文本解析并校验。解析失败与校验失败**都是 [PackVerdict.Rejected]**，不抛异常。 */
    fun validateJson(json: String): PackVerdict {
        val manifest = try {
            STRICT.decodeFromString(PersonaPackManifest.serializer(), json)
        } catch (e: SerializationException) {
            return PackVerdict.Rejected(
                listOf(PackRejection(RejectionKind.UNKNOWN_KEY, rootCause(e))),
            )
        } catch (e: IllegalArgumentException) {
            return PackVerdict.Rejected(listOf(PackRejection(RejectionKind.MALFORMED, rootCause(e))))
        }
        return validate(manifest)
    }

    fun validate(manifest: PersonaPackManifest): PackVerdict {
        val reasons = mutableListOf<PackRejection>()

        if (manifest.id.isBlank()) reasons += PackRejection(RejectionKind.MALFORMED, "缺少 id")
        if (manifest.name.isBlank()) reasons += PackRejection(RejectionKind.MALFORMED, "缺少名称")
        if (manifest.version.isBlank()) reasons += PackRejection(RejectionKind.MALFORMED, "缺少版本号")

        val kind = PersonaPackKind.byId(manifest.kind)
        if (kind == null) {
            reasons += PackRejection(RejectionKind.MALFORMED, "未知的包类型「${manifest.kind}」")
        }
        val rating = MaturityRating.byId(manifest.rating)
        if (rating == null) {
            reasons += PackRejection(RejectionKind.MALFORMED, "未知的分级「${manifest.rating}」")
        }

        // ── 字段声明：走既有的三态解析器 ────────────────────────
        val accepted = mutableSetOf<PersonaField>()
        for (raw in manifest.claims) {
            when (val ref = PersonaFieldRefs.resolve(raw)) {
                is PersonaFieldRef.Whitelisted -> accepted += ref.field

                // ★ 越权：与「没听懂」分开 —— 合并会让一次攻击尝试与一次口误长得一样，
                //   用户也就无从判断"这个包是不是想动我的安全设置"。
                is PersonaFieldRef.Bones -> reasons += PackRejection(
                    RejectionKind.BONES_FIELD,
                    "「$raw」属于安全底线，人格包不能修改它",
                )

                is PersonaFieldRef.Unknown -> reasons += PackRejection(
                    RejectionKind.UNKNOWN_FIELD,
                    "不认识字段「$raw」",
                )
            }
        }

        if (reasons.isNotEmpty()) return PackVerdict.Rejected(reasons)

        if (accepted.isEmpty()) {
            return PackVerdict.Rejected(
                listOf(PackRejection(RejectionKind.EMPTY_CLAIMS, "一个字段都没声明")),
            )
        }

        return PackVerdict.Accepted(
            AcceptedPack(
                manifest = manifest,
                kind = requireNotNull(kind),
                rating = requireNotNull(rating),
                claims = accepted,
            ),
        )
    }

    private fun rootCause(e: Throwable): String =
        (e.message ?: e::class.simpleName ?: "无法解析").lineSequence().first().take(200)
}

/** 一个字段被多个包同时声明。 */
data class FieldConflict(
    val field: PersonaField,
    /** 争夺这个字段的包 id（按 id 排序，保证确定） */
    val holders: List<String>,
)

/**
 * 冲突检测（§6.2）：**一个字段只能有一个占位者**。
 *
 * ⚠️ 检测到冲突**不自动裁决** —— 由用户选（§6.2「用户裁决」）。
 *    自动选一个（比如"先装的赢"）会让用户在某天发现"我叫它改了称呼，它没改"，
 *    而原因是一个他早就忘了的包在占着那个字段。
 */
object PersonaPackConflicts {

    fun detect(packs: List<AcceptedPack>): List<FieldConflict> {
        val byField = mutableMapOf<PersonaField, MutableList<String>>()
        for (pack in packs) {
            for (field in pack.claims) {
                byField.getOrPut(field) { mutableListOf() } += pack.manifest.id
            }
        }
        return byField
            .filterValues { it.size > 1 }
            .map { (field, holders) -> FieldConflict(field, holders.sorted()) }
            .sortedBy { it.field.ordinal }
    }

    /**
     * 按用户的裁决算出**最终生效**的包集合。
     *
     * @param decisions 每个有冲突的字段 → 用户选中的包 id
     * @return 未做裁决的冲突字段会被**排除在生效集合之外**（相关包的该字段不生效），
     *   并如实返回还欠哪些裁决 —— 不猜。
     */
    fun resolve(
        packs: List<AcceptedPack>,
        decisions: Map<PersonaField, String>,
    ): ResolutionResult {
        val conflicts = detect(packs)
        val undecided = conflicts.filter { decisions[it.field] == null }.map { it.field }
        if (undecided.isNotEmpty()) {
            return ResolutionResult(effective = emptyList(), pendingDecisions = undecided)
        }

        val disabled = mutableSetOf<String>()
        for (conflict in conflicts) {
            val winner = decisions.getValue(conflict.field)
            // 输掉的那个包**整体禁用**（而不是"只让出这一个字段"）——
            // 部分生效的包是最难解释的状态：用户以为装了 A，实际一半是 B。
            conflict.holders.filter { it != winner }.forEach { disabled += it }
        }
        return ResolutionResult(
            effective = packs.filterNot { it.manifest.id in disabled },
            pendingDecisions = emptyList(),
        )
    }

    /** 包 id 不重复、且每条冲突都有裁决之后才算「已裁决」。 */
    fun duplicateIds(packs: List<AcceptedPack>): List<String> =
        packs.groupBy { it.manifest.id }.filterValues { it.size > 1 }.keys.sorted()
}

data class ResolutionResult(
    /** 最终生效的包（无冲突时就是全部） */
    val effective: List<AcceptedPack>,
    /** 还欠用户裁决的字段。非空时 [effective] 为空 —— **不猜** */
    val pendingDecisions: List<PersonaField>,
)

/**
 * 分级过滤 —— **可见地**过滤（§6.4）。
 *
 * ⚠️ 返回被隐藏的**数量与名单**，而不是只给过滤后的列表：
 *    静默过滤会让用户以为"社区里就这些"。UI 必须能说出
 *    「有 N 个包被你当前的设置隐藏了」。
 */
data class RatingFilterResult(
    val visible: List<AcceptedPack>,
    val hidden: List<AcceptedPack>,
) {
    /** 给界面用的一句话。没有隐藏项时返回 null（**不显示"隐藏了 0 个"**这种噪音）。 */
    fun notice(): String? =
        if (hidden.isEmpty()) null
        else "有 ${hidden.size} 个包被你当前的内容分级设置隐藏了。"

    companion object {
        fun apply(packs: List<AcceptedPack>, showMature: Boolean): RatingFilterResult {
            val (visible, hidden) = packs.partition {
                showMature || it.rating == MaturityRating.ALL_AGES
            }
            return RatingFilterResult(visible, hidden)
        }
    }
}

/**
 * 内容级**打包可复现**的纯逻辑anchor：内容指纹。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 「可复现」的判据是「同样的输入两次得到同一串字节」
 * ═══════════════════════════════════════════════════════════════
 *
 * 这里做的是**规范化**：把清单折成一个**顺序固定、无冗余**的字符串再哈希。
 * 三件事必须钉死，否则同样的包会得到不同的指纹（而指纹一旦不同，
 * 校验、订阅源比对、增量更新全部失效）：
 *
 * 1. **字段顺序固定**：手写顺序，不靠 Map 迭代顺序；
 * 2. **集合排序**：`claims` 排序后拼接 —— 声明顺序不影响包的内容；
 * 3. **null 有明确表示**：用 `∅` 占位，不用空串 ——
 *    否则"作者字段是空串"与"没有作者"会撞成同一个指纹。
 */
object PersonaPackCanonical {

    fun canonicalize(manifest: PersonaPackManifest): String = buildString {
        append("id=").append(manifest.id.trim()).append('\n')
        append("name=").append(manifest.name.trim()).append('\n')
        append("version=").append(manifest.version.trim()).append('\n')
        append("kind=").append(manifest.kind.trim().lowercase()).append('\n')
        append("rating=").append(manifest.rating.trim().lowercase()).append('\n')
        append("author=").append(manifest.author?.trim() ?: NULL).append('\n')
        append("license=").append(manifest.license?.trim() ?: NULL).append('\n')
        append("description=").append(manifest.description?.trim() ?: NULL).append('\n')
        append("anchors=").append(manifest.providesAnchors).append('\n')
        append("voice=").append(manifest.voiceHint?.trim() ?: NULL).append('\n')
        // ★ 排序：声明顺序不该影响包的身份
        append("claims=").append(manifest.claims.map { it.trim() }.sorted().joinToString(",")).append('\n')
    }

    /**
     * 内容指纹（SHA-256 十六进制，前 16 位足够做同一性判断）。
     *
     * ⚠️ 用 SHA-256 而不是 `String.hashCode`：后者只有 32 位且极易碰撞，
     *    而这里的用途是"两个包是不是同一个" —— 碰撞意味着**装错了包**。
     */
    fun fingerprint(manifest: PersonaPackManifest): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalize(manifest).toByteArray(Charsets.UTF_8))
        // ⚠️ 显式 `and 0xFF` 再转十六进制，**不要**用 `"%02x".format(byte)` ——
        //    后者的入参是 Byte 时，负值会走补码路径（可能产出 8 个字符）。
        //    那样得到的虽仍然确定，却是一串没人能复算的指纹。
        return digest.joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }.take(FINGERPRINT_LENGTH)
    }

    private const val NULL = "∅"
    private const val FINGERPRINT_LENGTH = 16
}
