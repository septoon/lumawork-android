package ru.lumastack.lumawork.domain.profile

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = WorkDocumentCategorySerializer::class)
enum class WorkDocumentCategory(val wireValue: String, val title: String) {
    EmploymentContract("EMPLOYMENT_CONTRACT", "Трудовые договоры"),
    Certificate("CERTIFICATE", "Справки"),
    Regulation("REGULATION", "Положения"),
    Instruction("INSTRUCTION", "Инструкции"),
    Application("APPLICATION", "Заявления"),
    Qualification("QUALIFICATION", "Удостоверения и обучение"),
    Other("OTHER", "Прочее");

    companion object {
        fun suggested(fileName: String): WorkDocumentCategory {
            val value = fileName.lowercase()
            return when {
                listOf("труд", "договор").any(value::contains) -> EmploymentContract
                listOf("справ", "мед").any(value::contains) -> Certificate
                listOf("положен", "регламент").any(value::contains) -> Regulation
                value.contains("инструк") -> Instruction
                value.contains("заявлен") -> Application
                listOf("удостовер", "обуч", "сертифик").any(value::contains) -> Qualification
                else -> Other
            }
        }
    }
}

object WorkDocumentCategorySerializer : KSerializer<WorkDocumentCategory> {
    override val descriptor = PrimitiveSerialDescriptor("WorkDocumentCategory", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): WorkDocumentCategory {
        val value = decoder.decodeString()
        return WorkDocumentCategory.entries.firstOrNull { it.wireValue == value } ?: WorkDocumentCategory.Other
    }
    override fun serialize(encoder: Encoder, value: WorkDocumentCategory) = encoder.encodeString(value.wireValue)
}

@Serializable
data class WorkDocument(
    val id: String,
    val category: WorkDocumentCategory = WorkDocumentCategory.Other,
    val title: String = "Документ",
    val fileName: String = "Документ",
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
)

class WorkDocumentInput(val data: ByteArray, fileName: String, mimeType: String) {
    val fileName = safeWorkDocumentName(fileName)
    val mimeType = normalizedWorkDocumentMime(mimeType, this.fileName)
    val category = WorkDocumentCategory.suggested(this.fileName)
    val title = this.fileName.substringBeforeLast('.', this.fileName).ifBlank { "Документ" }.take(200)
    init {
        require(data.isNotEmpty()) { "Выбранный документ пуст." }
        require(data.size <= MAX_BYTES) { "Документ не должен превышать 20 МБ." }
        require(this.mimeType in ALLOWED_MIME_TYPES) { "Формат документа не поддерживается." }
    }
    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        const val CHUNK_BYTES = 512 * 1024
        const val MAX_DOCUMENTS = 100
        val ALLOWED_MIME_TYPES = setOf(
            "application/pdf", "image/jpeg", "image/png", "image/heic", "image/heif", "image/webp",
            "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/rtf", "text/rtf", "text/plain",
        )
    }
}

fun safeWorkDocumentName(value: String): String = value.replace('\\', '/').substringAfterLast('/')
    .replace(Regex("[\\p{Cntrl}]"), "").trim().trim('.').take(200).ifBlank { "Документ" }

fun normalizedWorkDocumentMime(value: String?, fileName: String): String {
    val mime = value.orEmpty().substringBefore(';').trim().lowercase()
    if (mime == "image/jpg") return "image/jpeg"
    if (mime in WorkDocumentInput.ALLOWED_MIME_TYPES) return mime
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "webp" -> "image/webp"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "rtf" -> "application/rtf"
        "txt" -> "text/plain"
        else -> mime
    }
}

fun validatedWorkDocumentTitle(value: String): String = value.trim().also {
    require(it.isNotEmpty()) { "Укажите название документа." }
    require(it.length <= 200) { "Название не должно превышать 200 символов." }
}
