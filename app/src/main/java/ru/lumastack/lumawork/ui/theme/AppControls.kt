package ru.lumastack.lumawork.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color

/** The same compact label and rounded, lightly filled input used by the iOS forms. */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    showLabel: Boolean = true,
    search: Boolean = false,
    placeholder: String = label,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    shape: Shape = RoundedCornerShape(16.dp),
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .45f)
    val border = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .65f)
        else AppSurfaces.border

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showLabel && !search) Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            textStyle = textStyle.copy(color = ink),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .semantics { contentDescription = label },
            decorationBox = { innerTextField ->
                Row(
                    Modifier.fillMaxWidth().clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .66f))
                        .border(1.dp, border, shape)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (search) Icon(Icons.Outlined.Search, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    else leadingIcon?.invoke()
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f))
                        innerTextField()
                    }
                    trailingIcon?.invoke()
                }
            },
        )
    }
}

@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(18.dp),
    colors: ButtonColors? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val fill = if (colors == null) Modifier.background(AppSurfaces.buttonFill, shape)
        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) .18f else .08f), shape)
        else Modifier
    androidx.compose.material3.Button(onClick, modifier.heightIn(min = 44.dp).then(fill), enabled,
        shape = shape, colors = colors ?: ButtonDefaults.buttonColors(
            containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)),
        contentPadding = contentPadding, content = content)
}

@Composable
fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(18.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    androidx.compose.material3.OutlinedButton(onClick, modifier.heightIn(min = 44.dp), enabled,
        shape = shape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) .18f else .08f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = AppSurfaces.subpanel,
            contentColor = MaterialTheme.colorScheme.primary),
        contentPadding = contentPadding, content = content)
}

@Composable
fun AppSegmentedControl(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isSegmentEnabled: (Int) -> Boolean = { true },
    onLongClick: ((Int) -> Unit)? = null,
) {
    if (titles.isEmpty()) return
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val labels = titles.map { measurer.measure(it, style, maxLines = 1) }
    val widths = labels.map { with(density) { it.size.width.toDp() + 24.dp }.coerceAtLeast(48.dp) }
    val capsuleHeight = with(density) { labels.maxOf { it.size.height }.toDp() + 12.dp }.coerceAtLeast(32.dp)
    val controlHeight = (capsuleHeight + 8.dp).coerceAtLeast(48.dp)
    val selected = selectedIndex.coerceIn(titles.indices)
    val start = widths.take(selected).fold(0.dp) { sum, width -> sum + width }
    val contentWidth = widths.fold(4.dp) { sum, width -> sum + width }
    val indicatorStart by animateDpAsState(start, tween(220), label = "segment position")
    val indicatorWidth by animateDpAsState(widths[selected], tween(220), label = "segment width")
    val scroll = rememberScrollState()
    val capsule = RoundedCornerShape(8.dp)
    val segmentShape = RoundedCornerShape(6.dp)
    val selectedFill = if (LocalAppDarkTheme.current) MaterialTheme.colorScheme.onSurface.copy(alpha = .16f)
        else MaterialTheme.colorScheme.surface.copy(alpha = .96f)

    BoxWithConstraints(modifier.widthIn(max = 560.dp)) {
        val viewportWidth = contentWidth.coerceAtMost(maxWidth)
        LaunchedEffect(selected, viewportWidth, contentWidth) {
            // Observe the measured range too, so restored selections are visible on first layout.
            snapshotFlow { scroll.maxValue }.collectLatest {
                val target = with(density) { (start + 2.dp - (viewportWidth - widths[selected]) / 2).roundToPx() }
                scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue), tween(220))
            }
        }
        Box(Modifier.width(viewportWidth).height(controlHeight).selectableGroup()) {
            Box(Modifier.fillMaxWidth().height(capsuleHeight).align(Alignment.Center)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .08f), capsule)
                .border(.5.dp, AppSurfaces.border, capsule))
            Box(Modifier.horizontalScroll(scroll).fillMaxHeight()) {
                Box(Modifier.width(contentWidth).fillMaxHeight()) {
                    Box(Modifier.offset { IntOffset(with(density) { (indicatorStart + 2.dp).roundToPx() }, with(density) { ((controlHeight - capsuleHeight) / 2 + 2.dp).roundToPx() }) }
                        .width(indicatorWidth).height(capsuleHeight - 4.dp)
                        .background(selectedFill, segmentShape))
                    Row(Modifier.padding(horizontal = 2.dp)) {
                        titles.forEachIndexed { index, title ->
                            val enabled = isSegmentEnabled(index)
                            val select = {
                                if (index != selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(index)
                            }
                            val interaction = if (onLongClick == null) Modifier.selectable(
                                selected = index == selected, enabled = enabled, role = Role.Tab, onClick = select,
                            ) else Modifier.combinedClickable(enabled = enabled, role = Role.Tab,
                                onClick = select, onLongClick = { onLongClick(index) })
                                .semantics { this.selected = index == selected }
                            Box(Modifier.width(widths[index]).height(controlHeight).clip(segmentShape)
                                .then(interaction), contentAlignment = Alignment.Center) {
                                Text(title, style = style, maxLines = 1, softWrap = false,
                                    color = (if (index == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                        .copy(alpha = if (enabled) 1f else .38f))
                            }
                        }
                    }
                }
            }
        }
    }
}
