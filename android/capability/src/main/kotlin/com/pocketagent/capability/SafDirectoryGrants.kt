package com.pocketagent.capability

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.pocketagent.filelogic.PathNormalizer
import com.pocketagent.filelogic.ScopeRoot
import timber.log.Timber

/**
 * 用户授权给我们的目录 —— SAF 那条通道的**范围**从哪来。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 核心设计：**我们不自己存授权列表**
 * ═══════════════════════════════════════════════════════════════
 *
 * 直觉做法是"授权时把 URI 存进 SharedPreferences，要用时读出来"。
 * 这里**刻意不那么做**，因为那份自建列表会与系统的那份漂移：
 *
 * | 情形 | 自建列表说 | 系统实际 |
 * |---|---|---|
 * | 用户在系统设置里撤销了权限 | 有 | **没有** |
 * | 应用数据从备份恢复（`allowBackup`） | 有 | **没有**（URI 授权不进备份） |
 * | 应用被卸载重装 | 没有 | 没有 |
 * | 用户清空了系统里那个 provider 的缓存 | 有 | 可能没有 |
 *
 * 漂移的方向永远是"**我们以为能读，其实读不到**" —— 于是每次操作都要
 * 走一遍失败路径才发现在骗自己，而失败信息说的是"文件访问出错"，
 * 指向完全错误的方向。
 *
 * ⇒ 所以本类的**唯一真相来源**是
 *   [ContentResolver.getPersistedUriPermissions] —— 那份列表由系统维护、
 *   跨重启有效、且与"能不能真的读写"永远一致。
 *   我们只在它之上加两件事：把 tree URI 翻译成 [ScopeRoot]（含展示名与路径），
 *   以及发起/撤销授权。
 *
 * ⚠️ 这也是为什么本类**没有任何 `save` / `load` 方法** —— 不是漏了。
 */
class SafDirectoryGrants(
    private val context: Context,
) {

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * 现在有效的授权，翻译成 [ScopeRoot]。
     *
     * ⚠️ 翻译不出来（不是"设备存储"提供者、或 documentId 形状不认识）的授权
     *    **会被跳过**，而不是用一个编出来的路径凑数 —— 见 [derivePath]。
     *    跳过的代价是"用户授权了但用不了"，所以 [unsupported] 会把它们
     *    单独列出来，界面要如实说明为什么。
     */
    fun grantedRoots(): List<ScopeRoot> =
        resolver.persistedUriPermissions.mapNotNull { permission ->
            if (!permission.isReadPermission) return@mapNotNull null
            rootOf(permission.uri)
        }

    /**
     * 有效但**我们支持不了**的授权。
     *
     * ★ 必须让界面能显示它。默默忽略的后果是：用户明明授权了一个网盘目录，
     *   界面上却什么都不显示 —— 他会以为"这个按钮坏了"，然后反复授权。
     *   而真相是"我们只支持手机存储里的目录"，那是一句能说清的话。
     */
    fun unsupported(): List<Uri> =
        resolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
            .filter { rootOf(it) == null }

    /** 这一个 URI 现在还能不能真的读写。 */
    fun isStillGranted(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    /**
     * 发起授权。**必须在 `OpenDocumentTree` 拿到结果之后调用。**
     *
     * ⚠️ 只申请 [Intent.FLAG_GRANT_READ_URI_PERMISSION] 与
     *    [Intent.FLAG_GRANT_WRITE_URI_PERMISSION] —— 这正是我们在
     *    `OpenDocumentTree` 的返回值上能拿到的两个标志，多申请别的没有意义。
     *
     * ⚠️ 不申请 `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`：那个标志是
     *    **系统在授予时**加在 Intent 上的（`OpenDocumentTree` 会加），
     *    调用方再传一次不会让它更持久，反而会让人以为"持久化是我们申请的"。
     *
     * @return 成功与否。失败时**不要**静默 —— 那会让用户以为授权好了，
     *         而实际上下次操作会报"还没有授权任何目录"。
     */
    fun take(uri: Uri): Boolean = try {
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    } catch (e: SecurityException) {
        // 这个 URI 不在本次 Intent 的授权范围内（比如调用方拿错了 URI）。
        Timber.w(e, "SAF 持久化授权失败")
        false
    }

    /**
     * 撤销一条授权。
     *
     * ⚠️ 撤销是**真的撤销** —— 系统的 `persistedUriPermissions` 会少一条，
     *    下一次操作会如实报"还没有授权任何目录"。
     *    这正是"可撤销"该有的样子：不是"界面上不再显示"。
     */
    fun release(uri: Uri): Boolean = try {
        resolver.releasePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    } catch (e: SecurityException) {
        Timber.w(e, "SAF 撤销授权失败")
        false
    }

    private fun rootOf(uri: Uri): ScopeRoot? {
        val path = derivePath(uri) ?: return null
        val displayName = displayNameOf(path)

        return ScopeRoot(
            // ⚠️ id 用 URI 字符串本身，不用序号或路径 ——
            //    序号会在撤销一条之后整体错位，而路径可能重复（同一个目录
            //    被授权两次时）。审计日志要靠 id 回答"这条操作是哪条授权下的"。
            id = uri.toString(),
            displayName = displayName,
            path = path,
            token = uri.toString(),
        )
    }

    /** 展示名：取路径的最后一段。根目录那种情况给个能读懂的名字。 */
    private fun displayNameOf(path: String): String =
        PathNormalizer.leafOf(path).ifBlank { "存储空间" }

    /**
     * tree URI → 已归一化的绝对路径。**认不出来时返回 null。**
     *
     * ═══════════════════════════════════════════════════════════════
     *  ⚠️ 为什么"认不出来"时返回 null 而不是编一个路径
     * ═══════════════════════════════════════════════════════════════
     *
     * [ScopeRoot.path] 的用途是**与 agent 请求的路径做字符串匹配**
     * （见 `FileScope.rootContaining`）。所以一个编出来的路径不是
     * "展示得难看一点"那么简单 —— 它会让**另一条**路径被判定为"在范围内"，
     * 而那条路径可能指向完全不同的地方。
     *
     * 云盘 provider 的目录**根本没有本地路径**，这是它们的本质而不是缺陷。
     * 所以正确答案是：认不出来就不收，并在界面上说清楚为什么。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ⚠️ 目前只支持"设备存储"提供者（`com.android.externalstorage.documents`）
     * ═══════════════════════════════════════════════════════════════
     *
     * 它的 documentId 形状是 `<volumeId>:<相对路径>`，而 volumeId 能映射到
     * 真实的挂载点 —— 所以路径是**真的**，不是猜的：
     *
     * ```
     * primary:Documents/Work   →  /storage/emulated/0/Documents/Work
     * 1A2B-3C4D:Music          →  /storage/1A2B-3C4D/Music
     * ```
     *
     * ⚠️ **已知不足**：`/sdcard` 是指向 `/storage/emulated/0` 的符号链接，
     *    而 `PathNormalizer` 只做字符串运算、不解析符号链接（那是刻意的，
     *    见它的类注释）。所以如果 agent 用 `/sdcard/Documents/a.txt`
     *    去请求，**匹配不上**我们注册的 `/storage/emulated/0/Documents`，
     *    会被拒绝为"不在授权范围内"。
     *
     *    这个失败方向是**安全**的（拒绝而不是放行），而且用户能看到
     *    界面上的路径就是 `/storage/emulated/0/...`，照着用就不会踩到。
     *    彻底解决要么在归一化里加符号链接解析（会破坏它的纯字符串性质，
     *    也让离线测试失效），要么让界面同时展示两种写法。
     *    **当前记为已知取舍，不在这一轮解决。**
     */
    private fun derivePath(uri: Uri): String? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null

        val documentId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "SAF 的 URI 不是 tree URI")
            return null
        }

        val volumeId = documentId.substringBefore(':', missingDelimiterValue = "")
        val relative = documentId.substringAfter(':', missingDelimiterValue = "")

        if (volumeId.isEmpty()) return null

        val base = when (volumeId) {
            "primary" -> primaryStoragePath()
            // 可移动存储（SD 卡）的 volumeId 是卷的 UUID，挂载点就是它的名字。
            else -> "/storage/$volumeId"
        }

        val raw = if (relative.isEmpty()) base else "$base/$relative"

        // 归一化一次，让 [ScopeRoot] 的构造断言能过，也让两边形式一致。
        return when (val normalized = PathNormalizer.normalize(raw)) {
            is PathNormalizer.Result.Valid -> normalized.path
            is PathNormalizer.Result.Rejected -> {
                Timber.w("SAF 授权路径无法归一化：%s", normalized.reason)
                null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun primaryStoragePath(): String = Environment.getExternalStorageDirectory().absolutePath

    companion object {
        /**
         * "设备存储"提供者。
         *
         * ⚠️ 用**字面量**而不是某个常量：这个 authority 是系统组件的一部分，
         *    没有公开常量，而它是稳定的（从 API 19 至今未变）。
         */
        const val EXTERNAL_STORAGE_AUTHORITY: String =
            "com.android.externalstorage.documents"
    }
}
