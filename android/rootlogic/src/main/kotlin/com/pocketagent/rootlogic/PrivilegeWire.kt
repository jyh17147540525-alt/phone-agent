package com.pocketagent.rootlogic

import java.io.ByteArrayOutputStream

/**
 * 常驻 root 侧守护进程的**线上协议**（帧编解码 + 请求/应答配对）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么要有这一层，而不是 continue 用 `su -c "cmd"`
 * ═══════════════════════════════════════════════════════════════
 *
 * 现在每一条 root 动作都是一次 `su -c`。这条路的问题不是"不安全"，
 * 是**慢得没法做 agent**：
 *
 * ```
 * fork su ──▶ Magisk 检查授权 ──▶ fork sh ──▶ exec cmd ──▶ 回收
 *   ≈ 80–300ms（真机中位在 120ms 上下，冷启动更久）
 * ```
 *
 * 一个 30 步的任务，光"启动命令"就吃掉 3–9 秒，而且这 3–9 秒全是
 * **等待而不是工作**。更糟的是它发生在 agent 循环里：模型每点一下
 * 屏幕，都要再付一次这个钱。
 *
 * ⇒ 常驻守护进程（`su -c app_process …`，uid 0 的 JVM）把这条成本
 *   变成**一次 socket 往返（<1ms）**，而且拿到的不是 shell 文本，
 *   是 framework 层的结构化返回。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 帧格式（固定头 + 长度前缀，无转义问题）
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 * offset  size  field
 *      0     4   bodyLen   （大端，不含 13 字节头）
 *      4     1   kind      （1=REQUEST 2=REPLY 3=ERROR）
 *      5     4   id        （大端；请求与应答配对用）
 *      9     4   code      （请求：动词；应答：退出码 / 错误码）
 *     13     n   body      （argv 或 stdout+stderr，见下）
 * ```
 *
 * 为什么不直接塞 JSON：
 * · `dumpsys`、`logcat`、二进制帧的输出里满是引号与控制字符，
 *   JSON 要么转义成两倍长，要么在某个边缘字符上炸掉；
 * · 长度前缀的二进制帧对**分片读取**天然友好（socket 不保证一次给全），
 *   而 JSON 流要靠扫描括号配平 —— 那是最经典的解析器漏洞来源。
 *
 * ⚠️ [MAX_BODY] 必须设上限：没有上限的长度前缀，等于让对端
 *    `bodyLen = 0x7FFFFFFF` 一句话把本进程 OOM 掉。
 */
object PrivilegeWire {

    /** 13 字节固定头。 */
    const val HEADER = 13

    /** 单帧正文上限 8 MB（一张 3200×1440 的原始截图正好在这个量级）。 */
    const val MAX_BODY = 8 * 1024 * 1024

    /** 正文里 argv / stdout+stderr 的分隔符。 */
    const val SEP: Byte = 0

    enum class Kind(val wire: Int) {
        REQUEST(1),
        REPLY(2),
        ERROR(3);

        companion object {
            fun of(wire: Int): Kind? = entries.firstOrNull { it.wire == wire }
        }
    }

    /**
     * 一帧。
     *
     * @param code 请求里是动词（见 [RootVerb]），应答里是退出码，错误帧里是错误类型。
     */
    class Frame(
        val kind: Kind,
        val id: Int,
        val code: Int,
        val body: ByteArray,
    ) {
        fun encode(): ByteArray {
            val out = ByteArray(HEADER + body.size)
            writeInt(out, 0, body.size)
            out[4] = kind.wire.toByte()
            writeInt(out, 5, id)
            writeInt(out, 9, code)
            body.copyInto(out, HEADER)
            return out
        }

        override fun toString(): String = "Frame($kind, id=$id, code=$code, ${body.size}B)"
    }

    /** 一次解码的结果：解出来的帧 + 还没凑成整帧的尾巴。 */
    class Decoded(val frames: List<Frame>, val leftover: ByteArray)

    /**
     * 从缓冲区里尽可能多地解出完整帧。
     *
     * ⚠️ **不抛异常、不丢字节**：分片读取是常态，尾巴必须原样还回去
     *    等下一批数据拼上。若这里选择"解析失败就整段丢弃"，
     *    一个粘包就会让后续所有请求错位 —— 而这种错位最终表现为
     *    "点错了地方"，不是报错。
     */
    fun decode(buffer: ByteArray): Decoded {
        val frames = ArrayList<Frame>()
        var at = 0
        while (buffer.size - at >= HEADER) {
            val bodyLen = readInt(buffer, at)
            if (bodyLen < 0 || bodyLen > MAX_BODY) {
                // 头已经坏了：把剩余字节全部吞掉（上层会重连），而不是猜着解。
                return Decoded(frames, ByteArray(0))
            }
            if (buffer.size - at - HEADER < bodyLen) break
            val kind = Kind.of(buffer[at + 4].toInt() and 0xFF) ?: return Decoded(frames, ByteArray(0))
            val id = readInt(buffer, at + 5)
            val code = readInt(buffer, at + 9)
            val body = buffer.copyOfRange(at + HEADER, at + HEADER + bodyLen)
            frames += Frame(kind, id, code, body)
            at += HEADER + bodyLen
        }
        return Decoded(frames, buffer.copyOfRange(at, buffer.size))
    }

    /**
     * argv → 正文。参数里出现 NUL 会被拒绝（协议无法表达，必须显式失败）。
     *
     * 空 list 是**合法**的：`PING` 与 `PROBE` 本来就没有参数。以前这里
     * `require(argv.isNotEmpty())`，导致这两个动词在客户端根本发不出去 ——
     * 探活失败被当成"通道不可用"，而不是"协议不让我探"。
     */
    fun encodeArgv(argv: List<String>): ByteArray {
        if (argv.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream()
        for ((i, arg) in argv.withIndex()) {
            require(arg.indexOf('\u0000') < 0) { "参数里不能有 NUL：第 ${i + 1} 个" }
            if (i > 0) out.write(SEP.toInt())
            out.write(arg.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    fun decodeArgv(body: ByteArray): List<String> =
        String(body, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }

    /** 应答正文：stdout ␀ stderr。 */
    fun encodeOutput(stdout: String, stderr: String): ByteArray =
        (stdout.toByteArray(Charsets.UTF_8) + byteArrayOf(SEP) + stderr.toByteArray(Charsets.UTF_8))

    fun decodeOutput(body: ByteArray): Pair<String, String> {
        val at = body.indexOf(SEP)
        if (at < 0) return String(body, Charsets.UTF_8) to ""
        return String(body.copyOfRange(0, at), Charsets.UTF_8) to
            String(body.copyOfRange(at + 1, body.size), Charsets.UTF_8)
    }

    private fun writeInt(dst: ByteArray, at: Int, v: Int) {
        dst[at] = (v ushr 24).toByte()
        dst[at + 1] = (v ushr 16).toByte()
        dst[at + 2] = (v ushr 8).toByte()
        dst[at + 3] = v.toByte()
    }

    private fun readInt(src: ByteArray, at: Int): Int =
        ((src[at].toInt() and 0xFF) shl 24) or
            ((src[at + 1].toInt() and 0xFF) shl 16) or
            ((src[at + 2].toInt() and 0xFF) shl 8) or
            (src[at + 3].toInt() and 0xFF)
}

/**
 * 守护进程侧暴露的动词。
 *
 * ★ **动词是枚举而不是"执行任意命令"**：一旦协议里有 `exec(String)`，
 *   整个 `CapabilityCatalog` 的白名单模型（"一条能力＝一个受限动作"）
 *   就在 root 这一层被绕过 —— shell 注入会重新变成可能。
 *   所以每条能力对应一个动词 + 强类型参数，参数在守护进程里再校验一遍。
 */
enum class RootVerb(val wire: Int) {
    /** 探活。守护进程回自己的 uid / SELinux 上下文。 */
    PING(1),

    /** 结构化取包信息（`IPackageManager`，不是 `pm list` 文本）。 */
    QUERY(2),

    /** 注入触摸事件（`InputManager.injectInputEvent`，多指、带时间轴）。 */
    INJECT(3),

    /** 抓帧（`SurfaceControl`）。 */
    CAPTURE(4),

    /** 读 /proc、/sys 下的只读信息。 */
    READ(5),

    /** 写系统设置（namespace:key，key 必须在白名单内）。 */
    SETTINGS(6),

    /** 受范围与速率约束的网络探测。目标必须过 `ScopeGuard`。 */
    PROBE(7),

    /** 执行一条**已在白名单里**的系统命令（argv 形式，不经 shell）。 */
    EXEC(8),
    ;

    companion object {
        fun of(wire: Int): RootVerb? = entries.firstOrNull { it.wire == wire }
    }
}
