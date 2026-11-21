package com.pocketagent.provider.api

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException

/**
 * 把"连不上"翻译成下一步动作。
 *
 * 真机上用户看到的原文是 `network unreachable` 或 `CLEARTEXT communication ... not
 * permitted` —— 两个都不告诉人该改什么。局域网里跑模型（vLLM / Ollama / LM Studio）
 * 又是最容易踩的坑，所以单独给一份到点上的提示。
 */
object EndpointHints {

    fun forFailure(e: Throwable): String {
        val text = (e.message ?: "").lowercase()
        return when {
            e is UnknownServiceException || "cleartext" in text ->
                "系统拦了明文 HTTP。这个版本已放行；若仍出现，检查地址是否写成了 https 却用 http 端口。"
            e is ConnectException || "failed to connect" in text || "econnrefused" in text ->
                lanHint("连接被拒绝（对方端口没开）")
            e is SocketTimeoutException || "timed out" in text || "timeout" in text ->
                lanHint("连接超时（对方没回话）")
            e is UnknownHostException || "no address associated" in text ->
                lanHint("解析不到这个主机名")
            e is IOException ->
                lanHint("网络断了")
            else -> "请求没发出去：${e.message ?: e::class.simpleName}"
        }
    }

    private fun lanHint(head: String): String = buildString {
        append(head)
        append("。按这几条排查：\n")
        append("1. 电脑上的服务要监听 0.0.0.0，不能只听 127.0.0.1 —— ")
        append("vLLM 默认只听本机，启动时加 `--host 0.0.0.0`。\n")
        append("2. 手机和电脑要在同一网段（连同一个热点也算），")
        append("在电脑上 `ipconfig` 看 IPv4 地址，别用 127.0.0.1。\n")
        append("3. 电脑防火墙放行那个端口（Windows 第一次会弹窗，选允许专用网络）。\n")
        append("4. 地址填到端口即可，例如 `http://192.168.1.10:8091`，路径由应用补。")
    }
}
