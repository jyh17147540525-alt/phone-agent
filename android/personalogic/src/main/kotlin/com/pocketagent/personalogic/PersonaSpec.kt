package com.pocketagent.personalogic

/**
 * 「当前这个人格是什么样」的**只读快照**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 构造是私有的，唯一入口是 [derive]
 * ═══════════════════════════════════════════════════════════════
 *
 * 这不是为了"防手滑"，是为了让**骨骼不可能被改**成为类型层面的结论，
 * 而不是一句需要靠人遵守的约定：
 *
 * - 构造私有 ⇒ 外部无法 `spec.copy(bones = 别的骨骼)`；
 * - [derive] 只从 [PersonaPreset] 取 `bones`（恒为 [SafetyBones] 默认值）
 *   与 `baseline`（恒为预设的初始语气）；
 * - [applying] 只写"血肉"字段，**连参数都没有**表达骨骼与基线的地方。
 *
 * 于是"人格层不能碰安全边界"这条不变量，检查方式从"走查所有调用点"
 * 变成了"看这一个文件"。
 *
 * ## 为什么 [baseline] 与 [tone] 是分开的两个字段
 *
 * [baseline] 是**反漂移锚点**（"最初的我"），[tone] 是"现在的我"。
 * 漂移 = 两者的距离。若只有一个字段，就必须在别处另存一份基线 ——
 * 而那份基线会随着"用户点了重置"之类的操作被漏改，于是距离计算失真，
 * 用户看到"我好像变得不像我了"却其实什么都没变。
 *
 * ⚠️ 注意 [baseline] 与 [applying] 无关：**微调不移动锚点**。
 *    锚点只在用户明确"以现在为新的起点"时才该动，那属于 P5 的范围，
 *    本阶段（P1）刻意不提供。
 *
 * @property addressStyle 怎么称呼用户
 * @property dialect 方言 / 口癖
 * @property catchphrase 口头禅
 * @property tone 当前语气轴
 * @property bones 安全骨骼。**恒为默认值**，见 [SafetyBones.isIntact]
 * @property baseline 语气轴的初始值，用作反漂移锚点
 */
data class PersonaSpec private constructor(
    val presetId: String,
    val displayName: String,
    val addressStyle: String,
    val dialect: String,
    val catchphrase: String,
    val tone: ToneAxes,
    val bones: SafetyBones,
    val baseline: ToneAxes,
) {

    /** 读取某个白名单字段的**当前生效值**（字符串形态，与账本一致） */
    fun valueOf(field: PersonaField): String = when (field) {
        PersonaField.ADDRESS_STYLE -> addressStyle
        PersonaField.DIALECT -> dialect
        PersonaField.CATCHPHRASE -> catchphrase
        // 轴类字段的 axis 一定非空（PersonaField 的构造约束保证）
        else -> tone.axisOf(requireNotNull(field.axis)).toString()
    }

    /**
     * 应用一条变更，返回新的快照。
     *
     * 只接受已生效（[DeltaState.ACTIVE]）的 delta —— 候选区的条目
     * 绝不允许参与回放。这一条如果错了，表现是"候选区的偏好悄悄生效了"，
     * 而用户从任何界面都看不出来。
     */
    fun applying(delta: PersonaDelta): PersonaSpec {
        require(delta.isActive()) { "只有 ACTIVE 的 delta 可以参与回放，当前为 ${delta.state}" }
        return when (delta.field.kind) {
            ValueKind.TEXT -> when (delta.field) {
                PersonaField.ADDRESS_STYLE -> rebuilt(addressStyle = delta.newValue)
                PersonaField.DIALECT -> rebuilt(dialect = delta.newValue)
                PersonaField.CATCHPHRASE -> rebuilt(catchphrase = delta.newValue)
                // 枚举已穷尽；轴类字段不会走到这里
                else -> error("字段 ${delta.field.name} 的 kind 与处理分支不一致")
            }

            // 回放路径用 coerce 而不是 withAxis：这是**历史数据**，
            // 越界只可能来自更早版本的更松校验，没有可以追问的对象。
            // 详见 ToneAxes.coerce 的注释。
            ValueKind.AXIS -> rebuilt(
                tone = tone.withAxis(
                    requireNotNull(delta.field.axis),
                    ToneAxes.coerce(delta.newValue.toInt()),
                ),
            )
        }
    }

    /**
     * 重建快照。
     *
     * ★ 刻意**不暴露** `bones` 与 `baseline` 两个参数 ——
     *    它们在这个函数签名里根本不出现，因此没有任何调用点
     *    能通过"顺手传一个"的方式改到它们。
     */
    private fun rebuilt(
        addressStyle: String = this.addressStyle,
        dialect: String = this.dialect,
        catchphrase: String = this.catchphrase,
        tone: ToneAxes = this.tone,
    ): PersonaSpec = PersonaSpec(
        presetId = presetId,
        displayName = displayName,
        addressStyle = addressStyle,
        dialect = dialect,
        catchphrase = catchphrase,
        tone = tone,
        bones = bones,
        baseline = baseline,
    )

    companion object {

        /**
         * 由"预设 + 变更账本"推导出当前人格。**唯一的构造入口。**
         *
         * ## 为什么是"回放"而不是"存一份当前值"
         *
         * 存一份当前值就要存"它是怎么变成这样的"；两者一旦不同步，
         * 用户会看到"我明明回滚过，怎么还是新的"。回放让当前值
         * **永远是账本的函数**，不存在第二个真相来源。
         *
         * 回放的顺序：`createdAt` 升序，同刻用 id 序号兜底 ——
         * 同一毫秒内产生的两条变更不能靠"插入顺序"决定先后，
         * 那在不同设备上会得到不同结果。
         *
         * @param preset 基础预设
         * @param ledger 变更账本（可含 CANDIDATE 与 ROLLED_BACK，本函数只取 ACTIVE）
         */
        fun derive(preset: PersonaPreset, ledger: List<PersonaDelta>): PersonaSpec {
            var spec = PersonaSpec(
                presetId = preset.id,
                displayName = preset.displayName,
                addressStyle = preset.addressStyle,
                dialect = preset.dialect,
                catchphrase = preset.catchphrase,
                tone = preset.tone,
                // ★ 骨骼恒为默认值。没有任何入参能改变它。
                bones = SafetyBones(),
                // ★ 锚点 = 预设的初始语气，微调不移动它。
                baseline = preset.tone,
            )

            ledger.asSequence()
                .filter { it.isActive() }
                .sortedWith(
                    compareBy<PersonaDelta> { it.createdAt }
                        .thenBy { PersonaDelta.sequenceOf(it.id) ?: Int.MAX_VALUE },
                )
                .forEach { spec = spec.applying(it) }

            return spec
        }
    }
}