package ru.lumastack.lumawork.ui.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomLight
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopLight

data class RequestAnalytics(
    val active: Int, val closed: Int, val overdue: Int, val dueToday: Int,
    val byStatus: List<Pair<String, Int>>, val byMonth: List<Pair<String, Int>>, val topGroups: List<Pair<String, Int>>,
)

fun buildRequestAnalytics(state: SimpleOneUiState, today: LocalDate = LocalDate.now()): RequestAnalytics {
    val active = state.snapshot.activeRequests
    val closed = state.closedArchive.requests
    return RequestAnalytics(
        active = active.size,
        closed = closed.size,
        overdue = active.count { it.isOverdue(today.atStartOfDay()) },
        dueToday = active.count { it.isDueToday(today) },
        byStatus = active.groupingBy { it.displayState.ifBlank { "Без статуса" } }.eachCount().entries.sortedByDescending { it.value }.take(8).map { it.key to it.value },
        byMonth = closed.groupingBy { requestMonth(it) }.eachCount().entries.filter { it.key.isNotEmpty() }.sortedBy { it.key }.takeLast(12).map { it.key to it.value },
        topGroups = (active + closed).groupingBy { it.assignmentGroup.ifBlank { "Без группы" } }.eachCount().entries.sortedByDescending { it.value }.take(6).map { it.key to it.value },
    )
}

@Composable
fun AnalyticsScreen(state: SimpleOneUiState, widthClass: AppWidthClass, modifier: Modifier = Modifier) {
    val analytics = remember(state.snapshot, state.closedArchive) { buildRequestAnalytics(state) }
    val dark = MaterialTheme.colorScheme.background == LumaBackgroundTopDark
    val background = remember(dark) { Brush.verticalGradient(if (dark) listOf(LumaBackgroundTopDark, LumaBackgroundBottomDark) else listOf(LumaBackgroundTopLight, LumaBackgroundBottomLight)) }
    Box(modifier.fillMaxSize().background(background), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 1120.dp).fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Text("Сводка заявок", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            item {
                if (widthClass == AppWidthClass.Compact) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricRow(analytics)
                } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { MetricCards(analytics) }
            }
            if (state.closedArchive.requests.isEmpty()) item { Notice("Для полной аналитики откройте вкладку «Закрытые SO» в заявках и обновите архив.") }
            item {
                if (widthClass == AppWidthClass.Compact) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    AnalyticsPanel("Статусы активных", analytics.byStatus, MaterialTheme.colorScheme.primary)
                    AnalyticsPanel("Закрыто по месяцам", analytics.byMonth, MaterialTheme.colorScheme.secondary)
                } else Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.weight(1f)) { AnalyticsPanel("Статусы активных", analytics.byStatus, MaterialTheme.colorScheme.primary) }
                    Box(Modifier.weight(1f)) { AnalyticsPanel("Закрыто по месяцам", analytics.byMonth, MaterialTheme.colorScheme.secondary) }
                }
            }
            item { AnalyticsPanel("Группы назначения", analytics.topGroups, MaterialTheme.colorScheme.tertiary) }
        }
    }
}

@Composable private fun MetricRow(v: RequestAnalytics) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Metric("Активные",v.active,Modifier.weight(1f));Metric("Закрытые",v.closed,Modifier.weight(1f))};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Metric("Просрочено",v.overdue,Modifier.weight(1f));Metric("Сегодня",v.dueToday,Modifier.weight(1f))} } }
@Composable private fun RowScope.MetricCards(v:RequestAnalytics){Metric("Активные",v.active,Modifier.weight(1f));Metric("Закрытые",v.closed,Modifier.weight(1f));Metric("Просрочено",v.overdue,Modifier.weight(1f));Metric("Сегодня",v.dueToday,Modifier.weight(1f))}
@Composable private fun Metric(title:String,value:Int,modifier:Modifier){Surface(modifier,shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.97f)){Column(Modifier.padding(15.dp)){Text(title,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value.toString(),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)}}}
@Composable private fun AnalyticsPanel(title:String,values:List<Pair<String,Int>>,color:Color){Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.97f)){Column(Modifier.fillMaxWidth().padding(17.dp),verticalArrangement=Arrangement.spacedBy(11.dp)){Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);if(values.isEmpty())Text("Недостаточно данных",color=MaterialTheme.colorScheme.onSurfaceVariant)else{val max=values.maxOf{it.second}.coerceAtLeast(1);values.forEach{(label,value)->Column{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,maxLines=1,modifier=Modifier.weight(1f));Text(value.toString(),fontWeight=FontWeight.Bold)};Box(Modifier.fillMaxWidth().height(7.dp).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(8.dp))){Box(Modifier.fillMaxWidth(value.toFloat()/max).height(7.dp).background(color,RoundedCornerShape(8.dp)))}}}}}}}
@Composable private fun Notice(value:String){Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(18.dp)){Text(value,modifier=Modifier.padding(15.dp),color=MaterialTheme.colorScheme.onSecondaryContainer)}}
private fun requestMonth(request:SimpleOneRequest):String { val value=request.resolvedAt.ifBlank{request.closedAt}.trim();return when { value.length>=7&&value[4]=='-' -> value.take(7);value.length>=10&&value[2]=='.' -> value.substring(6,10)+"-"+value.substring(3,5);else -> "" } }
