package com.pocketagent.plugin.api

/**
 * 插件包（`.pagent`）的解析约束。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么要有这个对象
 * ═══════════════════════════════════════════════════════════════
 *
 * `.pagent` 本质是个 zip。而**解压用户/网络提供的 zip 是本应用最危险的一个动作** ——
 * 它比"加载插件"本身还危险，因为解压发生在任何校验之前。
 *
 * 两个经典攻击，都在解压这一层：
 *
 * **1. Zip Slip（路径穿越）**
 * ```
 * ../../../../data/data/com.pocketagent/databases/plugins.db
 * ```
 * 解压器如果直接 `File(destDir, entryName)`，这个条目就会写到插件目录**外面**，
 * 覆盖本体的数据库、配置，甚至往 `shared_prefs` 里塞东西。用 `..` 就能越狱。
 *
 * **2. Zip Bomb（压缩炸弹）**
 * 一个 42 KB 的 zip 可以解出 4 GB。手机上直接 OOM 崩掉 ——
 * 而崩掉的应用会**丢掉无障碍权限**（见社区分发版方案的"受限设置"一节），
 * 用户得重新走一遍引导流程。所以这不只是"体验问题"。
 *
 * 因此本对象把"哪些条目名可以接受"和"解压预算是否超支"抽成**纯函数**，
 * 放在零 Android 依赖的 plugin/api 里 —— 这样它们能被单测覆盖。
 * 解压代码写错了会照样跑通，不测等于没写。
 *
 * ⚠️ 这里只提供**判断**，不提供解压。解压由 app 层做，但它必须逐条目调用
 *    [isSafeEntryName] 并在超预算时中止。**不要绕过。**
 */
object PluginBundle {

    /** 插件包的扩展名 */
    const val EXTENSION = ".pagent"

    /** 包内清单文件名 */
    const val MANIFEST_NAME = "plugin.json"

    /** 单个插件包允许的最大条目数。正常插件是个位数 */
    const val MAX_ENTRIES = 512

    /** 解压后总字节上限。正常 L1/L2 插件不到 1 MB */
    const val MAX_UNCOMPRESSED_BYTES = 32L * 1024 * 1024

    /**
     * 压缩比上限。
     *
     * 光看总字节不够：一个 4 GB 的 bomb 会被 [MAX_UNCOMPRESSED_BYTES] 拦住，
     * 但攻击者可以解出刚好 31 MB 的**大量小文件**塞爆 inode，或者用
     * 高压缩比在解压途中吃掉 CPU。压缩比是第二道闸。
     *
     * 200 已经很宽松 —— 正常插件的 JSON/JS 文本压缩比通常在 5~20 倍。
     */
    const val MAX_COMPRESSION_RATIO = 200

    /**
     * 这个条目名是否安全。
     *
     * 规则（**白名单式**，不是黑名单式 —— 黑名单永远漏）：
     *
     *  - 不能为空
     *  - 不能含 NUL（`\u0000`）—— 会被某些 API 截断，绕过扩展名检查
     *  - 不能含反斜杠 —— 只接受正斜杠。反斜杠在 Windows 解压器上是分隔符，
     *    在 Linux 上只是普通字符，这种歧义本身就是漏洞温床
     *  - 不能以 `/` 开头（绝对路径）
     *  - 不能形如 `C:`（Windows 盘符）
     *  - 逐段检查：不能有空段、`.`、`..`
     *
     * 目录条目（以 `/` 结尾）会被剥掉尾斜杠后再检查。
     */
    fun isSafeEntryName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.contains('\u0000')) return false
        if (name.contains('\\')) return false
        if (name.startsWith('/')) return false
        // "C:evil.json" 在部分实现里会被当成绝对路径
        if (name.length >= 2 && name[1] == ':') return false

        val body = name.removeSuffix("/")
        if (body.isEmpty()) return false

        return body.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    /** 是否是本应用认可的插件包文件名 */
    fun hasBundleExtension(fileName: String): Boolean =
        fileName.lowercase().endsWith(EXTENSION)

    /**
     * 解压预算检查。
     *
     * 由解压循环**每处理一个条目调用一次**，而不是只在开头检查一次 ——
     * zip 头里声明的 size 是可以撒谎的，只有累计的真实解压字节数不会。
     *
     * @param entryCount 已处理条目数
     * @param uncompressedBytes 累计真实解压字节数
     * @param compressedBytes 累计压缩字节数（用于算压缩比）
     * @return 超预算的原因；null 表示还在预算内
     */
    fun exceedsBudget(
        entryCount: Int,
        uncompressedBytes: Long,
        compressedBytes: Long,
    ): String? = when {
        entryCount > MAX_ENTRIES ->
            "包内文件过多（$entryCount > $MAX_ENTRIES），这不像一个正常的插件包。"

        uncompressedBytes > MAX_UNCOMPRESSED_BYTES ->
            "解压后体积过大（${uncompressedBytes / 1024 / 1024} MB > " +
                "${MAX_UNCOMPRESSED_BYTES / 1024 / 1024} MB），已中止。"

        // 压缩比：只在压缩数据有一定体量后才判，否则一个 100 字节的小文件
        // 就能凑出天文数字的比值，误报率极高
        compressedBytes > MIN_BYTES_FOR_RATIO &&
            uncompressedBytes / compressedBytes > MAX_COMPRESSION_RATIO ->
            "压缩比异常（${uncompressedBytes / compressedBytes} 倍 > " +
                "$MAX_COMPRESSION_RATIO 倍），疑似压缩炸弹，已中止。"

        else -> null
    }

    /** 压缩比判断的最小样本量，避免小文件误报 */
    private const val MIN_BYTES_FOR_RATIO = 64L * 1024
}
