package ru.lumastack.lumawork.data.assistant

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import ru.lumastack.lumawork.data.simpleone.SimpleOneXlsxParser

/** Existing backend extraction enum retains iOS names; Android uses the same wire contract. */
internal object AssistantDocuments {
    const val MAX_BYTES = 12 * 1024 * 1024
    fun prepare(bytes: ByteArray, rawName: String): AssistantAttachment {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Документ пуст или превышает 12 МБ." }
        val name = rawName.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cc}]"), "").take(140).ifBlank { "document.txt" }
        val extension = name.substringAfterLast('.', "").lowercase()
        val mime: String; val extraction: String; val text: String; var truncated = false
        when (extension) {
            "txt", "log", "md", "csv", "json", "xml" -> {
                text = decoded(bytes)
                mime = when (extension) { "md" -> "text/markdown"; "csv" -> "text/csv"; "json" -> "application/json"; "xml" -> "application/xml"; else -> "text/plain" }
                extraction = if (extension == "csv") "ios_csv" else "ios_text"
            }
            "doc", "xls" -> {
                require(bytes.size <= 5 * 1024 * 1024 && bytes.take(8).map { it.toInt() and 255 } == listOf(208,207,17,224,161,177,26,225)) { "Повреждённый DOC/XLS или размер больше 5 МБ." }
                return AssistantAttachment(name, document = AssistantDocument(name, if (extension == "doc") "application/msword" else "application/vnd.ms-excel",
                    extraction = "server_legacy", sourceBytes = bytes.size, rawBase64 = Base64.getEncoder().encodeToString(bytes)))
            }
            "docx" -> {
                val parts = archive(bytes)
                val names = parts.keys.filter { it == "word/document.xml" || Regex("word/(header|footer)[^/]*\\.xml").matches(it) }.sorted()
                require("word/document.xml" in names) { "В DOCX нет документа." }
                text = names.joinToString("\n") { docxText(parts.getValue(it)) }
                mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; extraction = "ios_docx"
            }
            "xlsx" -> {
                val sheets = SimpleOneXlsxParser.readTextWorksheets(bytes)
                text = sheets.take(6).mapIndexed { index, content -> "[Лист ${index + 1}]\n$content" }.joinToString("\n\n")
                truncated = sheets.size > 6
                mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; extraction = "ios_xlsx"
            }
            "rtf" -> { text = rtfText(bytes); mime = "application/rtf"; extraction = "ios_rtf" }
            else -> error("Этот формат документа пока не поддерживается.")
        }
        val cleaned = text.replace("\u0000", "").trim()
        require(cleaned.isNotBlank()) { "В документе не найден текст для анализа." }
        return AssistantAttachment(name, document = AssistantDocument(name, mime, cleaned.take(12_000), extraction, bytes.size,
            wasTruncated = truncated || cleaned.length > 12_000), note = if (extension == "xlsx" && truncated) "Анализируются первые шесть листов таблицы." else if (cleaned.length > 12_000) "Текст сокращён до 12 000 символов." else null)
    }
    private fun decoded(bytes: ByteArray): String = when {
        bytes.take(2) == listOf(0xff.toByte(), 0xfe.toByte()) -> bytes.toString(Charsets.UTF_16LE).trimStart('\uFEFF')
        bytes.take(2) == listOf(0xfe.toByte(), 0xff.toByte()) -> bytes.toString(Charsets.UTF_16BE).trimStart('\uFEFF')
        else -> try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString().trimStart('\uFEFF') }
            catch (_: java.nio.charset.CharacterCodingException) { bytes.toString(charset("windows-1251")) }
    }
    private fun archive(bytes: ByteArray): Map<String, ByteArray> {
        val parts = linkedMapOf<String, ByteArray>(); var total = 0; var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 256) { "В документе слишком много частей." }
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer); if (count < 0) break
                    total += count; require(total <= 32 * 1024 * 1024 && output.size() + count <= 8 * 1024 * 1024) { "Распакованный документ слишком большой." }
                    output.write(buffer, 0, count)
                }
                if (entry.name.startsWith("word/") && entry.name.endsWith(".xml")) parts[entry.name] = output.toByteArray()
            }
        }
        return parts
    }
    private fun docxText(bytes: ByteArray): String {
        val output = StringBuilder(); var inText = false
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            listOf("http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false).forEach { (feature, value) -> runCatching { setFeature(feature, value) } }
        }
        val reader = factory.newSAXParser().xmlReader
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> throw IllegalArgumentException("Внешние сущности XML запрещены.") }
        reader.contentHandler = object : DefaultHandler() {
            override fun startElement(uri: String?, local: String?, qName: String?, attributes: Attributes?) {
                if (local == "t") inText = true
                if (local == "tab") output.append('\t')
                if (local == "br") output.append('\n')
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inText) output.append(ch, start, length) }
            override fun endElement(uri: String?, local: String?, qName: String?) {
                if (local == "t") inText = false
                if (local == "p" || local == "tr") output.append('\n')
            }
        }
        reader.parse(org.xml.sax.InputSource(ByteArrayInputStream(bytes)))
        return output.toString()
    }
    private fun rtfText(bytes: ByteArray): String {
        val input = bytes.toString(Charsets.ISO_8859_1)
        require(input.startsWith("{\\rtf")) { "Не удалось прочитать RTF." }
        data class Group(val skip: Boolean, val unicodeFallback: Int)
        val stack = java.util.ArrayDeque<Group>(); var group = Group(false, 1)
        val output = StringBuilder(); var index = 0; var fallback = 0; var codepage = charset("windows-1252")
        fun append(value: Char) { if (fallback > 0) fallback-- else if (!group.skip) output.append(value) }
        while (index < input.length) {
            val c = input[index++]
            when (c) {
                '{' -> stack.push(group)
                '}' -> if (stack.isNotEmpty()) group = stack.pop()
                '\\' -> {
                    if (index >= input.length) break
                    val next = input[index++]
                    when {
                        next == '\\' || next == '{' || next == '}' -> append(next)
                        next == '*' -> group = group.copy(skip = true)
                        next == '\'' -> { if (index + 2 <= input.length) {
                            input.substring(index, index + 2).toIntOrNull(16)?.let { append(byteArrayOf(it.toByte()).toString(codepage).first()) }; index += 2
                        } }
                        next == '~' -> append(' ')
                        next.isLetter() -> {
                            val start = index - 1; while (index < input.length && input[index].isLetter()) index++
                            val word = input.substring(start, index); val numberStart = index
                            if (index < input.length && input[index] == '-') index++
                            while (index < input.length && input[index].isDigit()) index++
                            val number = input.substring(numberStart, index).toIntOrNull()
                            if (index < input.length && input[index] == ' ') index++
                            when (word) {
                                "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer" -> group = group.copy(skip = true)
                                "ansicpg" -> number?.let { runCatching { charset("windows-$it") }.getOrNull()?.let { cp -> codepage = cp } }
                                "uc" -> group = group.copy(unicodeFallback = (number ?: 1).coerceIn(0, 8))
                                "u" -> if (number != null) { if (!group.skip) output.append((number and 65535).toChar()); fallback = group.unicodeFallback }
                                "par", "line" -> if (!group.skip) output.append('\n')
                                "tab" -> if (!group.skip) output.append('\t')
                                "bin" -> index = (index.toLong() + (number ?: 0).coerceAtLeast(0)).coerceAtMost(input.length.toLong()).toInt()
                            }
                        }
                    }
                }
                '\r', '\n' -> Unit
                else -> append(c)
            }
        }
        return output.toString()
    }
}
