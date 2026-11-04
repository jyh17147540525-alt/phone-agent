package com.pocketagent.rootlogic

import java.io.File

/**
 * 常驻通道的**发现文件**：`<数据目录>/pocketagent-daemon.txt`，内容一行 `端口 token`。
 *
 * 为什么用文件而不是写死端口 / 环境变量：
 * - 写死端口迟早撞上别的服务，而"撞了之后连到别人的服务"是最坏的失败形态；
 *   让系统分配端口（`bind(0)`）再回写文件，换来的是不可能撞。
 * - 手机上的进程间没有可靠的环境变量通道；`app_process` 起的进程与
 *   `untrusted_app` 的 `/proc/<pid>/environ` 权限也不一定允许读。
 *
 * ★ 这个文件**不加密**：它只是"端口 + 本次启动的随机 token"，不是长期密钥；
 *   设备上能读到 `/data/local/tmp` 的只有 adb shell 与 root —— 而这两者本来
 *   就能直接执行任何命令。把 token 再包一层，收益是零，代价是"读不到就打不开"。
 */
object DaemonEndpoint {

    /** 与守护进程薄壳约定的路径（薄壳以 root 身份写，客户端以应用身份读）。 */
    const val DEFAULT_PATH = "/data/local/tmp/pocketagent-daemon.txt"

    data class Endpoint(val port: Int, val token: String)

    /** token 最短长度：握手行是 `PA1 <token>\n`，短到能被猜中的 token 等于没有。 */
    private const val MIN_TOKEN = 16

    fun parse(text: String): Endpoint? {
        val parts = text.trim().split(' ', '\t', '\n').filter { it.isNotBlank() }
        if (parts.size != 2) return null

        val port = parts[0].toIntOrNull() ?: return null
        // 0 与 65535 之间的特权端口区间也不该出现：守护进程绑的是临时端口。
        if (port !in 1..65535) return null

        val token = parts[1]
        if (token.length < MIN_TOKEN) return null
        if (!token.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null

        return Endpoint(port, token)
    }

    fun read(path: String = DEFAULT_PATH): Endpoint? = try {
        val file = File(path)
        if (!file.isFile) null else parse(file.readText())
    } catch (e: Exception) {
        // 读不到就是"通道没装好"，属于正常状态（绝大多数用户没有 root）。
        null
    }

    /**
     * 打开通道；发现文件不存在或内容不对就返回 null（调用方会退回 `su`）。
     *
     * 这里**不探活**：探活要发一次 `PROBE`，会把"启动时多打一次包"变成
     * "每次启动多一条 root 命令"。真正的探活交给第一次业务调用 ——
     * 连不上就退回 su，代价只有一次失败的 connect。
     */
    fun open(path: String = DEFAULT_PATH): PrivilegeSocketPort? = read(path)?.let { endpoint ->
        PrivilegeSocketPort(port = endpoint.port, token = endpoint.token)
    }
}
