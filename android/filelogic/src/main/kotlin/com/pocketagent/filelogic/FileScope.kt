package com.pocketagent.filelogic

/**
 * 用户授权的一个目录（SAF tree）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ [token] 刻意是一个**不透明字符串**
 * ═══════════════════════════════════════════════════════════════
 *
 * 它是 SAF 的 tree URI（形如
 * `content://com.android.externalstorage.documents/tree/primary%3ADocuments`）。
 *
 * 本模块**不解析它、不校验它、也不认识它** —— 只负责原样传给 Android 层的通道实现。
 * 理由有两条，都是硬的：
 *
 * 1. **解析 URI 需要知道 provider 的私有格式**，而 `com.android.externalstorage`
 *    与第三方云盘 provider（如 `com.google.android.apps.docs.storage`）的
 *    document id 编码**完全不同**。一旦这里开始解析，"判定逻辑"就会
 *    与某个具体 provider 绑死，换一个 provider 就静默失效。
 * 2. 更实际的：解析 URI 需要 `android.net.Uri`。引入它，本模块就再也进不了
 *    `run_logic_tests.py` —— 而这一层恰恰是最需要离线测试的。
 *
 * 所以分工是：**本层算路径、Android 层认令牌。**
 */
data class ScopeRoot(
    /** 稳定标识。用于审计日志与"这条授权是哪一条"。 */
    val id: String,

    /** 给用户看的名字，如「文档」「下载」。**不要塞 URI 进去** —— 用户看不懂。 */
    val displayName: String,

    /**
     * 已归一化的绝对路径，如 `/storage/emulated/0/Documents`。
     *
     * ⚠️ 它**只用于展示与判定**，不用于实际操作 —— 实际操作走 [token]。
     *    两者可能不完全对应（云盘 provider 的目录没有本地路径），
     *    那时 [path] 是一个尽力而为的展示值。
     */
    val path: String,

    /** SAF tree URI。不透明，本层不理解。 */
    val token: String = "",
) {
    init {
        // 归一化必须是"已经做过的"。未归一化的根会让 isWithin 的比较失去意义。
        when (val r = PathNormalizer.normalize(path)) {
            is PathNormalizer.Result.Rejected ->
                throw IllegalArgumentException("ScopeRoot.path 无法归一化：${r.reason}（path=$path）")

            is PathNormalizer.Result.Valid ->
                require(r.path == path) {
                    "ScopeRoot.path 必须是归一化后的形式：期望「${r.path}」，收到「$path」"
                }
        }

        // ⚠️ 绝不允许把根目录本身作为授权范围。
        //    isWithin 对 ancestor="/" 恒返回 true（根包含一切，语义上没错），
        //    于是"授权 /" 就等于"授权整块存储" —— 而那正是本模块存在的理由。
        require(path != "/") { "不允许把根目录 / 作为授权范围 —— 那等于授权整台设备" }
    }
}

/**
 * 位置黑名单 —— **即使某个路径落在授权范围内，命中这里也一律拒绝**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 与 `SensitiveRules` 同一条纪律：只能加严，不能放宽
 * ═══════════════════════════════════════════════════════════════
 *
 * [withUserRules] 是**唯一**的修改入口，且只支持追加 ——
 * 它没有、也不该有 `removeSegment` 之类的兄弟方法。
 *
 * 为什么：用户把授权范围划大（比如直接选了整块内部存储）是完全正常的行为，
 * 但那不该让 `.ssh` 里的私钥变得可读。**范围是用户给的，底线不是。**
 * 如果黑名单可以被用户设置覆盖，那么一次"为了方便"的配置就解除了全部保护，
 * 而用户不会意识到自己刚刚做了什么。
 */
data class DenyRules(
    /** 路径中任一**段**恰好等于其中之一 → 拒绝。用于 `.ssh` 这类目录名。 */
    val deniedSegments: Set<String> = Builtin.deniedSegments,

    /** 最后一个段**以**其中之一结尾 → 拒绝。用于 `.pem` 这类扩展名。 */
    val deniedSuffixes: Set<String> = Builtin.deniedSuffixes,

    /** 最后一个段恰好等于其中之一 → 拒绝。用于 `id_rsa` 这类固定文件名。 */
    val deniedNames: Set<String> = Builtin.deniedNames,

    /**
     * **连续段序列**。路径里出现任意一条这样的连续序列 → 拒绝。
     *
     * 存在的理由：`Android/data` 这种规则**不能拆成段** ——
     * 单看 `data` 太宽（用户完全可能有 `/Documents/data/报表.csv`），
     * 单看 `Android` 也一样。只有"这两个段挨在一起"才是那个意思。
     */
    val deniedSegmentRuns: List<List<String>> = Builtin.deniedSegmentRuns,
) {

    /**
     * 追加用户规则。
     *
     * ⚠️ **这是唯一的修改入口，且只支持追加。**
     *    不要为它加"移除内置规则"的能力 —— 那会让"底线不可协商"
     *    变成一句空话。同 `SensitiveRules.withUserRules`。
     */
    fun withUserRules(
        extraSegments: Set<String> = emptySet(),
        extraSuffixes: Set<String> = emptySet(),
        extraNames: Set<String> = emptySet(),
        extraRuns: List<List<String>> = emptyList(),
    ): DenyRules = copy(
        deniedSegments = deniedSegments + extraSegments,
        deniedSuffixes = deniedSuffixes + extraSuffixes,
        deniedNames = deniedNames + extraNames,
        deniedSegmentRuns = deniedSegmentRuns + extraRuns,
    )

    /**
     * 命中判定。[normalizedPath] 必须已归一化。
     *
     * ⚠️ **命中顺序即报告顺序**，不是随手排的：
     *    先报"位置"类（`Android/data`、应用私有目录），再报"文件"类
     *    （`id_rsa`、`.pem`）。因为对用户来说，前者是"这个地方我碰不得"，
     *    后者是"这个文件我碰不得" —— 当两者同时成立时，
     *    说出位置更接近用户真正需要理解的事。
     */
    fun matches(normalizedPath: String): DenyHit? {
        val segs = PathNormalizer.segments(normalizedPath)
        if (segs.isEmpty()) return null

        deniedSegmentRuns.firstOrNull { containsRun(segs, it) }
            ?.let { return DenyHit.Run(it) }

        val leaf = segs.last()

        deniedNames.firstOrNull { it == leaf }
            ?.let { return DenyHit.Name(it) }

        deniedSuffixes.firstOrNull { leaf.endsWith(it) }
            ?.let { return DenyHit.Suffix(it) }

        segs.firstOrNull { it in deniedSegments }
            ?.let { return DenyHit.Segment(it) }

        return null
    }

    private fun containsRun(segs: List<String>, run: List<String>): Boolean {
        if (run.isEmpty() || run.size > segs.size) return false
        outer@ for (start in 0..segs.size - run.size) {
            for (i in run.indices) {
                if (segs[start + i] != run[i]) continue@outer
            }
            return true
        }
        return false
    }

    companion object {
        /**
         * 内置黑名单。
         *
         * ⚠️ 维护要求：**每次新增通道（尤其 Shizuku）时都要复核这份清单。**
         *    SAF 够不到 `/data/data`，但 Shizuku 能 —— 一条只对 SAF 生效的
         *    黑名单在换通道的那一刻就失效了，而这种失效没有任何提示。
         */
        object Builtin {
            val deniedSegments: Set<String> = setOf(
                // ── 密钥与身份 ──
                ".ssh",
                ".gnupg",
                ".pki",
                ".android_secure",   // 应用加密数据（旧版 App2SD）
                // ── 我们自己的东西 ──
                // ⚠️ 应用私有目录里的 dsh 草稿区。用户看不到它，
                //    而 agent 能读到它等于读到了我们生成的凭据文件。
                ".pocketagent",
            )

            val deniedSuffixes: Set<String> = setOf(
                ".keystore",
                ".jks",
                ".p12",
                ".pfx",
                ".pem",
                ".key",
                ".pkcs12",
            )

            val deniedNames: Set<String> = setOf(
                "id_rsa",
                "id_ed25519",
                "id_ecdsa",
                "id_dsa",
                ".netrc",
                ".git-credentials",
            )

            val deniedSegmentRuns: List<List<String>> = listOf(
                // 其他应用的私有数据。Android 11+ 本来就读不到，
                // 但"读不到"与"我们说清了为什么读不到"是两件事 ——
                // 前者用户看到的是一个 IOException，后者是一句人话。
                listOf("Android", "data"),
                listOf("Android", "obb"),
                // 我们自己的私有目录。SAF 够不到，Shizuku 能 ——
                // 所以这两条是给未来的通道准备的防御。
                // ⚠️ 新增构建变体（新的 applicationIdSuffix）时要在这里加一行。
                listOf("data", "data", "com.pocketagent"),
                listOf("data", "data", "com.pocketagent.debug"),
                listOf("data", "user", "0", "com.pocketagent"),
                listOf("data", "user", "0", "com.pocketagent.debug"),
            )
        }
    }
}

/** 黑名单命中详情。用于生成给用户看的说明，以及写进审计日志。 */
sealed interface DenyHit {
    /** 命中了某个连续段序列，如 `Android/data` */
    data class Run(val run: List<String>) : DenyHit

    /** 命中了固定文件名，如 `id_rsa` */
    data class Name(val name: String) : DenyHit

    /** 命中了扩展名，如 `.pem` */
    data class Suffix(val suffix: String) : DenyHit

    /** 命中了段名，如 `.ssh` */
    data class Segment(val segment: String) : DenyHit

    /**
     * 给用户看的说明。
     *
     * ⚠️ 措辞里**不要出现路径** —— 路径由调用方在上下文中展示，
     *    这里只解释"为什么这个地方不能碰"。两者混在一起会让文案变长到没人读。
     */
    val userMessage: String
        get() = when (this) {
            is Run -> when (run) {
                listOf("Android", "data"), listOf("Android", "obb") ->
                    "这里是其他应用的私有数据，系统不允许任何应用读取。"
                else -> "这个位置属于 PocketAgent 自己的私有目录，我不会去动它。"
            }
            is Name -> "「$name」是密钥类文件，我不会读取或修改它。"
            is Suffix -> "「$suffix」是密钥类文件，我不会读取或修改它。"
            is Segment -> "「$segment」是存放密钥或凭据的位置，我不会碰它。"
        }
}

/**
 * 用户当前授权的文件范围。
 *
 * 由 SAF 授权结果构造（Android 层），由 [FileAccessDecider] 消费。
 * **本类型本身不做任何判定** —— 判定在 [PathNormalizer] 与 [FileAccessDecider]，
 * 这样"范围是什么"与"能不能动"可以分开测试。
 */
data class FileScope(
    val roots: List<ScopeRoot> = emptyList(),
    val denyRules: DenyRules = DenyRules(),
) {

    /** 用户是否还没授权任何目录。界面据此引导去选目录，而不是报"操作失败"。 */
    val isEmpty: Boolean get() = roots.isEmpty()

    /**
     * 找出包含 [normalizedPath] 的授权根。没有则返回 null。
     *
     * ⚠️ **取最深的一个**。用户完全可能既授权了整块存储、又单独授权了
     *    某个子目录（后者带更精确的 token）。这时应当用子目录那条 ——
     *    它生成的相对路径更短，通道实现少走几层；而且如果外层那条
     *    将来被撤销，内层仍然有效。
     */
    fun rootContaining(normalizedPath: String): ScopeRoot? =
        roots.filter { PathNormalizer.isWithin(normalizedPath, it.path) }
            .maxByOrNull { PathNormalizer.segments(it.path).size }

    /**
     * 追加用户黑名单规则。**只能加严** —— 见 [DenyRules.withUserRules]。
     */
    fun withUserDenies(
        extraSegments: Set<String> = emptySet(),
        extraSuffixes: Set<String> = emptySet(),
        extraNames: Set<String> = emptySet(),
        extraRuns: List<List<String>> = emptyList(),
    ): FileScope = copy(
        denyRules = denyRules.withUserRules(extraSegments, extraSuffixes, extraNames, extraRuns),
    )
}
