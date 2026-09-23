package com.pocketagent.keymgmt

import com.pocketagent.provider.gateway.dsh.DshConfigPatch
import com.pocketagent.provider.gateway.dsh.DshConfigSink
import com.pocketagent.provider.gateway.dsh.DshWriteResult

/**
 * 最保守的 [DshConfigSink]：写进**应用自己的私有目录**，然后请用户
 * 手工把内容粘贴到 dsh 那边。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么"让用户手工粘贴"是当前正确的一步
 * ═══════════════════════════════════════════════════════════════
 *
 * 看起来退步，但它同时满足三条硬约束：
 *
 * 1. **零权限** —— 不需要 Shizuku、不需要 root、不需要 `run-as`
 * 2. **零风险** —— 不碰用户的 `.credentials.yaml`（那里面有真 Key）
 * 3. **可验证** —— 用户能亲眼看到我们要他写进去的是什么，
 *    这与项目"沙箱优先 / 用户签字放行"的立场一致。
 *    ⚠️ 但"能看到"**必须由界面完成**：草稿落在应用私有目录，
 *    非 root 设备上用户打不开那个目录（见 [CREDENTIALS_REL_PATH] 的注释）。
 *
 * ⚠️ **它是暂时的**。等真机验证出"Shizuku 能否写 Termux 私有目录"之后，
 *    应当补一个自动 sink。但**不该在验证之前先写自动 sink** ——
 *    那会得到一个在真机上必然失败、却看起来"功能已实现"的东西。
 *
 * ⚠️ **它同时是"装配链路"的验证工具**：即使最终投递方式不是它，
 *    它也能证明"网关起来 → 渲染 → 合并 → 落盘"这条链是通的。
 *
 * ⚠️ [write] 只做**两次独立写入**，第二次失败不影响第一次 ——
 *    这正是 [DshWriteResult.Partial] 存在的原因。
 */
class AppPrivateDshConfigSink(
    /**
     * 读回调。`(relativePath) -> String?`，`null` 表示文件不存在。
     *
     * ⚠️ 与 [writeFile] 一样收成函数而**不是** `java.io.File` —— 本类要保持
     *    可离线测（与 `HttpGatewayServer` 收 `sanitize: (String)->String` 同一个理由）。
     *    真实现由 Android 层给出应用 `filesDir`。
     */
    private val readFile: (relativePath: String) -> String?,

    /** 落盘回调：`(relativePath, content) -> Boolean`。 */
    private val writeFile: (relativePath: String, content: String) -> Boolean,
) : DshConfigSink {

    override val settingsPath: String = SETTINGS_REL_PATH

    override val credentialsPath: String = CREDENTIALS_REL_PATH

    /**
     * ⚠️ `true` **仅因为这是我们的草稿区**。
     *    换成 dsh 真实配置目录时必须改成 `false` —— 理由见 [DshConfigSink] 的接口注释。
     */
    override val mayCreateCredentialsFile: Boolean = true

    override fun read(relativePath: String): String? =
        runCatching { readFile(relativePath) }.getOrNull()

    override fun write(settingsYaml: String, credentialsYaml: String?): DshWriteResult {
        val settingsOk = runCatching { writeFile(SETTINGS_REL_PATH, settingsYaml) }
            .getOrDefault(false)

        if (!settingsOk) {
            return DshWriteResult.Failure(
                "无法写入配置文件。请检查应用存储空间是否足够，或重启应用后重试。"
            )
        }

        if (credentialsYaml == null) {
            return DshWriteResult.Partial(
                "已写入 dsh 设置，但**未能生成凭据文件** —— dsh 现有的凭据文件结构" +
                    "与预期不符（可能是 dsh 升级后改了格式）。请把设置文件里的 " +
                    "${DshConfigPatch.DEFAULT_CREDENTIAL_REF} 手工加到 dsh 的凭据文件里，" +
                    "否则请求会报凭据缺失。"
            )
        }

        val credOk = runCatching { writeFile(CREDENTIALS_REL_PATH, credentialsYaml) }
            .getOrDefault(false)

        return if (credOk) {
            DshWriteResult.Success(
                location = SETTINGS_REL_PATH,
                credentialsWritten = true,
            )
        } else {
            DshWriteResult.Partial(
                "已写入 dsh 设置，但凭据文件写入失败。请把其中的 " +
                    "${DshConfigPatch.DEFAULT_CREDENTIAL_REF} 一行手工补进 dsh 的凭据文件。"
            )
        }
    }

    companion object {
        /** 相对应用私有目录的路径。用 `dsh/` 前缀便于用户找到。 */
        const val SETTINGS_REL_PATH = "dsh/settings.yaml"

        /**
         * ⚠️ 这里**没有前导点**（dsh 真实目录里是 `.credentials.yaml`）。
         *
         * 刻意的：这是应用私有目录里的草稿文件，不是 dsh 会去读的那个。
         * 不加点让名字与 dsh 那份对应得上，用户对照着抄时不容易搞混。
         *
         * ⚠️ **但不要以为"不加点用户就能在文件管理器里找到它"** ——
         *    这句话曾经写在这里，它是**错的**：草稿落在
         *    `/data/data/<pkg>/files/dsh/`，非 root 设备上文件管理器与 MTP
         *    都进不去，加点不加点都一样。
         *
         *    所以用户拿到这份内容的**唯一途径是界面** ——
         *    见 `AppContainer.dshDraftSettings()` 与 `DshIntegrationScreen`
         *    里的全文展示（长按可选中复制）。
         */
        const val CREDENTIALS_REL_PATH = "dsh/credentials.yaml"
    }
}
