package ru.lumastack.lumawork.ui.tools

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.lumastack.lumawork.R
import ru.lumastack.lumawork.ui.shell.AppHeaderContent
import ru.lumastack.lumawork.ui.shell.LocalAppHeader
import ru.lumastack.lumawork.ui.theme.LocalAppAppearance
import ru.lumastack.lumawork.ui.theme.LocalAppDarkTheme

data class ToolsPalette(
    val ink: Color, val muted: Color, val orange: Color, val mint: Color, val blue: Color,
    val violet: Color, val card: Color, val panel: Color, val border: Color, val success: Color,
)
internal object ToolsColors {
    val current: ToolsPalette @Composable get() = if (LocalAppDarkTheme.current) ToolsPalette(
        Color(0xFFEDF3FF), Color(0xFFB5C5DB), Color(0xFFFFAB50), Color(0xFF69D6B8), Color(0xFF82BAFF),
        Color(0xFFB5A6FF), Color(0xFF192638), Color(0xFF223349), Color(0xFF34485E), Color(0xFF72D99A),
    ) else ToolsPalette(
        Color(0xFF102548), Color(0xFF465F80), Color(0xFFF78220), Color(0xFF138B78), Color(0xFF096BD3),
        Color(0xFF6944C8), Color(0xFFFCFEFF), Color(0xFFF0F5FB), Color(0xFFDEE7F2), Color(0xFF27A85B),
    )
}
internal object ToolsIcons {
    val terminal: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.tools_terminal)
    val usb: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.tools_usb)
    val system: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.tools_system)
    val folder: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_nav_folder_open)
    val book: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_nav_menu_book)
}

@Composable internal fun toolsScreenBackground(): Brush {
    val dark = LocalAppDarkTheme.current
    val colored = LocalAppAppearance.current.backgroundEnabled
    val plain = MaterialTheme.colorScheme.background
    return remember(dark, colored, plain) {
        Brush.linearGradient(if (!colored) listOf(plain, plain)
        else if (dark) listOf(Color(0xFF111E30), Color(0xFF142B34), Color(0xFF142238))
        else if (colored) listOf(Color(0xFFCBE9FF), Color(0xFFEAF9F6), Color(0xFFEBF5FF))
        else listOf(Color(0xFFF2F6FC), Color(0xFFF2F6FC)))
    }
}

@Composable internal fun ToolsTheme(content: @Composable () -> Unit) {
    val p = ToolsColors.current
    val colors = MaterialTheme.colorScheme.copy(primary = p.orange, onPrimary = Color.White,
        secondary = p.mint, tertiary = p.blue, onSurface = p.ink, onSurfaceVariant = p.muted,
        surface = p.card, surfaceVariant = p.panel, outline = p.border, onBackground = p.ink)
    MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography, content = content)
}

@Composable internal fun ToolsStepCard(number: Int, title: String, accent: Color,
    decoration: ImageVector? = null, content: @Composable ColumnScope.() -> Unit,
) {
    val p = ToolsColors.current
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = p.card,
        contentColor = p.ink, shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(accent.copy(alpha = .13f), CircleShape), contentAlignment = Alignment.Center) {
                    Text(number.toString(), color = accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                decoration?.let { Icon(it, null, Modifier.size(34.dp), tint = accent.copy(alpha = .7f)) }
            }
            content()
        }
    }
}
@Composable internal fun ToolsSubpanel(accent: Color, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = ToolsColors.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(Brush.linearGradient(listOf(accent.copy(alpha = .08f), p.panel.copy(alpha = .65f))))
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable internal fun ToolsActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, icon: ImageVector? = null, primary: Boolean = false,
    accent: Color = ToolsColors.current.blue,
) {
    val p = ToolsColors.current
    val shape = RoundedCornerShape(16.dp)
    val fill: Brush = when {
        !enabled -> SolidColor(p.border.copy(alpha = .55f))
        primary -> Brush.horizontalGradient(listOf(Color(0xFFFFAD27), Color(0xFFF17828)))
        else -> SolidColor(accent.copy(alpha = .09f))
    }
    Button(onClick, modifier.heightIn(min = 44.dp).clip(shape).background(fill), enabled,
        shape = shape, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent,
            contentColor = if (primary) Color.White else accent, disabledContainerColor = Color.Transparent,
            disabledContentColor = p.muted.copy(alpha = .6f)),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        icon?.let { Icon(it, null, Modifier.size(21.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
    }
}
@Composable internal fun ToolsChoiceChip(label: String, selected: Boolean, onClick: () -> Unit,
    enabled: Boolean = true, icon: ImageVector? = null,
) {
    val p = ToolsColors.current
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(18.dp)
    val ink by animateColorAsState(if (selected) Color.White else p.muted, tween(180), label = "tools choice ink")
    val fill = if (selected) Brush.horizontalGradient(listOf(Color(0xFFFFAA27), Color(0xFFF17929))) else SolidColor(p.panel)
    Row(Modifier.heightIn(min = 44.dp).clip(shape).background(fill)
        .border(1.dp, if (selected) Color.Transparent else p.border, shape)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick()
        }).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (icon ?: Icons.Outlined.CheckCircle.takeIf { selected })?.let {
            Icon(it, null, Modifier.size(20.dp), tint = ink.copy(alpha = if (enabled) 1f else .5f))
        }
        Text(label, color = ink.copy(alpha = if (enabled) 1f else .5f), fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge)
    }
}
@Composable internal fun ToolsZipArtwork(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.tools_zip_art), null, modifier)
}

@Composable internal fun ToolsHeaderSegments(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val header = LocalAppHeader.current ?: return
    val currentTitles by rememberUpdatedState(titles)
    val currentSelected by rememberUpdatedState(selected)
    val currentSelect by rememberUpdatedState(onSelect)
    val backdrop = toolsScreenBackground()
    DisposableEffect(header, backdrop) {
        val entry = AppHeaderContent(title = { ToolsTabs(currentTitles, currentSelected, currentSelect) }, actions = {}, background = backdrop)
        header.content = entry
        onDispose { if (header.content === entry) header.content = null }
    }
}
@Composable private fun ToolsTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val compact = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() < 600.dp }
    val p = ToolsColors.current
    val haptics = LocalHapticFeedback.current
    Surface(shape = RoundedCornerShape(28.dp), color = p.card.copy(alpha = .87f), shadowElevation = 4.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (LocalAppDarkTheme.current) .1f else .85f))) {
        Row(Modifier.widthIn(max = 560.dp).horizontalScroll(rememberScrollState()).padding(4.dp).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically) {
            titles.forEachIndexed { index, title ->
                val chosen = index == selected
                val fill = if (chosen) Brush.horizontalGradient(listOf(Color(0xFFFFA729), Color(0xFFF27E2B))) else SolidColor(Color.Transparent)
                Box(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(23.dp)).background(fill)
                    .selectable(chosen, role = Role.Tab, onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(index) })
                    .padding(horizontal = if (compact) 7.dp else 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(title, color = if (chosen) Color.White else p.muted, style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
