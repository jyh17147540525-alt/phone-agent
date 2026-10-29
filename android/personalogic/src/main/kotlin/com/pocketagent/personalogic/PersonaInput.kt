package com.pocketagent.personalogic

/**
 * **表单输入 → [CustomPersona]** 的唯一映射 —— 它**不允许失败**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件的存在原因是一次真实的、反复发生的功能失效
 * ═══════════════════════════════════════════════════════════════
 *
 * 真机现象（用户报了五次）：填完角色设定、点保存，设定**变空了**。
 *
 * 根因链条（对着设备上的 `custom_persona.preferences_pb` 确认过）：
 *
 *   ① `PersonaScreen` 在**组合期**用 `CustomPersona(...)` 造一个预览对象，
 *      外面包了 `runCatching`
 *   ② 而 `CustomPersona.init` 有两条 `require`：名字 ≤ 24 字、口癖 ≤ 8 条
 *   ③ 用户输入一旦越过这两条（例如把一段设定粘进了「名字」，
 *      或多写了几个「常说的话」）⇒ 构造抛异常
 *   ④ `runCatching` 的兜底是一个**空壳**：
 *      `CustomPersona(id = "draft", name = "（未命名）")`
 *   ⑤ 而保存按钮的可用条件看的是**输入框原文**，仍然可点
 *   ⑥ 点保存 ⇒ **那个空壳被写进了盘**
 *   ⑦ 保存成功后 `initial` 变成那个空壳 ⇒ 表单按 `initial` 重填 ⇒
 *      用户看到的就是「刚填的全没了」
 *
 * 设备上留存的正是：
 * `{"id":"draft","name":"（未命名）","addressUser":"","catchphrases":[]}`
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么修法不是"把按钮变灰 + 报个错"
 * ═══════════════════════════════════════════════════════════════
 *
 * 那样做仍然把**校验失败**当成一种正常可能 —— 用户输入的是"一个人的设定"，
 * 没有哪一种写法是错的。让表单收下一段话，然后用一个 24 字的硬限制
 * 把它判为非法，是**我们的**设计错误，不该由用户去修。
 *
 * ⇒ 正确做法：**映射永不失败**。
 *    · 名字超长 ⇒ 按字（不是按 UTF-16 单元）截断，输入框里当场就能看见
 *    · 口癖超量 ⇒ 取前 [CustomPersona.MAX_CATCHPHRASES] 条
 *    · 作息乱填 ⇒ 夹到 0..23，非数字用默认
 *    · 什么都没填 ⇒ 名字用占位（但占位**永远不会被保存**，见
 *      `CustomPersonaStore.save` 与 [isPlaceholder]）
 *
 * ★ 它是纯 Kotlin（无 `android.*` 依赖），所以能被离线验证器
 *   `tools/verify/run_logic_tests.py` 完整覆盖 —— 包括一条
 *   "任意输入都不许抛异常"的模糊测试。这条模糊测试是这个 bug 的封印。
 */
object PersonaInput {

    /**
     * 表单还没填名字时用的占位。
     *
     * ⚠️ 它**永远不会被保存**：
     *    · `PersonaScreen` 要求真名字才发保存
     *    · `CustomPersonaStore.save` 会**拒绝**占位名（抛异常 → 界面报出来）
     *    · `CustomPersonaStore.decode` 把盘上的占位记录当作"没设过"
     *
     * 三处都拦，是因为这个 bug 的实质就是"占位被当成真数据存进去了"。
     */
    const val PLACEHOLDER_NAME = "（未命名）"

    /** 多值输入的常见分隔符 —— 用户不会只用顿号。 */
    private val SEPARATORS = charArrayOf('、', ',', '，', ';', '；', '\n', '\r')

    /** 是否是一个"占位/空"名字（= 不该被保存的角色）。 */
    fun isPlaceholder(name: String): Boolean {
        val t = name.trim()
        return t.isEmpty() || t == PLACEHOLDER_NAME
    }

    /**
     * 按**字符**（code point）截断，而不是 `take(n)`。
     *
     * ⚠️ `String.take(24)` 数的是 UTF-16 单元：一个 emoji 占两个单元，
     *    切在中间会留下半个代理对 —— 界面上显示成 `�`。
     *    名字里带 emoji 一点都不奇怪，所以这里按真实字符数截。
     *
     * ⚠️ 也**不要手写代理对步进**。第一版就是手写的
     *   （"高代理且下一个还在范围里就跳 2"），遇到"高代理 + 普通字符"
     *   这种不合法配对时，`codePointCount` 数出 2 个字符而我数了 1 个 ——
     *   于是"截到 24 字"实际截出 28 字，构造函数的校验当场抛异常。
     *   ⇒ 数法和截法必须是同一个函数：`offsetByCodePoints` 就是那一个。
     */
    fun clampName(raw: String): String {
        val t = raw.trim()
        if (t.codePointCount(0, t.length) <= CustomPersona.MAX_NAME) return t
        return t.substring(0, t.offsetByCodePoints(0, CustomPersona.MAX_NAME))
    }

    /**
     * 把一段自由输入拆成列表。
     *
     * @param max 最多保留几条（超出部分丢弃，顺序不变）
     */
    fun splitList(raw: String, max: Int = Int.MAX_VALUE): List<String> =
        raw.split(*SEPARATORS)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(max)
            .toList()

    /**
     * 解析一个"几点"输入。
     *
     * ⚠️ 夹到 `0..23` 而不是报错：用户写 25 点不是犯错，是随手。
     *    静默夹取会让界面和他想的不一致，所以调用方要回显真实生效的值。
     */
    fun hour(raw: String, fallback: Int): Int =
        raw.filter { it.isDigit() }.take(2).toIntOrNull()?.coerceIn(0, 23)
            ?: fallback.coerceIn(0, 23)

    /**
     * 组装角色。**任何输入都不会抛异常**。
     *
     * ⚠️ `name` 传空串/占位时仍然会造出一个**可渲染**的对象（名字是占位），
     *    那是给"表单画出来"用的；保存路径必须自己先确认名字非空。
     *    这个区分是本 bug 修复的核心 —— 见 [isPlaceholder]。
     *
     * ★ 表单上没有的字段（[CustomPersona.addressSelf]、`dialect`、
     *   `memories`、`avatarRes`、`tone`）由调用方从 `initial` 透传：
     *   用户改名字不该把他之前写的方言顺手删掉。
     */
    @Suppress("LongParameterList")
    fun toPersona(
        id: String,
        name: String,
        identity: String = "",
        addressUser: String = "",
        addressSelf: String = "",
        relationTo: String = "",
        dialect: String = "",
        gender: PersonaGender = PersonaGender.UNSPECIFIED,
        avatarRes: String? = null,
        catchphrases: String = "",
        memories: List<String> = emptyList(),
        flaws: String = "",
        verbosity: PersonaVerbosity = PersonaVerbosity.NORMAL,
        dailyLife: String = "",
        interests: String = "",
        taboos: String = "",
        sharedReferences: String = "",
        initiative: PersonaInitiative = PersonaInitiative.NORMAL,
        sleepHour: String = "",
        wakeHour: String = "",
    ): CustomPersona {
        val safeName = clampName(name).ifEmpty { PLACEHOLDER_NAME }
        return CustomPersona(
            id = id.ifBlank { "custom" },
            name = safeName,
            identity = identity.trim(),
            addressUser = addressUser.trim(),
            addressSelf = addressSelf.trim(),
            relationTo = relationTo.trim(),
            dialect = dialect.trim(),
            gender = gender,
            // ⚠️ 口癖是**唯一**有数量上限的栏位，而上限现在由这里执行，
            //    不再由构造函数抛异常执行。超出的不报错、只取前几条。
            catchphrases = splitList(catchphrases, CustomPersona.MAX_CATCHPHRASES),
            memories = memories.map { it.trim() }.filter { it.isNotEmpty() },
            flawsRaw = splitList(flaws),
            verbosity = verbosity,
            dailyLife = dailyLife.trim(),
            interests = splitList(interests),
            taboos = splitList(taboos),
            sharedReferences = splitList(sharedReferences),
            initiative = initiative,
            sleepHour = hour(sleepHour, 23),
            wakeHour = hour(wakeHour, 7),
            avatarRes = avatarRes,
        )
    }

    /**
     * 有多少栏真的填了东西（给"已保存"的提示用）。
     *
     * ★ 为什么需要：用户点保存后只看到一句"已保存"，无法判断
     *   **他的内容**是不是真的进去了 —— 而这个 bug 恰恰是
     *   "报成功、存的是空壳"。把有效栏数说出来，等于让他当场就能核对。
     */
    fun filledFields(p: CustomPersona): Int {
        var n = 0
        if (!isPlaceholder(p.name)) n++
        if (p.identity.isNotBlank()) n++
        if (p.relationTo.isNotBlank()) n++
        if (p.addressUser.isNotBlank()) n++
        if (p.catchphrases.isNotEmpty()) n++
        if (p.flawsRaw.isNotEmpty()) n++
        if (p.dailyLife.isNotBlank()) n++
        if (p.interests.isNotEmpty()) n++
        if (p.taboos.isNotEmpty()) n++
        if (p.sharedReferences.isNotEmpty()) n++
        return n
    }
}
