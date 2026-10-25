package ru.lumastack.lumawork.ui.backpack

import ru.lumastack.lumawork.ui.theme.AppContentHorizontalPadding
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.lumastack.lumawork.data.backpack.BackpackImageRepository
import ru.lumastack.lumawork.domain.backpack.OfficeEquipmentItem
import ru.lumastack.lumawork.domain.backpack.OfficeEquipmentSection
import ru.lumastack.lumawork.domain.backpack.OfficeEquipmentField
import ru.lumastack.lumawork.domain.backpack.SerialBarcode
import ru.lumastack.lumawork.domain.backpack.photoFor
import ru.lumastack.lumawork.ui.theme.AppTextField
import ru.lumastack.lumawork.ui.shell.AppBackButton
import ru.lumastack.lumawork.ui.theme.AppSurfaces
import ru.lumastack.lumawork.ui.theme.AppCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OfficeEquipmentSheet(
    state: BackpackUiState, images: BackpackImageRepository, onDismiss: () -> Unit, onQuery: (String) -> Unit,
    onRefresh: () -> Unit, onSelect: (OfficeEquipmentItem) -> Unit, onBack: () -> Unit, onSerial: (String) -> Unit,
) {
    val selected = state.selectedOfficeItem
    BackHandler(enabled = selected != null, onBack = onBack)
    ModalBottomSheet(containerColor = AppSurfaces.panel, tonalElevation = 0.dp, scrimColor = AppSurfaces.scrim, onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = 1000.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) AppBackButton(onBack)
                Text(if (selected == null) "Моё оборудование" else "Оборудование", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (selected == null) TextButton(onClick = onRefresh, enabled = !state.isLoadingOffice && state.isAuthorized) { Text("Обновить") }
                TextButton(onClick = onDismiss) { Text("Готово") }
            }
            if (selected == null) {
                AppTextField(state.officeQuery, onQuery, "Название, модель, серийный номер", search = true, singleLine = true, modifier = Modifier.padding(16.dp).fillMaxWidth())
                val items = remember(state.snapshot?.officeEquipment, state.officeQuery) { state.snapshot?.officeEquipment.orEmpty().filter { it.matches(state.officeQuery) } }
                LazyVerticalGrid(columns = GridCells.Adaptive(154.dp), contentPadding = PaddingValues(horizontal = AppContentHorizontalPadding, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${items.size} из ${state.snapshot?.officeEquipment?.size ?: 0}")
                            if (state.isLoadingOffice) LinearProgressIndicator(Modifier.fillMaxWidth())
                            state.officeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (items.isEmpty() && !state.isLoadingOffice) Text(if (state.officeQuery.isBlank()) "Оборудование, закреплённое за вами, не найдено." else "Ничего не найдено. Измените поисковый запрос.")
                        }
                    }
                    items(items, key = OfficeEquipmentItem::id) { item ->
                        AppCard(onClick = { onSelect(item) }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                BackpackImage(state.officePhotoCatalog.photoFor(item.name)?.resolvedUrl ?: state.officePhotoCatalog.photoFor("${item.vendor} ${item.model}")?.resolvedUrl,
                                    images, Modifier.fillMaxWidth().aspectRatio(1.25f))
                                Text(item.name, fontWeight = FontWeight.SemiBold)
                                if (item.serialNumber.isNotBlank()) Text(item.serialNumber, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Adaptive(260.dp), contentPadding = PaddingValues(horizontal = AppContentHorizontalPadding, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            BackpackImage(state.officePhotoCatalog.photoFor(selected.name)?.resolvedUrl ?: state.officePhotoCatalog.photoFor("${selected.vendor} ${selected.model}")?.resolvedUrl,
                                images, Modifier.fillMaxWidth().height(200.dp))
                            Text(selected.name, style = MaterialTheme.typography.headlineSmall)
                            if (state.isLoadingOfficeDetails) LinearProgressIndicator(Modifier.fillMaxWidth())
                            state.officeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    val sections = selected.detailSections.ifEmpty {
                        val fields = linkedMapOf("Серийный номер" to selected.serialNumber, "Вендор" to selected.vendor, "Модель" to selected.model,
                            "Номер" to selected.number, "Месторасположение" to selected.location, "Владелец" to selected.owner, "Получено" to selected.receivedAt,
                            "Дополнительная информация" to selected.additionalInformation, "Описание" to selected.description)
                        listOf(OfficeEquipmentSection("main", "Информация", fields.filterValues(String::isNotBlank).map { (title, value) -> OfficeEquipmentField(title, title, value) }))
                    }
                    sections.forEach { section ->
                        item(key = "section|${section.id}", span = { GridItemSpan(maxLineSpan) }) { Text(section.title, style = MaterialTheme.typography.titleMedium) }
                        items(section.fields, key = { "${section.id}|${it.id}" }) { field ->
                            val isSerial = field.title.lowercase().startsWith("серийный номер") && SerialBarcode.serial(field.value) != null
                            AppCard(Modifier.fillMaxWidth().clickable(enabled = isSerial) { onSerial(field.value) }) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(field.title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                    if (isSerial) Text(field.value, fontWeight = FontWeight.SemiBold)
                                    else SelectionContainer { Text(field.value, fontWeight = FontWeight.SemiBold) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
