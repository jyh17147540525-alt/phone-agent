package com.pocketagent.filelogic

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 解码结论。失败必须带原因 —— 见 [WorkspaceCodec.decode]。 */
sealed interface WorkspaceDecodeOutcome {

    data class Ok(val workspaces: List<Workspace>) : WorkspaceDecodeOutcome

    data class Failed(val reason: String) : WorkspaceDecodeOutcome
}

/**
 * 工作区列表的持久化编解码。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 最重要的一条：**损坏时返回 [WorkspaceDecodeOutcome.Failed]，
 *     绝不返回空列表**
 * ═══════════════════════════════════════════════════════════════
 *
 * 这是本项目头号 bug 形态（「安静地少做一件事」）最容易出现的地方。
 *
 * 一个 `?: emptyList()` 的兜底，会让"存储文件坏了"与"用户还没添加过工作区"
 * 在界面上长得**一模一样**：
 *
 * | 真实情况 | 兜底后的表现 | 用户会怎么做 |
 * |---|---|---|
 * | 文件损坏 | 「还没有工作区」 | 重新添加一遍（他以为数据没丢，其实丢了） |
 * | 真的没添加过 | 「还没有工作区」 | 添加一个 |
 *
 * ⇒ 两者要采取的行动**完全不同**，而兜底把它们合并成了一种。
 *   返回 `Failed` 让调用方能显示"工作区列表读不出来了（文件可能损坏）"，
 *   这才是用户能据以行动的信息。
 *
 * ═══════════════════════════════════════════════════════════════
 *  外壳与版本
 * ═══════════════════════════════════════════════════════════════
 *
 * 带一层自描述外壳（版本号 + 列表），理由同 `OffloadCodec`：
 * 裸存一个数组的话，将来要在旁边加字段（比如"工作区排序方式"）就没人知道
 * 该按哪个版本读。读到不认识的版本必须**报损坏**，不能"尽力解析" ——
 * 一个被新版本擅自解释过的旧文件，可能把某个字段当成了别的东西，
 * 而这种错误是静默的。
 */
object WorkspaceCodec {

    /**
     * 外壳版本。
     *
     * ⚠️ 改动**任何已有字段的语义**时必须 +1。
     *    只加新字段（有默认值）则不必 —— `ignoreUnknownKeys` 能兼容读取。
     */
    const val FORMAT_VERSION = 1

    /**
     * 编码。**确定性**：同一份列表两次编码产出同样的字节。
     *
     * 确定性靠两件事：
     * 1. 先排序（`lastOpenedAt` 降序，同刻按 id）—— 不依赖调用方的列表顺序
     * 2. `encodeDefaults = true` —— 有默认值的字段也写出来，
     *    避免"默认值变化"导致同一份数据产出不同字节
     */
    fun encode(workspaces: List<Workspace>): String =
        JSON.encodeToString(Envelope.serializer(), Envelope(v = FORMAT_VERSION, items = sort(workspaces)))

    /**
     * 解码。
     *
     * @return 成功时按"最近打开"降序排好序；**任何**异常都转成 [WorkspaceDecodeOutcome.Failed]。
     */
    fun decode(text: String): WorkspaceDecodeOutcome {
        if (text.isBlank()) {
            // 空文件是**正常的初始状态**（还没存过），不是损坏。
            // ⚠️ 这一条是刻意的例外：它与"文件里是乱码"必须区分开。
            return WorkspaceDecodeOutcome.Ok(emptyList())
        }

        val envelope = try {
            JSON.decodeFromString(Envelope.serializer(), text)
        } catch (e: SerializationException) {
            return WorkspaceDecodeOutcome.Failed("工作区存储无法解析（文件可能被截断或不是本格式）：${e.message}")
        } catch (e: IllegalArgumentException) {
            // ⚠️ 这一条会真的发生：`ScopeRoot` / `WorkspacePolicy` 的 init 校验
            //    在反序列化时被触发，而校验失败抛的是 IllegalArgumentException。
            //    它是**好事** —— 一份被手工改坏的文件在这里就暴露了，
            //    而不是在更晚的时刻表现为"某个工作区行为诡异"。
            return WorkspaceDecodeOutcome.Failed("工作区存储里的数据不合法：${e.message}")
        }

        if (envelope.v != FORMAT_VERSION) {
            return WorkspaceDecodeOutcome.Failed(
                "工作区存储的版本不认识：文件为 ${envelope.v}，本版本只认 $FORMAT_VERSION",
            )
        }

        // 重复 id 是**损坏**，不是"去重一下就行" ——
        // 两个同 id 的工作区会让"按 id 查找"变成不确定行为。
        val ids = envelope.items.map { it.id }
        if (ids.size != ids.toSet().size) {
            val dup = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            return WorkspaceDecodeOutcome.Failed("工作区存储里存在重复 id：$dup")
        }

        return WorkspaceDecodeOutcome.Ok(sort(envelope.items))
    }

    /** 按"最近打开"降序；同刻按 id 升序 —— 保证顺序**不依赖输入顺序**。 */
    private fun sort(workspaces: List<Workspace>): List<Workspace> =
        workspaces.sortedWith(
            compareByDescending<Workspace> { it.lastOpenedAt }.thenBy { it.id },
        )

    @Serializable
    private data class Envelope(
        val v: Int,
        val items: List<Workspace> = emptyList(),
    )

    private val JSON = Json {
        encodeDefaults = true
        // 向前兼容**读取**：新版本加了字段之后，老版本读到它不会炸。
        // ⚠️ 反过来（遇到不认识的版本号）必须拒绝，见 decode。
        ignoreUnknownKeys = true
        prettyPrint = true
    }
}
