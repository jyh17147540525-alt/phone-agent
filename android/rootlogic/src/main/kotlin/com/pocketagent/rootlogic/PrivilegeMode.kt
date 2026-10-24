package com.pocketagent.rootlogic

/**
 * 权限模式 —— 本应用只有两种，且**用户必须能一眼看懂差别**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么要有这个枚举（而不是"有 root 就多用点功能"）
 * ═══════════════════════════════════════════════════════════════
 *
 * 同一个应用在两台手机上能做到的事**根本不同**：
 *
 *  - 没 root：只能做**系统允许普通应用做**的事 —— 读无障碍树、点屏幕、
 *    通过 SAF 读写用户授权的目录、发通知。
 *  - 有 root：可以 `su -c` 执行任意命令 —— 改 SELinux、写任何目录、
 *    读别的应用私有数据、改系统属性、卸载系统应用。
 *
 * 这不是"功能多一点"的差别，是**能不能做**的差别。把它藏起来、
 * 或者用同一套文案描述，会让用户对自己的手机处于什么状态**完全没有概念**。
 *
 * ⇒ 所以模式是**显式的、可见的、可切换的**，而不是后台自动探测后静默改变行为。
 *   自动探测的问题：用户换了手机（或 Magisk 被更新重置）后，同一个说法
 *   突然做不到，而界面没有任何提示 —— 他会以为应用坏了。
 *
 * ⚠️ 模式**不等于**授权。选了 [ROOT] 但 `su` 拒绝（Magisk 里没给本应用
 *    放行）时，能力依然是降级的，见 [RootProbeResult]。
 */
enum class PrivilegeMode(
    /** 给用户看的名字。 */
    val displayName: String,

    /** 一句话说清这模式意味着什么 —— 出现在切换开关下方。 */
    val summary: String,
) {
    /**
     * 非 root —— 默认。**这是绝大多数用户的状态，也是本应用必须能独立成立的形态。**
     */
    NON_ROOT(
        displayName = "普通模式",
        summary = "不需要 root。能读屏、能点按、能读写你授权的文件、能发通知。" +
            "系统不给普通应用的权限，这里也做不到 —— 这是边界，不是缺陷。",
    ),

    /**
     * root —— 满血。**能做的事多得多，代价是风险也大得多。**
     */
    ROOT(
        displayName = "Root 模式",
        summary = "以 root 身份执行命令。能改系统设置、读写任何目录、" +
            "操作其他应用的数据。**这些命令一旦跑错，后果不可撤销**，" +
            "所以每一条都要你确认。",
    ),
}

/**
 * 一次 root 探测的结论。
 *
 * ⚠️ **四种结果必须分开**，因为它们要用户做的事完全不同：
 *
 * | 结论 | 用户该做什么 |
 * |---|---|
 * | [Available] | 什么都不用做 |
 * | [NotRooted] | 想用就去 root，不想用就接受边界 |
 * | [Denied] | **去 Magisk 里给本应用放行**（最容易混淆的一种） |
 * | [Failed] | 看原因（su 版本怪、超时、SELinux 拦） |
 *
 * 合并成"没有 root"会让 [Denied] 的用户以为自己手机没 root ——
 * 而他明明刚 root 完。这是本项目最忌讳的"错误信息指向无关的地方"。
 */
sealed interface RootProbeResult {

    /** root 可用，且拿到的是真正的 uid 0。 */
    data class Available(
        /** `su -c id` 的实际输出，给排查用（不含隐私）。 */
        val identity: String,
    ) : RootProbeResult

    /** 系统里没有 `su` —— 这是真的没 root。 */
    data object NotRooted : RootProbeResult

    /**
     * `su` 存在，但**拒绝了我们**。
     *
     * 这是**最常见**的一种，而且最容易被误判成"没 root"：
     * 手机已经 root 了，但 Magisk 的超级用户列表里没有本应用
     * （或用户点了"拒绝"、或授权超时自动拒绝）。
     */
    data object Denied : RootProbeResult

    /** 有 su 也没被拒绝，但执行失败（超时 / 输出异常 / SELinux）。 */
    data class Failed(val reason: String) : RootProbeResult
}

/**
 * root 探测结果的**用户可读解释**。
 *
 * ★ 单独抽成函数而不是写在界面里：这段话是"用户下一步做什么"的唯一来源，
 *   两个界面（权限页 / 设置页）各写一份必然漂移，而漂移方向是**更含糊的那份**。
 */
fun RootProbeResult.explain(): String = when (this) {
    is RootProbeResult.Available -> "Root 已就绪（${identity.trim()}）"

    // ★ 这一条要说得斩钉截铁：没 root 不是错误状态，是**正常状态**。
    //   写成"未检测到 root，部分功能不可用"会让用户以为应用坏了。
    RootProbeResult.NotRooted ->
        "这台手机没有 root。普通模式下能做的事已经全部可用 —— " +
            "下面列出的「需要 root」的能力，是系统不给普通应用的权限，不是本应用的限制。"

    // ★ 最容易误判的一种，必须点名 Magisk：
    RootProbeResult.Denied ->
        "手机有 root，但**本应用还没被授权**。" +
            "请打开 Magisk（或你的 root 管理器）→ 超级用户 → 找到 PocketAgent → 允许。" +
            "⚠️ 这不是「手机没 root」，别去重新 root 一遍。"

    is RootProbeResult.Failed ->
        "root 命令执行失败：$reason。手机可能是 root 的，但这条命令没跑通 —— " +
            "常见原因是授权超时、或 SELinux 拦下了它。"
}

/**
 * 能力梯度 —— 一条能力在两种模式下**分别**处于什么状态。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么是"梯度"而不是"有/没有"
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户问的从来不是"这个功能有没有"，而是"**我升级到 root 能得到什么**"。
 * 所以每条能力都要能回答：普通模式下能做到哪一步、root 之后能多做哪一步。
 *
 * 例：改系统设置
 *  - 普通模式：[Degraded] —— 只有 `WRITE_SECURE_SETTINGS`（要 adb 授一次）
 *    能改一部分，改不了受保护的那些
 *  - root：      [Full] —— 直接 `settings put`，全部可改
 *
 * ⚠️ 只写"root 能做"而不写"普通模式做到哪"是最坏的做法：
 *    用户会以为普通模式什么都不能做，于是要么放弃、要么去 root 一个
 *    他其实不需要 root 的手机。
 */
sealed interface CapabilityTier {

    /** 在这个模式下**完全可用**，且不需要任何额外授权。 */
    data object Full : CapabilityTier

    /**
     * 在这个模式下**部分可用**，或需要额外授权。
     *
     * @param how 怎么做才能到这一步（普通模式下的 adb 授权 / SAF 选目录等）
     * @param missing 还差什么（root 之后能补上的那部分）
     */
    data class Degraded(val how: String, val missing: String) : CapabilityTier

    /**
     * 在这个模式下**完全做不到**。
     *
     * @param why 为什么做不到 —— **必须说清是系统的限制，不是本应用偷懒**
     */
    data class Unavailable(val why: String) : CapabilityTier
}

/**
 * 一条"受模式影响"的能力。
 *
 * @param id 稳定标识（日志与审计用，不随文案变化）
 * @param title 给用户看的名字
 * @param nonRoot 普通模式下的状态
 * @param root root 模式下的状态
 */
data class ModeGatedCapability(
    val id: String,
    val title: String,
    val nonRoot: CapabilityTier,
    val root: CapabilityTier,
) {
    /** 这条能力在指定模式下是否**至少部分可用**。 */
    fun isUsableIn(mode: PrivilegeMode): Boolean = when (tierIn(mode)) {
        is CapabilityTier.Full, is CapabilityTier.Degraded -> true
        is CapabilityTier.Unavailable -> false
    }

    fun tierIn(mode: PrivilegeMode): CapabilityTier = when (mode) {
        PrivilegeMode.NON_ROOT -> nonRoot
        PrivilegeMode.ROOT -> root
    }
}

/**
 * 能力的**唯一事实来源** —— 界面、工具挂载、文档三处都引用它。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这张表的用途不是"展示"，而是**划边界**
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户明确要求："非 root 的上限就到那里了，没有 root 办不到的就办不到，
 * 要划清边界"。所以这张表的价值在于**把"做不到"说清楚**，而不在于列表有多长。
 *
 * ⚠️ 每加一条能力，必须同时回答三个问题：
 *   1. 普通模式能做到哪一步？（不接受"做不到"就随便写，要写清**为什么**）
 *   2. root 之后多做了什么？
 *   3. 多出来的那部分，**失败时的后果是什么**？（可撤销吗）
 */
object CapabilityMatrix {

    /**
     * 全部受模式影响的能力。
     *
     * ⚠️ 顺序有意义：**影响面从小到大**。界面上按这个顺序排，
     *    用户从上往下读，风险是递增的 —— 而不是把最危险的那条放第一条吓人。
     */
    val all: List<ModeGatedCapability> = listOf(

        // ── 1. 读屏 ────────────────────────────────────────────
        // ★ 这条在两种模式下**都是 Full**。刻意放在第一位：
        //   让用户看到"最核心的能力不需要 root"，这决定了产品的形态。
        ModeGatedCapability(
            id = "screen.read",
            title = "读屏（看懂界面）",
            nonRoot = CapabilityTier.Full,
            root = CapabilityTier.Full,
        ),

        // ── 2. 点按 / 手势 ─────────────────────────────────────
        ModeGatedCapability(
            id = "screen.tap",
            title = "点按与手势",
            nonRoot = CapabilityTier.Full,
            root = CapabilityTier.Full,
        ),

        // ── 3. 文件（用户授权目录）─────────────────────────────
        ModeGatedCapability(
            id = "file.saf",
            title = "读写你授权的文件夹",
            // SAF 是普通应用就能拿到的、范围最小但最干净的通道。
            nonRoot = CapabilityTier.Full,
            root = CapabilityTier.Full,
        ),

        // ── 4. 任意路径读写 ────────────────────────────────────
        ModeGatedCapability(
            id = "file.any",
            title = "读写任意路径（含其他应用的数据）",
            nonRoot = CapabilityTier.Unavailable(
                why = "SAF 只能碰你在系统选择器里授权过的那几个目录；" +
                    "/data/data 下别的应用的数据、系统目录，普通应用一律看不到。" +
                    "**这是 Android 的沙箱在起作用，不是本应用的限制。**",
            ),
            root = CapabilityTier.Full,
        ),

        // ── 5. 系统设置 ────────────────────────────────────────
        ModeGatedCapability(
            id = "settings.write",
            title = "改系统设置",
            nonRoot = CapabilityTier.Degraded(
                how = "用电脑 adb 执行一次 `pm grant` 授予「修改安全设置」后，" +
                    "可以改一部分（亮度、超时这类）。",
                missing = "受保护的那些（如强制开启 freeform 分屏）改不了 —— " +
                    "它们需要 shell 或 root。",
            ),
            // ★ root 下这变成 Full，但**不代表可以乱改** ——
            //   风险等级与确认流程仍然照走（见 RiskLevel / IntentGate）。
            root = CapabilityTier.Full,
        ),

        // ── 6. 系统属性 ────────────────────────────────────────
        ModeGatedCapability(
            id = "prop.set",
            title = "改系统属性（setprop / getprop）",
            nonRoot = CapabilityTier.Unavailable(
                why = "普通应用没有写系统属性的权限，而且很多属性在 SELinux 层面" +
                    "就不允许 shell（uid 2000）写入 —— 只有 root 的域能碰。",
            ),
            root = CapabilityTier.Full,
        ),

        // ── 7. 停用 / 卸载系统应用 ─────────────────────────────
        ModeGatedCapability(
            id = "pm.disable",
            title = "停用或卸载预装应用",
            nonRoot = CapabilityTier.Unavailable(
                why = "普通应用不能停用别的应用。用 adb 的 `pm disable-user` 可以，" +
                    "但那要求电脑在场 —— 本应用做不到「不接电脑也停用」。",
            ),
            root = CapabilityTier.Full,
        ),

        // ── 8. root 命令执行 ───────────────────────────────────
        // ★ 这条是上面所有 root 能力的基础，放在最后是因为它**最危险**：
        //   它等于把整台手机的 shell 交给模型。
        ModeGatedCapability(
            id = "shell.root",
            title = "以 root 身份执行命令",
            nonRoot = CapabilityTier.Unavailable(
                why = "这是 root 本身。普通应用执行 `su` 会被直接拒绝 —— " +
                    "没有 root 就没有这条通道，**没有任何替代方案**。",
            ),
            root = CapabilityTier.Full,
        ),
    )

    /** 按 id 查。找不到返回 null（不抛 —— 调用方多为界面渲染）。 */
    fun byId(id: String): ModeGatedCapability? = all.firstOrNull { it.id == id }

    /**
     * 在指定模式下**完全做不到**的能力。
     *
     * ★ 这个函数的产品意义大于技术意义：界面要把这些**明确列出来**，
     *   而不是让它们悄悄消失。用户看不见的东西，他会以为应用坏了；
     *   看见了并知道"是系统不让"，他才会接受边界。
     */
    fun unavailableIn(mode: PrivilegeMode): List<ModeGatedCapability> =
        all.filter { !it.isUsableIn(mode) }

    /**
     * root 之后**新解锁**的能力（普通模式做不到、root 能做到的）。
     *
     * ★ 这是切换开关旁边那个"升级到 root 能得到什么"列表的数据源。
     */
    fun unlockedByRoot(): List<ModeGatedCapability> =
        all.filter { !it.isUsableIn(PrivilegeMode.NON_ROOT) && it.isUsableIn(PrivilegeMode.ROOT) }
}
