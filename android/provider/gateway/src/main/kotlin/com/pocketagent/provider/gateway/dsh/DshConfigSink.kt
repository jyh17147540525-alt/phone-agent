package com.pocketagent.provider.gateway.dsh

/**
 * dsh 配置的一个**落点** —— 能读、能写、并声明它自己的边界。
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
 *   DshConfigPatch（本模块，纯逻辑，产出文本）
 *        ──→  DshConfigSink（本模块，契约）──→ 实现（上层，怎么送过去）
 * ```
 *
 * 拆开的好处很实际：**投递方式会随真机验证结果改，而渲染逻辑不用动**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 契约放在本模块、实现放上层 —— 这不是随手的选择
 * ═══════════════════════════════════════════════════════════════
 *
 * 与本模块的 `CredentialSource` / `UsageRecorder` **完全同一个模式**：
 *
 * | 契约（本模块） | 实现（`:keymgmt`） |
 * |---|---|
 * | `CredentialSource` | `GatewayCredentialSource` |
 * | `UsageRecorder` | `RoomUsageRecorder` |
 * | `DshConfigSink` | `AppPrivateDshConfigSink` |
 *
 * 收益很具体：**契约与使用它的 [DshGatewaySession] 一起留在纯 Kotlin 模块里，
 * 于是整条"起网关 → 渲染 → 合并 → 投递"的链路都能进 `run_logic_tests.py`
 * 离线验证**。反过来（契约放 `:keymgmt`）会让会话跟着 `:keymgmt` 一起
 * 被 Room 拖出离线通道 —— 而这条链路里每一步错的后果都是静默的。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么从「只写」改成「读写」
 * ═══════════════════════════════════════════════════════════════
 *
 * 第一版只有 `write`，因为当时的想法是"我们产出一份完整文档"。
 * 但 [DshConfigPatch] 的合并语义是**读-改-写**：
 * 它要把我们的段插进**既有**文档，并且**保住用户的注释与不认识的新键**。
 * 只写不读的接口根本表达不了这件事 —— 只能退化成整文件覆盖，
 * 而那个会把用户手写的 `# user patch layer` 和 dsh 升级带来的新键全部删掉。
 *
 * 真实落点（dsh 配置目录）里同时有 `settings.yaml` 与 `.credentials.yaml`，
 * 两者**必须由同一个 sink 一起处理**：只写其中一个会得到
 * "配置指着一个不存在的凭据"这种半成品状态。
 */
interface DshConfigSink {

    /**
     * `settings.yaml` 在本落点的相对路径。
     *
     * ⚠️ 由 sink 给出而不是由调用方硬编码：不同落点的路径不同
     *    （应用私有目录带 `dsh/` 前缀便于用户找；dsh 真实目录是 `root/.dsh/settings.yaml`）。
     *    调用方硬编码会让"换个落点"变成改多处。
     */
    val settingsPath: String

    /** 凭据文件的相对路径。同上 */
    val credentialsPath: String

    /**
     * 本落点是否允许**新建**凭据文件。
     *
     * ⚠️ **这个开关守的是一条真实的数据损坏路径**，不是洁癖：
     *
     * - 应用私有目录 → `true`。那是**我们自己的草稿区**，文件不存在时
     *   生成一份没有东西可毁，而且不生成的话用户手上就没有可参照的凭据行。
     * - dsh **真实**配置目录 → 必须 `false`。那份 `.credentials.yaml` 里有
     *   用户的真 Key，且可能含 `records:` 等我们没见过的段；
     *   凭空造一份可能让 dsh **整个读不动配置** —— 用户会以为是我们把 dsh 弄坏了。
     *   文件不存在时正确的做法是**放弃并如实告诉用户**（见 [DshWriteResult.Partial]）。
     */
    val mayCreateCredentialsFile: Boolean

    /**
     * 读既有内容。
     *
     * @return 文件全文；**`null` 表示文件不存在**。
     *
     * ⚠️ `null` 与空串是**两件不同的事**，调用方必须分开：
     *    - 空串 = 文件存在但是空的 → 可以按"没有我们的段"合并
     *    - `null` = 文件不存在 → 要按 [mayCreateCredentialsFile] 决定新建还是放弃
     *    合并成同一个值会让"文件不存在"被当成"文件是空的"，
     *    于是我们会往一个不存在的文件上做读-改-写，而结果无人察觉。
     */
    fun read(relativePath: String): String?

    /**
     * 写入（已合并好的）两份配置。
     *
     * @param settingsYaml 合并后的 `settings.yaml` 全文
     * @param credentialsYaml 合并后的凭据文件全文；`null` 表示
     *        **无法安全生成/合并**（见 [DshConfigPatch.mergeIntoCredentialsYaml] 的返回约定）——
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
     * 部分成功 —— `settings.yaml` 写好了，但凭据文件没写。
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
