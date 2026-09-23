package com.pocketagent.capabilitylogic

import com.pocketagent.filelogic.ChannelResult as FileChannelResult
import com.pocketagent.filelogic.DenyReason
import com.pocketagent.filelogic.FileAccessDecider
import com.pocketagent.filelogic.FileAccessDecision
import com.pocketagent.filelogic.FileChannel
import com.pocketagent.filelogic.FileOp
import com.pocketagent.filelogic.FileOperationRequest
import com.pocketagent.filelogic.FileReadResult
import com.pocketagent.filelogic.FileScope
import com.pocketagent.filelogic.PathNormalizer
import com.pocketagent.filelogic.ResolvedTarget
import com.pocketagent.filelogic.ScopeRoot
import kotlinx.coroutines.CancellationException

/**
 * 第 0 档的**通道层** —— 把 [PlannedExecution] 交给真正会碰到系统的那两个端口。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么端口声明在纯模块里、实现放在 Android 侧
 * ═══════════════════════════════════════════════════════════════
 *
 * 与 `filelogic` 的 `FileChannel` 完全同构：本模块只产出「要执行什么」，
 * 真正碰 `Settings` / `Shizuku` 的代码在 `:capability`。
 *
 * 这样切的价值不是「分层好看」，而是**派发逻辑能离线钉死** —— 而它恰恰是
 * 最容易安静地做错的一段：
 *
 * | 写错的形态 | 后果 | 会不会报错 |
 * |---|---|---|
 * | 设置写入被派给 shell 端口 | 变成一条 `settings put`，走错通道 | 不会 |
 * | argv 过边界时被拼成字符串 | 注入防护当场作废 | 不会 |
 * | 端口没接上时抛异常 | 用户看到「出错了」，而不是「去配置」 | 会，但信息无用 |
 * | 先写后读原值 | 撤销把设置「恢复」成它刚变成的样子 | 不会 |
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 两种「不可用」必须分开
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户可能已经在本体界面里放行了 `display.brightness`（我们的授权表里有记录），
 * 但**从没跑过那条 `pm grant … WRITE_SECURE_SETTINGS`**。这是两套彼此独立的授权：
 *
 * | 授权 | 存在哪 | 谁检查 |
 * |---|---|---|
 * | 用户放行这条能力 | 本体的数据库 | [CapabilityGuard] 第 6 步 |
 * | 系统给了这个权限 | 系统的包管理 | 通道端口（这里） |
 *
 * 如果端口把「权限没给」也报成 [ChannelResult.Failed]，用户看到的就是
 * 「执行失败」—— 而他要做的事其实是去跑一条 adb 命令。所以这里刻意分成
 * [ChannelResult.Unavailable]（去配置）与 [ChannelResult.Failed]（试过了，没成）。
 *
 * ⚠️ 本模块**不认识** `WRITE_SECURE_SETTINGS` 这个名字 —— 它是 Android 常量。
 *    端口实现把它翻译成 [ChannelUnavailableReason.PERMISSION_DENIED]，
 *    本模块只负责把结论原样传给界面。
 */

/** 通道**不可用**的原因 —— 决定「去配置」这句话把用户指向哪里。 */
enum class ChannelUnavailableReason(
    val displayName: String,
) {
    /**
     * 端口根本没接上。
     *
     * 两种来源：[CapabilityRunner] 拿到 null（这台设备没有这个通道），
     * 或者这个构建根本没带这个实现。
     */
    PORT_NOT_CONFIGURED("这台设备没有接上这个通道"),

    /**
     * 系统权限没给。
     *
     * ⚠️ 与 [CapabilityGuard] 第 6 步的「用户没放行」**不是**同一件事 ——
     *    见文件头。修法也不同：这一条要去跑 adb，那一条在界面里点一下。
     */
    PERMISSION_DENIED("系统权限还没授予"),

    /** 通道依赖的外部服务没在跑（典型是 Shizuku 没启动） */
    SERVICE_NOT_RUNNING("通道依赖的服务没有运行"),
}

/**
 * 通道执行的结果。
 *
 * ⚠️ 三个分支的区别是**给用户看的那句话不同**，不是为了代码整齐：
 *
 * - [Succeeded] → 什么都不用说
 * - [Unavailable] → 「还差一步配置」，要给出入口
 * - [Failed] → 「试过了，没成」，要给出原因
 *
 * 把后两者合成一个 `Boolean` 或一个 `Error` 分支，界面上就只能说一句
 * 「操作失败」—— 而那句话对这两种情况**都是错的指引**。
 */
sealed interface ChannelResult {

    /** 便利判据。用于界面分流，不用于安全判定。 */
    val isSuccess: Boolean get() = this is Succeeded

    /**
     * 执行完成。
     *
     * ⚠️ 「完成」**不等于**「产生了预期效果」。[PlannedExecution.SettingWrite]
     *    有可能被系统静默忽略（写进去了、也没报错，但值没变）。
     *    回读校验是下一步的事（见 [previousValue]），在那之前这个结论
     *    只保证「通道接受了这次写入」。
     */
    data class Succeeded(
        /**
         * 执行**之前**这项设置的值。
         *
         * null 有三种正常来源：原本没有这一项、通道读不到、这不是设置写入。
         *
         * ⚠️ **它不能进审计日志。** 审计日志的纪律是「绝不记录参数原值」
         *    （见 [CapabilityAuditLog] 文件头），而这是一个来自用户或系统的值。
         *    它的用途是**撤销账本**：[CapabilityRisk.SAFE] 的判据之一是
         *    「可逆 —— 用户随时能改回来」，而不知道原值就改不回去。
         *
         * ⚠️ 它由 [CapabilityRunner] 在**写入之前**填 —— 端口实现不要自己填。
         *    顺序反了会把新值当成旧值记下来：一个不报错、但让撤销把设置
         *    「恢复」成它刚变成的那个样子的 bug。
         */
        val previousValue: String? = null,

        /**
         * 这次操作**读到的**内容（目录列表、文件正文）。null = 这次操作不产出内容。
         *
         * ⚠️ 它与 [previousValue] **不是一回事**，别混：
         *    [previousValue] 是「执行**前**这个设置是什么」（用途是撤销）；
         *    [payload] 是「这次操作**读到**了什么」（用途是展示）。
         *    对一次「读文件」来说前者恒为 null，后者才是结果本身。
         *
         * ⚠️ 它同样**不能进审计日志** —— 文件正文是用户的原始数据，
         *    而审计日志的纪律是「绝不记录参数原值与内容」
         *    （见 [CapabilityAuditLog] 文件头）。
         */
        val payload: String? = null,
    ) : ChannelResult {

        /**
         * ⚠️ **刻意覆写** `toString()`，把 [previousValue] 与 [payload] 都隐去。
         *
         * `data class` 自动生成的那份会把它们原样打出来 —— 而只要有人写一句
         * `Log.d(TAG, result.toString())`，两个「绝不进日志」的值就进了日志。
         * 这与「Key 永不落明文」是同一条纪律的两种形态：
         * **不要让危险的东西出现在任何自动生成的输出里。**
         * （自动生成的东西没人会去审，所以它是最安全的泄漏路径。）
         *
         * ⚠️ [payload] 比 [previousValue] **更**需要隐去，不是顺手加的：
         *    `previousValue` 是一个设置值（几个字符），而 `payload` 可能是
         *    一整份用户文件。同一个疏漏在两者上的后果差好几个数量级。
         *    （2026-09-23：加 `payload` 字段时只想着"界面要显示它"，
         *    差点把这个覆写漏掉 —— 而漏掉的后果是**安静的**。）
         */
        override fun toString(): String = buildString {
            append("Succeeded(previousValue=")
            append(if (previousValue == null) "null" else "<${previousValue.length} 字符，已隐去>")
            append(", payload=")
            append(if (payload == null) "null" else "<${payload.length} 字符，已隐去>")
            append(")")
        }
    }

    /**
     * 通道没准备好，这次**根本没试**。
     *
     * ⚠️ 它与 [Failed] 的分界是「有没有真的去动系统」：
     *    这一支意味着**什么都没发生**，所以重试前必须先解决 [reason]。
     */
    data class Unavailable(
        val reason: ChannelUnavailableReason,

        /** 补充说明。⚠️ 不得含 argv 与参数原值 —— 它会被写进审计日志。 */
        val detail: String = "",
    ) : ChannelResult

    /**
     * 通道可用，但这次没成功。
     *
     * ⚠️ [reason] 由通道实现方给出，必须自行保证不含 argv 与参数原值 ——
     *    与 [CapabilityAuditEvents.failed] 的契约是同一条。
     */
    data class Failed(
        val reason: String,

        /**
         * 进程退出码。只有 shell 通道会有，设置写入没有这个概念。
         *
         * ⚠️ 它是「权限被拒」与「命令返回非零」的判别依据之一：
         *    前者应当报 [Unavailable] 而不是 [Failed]。一旦两者混起来，
         *    用户就分不清自己该去授权，还是该去查命令写错了。
         */
        val exitCode: Int? = null,
    ) : ChannelResult

    /**
     * 这一次**没有执行** —— 它需要用户先确认。
     *
     * ⚠️ 它与 [CapabilityVerdict.RequireConfirmation] 是**两个不同的闸**：
     *
     * | | 问的是什么 | 多久问一次 | 谁产出的 |
     * |---|---|---|---|
     * | [CapabilityVerdict.RequireConfirmation] | 你允许 agent 做**这类事**吗 | 一次（放行后长期有效） | [CapabilityGuard] |
     * | 本分支 | 这一次要动的**那个文件**，你确定吗 | **每次** | `:filelogic` 的 `FileAccessDecider` |
     *
     * ⚠️ 合成一个的后果不是"少问一次"，而是**用户分不清这两个问题** ——
     *    而分不清的下一步就是"看见确认框就点同意"，那正是确认门失效的方式。
     *
     * ⚠️ 拿到它时**不记审计**（与上面那个闸同一条纪律）：一条"问了但还没答"
     *    的记录没有任何可操作信息，而它会在用户每次打开确认框时占掉一格缓冲。
     *    用户答了之后由调用方记 `confirmationResolved`。
     */
    data class NeedsConfirmation(
        /** 直接展示给用户的那句话。**里面会带要动哪个文件** —— 那正是他要确认的东西。 */
        val userMessage: String,

        /** 原因码，进审计日志。**不含路径原值** —— 见 [CapabilityAuditLog]。 */
        val reason: String,

        /** 超时视为**拒绝**，不是默认同意。 */
        val timeoutMs: Long,
    ) : ChannelResult
}

/**
 * 系统设置的读写端口（T0-A）。
 *
 * 实现走 `Settings.Global / System / Secure` 的 API，**不是** `settings put` 命令 ——
 * 两者权限要求相同，但 API 路径有返回值，能区分「权限被拒」与「写成功了」。
 */
interface SettingsAccess {

    /**
     * 读一项设置。读不到返回 `null`。
     *
     * ⚠️ 读**不需要** `WRITE_SECURE_SETTINGS`，所以「读得到」**不能**用来
     *    证明「写得进」。别把它当可用性探针 —— 那会给出一个假的安全感。
     *
     * ⚠️ 实现**不得抛异常**：读不到就返回 null。读原值是「有则更好」的信息，
     *    而写入才是这次操作本身。真机上读失败的常见形态（`SecurityException`、
     *    `RemoteException`、`DeadObjectException`）在 [CapabilityRunner]
     *    那里还有一层兜底，但别依赖它。
     */
    suspend fun read(namespace: SettingNamespace, key: String): String?

    /**
     * 写一项设置。
     *
     * ⚠️ 权限没给时必须返回 [ChannelResult.Unavailable] 而不是 [ChannelResult.Failed] ——
     *    见文件头那张「两套授权」的表。
     */
    suspend fun write(namespace: SettingNamespace, key: String, value: String): ChannelResult
}

/**
 * shell 执行端口（T0-B，以 shell 身份运行）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★★ 参数是**数组**，不是字符串
 * ═══════════════════════════════════════════════════════════════
 *
 * 不管底下走哪条路，命令一律**以数组形式**交给系统，不经过 shell ——
 * 没有 `/bin/sh -c`，所以 `;`、`|`、反引号、`$()`、重定向全都是普通字符。
 *
 * 所以这个签名**只能是** `List<String>`。把它改成 `String`（或者实现方
 * 自己 `joinToString(" ")` 再交给 shell）会让
 * [PlannedExecution.ShellArgv] 关于「注入在结构上不可能」的整个论证作废 ——
 * 而改动的后果不会立刻显现：它要等到某条能力的某个参数恰好含 `;` 才发作。
 *
 * ⚠️ 数组只挡住「执行别的命令」，**挡不住**「用合法命令做别的事」。
 *    `cmd package disable <任意包名>` 里的包名即使只含字母数字，
 *    也足够把系统 UI 停掉 —— 那是 [CapabilityDenyRules] 的活。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️⚠️ 上游现状：**`Shizuku#newProcess` 已经不存在了**（2026-09-23 核实）
 * ═══════════════════════════════════════════════════════════════
 *
 * 本接口的第一版注释写着「Shizuku 的入口是
 * `Shizuku.newProcess(String[] cmd, String[] env, String dir)`」——
 * **那句现在是错的**，而且照着它写出来的实现会**静默失效**，所以特意留在这里。
 *
 * 事实（对 `dev.rikka.shizuku:api:13.1.5` 的 `classes.jar` 直接 `javap` 得到）：
 *   · `rikka.shizuku.Shizuku` 里**没有** `newProcess`。它只有
 *     `pingBinder` / `checkSelfPermission` / `requestPermission` /
 *     `getBinder` / `bindUserService` / `unbindUserService` / `transactRemote` 等。
 *   · `rikka.shizuku.ShizukuRemoteProcess` 还在，但它的构造器收的是
 *     `moe.shizuku.server.IRemoteProcess`，而且是**包内可见** —— 外部构造不了。
 *   · 官方 Changelog（13.1.1）：「Prepare to remove `Shizuku#newProcess`,
 *     developers should have to use `UserService` instead」。
 *
 * ⇒ **本端口将来的实现是 `UserService`**：应用自带一个 `.aidl` 接口与一个
 *   service 类，由 Shizuku 在 shell/root 身份的进程里拉起，应用再通过
 *   `bindUserService` 拿到它的 binder。代价是要开 AIDL 代码生成
 *   （`buildFeatures { aidl = true }`）。
 *
 * ⚠️ 为什么要把这段写下来：社区里仍在流传 `Shizuku.newProcess` 的写法，
 *    而它们**看起来能跑** —— 有项目用反射去调它，反射失败后回落到
 *    无权限的 `Runtime.exec`，界面上照常显示「Shizuku 模式」，
 *    命令却一点特权都没有。那是本项目最忌讳的形态：
 *    **不报错、不崩溃，只是安静地少做一件事。**
 */
interface ShellRunner {

    /**
     * 执行一条命令。**第一项是命令本身**，不含任何 shell 包装。
     *
     * ⚠️ 实现方**不得**自行拼接字符串再交给 shell。
     */
    suspend fun run(argv: List<String>): ChannelResult
}

/**
 * 派发器 —— 「[PlannedExecution] → 端口」这一段唯一的映射点。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它不做任何安全判定
 * ═══════════════════════════════════════════════════════════════
 *
 * 判定全在 [CapabilityGuard]。本类只负责把**已经裁决过**的那个对象送到端口，
 * 所以它没有「要不要执行」这个概念 —— 收到就执行。
 *
 * ⚠️ 这条纪律**不是**靠可见性保证的。试过给 [PlannedExecution] 的构造器加
 *    `internal`，是白费力气：`data class` 生成的 `copy()` 绕得过任何非公开
 *    构造器（编译器会警告），而且可见性本来也挡不住真正危险的那件事 ——
 *    「跳过裁决器直接调本类」。
 *
 *    真正的边界是**接口形状**：本类要的那个对象只能来自
 *    [CapabilityVerdict.Allowed]，而那个结论只能由 [CapabilityGuard] 产出。
 *    想跳过裁决，就得显式地自己造一个结论 —— 那件事在代码审查里一眼可见；
 *    而「我忘了调用裁决器」这种错误根本写不出来，因为没有别的入口。
 *    ⇒ **可见性挡不住「绕过去」，只有「没有别的入口」才挡得住。**
 *
 * ⚠️ 端口为 null 时**返回结论**，不抛异常 —— 见文件头。
 */
class CapabilityRunner(

    /** T0-A 系统设置通道。不接则 [PlannedExecution.SettingWrite] 一律报「没接上」。 */
    private val settings: SettingsAccess? = null,

    /** T0-B shell 通道。不接则 [PlannedExecution.ShellArgv] 一律报「没接上」。 */
    private val shell: ShellRunner? = null,

    /**
     * T0-D 文件通道。不接则 [PlannedExecution.FileIntent] 一律报「没接上」。
     *
     * ⚠️ 与 [shell] 一样：**不要传 null 来"表示还没实现"** ——
     *    null 会让这里说「没有接上通道」，而那句话把原因指向**配置**
     *    （用户会去翻设置找一个开关）。实现还没写时，传一个像
     *    `PendingShellRunner` 那样**如实说"还没实现"**的实现。
     */
    private val files: FileChannel? = null,

    /**
     * 用户当前授权的文件范围。
     *
     * ⚠️ 用 lambda 而**不是** `FileScope` 值：用户随时可能授权或撤销一个目录，
     *    而 runner 是长生命周期的对象 —— 传值会让「刚撤销的目录仍然可写」
     *    一直持续到下次重启应用。与 `ModelRouteCoordinator` 刻意不缓存
     *    是同一个理由：**改了配置，正在跑的东西就该按新配置走**。
     */
    private val fileScope: () -> FileScope = { FileScope() },
) {

    /**
     * 执行一次已裁决的动作。
     *
     * ⚠️ 调用方**必须**只在 [CapabilityVerdict.Allowed] 上调用它。
     *    [CapabilityVerdict.RequireConfirmation] 要先拿用户答复回填
     *    [CapabilityCall.confirmedByUser]、**重新裁决一次**，再执行 ——
     *    见那个类的注释。
     */
    suspend fun run(
        execution: PlannedExecution,

        /**
         * 用户是否已经就**这一次执行**点过确认。
         *
         * ⚠️ 它同时服务两个闸：能力放行（[CapabilityVerdict.RequireConfirmation]）
         *    与文件操作确认（[ChannelResult.NeedsConfirmation]）。
         *
         *    共用一个标志是**刻意的** —— 用户点的那一下确认框里写了要动哪个文件
         *    （见 [CommandPlanner] 的 fileSummary），所以他确认的确实是这件事。
         *    再加一个平行标志的后果是：两个标志迟早不同步，而不同步的方向
         *    必然是**某一个闸被绕过**。
         *
         * ⚠️ 默认值 false 是**安全侧**：忘了传的后果是"多问一次"，
         *    不是"没问就做了"。
         */
        confirmedByUser: Boolean = false,
    ): ChannelResult = when (execution) {
        is PlannedExecution.SettingWrite -> runSetting(execution)
        is PlannedExecution.ShellArgv -> runShell(execution)
        is PlannedExecution.FileIntent -> runFile(execution, confirmedByUser)
    }

    private suspend fun runSetting(execution: PlannedExecution.SettingWrite): ChannelResult {
        val port = settings ?: return ChannelResult.Unavailable(
            reason = ChannelUnavailableReason.PORT_NOT_CONFIGURED,
            detail = "这条动作要改写系统设置，但当前没有接上设置通道",
        )

        // ★ 先读后写。顺序**不能**反 —— 见 ChannelResult.Succeeded.previousValue。
        //   反过来写不会报任何错，只会让撤销把设置「恢复」成它刚变成的样子。
        val previous = readPreviousValue(execution.namespace, execution.key)

        return when (val result = port.write(execution.namespace, execution.key, execution.value)) {
            is ChannelResult.Succeeded -> result.copy(previousValue = previous)
            else -> result
        }
    }

    private suspend fun runShell(execution: PlannedExecution.ShellArgv): ChannelResult {
        val port = shell ?: return ChannelResult.Unavailable(
            reason = ChannelUnavailableReason.PORT_NOT_CONFIGURED,
            detail = "这条动作要以 shell 身份执行，但当前没有接上 shell 通道",
        )

        // ★ 这里把 argv **原样**交给端口，不做任何拼接。
        //   一旦这里出现 `joinToString(" ")`，注入防护就没了 ——
        //   而它不会报错，只会让「参数里恰好有个分号」变成「执行了另一条命令」。
        return port.run(execution.argv)
    }

    /**
     * 执行一次文件操作。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ⚠️ 本方法**不做安全判定** —— 它只做三件事
     * ═══════════════════════════════════════════════════════════════
     *
     * 1. **定位**：归一化路径、找出它落在哪条授权根下。
     *    这不是判定，是「该向谁问」—— 判定要先拿到根才能做。
     * 2. **探测**：`stat` 一次，拿到「在不在、是不是目录、多大」。
     *    这是裁决器的**输入**（裁决器刻意碰不到文件系统）。
     * 3. **委托**：把探测结果交给 `:filelogic` 的 [FileAccessDecider]。
     *
     * ⚠️ 判定**必须**在这一侧（编排者）发生，**不能**下移到
     *    [FileChannel] 实现里 —— 那样「什么允许、什么不允许」就同时存在于
     *    两处，而漂移的方向永远是**松的那一份生效**
     *    （调用方会走到它先通过的那条路上）。
     */
    private suspend fun runFile(
        intent: PlannedExecution.FileIntent,
        confirmedByUser: Boolean,
    ): ChannelResult {
        val channel = files ?: return ChannelResult.Unavailable(
            reason = ChannelUnavailableReason.PORT_NOT_CONFIGURED,
            detail = "这条动作要读写文件，但当前没有接上文件通道",
        )

        val scope = fileScope()

        // ── 1. 定位（不是判定）────────────────────────────────────
        // ⚠️ 归一化失败、或不在任何授权根下时**不探测** ——
        //    把默认值交给裁决器，由它给出那句更准确的话。
        //    在这里自己编一句的后果是：同一件事有两处文案，而它们会不一致。
        val normalized =
            (PathNormalizer.normalize(intent.path) as? PathNormalizer.Result.Valid)?.path
        val root = normalized?.let { scope.rootContaining(it) }

        // ── 2. 探测 ──────────────────────────────────────────────
        val stat = if (root != null && normalized != null) {
            when (val probed = channel.stat(root, relativeTo(root, normalized))) {
                is FileChannelResult.Ok -> probed.value

                // ⚠️ 通道说「不可用」时**原样上报**，不要在这里编一句话 ——
                //    只有通道实现知道它为什么不可用
                //    （SAF 授权被撤销 / Shizuku 没跑 / 目录被删了）。
                is FileChannelResult.Unavailable -> return ChannelResult.Unavailable(
                    reason = ChannelUnavailableReason.PORT_NOT_CONFIGURED,
                    detail = probed.reason,
                )

                is FileChannelResult.Failed -> return ChannelResult.Failed(reason = probed.reason)
            }
        } else {
            null
        }

        // ── 2.5 数一下目录里有多少子项（只在删除目录时才数）────────
        //
        // ⚠️⚠️ 这一步**不是可选的锦上添花**，它守的是一件很具体的事：
        //    `FileAccessDecider` 的 `DELETE_DIRECTORY` 文案在
        //    `descendantCount == null` 时会说「要删除文件夹「X」。」——
        //    **完全不提里面还有东西**。而用户在这一下要签掉的是
        //    "里面那 200 个文件一起消失"。
        //
        //    ⇒ 探测不到可以（那就如实说"要删除文件夹"），
        //      但**能探测到就必须探测** —— 否则确认框会变成一句
        //      技术上正确、实际上在隐瞒的话。
        //
        // ⚠️ 只对 DELETE 做。`MOVE` 也会让目录消失，但裁决器的
        //    `MOVE_OR_RENAME` 文案不消费这个字段，数了也没人用 ——
        //    而每次多余的 `list` 都是一轮真实的目录扫描（SAF 上可能几秒）。
        val descendantCount = if (
            intent.op == FileOp.DELETE &&
            stat?.isDirectory == true &&
            root != null &&
            normalized != null
        ) {
            when (val listed = channel.list(root, relativeTo(root, normalized))) {
                is FileChannelResult.Ok -> listed.value.size
                // 列不出来就留 null，让裁决器说「要删除文件夹「X」」——
                // **不要**编一个数字，那比不说更糟。
                else -> null
            }
        } else {
            null
        }

        // ── 3. 委托裁决 ──────────────────────────────────────────
        val request = FileOperationRequest(
            op = intent.op,
            path = intent.path,
            targetExists = stat?.exists ?: false,
            isDirectory = stat?.isDirectory ?: false,

            // ⚠️ 直接透传可空值，**不要**写 `?: 0`：0 会让确认框说
            //    「这个文件是空的」，而那可能是假话。宁可不说，也不要说错。
            existingSizeBytes = stat?.sizeBytes,

            descendantCount = descendantCount,

            destinationPath = intent.destinationPath,
            confirmedByUser = confirmedByUser,
        )

        return when (val decision = FileAccessDecider(scope).decide(request)) {

            is FileAccessDecision.Allowed -> executeFile(channel, request, decision.target, intent)

            is FileAccessDecision.RequireConfirmation -> ChannelResult.NeedsConfirmation(
                userMessage = decision.userMessage,
                reason = decision.reason.name,
                timeoutMs = decision.timeoutMs,
            )

            // ⚠️ 拒绝要分两类 —— 它们给用户的下一步**完全不同**：
            //    NO_SCOPE / OUT_OF_SCOPE → 他可以去授权那个目录（Unavailable）
            //    其余（路径非法、命中黑名单）→ 只有改参数才有用（Failed）
            //    合并成一句「操作失败」会让"去授权"和"改路径"看起来一样。
            is FileAccessDecision.Denied ->
                if (decision.reason == DenyReason.NO_SCOPE ||
                    decision.reason == DenyReason.OUT_OF_SCOPE
                ) {
                    ChannelResult.Unavailable(
                        reason = ChannelUnavailableReason.PERMISSION_DENIED,
                        detail = decision.userMessage,
                    )
                } else {
                    ChannelResult.Failed(reason = decision.userMessage)
                }
        }
    }

    /**
     * 真正执行 —— 只在裁决放行之后被调用。
     *
     * ⚠️ 执行前**重新探测一次**（TOCTOU）。
     *
     *    裁决用的快照与现在之间有时间差，而危险方向是**覆盖**：
     *    探测时文件不存在 → 判定为「新建、无需确认」 → 执行时它已经存在
     *    → 用户的原文件被静默覆盖。
     *
     *    这条**无法**在纯逻辑层保证（见 [FileOperationRequest] 的注释），
     *    只能在这里补。状态不一致时**放弃执行**并如实说明 ——
     *    让调用方重新走一次裁决，那时它会看到新状态、问出该问的问题。
     */
    private suspend fun executeFile(
        channel: FileChannel,
        request: FileOperationRequest,
        target: ResolvedTarget,
        intent: PlannedExecution.FileIntent,
    ): ChannelResult {
        val recheck = channel.stat(target.root, target.relativePath)
        if (recheck is FileChannelResult.Ok && recheck.value.exists != request.targetExists) {
            return ChannelResult.Failed(
                reason = "这个文件的状态在我判断之后变了，所以我停下来没有动手。请再试一次。",
            )
        }

        return when (intent.op) {
            FileOp.LIST -> when (val listed = channel.list(target.root, target.relativePath)) {
                is FileChannelResult.Ok -> ChannelResult.Succeeded(
                    payload = listed.value.joinToString("\n") { entry ->
                        val mark = if (entry.isDirectory) "目录" else "文件"
                        // ⚠️ **目录不显示字节数**（2026-09-23 真机目击）。
                        //    SAF 对目录返回的 `COLUMN_SIZE` 是**目录项本身的元数据大小**，
                        //    与"里面有多少内容"毫无关系 —— 真机上 `sub` 报的是
                        //    「3452 字节」，而它其实是个空目录。
                        //
                        //    给用户看这个数字比不显示更糟：他会以为这个目录只占 3 KB，
                        //    于是拿它当"目录有多大"的答案。
                        val size = if (entry.isDirectory) {
                            ""
                        } else {
                            entry.sizeBytes?.let { "  $it 字节" }.orEmpty()
                        }
                        "$mark  ${entry.name}$size"
                    }.ifEmpty { "（这个目录是空的）" },
                )

                is FileChannelResult.Unavailable -> ChannelResult.Unavailable(
                    reason = ChannelUnavailableReason.PORT_NOT_CONFIGURED,
                    detail = listed.reason,
                )

                is FileChannelResult.Failed -> ChannelResult.Failed(reason = listed.reason)
            }

            FileOp.READ -> when (val read = channel.read(target.root, target.relativePath)) {
                is FileChannelResult.Ok -> when (val value = read.value) {
                    // ⚠️ 空文件**不能**交出一个空串 —— `payload` 的不变量是
                    //    「要么 null（这次没读到内容），要么是一段非空文本」。
                    //    空串在界面上渲染成一片空白，用户分不清
                    //    "这个文件本来就是空的"与"界面出错了"，
                    //    而这两种情况的下一步动作完全不同。
                    is FileReadResult.Text -> ChannelResult.Succeeded(
                        payload = value.content.ifEmpty { "（这个文件是空的）" },
                    )

                    // ⚠️ 这两个是**正常业务状态**，不是通道故障 —— 见 [FileReadResult]。
                    //    但它们走 [ChannelResult.Failed] 而不是 Succeeded：
                    //    这次操作**没拿到内容**，而用户要做的
                    //    （换个文件 / 只读前面一段）与「试过了，没成」是同一类。
                    //
                    // ⚠️⚠️ 说「**至少有**」而不是「有」。`sizeBytes` 会从**两条**
                    //    完全不同的路径走到这里：
                    //      ① 通道先读了元数据、发现超限 → 那是**准确值**
                    //      ② 通道读着读着才发现超限 → 那只是**下界**
                    //         （读在上限处就收手了，后面还有多少它并不知道）
                    //    「至少有」在两种情况下都为真；「有」在 ② 里是假话，
                    //    而假话会让用户以为"只超了一点点，也许能挤进去"。
                    is FileReadResult.TooLarge -> ChannelResult.Failed(
                        reason = "这个文件至少有 ${value.sizeBytes} 字节，" +
                            "超过了我一次能读的上限（${value.limitBytes} 字节）。",
                    )

                    is FileReadResult.NotUtf8 -> ChannelResult.Failed(reason = value.reason)
                }

                is FileChannelResult.Unavailable -> ChannelResult.Unavailable(
                    reason = ChannelUnavailableReason.PORT_NOT_CONFIGURED,
                    detail = read.reason,
                )

                is FileChannelResult.Failed -> ChannelResult.Failed(reason = read.reason)
            }

            FileOp.WRITE -> toChannelResult(
                channel.write(target.root, target.relativePath, intent.content.orEmpty()),
            )

            FileOp.DELETE -> toChannelResult(channel.delete(target.root, target.relativePath))

            FileOp.MOVE -> {
                val destination = target.destinationRelativePath
                if (destination == null) {
                    // ⚠️ 裁决器本该在缺目标时拒绝（MISSING_DESTINATION）。
                    //    走到这里说明有人绕过了它 —— **如实报出来**，
                    //    不要静默跳过（那会让"移动失败"看起来像"什么都没发生"）。
                    ChannelResult.Failed(reason = "这次移动没有给出目标路径")
                } else {
                    toChannelResult(channel.move(target.root, target.relativePath, destination))
                }
            }
        }
    }

    /**
     * 绝对路径 → 相对授权根的路径。
     *
     * ⚠️ 用 `removePrefix` 掐头，而不是 `java.nio.file.Path` 运算：
     *    [ScopeRoot.path] 与 [normalizedPath] 都出自同一个 `PathNormalizer`，
     *    所以前缀匹配是可靠的。而且**这里算错了也不构成安全漏洞** ——
     *    算错只会让 `stat` 探到一个不存在的位置（`exists = false`），
     *    而那个结果会被裁决器如实拒绝。真正的越界检查在裁决器里。
     */
    private fun relativeTo(root: ScopeRoot, normalizedPath: String): String =
        normalizedPath.removePrefix(root.path).trimStart('/')

    /**
     * `:filelogic` 的通道结果 → 本模块的通道结果。
     *
     * ⚠️ 两个模块各有一个 `ChannelResult`，形状几乎相同却**不是一个类型**。
     *    这是**已知的架构债务**（统一它们要动 `:filelogic` 的全部代码与测试）。
     *    本轮先把转换收在这一处 —— 至少保证「怎么翻译」只有一份实现，
     *    而不是每个调用点各写一遍（那正是漂移的起点）。
     */
    private fun toChannelResult(result: FileChannelResult<Unit>): ChannelResult = when (result) {
        is FileChannelResult.Ok -> ChannelResult.Succeeded()

        is FileChannelResult.Unavailable -> ChannelResult.Unavailable(
            reason = ChannelUnavailableReason.PORT_NOT_CONFIGURED,
            detail = result.reason,
        )

        is FileChannelResult.Failed -> ChannelResult.Failed(reason = result.reason)
    }

    /**
     * 读原值 —— **失败不影响执行**。
     *
     * ⚠️ 刻意吞掉异常：原值是「有则更好」的信息，写入才是这次操作本身。
     *    真机上的读失败几乎都是环境问题（`SecurityException` / `RemoteException` /
     *    `DeadObjectException`），不是逻辑 bug；为了记不上原值而让整个操作失败，
     *    是把代价付错了地方。
     *
     * ⚠️ 但 [CancellationException] **必须原样抛出**。吞掉它会让协程取消在这里
     *    断掉 —— 表现是「点了停止，任务还在跑」，而且不报任何错。
     *    这正是 `runCatching` 在本项目里不能直接用的原因：它连取消一起吞。
     *    （编译器要求子类分支在前，所以这两条 catch 的**顺序是被强制的** ——
     *    但换成一个 `catch (e: Exception)` 就没有这个保护了。）
     */
    private suspend fun readPreviousValue(namespace: SettingNamespace, key: String): String? =
        try {
            settings?.read(namespace, key)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ignored: Exception) {
            // 读不到就是读不到。审计日志记的是「这次动作指向什么」，
            // 而不是「原值是多少」—— 后者本来就不该进日志。
            null
        }
}
