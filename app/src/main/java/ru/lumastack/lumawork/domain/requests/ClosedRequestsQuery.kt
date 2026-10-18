package ru.lumastack.lumawork.domain.requests

import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.parseSimpleOneDate

/** Inclusive calendar days, matching the Closed archive period on iOS. */
data class ClosedRequestsPeriod(val start: LocalDate, val end: LocalDate) {
    init { require(start <= end) }
    operator fun contains(date: LocalDate): Boolean = date >= start && date <= end

    companion object {
        fun between(first: LocalDate, second: LocalDate) =
            ClosedRequestsPeriod(minOf(first, second), maxOf(first, second))
    }
}

data class ClosedRequestsItem(
    val request: SimpleOneRequest,
    val date: LocalDateTime?,
    val timeText: String,
    val timeTitle: String,
    val typeText: String,
    val statusText: String,
    val searchText: String,
) {
    val day: LocalDate? get() = date?.toLocalDate()
    val dayKey: String get() = day?.toString() ?: timeText.ifBlank { "unknown" }
}

fun normalizedClosedRequestsText(value: String): String = Normalizer.normalize(
    value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD,
).replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ")

fun SimpleOneRequest.closedTableValue(vararg titles: String): String {
    val keys = titles.map(::normalizedClosedRequestsText).toSet()
    return tableFields.entries.firstOrNull {
        normalizedClosedRequestsText(it.key) in keys && it.value.isNotBlank()
    }?.value?.trim().orEmpty()
}

fun isClosedReturnEquipment(rawType: String): Boolean {
    val type = normalizedClosedRequestsText(rawType)
    return type == "returnequip" || type == "return_equip" || type.contains("возврат")
}

fun closedRequestTypeTitle(raw: String): String = when (raw.trim().substringBefore(' ')) {
    "install" -> "Установка"
    "dismounting" -> "Демонтаж"
    "returnEquip", "return_equip" -> "Возврат ТО"
    "serviceStd" -> "Сервисная"
    "replacement" -> "Замена"
    else -> raw.trim()
}

fun SimpleOneRequest.closedRequestType(): String = requestType.ifBlank { closedTableValue("Тип заявки") }
fun SimpleOneRequest.closedTerminalId(): String = terminalId.ifBlank { closedTableValue("ID терминал", "ID терминала") }
fun SimpleOneRequest.closedCustomer(): String = customer.ifBlank { closedTableValue("Заказчик") }
fun SimpleOneRequest.closedAddress(): String = address.ifBlank { closedTableValue("Адрес установки терминала", "Адрес") }

fun SimpleOneRequest.closedStatusTitle(): String {
    if (isClosedReturnEquipment(closedRequestType())) {
        return when (val status = displayState.trim().ifBlank { closedTableValue("МК Статус", "Статус") }) {
            "completed" -> "Выполнена"
            "closed", "" -> "Закрыта"
            else -> status
        }
    }
    val code = closureCode.ifBlank { closedTableValue("Код закрытия") }
    return if (normalizedClosedRequestsText(code).startsWith("решено с выездом")) "Выполнена" else "Отказ"
}

/** UTC API timestamps use the shared parser; XLSX display dates are already local. */
fun parseClosedRequestsDate(raw: String): LocalDateTime? {
    val value = raw.trim()
    parseSimpleOneDate(value)?.let { return it }
    if (value.matches(Regex("\\d+(\\.\\d+)?"))) {
        val serial = value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 1.0..2_958_465.0 }
        if (serial != null) return LocalDate.of(1899, 12, 30).atStartOfDay()
            .plusSeconds((serial * 86_400).toLong())
    }
    // iOS exports also accept a one-digit hour, e.g. 29.02.2024 9:05.
    listOf("dd.MM.yyyy H:mm", "MM.dd.yyyy H:mm").firstNotNullOfOrNull { pattern ->
        runCatching { LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern)) }.getOrNull()
    }?.takeIf { it.year > 1970 }?.let { return it }
    return listOf("yyyy-MM-dd", "dd.MM.yyyy", "MM.dd.yyyy").firstNotNullOfOrNull { pattern ->
        runCatching { LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern)).atStartOfDay() }.getOrNull()
    }?.takeIf { it.year > 1970 }
}

fun SimpleOneRequest.closedEffectiveTime(): String {
    val registered = registeredAt.ifBlank { closedTableValue("Время создания в МК", "Время регистрации", "Регистрация") }
    if (isClosedReturnEquipment(closedRequestType()) && parseClosedRequestsDate(registered) != null) return registered
    // Android's archive maps the iOS ClosedRequestRecord.closedAt to resolvedAt.
    return resolvedAt.ifBlank { completedAt }.ifBlank { closedAt }.ifBlank {
        closedTableValue("Время решения", "Время выполнения", "Дата закрытия в МК")
    }
}

fun prepareClosedRequests(records: List<SimpleOneRequest>): List<ClosedRequestsItem> = records
    .distinctBy { it.id }
    .map { request ->
        val rawTime = request.closedEffectiveTime()
        val date = parseClosedRequestsDate(rawTime)
        val timeText = date?.format(CLOSED_TIME_FORMAT) ?: rawTime
        val type = closedRequestTypeTitle(request.closedRequestType())
        val status = request.closedStatusTitle()
        val formattedDates = date?.let { parsed -> CLOSED_SEARCH_FORMATS.map { parsed.format(it) } }.orEmpty()
        ClosedRequestsItem(request, date, timeText,
            if (isClosedReturnEquipment(request.closedRequestType())) "Время регистрации" else "Выполнена",
            type, status, normalizedClosedRequestsText(
                listOf(request.searchText, type, status, rawTime, timeText).plus(formattedDates).joinToString("\n"),
            ))
    }
    .sortedWith(compareByDescending<ClosedRequestsItem> { it.date ?: LocalDateTime.MIN }
        .thenByDescending { it.request.number })

fun defaultClosedRequestsPeriod(
    items: List<ClosedRequestsItem>, today: LocalDate = LocalDate.now(),
): ClosedRequestsPeriod? {
    val dates = items.mapNotNull { it.day }
    val yesterday = today.minusDays(1)
    if (dates.any { it >= yesterday && it <= today }) return ClosedRequestsPeriod(yesterday, today)
    val day = dates.filter { it <= today }.maxOrNull() ?: dates.filter { it > today }.minOrNull() ?: return null
    return ClosedRequestsPeriod(day, day)
}

fun queryClosedRequests(
    items: List<ClosedRequestsItem>, query: String, period: ClosedRequestsPeriod?,
): List<ClosedRequestsItem> {
    val normalized = normalizedClosedRequestsText(query)
    return items.filter { item ->
        // Searching deliberately covers the entire archive, including undated records.
        if (normalized.isNotEmpty()) item.searchText.contains(normalized)
        else period == null || item.day?.let { it in period } == true
    }
}

fun closedRequestsDayTitle(day: LocalDate?, fallback: String = "", today: LocalDate = LocalDate.now()): String = when (day) {
    null -> fallback.ifBlank { "Дата не указана" }
    today -> "Сегодня"
    today.minusDays(1) -> "Вчера"
    else -> day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", CLOSED_RUSSIAN_LOCALE))
}

fun normalizedClosedAddress(raw: String): String {
    val trimmed = raw.trim()
    val index = listOf("Алушта", "Ялта", "Севастополь", "Симферополь", "Джанкой")
        .map { trimmed.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: return trimmed
    return trimmed.substring(index).replace(Regex("\\s*,\\s*"), ", ")
}

private val CLOSED_RUSSIAN_LOCALE = Locale.forLanguageTag("ru-RU")
private val CLOSED_TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", CLOSED_RUSSIAN_LOCALE)
private val CLOSED_SEARCH_FORMATS = listOf("d MMMM", "d MMMM yyyy", "dd.MM.yy", "dd MM yyyy", "EEEE")
    .map { DateTimeFormatter.ofPattern(it, CLOSED_RUSSIAN_LOCALE) }
