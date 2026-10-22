package ru.lumastack.lumawork.ui.shell

import androidx.compose.foundation.layout.RowScope
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import ru.lumastack.lumawork.ui.theme.AppSegmentedControl
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.lumastack.lumawork.ui.theme.LocalAppDarkTheme

internal class AppHeaderState {
    var content by mutableStateOf<AppHeaderContent?>(null)
    var onBack by mutableStateOf<(() -> Unit)?>(null)
}

internal class AppHeaderContent(
    val title: (@Composable () -> Unit)?,
    val actions: @Composable RowScope.() -> Unit,
    val background: androidx.compose.ui.graphics.Brush? = null,
)

internal val LocalAppHeader = staticCompositionLocalOf<AppHeaderState?> { null }

/** Publishes route actions, optionally replacing the shell's default title. */
@Composable
fun AppHeaderActions(title: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit) {
    val header = LocalAppHeader.current ?: return
    val currentTitle by rememberUpdatedState(title)
    val currentActions by rememberUpdatedState(actions)
    DisposableEffect(header, title != null) {
        val content = AppHeaderContent(title = if (title != null) ({ currentTitle?.invoke() }) else null,
            actions = { currentActions() })
        header.content = content
        onDispose { if (header.content === content) header.content = null }
    }
}

/** Two leading-aligned capsules from iOS AppSidebarMenuButton, inside a 48 dp touch target. */
@Composable
internal fun AppMenuButton(onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val dark = LocalAppDarkTheme.current
    val tint = if (dark) Color.White else Color(.08f, .10f, .11f)
    IconButton(onClick = {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }, modifier = Modifier.size(48.dp)) {
        androidx.compose.material3.Surface(modifier = Modifier.size(40.dp), shape = androidx.compose.foundation.shape.CircleShape,
            color = if (dark) ru.lumastack.lumawork.ui.theme.AppSurfaces.card else Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(1.dp,
                if (dark) ru.lumastack.lumawork.ui.theme.AppSurfaces.border else tint.copy(alpha = .16f)),
            shadowElevation = if (dark) 3.dp else 0.dp) {
        androidx.compose.foundation.layout.Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
        Canvas(Modifier.size(22.dp, 18.dp).then(Modifier.semantics {
            contentDescription = "Открыть меню"
        })) {
            val stroke = 3.dp.toPx()
            drawLine(tint, Offset(stroke / 2, 5.dp.toPx()),
                Offset(size.width - stroke / 2, 5.dp.toPx()), stroke, StrokeCap.Round)
            drawLine(tint, Offset(stroke / 2, 13.dp.toPx()),
                Offset(13.2.dp.toPx() - stroke / 2, 13.dp.toPx()), stroke, StrokeCap.Round)
        }
        }
        }
    }
}

/** The toolbar arrow and Android Back use the same route-owned action. */
@Composable
fun AppHeaderBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
    val header = LocalAppHeader.current
    val currentOnBack by rememberUpdatedState(onBack)
    if (enabled && header != null) DisposableEffect(header) {
        val action = { currentOnBack() }
        header.onBack = action
        onDispose { if (header.onBack === action) header.onBack = null }
    }
}

@Composable
fun AppBackButton(onBack: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onBack, enabled = enabled) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
    }
}

/** Publishes route-owned navigation into the shell's existing top bar. */
@Composable
fun AppHeaderSegments(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val header = LocalAppHeader.current ?: return
    val currentTitles by rememberUpdatedState(titles)
    val currentIndex by rememberUpdatedState(selectedIndex)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentActions by rememberUpdatedState(actions)
    DisposableEffect(header) {
        val content = AppHeaderContent(
            title = { AppSegmentedControl(currentTitles, currentIndex, currentSelect) },
            actions = { currentActions() },
        )
        header.content = content
        onDispose { if (header.content === content) header.content = null }
    }
}
