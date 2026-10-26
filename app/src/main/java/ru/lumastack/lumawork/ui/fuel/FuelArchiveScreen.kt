package ru.lumastack.lumawork.ui.fuel

import ru.lumastack.lumawork.ui.theme.AppContentHorizontalPadding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.lumastack.lumawork.data.fuel.FuelReceipt
import ru.lumastack.lumawork.domain.fuel.*
import ru.lumastack.lumawork.ui.theme.appScreenBackground
import ru.lumastack.lumawork.ui.theme.appTopBarColors
import androidx.compose.foundation.background
import ru.lumastack.lumawork.ui.theme.AppPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FuelArchiveScreen(state: FuelUiState, receipts: List<FuelReceipt>, receiptsLoading: Boolean, onOpenReceipts: (String) -> Unit, onDismiss: () -> Unit, onEdit: (FuelRecord) -> Unit, onDelete: (FuelRecord) -> Unit) {
    val records = remember(state.snapshot.records) { state.snapshot.records.filter(FuelArchivePolicy::isArchived).sortedWith(compareByDescending(FuelRecord::date).thenByDescending(FuelRecord::stableId)) }
    val summary = remember(records) { FuelSummaryCalculator.build(records) }
    val groups = remember(records) { records.groupBy(FuelRecord::resolvedMonthKey).toSortedMap(reverseOrder()) }
    Scaffold(modifier = Modifier.background(appScreenBackground()), containerColor = androidx.compose.ui.graphics.Color.Transparent, topBar = { TopAppBar(colors = appTopBarColors(), title = { Text("Архив топлива") }, navigationIcon = {
        IconButton(onClick = onDismiss, enabled = !state.isSaving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = AppContentHorizontalPadding, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            state.errorMessage?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            state.notice?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            item {
                AppPanel {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(FuelArchivePolicy.ARCHIVED_RECORDS_TITLE, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("На основном экране остаются записи с 13 мая 2026.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (summary.hasData) {
                            Text("Пробег: ${number(summary.totals.totalMileage)} км")
                            Text("Заправлено: ${number(summary.totals.totalLiters)} л")
                            Text("Сумма: ${rubles(summary.totals.totalFuelCost)}")
                        } else Text("Архивных записей пока нет.")
                    }
                }
            }
            groups.forEach { (month, entries) -> item(key = month) {
                AppPanel {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(summary.monthly.firstOrNull { it.key == month }?.label ?: month, style = MaterialTheme.typography.titleMedium)
                        FuelReceiptsButton(receipts.filter { it.month == month }, receiptsLoading) { onOpenReceipts(month) }
                        entries.forEachIndexed { index, record ->
                            if (index > 0) HorizontalDivider()
                            FuelRecordRow(record, enabled = !state.isSaving && !state.isOffline, onEdit = { onEdit(record) }, onDelete = { onDelete(record) })
                            record.comment?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            } }
        }
    }
}
