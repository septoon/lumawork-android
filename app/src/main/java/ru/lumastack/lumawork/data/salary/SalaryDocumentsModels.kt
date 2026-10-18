package ru.lumastack.lumawork.data.salary

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.Serializable

@Serializable
data class SalaryDocument(
    val id: String,
    val month: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Int,
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    fun validate(): SalaryDocument = apply {
        require(id.isNotBlank() && SALARY_DOCUMENT_MONTH.matches(month) && fileName.isNotBlank()) { "Сервер вернул некорректный расчётный листок." }
        require(sizeBytes in 1..SalaryDocumentInput.MAX_BYTES && mimeType in SalaryDocumentInput.MIME_TYPES) { "Сервер вернул некорректный расчётный листок." }
    }
}

class SalaryDocumentInput(val data: ByteArray, sourceName: String, sourceMime: String?) {
    val fileName = safeSalaryDocumentName(sourceName)
    val mimeType: String
    init {
        require(data.isNotEmpty()) { "Выбранный расчётный листок пуст." }
        require(data.size <= MAX_BYTES) { "Расчётный листок не должен превышать 20 МБ." }
        mimeType = when (fileName.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "html", "htm" -> "text/html"
            else -> throw IllegalArgumentException("Поддерживаются только PDF и HTML.")
        }
        val suppliedMime = sourceMime?.substringBefore(';')?.trim()?.lowercase()
        require(suppliedMime.isNullOrBlank() || suppliedMime == "application/octet-stream" || suppliedMime == mimeType) { "Тип файла не соответствует его расширению." }
        if (mimeType == "application/pdf") {
            require(data.size >= 5 && data.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) { "Выбранный файл не является PDF." }
        } else {
            // Accept BOM/UTF-16 exports as well as UTF-8 without rendering arbitrary binary as HTML.
            val charset = when {
                data.size >= 2 && data[0] == 0xff.toByte() && data[1] == 0xfe.toByte() -> Charsets.UTF_16LE
                data.size >= 2 && data[0] == 0xfe.toByte() && data[1] == 0xff.toByte() -> Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
            val text = runCatching { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString() }.getOrNull()
            require(text != null && '\u0000' !in text && Regex("<(?:!doctype\\s+html|html|head|body)(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(text.take(8192))) { "Выбранный файл не является HTML." }
        }
    }
    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        const val CHUNK_BYTES = 512 * 1024
        val MIME_TYPES = setOf("application/pdf", "text/html")
    }
}

internal val SALARY_DOCUMENT_MONTH = Regex("^\\d{4}-(0[1-9]|1[0-2])$")

fun safeSalaryDocumentName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
    .filter { !it.isISOControl() && it !in ":*?\"<>|" }.trim().take(180).ifBlank { "Расчётный листок.pdf" }

internal fun readSalaryDocumentBytes(stream: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        require(output.size() + count <= SalaryDocumentInput.MAX_BYTES) { "Расчётный листок не должен превышать 20 МБ." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
