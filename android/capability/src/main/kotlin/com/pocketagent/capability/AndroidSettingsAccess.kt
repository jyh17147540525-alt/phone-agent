package com.pocketagent.capability

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.pocketagent.capabilitylogic.ChannelResult
import com.pocketagent.capabilitylogic.ChannelUnavailableReason
import com.pocketagent.capabilitylogic.SettingNamespace
import com.pocketagent.capabilitylogic.SettingsAccess
import com.pocketagent.capabilitylogic.SettingsPermissionPolicy
import com.pocketagent.core.common.permission.SettingsPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * T0-A 设置通道 —— [SettingsAccess] 的 Android 实现。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么走 `Settings.*` 的 API，而不是 `settings put` 命令
 * ═══════════════════════════════════════════════════════════════
 *
 * 两者的权限要求相同，但**只有 API 路径有返回值**：
 * `Settings.Global.putString` 返回 `Boolean`，而
 * `adb shell settings put …` 在权限不足时也可能以 0 退出。
 *
 * ⇒ 用命令的话，「写成功了」与「权限被拒」在调用方看来**长得一模一样**，
 *   于是只能记一句"失败了"。而那对"去授权"和"命令写错了"都是错的指引。
 *
 * ⚠️ 而且走 API 路径**不需要 shell 通道**。这是第 0 档能在 Shizuku 服务
 *    没跑起来的情况下仍然可用的原因 —— 见 [SettingsPermissionPolicy] 的注释。
 */
class AndroidSettingsAccess(
    private val context: Context,
) : SettingsAccess {

    private val resolver get() = context.contentResolver

    /**
     * 读一项设置。读不到返回 `null`。
     *
     * ⚠️ 读**不需要** `WRITE_SECURE_SETTINGS`，所以「读得到」**不能**用来
     *    证明「写得进」。别把它当可用性探针 —— 那会给出一个假的安全感。
     *
     * ⚠️ 异常处理刻意与 [write] 不同：这里吞掉一切（除了取消）。
     *    因为原值是「有则更好」的信息，而 [CapabilityRunner] 已经有一层兜底 ——
     *    这里再抛一次只是把同一件事报两遍。
     */
    override suspend fun read(namespace: SettingNamespace, key: String): String? =
        try {
            withContext(Dispatchers.IO) {
                when (namespace) {
                    SettingNamespace.GLOBAL -> Settings.Global.getString(resolver, key)
                    SettingNamespace.SYSTEM -> Settings.System.getString(resolver, key)
                    SettingNamespace.SECURE -> Settings.Secure.getString(resolver, key)
                }
            }
        } catch (cancellation: CancellationException) {
            // ⚠️ 必须原样抛出。吞掉它会让协程取消在这里断掉 ——
            //    表现是"点了停止，任务还在跑"，而且不报任何错。
            //    （`runCatching` 在本项目里不能直接用的原因就是它连取消一起吞。）
            throw cancellation
        } catch (ignored: Exception) {
            null
        }

    /**
     * 写一项设置。
     *
     * ⚠️ 权限没给时返回 [ChannelResult.Unavailable]，**不是** [ChannelResult.Failed]。
     *    见 [ChannelUnavailableReason] 的注释：这两者对应的用户动作完全不同。
     */
    override suspend fun write(
        namespace: SettingNamespace,
        key: String,
        value: String,
    ): ChannelResult {
        if (!isGranted(namespace)) {
            return ChannelResult.Unavailable(
                reason = ChannelUnavailableReason.PERMISSION_DENIED,
                detail = SettingsPermissionPolicy.guidanceFor(namespace, context.packageName),
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                // `putString` 返回 false 表示系统拒绝了这次改写。
                // ⚠️ 不要把它和"抛异常"合并处理：false 是**正常返回**，
                //    说明请求被受理了但没生效（常见于只读键、值不合法）。
                if (putValue(namespace, key, value)) {
                    ChannelResult.Succeeded()
                } else {
                    // ⚠️ 这里**不能**带上 value —— 通道失败的 reason 会进审计日志，
                    //    而参数原值不进日志是本项目的硬纪律（见 CapabilityAuditLog）。
                    //    key 已经由 targetDigest 记下了，不必在这里重复。
                    ChannelResult.Failed(reason = "系统拒绝了这次改写")
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (denied: SecurityException) {
            // ★ 这是一条**竞态**，不是逻辑错误：上面刚检查过有权限，
            //   但写入被拒 —— 用户可能在这两步之间撤销了授权，
            //   或者 appop 被别的进程改了。
            //
            // ⚠️ 仍然报 Unavailable 而不是 Failed：用户该做的事没变，
            //   还是去授权。报 Failed 会把他引向"查命令/查参数"。
            Timber.w(denied, "写设置时权限被拒（检查时还是通的），按不可用处理")
            ChannelResult.Unavailable(
                reason = ChannelUnavailableReason.PERMISSION_DENIED,
                detail = SettingsPermissionPolicy.guidanceFor(namespace, context.packageName),
            )
        } catch (failed: Exception) {
            // ⚠️ 只记异常**类型**。message 里可能带上 key 或 value 的内容，
            //    而这条字符串会进审计日志。
            ChannelResult.Failed(reason = "写入设置时出错：${failed::class.simpleName}")
        }
    }

    /**
     * 这条命名空间现在能不能写。
     *
     * ⚠️ 给界面用的。它与 [write] 走的是**同一个判据** ——
     *    这一点必须保持：界面自己写一套检查的话，会出现"页面显示已授权、
     *    点下去却报没权限"，而用户完全无从判断该信哪一个。
     */
    fun isGranted(namespace: SettingNamespace): Boolean =
        isGranted(SettingsPermissionPolicy.requiredFor(namespace))

    /**
     * 这一项授权现在有没有。
     *
     * ⚠️ 权限页问的是**权限**，不是命名空间 —— 它不该为了问一句话
     *    就去 import `:capabilitylogic` 的类型（那还会让 `:app` 凭空多一条
     *    对能力逻辑层的依赖，而离线跑器看不出来：它把所有模块塞进同一次
     *    kotlinc 调用，跨模块引用会顺便解析成功，只有 Gradle 会挂）。
     */
    fun isGranted(permission: SettingsPermission): Boolean = when (permission) {
        SettingsPermission.WRITE_SECURE_SETTINGS ->
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
            ) == PackageManager.PERMISSION_GRANTED

        // ⚠️ `Settings.System.canWrite` 查的是 **appop**，不是包管理器里的授权。
        //    用 `checkSelfPermission(WRITE_SETTINGS)` 查会永远返回 DENIED ——
        //    因为那个权限是 appop 型的，包管理器根本不认它。
        SettingsPermission.WRITE_SETTINGS_APPOP -> Settings.System.canWrite(context)
    }

    private fun putValue(namespace: SettingNamespace, key: String, value: String): Boolean =
        when (namespace) {
            SettingNamespace.GLOBAL -> Settings.Global.putString(resolver, key, value)
            SettingNamespace.SYSTEM -> Settings.System.putString(resolver, key, value)
            SettingNamespace.SECURE -> Settings.Secure.putString(resolver, key, value)
        }
}
