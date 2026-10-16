package ru.lumastack.lumawork.domain.auto

import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = VehicleDocumentKindSerializer::class)
enum class VehicleDocumentKind(val wireValue: String, val title: String) {
    Osago("OSAGO", "ОСАГО"),
    Sts("STS", "СТС"),
    Pts("PTS", "ПТС"),
    DiagnosticCard("DIAGNOSTIC_CARD", "Диагностическая карта"),
    PurchaseContract("PURCHASE_CONTRACT", "Договор купли-продажи"),
    Service("SERVICE", "Сервисный документ"),
    Other("OTHER", "Другой документ");

    companion object {
        fun suggested(fileName: String): VehicleDocumentKind {
            val name = fileName.lowercase()
            return when {
                listOf("осаго", "osago", "полис").any(name::contains) -> Osago
                listOf("стс", "sts").any(name::contains) -> Sts
                listOf("птс", "pts").any(name::contains) -> Pts
                listOf("диагност", "то-").any(name::contains) -> DiagnosticCard
                listOf("дкп", "договор").any(name::contains) -> PurchaseContract
                listOf("сервис", "заказ-наряд").any(name::contains) -> Service
                else -> Other
            }
        }
    }
}

object VehicleDocumentKindSerializer : KSerializer<VehicleDocumentKind> {
    override val descriptor = PrimitiveSerialDescriptor("VehicleDocumentKind", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): VehicleDocumentKind {
        val value = decoder.decodeString()
        return VehicleDocumentKind.entries.firstOrNull { it.wireValue == value } ?: VehicleDocumentKind.Other
    }
    override fun serialize(encoder: Encoder, value: VehicleDocumentKind) = encoder.encodeString(value.wireValue)
}

@Serializable
data class VehicleDocument(
    val id: String,
    val kind: VehicleDocumentKind = VehicleDocumentKind.Other,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Int,
    val createdAt: String,
)

class VehicleDocumentInput(
    val data: ByteArray,
    fileName: String,
    mimeType: String,
    val kind: VehicleDocumentKind,
    val id: String = UUID.randomUUID().toString(),
) {
    val fileName = safeVehicleDocumentName(fileName)
    val mimeType = normalizedVehicleDocumentMime(mimeType, fileName)

    init {
        require(data.isNotEmpty()) { "Выбранный документ пуст." }
        require(data.size <= MAX_BYTES) { "Документ не должен превышать 20 МБ." }
        require(this.mimeType in ALLOWED_MIME_TYPES) { "Поддерживаются PDF, JPG, PNG, HEIC и WebP." }
    }

    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        const val CHUNK_BYTES = 512 * 1024
        val ALLOWED_MIME_TYPES = setOf("application/pdf", "image/jpeg", "image/png", "image/heic", "image/heif", "image/webp")
    }
}

fun safeVehicleDocumentName(value: String): String = value
    .replace(Regex("[\\p{Cntrl}/\\\\]"), "-")
    .trim().trim('.').take(180).ifBlank { "Документ" }

fun normalizedVehicleDocumentMime(value: String?, fileName: String): String {
    val mime = value.orEmpty().substringBefore(';').trim().lowercase()
    if (mime == "image/jpg") return "image/jpeg"
    if (mime in VehicleDocumentInput.ALLOWED_MIME_TYPES) return mime
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "webp" -> "image/webp"
        else -> mime
    }
}
