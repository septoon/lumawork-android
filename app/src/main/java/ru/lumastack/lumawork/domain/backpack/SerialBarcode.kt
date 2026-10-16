package ru.lumastack.lumawork.domain.backpack

import com.google.zxing.*
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer

object SerialBarcode {
    val formats = listOf(BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.EAN_13,
        BarcodeFormat.EAN_8, BarcodeFormat.UPC_E, BarcodeFormat.ITF)
    fun serial(raw: String): String? = raw.trim().takeUnless { it.lowercase() in setOf("", "-", "—", "(не задано)", "не задано", "не указан", "не указано", "информация отсутствует") }
    fun encode(raw: String): BitMatrix? {
        val value = serial(raw) ?: return null
        if (value.any { it.code !in 32..126 } || value.length > 256) return null
        return runCatching { Code128Writer().encode(value, BarcodeFormat.CODE_128, 0, 1, mapOf(EncodeHintType.MARGIN to 24)) }.getOrNull()
    }
    fun decode(bitmap: BinaryBitmap): String? = runCatching {
        serial(MultiFormatReader().decode(bitmap, mapOf(DecodeHintType.POSSIBLE_FORMATS to formats, DecodeHintType.TRY_HARDER to true)).text)
    }.getOrNull()
}
