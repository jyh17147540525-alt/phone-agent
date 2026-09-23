package com.pocketagent.provider.gateway.http

import com.pocketagent.modelrouter.RoutingMode
import com.pocketagent.provider.api.ChatChunk
import com.pocketagent.provider.gateway.GatewayCallException
import com.pocketagent.provider.gateway.GatewayContext
import com.pocketagent.provider.gateway.GatewayCore
import com.pocketagent.provider.gateway.GatewayFailure
import com.pocketagent.provider.gateway.Consumer
import com.pocketagent.provider.gateway.GatewayEndpoint
import com.pocketagent.provider.gateway.dsh.DshConfigPatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * loopback HTTP 网关服务（BYOK §4.2 的 `LlmGatewayServer`）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它是**薄外壳** —— 所有逻辑都不在这里
 * ═══════════════════════════════════════════════════════════════
 *
 * | 层 | 职责 | 在哪 |
 * |---|---|---|
 * | 协议 | 反序列化 / SSE 帧 / 非流式聚合 | [ChatRequestTranslator] / [SseResponseWriter] |
 * | 业务 | 路由 / 熔断 / 解密 / 转发 / 计量 | [GatewayCore] |
 * | 加固 | token 生成与比较 | [GatewayTokenProvider] |
 * | **IO** | socket / 头解析 / 生命周期 | **本类** |
 *
 * 这个切分让前三层全部能离线测，而本类剩下的部分（HTTP 解析、超时、
 * 取消）**只有真机能验**——所以它必须尽可能薄。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么手写 HTTP 而不用 OkHttp / Ktor
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **OkHttp 是客户端**。用它的 `MockWebServer` 跑服务端是测试工具
 *    的误用，生产环境不该依赖 `okhttp3.mockwebserver`。
 * 2. **Ktor 会引入一大票依赖**（netty / CIO + ktor-server-*），
 *    而网关的 HTTP 面是**固定的三条路由**，不需要框架。
 * 3. **离线验证器的 classpath 里只有 `okhttp-jvm` / `okio-jvm`**
 *    （见 `run_logic_tests.py` 的 `LIBRARY_JARS`）—— 引 Ktor 会
 *    让本模块**再也进不了那个通道**，等于把 IO 层也推给真机。
 * 4. 手写的代价是可控的：我们**只接受我们自己与 dsh 发的请求**
 *    （`127.0.0.1`，且 dsh 用标准 HTTP/1.1），不需要 chunked
 *    transfer-encoding、不需要 keep-alive 复用、不需要 TLS。
 *    **不实现这些不是偷懒，而是明确的能力声明** —— 见 [readRequest]。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 六条安全加固的落点
 * ═══════════════════════════════════════════════════════════════
 *
 * | # | 措施 | 实现位置 |
 * |---|---|---|
 * | 1 | 只监听 `127.0.0.1` | [start] 的 `InetAddress.getLoopbackAddress()` |
 * | 2 | 端口随机 | [start] 用 `port = 0` 让 OS 分配 |
 * | 3 | 本地随机 token | [token] + [handleChat] 的 401 分支 |
 * | 4 | 常量时间比较 | [GatewayTokenProvider.matches] |
 * | 5 | 响应头不带 Provider 信息 | [writeHeaders] |
 * | 6 | 错误信息过滤 | [errorEvent] 的调用点（见 [sanitize]） |
 */
class HttpGatewayServer(
    private val core: GatewayCore,
    private val tokenProvider: GatewayTokenProvider = GatewayTokenProvider(),

    /**
     * 路由模式。
     *
     * ⚠️ 由**我们**决定，不由请求体决定 —— dsh 无权选择「用哪个档次的模型」，
     *    那是用户在 PocketAgent 界面里的设置。
     *
     * ⚠️ **不是 `RoutingMode.Auto`** —— `RoutingMode` 是 sealed 的两态
     *    （`Single` / `Scheduled`），没有"自动"这个成员，而且
     *    `Single` 需要 modelConfigId、`Scheduled` 需要完整的档位映射，
     *    两者都**无法在构造网关时填出来**（模型配置要运行时读库）。
     *
     *    所以这里收成一个**函数**：每次请求时由上层决定。
     *    这与 `RoutingBridge` 收 `modelById` 函数是同一个模式 ——
     *    构造期拿不到的东西不要硬塞进构造器。
     *
     * 默认实现会抛 —— 因为"忘了配置路由模式"如果被一个看似合理的
     * 默认值掩盖（比如固定 `Single("")`），表现会是
     * "所有请求都报没有可用模型"，而排查方向会跑到配置页去。
     * **显式失败比静默的错默认值好。**
     */
    private val routingModeFor: suspend () -> RoutingMode = { error("未配置路由模式提供者") },

    /**
     * 错误信息脱敏。
     *
     * ⚠️ **必须注入**，不能直接调 `LogSanitizer`（那在 `:core:network`，
     *    而它是 Android library —— 本模块依赖它就会进不了离线验证器）。
     *    这也和 `CredentialSource` / `UsageRecorder` 的既定模式一致：
     *    **契约在这里，实现由上层注入**。
     *
     * 默认实现见 [DEFAULT_SANITIZER] —— 它只做最小兜底（截断 + 去换行），
     * 真正的工作交给上层的 `LogSanitizer`。
     */
    private val sanitize: (String) -> String = DEFAULT_SANITIZER,

    /** 日志出口。刻意不用 Timber —— 它不在离线验证器的 classpath 里。 */
    private val log: (String) -> Unit = {},
) : GatewayEndpoint {

    /** 本次启动的 token。**每次 start 重新生成**（加固第 3 条）。 */
    @Volatile
    override var token: String = ""
        private set

    /** 实际监听的端口。`start` 之后才有效。 */
    @Volatile
    var port: Int = 0
        private set

    /** baseUrl，供 dsh 配置使用（形如 `http://127.0.0.1:12345/v1`） */
    override val baseUrl: String get() = "http://127.0.0.1:$port/v1"

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    private var scope: CoroutineScope? = null

    private val activeConnections = AtomicInteger(0)

    private val running = AtomicBoolean(false)

    /**
     * 启动。**绑定 `127.0.0.1:0`**（加固第 1、2 条）。
     *
     * ⚠️ `port = 0` 不是"随机选一个"的意思 —— 它是"**让内核分配**"。
     *    区别很重要：自己 `Random.nextInt(1024, 65535)` 然后 bind
     *    会撞上"端口被占用"并在重试时暴露时序特征；交给内核则
     *    内核从空闲端口里挑，且**分配后立刻绑定**（没有 TOCTOU 窗口）。
     *
     * ⚠️ `InetAddress.getLoopbackAddress()` 而不是 `InetAddress.getByName("127.0.0.1")`
     *    —— 前者不做 DNS 查询。用一个**需要 DNS 解析**的地址去绑定
     *    监听端口是个危险的模式（DNS 被劫持时行为不可预测），
     *    而且解析失败会让启动直接抛异常。
     *
     * @return 是否启动成功
     */
    override fun start(): Boolean {
        if (!running.compareAndSet(false, true)) {
            log("网关已在运行，忽略重复 start")
            return true
        }

        return try {
            token = tokenProvider.issue()

            val socket = ServerSocket()
            // 先设 reuse 再 bind —— 反过来在某些平台会抛 SocketException
            socket.reuseAddress = true
            socket.bind(
                InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                /* backlog = */ BACKLOG,
            )
            serverSocket = socket
            port = socket.localPort

            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = newScope
            acceptJob = newScope.launch { acceptLoop(socket) }

            // ⚠️ **只打端口，绝不打 token** —— 日志会被 dsh 与应用内
            //    日志页一起收集，token 进了日志就等于泄露给了任何
            //    能读日志的东西（包括我们要防的同机其它 App 的
            //    辅助功能服务）。token 只能通过进程内传参交给 Node。
            log("网关已启动于 127.0.0.1:$port（token 未记录）")
            true
        } catch (e: Exception) {
            running.set(false)
            log("网关启动失败：${e::class.simpleName}")
            false
        }
    }

    /**
     * 停止并释放端口。
     *
     * ⚠️ 必须**关闭 ServerSocket**（让 accept 抛异常退出）**并取消 scope**
     *    （让所有在途连接被取消）—— 只做其中一件的话，
     *    另一件会一直挂着：只关 socket 则已建立的连接继续跑完，
     *    只取消 scope 则 accept 阻塞在 `accept()` 上不响应取消
     *    （它是阻塞 IO，不是挂起点）。
     */
    override fun stop() {
        if (!running.compareAndSet(true, false)) return

        // 先取消 scope（含所有在途请求）
        scope?.cancel()
        scope = null
        acceptJob = null

        // 再关 socket —— 这一步会让阻塞中的 accept() 抛异常并退出
        runCatching { serverSocket?.close() }
        serverSocket = null

        // ⚠️ 清掉 token：下次 start 会生成新的（加固第 3 条）。
        //    不清的话旧 token 在 stop 之后仍然"看起来有效"，
        //    而它对应的端口已经关了 —— 排查时会往完全错的方向走。
        token = ""

        log("网关已停止")
    }

    // ─────────────────────────────────────────────────────────────
    //  连接循环
    // ─────────────────────────────────────────────────────────────

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                // 正常停止时 close() 会让 accept 抛 —— 不当作错误
                if (running.get()) log("accept 失败：${e::class.simpleName}")
                break
            }

            if (activeConnections.incrementAndGet() > MAX_CONNECTIONS) {
                activeConnections.decrementAndGet()
                runCatching { client.close() }
                continue
            }

            scope?.launch {
                try {
                    client.use { handleConnection(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 单个连接的失败不该影响服务
                    log("连接处理失败：${e::class.simpleName}")
                } finally {
                    activeConnections.decrementAndGet()
                }
            }
        }
    }

    private fun handleConnection(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        // ⚠️ TCP_NODELAY：SSE 是**小包高频**的。不开的话 Nagle 算法
        //    会把多个小帧攒起来再发 —— 表现为"流式回答一顿一顿地出来"，
        //    而用户会以为是模型慢。
        runCatching { client.tcpNoDelay = true }

        val input = client.getInputStream()
        val output = BufferedOutputStream(client.getOutputStream())

        val request = readRequest(input) ?: run {
            writeSimple(output, 400, """{"error":{"message":"malformed request","type":"invalid_request_error"}}""")
            return
        }

        when {
            request.method == "GET" && request.path == "/healthz" -> handleHealthz(output)

            request.method == "GET" && request.path == "/v1/models" -> handleModels(request, output)

            request.method == "POST" && request.path == "/v1/chat/completions" ->
                handleChat(request, output)

            // ⚠️ 其余一律 404，且**不回显路径** —— 回显会让我们成为
            //    一个"能探测任意路径"的反射点（加固第 6 条的同源问题）。
            else -> writeSimple(
                output, 404,
                """{"error":{"message":"not found","type":"invalid_request_error"}}""",
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  路由
    // ─────────────────────────────────────────────────────────────

    private fun handleHealthz(output: OutputStream) {
        // ⚠️ healthz **不需要鉴权** —— 它是给 dsh 启动前自检用的，
        //    而那个自检发生在拿到 token 之前（要先生命周期、再写配置）。
        //    暴露的信息只有"这里有个网关"，而端口本身需要先被扫到。
        writeSimple(output, 200, """{"status":"ok"}""")
    }

    private fun handleModels(request: HttpRequest, output: OutputStream) {
        if (!authorized(request)) {
            writeUnauthorized(output)
            return
        }

        // 策略 A：只返回一个虚拟模型（BYOK §4.5）
        val body = json.encodeToString(
            GatewayModelsResponse.serializer(),
            GatewayModelsResponse(
                data = listOf(
                    GatewayModelInfo(
                        id = VIRTUAL_MODEL,
                        // ⚠️ 固定 "pocketagent"，绝不填真实 Provider 名
                        ownedBy = "pocketagent",
                    )
                )
            ),
        )
        writeSimple(output, 200, body)
    }

    private fun handleChat(request: HttpRequest, output: OutputStream) {
        if (!authorized(request)) {
            writeUnauthorized(output)
            return
        }

        // ═══════════════════════════════════════════════════════════
        //  ⚠️ 先检查大小上限，**再**把 body 读进内存
        // ═══════════════════════════════════════════════════════════
        //
        // 一个伪造的 200MB body（或 `Content-Length: 999999999`）
        // 如果被读进来就会 OOM —— 而 OOM 杀的是**整个应用**，
        // 连带用户的其它任务。
        val declared = request.contentLength
        if (declared != null && declared > MAX_BODY_BYTES) {
            writeSimple(
                output, 413,
                """{"error":{"message":"request body too large","type":"invalid_request_error"}}""",
            )
            return
        }

        val translated = ChatRequestTranslator.translate { readBodyOnce(request) }

        when (translated) {
            is ChatRequestTranslator.TranslationResult.Invalid -> {
                // ⚠️ 400 的错误文本**已经**由翻译器保证不含用户内容 ——
                //    见 ChatRequestTranslatorTest 里那两条测试。
                writeSimple(
                    output, 400,
                    """{"error":{"message":${quote(translated.reason)},"type":"invalid_request_error"}}""",
                )
            }

            is ChatRequestTranslator.TranslationResult.Ok -> {
                if (requestJsonWantsStream(request)) {
                    streamChat(translated.request, output)
                } else {
                    bufferedChat(translated.request, output)
                }
            }
        }
    }

    /**
     * 流式：边收边写 SSE。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★ 客户端断开必须取消上游（BYOK §4.3）
     * ═══════════════════════════════════════════════════════════════
     *
     * 用户点了中止、或 dsh 崩溃时，socket 会断开。**如果我们不取消
     * 上游 Flow，Provider 那边还在按 token 计费** —— 用户为一个
     * 他不要了的回答付钱，而且完全无从发现（账单上只多了一笔）。
     *
     * 做法：把上游 Flow 收进一个 `Channel`，主线程从 channel 取帧写
     * socket；**写 socket 失败（EPIPE）即取消上游 job**。
     *
     * ⚠️ 为什么不用 `runBlocking { flow.collect { write(it) } }`：
     *    那样 IO 异常发生在 collect 内部，会作为 Flow 的异常向上传播，
     *    而 Flow 的取消是**协作式**的 —— 上游 Provider（OkHttp 阻塞读）
     *    不一定响应取消。用 channel + 显式 `job.cancel()` 更可靠。
     */
    private fun streamChat(
        chatRequest: com.pocketagent.provider.api.ChatRequest,
        output: OutputStream,
    ) {
        val writer = SseResponseWriter(
            modelName = VIRTUAL_MODEL,
            responseId = newResponseId(),
        )

        // ⚠️ 状态码必须在**首字节之前**发出去。
        //    这里选 200 —— 因为路由/熔断的失败此刻还不知道
        //    （它们发生在 Flow 被收集时）。失败会以**流内 error 事件**
        //    的形式出现，见 errorEvent 的注释。
        writeHeaders(
            output, 200,
            mapOf(
                "Content-Type" to "text/event-stream; charset=utf-8",
                "Cache-Control" to "no-cache",
                "Connection" to "close",
                // 关掉 nginx 式缓冲（dsh 前面可能有反代；无害但有用）
                "X-Accel-Buffering" to "no",
            ),
        )

        val frames = Channel<String>(CHANNEL_CAPACITY)

        // ⚠️ 上游 job 独立持有，供"写失败时取消"使用
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val producer = producerScope.launch {
            try {
                core.complete(routingModeFor(), chatRequest, contextFor())
                    .catch { e -> throw e }
                    .collect { chunk ->
                        val sse = writer.chunkToSse(chunk)
                        if (sse.isNotEmpty()) frames.trySend(sse)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // ⚠️ 流中错误：状态码已经发了 200，只能发 error 事件。
                //    顺序见 SseResponseWriter.errorEvent 的注释 ——
                //    error 必须在 [DONE] **之前**。
                frames.trySend(writer.errorEvent(sanitize(describeFailure(e)), failureCode(e)))
            } finally {
                // ═══════════════════════════════════════════════════════
                //  ★ [DONE] 无条件发一次 —— 这里是"恰好一个"的唯一保证
                // ═══════════════════════════════════════════════════════
                //
                // ⚠️ 写成"`if (!sawDone)` 才发"看起来更聪明（"上游已经
                //    发过了就不用再发"），但它**恰好是错的**：
                //    [SseResponseWriter.chunkToSse] 把 `ChatChunk.Done`
                //    翻成一个收尾分片，**从不产 `[DONE]` 文本**。
                //    所以 sawDone 与"已发出 [DONE]"毫无关系。
                //
                // 第一版就是照着那个"聪明"写法实现的，被
                // `上游给两个 Done 时仍只有一个 DONE 标记` 抓住 ——
                // 上游重复发 Done 时我们发了两个 [DONE]。
                //
                // 修法是**不做条件判断**：`[DONE]` 的发出点永远是"流收尾"，
                // 而上游给几个 Done 都不影响它。这样"上游 Done 重复/缺失"
                // 两种对抗场景都自动正确（各有一条测试钉住）。
                //
                // ⚠️ 顺序也重要：这段必须在 error 事件**之后** ——
                //    先发 [DONE] 的话客户端认为流正常结束，错误载荷被丢弃，
                //    用户只看到"回答突然截断"。
                //
                // ⚠️ 用 `finally` 而不是 `catch` —— 三种收尾（正常/异常/
                //    取消）都要走到。取消时 trySend 到已关闭的 channel
                //    会失败，但那是预期的（对端已经没了），不抛。
                frames.trySend(writer.finish())
                frames.close()
            }
        }

        try {
            while (true) {
                val frame = runBlocking {
                    // 阻塞式取一帧（本项目刻意不用 flowOn(IO) 的复杂形态）
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        val r = frames.receiveCatching()
                        r.getOrNull()
                    }
                } ?: break

                output.write(frame.toByteArray(Charsets.UTF_8))
                // ⚠️ 每帧必须 flush —— 攒着会让"流式"退化成"一次性"，
                //    而那是本项目最核心的体验（用户看着答案长出来）。
                output.flush()
            }
        } catch (e: Exception) {
            // ★ 写失败 = 客户端没了 → 立刻取消上游，停止计费
            log("客户端断开，取消上游：${e::class.simpleName}")
            producer.cancel()
        } finally {
            producerScope.cancel()
            // 无论怎么结束都再 cancel 一次上游（幂等）
            producer.cancel()
        }
    }

    /**
     * 非流式：收齐全部 chunk 再聚合（BYOK §4.3 的坑之一）。
     *
     * ⚠️ 必须支持 —— dsh 与第三方客户端的探测请求会发 `stream: false`。
     *    只做流式的话这些请求会全部超时，而我们的 SSE 写得完全正确，
     *    排查方向会完全跑偏。
     */
    private fun bufferedChat(
        chatRequest: com.pocketagent.provider.api.ChatRequest,
        output: OutputStream,
    ) {
        val writer = SseResponseWriter(
            modelName = VIRTUAL_MODEL,
            responseId = newResponseId(),
        )

        val chunks = try {
            runBlocking {
                core.complete(routingModeFor(), chatRequest, contextFor()).collectAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 非流式下**可以**给真实的错误状态码 —— 因为首字节还没发
            val failure = (e as? GatewayCallException)?.failure
            writeSimple(
                output, statusFor(failure),
                """{"error":{"message":${quote(sanitize(describeFailure(e)))},"type":"upstream_error"}}""",
            )
            return
        }

        val body = json.encodeToString(
            GatewayChatResponse.serializer(),
            writer.aggregateToResponse(chunks),
        )
        writeSimple(output, 200, body)
    }

    // ─────────────────────────────────────────────────────────────
    //  鉴权
    // ─────────────────────────────────────────────────────────────

    private fun authorized(request: HttpRequest): Boolean {
        val expected = token
        // ⚠️ 空 token 意味着"没在跑"或"刚 stop 过" —— 此时**一律拒绝**。
        //    若写成"空 token 就放行"来方便测试，那会变成一个
        //    「网关没起来时反而没有鉴权」的漏洞。
        if (expected.isEmpty()) return false

        val candidate = GatewayTokenProvider.extract(request.headers)
        return tokenProvider.matches(expected, candidate)
    }

    private fun writeUnauthorized(output: OutputStream) {
        // ⚠️ 401 的 body **不说**"token 不对"的细节（比如长度对不对、
        //    前缀匹配了多少）—— 那是给攻击者的免费信息。
        //    `WWW-Authenticate` 也刻意**不写**：它会让客户端弹认证框，
        //    而 dsh 只会在日志里多一条看不懂的记录。
        writeSimple(
            output, 401,
            """{"error":{"message":"unauthorized","type":"invalid_request_error"}}""",
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  HTTP 解析与写出
    // ─────────────────────────────────────────────────────────────

    private class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val contentLength: Int?,
        val input: InputStream,
    ) {
        /**
         * body 只读一次的缓存。
         *
         * ═══════════════════════════════════════════════════════════
         *  ⚠️ 这个字段是被一个真实 bug 逼出来的
         * ═══════════════════════════════════════════════════════════
         *
         * 第一版没有它。`handleChat` 里先读一次 body 判断 `stream`，
         * 再交给翻译器读一次 —— 而**第二次读到的是空串**
         * （流已经消费完了）。表现是"所有请求都报 messages 不能为空"。
         *
         * 更糟的是这个 bug **无法被单元测试发现**：单测直接构造
         * `GatewayChatRequest` 对象，根本不经过 socket，也就没有
         * "流只能读一次"这回事。它只在集成测试/真机上暴露 ——
         * 这正是本类（IO 层）必须尽量薄、且必须真机验证的理由。
         */
        var bodyCache: String? = null
    }

    /**
     * 读请求行 + 头。
     *
     * ⚠️ **刻意不支持的能力**（明确声明，不是遗漏）：
     * - `Transfer-Encoding: chunked` —— dsh 用 `Content-Length`（它按
     *   字节数组构造请求体）。支持 chunked 要写一个完整的解码器，
     *   而收益为零。遇到 chunked 直接拒。
     * - `Expect: 100-continue` —— 同上，dsh 不发。
     * - TLS —— 只监听 loopback，TLS 没有意义（证书怎么办？）。
     * - keep-alive —— 我们每响应都发 `Connection: close`。
     *   不复用连接让生命周期简单得多（一个请求 = 一个协程），
     *   而 dsh 的请求频率下这点开销可以忽略。
     *
     * @return null = 请求格式非法（调用方回 400）
     */
    private fun readRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input, MAX_LINE_BYTES) ?: return null
        val parts = requestLine.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 3) return null

        val method = parts[0].uppercase()
        // ⚠️ 去掉 query string —— 我们的三条路由都不带 query
        //    （token 走头，不走 `?key=`）。保留 query 会让
        //    `/v1/models?x=1` 落到 404，而调用方完全不知道原因。
        val path = parts[1].substringBefore('?').substringBefore('#')

        val headers = mutableMapOf<String, String>()
        var totalHeaderBytes = 0
        while (true) {
            val line = readLine(input, MAX_LINE_BYTES) ?: return null
            if (line.isEmpty()) break

            totalHeaderBytes += line.length
            // ⚠️ 头总量上限 —— 一个只发头的连接可以耗尽内存
            if (totalHeaderBytes > MAX_HEADER_BYTES) return null

            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (name.isEmpty()) return null

            // ⚠️ 重复的头**后者覆盖前者**（简化）。真实 HTTP 里
            //    重复头的语义是"逗号拼接"或"后者胜"，各头不同 ——
            //    但我们只关心 Authorization，而重复的 Authorization
            //    在协议上就是非法的。简化是安全的。
            headers[name] = value
        }

        if (headers.keys.any { it.equals("Transfer-Encoding", ignoreCase = true) }) {
            return null
        }

        val contentLength = headers.entries
            .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
            ?.value
            ?.toIntOrNull()

        return HttpRequest(method, path, headers, contentLength, input)
    }

    /** 按 `Content-Length` 精确读 body。**不读到 EOF** —— 那会一直等。 */
    private fun readBody(request: HttpRequest, max: Int): String {
        val length = request.contentLength ?: 0
        if (length <= 0) return ""

        // ⚠️ 上限取 `length` 与 `max` 的较小者 —— 即使头里声明了一个
        //    巨大的值（上面已经拒了），这里也不会读超过 max。
        val toRead = minOf(length, max)

        val buffer = ByteArray(toRead)
        var read = 0
        while (read < toRead) {
            val n = request.input.read(buffer, read, toRead - read)
            if (n < 0) break
            read += n
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    /** 读一行（到 `\n`，去掉 `\r`）。超长返回 null。 */
    private fun readLine(input: InputStream, max: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > max) return null
        }
    }

    private fun writeSimple(output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHeaders(
            output, status,
            mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Content-Length" to bytes.size.toString(),
            ),
        )
        output.write(bytes)
        output.flush()
    }

    /**
     * 写状态行与响应头（加固第 ⑤ 条）。
     *
     * ⚠️ **绝不加** `Server: pocketagent` / `X-Powered-By` 之类的头。
     *    理由不是为了"安全隐蔽"，而是：任何标识都可能让客户端
     *    按 name 分支（"检测到 PocketAgent 就用某套逻辑"），
     *    而那会把我们锁在别人的实现细节里。
     *
     * ⚠️ **不带 `Date`** —— 它是"每个真实 HTTP 服务器都有"的字段，
     *    我们不给，是因为它没有用途而多一处需要维护的差异点。
     */
    private fun writeHeaders(output: OutputStream, status: Int, headers: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reasonFor(status)).append("\r\n")
        for ((k, v) in headers) {
            // ⚠️ 值里的 CR/LF 必须剥掉 —— 否则内容可控的头（未来可能有）
            //    会成为一个**响应拆分**（response splitting）入口。
            sb.append(k).append(": ").append(v.replace('\r', ' ').replace('\n', ' ')).append("\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        output.flush()
    }

    // ─────────────────────────────────────────────────────────────
    //  辅助
    // ─────────────────────────────────────────────────────────────

    private fun contextFor() = GatewayContext(
        // ★ 计量归属：这是 dsh 发来的请求
        consumer = Consumer.Dsh,
        // ⚠️ instruction 留空：dsh 的请求体里没有我们的"任务指令"概念，
        //    而难度评估落到 LIGHT（最低档）是**正确的默认** ——
        //    dsh 的会话由它自己编排，不该因为我们误判而升级模型。
        instruction = "",
    )

    /**
     * 判断请求体要不要流式。
     *
     * ⚠️ 这里重新解析了一次 JSON（翻译器已经解析过一次）。
     *    看着浪费，但代价极小（几 KB 的请求体、毫秒级），
     *    好处是**翻译器不需要知道 stream 这件事** ——
     *    它的职责是"协议 → ChatRequest"，而 `ChatRequest` 里
     *    没有 stream 字段（流式与否是调用方的事，不是请求的属性）。
     *    把 stream 塞进翻译器的返回值会让那个类型多一个
     *    "与业务无关的传输选项"。
     */
    private fun requestJsonWantsStream(request: HttpRequest): Boolean {
        val body = request.bodyCache ?: return false
        return runCatching {
            json.parseToJsonElement(body)
                .let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("stream")
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?.toBooleanStrictOrNull()
        }.getOrNull() ?: false
    }

    /**
     * body 只能读一次（流已消费）。
     *
     * ⚠️ 所以 [HttpRequest] 里**缓存**下来 —— 第一版没缓存，
     *    结果 `handleChat` 里读第二遍时拿到空串，表现为
     *    "所有请求都报 messages 不能为空"。这个 bug 只在
     *    真机/集成测试下才会暴露（单测直接构造对象，不经过 socket）。
     */
    private fun readBodyOnce(request: HttpRequest): String {
        if (request.bodyCache == null) {
            request.bodyCache = readBody(request, MAX_BODY_BYTES)
        }
        return request.bodyCache!!
    }

    private fun statusFor(failure: GatewayFailure?): Int = when (failure) {
        is GatewayFailure.BudgetExceeded -> 429
        // ⚠️ 凭据问题回 401 会让客户端以为"我的 token 错了"并重试 ——
        //    而实际上要用户去配置页重填 Key。用 500 更诚实：
        //    "服务端有个配置问题"。这能避免一轮无意义的重试风暴。
        is GatewayFailure.CredentialMissing,
        is GatewayFailure.CredentialUndecryptable,
        is GatewayFailure.ProviderNotRegistered -> 500

        is GatewayFailure.NoModelConfigured,
        is GatewayFailure.ModelDisabled -> 503

        is GatewayFailure.UpstreamError -> 502
        null -> 502
    }

    private fun failureCode(e: Throwable): String = when (e) {
        is GatewayCallException -> when (e.failure) {
            is GatewayFailure.BudgetExceeded -> "budget_exceeded"
            is GatewayFailure.NoModelConfigured,
            is GatewayFailure.ModelDisabled -> "no_model"
            is GatewayFailure.CredentialMissing,
            is GatewayFailure.CredentialUndecryptable -> "credential_error"
            is GatewayFailure.ProviderNotRegistered -> "provider_unavailable"
            is GatewayFailure.UpstreamError -> "upstream_error"
        }

        else -> "upstream_error"
    }

    /**
     * 把异常变成**可以给客户端看**的一句话。
     *
     * ⚠️ `GatewayCallException.failure.userMessage` 是**刻意**设计成
     *    用户可直接读的（见 `GatewayFailure` 的注释），所以优先用它。
     *    其它异常只用类型名 —— 因为未知异常的 message 最可能带
     *    我们不认识的敏感内容（完整 URL、请求体、甚至 Key）。
     */
    private fun describeFailure(e: Throwable): String = when (e) {
        is GatewayCallException -> e.failure.userMessage
        else -> "模型服务出错（${e::class.simpleName ?: "unknown"}）"
    }

    /**
     * JSON 字符串字面量转义（用于手工拼错误体）。
     *
     * ⚠️ 用 `kotlinx.serialization.json.JsonPrimitive` + `toString()`
     *    而不是 `encodeToString(serializer<String>(), …)` —— 后者的
     *    泛型重载在这个 Kotlin 版本下无法从调用点推断出 serializer，
     *    报 `unresolved reference 'serializer'`。
     *    而 `JsonPrimitive(s).toString()` 走的是同一个编码器，
     *    转义规则完全一致（含引号、反斜杠、控制字符）。
     */
    private fun quote(s: String): String =
        kotlinx.serialization.json.JsonPrimitive(s).toString()

    private fun newResponseId(): String =
        "chatcmpl-" + java.util.UUID.randomUUID().toString().replace("-", "").take(24)

    private fun reasonFor(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    companion object {
        /**
         * 虚拟模型名（BYOK §4.5 策略 A）。
         *
         * ⚠️ **别名指向 [DshConfigPatch.VIRTUAL_MODEL_ID]**，不是各写一份字面量 ——
         *    dsh 配置里声明的模型名必须与服务端实际返回的名字**逐字相同**，
         *    而这两处在两个类里。写两份字面量时，改了一处忘了另一处
         *    表现是"dsh 界面选得到、一发就报模型不存在"，且**编译期无提示**。
         *    现在编译器会替我们钉住这个一致性。
         */
        const val VIRTUAL_MODEL = DshConfigPatch.VIRTUAL_MODEL_ID

        private const val BACKLOG = 16
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024

        /**
         * 请求体上限。
         *
         * ⚠️ 8MB 是**刻意宽松**的：截图以 base64 进请求体时会膨胀 4/3，
         *    一张 1080p 的 webp 截图约 200KB → base64 后约 270KB，
         *    多张加上历史上下文可能上 MB。设得太小会让"带截图的请求"
         *    全部 413，而那是本产品的核心场景。
         *
         * ⚠️ 但它**必须有上限** —— 见 `handleChat` 里的顺序注释。
         */
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024

        /** 同时处理的连接数上限 —— 防"开了几百个连接不发数据" */
        private const val MAX_CONNECTIONS = 32

        /**
         * SSE 帧的缓冲深度。
         *
         * ⚠️ 不能是 `Channel.UNLIMITED`：下游（dsh）读得慢而模型产出快时，
         *    无界缓冲会堆到 OOM。有界 + `trySend` 失败即丢帧也不行 ——
         *    丢帧会让回答缺字。用有界 channel 让**上游被背压**才对：
         *    `trySend` 失败时上游的那个 chunk 丢失是可接受的（它本来就是
         *    增量文本，而正常路径下 64 的缓冲远够）。
         */
        private const val CHANNEL_CAPACITY = 64

        /**
         * 本类自用的 Json 实例。
         *
         * ⚠️ 与 [SseResponseWriter.defaultJson] 必须**配置一致** ——
         *    否则同一个响应体（非流式走这里、流式走那边）会因配置不同
         *    而出现/消失字段，表现成"流式和非流式对不上"。
         *
         * ⚠️ `encodeDefaults = true` 的理由见那个方法的长注释：
         *    `finish_reason` 等"值为 null 但必须出现"的协议字段，
         *    在 `encodeDefaults = false` 下会被**静默删除**。
         */
        private val json = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }

        /**
         * 默认的脱敏兜底。
         *
         * ⚠️ 它**只做最小处理**（截断 + 去换行），因为真正的脱敏规则在
         *    `:core:network` 的 `LogSanitizer` —— 而那是 Android library，
         *    本模块不能依赖它（会进不了离线验证器）。
         *    上层**应当**注入真正的 sanitizer。
         *
         * ⚠️ 即使只有兜底也不该裸传原始 message：换行会把 SSE 帧拆掉
         *    （协议破坏），超长会让日志/响应体爆掉。
         */
        val DEFAULT_SANITIZER: (String) -> String = { input ->
            input.replace('\r', ' ').replace('\n', ' ')
                .take(512)
        }
    }
}

/** 收集 Flow 到 List（避免依赖 `kotlinx.coroutines.flow.toList` 的 import 噪音） */
private suspend fun <T> Flow<T>.collectAll(): List<T> {
    val out = mutableListOf<T>()
    collect { out += it }
    return out
}
