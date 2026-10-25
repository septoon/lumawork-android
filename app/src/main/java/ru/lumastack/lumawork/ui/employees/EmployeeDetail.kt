package ru.lumastack.lumawork.ui.employees

import ru.lumastack.lumawork.ui.theme.AppContentHorizontalPadding
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.lumastack.lumawork.domain.employees.*
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton
import ru.lumastack.lumawork.ui.theme.AppSurfaces
import ru.lumastack.lumawork.ui.theme.AppCard

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EmployeeDetail(
    employee: Employee, isLoading: Boolean, errorMessage: String?,
    onRefresh: () -> Unit, canRefresh: Boolean, onActionError: (String) -> Unit,
    modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    var sectionId by rememberSaveable(employee.sysId) { mutableStateOf<String?>(null) }
    employee.detailSections.firstOrNull { it.id == sectionId }?.let { section ->
        ModalBottomSheet(containerColor = AppSurfaces.panel, tonalElevation = 0.dp, scrimColor = AppSurfaces.scrim, onDismissRequest = { sectionId = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetMaxWidth = 800.dp) {
            LazyColumn(contentPadding = PaddingValues(horizontal = AppContentHorizontalPadding, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("ДОСЬЕ СОТРУДНИКА", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            Text(section.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("${section.fields.size} ${employeeCountWord(section.fields.size, "поле", "поля", "полей")}")
                        }
                        TextButton(onClick = { sectionId = null }) { Text("Готово") }
                    }
                }
                items(section.fields.withIndex().toList(), key = { it.value.id }) { (index, field) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text((index + 1).toString().padStart(2, '0'), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(field.title.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            SelectionContainer { Text(field.value) }
                            HorizontalDivider(Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(horizontal = AppContentHorizontalPadding, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "toolbar") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = canRefresh && !isLoading) { Text("Обновить") }
            }
        }
        item(key = "hero") {
            AppCard {
                Column(Modifier.fillMaxWidth().background(AppSurfaces.accent).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                        Surface(color = Color.White.copy(alpha = .18f), shape = RoundedCornerShape(20.dp)) {
                            Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) { Text(employee.initials, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(employee.fullName, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(employee.roleText, color = Color.White.copy(alpha = .8f))
                            if (employee.login.isNotBlank()) Text(employee.login, color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    employee.tenureText()?.let { tenure ->
                        Surface(color = Color.White.copy(alpha = .12f), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("СТАЖ", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
                                Text(tenure, color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    val badges = listOfNotNull(employee.address?.title, when (employee.isActive) { true -> "Действующий"; false -> "Уволен"; null -> null }, "Доступ заблокирован".takeIf { employee.isLocked == true })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        badges.forEach { badge -> Surface(color = Color.White.copy(alpha = .14f), shape = RoundedCornerShape(20.dp)) {
                            Text(badge, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        } }
                    }
                }
            }
        }
        if (employee.phone.isNotBlank() || employee.email.isNotBlank()) item(key = "actions") {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                if (employee.phone.isNotBlank()) AppButton(onClick = {
                    val phone = employee.phone.filter { it.isDigit() || it == '+' }
                    if (phone.isNotBlank()) runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))) }
                        .onFailure { onActionError("Не удалось открыть приложение для звонка.") }
                }, modifier = Modifier.weight(1f).widthIn(min = 130.dp)) {
                    Column(Modifier.fillMaxWidth()) { Text("Позвонить"); Text(employee.phone, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (employee.email.isNotBlank()) AppOutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", employee.email.trim(), null))) }
                        .onFailure { onActionError("Не удалось открыть почтовое приложение.") }
                }, modifier = Modifier.weight(1f).widthIn(min = 130.dp)) {
                    Column(Modifier.fillMaxWidth()) { Text("Написать"); Text(employee.email, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        if (isLoading) item(key = "loading") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("Загружаю полную информацию", style = MaterialTheme.typography.bodySmall)
            }
        }
        errorMessage?.let { error -> item(key = "error") { Text(error, color = MaterialTheme.colorScheme.error) } }
        if (employee.detailSections.isNotEmpty()) item(key = "section-title") {
            Row(Modifier.fillMaxWidth()) {
                Text("РАЗДЕЛЫ ИНФОРМАЦИИ", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text(employee.detailSections.size.toString(), color = MaterialTheme.colorScheme.primary)
            }
        }
        items(employee.detailSections, key = EmployeeSection::id) { section ->
            AppCard(onClick = { sectionId = section.id }) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(section.title, fontWeight = FontWeight.SemiBold)
                        Text(section.fields.take(2).joinToString(" · ") { it.title }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${section.fields.size}  ›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
