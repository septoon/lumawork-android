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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults

enum class AppAppearance(val title: String) {
    System("Система"), Light("День"), Dark("Ночь");

    fun usesDarkTheme(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }
}

data class AppAppearancePreference(
    val mode: AppAppearance,
    val backgroundEnabled: Boolean = true,
    val onBackgroundChange: (Boolean) -> Unit = {},
    val onChange: (AppAppearance) -> Unit,
)

val LocalAppAppearance = staticCompositionLocalOf {
    AppAppearancePreference(AppAppearance.System) {}
}

val LocalAppDarkTheme = staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun appTopBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent,
    scrolledContainerColor = Color.Transparent,
    navigationIconContentColor = MaterialTheme.colorScheme.primary,
    actionIconContentColor = MaterialTheme.colorScheme.primary,
)

/** Shared screen fill: changing the background preference never replaces the screen's state. */
@Composable
fun appScreenBackground(): Brush {
    val dark = LocalAppDarkTheme.current
    val colored = LocalAppAppearance.current.backgroundEnabled
    val background = MaterialTheme.colorScheme.background
    return remember(dark, colored, background) {
        Brush.linearGradient(if (!colored) listOf(background, background)
            else if (dark) listOf(LumaBackgroundTopDark, LumaBackgroundMiddleDark, LumaBackgroundBottomDark)
            else listOf(LumaBackgroundTopLight, LumaBackgroundMiddleLight, LumaBackgroundBottomLight))
    }
}

// Source: iOS AppTheme.swift and its named sRGB assets, including their opacity.
val LumaPrimaryLight = Color(.24f, .39f, .31f)
val LumaPrimaryDark = Color(.55f, .73f, .62f)
val LumaBackgroundTopLight = Color(.98f, .97f, .93f)
val LumaBackgroundMiddleLight = Color(.94f, .95f, .90f)
val LumaBackgroundBottomLight = Color(.91f, .93f, .89f)
val LumaBackgroundTopDark = Color(.05f, .07f, .09f)
val LumaBackgroundMiddleDark = Color(.08f, .10f, .12f)
val LumaBackgroundBottomDark = Color(.10f, .13f, .12f)

internal object AppSurfaces {
    val card: Color @Composable get() = if (LocalAppDarkTheme.current)
        Color(.12f, .15f, .16f, .88f) else Color.White.copy(alpha = .86f)
    // Native iOS sheets supply a backing surface beneath performancePanelFill.
    // Composite that backing here so underlying page text cannot show through a form.
    val panel: Color @Composable get() = (if (LocalAppDarkTheme.current)
        Color(.10f, .12f, .14f, .86f) else Color.White.copy(alpha = .84f))
        .compositeOver(MaterialTheme.colorScheme.background)
    val subpanel: Color @Composable get() = if (LocalAppDarkTheme.current)
        Color(.14f, .16f, .18f, .80f) else Color.White.copy(alpha = .78f)
    val border: Color @Composable get() = Color.White.copy(alpha = if (LocalAppDarkTheme.current) .12f else .62f)
    val shadow: Color @Composable get() = Color.Black.copy(alpha = if (LocalAppDarkTheme.current) .323f else .068f)
    val scrim: Color @Composable get() = Color.Black.copy(alpha = if (LocalAppDarkTheme.current) .44f else .20f)
    val buttonFill: Brush @Composable get() = Brush.linearGradient(if (LocalAppDarkTheme.current)
        listOf(Color(.23f, .29f, .27f), Color(.18f, .22f, .21f))
        else listOf(Color(.91f, .88f, .80f), Color(.82f, .86f, .78f)))
    val accent: Brush @Composable get() = Brush.linearGradient(if (LocalAppDarkTheme.current)
        listOf(Color(.44f, .63f, .52f), Color(.20f, .29f, .25f))
        else listOf(Color(.33f, .49f, .39f), Color(.22f, .33f, .27f)))
    val hero: Brush @Composable get() = Brush.linearGradient(if (LocalAppDarkTheme.current)
        listOf(Color(.48f, .29f, .18f), Color(.23f, .35f, .28f), Color(.10f, .16f, .14f))
        else listOf(Color(.78f, .50f, .34f), Color(.44f, .55f, .41f), Color(.25f, .35f, .31f)))
}

private val LightColors = lightColorScheme(
    primary = LumaPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0EBDF),
    onPrimaryContainer = Color(0xFF203C2D),
    secondary = Color(.72f, .43f, .27f),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2E5D6),
    onSecondaryContainer = Color(0xFF62432D),
    tertiary = Color(0xFF647C68),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE3EBDD),
    onTertiaryContainer = Color(0xFF2A402E),
    background = LumaBackgroundMiddleLight,
    onBackground = Color(.12f, .14f, .15f),
    surface = Color.White,
    onSurface = Color(.12f, .14f, .15f),
    surfaceVariant = Color(.89f, .90f, .85f),
    onSurfaceVariant = Color(.46f, .48f, .45f),
    outline = Color(0xFF858C82),
    outlineVariant = Color(0xFFD5DCCD),
    surfaceTint = Color.Transparent,
    surfaceDim = LumaBackgroundBottomLight,
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(.97f, .97f, .95f),
    surfaceContainer = Color(.96f, .96f, .93f),
    surfaceContainerHigh = Color(.93f, .94f, .90f),
    surfaceContainerHighest = Color(.89f, .90f, .85f),
    inverseSurface = Color(.12f, .15f, .16f),
    inverseOnSurface = Color(.92f, .94f, .93f),
    inversePrimary = LumaPrimaryDark,
    scrim = Color.Black,
    error = Color(.74f, .27f, .24f),
    onError = Color.White,
    errorContainer = Color(0xFFFBE4E0),
    onErrorContainer = Color(0xFF6F201B),
)

private val DarkColors = darkColorScheme(
    primary = LumaPrimaryDark,
    onPrimary = Color(0xFF11281E),
    primaryContainer = Color(0xFF2C4436),
    onPrimaryContainer = Color(0xFFCCE6D2),
    secondary = Color(.87f, .62f, .41f),
    onSecondary = Color(0xFF3F2715),
    secondaryContainer = Color(0xFF47392E),
    onSecondaryContainer = Color(0xFFF1D8BF),
    tertiary = Color(0xFFA1B89F),
    onTertiary = Color(.12f, .20f, .14f),
    tertiaryContainer = Color(0xFF354434),
    onTertiaryContainer = Color(0xFFD6E6CF),
    background = LumaBackgroundMiddleDark,
    onBackground = Color(.92f, .94f, .93f),
    surface = Color(.12f, .15f, .16f),
    onSurface = Color(.92f, .94f, .93f),
    surfaceVariant = Color(.18f, .21f, .20f),
    onSurfaceVariant = Color(.63f, .67f, .65f),
    outline = Color(0xFF79867C),
    outlineVariant = Color(0xFF3C4840),
    surfaceTint = Color.Transparent,
    surfaceDim = LumaBackgroundTopDark,
    surfaceBright = Color(.18f, .21f, .20f),
    surfaceContainerLowest = Color(.08f, .10f, .12f),
    surfaceContainerLow = Color(.10f, .12f, .14f),
    surfaceContainer = Color(.14f, .16f, .18f),
    surfaceContainerHigh = Color(.16f, .19f, .19f),
    surfaceContainerHighest = Color(.18f, .21f, .20f),
    inverseSurface = Color(.94f, .95f, .90f),
    inverseOnSurface = Color(.12f, .14f, .15f),
    inversePrimary = LumaPrimaryLight,
    scrim = Color.Black,
    error = Color(.92f, .45f, .41f),
    onError = Color(0xFF3B0906),
    errorContainer = Color(0xFF512B29),
    onErrorContainer = Color(0xFFFFDAD5),
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
    var backgroundEnabled by remember(preferences) { mutableStateOf(preferences.getBoolean("background-color-enabled", true)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "mode") appearance = readMode()
            if (key == "background-color-enabled") backgroundEnabled = preferences.getBoolean("background-color-enabled", true)
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
    CompositionLocalProvider(LocalAppDarkTheme provides resolvedDark,
        LocalContentColor provides (if (resolvedDark) DarkColors else LightColors).onBackground,
        LocalTonalElevationEnabled provides false,
        LocalAppAppearance provides AppAppearancePreference(appearance, backgroundEnabled, {
            backgroundEnabled = it
            preferences.edit { putBoolean("background-color-enabled", it) }
        }) {
        appearance = it
        preferences.edit { putString("mode", it.name) }
    }) {
        MaterialTheme(
            colorScheme = (if (resolvedDark) DarkColors else LightColors).let { colors ->
                if (backgroundEnabled) colors else colors.copy(background = if (resolvedDark) Color.Black else Color.White)
            },
            typography = LumaTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(10.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}

