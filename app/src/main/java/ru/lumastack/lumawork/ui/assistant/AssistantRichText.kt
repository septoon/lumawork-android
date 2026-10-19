package ru.lumastack.lumawork.ui.assistant

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Basic Markdown text is selectable; responses never execute HTML or JavaScript. */
@Composable internal fun AssistantRichText(value: String) {
    val lines = value.lines()
    Column {
        var code = false
        for (line in lines) {
            if (line.trimStart().startsWith("```")) { code = !code; continue }
            val heading = line.takeWhile { it == '#' }.length.takeIf { it in 1..6 && line.getOrNull(it) == ' ' }
            val content = if (heading != null) line.drop(heading + 1) else line.replaceFirst(Regex("^\\s*[-*] "), "• ")
            if (code || line.trimStart().startsWith('|')) Text(content,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            else {
                val annotated = buildAnnotatedString {
                    val regex = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^]]+)\\]\\((https?://[^\\s)]+)\\)")
                    var offset = 0
                    for (match in regex.findAll(content)) {
                        append(content.substring(offset, match.range.first))
                        when {
                            match.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
                            match.groups[2] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.groupValues[2]) }
                            else -> withLink(LinkAnnotation.Url(match.groupValues[4])) { append(match.groupValues[3]) }
                        }
                        offset = match.range.last + 1
                    }
                    append(content.substring(offset))
                }
                Text(annotated, Modifier.padding(vertical = if (heading != null) 4.dp else 1.dp),
                    style = if (heading != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    fontWeight = if (heading != null) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
