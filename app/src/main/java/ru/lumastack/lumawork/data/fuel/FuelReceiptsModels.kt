package ru.lumastack.lumawork.data.fuel

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
data class FuelReceipt(
    val id: String, val month: String, val kind: String, val fileName: String,
    val mimeType: String, val sizeBytes: Int, val createdAt: String, val updatedAt: String,
) {
    fun validate(): FuelReceipt = apply {
        require(FUEL_RECEIPT_ID.matches(id) && FUEL_RECEIPT_MONTH.matches(month) && kind in FUEL_RECEIPT_KINDS && fileName.isNotBlank()) { "Сервер вернул некорректный чек." }
        require(mimeType in FuelReceiptInput.MIME_TYPES && sizeBytes in 1..FuelReceiptInput.MAX_BYTES) { "Сервер вернул некорректный чек." }
        require(runCatching { Instant.parse(createdAt); Instant.parse(updatedAt) }.isSuccess) { "Сервер вернул некорректную дату чека." }
    }
}

class FuelReceiptInput(val data: ByteArray, sourceName: String, sourceMime: String?) {
    val fileName = sourceName.substringAfterLast('/').substringAfterLast('\\')
        .filter { !it.isISOControl() && it !in ":*?\"<>|" }.trim().take(180).ifBlank { "Чек.pdf" }
    val mimeType: String
    init {
        require(data.isNotEmpty()) { "Выбранный чек пуст." }
        require(data.size <= MAX_BYTES) { "Чек не должен превышать 20 МБ." }
        mimeType = when (fileName.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            else -> throw IllegalArgumentException("Поддерживаются PDF, JPEG, PNG, HEIC и HEIF.")
        }
        val suppliedMime = sourceMime?.substringBefore(';')?.trim()?.lowercase()
        require(suppliedMime.isNullOrBlank() || suppliedMime == "application/octet-stream" || suppliedMime == mimeType) { "Тип файла не соответствует его расширению." }
        val valid = when (mimeType) {
            "application/pdf" -> data.startsWith("%PDF-".toByteArray())
            "image/jpeg" -> data.startsWith(byteArrayOf(-1, -40, -1))
            "image/png" -> data.startsWith(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            else -> validHeif(data)
        }
        require(valid) { "Содержимое файла не соответствует формату чека." }
    }
    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        const val CHUNK_BYTES = 512 * 1024
        val MIME_TYPES = setOf("application/pdf", "image/jpeg", "image/png", "image/heic", "image/heif")
    }
}

internal val FUEL_RECEIPT_MONTH = Regex("^\\d{4}-(0[1-9]|1[0-2])$")
internal val FUEL_RECEIPT_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
internal val FUEL_RECEIPT_KINDS = setOf("fiscal", "bank")
private fun ByteArray.startsWith(signature: ByteArray) = size >= signature.size && signature.indices.all { this[it] == signature[it] }
private fun validHeif(data: ByteArray): Boolean {
    if (data.size < 16 || data.copyOfRange(4, 8).toString(Charsets.US_ASCII) != "ftyp") return false
    val boxSize = java.nio.ByteBuffer.wrap(data, 0, 4).int
    if (boxSize < 16 || boxSize > data.size) return false
    val brands = listOf(8) + (16 until minOf(boxSize, 256) step 4).toList()
    return brands.any { offset -> offset + 4 <= boxSize && data.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII) in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1") }
}

internal suspend fun readFuelReceiptBytes(stream: InputStream, limit: Int = FuelReceiptInput.MAX_BYTES): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = stream.read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "Файл превышает допустимый размер." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
