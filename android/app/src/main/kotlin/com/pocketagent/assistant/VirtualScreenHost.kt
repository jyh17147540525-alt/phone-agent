package com.pocketagent.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import com.pocketagent.hosting.HostingCommands
import com.pocketagent.hosting.VirtualScreenCommands
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * **虚拟屏宿主** —— 角色干活的那块屏。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它解决的是用户的哪一句话
 * ═══════════════════════════════════════════════════════════════
 *
 * "角色操这些软件时……都必须在虚拟屏里，不能对用户使用手机造成干扰。
 *  要让用户无感，不占用用户的屏幕。"
 * 以及后来补的那句更硬的："还有虚拟屏执行时，不能影响打扰到用户的主屏幕。"
 *
 * 之前那条 freeform 小窗的路**做不到这一条** —— 窗口真的画在用户屏幕上。
 * 真机证据见 `:hosting` 的 `VirtualScreenCommands` 头注释。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三条设计上的硬约束
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **建屏不需要 root，拉 App 需要。**
 *    所以这里把"建屏 / 读画面"（本进程做）与"拉起 / 注入"（root 做）分开。
 *
 * 2. **必须先清任务，再释放屏。**
 *    虚拟屏被 release 时，上面的 Task 会被系统**挪回主屏**。
 *    真机目击：宿主一退出，QQ 就整个铺到用户屏幕上全屏显示。
 *    ⇒ [stop] 里 `am force-stop` 一定在 `release()` **之前**。
 *
 * 3. **宿主必须活得比一次对话久。**
 *    它挂在应用进程上（由 `AgentForegroundService` 保活），
 *    而不是挂在某个 Activity 上 —— Activity 会被系统回收，
 *    而它一被回收，用户屏幕上就会多出一个应用的窗口。
 */
class VirtualScreenHost(private val context: Context) {

    init {
        // 登记为进程内当前宿主（见 companion 里 current 的注释）
        current = this
    }

    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frameThread: android.os.HandlerThread? = null

    /**
     * 最近一帧（已转成 Bitmap）。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★★ 必须有这个监听器，否则整块屏会**停止渲染**
     * ═══════════════════════════════════════════════════════════
     *
     * 真机目击（2026-10-27）：第一次 `shot` 报"没有画面"。
     * 原因不是应用没起来，而是 **ImageReader 的缓冲区被占满**：
     * `acquireLatestImage()` 只在调用那一刻取一张，没取走的 Image 会一直
     * 占着 `maxImages` 个槽位；槽位满了之后**生产者直接停摆** ——
     * 那块屏以后再也不出新帧，而界面上看不出任何异常。
     *
     * ⇒ 常驻监听、每次把最新的取走并缓存，永远不留下未消费的 Image。
     *   顺带解决第二个问题：`shot` 任何时候都能拿到"最近一帧"，
     *   而不是"此刻恰好有没有"。
     */
    @Volatile
    private var lastFrame: Bitmap? = null

    @Volatile
    private var lastFrameAt: Long = 0L

    /** 当前这块屏的 id。null = 还没建 / 已经释放。 */
    @Volatile
    var displayId: Int? = null
        private set

    /** 这块屏上被托管过的包（释放时要清掉，见类注释第 2 条）。 */
    private val hosted = LinkedHashSet<String>()

    /** 建屏尺寸。**固定**，不跟主屏走 —— 托管是它自己的世界。 */
    private val width = SCREEN_W
    private val height = SCREEN_H

    /**
     * 建屏（幂等）。
     *
     * @return 屏 id；null = 建不起来（原因见日志）
     */
    fun start(): Int? {
        displayId?.let { return it }
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        val r = runCatching {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        }.getOrElse {
            Timber.e(it, "虚拟屏：ImageReader 创建失败")
            return null
        }
        val vd = runCatching {
            // ★★ 见 VirtualScreenCommands 头注释：只写 PUBLIC 会被系统当成
            //    "屏幕录制"，要 CAPTURE_VIDEO_OUTPUT；加上 OWN_CONTENT_ONLY
            //    语义才是"这块屏是给我自己的内容用的"。
            dm.createVirtualDisplay(
                DISPLAY_NAME,
                width,
                height,
                DENSITY,
                r.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                null,
                null,
            )
        }.getOrElse {
            Timber.e(it, "虚拟屏：createVirtualDisplay 失败")
            runCatching { r.close() }
            return null
        }
        reader = r
        // ★ 立刻挂上消费监听 —— 不挂的话缓冲区会被占满、屏停止出新帧，
        //   见 lastFrame 的注释。
        frameThread = android.os.HandlerThread("virtual-screen-frames").apply { start() }
        r.setOnImageAvailableListener({ avail ->
            val img = runCatching { avail.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                // ⚠️ 不是每一帧都转 Bitmap：转一次要分配 ~3.7MB（720×1280×4）。
                //    90ms 的节流对"看一眼界面"完全够，而它把 GC 压力压掉一个量级。
                if (now - lastFrameAt >= FRAME_THROTTLE_MS) {
                    val plane = img.planes.firstOrNull()
                    if (plane != null) {
                        val bmp = plane.toBitmap(img.width, img.height)
                        val old = lastFrame
                        lastFrame = bmp
                        lastFrameAt = now
                        old?.recycle()
                    }
                }
            } finally {
                runCatching { img.close() }
            }
        }, android.os.Handler(frameThread!!.looper))
        display = vd
        displayId = vd.display.displayId
        Timber.i("虚拟屏就绪：id=%d %dx%d", vd.display.displayId, width, height)
        return vd.display.displayId
    }

    /**
     * 把某个应用拉进虚拟屏。
     *
     * @return null = 成功；否则是一句"为什么不行"
     */
    fun launch(packageName: String): String? {
        if (!VirtualScreenCommands.isValidPackage(packageName)) {
            return "包名不合法：$packageName"
        }
        val id = start() ?: return "虚拟屏没建起来（见日志）"
        val component = resolveComponent(packageName)
            ?: return "「$packageName」没有可启动的入口（可能它没有桌面图标，或包名不对）。"

        // ══════════════════════════════════════════════════════════
        //  ★★ 2026-10-28 真机：必须先杀掉它，再往虚拟屏上启动
        // ══════════════════════════════════════════════════════════
        //
        // 用户的原话："你又影响到我的主屏了。"
        //
        // 原因找到了：如果目标应用**本来就有个 Task 在主屏上**，
        // `am start --display N` 不会新建 Task，而是把**那个已有的 Task
        // 直接拉到前台** —— 于是用户眼前整个弹出微信/QQ。
        // 而我们的日志照样写着"已拉进虚拟屏"，因为命令确实成功了。
        //
        // ⇒ 先 `am force-stop`，再启动。代价是一次冷启动（慢 1～3 秒），
        //   换来的是"绝不会出现在用户屏幕上"这条硬指标。
        root(VirtualScreenCommands.forceStop(packageName))
        Thread.sleep(KILL_SETTLE_MS)

        val out = root(VirtualScreenCommands.startOnDisplay(id, component))
            ?: return "root 命令没返回（su 不可用？）"
        // ⚠️ `am start` 失败时**退出码仍是 0**，只能看输出里有没有 Error。
        if (out.contains("Error") || out.contains("Exception")) {
            return "启动失败：${out.trim().take(160)}"
        }
        hosted += packageName
        Timber.i("虚拟屏：已把 %s 拉到 display=%d（%s）", packageName, id, component)
        return null
    }

    /**
     * 把当前画面压成**一段模型读得懂的文本摘要**。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 为什么不是"直接把图片塞给模型"
     * ═══════════════════════════════════════════════════════════
     *
     * 用户的要求是"让角色能看到虚拟屏里的内容"。最理想的实现是把 PNG
     * 当多模态图片交给模型，但那条路有个硬前提：**所选模型必须支持视觉**。
     * 本项目是 BYOK —— 用户可能配的是纯文本模型，塞图片过去会整轮报错，
     * 而报错信息不会说"你的模型不支持图片"。
     *
     * ⇒ 这一版走一条**任何模型都能用**的路：把画面压成亮度网格。
     *   它给不出文字，但能给出**布局**：哪里是标题栏、哪里是列表、
     *   哪里是大片空白（说明还在加载）。配合 tap/swipe 已经够用。
     *   PNG 同时落盘，留给将来的视觉模型。
     *
     * ⚠️ 必须如实说明"这不是看图"：不说明的话，模型会编造画面内容。
     */
    fun describeScreen(): String? {
        val bmp = lastFrame ?: return null
        val cols = 16
        val rows = 24
        return buildString {
            appendLine("画面 ${bmp.width}x${bmp.height}（亮度图，不是文字识别结果）")
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val x = (c * bmp.width / cols).coerceIn(0, bmp.width - 1)
                    val y = (r * bmp.height / rows).coerceIn(0, bmp.height - 1)
                    append(LUMINANCE_RAMP[luminanceOf(bmp.getPixel(x, y)) * (LUMINANCE_RAMP.length - 1) / 255])
                }
                append('\n')
            }
            // 顶部/底部平均亮度能区分"有标题栏"和"全空白"两种完全不同的状态
            appendLine("顶部亮度=${avgLuminance(bmp, 0, bmp.height / 6)}")
            append("底部亮度=${avgLuminance(bmp, bmp.height * 5 / 6, bmp.height)}")
        }
    }

    private fun luminanceOf(px: Int): Int =
        ((px shr 16 and 0xFF) * 299 + (px shr 8 and 0xFF) * 587 + (px and 0xFF) * 114) / 1000

    private fun avgLuminance(bmp: Bitmap, fromY: Int, toY: Int): Int {
        var sum = 0L
        var n = 0
        val stepX = (bmp.width / 24).coerceAtLeast(1)
        val stepY = ((toY - fromY) / 24).coerceAtLeast(1)
        var y = fromY
        while (y < toY) {
            var x = 0
            while (x < bmp.width) {
                sum += luminanceOf(bmp.getPixel(x, y))
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 0 else (sum / n).toInt()
    }

    /**
     * 把当前虚拟屏画面存到应用私有目录，返回路径。
     *
     * ★ 存在的理由很具体：要能**证明**这块屏上真的有东西。
     *   日志说"取到 41649 字节"是一回事，把图拿出来看是另一回事 ——
     *   本项目对"我声称"和"已验证"是分开标注的。
     */
    fun saveSnapshot(fileName: String = "virtual_screen.png"): String? {
        val png = snapshotPng() ?: return null
        return runCatching {
            val f = java.io.File(context.filesDir, fileName)
            f.writeBytes(png)
            f.absolutePath
        }.getOrNull()
    }

    /** 在虚拟屏上点一下（坐标是**那块屏自己的**坐标系）。 */
    fun tap(x: Int, y: Int): String? {
        val id = displayId ?: return "虚拟屏还没建"
        if (x !in 0 until width || y !in 0 until height) {
            return "坐标 ($x,$y) 超出虚拟屏 ${width}x$height —— 点的会是别的地方"
        }
        root(VirtualScreenCommands.tapOnDisplay(id, x, y)) ?: return "root 命令没返回"
        return null
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): String? {
        val id = displayId ?: return "虚拟屏还没建"
        root(VirtualScreenCommands.swipeOnDisplay(id, x1, y1, x2, y2)) ?: return "root 命令没返回"
        return null
    }

    fun type(text: String): String? {
        val id = displayId ?: return "虚拟屏还没建"
        root(VirtualScreenCommands.typeOnDisplay(id, text)) ?: return "root 命令没返回"
        return null
    }

    fun key(keyCode: Int): String? {
        val id = displayId ?: return "虚拟屏还没建"
        root(VirtualScreenCommands.keyOnDisplay(id, keyCode)) ?: return "root 命令没返回"
        return null
    }

    /**
     * 取当前画面（PNG 字节）。
     *
     * ★ 这是"角色能看见它在操作什么"的唯一通路。
     *   `screencap -d <id>` 对 OWN_CONTENT_ONLY 的屏会失败（Status -2），
     *   所以只能由本进程的 [ImageReader] 取帧。
     *
     * @return null = 这块屏上暂时没有画面（还没人渲染）
     */
    fun snapshotPng(maxWidth: Int = MAX_SNAPSHOT_W): ByteArray? {
        // ⚠️ 屏还没建就别说"没有画面" —— 那是两个完全不同的原因，
        //    混成一句话会让模型（和排查的人）往错的方向走。
        if (reader == null) return null
        var source: Bitmap? = lastFrame
        if (source == null) {
            // ★ 缓存为空不代表没有画面：可能刚建屏、还没出第一帧。
            //   给冷启动留一点余地，而不是立刻报"没有画面"。
            var waited = 0
            while (source == null && waited < SNAPSHOT_RETRY) {
                Thread.sleep(250)
                source = lastFrame
                waited++
            }
        }
        val bmp: Bitmap = source ?: return null
        return runCatching {
            val scaled = if (bmp.width <= maxWidth) {
                bmp
            } else {
                val ratio = maxWidth.toFloat() / bmp.width
                Bitmap.createScaledBitmap(
                    bmp,
                    maxWidth,
                    (bmp.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            }
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 80, out)
                out.toByteArray()
            }
        }.getOrElse {
            Timber.w(it, "虚拟屏取帧失败")
            null
        }
    }

    /**
     * 收工：**先清任务，再释放屏**。
     *
     * ⚠️ 顺序不能换。反过来的话，被托管的应用会被系统挪回用户主屏，
     *    于是"无感"变成"突然满屏弹出一个微信"。
     */
    fun stop() {
        for (pkg in hosted.toList()) {
            root(VirtualScreenCommands.forceStop(pkg))
            Timber.i("虚拟屏：已清掉 %s", pkg)
        }
        hosted.clear()
        // ⚠️ 给 force-stop 一点时间真的落地，再释放屏。
        //    不等的话，release 与 force-stop 会赛跑，偶尔仍会挪回主屏。
        Thread.sleep(300)
        runCatching { display?.release() }
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { reader?.close() }
        runCatching { frameThread?.quitSafely() }
        frameThread = null
        runCatching { lastFrame?.recycle() }
        lastFrame = null
        lastFrameAt = 0L
        display = null
        reader = null
        displayId = null
        Timber.i("虚拟屏：已释放")
    }

    // ── 内部 ───────────────────────────────────────────────────

    private fun resolveComponent(pkg: String): String? =
        root(HostingCommands.resolveLauncher(pkg))
            ?.let { HostingCommands.parseLauncherComponent(it) }

    private fun root(cmd: String): String? = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        if (!p.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Timber.w("虚拟屏 root 命令超时：%s", cmd.take(60))
            null
        } else {
            p.inputStream.bufferedReader().readText()
        }
    }.getOrElse {
        Timber.w(it, "虚拟屏 root 命令失败：%s", cmd.take(60))
        null
    }

    /**
     * `Image.Plane` → `Bitmap`。
     *
     * ⚠️ 不能直接 `Bitmap.wrapHardwareBuffer` 或用 `copyPixelsFromBuffer`：
     *    ImageReader 的 `rowStride` 经常**大于** `width * 4`（有对齐填充），
     *    直接整块拷会得到一张斜着错位的图 —— 而且看起来"有画面"，
     *    所以这种错不会被当成 bug 发现，只会让模型认错界面。
     *    ⇒ 必须逐行按 rowStride 跨过去。
     */
    private fun Image.Plane.toBitmap(w: Int, h: Int): Bitmap {
        val buffer = buffer
        val pixelStride = pixelStride
        val rowStride = rowStride
        val rowPadding = rowStride - pixelStride * w
        val bitmap = Bitmap.createBitmap(
            w + rowPadding / pixelStride,
            h,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, w, h)
        if (cropped !== bitmap) bitmap.recycle()
        return cropped
    }

    companion object {
        /**
         * 进程内**唯一**的那个宿主。
         *
         * ★ 为什么要这个静态入口：验证/排查时需要在不新建第二块屏的前提下
         *   拿到"当前正在用的那块屏"。新建一个实例会再建一块 `VirtualDisplay`，
         *   而两块屏同时存在时，谁在渲染谁在吃内存都说不清 ——
         *   排查工具不该引入它要排查的那类问题。
         */
        @Volatile
        var current: VirtualScreenHost? = null
            private set

        private const val DISPLAY_NAME = "pocketagent-virtual"
        const val SCREEN_W = 720
        const val SCREEN_H = 1280
        const val DENSITY = 320
        const val MAX_SNAPSHOT_W = 720
        const val ROOT_TIMEOUT_SECONDS = 15L

        /** 转 Bitmap 的节流间隔。见 [lastFrame] 注释。 */
        const val FRAME_THROTTLE_MS = 90L

        /** `shot` 拿不到缓存帧时的重试次数（每次 250ms）。
         *
         * ★ 24 × 250ms = 6 秒。真机实测：微信/QQ 冷启动到**第一帧**常要 4～5 秒
         *   （先白屏、再闪屏）。原来只等 1 秒，于是 `shot` 在应用刚起来时必然报
         *   "没有画面"，而那并不代表这块屏不能用。
         */
        const val SNAPSHOT_RETRY = 24

        /**
         * `am force-stop` 之后等多久再启动。
         *
         * ⚠️ 不等的话会**偶发**地在主屏上闪一下 —— 因为 force-stop 是异步的，
         *    新的 `am start` 可能撞在"旧 Task 还没拆掉"的窗口里。
         *    这种偶发最难查：十次里成九次，剩下那一次用户就看见了。
         */
        const val KILL_SETTLE_MS = 700L

        /** 亮度网格用的字符梯度（暗 → 亮）。 */
        const val LUMINANCE_RAMP = " .:-=+*#%@"
    }
}
