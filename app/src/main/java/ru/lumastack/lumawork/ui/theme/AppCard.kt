package ru.lumastack.lumawork.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** iOS AppCard: translucent fill, 28 pt corners, 1 pt border and a 12 pt shadow at y = 7. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    colors: CardColors = CardDefaults.cardColors(containerColor = AppSurfaces.card,
        contentColor = MaterialTheme.colorScheme.onSurface),
    border: BorderStroke? = BorderStroke(1.dp, AppSurfaces.border),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.appCardShadow(shape), shape = shape, colors = colors,
        elevation = CardDefaults.cardElevation(0.dp), border = border ?: BorderStroke(1.dp, AppSurfaces.border), content = content)
}

@Composable
fun AppCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(28.dp),
    colors: CardColors = CardDefaults.cardColors(containerColor = AppSurfaces.card,
        contentColor = MaterialTheme.colorScheme.onSurface),
    border: BorderStroke? = BorderStroke(1.dp, AppSurfaces.border),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(onClick = onClick, modifier = modifier.appCardShadow(shape), enabled = enabled,
        shape = shape, colors = colors, border = border ?: BorderStroke(1.dp, AppSurfaces.border),
        elevation = CardDefaults.cardElevation(0.dp), content = content)
}

/** Same surface for cards whose content is a Row or Box rather than a Column. */
@Composable
fun AppPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    color: Color = AppSurfaces.card,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    border: BorderStroke? = BorderStroke(1.dp, AppSurfaces.border),
    content: @Composable () -> Unit,
) {
    Surface(modifier.appCardShadow(shape), shape, color, contentColor,
        border = border ?: BorderStroke(1.dp, AppSurfaces.border), content = content)
}

@Composable
fun AppPanel(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(28.dp),
    color: Color = AppSurfaces.card,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    border: BorderStroke? = BorderStroke(1.dp, AppSurfaces.border),
    content: @Composable () -> Unit,
) {
    Surface(onClick = onClick, modifier = modifier.appCardShadow(shape), enabled = enabled,
        shape = shape, color = color, contentColor = contentColor,
        border = border ?: BorderStroke(1.dp, AppSurfaces.border), content = content)
}

@Composable
private fun Modifier.appCardShadow(shape: Shape): Modifier = dropShadow(shape,
    Shadow(radius = 12.dp, color = AppSurfaces.shadow, offset = DpOffset(0.dp, 7.dp)))
