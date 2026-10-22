package ru.lumastack.lumawork.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.lumastack.lumawork.data.ftp.FtpItem
import ru.lumastack.lumawork.data.tools.*
import ru.lumastack.lumawork.ui.theme.AppTextField

@Composable
internal fun PaxArchivePicker(state: ToolsUiState, onClose: () -> Unit, onNavigate: (String) -> Unit,
    onDownload: (FtpItem) -> Unit, onSaved: (String) -> Unit,
) {
    val kind = state.archivePicker ?: return
    var query by rememberSaveable(state.paxModel, kind, state.ftpPath) { mutableStateOf("") }
    val items = state.ftpItems.filter { (it.isDirectory || it.name.isPaxArchiveName()) && it.name.contains(query.trim(), true) }
        .sortedWith(compareByDescending<FtpItem> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    val saved = state.files.filter { !it.downloading && it.remotePath?.startsWith(kind.ftpPath + "/") == true && it.name.isPaxArchiveName() && it.name.contains(query.trim(), true) }
    AlertDialog(onDismissRequest = onClose,
        title = { Text("${state.paxModel.title} · ${kind.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Выберите архив. Приложение скачает его и подготовит файлы для этой модели.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.ftpPath != kind.ftpPath) IconButton(onClick = { onNavigate(state.ftpPath.substringBeforeLast('/')) }) {
                        Icon(Icons.Outlined.ArrowBack, "На уровень выше")
                    }
                    Text(state.ftpPath.removePrefix(kind.ftpPath).ifBlank { if (kind == PaxArchiveKind.Prolin) "POSLoader" else "Прошивки" },
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 3)
                    IconButton(onClick = { onNavigate(state.ftpPath) }, enabled = !state.ftpLoading) { Icon(Icons.Outlined.Refresh, "Обновить каталог") }
                }
                AppTextField(query, { query = it }, "Поиск архива или папки", search = true, singleLine = true)
                if (state.ftpLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.ftpError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (saved.isNotEmpty()) {
                        item { Text("Уже скачаны", style = MaterialTheme.typography.labelLarge) }
                        items(saved, key = { "saved:${it.id}" }) { file ->
                            ListItem(headlineContent = { Text(file.name, style = MaterialTheme.typography.bodyMedium) },
                                leadingContent = { Icon(Icons.Outlined.CheckCircle, null) },
                                supportingContent = { Text("Подготовить без повторного скачивания") },
                                modifier = Modifier.clickable { onSaved(file.id) })
                        }
                        item { Text("На FTP", style = MaterialTheme.typography.labelLarge) }
                    }
                    items(items, key = { it.path }) { item ->
                        ListItem(headlineContent = { Text(item.name, style = MaterialTheme.typography.bodyMedium) },
                            leadingContent = { Icon(if (item.isDirectory) ToolsIcons.folder else Icons.Outlined.Send, null) },
                            supportingContent = { if (!item.isDirectory) Text(listOfNotNull(item.sizeText, item.modifiedAt).joinToString(" · ")) },
                            modifier = Modifier.clickable { if (item.isDirectory) onNavigate(item.path) else onDownload(item) })
                    }
                    if (items.isEmpty() && saved.isEmpty() && !state.ftpLoading && state.ftpError == null)
                        item { Text("Архивы не найдены.", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
}
