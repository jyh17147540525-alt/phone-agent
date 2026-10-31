package com.pocketagent.agentui

/**
 * **用户附件的分类与图片约束** —— 决定哪些附件能真的给模型看。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 这个文件修的是一个"能力装好了但没接线"的 bug
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户："虽然在 app 里能支持图片输入，但是模型还是看不了图片，
 *      因为你没有给模型装上眼睛。"
 *
 * 查出来的根因（真机上可复现）：
 *
 *  ① `TasksScreen` 里选图 → `vm.attach(uri, "图片")` → 存进 `attachment`
 *  ② `submit()` 里把附件拼成**一段文字**塞进 prompt：
 *     `（我附了一个图片：content://... —— 这是路径，不是内容；
 *       需要的话你自己去打开看）`
 *  ③ 模型收到的是一串 URI 字符串。它**当然**看不了。
 *
 * 而 provider 层其实**早就能发图片**（`ContentPart.Image` +
 * `image_url` + data URI 都实现了，见 `OpenAiCompatProvider`）。
 * 缺的只是"把用户选的图读成字节"这一步。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么这一层要住在纯 Kotlin 里
 * ═══════════════════════════════════════════════════════════════
 *
 * "哪些附件能给模型看"是个**判断**，而且判断错了有两个方向的代价：
 *   · 判成"能发"但其实是文件 ⇒ 模型收到一堆乱码字节，答非所问
 *   · 判成"不能发"但其实是图片 ⇒ 用户以为功能坏了
 *
 * 这两个都不是崩溃，是**安静地做错事**。所以它必须有测试。
 */
object Attachments {

    /**
     * 一个附件的类型。
     *
     * ⚠️ 它不是按扩展名猜的 —— 见 [fromMime]。
     */
    enum class Kind {
        /** 图片。**可以**作为 `ContentPart.Image` 发给模型。 */
        IMAGE,

        /** 音频。可以走 STT（本版未接），但不能当图片发。 */
        AUDIO,

        /** 其它文件。只能给路径，模型看不到内容。 */
        FILE,
    }

    /**
     * 从 MIME 类型判断。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 为什么不看扩展名
     * ═══════════════════════════════════════════════════════════
     *
     * Android 的 `content://` URI 不带可信扩展名（它可能是一个
     * 无扩展名的缓存文件）。而 `ContentResolver.getType()` 给的是
     * **系统按内容判定**的 MIME —— 那才是真的。
     *
     * ⚠️ 也不能只信自己猜的：用户可能把一个 .png 改名成 .txt，
     *    或者反过来。按扩展名判会把它们全判错。
     *
     * @param mime 来自 `ContentResolver.getType(uri)`；null = 读不到
     * @return 读不到时按 [Kind.FILE] 处理 —— **保守方向**：
     *   宁可"这张图没发出去"，也不要"把二进制当图片发出去"。
     */
    fun fromMime(mime: String?): Kind {
        val m = mime?.lowercase()?.trim().orEmpty()
        return when {
            m.startsWith("image/") -> Kind.IMAGE
            m.startsWith("audio/") -> Kind.AUDIO
            else -> Kind.FILE
        }
    }

    /**
     * 这个 MIME 能不能被主流视觉模型接受。
     *
     * ★ 只放行 PNG / JPEG / WebP / GIF（首帧）：
     *   · 各家的 `image_url` 规范主要就这几个
     *   · HEIC（iPhone 默认）**很多服务商不支持** —— 直接发会被 400 拒绝，
     *     而错误信息通常只说"unsupported image format"，用户看不出是格式问题
     *
     * ⇒ 不支持的格式应当在**本地就转码**（见 `ImagePrep`），
     *   而不是发出去等厂商报错。
     */
    fun isSupportedImageMime(mime: String?): Boolean {
        val m = mime?.lowercase()?.trim().orEmpty()
        return m == "image/png" ||
            m == "image/jpeg" ||
            m == "image/jpg" ||
            m == "image/webp" ||
            m == "image/gif"
    }

    /**
     * 图片字节的**大小上限**。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 为什么必须有上限，而且要在本地压
     * ═══════════════════════════════════════════════════════════
     *
     * 手机拍的一张照片 4~8 MB。转成 base64 之后膨胀 33% ⇒ 5~11 MB
     * **塞进一次 JSON 请求体**。后果：
     *   · 多数厂商对单次请求体有硬上限（常见 10~20 MB），超了直接 413
     *   · 就算没超，上传要几十秒，用户以为卡死
     *   · 按 token 计费时图片通常按"分辨率档位"算，原图不省一分钱
     *
     * ⇒ 4 MB 上限 + 长边压到 [MAX_EDGE_PX]。
     *   这个组合下图片仍在"模型能看清字"的范围里，而体积降到 1MB 内。
     */
    const val MAX_IMAGE_BYTES: Int = 4 * 1024 * 1024

    /**
     * 压缩后的长边上限（像素）。
     *
     * ★ 1568：这是 Anthropic 官方文档给的"视觉精度不损失"的档位，
     *   也是多数模型的有效分辨率上限。超过之后模型会把图缩小再看，
     *   所以多发像素**不会**让它看得更清，只会让请求更慢更贵。
     */
    const val MAX_EDGE_PX: Int = 1568

    /**
     * 压缩质量（JPEG）。
     *
     * ★ 85：肉眼与 100 几乎无差别，体积约小一半。
     *   再低（70 以下）文字边缘会糊 —— 而用户发图很多时候就是
     *   "帮我看看这段截图里写了什么"。
     */
    const val JPEG_QUALITY: Int = 85

    /**
     * 一个待发送的图片。
     *
     * @param mimeType 压缩后**实际**的 MIME（不一定等于原图）
     * @param bytes 压缩后的字节
     */
    data class PreparedImage(val bytes: ByteArray, val mimeType: String) {
        init {
            require(bytes.isNotEmpty()) { "图片字节不能为空" }
            require(mimeType.startsWith("image/")) { "MIME 必须是 image/*：$mimeType" }
            require(bytes.size <= MAX_IMAGE_BYTES) {
                "图片过大（${bytes.size} > $MAX_IMAGE_BYTES）—— 应当在压缩阶段就被拦下"
            }
        }

        /** 转 base64 后的估算体积（实际编码在 UI 层做，这里只给长度预估）。 */
        val base64Estimate: Int get() = (bytes.size + 2) / 3 * 4
    }

    /**
     * 该不该把这个附件当图片发。
     *
     * @return null = 可以发；否则是一句**给用户看**的原因
     *
     * ★ 返回"原因"而不是 Boolean：用户选了图却发不出去时，
     *   必须知道为什么（"HEIC 不支持，已帮你转成 JPEG" vs
     *   "这张图太大了"）。只说"失败"会让他反复重试。
     */
    fun rejectionReason(mime: String?, byteCount: Int): String? = when {
        fromMime(mime) != Kind.IMAGE -> "这个附件不是图片，只能发路径给模型"
        !isSupportedImageMime(mime) ->
            "这个图片格式（${mime ?: "未知"}）多数模型不支持，建议先转成 JPEG"
        byteCount <= 0 -> "这个文件是空的"
        // ⚠️ 注意：**超限不算拒绝** —— 走压缩路径。
        //    只有压缩后仍然超限才拒，那个判断在压缩之后。
        else -> null
    }
}
