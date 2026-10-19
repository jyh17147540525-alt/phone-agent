package com.pocketagent.agentui

/**
 * `@` 面板的分组与排序。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 排序必须是**纯函数**
 * ═══════════════════════════════════════════════════════════════
 *
 * 面板每敲一个字都要重排。如果排序里掺了"当前时间""上次用的时间"
 * 这类会漂的量，同一个输入在不同时刻会给出不同顺序 ——
 * 用户看到的是"列表在乱跳"，而他只会得出一个结论：这东西不听话。
 *
 * ⇒ 输入相同的 `(query, items, now)` 必然给出相同结果，有测试钉住。
 */
object MentionIndex {

    /** 面板分组 —— 顺序即界面顺序。 */
    enum class Group { CAPABILITY, MEMORY, FILE, APP }

    /**
     * 一个可提及项。
     *
     * @param key 稳定 id（工具名 / 记忆 id / 路径 / 包名）
     * @param label 显示名
     * @param group 分组
     * @param lastUsedAt 上次使用时刻；null 表示从未用过
     * @param pinyin 拼音首字母串（由调用方注入，见 [PinyinPort]）
     */
    data class Item(
        val key: String,
        val label: String,
        val group: Group,
        val lastUsedAt: Long? = null,
        val pinyin: String = "",
    ) {
        init {
            require(key.isNotBlank()) { "key 不能为空" }
            require(label.isNotBlank()) { "label 不能为空" }
        }
    }

    /** 匹配强度。数字越小越靠前 —— 顺序有意义。 */
    enum class Match {
        /** 完全等于（大小写不敏感）。 */
        EXACT,

        /** 以查询开头。 */
        PREFIX,

        /** 包含。 */
        CONTAINS,

        /** 拼音首字母命中。 */
        PINYIN,
    }

    /** 一条命中。 */
    data class Hit(val item: Item, val match: Match)

    /**
     * 排序键（四级，全部确定）。
     *
     * 1. 匹配强度（EXACT > PREFIX > CONTAINS > PINYIN）
     * 2. 最近使用（越近越前；null 排最后）
     * 3. 显示名字典序（大小写不敏感）
     * 4. key 字典序 —— 兜底，保证**任意两项都不会相等**
     */
    private val ORDER: Comparator<Hit> = compareBy<Hit>(
        { it.match.ordinal },
        { it.item.lastUsedAt == null },
        { -(it.item.lastUsedAt ?: 0L) },
        { it.item.label.lowercase() },
        { it.item.key },
    )

    /**
     * 检索。
     *
     * @param query 用户已输入的部分（空串 = 全列出）
     * @param items 候选池
     * @param limit 最多返回几条
     */
    fun search(query: String, items: List<Item>, limit: Int = 20): List<Hit> {
        require(limit > 0) { "limit 必须为正" }
        val q = query.trim().lowercase()
        val hits = if (q.isEmpty()) {
            items.map { Hit(it, Match.CONTAINS) }
        } else {
            items.mapNotNull { item ->
                classify(item, q)?.let { Hit(item, it) }
            }
        }
        return hits.sortedWith(ORDER).take(limit)
    }

    private fun classify(item: Item, q: String): Match? {
        val label = item.label.lowercase()
        return when {
            label == q -> Match.EXACT
            label.startsWith(q) -> Match.PREFIX
            label.contains(q) -> Match.CONTAINS
            item.pinyin.lowercase().startsWith(q) -> Match.PINYIN
            else -> null
        }
    }

    /**
     * 分组展示。
     *
     * ★ 记忆组**不按相似度排序**，只按时间倒序：相似度排序会让
     *   "我记得"变成"我猜你指的是这条"，而用户无法反驳一个他没看见的理由。
     *   能力组则按 [search] 的匹配强度排。
     */
    fun grouped(query: String, items: List<Item>, perGroup: Int = 5): Map<Group, List<Item>> {
        require(perGroup > 0) { "perGroup 必须为正" }
        val out = LinkedHashMap<Group, List<Item>>()
        for (group in Group.entries) {
            val pool = items.filter { it.group == group }
            val picked = when (group) {
                Group.MEMORY -> pool.sortedWith(
                    compareBy<Item> { it.lastUsedAt == null }
                        .thenByDescending { it.lastUsedAt ?: 0L }
                        .thenBy { it.key },
                )
                else -> search(query, pool, perGroup).map { it.item }
            }
            if (picked.isNotEmpty()) out[group] = picked.take(perGroup)
        }
        return out
    }
}

/**
 * 汉字 → 拼音首字母。
 *
 * ⚠️ 为什么做成**端口**而不是内置一张全量表：一级汉字表约 3755 项，
 *    塞进这个纯逻辑模块会让它多出上百 KB 常量，而收益只是"打 `wx` 能找到「微信」"。
 *    端口让 Android 侧注入真正的转换器（或一个精简表），
 *    而本模块的排序逻辑保持零数据、可离线断言。
 *
 * 默认实现只做小写化 —— 它让纯拉丁名（`screen_read`）的拼音匹配可用，
 * 对汉字则返回空串（不匹配，而不是错匹配）。
 */
fun interface PinyinPort {
    fun initials(text: String): String

    companion object {
        /** 只处理拉丁字符的默认实现。 */
        val ASCII_ONLY: PinyinPort = PinyinPort { text ->
            text.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.lowercase()
        }
    }
}
