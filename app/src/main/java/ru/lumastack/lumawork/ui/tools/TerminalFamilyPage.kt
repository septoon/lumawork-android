package ru.lumastack.lumawork.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class TerminalFamily { P8, Morefun }

@Composable
internal fun TerminalFamilyPage(family: TerminalFamily, onFtp: () -> Unit, onWiki: () -> Unit) {
    val colors = ToolsColors.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (family) {
            TerminalFamily.P8 -> {
                ToolsStepCard(1, "Unitodi P8 · update.zip", colors.orange, decoration = ToolsIcons.terminal) {
                    ToolsSubpanel(colors.mint) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Info, null, Modifier.size(24.dp), tint = colors.mint)
                            Text("Скачайте совместимый update.zip из FTP.", style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                        }
                    }
                    ToolsActionButton("Открыть FTP", onFtp, Modifier.fillMaxWidth(), icon = Icons.Outlined.Send, primary = true)
                }
                ToolsStepCard(2, "Внешняя флешка", colors.mint) {
                    Text("Скопируйте update.zip в корень внешней USB-флешки. Подключите флешку к P8 через P8HostOTG.",
                        style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                    ToolsSubpanel(colors.blue) {
                        Text("Копирование из приложения по USB не реализовано.", style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                    }
                }
                ToolsStepCard(3, "Обновление на терминале", colors.blue) {
                    Text("Запустите обновление на P8 по инструкции для вашей версии. Сохраняйте питание до завершения.",
                        style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                    ToolsActionButton("Открыть инструкцию в Вики", onWiki, Modifier.fillMaxWidth(), icon = ToolsIcons.book, accent = colors.blue)
                }
            }
            TerminalFamily.Morefun -> {
                ToolsStepCard(1, "Morefun", colors.orange, decoration = ToolsIcons.terminal) {
                    Text("Выберите модель и прошивку по инструкции в Вики.", style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                    ToolsSubpanel(colors.blue) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Info, null, Modifier.size(24.dp), tint = colors.blue)
                            Text("Передача файлов и прошивка Morefun по USB из приложения не реализованы.", style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                        }
                    }
                    ToolsActionButton("Инструкция в Вики", onWiki, Modifier.fillMaxWidth(), icon = ToolsIcons.book, primary = true)
                    ToolsActionButton("Файлы в FTP", onFtp, Modifier.fillMaxWidth(), icon = Icons.Outlined.Send, accent = colors.mint)
                }
            }
        }
    }
}
