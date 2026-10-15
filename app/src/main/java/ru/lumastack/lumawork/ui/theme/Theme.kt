package ru.lumastack.lumawork.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LumaPrimaryLight = Color(0xFF3D634F)
val LumaPrimaryDark = Color(0xFF8CBA9E)
val LumaBackgroundTopLight = Color(0xFFFAF7ED)
val LumaBackgroundBottomLight = Color(0xFFE8EDE3)
val LumaBackgroundTopDark = Color(0xFF0D1217)
val LumaBackgroundBottomDark = Color(0xFF1A211F)

private val LightColors = lightColorScheme(
    primary = LumaPrimaryLight,
    onPrimary = Color.White,
    secondary = Color(0xFFB86E45),
    background = LumaBackgroundBottomLight,
    onBackground = Color(0xFF1F2426),
    surface = Color(0xFFFCFBF7),
    onSurface = Color(0xFF1F2426),
    surfaceVariant = Color(0xFFE3E6DC),
    onSurfaceVariant = Color(0xFF6F746F),
    error = Color(0xFFBC453D),
)

private val DarkColors = darkColorScheme(
    primary = LumaPrimaryDark,
    onPrimary = Color(0xFF11281E),
    secondary = Color(0xFFDE9E69),
    background = LumaBackgroundTopDark,
    onBackground = Color(0xFFEBEFED),
    surface = Color(0xFF1F2628),
    onSurface = Color(0xFFEBEFED),
    surfaceVariant = Color(0xFF2B3334),
    onSurfaceVariant = Color(0xFFA1AAA6),
    error = Color(0xFFEB7369),
)

@Composable
fun LumaWorkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = LumaTypography,
        content = content,
    )
}

