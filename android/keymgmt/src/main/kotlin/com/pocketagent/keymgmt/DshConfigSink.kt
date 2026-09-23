package com.pocketagent.keymgmt

import com.pocketagent.provider.gateway.dsh.DshConfigPatch

/**
 * 把渲染好的 dsh 配置**送到 dsh 那边**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么必须与 [DshConfigPatch] 分开成两层
 * ═══════════════════════════════════════════════════════════════
 *
 * 真机实测（2026-09-22）：dsh 跑在 Termux 的 proot Debian 容器里，
 * 配置目录 `/data/data/com.termux/…/root/.dsh/` 属于 UID `u0_a489`，
 * **我们的进程写不进去**（跨 UID + SELinux）。所以：
 *
 * ```
 *   DshConfigPatch（:provider:gateway，纯逻辑，产出文本）
 *        ──→  DshConfigSink（本模块，怎么送过去）
 * ```
 *
 * ⚠️ 渲染器放在 `:provider:gateway` 而不是本模块，**是刻意的**：
 *    `run_logic_tests.py` 按模块**整目录**编译，而 `:keymgmt` 因为
 *    `CredentialRepository` 用了 `androidx.room.withTransaction` 而
 *    **不在** `MODULES` 里。把渲染器放这里 = 放弃离线测试。
 *    放 `:provider:gateway`（零 Android 依赖，已在 MODULES 里）→ 立刻可离线覆盖。
 *
 * 拆开的好处很实际：**投递方式会随真机验证结果改，而渲染逻辑不用动**。
 * 已识别的候选（按风险递增）：
 *
 * | 实现 | 需要的条件 | 现状 |
 * |---|---|---|
 * | 写应用私有目录 + 提示用户手工粘贴 | 无 | ✅ **先做这个**（零权限、零风险） |
 * | 经 Shizuku 直接写 Termux 私有目录 | 用户装并授权 Shizuku | ⚠️ 待真机验证 uid 2000 能否写入 |
 * | 经 Termux 侧一次性脚本拉取 | 用户跑一条命令 | ⚠️ 待验证 |
 * | 经 dsh-mcp-client 反向暴露"写配置"能力 | dsh 已在跑 | ⚠️ 依赖网关先通 —— 循环依赖，不能作为首次接入路径 |
 *
 * ⚠️ **刻意不把 sink 做成 `(String) -> Unit`** —— 投递会失败，而失败必须
 *    能说清"为什么"以及"用户下一步做什么"（与 `GatewayFailure` 的理由相同：
 *    用户能据一句话自救，异常类型不能）。
 */
fun interface DshConfigSink {

    /**
     * 写入一份配置。
     *
     * @param settingsYaml 合并后的 `settings.yaml` 全文
     * @param credentialsYaml 合并后的 `.credentials.yaml` 全文；`null` 表示
     *        **无法安全合并**（见 [DshConfigPatch.mergeIntoCredentialsYaml] 的返回约定）——
     *        此时实现应**只写 settings 并如实报告**，不要伪造一份凭据文件。
     * @return 结果。**不要抛异常** —— 失败是业务状态。
     */
    fun write(settingsYaml: String, credentialsYaml: String?): DshWriteResult
}

/**
 * 投递结果。
 *
 * ⚠️ 与 `GatewayFailure` 同样的理由：**每个失败都要有用户可执行的下一步**。
 *    "写入失败"这四个字对用户没有任何用处。
 */
sealed interface DshWriteResult {

    /**
     * 成功。
     *
     * @param location 给用户看的落点描述（如"应用私有目录 dsh/settings.yaml"）——
     *        用户要能据此找到它。**不是一个抽象的成功标志**。
     * @param credentialsWritten 凭据文件是否也写成功了。`false` 时必须配合
     *        [DshWriteResult.Partial] 的提示语，见该成员。
     */
    data class Success(val location: String, val credentialsWritten: Boolean) : DshWriteResult

    /**
     * 部分成功 —— `settings.yaml` 写好了，但 `.credentials.yaml` 没写。
     *
     * ⚠️ **这个状态必须独立存在，不能被 [Success] 吞掉**。后果很具体：
     *    配置里 `apiKeyEnv: POCKETAGENT_LOCAL_TOKEN` 指向一个**不存在的凭据**
     *    → dsh 每请求都报 `MISSING_CREDENTIAL` → 用户去查"是不是 token 过期了"，
     *    而真正的问题是"凭据文件根本没写进去"。
     */
    data class Partial(val reason: String) : DshWriteResult

    /**
     * 失败。`reason` 必须是**用户可据以行动**的一句话。
     *
     * ⚠️ 不允许把异常 message 直接塞进来 —— 它可能含路径之外的敏感信息，
     *    而且 `FileNotFoundException: /data/data/com.termux/…` 这种文本
     *    会把用户的排查方向引到"路径写错了"，而实际是权限不通。
     */
    data class Failure(val reason: String) : DshWriteResult
}

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
 *    这与项目"沙箱优先 / 用户签字放行"的立场一致
 *
 * ⚠️ **它是暂时的**。等真机验证出"Shizuku 能否写 Termux 私有目录"之后，
 *    应当补一个自动 sink。但**不该在验证之前先写自动 sink** ——
 *    那会得到一个在真机上必然失败、却看起来"功能已实现"的东西。
 *
 * ⚠️ [write] 只做**两次独立写入**，第二次失败不影响第一次 ——
 *    这正是 [DshWriteResult.Partial] 存在的原因。
 */
class AppPrivateDshConfigSink(
    /**
     * 落盘回调：`(relativePath, content) -> Boolean`。
     *
     * ⚠️ 收成一个函数而**不是** `java.io.File` —— 本类要保持可离线测
     *    （与 `HttpGatewayServer` 收 `sanitize: (String)->String` 同一个理由）。
     *    真实现由 Android 层给出应用 `filesDir`。
     */
    private val writeFile: (relativePath: String, content: String) -> Boolean,
) : DshConfigSink {

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
        const val CREDENTIALS_REL_PATH = "dsh/credentials.yaml"
    }
}
