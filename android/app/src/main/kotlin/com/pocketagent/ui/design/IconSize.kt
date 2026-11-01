package com.pocketagent.ui.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketagent.agentui.IconGeometry

/**
 * 把 [IconGeometry.Size] 档位转成 Compose 的 [Dp]。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么要这个扩展
 * ═══════════════════════════════════════════════════════════════
 *
 * `IconGeometry` 在 `:agentui`，那个模块是**纯 Kotlin**（能离线测），
 * 所以不能返回 Compose 的 `Dp` —— 那会把它拖进 Android 依赖。
 *
 * ⇒ 数值留在纯逻辑层，`Dp` 转换放在 UI 层。
 *   于是档位可测，界面也能直接写：
 *
 * ```kotlin
 * Icon(Icons.Default.Menu, modifier = Modifier.size(IconGeometry.Size.DEFAULT.dp))
 * ```
 */
val IconGeometry.Size.dp: Dp get() = value.dp