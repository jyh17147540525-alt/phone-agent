package com.pocketagent.display

import kotlinx.coroutines.flow.Flow

/**
 * 虚拟显示管理器。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 先读这段，否则会写出跑不通的代码
 * ═══════════════════════════════════════════════════════════════
 *
 * AOSP 官方文档《activity 启动政策》明确规定：
 *
 *   「在 Android 10 中，为了防止恶意应用通过从其创建的虚拟屏幕的表面
 *     读取用户敏感信息来盗用相关信息，**应用只能在其创建的虚拟屏幕上
 *     启动其自己的 Activity**。」
 *
 * 这意味着：**普通第三方应用无法把微信、美团这类别人的 App 启动到虚拟屏上。**
 * 唯一出路是 **Shizuku 的 shell 权限**（shell UID 与普通应用权限不同，
 * `am start --display` 是官方支持的 shell 参数）。
 *
 * 因此本模块的设计前提是：
 *  1. **强依赖 Shizuku**，无 Shizuku 时一切返回 [DisplayAvailability.Unavailable]
 *  2. **无障碍通道无法操作虚拟屏**（只能读，不能注入输入）
 *  3. **截取虚拟屏只能靠 `screencap -d`**（MediaProjection 截不到副屏）
 *  4. 上层必须实现降级链：虚拟屏 → 小窗 → 全屏接管 → 手动引导
 *
 * ═══════════════════════════════════════════════════════════════
 *  实现要点（易错，务必遵守）
 * ═══════════════════════════════════════════════════════════════
 *
 * - 创建副屏后**不要带 `secure` 参数**，否则无法截屏
 * - 应用崩溃/被杀时副屏**不会自动清理**，启动时必须做孤儿副屏检测
 * - 释放顺序必须是：隐藏预览 → force-stop App → 释放 VirtualDisplay → 删设置
 * - 若用 ImageReader 做预览，`maxImages` 设 2 且必须及时 close，
 *   否则 buffer 满会导致副屏 App 停止渲染（表现为"卡死"）
 */
interface VirtualDisplayManager {

    /**
     * 当前可用性。UI 据此决定显示哪种执行模式。
     * ⚠️ 这是个廉价调用，可以频繁调用（结果有缓存）。
     */
    fun availability(): DisplayAvailability

    /**
     * 创建（或复用已存在的）虚拟屏。
     *
     * 实现：
     * ```
     * settings put global overlay_display_devices "1080x1920/320"
     * ```
     * 然后从 DisplayManager 中查找 type == OVERLAY 的显示，取其 displayId。
     *
     * @return 创建结果。已存在时直接复用，不重复创建。
     */
    suspend fun ensureDisplay(config: DisplayConfig = DisplayConfig()): DisplayResult

    /** 当前虚拟屏的 displayId；未创建时为 null */
    fun currentDisplayId(): Int?

    /**
     * 在虚拟屏上启动目标 App。
     *
     * 实现：
     * ```
     * am start --display <displayId> -n <packageName>/<activityName>
     * ```
     *
     * ⚠️ 启动前建议先调 [canRunOnDisplay] 预检，避免黑屏。
     */
    suspend fun launchOnDisplay(packageName: String, displayId: Int): LaunchResult

    /**
     * 预检：某 App 能否在虚拟屏正常渲染。
     *
     * 判断依据：
     *  1. 目标 Activity 是否声明 `resizeableActivity="true"`（未声明的大概率失败）
     *  2. 该 App 是否在已知"拒绝多显示"的黑名单中（金融类、安全类）
     *  3. 历史记录：之前是否在该 App 上失败过
     *
     * ⚠️ 这是启发式判断，不能保证 100% 准确。失败时仍需兜底降级。
     */
    suspend fun canRunOnDisplay(packageName: String): Boolean

    /**
     * 在虚拟屏上注入输入。
     *
     * 实现：
     * ```
     * input -d <displayId> tap <x> <y>
     * input -d <displayId> swipe <x1> <y1> <x2> <y2> <durationMs>
     * input -d <displayId> text "<text>"
     * ```
     *
     * ✅ 关键优势：`-d` 参数让输入只作用于副屏，**不干扰用户在主屏的触摸**。
     */
    suspend fun injectInput(displayId: Int, input: DisplayInput): InputResult

    /**
     * 截取虚拟屏画面。
     *
     * 实现：
     * ```
     * screencap -d <displayId> -p <path>
     * ```
     * 然后读取文件、缩放到目标尺寸、删除临时文件。
     *
     * ⚠️ **只能走 Shizuku**。MediaProjection 无法截取副屏。
     * ⚠️ 若副屏创建时带了 `secure` 参数会失败（所以不要带）。
     */
    suspend fun captureDisplay(displayId: Int, options: CaptureOptions): DisplayCapture?

    /**
     * 观察虚拟屏上正在运行的应用。
     * 用于检测目标 App 是否被系统或用户杀掉。
     */
    fun observeRunningApps(displayId: Int): Flow<Set<String>>

    /**
     * 释放虚拟屏并清理。
     *
     * 严格按此顺序：
     *  1. 隐藏副屏预览悬浮窗
     *  2. force-stop 副屏上运行的所有 App
     *  3. 释放 VirtualDisplay
     *  4. 关闭 ImageReader（若使用）
     *  5. `settings put global overlay_display_devices "null"`
     */
    suspend fun release()

    /**
     * 孤儿副屏检测与清理。
     *
     * ⚠️ 应用被系统杀死时不会走 [release]，副屏会残留。
     * **必须在 Application.onCreate 中调用本方法**，清理不属于当前会话的副屏。
     */
    suspend fun cleanupOrphanDisplays()
}

// ═══════════════════════════════════════════════════════════════
//  可用性
// ═══════════════════════════════════════════════════════════════

sealed interface DisplayAvailability {
    /** 完全可用 */
    data class Available(val displayId: Int?) : DisplayAvailability

    /** Shizuku 未安装 —— 引导用户去安装 */
    data object ShizukuNotInstalled : DisplayAvailability

    /** Shizuku 已安装但未激活（通常是重启后需要重新激活） */
    data object ShizukuNotRunning : DisplayAvailability

    /** 已激活但缺少 WRITE_SECURE_SETTINGS 权限 */
    data object MissingWriteSecureSettings : DisplayAvailability

    /** 创建副屏失败（ROM 限制） */
    data class CreateFailed(val reason: String) : DisplayAvailability

    /** 系统版本过低（overlay display 需 Android 10+ 才有完整触摸支持） */
    data object UnsupportedOsVersion : DisplayAvailability
}

/** 是否可用的便捷判断 */
val DisplayAvailability.isUsable: Boolean
    get() = this is DisplayAvailability.Available

/**
 * 降级决策。
 *
 * ⚠️ 上层（Agent）必须实现这条链，不能假设虚拟屏一定可用。
 */
sealed interface ExecutionMode {
    /** 虚拟屏模式：主屏完全不受影响（最佳） */
    data class VirtualDisplay(val displayId: Int) : ExecutionMode

    /** 小窗模式：占用部分主屏，但可移动可最小化 */
    data object FreeformWindow : ExecutionMode

    /** 全屏接管：占用主屏，必须明确告知用户并提供中止入口 */
    data object FullScreenTakeover : ExecutionMode

    /** 手动引导：只给步骤，用户自己操作（永不失效的兜底） */
    data object ManualGuidance : ExecutionMode
}

// ═══════════════════════════════════════════════════════════════
//  配置与结果
// ═══════════════════════════════════════════════════════════════

data class DisplayConfig(
    val width: Int = 1080,
    val height: Int = 1920,
    val densityDpi: Int = 320,
    /**
     * ⚠️ **保持 false**。
     * 设为 true 会让副屏无法截屏/录屏，而我们需要 `screencap -d` 来读取画面。
     */
    val secure: Boolean = false,
)

sealed interface DisplayResult {
    data class Created(val displayId: Int) : DisplayResult

    /** 已存在，直接复用 */
    data class Reused(val displayId: Int) : DisplayResult

    data object Unavailable : DisplayResult
    data class Failed(val reason: String) : DisplayResult
}

sealed interface LaunchResult {
    data object Started : LaunchResult

    /** 该 App 不支持多显示 */
    data class NotSupported(val packageName: String) : LaunchResult

    /** 启动超时，可能黑屏 */
    data object Timeout : LaunchResult

    /** 权限不足 */
    data class PermissionDenied(val reason: String) : LaunchResult

    data class Failed(val reason: String) : LaunchResult
}

/** 虚拟屏输入事件 */
sealed interface DisplayInput {
    data class Tap(val x: Int, val y: Int) : DisplayInput
    data class LongPress(val x: Int, val y: Int, val durationMs: Long = 600) : DisplayInput
    data class Swipe(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val durationMs: Long = 300) : DisplayInput
    data class Text(val content: String) : DisplayInput
    data object Back : DisplayInput
    data object Home : DisplayInput
}

sealed interface InputResult {
    data object Injected : InputResult
    data class Failed(val reason: String) : InputResult
}

data class DisplayCapture(
    /** 副屏原始尺寸 */
    val sourceWidth: Int,
    val sourceHeight: Int,
    /** 已按 options 缩放压缩后的字节（WebP） */
    val bytes: ByteArray,
    val mimeType: String = "image/webp",
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is DisplayCapture && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()
}

/**
 * 截图参数。
 * ⚠️ 默认值已针对 2K 屏（红米 K60 为 3200×1440）的成本优化设定。
 * 但副屏是我们自己创建的，可以直接按目标分辨率创建，无需再缩放。
 */
data class CaptureOptions(
    /** 输出长边上限。副屏本身就设成 1080 宽，一般不需要再缩 */
    val maxEdge: Int = 1280,
    /** WebP 质量 */
    val quality: Int = 75,
    /** 临时文件目录（会被清理） */
    val tempDir: String,
)

// ═══════════════════════════════════════════════════════════════
//  坐标映射（副屏预览交互用）
// ═══════════════════════════════════════════════════════════════

/**
 * 把预览窗上的触摸坐标换算成虚拟屏坐标。
 *
 * 采用 FIT_CENTER（等比缩放 + letterbox 居中）。
 * 触摸点落在黑边上时返回 null（应被忽略）。
 *
 * 流程：用户在预览窗点击 → 本函数换算 → [VirtualDisplayManager.injectInput]
 */
fun mapToDisplay(
    touchX: Float,
    touchY: Float,
    viewWidth: Int,
    viewHeight: Int,
    displayWidth: Int,
    displayHeight: Int,
): DisplayPoint? {
    if (viewWidth <= 0 || viewHeight <= 0) return null

    val scale = minOf(
        viewWidth.toFloat() / displayWidth,
        viewHeight.toFloat() / displayHeight,
    )
    val renderedW = displayWidth * scale
    val renderedH = displayHeight * scale
    val offsetX = (viewWidth - renderedW) / 2f
    val offsetY = (viewHeight - renderedH) / 2f

    val contentX = touchX - offsetX
    val contentY = touchY - offsetY

    if (contentX < 0f || contentY < 0f || contentX > renderedW || contentY > renderedH) {
        return null
    }

    return DisplayPoint(
        x = (contentX / renderedW * displayWidth).toInt().coerceIn(0, displayWidth - 1),
        y = (contentY / renderedH * displayHeight).toInt().coerceIn(0, displayHeight - 1),
    )
}

data class DisplayPoint(val x: Int, val y: Int)

// ═══════════════════════════════════════════════════════════════
//  已知限制（供 UI 展示给用户）
// ═══════════════════════════════════════════════════════════════

/**
 * 虚拟屏的已知限制 —— **必须在 UI 上如实告知用户**。
 *
 * 不告知的后果：用户以为是 bug，然后来提 issue，然后你只能回复"这是系统限制"。
 * 提前说清楚，用户反而会觉得你专业。
 */
object DisplayLimitations {
    val items: List<Limitation> = listOf(
        Limitation(
            title = "副屏窗口在主屏上可见",
            detail = "这是 Android 系统的行为，副屏会以悬浮窗口的形式叠加在主屏上，" +
                "可以缩小和移动，但无法完全隐藏。这是系统限制，不是本应用的问题。",
        ),
        Limitation(
            title = "需要 Shizuku 才能使用",
            detail = "Android 10 起，系统禁止普通应用把其他应用启动到虚拟屏上。" +
                "只有通过 Shizuku 获取 shell 权限才能做到。" +
                "Shizuku 需要每次重启手机后重新激活。",
        ),
        Limitation(
            title = "部分应用无法在副屏运行",
            detail = "没有声明支持多窗口的应用（尤其是一些老应用和金融类应用）" +
                "可能无法在副屏正常显示。遇到这种情况会自动降级到小窗模式。",
        ),
        Limitation(
            title = "最多同时创建 6 块副屏",
            detail = "系统限制。本应用通常只需要 1 块。",
        ),
    )

    data class Limitation(val title: String, val detail: String)
}
