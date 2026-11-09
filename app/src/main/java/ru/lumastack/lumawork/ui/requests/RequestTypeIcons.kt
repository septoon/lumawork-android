package ru.lumastack.lumawork.ui.requests

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Shared by request details and coordination; outlines match the reference screenshots. */
internal object RequestTypeIcons {
    fun forType(raw: String): ImageVector {
        val token = raw.trim().split(Regex("\\s+"), limit = 2).first().lowercase().filter(Char::isLetterOrDigit)
        return when (token) {
            "install", "установка" -> install
            "dismounting", "демонтаж" -> dismounting
            "returnequip", "возврат" -> returnEquipment
            "servicestd", "сервис", "сервисная" -> service
            "replacement", "замена" -> RequestDetailIcons.Sync
            "expertise", "экспертиза" -> expertise
            else -> unknown
        }
    }

    private val install = outline("install", "M5 3L19 3L22 14L2 14L5 3 M8 6L16 6L17 11L7 11L8 6 M12 18L12 23 M9 20L12 23L15 20")
    private val dismounting = outline("dismounting", "M3 5L21 5L21 11L3 11L3 5 M9 5L9 2L15 2L15 5 M10 11L10 23L14 23L14 11 M10 16L14 16")
    private val service = outline("service", "M3 2L21 2L21 14L3 14L3 2 M7 2L7 7 M11 2L11 5 M15 2L15 7 M18 2L18 5 M10 14L10 23L14 23L14 14 M10 18L14 18")
    private val returnEquipment = outline("returnEquipment", "M6 10L2 14L6 18 M2 14L11 14 M13 17L10 20L13 23 M10 20L17 20 M15 2L17 3L19 2L20 4L22 5L21 7L22 9L20 10L19 12L17 11L15 12L14 10L12 9L13 7L12 5L14 4L15 2 M19 7A2 2 0 1 1 15 7A2 2 0 1 1 19 7")
    private val expertise = outline("Expertise", "M17 10A7 7 0 1 1 3 10A7 7 0 1 1 17 10 M15 15L22 22")
    private val unknown = outline("Request", "M5 2H15L20 7V22H5Z M15 2V7H20 M8 12H17 M8 16H17")

    private fun outline(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(PathParser().parsePathString(path).toNodes(), fill = null, stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }.build()
}
