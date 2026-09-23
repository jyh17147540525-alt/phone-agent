package com.pocketagent.filelogic

/**
 * 路径归一化与包含判定 —— **本模块最关键的一段代码**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它守的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户通过系统文件选择器授权了一个目录，比如「文档」。从那以后，
 * **这一层是唯一挡在「授权一个目录」与「授权整块存储」之间的东西**。
 *
 * 具体的失败形态（全部静默）：
 *
 * | 漏了什么 | 后果 |
 * |---|---|
 * | `..` 消解不彻底 | `/Documents/../../DCIM` 被当成在文档目录内 → 读到相册 |
 * | 包含判定写成字符串前缀 | `/Documents-evil` 的字符串前缀恰好是 `/Documents` → 越界 |
 * | 没拒 NUL 字符 | 底层调用被截断，实际操作的可能是另一个路径 |
 * | 对路径做了 Unicode 归一化 | 全角句点 `．` 被折成 `.`，凭空造出 `..` |
 *
 * 没有一条会抛异常。没有一条在真机上一眼看得出来。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 四条"**故意不做**"的事，每一条都是踩过或差点踩到的坑
 * ═══════════════════════════════════════════════════════════════
 *
 * **1. 不做 Unicode 归一化（NFKC）。**
 *    `SensitiveDetector.normalize` 用了 NFKC，那是**对的** —— 那里要把全角
 *    `立即付款` 和半角 `立即付款` 视作同一个词。但路径不是自然语言：
 *    文件系统只认 ASCII 的 `U+002E` 是"当前目录"。做了 NFKC 之后，
 *    一个叫 `．．` 的目录名（全角句点）会被折成 `..`，于是
 *    **归一化本身制造出一次路径穿越**。所以这里逐字节比较。
 *
 * **2. 不做大小写折叠。**
 *    Android 的 `/sdcard` 底下是 ext4 / f2fs，**大小写敏感**。
 *    而某些 SAF provider（云盘）不敏感。两边的行为不一致时，
 *    "折叠"会让 `/Documents/Secret` 与 `/documents/secret` 混为一谈 ——
 *    那是**放行**方向。宁可拒绝一个本来合法的路径，也不放行一个可疑的。
 *    代价写在这里：真机上如果遇到"明明授权了却说不匹配"，
 *    第一件事就是查大小写，而不是改这里的判定。
 *
 * **3. 不做百分号解码。**
 *    SAF 的 tree URI 是 `primary%3ADocuments` 这种形式。**解码是 Android 层的责任**，
 *    必须在调用本层**之前**完成。理由：解码放在这里，"解一次"与"解两次"
 *    会得到不同结果（`%252E` → `%2E` → `.`），而这是**双重解码攻击**的经典入口。
 *    本层收到的字符串就是最终路径，多解一次都是漏洞。
 *
 * **4. 不解析符号链接。**
 *    `/sdcard` 就是指向 `/storage/emulated/0` 的符号链接。解析它需要访问
 *    文件系统 —— 一旦引入就把本模块拖出离线验证器的范围（本项目最贵的一课）。
 *    所以约定：**Android 层必须传 `canonicalPath`（已解析符号链接）**，
 *    本层只做字符串运算。取不到 canonical 路径时，Android 层应当**拒绝这次操作**，
 *    而不是退回原路径 —— 见 [Result.Rejected] 的说明。
 */
object PathNormalizer {

    const val SEP: Char = '/'

    private const val SEP_STR = "/"

    /**
     * 归一化结果。
     *
     * ⚠️ **没有"尽力而为"这一档。** 归一化失败必须是明确的 [Rejected]，
     *    因为下游要拿这个字符串去操作真实文件。一个"大致对"的路径
     *    会静默地作用在另一个文件上 —— 那比直接失败危险得多。
     */
    sealed interface Result {
        /** 归一化成功。[path] 是唯一的规范形式。 */
        data class Valid(val path: String) : Result

        /**
         * 拒绝。
         *
         * [reason] 是给用户看的话（"路径越过了根目录"），不是内部错误码 ——
         * 按项目纪律，用户需要知道为什么被拒，否则只会觉得"这软件坏了"。
         */
        data class Rejected(val reason: String) : Result
    }

    /**
     * 归一化成唯一的规范形式。
     *
     * 规则（顺序即实现顺序）：
     * 1. 去首尾空白；空 → 拒
     * 2. 含 NUL → 拒
     * 3. `\` 统一成 `/`
     * 4. 必须以 `/` 开头（只接受绝对路径）→ 否则拒
     * 5. 逐段消解：丢弃空段与 `.`；`..` 弹出上一段
     * 6. `..` 弹出到根之上 → 拒（**不夹到根**，理由见下）
     * 7. 去掉尾部 `/`（根 `/` 除外）
     *
     * ⚠️ **第 6 步为什么是"拒"而不是"夹到根"**：
     *    `/a/../../Documents` 夹到根会得到 `/Documents`。如果用户恰好
     *    授权了 `/Documents`，这次越界就**静默变成了合法操作**。
     *    而它本来是攻击信号（只有构造出来的路径才会越根）。
     *    拒绝是唯一保守的选择。
     *
     * ⚠️ 注意第 6 步与"正常的向上走"要分开：`/Documents/Work/../Notes`
     *    → `/Documents/Notes`，这是**合法**的归一化，不该拒。
     *    只有弹出到**根之上**才拒。
     */
    fun normalize(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.Rejected("路径为空")

        // NUL 会让底层 C 调用在此处截断 —— 我们看到的路径与实际操作的不是同一个。
        // Kotlin 的 File API 会拒绝，但拒绝的时机和方式不可控，这里显式拦。
        if (trimmed.indexOf('\u0000') >= 0) {
            return Result.Rejected("路径含有非法字符（空字符）")
        }

        val unified = trimmed.replace('\\', SEP)

        if (!unified.startsWith(SEP)) {
            return Result.Rejected("只接受绝对路径，收到的是「$raw」")
        }

        val out = ArrayList<String>()
        var escapedRoot = false

        for (seg in unified.split(SEP)) {
            when (seg) {
                // 空段来自重复分隔符（`//`）或首尾分隔符 —— 都不是真实目录名
                "", "." -> Unit

                ".." -> {
                    if (out.isEmpty()) {
                        escapedRoot = true
                        break
                    }
                    out.removeAt(out.lastIndex)
                }

                else -> out.add(seg)
            }
        }

        if (escapedRoot) {
            return Result.Rejected("路径越过了根目录（`..` 过多）")
        }

        return Result.Valid(SEP_STR + out.joinToString(SEP_STR))
    }

    /**
     * [child] 是否在 [ancestor] **之内**（含相等）。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 必须是**段级**比较，不能是字符串前缀
     * ═══════════════════════════════════════════════════════════════
     *
     * 这是最容易写错、也最危险的一处。看反例：
     *
     * ```
     * "/storage/emulated/0/Documents-evil/x".startsWith("/storage/emulated/0/Documents")
     * // → true   但它显然不在 Documents 目录里
     * ```
     *
     * 用 `startsWith` 的版本会**通过所有"正常路径"的测试**，只在攻击构造下暴露。
     * 所以这里先切成段再逐段比。
     *
     * ⚠️ 两个入参都必须**已经归一化**（[normalize] 的产物）。传入未归一化的
     *    字符串会得到无意义的结果 —— 调用方是 [FileScope]，它保证这一点。
     */
    fun isWithin(child: String, ancestor: String): Boolean {
        val c = segments(child)
        val a = segments(ancestor)

        // 父比子长 → 不可能包含
        if (a.size > c.size) return false

        for (i in a.indices) {
            if (a[i] != c[i]) return false
        }
        return true
    }

    /**
     * [path] 相对 [ancestor] 的路径。不在其内时返回 null。
     *
     * 用于把「绝对路径」翻译成「通道能用的相对路径」——
     * SAF 的 `DocumentFile` 只能从 tree 根逐段往下走。
     *
     * 相等时返回空串（不是 null）—— 那是"就是这个目录本身"，
     * 与"不在其内"是两回事，调用方需要区分。
     */
    fun relativeTo(path: String, ancestor: String): String? {
        if (!isWithin(path, ancestor)) return null
        val c = segments(path)
        val a = segments(ancestor)
        return c.drop(a.size).joinToString(SEP_STR)
    }

    /** 切段。`/a/b` → `["a", "b"]`；`/` → `[]`。 */
    fun segments(path: String): List<String> =
        path.split(SEP).filter { it.isNotEmpty() }

    /**
     * 父目录。`/a/b` → `/a`；`/a` → `/`；`/` → null。
     *
     * ⚠️ `/a` 的父目录是 `/`，而 `/` 的父目录是 null（不存在）。
     *    把 `/` 的父目录返回成 `/` 会让"向上找授权根"的循环变成死循环。
     */
    fun parentOf(path: String): String? {
        val segs = segments(path)
        if (segs.isEmpty()) return null
        if (segs.size == 1) return SEP_STR
        return SEP_STR + segs.dropLast(1).joinToString(SEP_STR)
    }

    /** 最后一段。`/a/b.md` → `b.md`；`/` → 空串。 */
    fun leafOf(path: String): String = segments(path).lastOrNull() ?: ""

    /** 扩展名（小写，不含点）。没有扩展名时返回空串。 */
    fun extensionOf(path: String): String {
        val leaf = leafOf(path)
        val dot = leaf.lastIndexOf('.')
        // `dot == 0` 是隐藏文件（`.gitignore`），它**没有**扩展名 ——
        // 把 `.gitignore` 的扩展名当成 "gitignore" 会让后缀类规则误伤一片
        if (dot <= 0 || dot == leaf.lastIndex) return ""
        return leaf.substring(dot + 1).lowercase()
    }
}
