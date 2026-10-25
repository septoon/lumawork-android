package ru.lumastack.lumawork.ui.fuel

import ru.lumastack.lumawork.ui.theme.AppContentHorizontalPadding
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import ru.lumastack.lumawork.domain.fuel.FuelAnalytics
import ru.lumastack.lumawork.domain.fuel.FuelMonthlyPricePoint
import ru.lumastack.lumawork.domain.fuel.FuelRecord
import ru.lumastack.lumawork.ui.theme.appScreenBackground
import ru.lumastack.lumawork.ui.theme.appTopBarColors
import ru.lumastack.lumawork.ui.theme.AppCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelAnalyticsScreen(records: List<FuelRecord>, onDismiss: () -> Unit) {
    var selectedYear by rememberSaveable { mutableStateOf<String?>(null) }
    val analytics = remember(records, selectedYear) { FuelAnalytics(records, selectedYear) }
    Scaffold(modifier = Modifier.background(appScreenBackground()), containerColor = androidx.compose.ui.graphics.Color.Transparent, topBar = {
        TopAppBar(colors = appTopBarColors(), title = { Text("Аналитика топлива") }, navigationIcon = {
            IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
        })
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 780.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = AppContentHorizontalPadding, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    AppCard(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Средняя цена литра", style = MaterialTheme.typography.titleMedium)
                            Text(if (analytics.overallPricePerLiter > 0) money(analytics.overallPricePerLiter, 2) else "—", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                            Text("${analytics.recordCount} записей · ${analytics.pricePoints.size} месяцев" + if (analytics.archiveCount > 0) " · Архив: ${analytics.archiveCount}" else "", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = selectedYear == null, onClick = { selectedYear = null }, label = { Text("Весь период") })
                        analytics.projection.years.forEach { year ->
                            FilterChip(selected = selectedYear == year, onClick = { selectedYear = year }, label = { Text(year) })
                        }
                    }
                }
                if (analytics.months.isEmpty()) {
                    item { AnalyticsPanel("Нет данных за период", "Выберите другой год.") {} }
                } else {
                    item { AnalyticsOverview(analytics) }
                    item { ConsumptionChart(analytics) }
                    item {
                        AnalyticsPanel("Цена топлива по месяцам", "Стоимость заправок месяца ÷ залитые литры") {
                            if (analytics.pricePoints.isEmpty()) Text("Нет месяцев, где одновременно указаны литры и стоимость.")
                            else PriceChart(analytics.pricePoints, selectedYear == null && analytics.projection.years.size > 1)
                        }
                    }
                    item {
                        AnalyticsPanel("Распределение расходов", "Доля каждого месяца в общей стоимости топлива") {
                            if (analytics.pricePoints.isEmpty()) Text("Нет данных о стоимости топлива.")
                            else CostChart(analytics.pricePoints)
                        }
                    }
                    item {
                        AnalyticsPanel("Детализация", "Полный расчёт цены, объёма и расхода") {
                            analytics.months.asReversed().forEachIndexed { index, month ->
                                if (index > 0) HorizontalDivider()
                                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(month.label, fontWeight = FontWeight.SemiBold)
                                    val price = if (month.totalLiters > 0) month.fuelCost / month.totalLiters else 0.0
                                    Text(if (price > 0) "${money(price, 2)}/л" else "—", color = MaterialTheme.colorScheme.primary)
                                    Text("${number(month.totalLiters)} л · ${money(month.fuelCost)} · ${number(month.totalMileage, 0)} км", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsOverview(analytics: FuelAnalytics) {
    val metrics = listOf(
        "Пройдено" to "${number(analytics.mileage, 0)} км",
        "Залито" to "${number(analytics.liters)} л",
        "Средний расход" to "${number(analytics.consumption, 1)} л/100 км",
        "Стоимость топлива" to money(analytics.fuelCost),
        "Начислено" to money(analytics.compensation),
        "Выплачено" to money(analytics.paid),
        "Вычтено" to money(analytics.deducted),
        "Осталось к выплате" to money(analytics.remaining),
    )
    AnalyticsPanel("Аналитика", "Итоги за выбранный период") {
        metrics.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { (title, value) ->
                    Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        Text(value, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsumptionChart(analytics: FuelAnalytics) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline
    val danger = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.surfaceVariant
    AnalyticsPanel("Расход по месяцам", "Фактический расход и норма, литров на 100 км") {
        analytics.months.asReversed().forEach { month ->
            val actual = if (month.totalMileage > 0) month.totalLiters / month.totalMileage * 100 else 0.0
            val actualColor = if (actual > month.approvedRate && month.approvedRate > 0) danger else primary
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                    contentDescription = "${month.label}: фактический расход ${number(actual, 1)} литров на 100 километров, норма ${number(month.approvedRate, 1)}"
                },
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(shortMonth(month.key), Modifier.width(42.dp), style = MaterialTheme.typography.labelSmall)
                Canvas(Modifier.weight(1f).height(24.dp)) {
                    val scale = analytics.maximumConsumptionScale
                    drawLine(track, Offset(0f, 6.dp.toPx()), Offset(size.width, 6.dp.toPx()), 9.dp.toPx(), StrokeCap.Round)
                    drawLine(track.copy(alpha = .6f), Offset(0f, 19.dp.toPx()), Offset(size.width, 19.dp.toPx()), 5.dp.toPx(), StrokeCap.Round)
                    if (actual > 0) drawLine(actualColor, Offset(0f, 6.dp.toPx()), Offset(maxOf(4.dp.toPx(), size.width * (actual / scale).coerceIn(0.0, 1.0).toFloat()), 6.dp.toPx()), 9.dp.toPx(), StrokeCap.Round)
                    if (month.approvedRate > 0) drawLine(muted, Offset(0f, 19.dp.toPx()), Offset(maxOf(4.dp.toPx(), size.width * (month.approvedRate / scale).coerceIn(0.0, 1.0).toFloat()), 19.dp.toPx()), 5.dp.toPx(), StrokeCap.Round)
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(40.dp)) {
                    Text(if (month.totalMileage > 0) number(actual, 1) else "—", color = actualColor, style = MaterialTheme.typography.labelSmall)
                    Text(number(month.approvedRate, 1), color = muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text("Фактически · Норма", color = muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PriceChart(points: List<FuelMonthlyPricePoint>, showYear: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Canvas(Modifier.width(maxOf(300, points.size * 62).dp).height(250.dp).semantics {
            contentDescription = points.joinToString("; ") { "${it.month.label}: ${money(it.pricePerLiter, 2)} за литр" }
        }) {
            val left = 44.dp.toPx()
            val top = 12.dp.toPx()
            val bottom = size.height - 32.dp.toPx()
            val chartHeight = bottom - top
            val maximum = maxOf(points.maxOf { it.pricePerLiter } * 1.15, 1.0)
            val step = (size.width - left) / points.size
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = labelColor.toArgb(); textSize = 10.dp.toPx() }
            for (i in 0..4) {
                val y = bottom - chartHeight * i / 4
                drawLine(grid, Offset(left, y), Offset(size.width, y), 1.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText("${number(maximum * i / 4, 0)} ₽", 0f, y, paint)
            }
            var previous: Offset? = null
            points.forEachIndexed { index, point ->
                val x = left + step * (index + .5f)
                val y = bottom - chartHeight * (point.pricePerLiter / maximum).toFloat()
                drawRoundRect(color, Offset(x - step * .25f, y), Size(step * .5f, bottom - y), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                val current = Offset(x, y)
                previous?.let { drawLine(lineColor, it, current, 2.5.dp.toPx(), StrokeCap.Round) }
                drawCircle(lineColor, 3.5.dp.toPx(), current)
                previous = current
                paint.textAlign = Paint.Align.CENTER
                val label = shortMonth(point.month.key) + if (showYear) " ${point.month.key.take(4).takeLast(2)}" else ""
                drawContext.canvas.nativeCanvas.drawText(label, x, size.height - 8.dp.toPx(), paint)
                paint.textAlign = Paint.Align.LEFT
            }
        }
    }
}

@Composable
private fun CostChart(points: List<FuelMonthlyPricePoint>) {
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary, Color(0xFF3C8977), Color(0xFFC78A41), Color(0xFF8D71B5))
    val total = points.sumOf(FuelMonthlyPricePoint::cost)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(190.dp).semantics { contentDescription = "Стоимость топлива по месяцам: " + points.joinToString { "${it.month.label} ${money(it.cost)}" } }) {
            var start = -90f
            val inset = 20.dp.toPx()
            points.forEachIndexed { index, point ->
                val sweep = (point.cost / total * 360).toFloat()
                drawArc(colors[index % colors.size], start + minOf(1f, sweep / 4), maxOf(0f, sweep - minOf(2f, sweep / 2)), false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(32.dp.toPx()))
                start += sweep
            }
        }
    }
    points.forEachIndexed { index, point ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).background(colors[index % colors.size], RoundedCornerShape(3.dp)))
            Text(point.month.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text("${money(point.cost)} · ${number(point.cost / total * 100, 1)}%", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AnalyticsPanel(title: String, caption: String, content: @Composable () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

private val analyticsLocale = Locale.forLanguageTag("ru-RU")
private fun number(value: Double, fractionDigits: Int = 2): String = NumberFormat.getNumberInstance(analyticsLocale).apply { maximumFractionDigits = fractionDigits }.format(value)
private fun money(value: Double, fractionDigits: Int = 0): String = "${number(value, fractionDigits)} ₽"
private fun shortMonth(key: String): String = runCatching {
    YearMonth.parse(key).month.getDisplayName(TextStyle.SHORT_STANDALONE, analyticsLocale).take(3).replaceFirstChar { it.titlecase(analyticsLocale) }
}.getOrDefault(key)
