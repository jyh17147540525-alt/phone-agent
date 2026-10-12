package com.pocketagent.assistant

import com.pocketagent.mcp.PersonaBrief
import com.pocketagent.mcp.PersonaBriefPort
import com.pocketagent.mcp.personaBriefOf
import com.pocketagent.personalogic.DefaultPersonaTuner
import com.pocketagent.personalogic.PersonaPresets
import com.pocketagent.personalogic.PersonaRuntime

/**
 * 人格方向在 `:app` 侧的**全部接线**：一个适配器，把 `:personalogic` 的
 * [PersonaRuntime] 接到 `:mcp` 的 [PersonaBriefPort] 上。
 *
 * ═══════════════════════════════════════════════════════════════
 *  与 McpBridge.kt / ActionPorts.kt 的关系
 * ═══════════════════════════════════════════════════════════════
 *
 * - `McpBridge.kt`  —— 读屏方向的两根端口（ScreenReaderPort / ScreenSafetyPort）
 * - `ActionPorts.kt` —— 执行方向的两根端口（ActionSafetyPort / ActionDispatchPort）
 * - 本文件          —— 人格方向的**一根**端口（PersonaBriefPort）
 *
 * 三条纪律完全相同：**只搬运、不判定**。
 * 本文件里任何"顺手拼一句人格描述"都会制造一份 `:personalogic` 之外的影子人格 ——
 * 而那份影子人格不会随账本回放变化，于是"用户改了口癖，助理还是老样子"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它是 `:personalogic` 的第一个消费方（2026-10-12）
 * ═══════════════════════════════════════════════════════════════
 *
 * 在此之前，DL-1…DL-4 的 5000+ 行、1852 条测试**没有任何模块引用** ——
 * 编译得过、测试全绿，但 `classes.dex` 里一个字节都没有。
 * 那不是"还没接线"，那是**逻辑悬空**：判错的后果（用户以为人格变了其实没变、
 * 白名单漏一个字段击穿安全边界）在真机上永远不会发生，因为代码根本没进包。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 本文件不含"组装"
 * ═══════════════════════════════════════════════════════════════
 *
 * [PersonaRuntime] 的实例从哪来（当前用哪个 preset、账本从哪恢复），
 * 以及把工具注册进 `ToolRegistry`，属于 `AppContainer` 的事（见 `buildMcpBridgeHost`）。
 * 本文件只提供一个适配器类，供接线时 new 出来。
 */
class AppPersonaBriefPort(
    private val runtime: PersonaRuntime,
) : PersonaBriefPort {

    /**
     * 读一次人格摘要。
     *
     * ⚠️ 这里**只做映射**（`:personalogic` 的运行时 → `:mcp` 的扁平视图），
     *    不新增任何"人格从哪来"的判断 —— 真相来源仍是 `PersonaRuntime`。
     *
     * ⚠️ `currentState()` 会**推进时间**（那是刻意的：冻结的状态是假话）。
     *    本适配器每被调用一次就推进一次，这是正确行为 —— 见 PersonaRuntime 的注释。
     */
    override fun brief(): PersonaBrief =
        personaBriefOf(runtime.currentSpec(), runtime.currentState())
}

/**
 * 建一个"开箱可用"的助理人格 —— **模板 + 运行时**。
 *
 * ⚠️ 当前**只有默认 preset + 空账本** —— 这是如实的起点，不是省略：
 *    账本的持久化归 `:memory`（还没接），人格微调的入口是向导 UI（还没做），
 *    对话循环驱动状态事件归 `:assistant`（还没有）。
 *
 *    把这几样"预支"进来的后果是**猜一个还没问出口的需求**，而猜错的代价
 *    （比如账本格式定错）是用户已经攒下的人格调整全部作废。
 *    这与设计稿 §8.8 拒绝先定人格包格式是同一条纪律。
 *
 * ★ 但它**不是**一个假消费方：`brief()` 与系统提示词返回的每一项都由 `:personalogic`
 *   真实算出 —— 六根语气轴的数值来自 `PersonaPresets.MALE.tone`，
 *   禁用语来自 `LanguageFingerprint` 的默认集，骨骼来自 `SafetyBones` 的默认值。
 *   这些常量只存在于 `:personalogic` 里，所以真机上读到的数值本身就是
 *   "依赖边真的通了"的证据。
 *
 * ⚠️ 为什么把 [PersonaPreset] 一起交出去（而不是只给 `PersonaRuntime`）：
 *    见 [AssistantPersona] 的注释 —— `PersonaCard.of(preset, spec)` 需要它，
 *    而"模板 id 认不出来时退到哪个"这个兜底判断**只能有一份**。
 */
fun defaultAssistantPersona(): AssistantPersona = AssistantPersona(
    preset = PersonaPresets.MALE,
    runtime = PersonaRuntime(DefaultPersonaTuner(PersonaPresets.MALE)),
)
