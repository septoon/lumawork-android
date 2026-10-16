package ru.lumastack.lumawork.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

enum class AppAppearance(val title: String) {
    System("Системная"), Light("Светлая"), Dark("Тёмная");

    fun usesDarkTheme(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }
}

data class AppAppearancePreference(val mode: AppAppearance, val onChange: (AppAppearance) -> Unit)

val LocalAppAppearance = staticCompositionLocalOf {
    AppAppearancePreference(AppAppearance.System) {}
}

val LumaPrimaryLight = Color(0xFF3D634F)
val LumaPrimaryDark = Color(0xFF8CBA9E)
val LumaBackgroundTopLight = Color(0xFFFAF7ED)
val LumaBackgroundBottomLight = Color(0xFFE8EDE3)
val LumaBackgroundTopDark = Color(0xFF0D1217)
val LumaBackgroundBottomDark = Color(0xFF1A211F)

private val LightColors = lightColorScheme(
    primary = LumaPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0EBDF),
    onPrimaryContainer = Color(0xFF203C2D),
    secondary = Color(0xFFB86E45),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2E5D6),
    onSecondaryContainer = Color(0xFF62432D),
    tertiary = Color(0xFF647C68),
    tertiaryContainer = Color(0xFFE3EBDD),
    onTertiaryContainer = Color(0xFF2A402E),
    background = LumaBackgroundBottomLight,
    onBackground = Color(0xFF1F2426),
    surface = Color(0xFFFCFBF7),
    onSurface = Color(0xFF1F2426),
    surfaceVariant = Color(0xFFE3E6DC),
    onSurfaceVariant = Color(0xFF6F746F),
    outline = Color(0xFF858C82),
    outlineVariant = Color(0xFFD5DCCD),
    surfaceTint = LumaPrimaryLight,
    surfaceContainer = Color(0xFFF0F1E9),
    surfaceContainerLow = Color(0xFFF7F7F0),
    surfaceContainerHigh = Color(0xFFE9ECE2),
    error = Color(0xFFBC453D),
)

private val DarkColors = darkColorScheme(
    primary = LumaPrimaryDark,
    onPrimary = Color(0xFF11281E),
    primaryContainer = Color(0xFF2C4436),
    onPrimaryContainer = Color(0xFFCCE6D2),
    secondary = Color(0xFFDE9E69),
    onSecondary = Color(0xFF3F2715),
    secondaryContainer = Color(0xFF47392E),
    onSecondaryContainer = Color(0xFFF1D8BF),
    tertiary = Color(0xFFA1B89F),
    tertiaryContainer = Color(0xFF354434),
    onTertiaryContainer = Color(0xFFD6E6CF),
    background = LumaBackgroundTopDark,
    onBackground = Color(0xFFEBEFED),
    surface = Color(0xFF1F2628),
    onSurface = Color(0xFFEBEFED),
    surfaceVariant = Color(0xFF2B3334),
    onSurfaceVariant = Color(0xFFA1AAA6),
    outline = Color(0xFF79867C),
    outlineVariant = Color(0xFF3C4840),
    surfaceTint = LumaPrimaryDark,
    surfaceContainer = Color(0xFF1B2423),
    surfaceContainerLow = Color(0xFF151E1D),
    surfaceContainerHigh = Color(0xFF26302C),
    error = Color(0xFFEB7369),
)

@Composable
fun LumaWorkTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    fun readMode() = AppAppearance.entries.firstOrNull { it.name == preferences.getString("mode", null) }
        ?: AppAppearance.System
    var appearance by remember(preferences) { mutableStateOf(readMode()) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "mode") appearance = readMode()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val resolvedDark = darkTheme ?: appearance.usesDarkTheme(isSystemInDarkTheme())
    val view = LocalView.current
    val currentDark = rememberUpdatedState(resolvedDark)
    // AndroidX keeps the first configuration listener, so its detector must read current state.
    val statusBarStyle = remember(view) {
        SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) {
            currentDark.value
        }
    }
    val navigationBarStyle = remember(view) {
        SystemBarStyle.auto(LightColors.surface.toArgb(), DarkColors.surface.toArgb()) {
            currentDark.value
        }
    }
    DisposableEffect(view, resolvedDark) {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>().firstOrNull()
        if (!view.isInEditMode && activity != null) {
            activity.enableEdgeToEdge(statusBarStyle, navigationBarStyle)
        }
        onDispose { }
    }
    CompositionLocalProvider(LocalAppAppearance provides AppAppearancePreference(appearance) {
        appearance = it
        preferences.edit { putString("mode", it.name) }
    }) {
        MaterialTheme(
            colorScheme = if (resolvedDark) DarkColors else LightColors,
            typography = LumaTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(10.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(22.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}

