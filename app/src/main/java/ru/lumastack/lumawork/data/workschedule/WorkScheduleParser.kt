package ru.lumastack.lumawork.data.workschedule

import java.text.Collator
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.json.*
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.domain.workschedule.*

object WorkScheduleParser {
    fun journals(response: JsonObject): List<WorkScheduleJournal> {
        val items = (response["data"] as? JsonObject)?.get("items") as? JsonArray ?: throw SimpleOneFailure.InvalidResponse
        return items.mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            val id = database(item["sys_id"]) ?: return@mapNotNull null
            val cityId = database(item["city"]) ?: return@mapNotNull null
            val city = display(item["city"]) ?: return@mapNotNull null
            val year = database(item["year"])?.toIntOrNull() ?: return@mapNotNull null
            val month = month(database(item["month"])) ?: month(display(item["month"])) ?: return@mapNotNull null
            WorkScheduleJournal(id, cityId, city, year, month)
        }
    }

    fun schedule(response: JsonObject, journal: WorkScheduleJournal, audit: WorkScheduleAuditInfo? = null): WorkSchedule {
        val data = scheduleDictionary(find(response, "scheduleData")) ?: throw SimpleOneFailure.InvalidResponse
        val days = text(find(response, "daysInMonth"))?.toIntOrNull()?.takeIf { it in 1..31 }
            ?: runCatching { YearMonth.of(journal.year, journal.month).lengthOfMonth() }.getOrDefault(31)
        val employees = data.mapNotNull { (displayName, raw) ->
            val employee = raw as? JsonObject ?: return@mapNotNull null
            if (displayName.lowercase().let { "всего" in it && "работ" in it }) return@mapNotNull null
            val identity = Regex("^(.*?)\\s*\\(([^()]*)\\)\\s*$").matchEntire(displayName)
            val name = identity?.groupValues?.get(1)?.trim() ?: displayName.trim()
            val login = identity?.groupValues?.get(2)?.trim().orEmpty()
            val values = (1..days).mapNotNull { day ->
                val dayData = employee[day.toString()] as? JsonObject ?: return@mapNotNull null
                val hours = number(dayData["hoursWork"]) ?: return@mapNotNull null
                WorkScheduleDayValue(day, hours, boolean(dayData["isActive"]) ?: false)
            }
            WorkScheduleEmployee(text(employee["employeeSysId"]) ?: text(employee["employee_sys_id"])
                ?: login.ifEmpty { name }, name, login, number(employee["totalHours"]) ?: values.sumOf { it.hours }, values)
        }.sortedWith(compareBy(Collator.getInstance(Locale.forLanguageTag("ru"))) { it.name.lowercase() })
        return WorkSchedule(journal, days, employees, (1..days).map { day ->
            WorkScheduleDailyTotal(day, employees.count { it.value(day)?.isActive == true })
        }, audit)
    }

    fun widgetInstanceId(response: JsonObject): String {
        val candidates = objects(response).mapNotNull { value ->
            text(value["widget_instance_id"])?.takeIf { it.isNotEmpty() }?.let { id ->
                val markers = listOf("client_script", "template", "name", "widget_name", "title")
                    .mapNotNull { text(value[it]) }.joinToString(" ").lowercase()
                id to listOf("scheduledata", "daysinmonth", "график").any(markers::contains)
            }
        }.toList()
        return candidates.firstOrNull { it.second }?.first ?: candidates.singleOrNull()?.first ?: throw SimpleOneFailure.InvalidResponse
    }

    fun auditInfo(response: JsonObject): WorkScheduleAuditInfo? {
        fun field(name: String) = objects(response).firstOrNull { text(it["sys_column_name"]) == name }?.get("value")
        fun person(name: String) = display(field(name))?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
        fun date(name: String) = database(field(name))?.let { raw ->
            runCatching { LocalDateTime.parse(raw.trim().replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        }
        return WorkScheduleAuditInfo(person("sys_created_by"), date("sys_created_at"), person("sys_updated_by"), date("sys_updated_at"))
            .takeIf { it.hasContent }
    }

    private fun objects(value: JsonElement): Sequence<JsonObject> = sequence {
        when (value) {
            is JsonObject -> { yield(value); value.values.forEach { yieldAll(objects(it)) } }
            is JsonArray -> value.forEach { yieldAll(objects(it)) }
            else -> Unit
        }
    }
    private fun find(value: JsonElement, key: String): JsonElement? = objects(value).firstNotNullOfOrNull { it[key] }
    private fun scheduleDictionary(value: JsonElement?): JsonObject? = when (value) {
        is JsonObject -> if ("value" in value && value.keys.all { it in listOf("value", "database_value", "display_value") }) scheduleDictionary(value["value"]) else value
        is JsonPrimitive -> runCatching { Json.parseToJsonElement(value.content) as? JsonObject }.getOrNull()
        else -> null
    }
    private fun text(value: JsonElement?): String? = when (value) {
        null, JsonNull -> null
        is JsonPrimitive -> value.contentOrNull
        is JsonObject -> text(value["value"]) ?: text(value["database_value"]) ?: text(value["display_value"])
        else -> null
    }
    private fun database(value: JsonElement?): String? = if (value is JsonObject) {
        value["value"]?.let(::database) ?: text(value["database_value"]) ?: text(value["sys_id"])
    } else text(value)
    private fun display(value: JsonElement?): String? = if (value is JsonObject) {
        value["value"]?.let(::display) ?: text(value["display_value"]) ?: text(value["display_value_translation"]) ?: database(value)
    } else text(value)
    private fun number(value: JsonElement?): Double? = (value as? JsonPrimitive)?.contentOrNull
        ?.replace(',', '.')?.toDoubleOrNull()?.takeIf(Double::isFinite)
    private fun boolean(value: JsonElement?): Boolean? = text(value)?.lowercase()?.let {
        when (it) { "true", "yes", "1" -> true; "false", "no", "0" -> false; else -> it.toDoubleOrNull()?.let { n -> n != 0.0 } }
    }
    private fun month(value: String?): Int? {
        val normalized = value?.trim()?.lowercase() ?: return null
        return normalized.toIntOrNull()?.takeIf { it in 1..12 } ?: listOf(
            "январь января", "февраль февраля", "март марта", "апрель апреля", "май мая", "июнь июня",
            "июль июля", "август августа", "сентябрь сентября", "октябрь октября", "ноябрь ноября", "декабрь декабря",
        ).indexOfFirst { normalized in it.split(' ') }.takeIf { it >= 0 }?.plus(1)
    }
}
