package com.pocketagent.plugin.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 宽容的能力列表序列化器。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它解决的是什么问题
 * ═══════════════════════════════════════════════════════════════
 *
 * `List<PluginCapability>` 用默认序列化器时，遇到不认识的 id 会**直接抛异常**。
 * 于是"未知能力"在两条路径上表现不一致：
 *
 *  | 路径 | 字段类型 | 遇到未知 id 的行为 |
 *  |---|---|---|
 *  | 市场目录 | `SubscriptionEntry.capabilities: List<String>` | 丢弃，记进 rawCapabilityIds |
 *  | 本地导入 | `PluginManifest.capabilities: List<PluginCapability>` | **解析失败** |
 *
 * 这不只是体验不一致。**严格模式把兼容性问题伪装成了格式错误**：
 * 一个为将来版本编写的插件，用户看到的报错是"清单格式不正确"，
 * 于是他会去找作者换个版本 —— 而真正的原因是他的本体太旧，换版本没用。
 *
 * 所以这里改成宽容解析：不认识的 id **丢弃，不报错**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么这不构成安全漏洞
 * ═══════════════════════════════════════════════════════════════
 *
 * 关键点：**丢弃事实不是由这个序列化器上报的。**
 *
 * [PluginValidator.inspectRawCapabilities] 在**反序列化之前**读原始 JSON，
 * 独立捕获禁止前缀（ERROR）与未知能力（WARNING）。序列化器只负责"别炸"，
 * 不负责判断 —— 它看不到 `payment.pay` 和 `future.telepathy` 的区别，
 * 也不该看到。判断集中在 [PluginValidator] 一处，两条路径共用。
 *
 * 换句话说：**宽容的是解析，不是校验。** 一个申请 `payment.pay` 的插件，
 * 依然会在 inspectRawCapabilities 那一步被拦下来，一步都不会少。
 *
 * ⚠️ 顺带一提，序列化时反向映射回 `id` 字符串。这样 `PluginManifest` 的
 *    序列化输出里能力字段仍是人类可读的 `"screen.read"`，而不是枚举序号 ——
 *    插件清单是要给作者手写和排错的，可读性不是可选项。
 */
object PluginCapabilityListSerializer : KSerializer<List<PluginCapability>> {

    /**
     * 借用 `List<String>` 的描述符。
     *
     * 刻意不借用 `ListSerializer(PluginCapability.serializer())` 的描述符：
     * 我们用不上枚举元素描述符（不认识的 id 根本到不了枚举那一步），
     * 而 `List<String>` 的形状与 JSON 里实际存的东西完全一致。
     */
    private val delegate = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<PluginCapability> {
        val rawIds = decoder.decodeSerializableValue(delegate)
        return rawIds.mapNotNull { PluginCapability.fromId(it.trim().lowercase()) }
    }

    override fun serialize(encoder: Encoder, value: List<PluginCapability>) {
        encoder.encodeSerializableValue(delegate, value.map { it.id })
    }
}
