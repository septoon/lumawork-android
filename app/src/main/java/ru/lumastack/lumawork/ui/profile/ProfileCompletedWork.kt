package ru.lumastack.lumawork.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.lumastack.lumawork.domain.profile.ProfileCompletedWorkStatistics
import ru.lumastack.lumawork.domain.profile.ProfileWorkCategory
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

@Composable
internal fun ProfileCompletedWorkSection(
    records: List<SimpleOneRequest>,
    isLoading: Boolean,
    hasArchive: Boolean,
    modifier: Modifier = Modifier,
) {
    val statistics = remember(records) { ProfileCompletedWorkStatistics(records) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Выполненные работы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Только успешно закрытые заявки", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isLoading) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Загружаем статистику", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val fontScale = LocalDensity.current.fontScale
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (fontScale >= 1.5f || maxWidth < 288.dp) 1 else 2
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    statistics.categories.chunked(columns).forEach { summaries ->
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            summaries.forEach { summary -> ProfileWorkCategoryCard(summary, Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (!hasArchive) {
                Text("Загрузите закрытые заявки в разделе «Заявки».", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProfileWorkCategoryCard(summary: ProfileCompletedWorkStatistics.CategorySummary, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    val tint = summary.category.tint()
    val accessibilityValue = (listOf("Всего ${summary.total}") + summary.operations.map {
        "${it.title}: ${summary.count(it)}"
    }).joinToString(", ")
    Surface(
        onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
        modifier = modifier.fillMaxHeight().semantics {
            contentDescription = summary.category.title
            stateDescription = accessibilityValue
            role = Role.Button
        },
        shape = RoundedCornerShape(14.dp),
        color = tint.copy(alpha = .08f),
        border = BorderStroke(1.dp, tint.copy(alpha = .16f)),
    ) {
        Column(Modifier.padding(10.dp).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                ProfileWorkCategoryIcon(summary.category, tint)
                Text(summary.category.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(summary.total.toString(), style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold, color = tint)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                summary.operations.forEach { operation ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(operation.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(summary.count(operation).toString(), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileWorkCategory.tint(): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return when (this) {
        ProfileWorkCategory.Portable -> if (dark) Color(0xFF91C4FF) else Color(0xFF245DA8)
        ProfileWorkCategory.Stationary -> if (dark) Color(0xFFD1A9FF) else Color(0xFF7742A6)
        ProfileWorkCategory.Integration -> if (dark) Color(0xFF8CBA9E) else Color(0xFF316347)
        ProfileWorkCategory.Fiscal -> if (dark) Color(0xFFFFBD87) else Color(0xFF9C5418)
    }
}

@Composable
private fun ProfileWorkCategoryIcon(category: ProfileWorkCategory, tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val unit = size.width / 24
        val stroke = Stroke(1.7f * unit)
        fun box(x: Float, y: Float, width: Float, height: Float, radius: Float = 1.5f) = drawRoundRect(
            tint, Offset(x * unit, y * unit), Size(width * unit, height * unit), CornerRadius(radius * unit), style = stroke,
        )
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit), strokeWidth = stroke.width)
        when (category) {
            ProfileWorkCategory.Portable -> { box(6f, 2f, 12f, 20f, 2f); line(10f, 19f, 14f, 19f) }
            ProfileWorkCategory.Stationary -> {
                box(2f, 3f, 20f, 14f); line(12f, 17f, 12f, 21f); line(7f, 21f, 17f, 21f)
            }
            ProfileWorkCategory.Integration -> rotate(-40f) {
                box(1f, 8f, 12f, 8f, 4f); box(11f, 8f, 12f, 8f, 4f); line(8f, 12f, 16f, 12f)
            }
            ProfileWorkCategory.Fiscal -> {
                box(3f, 11f, 18f, 11f); box(6f, 2f, 12f, 9f); line(3f, 18f, 21f, 18f)
                line(7f, 14.5f, 9f, 14.5f); line(12f, 14.5f, 14f, 14.5f); line(17f, 14.5f, 19f, 14.5f)
            }
        }
    }
}
