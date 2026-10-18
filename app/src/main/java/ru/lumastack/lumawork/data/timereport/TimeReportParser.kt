package ru.lumastack.lumawork.data.timereport

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import ru.lumastack.lumawork.data.simpleone.SimpleOneXlsxException
import ru.lumastack.lumawork.data.simpleone.SimpleOneXlsxParser
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry
import ru.lumastack.lumawork.domain.timereport.parseTimeReportDate

internal object TimeReportParser {
    fun parseXlsx(bytes: ByteArray): List<TimeReportEntry> = SimpleOneXlsxParser.readRows(bytes)
        .mapNotNull(::entryFromRow)
        .deduplicatedAndSorted()
        .ifEmpty { throw SimpleOneXlsxException("В файле не найдены трудозатраты.") }

    fun parseRecord(item: JsonObject): TimeReportEntry? {
        val activity = item.value(
            "acticvity",
            "activity",
            "task",
            "itsm_request",
            "source_task",
            "Активность",
            "Задача",
            "Задача.Активность",
            preferDisplay = true,
        )
        val createdAtRaw = firstNonEmpty(
            item.value("sys_created_at", "created_at", "Когда создано"),
            item.value("date_of_work", "Дата проведения работ"),
        )
        val workDate = item.value("date_of_work", "Дата проведения работ")
        val createdAt = normalizeDate(createdAtRaw) ?: normalizeDate(workDate) ?: return null
        if (activity.isBlank()) return null

        return TimeReportEntry(
            activity = activity,
            period = item.value("month", "period", "Период", preferDisplay = true),
            createdAt = createdAt,
            createdAtRaw = createdAtRaw,
            simpleOneRecordId = item.value("sys_id", "id", "ID"),
            workDate = normalizeDate(workDate).orEmpty(),
            workMinutes = parseMinutes(
                duration = item.value(
                    "time_of_work",
                    "work_time",
                    "work_duration",
                    "time_work",
                    "Время работ",
                ),
                hours = item.value("time_of_work_hours", "work_hours", "time_work_hours", "Время работ (ч)"),
                minutes = item.value("time_of_work_minutes", "work_minutes", "time_work_minutes", "Время работ (м)"),
            ),
            travelMinutes = parseMinutes(
                duration = item.value("travel_time", "time_on_road", "Время в дороге"),
                hours = item.value("travel_time_hours", "travel_hours", "time_on_road_hours", "Время в дороге (ч)"),
                minutes = item.value("travel_time_minutes", "travel_minutes", "time_on_road_minutes", "Время в дороге (м)"),
            ),
            overtimeMinutes = parseNumeric(item.value("extracurricular_activities_hours", "overtime_hours", "over_time_hours", "Внеурочные работы (ч.)"))
                .times(60).roundToInt() +
                parseNumeric(item.value("overtime_minutes", "over_time_minutes", "Внеурочные работы (м)"))
                    .roundToInt(),
            notes = item.value("result", "work_notes", "notes", "Рабочие заметки", preferDisplay = true),
            nonWorkCosts = item.value(
                "extracurricular_time",
                "non_work_costs",
                "non_work_time",
                "Трудозатраты нерабочие",
                preferDisplay = true,
            ),
            isOvertime = parseBoolean(item.value("extracurricular_work", "overtime", "is_overtime", "Внеурочные работы")),
            executor = item.value(
                "person.c_full_name",
                "person",
                "executor.c_full_name",
                "executor",
                "Исполнитель",
                preferDisplay = true,
            ),
        )
    }

    private fun entryFromRow(row: Map<String, String>): TimeReportEntry? {
        val activity = row.value(
            "Активность",
            "Задача",
            "Задача.Активность",
            "acticvity",
            "activity",
            "task",
            "itsm_request",
            "source_task",
        )
        val createdAtRaw = firstNonEmpty(
            row.value("Когда создано", "sys_created_at", "created_at"),
            row.value("Дата проведения работ", "date_of_work"),
        )
        val workDate = row.value("Дата проведения работ", "date_of_work")
        val createdAt = normalizeDate(createdAtRaw) ?: normalizeDate(workDate) ?: return null
        if (activity.isBlank()) return null

        return TimeReportEntry(
            activity = activity,
            period = row.value("Период", "month", "period"),
            createdAt = createdAt,
            createdAtRaw = createdAtRaw,
            simpleOneRecordId = row.value("sys_id", "id", "ID"),
            workDate = normalizeDate(workDate).orEmpty(),
            workMinutes = parseMinutes(
                duration = row.value("Время работ", "time_of_work", "work_time", "work_duration", "time_work"),
                hours = row.value("Время работ (ч)", "time_of_work_hours", "work_hours", "time_work_hours"),
                minutes = row.value("Время работ (м)", "time_of_work_minutes", "work_minutes", "time_work_minutes"),
            ),
            travelMinutes = parseMinutes(
                duration = row.value("Время в дороге", "travel_time", "time_on_road"),
                hours = row.value("Время в дороге (ч)", "travel_time_hours", "travel_hours", "time_on_road_hours"),
                minutes = row.value("Время в дороге (м)", "travel_time_minutes", "travel_minutes", "time_on_road_minutes"),
            ),
            overtimeMinutes = parseNumeric(
                row.value("Внеурочные работы (ч.)", "extracurricular_activities_hours", "overtime_hours", "over_time_hours"),
            ).times(60).roundToInt() + parseNumeric(
                row.value("Внеурочные работы (м)", "overtime_minutes", "over_time_minutes"),
            ).roundToInt(),
            notes = row.value("Рабочие заметки", "result", "work_notes", "notes"),
            nonWorkCosts = row.value(
                "Трудозатраты нерабочие",
                "extracurricular_time",
                "non_work_costs",
                "non_work_time",
            ),
            isOvertime = parseBoolean(row.value("Внеурочные работы", "extracurricular_work", "overtime", "is_overtime")),
            executor = row.value(
                "Исполнитель",
                "person",
                "person.c_full_name",
                "executor",
                "executor.c_full_name",
            ),
        )
    }

    internal fun parseDurationMinutes(raw: String): Int {
        val normalized = raw.trim().lowercase(Locale.ROOT).replace(',', '.')
        if (normalized.isEmpty()) return 0
        normalized.toDoubleOrNull()?.takeIf { it > 0 }?.let { value ->
            return if (value >= 60_000) (value / 60_000).roundToInt() else value.roundToInt()
        }
        val hours = HOUR_PATTERN.findAll(normalized).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        val minutes = MINUTE_PATTERN.findAll(normalized).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        return (hours * 60 + minutes).roundToInt()
    }

    private fun parseMinutes(duration: String, hours: String, minutes: String): Int =
        parseDurationMinutes(duration).takeIf { it > 0 }
            ?: (parseNumeric(hours) * 60 + parseNumeric(minutes)).roundToInt()

    private fun normalizeDate(raw: String): String? {
        val value = raw.trim().replace(',', '.')
        value.toDoubleOrNull()?.takeIf { it in 1.0..1_000_000.0 }?.let { serial ->
            return EXCEL_BASE_DATE.plusSeconds((serial * 86_400).roundToLong()).format(NORMALIZED_DATE_FORMATTER)
        }
        return parseTimeReportDate(value)?.format(NORMALIZED_DATE_FORMATTER)
    }

    private fun List<TimeReportEntry>.deduplicatedAndSorted(): List<TimeReportEntry> =
        associateBy(TimeReportEntry::stableId).values.sortedWith(
            compareByDescending<TimeReportEntry> { it.createdAtDateTime() ?: LocalDateTime.MIN }
                .thenBy(TimeReportEntry::activity),
        )

    private fun Map<String, String>.value(vararg names: String): String {
        val targets = names.map(::normalizeHeader).toSet()
        return entries.firstOrNull { normalizeHeader(it.key) in targets }
            ?.value
            ?.trim()
            ?.normalizedDisplayValue()
            .orEmpty()
    }

    private fun JsonObject.value(vararg names: String, preferDisplay: Boolean = false): String =
        names.firstNotNullOfOrNull { name ->
            jsonField(name)?.let { fieldValue(it, preferDisplay).normalizedDisplayValue() }
                ?.takeIf(String::isNotBlank)
        }.orEmpty()

    private fun JsonObject.jsonField(name: String): JsonElement? {
        get(name)?.let { return it }
        var current: JsonElement = this
        name.split('.').forEach { component ->
            current = (current as? JsonObject)?.get(component) ?: return null
        }
        return current
    }

    private fun fieldValue(raw: JsonElement?, preferDisplay: Boolean): String = when (raw) {
        null, JsonNull -> ""
        is JsonPrimitive -> raw.contentOrNull?.trim().orEmpty()
        is JsonArray -> raw.joinToString(", ") { fieldValue(it, true) }
        is JsonObject -> {
            val display = (raw["display_value"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val value = fieldValue(raw["value"], preferDisplay)
            val database = (raw["database_value"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (preferDisplay) firstNonEmpty(display, value, database) else firstNonEmpty(value, database, display)
        }
    }

    private fun String.normalizedDisplayValue(): String = trim().takeUnless {
        it.equals("(не задано)", ignoreCase = true) || it.equals("(not set)", ignoreCase = true)
    }.orEmpty()

    private fun normalizeHeader(raw: String): String = raw
        .replace('\u00a0', ' ')
        .trim()
        .replace('ё', 'е')
        .replace('Ё', 'Е')
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

    private fun parseNumeric(raw: String): Double = raw.trim().replace(',', '.').toDoubleOrNull() ?: 0.0

    private fun parseBoolean(raw: String): Boolean = raw.trim().lowercase(Locale.ROOT) in setOf(
        "1",
        "true",
        "yes",
        "да",
        "истина",
    )

    private fun firstNonEmpty(vararg values: String): String = values.firstOrNull(String::isNotBlank).orEmpty()

    private val HOUR_PATTERN = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:hours?|hour|час(?:а|ов)?|ч|h)")
    private val MINUTE_PATTERN = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:minutes?|minute|мин(?:ут(?:а|ы)?)?|м|m)")
    private val EXCEL_BASE_DATE = LocalDate.of(1899, 12, 30).atStartOfDay()
    private val NORMALIZED_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
}
