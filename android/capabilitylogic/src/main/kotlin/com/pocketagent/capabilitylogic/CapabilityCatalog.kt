package com.pocketagent.capabilitylogic

import com.pocketagent.filelogic.FileOp

/**
 * 第 0 档能力的**内置目录**。
 *
 * 每一条能力都对应 `docs/第0档零占屏任务清单-v1.0.md` §3 里**真机实测过**的命令
 * （`cmd <svc> -h` 的实际输出），不是照文档猜的。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么是 20 条具体能力，而不是 3 条通用能力
 * ═══════════════════════════════════════════════════════════════
 *
 * 最省事的写法是三条：
 *
 * ```
 * setting.write(namespace, key, value)
 * shell.exec(argv...)            ← 或者更省事：shell.exec(command: String)
 * ```
 *
 * 它能让目录只有一屏，但会**把整个白名单模型清零**：
 * 一旦存在"能执行任意命令"的能力，后面所有关于风险分级、参数校验、
 * 确认流程的设计都只是在给一个后门镶边。而且它是**最容易通过审查的** ——
 * 谁会觉得 `shell.exec` 危险呢，它只是个工具函数。
 *
 * ⇒ 所以这里的纪律是：**一条能力对应一个具体的、参数受限的动作。**
 *   宁可写 20 条，也不要 1 条通用的。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 目录是**可扩展**的，但扩展不能覆盖内置
 * ═══════════════════════════════════════════════════════════════
 *
 * 插件可以带自己的能力清单（[CapabilityCatalog] 的构造参数）。
 * 但**同 id 时内置优先，扩展项被丢弃** —— 否则一个插件只要声明
 * `media.dispatch` 就能把它的风险级别从 SAFE 改成……好吧 SAFE 已经是最松的，
 * 那它会去改 `wifi.set_enabled` 的参数候选值，或者把 `GUARDED` 改成 `SAFE`。
 */
class CapabilityCatalog(
    /**
     * 额外的能力（插件带来的）。
     *
     * ⚠️ 重复 id 会被丢弃，见类注释。丢弃而不是报错，是因为
     *    "插件想覆盖内置能力"这件事本身不该让整个目录加载失败 ——
     *    但必须留下痕迹，所以 [conflicts] 会记录被丢弃的 id。
     */
    extra: List<Capability> = emptyList(),
) {

    /** 被丢弃的扩展能力 id。用于审计与"为什么我的插件不生效"的诊断。 */
    val conflicts: List<String>

    private val index: Map<String, Capability>

    init {
        val dropped = mutableListOf<String>()
        val merged = LinkedHashMap<String, Capability>()

        for (c in BUILT_IN) merged[c.id] = c

        for (c in extra) {
            if (merged.containsKey(c.id)) {
                dropped += c.id
            } else {
                merged[c.id] = c
            }
        }

        conflicts = dropped.toList()
        index = merged
    }

    /** 全部能力，内置在前。 */
    val all: List<Capability> get() = index.values.toList()

    fun byId(capabilityId: String): Capability? = index[capabilityId]

    /** 按风险级别筛选，供"我放行了哪些东西"页面使用。 */
    fun withRisk(risk: CapabilityRisk): List<Capability> =
        all.filter { it.risk == risk }

    /**
     * 按分组筛选。
     */
    fun withGroup(group: CapabilityGroup): List<Capability> =
        all.filter { it.group == group }

    /**
     * 第 0 档的能力 —— 界面上"第 0 档能力"页显示的就是这一批。
     *
     * ⚠️ 判据用 `group.isZeroScreen`，**不要**写死 `group != DEVICE`：
     *    将来加一个新组时，写死的那处判断会让新组**默认落在第 0 档页上** ——
     *    又是一个"用户以为能用、实际不能"的入口。
     */
    val zeroScreen: List<Capability> get() = all.filter { it.group.isZeroScreen }


    companion object {

        /**
         * 设置类能力的辅助构造。
         *
         * ⚠️ 键名与命名空间是**字面量**，不来自参数 —— 见 [ExecutionRecipe.Setting]。
         */
        private fun setting(
            id: String,
            group: CapabilityGroup,
            namespace: SettingNamespace,
            key: String,
            valueParam: String,
            risk: CapabilityRisk,
            summary: String,
            valueSpec: ParamSpec,
        ) = Capability(
            id = id,
            channel = CapabilityChannel.SETTINGS,
            group = group,
            risk = risk,
            summary = summary,
            params = listOf(valueSpec),
            recipe = ExecutionRecipe.Setting(namespace, key, valueParam),
        )

        /**
         * shell 类能力的辅助构造。
         *
         * 模板是**空格分隔**的 token 序列，`{name}` 表示一个参数占位。
         *
         * ⚠️ 参数占位必须是**完整的一个 token**，不能写成 `--set={index}` 这种
         *    半拼接形式 —— 见 [ExecutionRecipe.Shell] 里"不支持拼接"的论证。
         */
        private fun sh(
            id: String,
            group: CapabilityGroup,
            risk: CapabilityRisk,
            summary: String,
            template: String,
            vararg params: ParamSpec,
        ): Capability {
            val segments = template.trim().split(' ')
                .filter { it.isNotEmpty() }
                .map { token ->
                    if (token.startsWith("{") && token.endsWith("}")) {
                        ExecutionRecipe.Shell.Segment.Param(token.substring(1, token.length - 1))
                    } else {
                        ExecutionRecipe.Shell.Segment.Literal(token)
                    }
                }
            return Capability(
                id = id,
                channel = CapabilityChannel.SHELL,
                group = group,
                risk = risk,
                summary = summary,
                params = params.toList(),
                recipe = ExecutionRecipe.Shell(segments),
            )
        }

        /** 通知文本的白名单正则：允许任何**非控制字符**。见 [ParamSpec.Text] 的论证。 */
        private val NO_CONTROL_CHARS = Regex("""^[^\p{Cntrl}]+$""")

        /**
         * 路径参数的规格。
         *
         * ⚠️ 这里**只挡控制字符**，不做别的 —— 因为路径的安全边界不是
         *    「字符集」，而是「它落在哪条已授权的根下」（见 [ExecutionRecipe.File]）。
         *    试图用正则把「危险路径」挡在外面是错的：正则永远漏得比挡得多，
         *    而漏掉的那一个就是穿透。
         */
        private val PATH_SPEC = ParamSpec.Text(
            name = "path",
            maxLength = 512,
            allowed = NO_CONTROL_CHARS,
            summary = "文件或目录的完整路径",
        )

        /**
         * 文件能力的辅助构造。
         *
         * ⚠️ 路径**由参数给出**，与 [setting] 的固定键名正相反 ——
         *    理由见 [ExecutionRecipe.File] 的注释。
         */
        private fun file(
            id: String,
            op: FileOp,
            risk: CapabilityRisk,
            summary: String,
            contentSpec: ParamSpec? = null,
            destinationSpec: ParamSpec? = null,
        ) = Capability(
            id = id,
            channel = CapabilityChannel.FILES,
            group = CapabilityGroup.FILES,
            risk = risk,
            summary = summary,
            params = listOfNotNull(PATH_SPEC, contentSpec, destinationSpec),
            recipe = ExecutionRecipe.File(
                op = op,
                pathParam = PATH_SPEC.name,
                contentParam = contentSpec?.name,
                destinationParam = destinationSpec?.name,
            ),
        )

        /**
         * 内置能力。
         *
         * ⚠️ **顺序不影响行为**（查表走 id），但影响用户看到的授权列表，
         *    所以按"用户最可能用到的在前"排。
         */
        val BUILT_IN: List<Capability> = listOf(

            // ══════════════════════════════════════════════════════
            //  FILES —— 第 0 档的核心（T0-D）
            //
            //  ★ 这一组是**唯一不需要任何特权**的：不用在电脑上跑 adb、
            //     不用 Shizuku、不用签名级权限 —— 用户只需在系统文件
            //     选择器里选一次目录，之后一直有效。
            //
            //  ⚠️ 而下面那些 SHELL 组**全都还在等 T0-B**（Shizuku 的
            //     UserService 那一步）。这就是把第 0 档的重心从「设备开关」
            //     移到「文件办公」的现实理由：一边点了就有结果，
            //     另一边点了只会得到「还没实现」。
            // ══════════════════════════════════════════════════════

            file(
                id = "file.list",
                op = FileOp.LIST,
                risk = CapabilityRisk.SAFE,
                summary = "列出一个目录里的文件和子目录。只读，不改任何东西。",
            ),

            file(
                id = "file.read",
                op = FileOp.READ,
                risk = CapabilityRisk.SAFE,
                summary = "读一个文本文件的内容。只读，不改任何东西。",
            ),

            file(
                id = "file.write",
                op = FileOp.WRITE,
                risk = CapabilityRisk.GUARDED,
                summary = "把一段文字写进文件。文件已经存在时，它的原内容会被替换掉。",
                contentSpec = ParamSpec.Text(
                    name = "content",
                    maxLength = 8192,
                    allowed = NO_CONTROL_CHARS,
                    summary = "要写入的正文（最多约 8000 字）",
                ),
            ),

            file(
                id = "file.delete",
                op = FileOp.DELETE,
                risk = CapabilityRisk.GUARDED,
                summary = "删除一个文件。删掉之后就找不回来了。",
            ),

            file(
                id = "file.move",
                op = FileOp.MOVE,
                risk = CapabilityRisk.GUARDED,
                summary = "移动或重命名。原来那个位置就没有了。",
                destinationSpec = ParamSpec.Text(
                    name = "destination",
                    maxLength = 512,
                    allowed = NO_CONTROL_CHARS,
                    summary = "要移到哪个路径（也可以只是换个文件名）",
                ),
            ),

            // ══════════════════════════════════════════════════════
            //  SHELL · SAFE —— 默认放行
            // ══════════════════════════════════════════════════════

            sh(
                id = "media.dispatch",
                group = CapabilityGroup.DEVICE,
                risk = CapabilityRisk.SAFE,
                summary = "控制正在播放的媒体：播放、暂停、上一首、下一首。不会改变画面。",
                template = "cmd media_session dispatch {action}",
                ParamSpec.Choice(
                    name = "action",
                    values = listOf("play", "pause", "play-pause", "next", "previous", "stop", "mute"),
                    summary = "要执行的媒体动作",
                ),
            ),

            sh(
                id = "media.volume_step",
                group = CapabilityGroup.DEVICE,
                risk = CapabilityRisk.SAFE,
                summary = "把某个音频流的音量调高、调低或静音。",
                template = "cmd media_session volume --stream {stream} --adj {direction}",
                ParamSpec.IntIn(
                    name = "stream",
                    range = 0..10,
                    summary = "音频流编号（3 = 媒体音量）",
                ),
                ParamSpec.Choice(
                    name = "direction",
                    values = listOf("raise", "lower", "same"),
                    summary = "调整方向",
                ),
            ),

            sh(
                id = "media.volume_set",
                group = CapabilityGroup.DEVICE,
                risk = CapabilityRisk.SAFE,
                summary = "把某个音频流的音量设成一个具体数值。",
                template = "cmd media_session volume --stream {stream} --set {index}",
                ParamSpec.IntIn(name = "stream", range = 0..10, summary = "音频流编号（3 = 媒体音量）"),
                ParamSpec.IntIn(name = "index", range = 0..100, summary = "目标音量档位"),
            ),

            sh(
                id = "ui.night_mode",
                group = CapabilityGroup.DEVICE,
                risk = CapabilityRisk.SAFE,
                summary = "切换深色模式。",
                template = "cmd uimode night {mode}",
                ParamSpec.Choice(
                    name = "mode",
                    values = listOf("yes", "no", "auto"),
                    summary = "深色 / 浅色 / 跟随时间",
                ),
            ),

            sh(
                id = "statusbar.collapse",
                group = CapabilityGroup.DEVICE,
                risk = CapabilityRisk.SAFE,
                summary = "收起已经拉下来的通知栏。",
                template = "cmd statusbar collapse",
            ),

            sh(
                id = "power.display",
                group = CapabilityGroup.DEVICE,
                risk = CapabilityRisk.SAFE,
                summary = "点亮或熄灭屏幕。不会打开任何界面。",
                template = "input keyevent {key}",
                ParamSpec.Choice(
                    name = "key",
                    values = listOf("KEYCODE_SLEEP", "KEYCODE_WAKEUP"),
                    summary = "熄屏 / 亮屏",
                ),
            ),

            sh(
                id = "notification.post",
                group = CapabilityGroup.NOTIFY,
                risk = CapabilityRisk.SAFE,
                summary = "发一条通知。内容由你确认后才会发出。",
                template = "cmd notification post {tag} {text}",
                ParamSpec.Text(
                    name = "tag",
                    maxLength = 32,
                    allowed = NO_CONTROL_CHARS,
                    summary = "通知标签（用于去重）",
                ),
                ParamSpec.Text(
                    name = "text",
                    maxLength = 200,
                    allowed = NO_CONTROL_CHARS,
                    summary = "通知正文",
                ),
            ),

            // ══════════════════════════════════════════════════════
            //  SETTINGS · SAFE
            // ══════════════════════════════════════════════════════

            setting(
                id = "display.auto_rotate",
                group = CapabilityGroup.DEVICE,
                namespace = SettingNamespace.SYSTEM,
                key = "accelerometer_rotation",
                valueParam = "enabled",
                risk = CapabilityRisk.SAFE,
                summary = "开关屏幕自动旋转。",
                valueSpec = ParamSpec.Choice(
                    name = "enabled",
                    values = listOf("0", "1"),
                    summary = "0 = 关闭，1 = 开启",
                ),
            ),

            // ══════════════════════════════════════════════════════
            //  SHELL · GUARDED —— 默认拒绝，需用户逐项放行
            // ══════════════════════════════════════════════════════

            sh(
                id = "wifi.set_enabled",
                group = CapabilityGroup.DEVICE,
                risk = CapabilityRisk.GUARDED,
                summary = "开关 Wi-Fi。关掉之后手机会断开无线网络，可能收不到消息。",
                template = "cmd wifi set-wifi-enabled {state}",
                ParamSpec.Choice(
                    name = "state",
                    values = listOf("enabled", "disabled"),
                    summary = "开启 / 关闭",
                ),
            ),

            sh(
                id = "airplane_mode.set_enabled",
                group = CapabilityGroup.DEVICE,
                risk = CapabilityRisk.GUARDED,
                summary = "开关飞行模式。开启后会同时断掉移动网络、Wi-Fi 与蓝牙。",
                template = "cmd connectivity airplane-mode {state}",
                ParamSpec.Choice(
                    name = "state",
                    values = listOf("enable", "disable"),
                    summary = "开启 / 关闭",
                ),
            ),

            sh(
                id = "dnd.set_mode",
                group = CapabilityGroup.DEVICE,
                risk = CapabilityRisk.GUARDED,
                summary = "切换免打扰模式。开启后来电与通知不会响铃。",
                template = "cmd notification set_dnd {mode}",
                ParamSpec.Choice(
                    name = "mode",
                    values = listOf("on", "priority", "alarms", "off"),
                    summary = "开启 / 仅允许优先 / 仅允许闹钟 / 关闭",
                ),
            ),

            sh(
                id = "app.set_enabled",
                group = CapabilityGroup.DEVICE,
                risk = CapabilityRisk.GUARDED,
                summary = "停用或重新启用一个应用。停用后它的图标会消失，直到被重新启用。",
                template = "cmd package {action} {packageName}",
                ParamSpec.Choice(
                    name = "action",
                    values = listOf("disable", "enable", "disable-user"),
                    summary = "停用 / 启用",
                ),
                ParamSpec.PackageName(
                    name = "packageName",
                    summary = "应用的包名",
                ),
            ),

            // ══════════════════════════════════════════════════════
            //  SETTINGS · GUARDED
            // ══════════════════════════════════════════════════════

            setting(
                id = "display.brightness",
                group = CapabilityGroup.DEVICE,
                namespace = SettingNamespace.SYSTEM,
                key = "screen_brightness",
                valueParam = "level",
                risk = CapabilityRisk.GUARDED,
                summary = "设置屏幕亮度。",
                valueSpec = ParamSpec.IntIn(
                    name = "level",
                    range = 1..255,
                    summary = "亮度值（1 最暗，255 最亮）",
                ),
            ),

            setting(
                id = "display.screen_off_timeout",
                group = CapabilityGroup.DEVICE,
                namespace = SettingNamespace.SYSTEM,
                key = "screen_off_timeout",
                valueParam = "millis",
                risk = CapabilityRisk.GUARDED,
                summary = "设置自动熄屏的等待时间。",
                valueSpec = ParamSpec.IntIn(
                    name = "millis",
                    range = 5_000..1_800_000,
                    summary = "等待毫秒数（最短 5 秒，最长 30 分钟）",
                ),
            ),

            setting(
                id = "display.stay_awake_while_plugged",
                group = CapabilityGroup.DEVICE,
                namespace = SettingNamespace.GLOBAL,
                key = "stay_on_while_plugged_in",
                valueParam = "mode",
                risk = CapabilityRisk.GUARDED,
                // ⚠️ 这条的说明必须提到烧屏 —— 见 MEMORY 里"夜间跑屏幕任务的真实成本"
                summary = "设置充电时是否保持亮屏。⚠️ 长期亮屏会让 OLED 屏幕留下残影。",
                valueSpec = ParamSpec.Choice(
                    name = "mode",
                    values = listOf("0", "1", "2", "4", "7"),
                    summary = "0 = 不保持；1 = 交流电；2 = USB；4 = 无线；7 = 全部",
                ),
            ),
        )
    }
}
