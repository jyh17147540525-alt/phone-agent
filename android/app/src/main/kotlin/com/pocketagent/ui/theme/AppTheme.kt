package com.pocketagent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 应用主题。
 *
 * 配色刻意保守：这个应用的界面主体是"权限与风险提示"，
 * 用户在上面做的决定涉及自己的账号和钱。花哨的配色会削弱警示的分量。
 * 所以只有**风险等级**用高饱和色，其余一律低饱和。
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CC4FF),
    onPrimary = Color(0xFF00344F),
    secondary = Color(0xFFB0CCE0),
    background = Color(0xFF101417),
    surface = Color(0xFF161B1F),
    surfaceVariant = Color(0xFF232A2F),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00629E),
    secondary = Color(0xFF4E616D),
    background = Color(0xFFF7F9FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE3E8EC),
    error = Color(0xFFBA1A1A),
)

/**
 * 风险等级配色。
 *
 * 单独抽出来而不是塞进 ColorScheme，因为它是**语义色**不是主题色：
 * 「极高风险」在任何主题下都必须是同一个红，不能随浅色/深色漂移 ——
 * 用户是靠这个颜色形成条件反射的。
 */
object RiskColors {
    val low = Color(0xFF3FA34D)
    val medium = Color(0xFFD98A00)
    val high = Color(0xFFE0592A)
    val critical = Color(0xFFC62828)
}

@Composable
fun PocketAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
