package ru.lumastack.lumawork.domain.requests

import java.time.LocalDateTime
import java.util.Locale
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.parseSimpleOneDate

fun normalizeHistoryTerminalId(raw: String): String {
    val value = raw.filterNot(Char::isWhitespace).uppercase(Locale.ROOT)
    return value.takeIf { it.isNotEmpty() && it.all { c -> c.code < 128 && (c.isLetterOrDigit() || c == '-' || c == '_') } }.orEmpty()
}

fun terminalHistoryCondition(terminalId: String): String {
    val id = normalizeHistoryTerminalId(terminalId)
    require(id.isNotEmpty()) { "Некорректный ID терминала." }
    return "(resolved_atNOTONopt:167653141215593053^stateNOT INon_hold@assigned@in_progress@escalated@returned_to_work@3@6@update_received^multicard_id_terminalLIKE$id)^ORDERBYDESCresolved_at^ORDERBYDESCsys_id"
}

fun terminalHistoryDate(request: SimpleOneRequest): LocalDateTime? =
    listOf(request.completedAt, request.resolvedAt, request.closedAt).firstNotNullOfOrNull(::parseSimpleOneDate)

fun terminalHistoryStatus(request: SimpleOneRequest): String = when {
    request.closureCode.trim().startsWith("решено", ignoreCase = true) -> "Выполнена"
    request.closureCode.trim().startsWith("отказ", ignoreCase = true) -> "Отказ"
    else -> "Нет данных"
}

fun terminalHistoryType(request: SimpleOneRequest): String = when (request.requestType.trim()) {
    "expertise" -> "Экспертиза"
    else -> closedRequestTypeTitle(request.requestType).ifBlank { "Не указан" }
}

fun terminalRequestHistory(
    records: List<SimpleOneRequest>,
    terminalId: String,
    excludingRequestId: String = "",
    showsReturnEquipment: Boolean = true,
    showsExpertise: Boolean = true,
): List<SimpleOneRequest> {
    val target = normalizeHistoryTerminalId(terminalId)
    if (target.isEmpty()) return emptyList()
    return records.asSequence().filter { record ->
        val type = record.requestType.trim().lowercase(Locale.ROOT)
        record.id != excludingRequestId && record.number != excludingRequestId &&
            (showsReturnEquipment || !isClosedReturnEquipment(type)) &&
            (showsExpertise || !(type.contains("expert") || type.contains("экспертиз"))) &&
            (listOf(record.terminalId) + record.tableFields.filterKeys { it.trim().lowercase(Locale.ROOT) in listOf("id терминал", "id терминала") }.values)
                .any { value -> normalizeHistoryTerminalId(value) == target || value.split(HISTORY_ID_SEPARATORS).any { normalizeHistoryTerminalId(it) == target } }
    }.distinctBy { it.id }
        .sortedWith(compareByDescending<SimpleOneRequest> { terminalHistoryDate(it) ?: LocalDateTime.MIN }.thenByDescending { it.id })
        .toList()
}

private val HISTORY_ID_SEPARATORS = Regex("[,;|/\\s]+")
