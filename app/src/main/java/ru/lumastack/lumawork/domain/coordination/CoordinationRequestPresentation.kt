package ru.lumastack.lumawork.domain.coordination

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import ru.lumastack.lumawork.domain.requests.RequestDetailsData
import ru.lumastack.lumawork.domain.requests.normalizedClosedRequestsText
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

fun List<SimpleOneRequest>.sortedCoordinationRequests(): List<SimpleOneRequest> = sortedWith(
    compareByDescending<SimpleOneRequest> { it.registeredDateTime() ?: LocalDateTime.MIN }.thenByDescending { it.number },
)

class CoordinationRequestPresentation(val request: SimpleOneRequest, private val loading: Boolean = false, usesMulticardStatus: Boolean = true) {
    private val data = RequestDetailsData(request)
    private fun identifier(value: String) = normalizedClosedRequestsText(value).filter(Char::isLetterOrDigit)
    private fun missing(value: String) = value.ifBlank { if (loading) "загрузка…" else "—" }
    val primaryNumber: String = request.incomingNumber.trim().ifBlank {
        request.shortDescription.trim().takeIf { it.startsWith("SUTS", true) && '-' in it && it.none(Char::isWhitespace) }.orEmpty()
    }.ifBlank { request.number.trim() }
    val secondaryNumber = request.number.trim().takeIf { it.isNotBlank() && identifier(it) != identifier(primaryNumber) }
    val description = request.shortDescription.trim().takeIf { value -> value.isNotBlank() && listOf(primaryNumber, request.number, request.incomingNumber).none { identifier(it) == identifier(value) } }
    val typeTitle = when (request.requestType.trim().substringBefore(' ').lowercase()) {
        "install" -> "Установка"; "servicestd" -> "Сервис"; "replacement" -> "Замена"; "dismounting" -> "Демонтаж"
        else -> if (isReturnEquipmentRequest(request.requestType)) "Возврат ТО" else request.requestType.trim()
    }
    val status: String? = if (!usesMulticardStatus) request.state.trim().takeIf(String::isNotBlank) else {
        val type = normalizedClosedRequestsText(request.requestType)
        if (type.contains("экспертиз") || type.contains("expert")) null else {
            val raw = data.value("МК Статус", "Статус заявки Мультикарта").ifBlank { request.multicardState }.trim()
            when {
                raw.isBlank() -> if (loading) "Загружаю статус" else "Статус недоступен"
                raw.lowercase().filter(Char::isLetterOrDigit) == "storeequipissued" -> "Оборудование выдано"
                else -> raw
            }
        }
    }
    val terminal = request.terminalId.trim().ifBlank { data.value("ID терминал", "ID терминала") }
    val installedSerial = data.value("Оборудование POS")
    val dismantledSerial = data.value("Серийный номер демонтируемого ТО", "Номер принятого оборудования POS").ifBlank {
        if (isReturnEquipmentRequest(request.requestType)) data.value("S/N терминала", "Оборудование POS") else ""
    }
    val terminalText = missing(terminal)
    val installedSerialText = missing(installedSerial)
    val dismantledSerialText = missing(dismantledSerial)
    val registeredAt = dateText(request.registeredAt, request.registeredDateTime())
    val deadline = dateText(request.deadline, request.deadlineDateTime())
    val address = request.address.trim().replace(Regex("\\s*,\\s*"), ", ")
    private fun dateText(raw: String, parsed: LocalDateTime?): String? {
        val text = raw.trim()
        if (text.isBlank() || text == "0" || "1970-01-01" in text || "01.01.1970" in text) return null
        return parsed?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) ?: text
    }
}
