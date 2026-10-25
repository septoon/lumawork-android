package ru.lumastack.lumawork.domain.requests

import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequestSource
import ru.lumastack.lumawork.domain.simpleone.parseSimpleOneDate
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Field order and fallbacks mirror ActiveSimpleOneRequestData on iOS, including archived requests. */
data class RequestDetailField(val title: String, val value: String)
data class RequestDetailSection(val id: String, val title: String, val fields: List<RequestDetailField>)
data class RequestDetailSLA(val progress: Float, val remainingText: String, val isOverdue: Boolean)

fun requestInformation(request: SimpleOneRequest): Map<String, String> {
    val labels = listOf("Номер заявки Мультикарты", "Принадлежность оборудования по заявке", "Серийный номер демонтируемого ТО",
        "Серийный номер демонтируемого PIN", "Производитель устанавливаемого ТО", "Модель устанавливаемого ТО", "Модель POS-терминала",
        "Модель POS", "Модель терминала", "Тип устанавливаемого ТО", "Тип терминала", "Согласованная дата и время проведения работ",
        "Согласованная дата и время предоставления доступа", "Тип заявки", "Категория обслуживания", "Город склада", "Адрес установки терминала",
        "Адрес ТСП", "Заказчик", "Оборудование POS", "Оборудование Pin Pad", "Доп. информация", "ID СБП", "ИНН ТСП", "Код закрытия", "Решение")
    var text = listOf(request.description, request.additionalInformation).joinToString("\n")
        .replace("\\r\\n", "\n").replace("\\n", "\n").replace("&#xA;", "\n")
        .replace(Regex("<br\\s*/?>|</(?:div|p|li|tr)>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&amp;", "&")
    labels.forEach { label -> text = text.replace(Regex("([^\\n])\\s*(${Regex.escape(label)}\\s*:)", RegexOption.IGNORE_CASE), "$1\n$2") }
    val fields = linkedMapOf<String, String>()
    request.tableFields.forEach { (key, value) -> if (value.isMeaningful()) fields.putIfAbsent(normalizeDetailLabel(key), value.trim()) }
    text.lineSequence().forEach { line ->
        val index = line.indexOf(':')
        if (index > 0) {
            val key = normalizeDetailLabel(line.substring(0, index))
            val value = line.substring(index + 1).trim()
            if (value.isMeaningful()) fields.putIfAbsent(key, value)
        }
    }
    return fields
}
private fun String.isMeaningful(): Boolean = isNotBlank() && lowercase() !in listOf("null", "информация отсутствует")
private fun normalizeDetailLabel(raw: String): String = normalizedClientCommentScope(raw.trimEnd(':').replace("Cерийный", "Серийный").replace("cерийный", "серийный"))

class RequestDetailsData(val request: SimpleOneRequest) {
    private val info = requestInformation(request)
    fun value(vararg labels: String): String = labels.firstNotNullOfOrNull { info[normalizeDetailLabel(it)]?.takeIf(String::isMeaningful) }.orEmpty()
    private fun first(vararg values: String): String = values.firstOrNull(String::isMeaningful)?.trim().orEmpty()
    val number = first(request.incomingNumber, value("Номер заявки Мультикарты"), request.shortDescription.takeIf { it.startsWith("SUTS") }.orEmpty(), request.number)
    val terminal = listOf(first(request.terminalId, value("ID терминал", "ID терминала")), value("ID СБП"))
        .flatMap { it.split(',', ';', '\n', '\r') }.map(String::trim).filter(String::isMeaningful).distinctBy { it.lowercase() }.joinToString(", ")
    val tin = first(value("ИНН ТСП"))
    val address = normalizedClosedAddress(first(request.address, value("Адрес установки терминала", "Адрес ТСП")))
    val legalName = first(request.customer, value("Заказчик")).replace("OOO", "ООО").replace("OОO", "ООО")
    val type = first(value("Тип заявки"), request.requestType)
    private val deadline = first(value("Предельный срок СУТС"), request.deadline,
        if (request.source == SimpleOneRequestSource.Closed) request.resolvedAt else "")
    val deadlineText = parseSimpleOneDate(deadline)?.format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru"))) ?: deadline
    fun sla(now: LocalDateTime = LocalDateTime.now()): RequestDetailSLA? {
        if (request.source == SimpleOneRequestSource.Closed) return null
        val start = parseSimpleOneDate(first(value("Время создания в МК"), request.registeredAt)) ?: return null
        val end = parseSimpleOneDate(first(value("Предельный срок СУТС"), request.deadline)) ?: return null
        if (!end.isAfter(start)) return null
        val remaining = Duration.between(now, end)
        val minutes = remaining.abs().toMinutes()
        return RequestDetailSLA(
            (Duration.between(start, now).seconds.toDouble() / Duration.between(start, end).seconds).toFloat().coerceIn(0f, 1f),
            "${if (remaining.isNegative) "Просрочено на" else "Осталось"} ${minutes / 60} ч. ${minutes % 60} м.",
            remaining.isNegative,
        )
    }
    val installType = first(value("Тип устанавливаемого ТО", "Тип терминала"))
    val showsChatQuestions = installType.lowercase() in listOf("fiscal_pos", "universal_pos")
    val showsEvotorDrivers = installType == "intel_pin"
    private val vendor = first(value("Производитель устанавливаемого ТО", "Терминал вендора POS")).vendorText()
    private val model = first(value("Модель устанавливаемого ТО"), request.terminalModel, value("Модель POS-терминала", "Модель POS", "Модель терминала"))
    private val serial = value("Оборудование POS")
    private val pinSerial = value("Оборудование Pin Pad")
    private val pinModel = value("Модель PIN-Pad", "Модель PINPad", "Модель Пин-Пада")
    private val uninstallSerial = value("Серийный номер демонтируемого ТО", "Номер принятого оборудования POS")
    private val uninstallPinSerial = value("Серийный номер демонтируемого PIN", "Номер принятого оборудования PIN")
    val additionalInfo: String = first(value("Доп. информация"), request.additionalInformation)
        .replace(Regex("^null(?:#CC)?\\s*", RegexOption.IGNORE_CASE), "").trim().let {
            if (request.source == SimpleOneRequestSource.Closed && it.filterNot(Char::isWhitespace).let { raw -> raw.length >= 32 && raw.all(Char::isDigit) }) request.shortDescription else it
        }
    val sections: List<RequestDetailSection> = buildList {
        fun addSection(id: String, title: String, vararg pairs: Pair<String, String>, always: Boolean = true) {
            val fields = pairs.filter { it.second.isNotBlank() }.map { RequestDetailField(it.first, it.second) }
            if (always || fields.isNotEmpty()) add(RequestDetailSection(id, title, fields))
        }
        addSection("task", "Задача", "Дата создания" to date(first(value("Время создания в МК"), request.registeredAt)),
            "Статус" to first(value("МК Статус"), request.multicardState, request.state), "Статус SimpleOne" to request.state,
            "Код статуса" to request.stateRaw, "Исполнитель" to request.assignedUser, "Рабочая группа" to request.assignmentGroup,
            "Приоритет" to request.priority, "Причина ожидания" to request.waitingReason, "Телефон" to request.contactPhone,
            "Доп. инфо" to additionalInfo, "Выполнена" to date(request.completedAt), "Закрыта" to date(request.closedAt))
        addSection("closureCode", "Код закрытия", "" to first(request.closureCode, value("Код закрытия")), always = false)
        addSection("resolution", "Решение", "" to first(request.resolution, value("Решение")), always = false)
        addSection("warehouse", "Склад", "Город склада" to value("Город склада"), "Сотрудник склада" to value("МК Сотрудник склада"))
        addSection("client", "Клиент", "" to legalName, "ИНН ТСП" to tin, "Адрес" to address,
            "Контакт" to first(request.contactPerson, value("Kонтактное лицо", "Контактное лицо")), "Терминал" to terminal)
        addSection("install", "Установка", "Производитель" to vendor, "Модель терминала" to model.ifBlank { "Информация отсутствует" },
            "Тип терминала" to when (installType) { "portable_pos" -> "Переносной POS-терминал"; "stationary_pos", "stat_pos" -> "Стационарный POS-терминал"; else -> installType },
            "S/N терминала" to serial, "S/N PINPad" to pinSerial, "Модель PINPad" to pinModel.takeIf { pinSerial.isNotBlank() }.orEmpty(),
            "Тип PINPad" to if (pinSerial.isNotBlank()) "Внешняя PIN-клавиатура" else "")
        if (normalizedClientCommentScope(type) in listOf("replacement", "dismounting", "замена", "демонтаж")) {
            addSection("dismantle", "Демонтаж", "Терминал" to first(value("Терминал вендора POS"), vendor).vendorText(),
                "Модель терминала" to first(value("Модель демонтируемого ТО"), model), "S/N терминала" to uninstallSerial,
                "PINPad" to value("Терминал вендора PIN").takeIf { uninstallPinSerial.isNotBlank() }.orEmpty().vendorText(),
                "Модель PINPad" to pinModel.takeIf { uninstallPinSerial.isNotBlank() }.orEmpty(), "S/N PINPad" to uninstallPinSerial,
                "Факт терминал" to value("Терминал вендора POS").vendorText())
        }
        addSection("equipment", "Оборудование", "Оборудование POS" to serial, "Оборудование Pin Pad" to pinSerial,
            "Принятое оборудование POS" to value("Номер принятого оборудования POS"), "Принятое оборудование PIN" to value("Номер принятого оборудования PIN"))
    }
    val managerMessage = listOf(number, legalName.ifBlank { "Наименование юр. лица (ИП или ООО):" }, labeled("ИНН", tin), labeled("ТИД", terminal), labeled("Адрес", address)).joinToString("\n")
    val kaAndPinMessage = (listOf("Здравствуйте!", "", number, labeled("Тид", terminal), labeled("ИНН", tin), address.ifBlank { "Адрес:" }, labeled("S/N", first(serial, uninstallSerial)), "", "Прошу выслать новый КА и пин")).joinToString("\n")
    val ofdMessage = listOf("Здравствуйте!", "", number, labeled("Тид", terminal), labeled("ИНН", tin), labeled("S/N", first(serial, uninstallSerial)), "РНМ:", "КА:", "", "Прошу активировать тариф ОФД").joinToString("\n")
    val autoregMessage: String get() {
        val additional = additionalInfo.split(';').filter { ':' in it }.associate { normalizeDetailLabel(it.substringBefore(':')) to it.substringAfter(':').trim() }
        val nested = additional["оквэд"].orEmpty().takeIf { normalizeDetailLabel(it.substringBefore(':')) == "сно" }?.substringAfter(':')?.trim().orEmpty()
        return listOf(number, labeled("ИНН", tin), labeled("ТИД", terminal), labeled("СНО", first(nested, additional["сно"].orEmpty())),
            labeled("ФН", additional["фн"].orEmpty()), labeled("Адрес", address), labeled("Услуги", additional["услуги"].orEmpty()),
            labeled("ТМТ", additional["тмт"].orEmpty()), "", "Прошу новый код автореги").joinToString("\n")
    }
    private fun labeled(label: String, value: String) = "$label:${if (value.isBlank()) "" else " $value"}"
    private fun String.vendorText(): String = if (length <= 4) uppercase() else this
    private fun date(raw: String): String = parseSimpleOneDate(raw)?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) ?: raw
}
