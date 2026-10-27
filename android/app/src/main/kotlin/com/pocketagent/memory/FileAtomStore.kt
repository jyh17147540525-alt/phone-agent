package com.pocketagent.memory

import com.pocketagent.memorylogic.Atom
import com.pocketagent.memorylogic.AtomKind
import com.pocketagent.memorylogic.AtomStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import timber.log.Timber

/**
 * [AtomStore] 的文件实现 —— **一行一条**的 JSON（JSONL）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么是 JSONL 而不是一个 JSON 数组
 * ═══════════════════════════════════════════════════════════════
 *
 * 数组格式下**任何一处损坏都会让整份记忆读不出来**：少一个 `]`，
 * 或者进程在写一半时被杀，结果就是"助理忘了你的一切"，而且不报错。
 *
 * JSONL 的坏处被限制在**那一行**：坏行跳过、其余照读，并且能记下跳过了几条。
 * 对"记忆"这种东西，**部分可用**远胜于**全有或全无**。
 *
 * ⚠️ 写入用 `tmp + rename`：直接覆盖原文件时被杀，会留下一份写了一半的文件。
 *    rename 在同一分区上是原子的 —— 要么是旧文件，要么是新文件。
 *
 * ⚠️ 刻意**不加密**：它与聊天记录在同一个应用私有目录里，
 *    而聊天记录本身（`ConversationStore` / SQLCipher）才是敏感面。
 *    给一条"他喜欢蓝色"的原子加一层密钥，收益接近零，代价是又一把钥匙要保管。
 */
class FileAtomStore(private val file: File) : AtomStore {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 上次读盘时跳过的坏行数。**必须可观测** —— 静默丢记忆是最难查的一类问题。 */
    @Volatile
    var lastSkippedLines: Int = 0
        private set

    override fun loadAll(): List<Atom> {
        if (!file.isFile) return emptyList()
        var skipped = 0
        val out = mutableListOf<Atom>()
        runCatching {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachLine
                val atom = runCatching { parse(line) }.getOrNull()
                if (atom == null) skipped++ else out += atom
            }
        }.onFailure {
            // 整个文件读不出来（权限/IO）—— 如实记，但不让应用起不来
            Timber.e(it, "原子记忆读取失败：${file.name}")
            return emptyList()
        }
        lastSkippedLines = skipped
        if (skipped > 0) {
            Timber.w("原子记忆有 %d 行解析不了（共 %d 条可用）", skipped, out.size)
        }
        return out
    }

    override fun replaceAll(atoms: List<Atom>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(atoms.joinToString(separator = "\n", postfix = "\n") { encode(it) })
            if (!tmp.renameTo(file)) {
                // 极少数情况下 rename 会失败（跨分区/被占用）—— 退回拷贝后删
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Timber.e(it, "原子记忆写入失败：${file.name}") }
    }

    private fun encode(a: Atom): String = buildJsonObject {
        put("id", JsonPrimitive(a.id))
        put("kind", JsonPrimitive(a.kind.name))
        put("text", JsonPrimitive(a.text))
        put("src", buildJsonArray { a.sourceTurnIds.forEach { add(JsonPrimitive(it)) } })
        put("conf", JsonPrimitive(a.confidence))
        put("at", JsonPrimitive(a.at))
    }.toString()

    /**
     * 解析一行。
     *
     * ⚠️ 任何一处不合法都**抛**，由调用方计为坏行 —— 而不是"尽力拼一个 Atom 出来"。
     *    `Atom` 的 `init` 本身就有 4 条 require（空 id / 空文本 / 无溯源 / 置信度越界），
     *    在这里兜住它们，坏数据就进不了库。
     */
    private fun parse(line: String): Atom {
        val obj: JsonObject = json.parseToJsonElement(line).jsonObject
        val src: JsonArray = obj["src"]?.jsonArray ?: JsonArray(emptyList())
        return Atom(
            id = obj["id"]?.jsonPrimitive?.content ?: error("缺 id"),
            kind = AtomKind.valueOf(obj["kind"]?.jsonPrimitive?.content ?: error("缺 kind")),
            text = obj["text"]?.jsonPrimitive?.content ?: error("缺 text"),
            sourceTurnIds = src.map { it.jsonPrimitive.content },
            confidence = obj["conf"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: error("缺 conf"),
            at = obj["at"]?.jsonPrimitive?.longOrNull ?: error("缺 at"),
        )
    }
}
