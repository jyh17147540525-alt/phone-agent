package com.pocketagent.capabilitylogic

// ⚠️ `:capabilitylogic` 依赖 `:filelogic` 是**刻意的**，而且它是安全的：
//    `:filelogic` 是纯 Kotlin 模块（零 Android 依赖），所以这条边不会让
//    本模块失去离线可测性 —— 两个模块都能进 run_logic_tests.py。
//
//    反过来才危险：如果让 `:filelogic` 依赖本模块，或者让文件裁决
//    下移到通道实现里（`:capability`），那么「什么允许、什么不允许」
//    就会同时存在于两处，而漂移的方向永远是**松的那一份生效**。
import com.pocketagent.filelogic.FileOp

/** `settings` 命令的三个命名空间。 */
enum class SettingNamespace(val wireName: String) {
    GLOBAL("global"),
    SYSTEM("system"),
    SECURE("secure"),
}

/**
 * 规划好的执行动作 —— **裁决与执行之间唯一的交接物**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★★ 为什么 [ShellArgv] 是**数组**而不是字符串
 * ═══════════════════════════════════════════════════════════════
 *
 * Shizuku 的入口是：
 *
 * ```java
 * Shizuku.newProcess(String[] cmd, String[] env, String dir)
 * ```
 *
 * 它内部把数组交给 `Runtime.exec(String[])`。**这条路不经过 shell** ——
 * 没有 `/bin/sh -c`，所以 `;`、`|`、`` ` ``、`$()`、重定向**全都是普通字符**，
 * 不构成注入。
 *
 * 如果这里存的是拼好的字符串，那么每一个参数的校验失误都会变成命令注入；
 * 存数组则让**注入在结构上不可能**，参数校验退化成"保证命令语义正确"
 * 这一件更简单的事。
 *
 * ⚠️ 但**不要因此放松参数校验** —— 数组只挡住了"执行别的命令"，
 *    挡不住"用合法命令做别的事"。`cmd package disable <任意包名>` 里的包名
 *    即使只含字母数字，也足够把系统 UI 停掉。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ [summary] 是给**用户**看的，不是日志
 * ═══════════════════════════════════════════════════════════════
 *
 * 确认框里会原样展示它，所以它必须说清"会发生什么"，
 * 而不是"我执行了什么命令"。用户不理解 `cmd wifi set-wifi-enabled disabled`，
 * 但他理解「把 Wi-Fi 关掉」。
 */
sealed interface PlannedExecution {

    /** 给用户看的一句话 */
    val summary: String

    /**
     * 一条 shell 命令（argv 形式）。
     *
     * ⚠️ [argv] 的第一项是命令本身（如 `cmd` / `settings` / `input`），
     *    不含任何 shell 包装。实现方**不得**自行把它拼成字符串再交给 shell ——
     *    那会让上面那段论证失效。
     *
     * ⚠️ 构造器是**公开**的，这一点是刻意的 —— 别用 `internal` 去「加固」它。
     *    试过，是白费力气：`data class` 生成的 `copy()` 绕得过任何非公开构造器
     *    （编译器会警告 `non-public primary constructor is exposed via the
     *    generated copy()`）。更根本的是，**可见性挡不住真正危险的那件事** ——
     *    「跳过 [CapabilityGuard] 直接调 [CapabilityRunner]」。
     *    那件事的边界是**接口形状**：runner 要的那个对象只能来自
     *    [CapabilityVerdict.Allowed]，而那个结论只能由裁决器产出。
     */
    data class ShellArgv(
        val argv: List<String>,
        override val summary: String,
    ) : PlannedExecution {
        init {
            require(argv.isNotEmpty()) { "argv 不能为空" }
            require(argv.none { it.isEmpty() }) { "argv 不能含空字符串项：$argv" }
        }
    }

    /**
     * 一次系统设置写入。
     *
     * ⚠️ 走 Android API（`Settings.Global/System/Secure.putString`）而不是
     *    `settings put` 命令 —— 两者权限要求相同，但 API 路径**有返回值**，
     *    能区分"权限被拒"和"写成功了"。命令路径只能靠解析 stderr。
     *
     * ⚠️ 命名空间**单独成一字段**而不是拼进 key：这样
     *    [CapabilityDenyRules.isSettingKeyDenied] 的匹配不会因为
     *    "有人写了 `secure/xxx` 还是 `secure.xxx`" 而漏判。
     *
     * ⚠️ 构造器同样是**公开**的 —— 理由见 [ShellArgv] 里「别用 `internal`
     *    去加固它」那一段。
     */
    data class SettingWrite(
        val namespace: SettingNamespace,
        val key: String,
        val value: String,
        override val summary: String,
    ) : PlannedExecution

    /**
     * 一次文件操作。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 它只携带**用户意图**，不携带"已解析的目标"
     * ═══════════════════════════════════════════════════════════════
     *
     * 设置与 shell 那两种计划是**终态**（拿到就能执行），文件不是 ——
     * 因为「这个路径能不能动」需要两件本类拿不到的东西：
     *
     * 1. **已授权的目录列表**（`FileScope`）—— 那是用户的配置，运行时才知道；
     * 2. **目标当前的状态**（在不在、是不是目录、多大）—— 那要 `stat`，
     *    而 `stat` 是 suspend + IO。
     *
     * ⚠️ 第 2 点决定了文件裁决**不能**发生在 [CapabilityGuard] 里：
     *    那个类刻意是同步的（它是纯逻辑，要能进离线验证器）。
     *
     * ⇒ 所以分工是：
     *    [CommandPlanner] 产出本对象（同步、纯字符串）
     *    → [CapabilityRunner] 拿到后：探测 → 交给 `:filelogic` 的
     *      `FileAccessDecider` 裁决 → 执行
     *
     * ⚠️ 路径**不在这里归一化**。归一化是裁决的第一步（`PathNormalizer`），
     *    而它属于 `:filelogic`。在规划期做一次、裁决期再做一次的话，
     *    两份实现迟早不一致 —— 而不一致的那一份会让"越根"从缝里钻过去。
     */
    data class FileIntent(
        val op: FileOp,

        /** 原始路径，**未归一化**。归一化是裁决器的第一步。 */
        val path: String,

        /** 写入内容。只有 [FileOp.WRITE] 有。 */
        val content: String? = null,

        /** 移动 / 重命名的目标路径。只有 [FileOp.MOVE] 有。 */
        val destinationPath: String? = null,

        override val summary: String,
    ) : PlannedExecution
}


/**
 * 执行配方 —— 「这条能力怎么变成一次执行」。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么配方写在 [Capability] 里，而不是 [CommandPlanner] 的 `when` 里
 * ═══════════════════════════════════════════════════════════════
 *
 * 另一种写法是让规划器写一大段 `when (capabilityId) { "media.dispatch" -> ... }`。
 * 它更"传统"，但有两个后果：
 *
 * 1. **目录与实现会漂移。** 往目录里加一条能力、忘了加 `when` 分支，
 *    得到的是运行时的"这条能力没实现" —— 一个**只在被调用时才暴露**的错误。
 *    而配方放在一起时，[Capability] 的 `init` 就能检查两者一致。
 * 2. **"这个能力能做什么"被拆到两个文件里。** 审查一条能力时要在
 *    目录和规划器之间来回跳，而安全审查最怕的正是"看不全"。
 *
 * ⚠️ 配方的表达能力**刻意做得极弱**：只有字面量与参数两种片段，
 *    **没有条件、没有循环、没有拼接**。
 *    因为每多一种能力，就多一类"配置里写出来的行为"需要被审查 ——
 *    而配置是可以被插件带来的。
 */
sealed interface ExecutionRecipe {

    /** 配方里引用到的参数名。用于与 [Capability.params] 做一致性校验。 */
    fun referencedParamNames(): Set<String>

    /**
     * 一条 shell 命令的**模板**。
     *
     * ⚠️ 只允许"固定字面量 + 参数"交替，例如
     *    `cmd media_session volume --stream {stream} --set {index}`。
     *
     *    刻意**不支持**把两个字面量与参数拼成一个 argv 项
     *    （如 `deviceidle whitelist` 的 `+<pkg>`）——
     *    那需要引入"拼接"这个能力，而拼接是注入的温床。
     *    代价是少数能力暂时进不来，这个取舍是划算的。
     */
    data class Shell(val segments: List<Segment>) : ExecutionRecipe {

        init {
            require(segments.isNotEmpty()) { "Shell 配方的 segments 不能为空" }
            require(segments.first() is Segment.Literal) {
                "Shell 配方必须以字面量开头 —— 否则第一个 argv 项由参数决定，等于让调用方选命令"
            }
            require(segments.none { it is Segment.Param && it.name.isBlank() }) {
                "Shell 配方里不能有空白参数名"
            }
        }

        override fun referencedParamNames(): Set<String> =
            segments.filterIsInstance<Segment.Param>().map { it.name }.toSet()

        sealed interface Segment {
            data class Literal(val text: String) : Segment {
                init {
                    require(text.isNotEmpty()) { "字面量片段不能为空" }
                }
            }

            data class Param(val name: String) : Segment
        }
    }

    /**
     * 写一个**固定的**设置键。
     *
     * ⚠️ 键名是配方里的字面量，**不能由参数决定** ——
     *    否则一条能力就能写任意设置键，
     *    [CapabilityDenyRules] 的键白名单会立刻失效
     *    （`setting.write("secure", "enabled_accessibility_services", "1")`）。
     */
    data class Setting(
        val namespace: SettingNamespace,
        val key: String,
        /** 取哪个参数作为要写入的值 */
        val valueParam: String,
    ) : ExecutionRecipe {
        override fun referencedParamNames(): Set<String> = setOf(valueParam)
    }

    /**
     * 一次文件操作。
     *
     * ⚠️ 与 [Setting] **正相反**：这里的目标路径**由参数决定**。
     *
     *    乍看像是把安全边界交给了用户，但不是 —— 文件能力的安全边界
     *    **不是"路径长什么样"，而是"路径落在哪条已授权的根下"**，
     *    而那件事由 `:filelogic` 的 `FileAccessDecider` 用
     *    `FileScope`（用户的授权列表）来判断。
     *    把路径写死成字面量，等于让这个能力毫无用处。
     */
    data class File(
        val op: FileOp,

        /** 取哪个参数作为目标路径 */
        val pathParam: String,

        /** 取哪个参数作为写入内容。只有 [FileOp.WRITE] 有。 */
        val contentParam: String? = null,

        /** 取哪个参数作为移动目标。只有 [FileOp.MOVE] 有。 */
        val destinationParam: String? = null,
    ) : ExecutionRecipe {
        override fun referencedParamNames(): Set<String> =
            listOfNotNull(pathParam, contentParam, destinationParam).toSet()
    }
}

