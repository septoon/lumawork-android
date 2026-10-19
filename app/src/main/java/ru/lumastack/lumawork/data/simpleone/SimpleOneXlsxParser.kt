package ru.lumastack.lumawork.data.simpleone

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.roundToLong
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequestSource

internal object SimpleOneXlsxParser {
    fun parse(bytes: ByteArray): List<SimpleOneRequest> {
        return readRows(bytes, requiredHeader = "Номер заявки")
            .asSequence()
            .mapNotNull(::makeRequest)
            .filter { isIncludedStatus(it.state) || isReturnEquipment(it.requestType) }
            .sortedByDescending { it.resolvedDateTime() ?: LocalDateTime.MIN }
            .toList()
    }

    internal fun readRows(bytes: ByteArray, requiredHeader: String? = null): List<Map<String, String>> {
        val parts = readParts(bytes)
        val worksheet = parts.entries
            .filter { it.key.startsWith("xl/worksheets/") && it.key.endsWith(".xml") }
            .minByOrNull { it.key }
            ?.value
            ?: throw SimpleOneXlsxException("В файле не найдены данные.")
        val sharedStrings = parts["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        return parseWorksheet(worksheet, sharedStrings, requiredHeader)
    }

    /** Generic worksheet text for Assistant attachments; request-import header rules stay in readRows. */
    internal fun readTextWorksheets(bytes: ByteArray): List<String> {
        val parts = readParts(bytes)
        val shared = parts["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        return parts.entries.filter { it.key.startsWith("xl/worksheets/") && it.key.endsWith(".xml") }
            .sortedBy { it.key.substringAfterLast('/').removePrefix("sheet").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
            .take(7).map { (_, xml) ->
                val output = StringBuilder(); val value = StringBuilder()
                var type = ""; var reading = false
                parseXml(xml, object : DefaultHandler() {
                    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                        when (elementName(localName, qName)) {
                            "c" -> { type = attributes?.getValue("t").orEmpty(); value.clear() }
                            "v", "t" -> reading = true
                        }
                    }
                    override fun characters(ch: CharArray, start: Int, length: Int) {
                        if (reading && value.length < MAX_CELL_CHARACTERS) value.append(ch, start, minOf(length, MAX_CELL_CHARACTERS - value.length))
                    }
                    override fun endElement(uri: String?, localName: String?, qName: String?) {
                        when (elementName(localName, qName)) {
                            "v", "t" -> reading = false
                            "c" -> if (output.length < 12500) {
                                val text = if (type == "s") value.toString().toIntOrNull()?.let(shared::getOrNull).orEmpty() else value.toString()
                                output.append(text.take(12500 - output.length)); if (output.length < 12500) output.append('\t')
                            }
                            "row" -> if (output.length < 12500) output.append('\n')
                        }
                    }
                })
                output.toString().trim()
            }
    }

    private fun readParts(bytes: ByteArray): Map<String, ByteArray> {
        if (bytes.size > MAX_ARCHIVE_BYTES) throw SimpleOneXlsxException("Размер файла превышает 30 МиБ.")
        val parts = linkedMapOf<String, ByteArray>()
        var totalUncompressed = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val path = entry.name.replace('\\', '/')
                val isNeeded = path == "xl/sharedStrings.xml" ||
                    (path.startsWith("xl/worksheets/") && path.endsWith(".xml") && !path.contains("/_rels/"))
                val data = if (isNeeded) readLimited(zip, minOf(MAX_PART_BYTES, MAX_UNCOMPRESSED_BYTES - totalUncompressed)) else null
                if (data != null) {
                    totalUncompressed += data.size
                    if (parts.put(path, data) != null) throw SimpleOneXlsxException("В файле повторяются разделы.")
                } else {
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        totalUncompressed += count
                        if (totalUncompressed > MAX_UNCOMPRESSED_BYTES) throw SimpleOneXlsxException("Файл содержит слишком много данных.")
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return parts
    }

    private fun readLimited(zip: ZipInputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw SimpleOneXlsxException("Файл содержит слишком много данных.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val handler = SharedStringsHandler()
        parseXml(bytes, handler)
        return handler.values
    }

    private fun parseWorksheet(bytes: ByteArray, sharedStrings: List<String>, requiredHeader: String?): List<Map<String, String>> {
        val handler = WorksheetHandler(sharedStrings)
        parseXml(bytes, handler)
        if (requiredHeader != null && handler.headers.values.none { normalizeHeader(it) == normalizeHeader(requiredHeader) }) {
            throw SimpleOneXlsxException("В файле не найдена колонка «Номер заявки».")
        }
        return handler.rows
    }

    private fun parseXml(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            setSafeFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setSafeFeature("http://xml.org/sax/features/external-general-entities", false)
            setSafeFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setSafeFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        try {
            factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
        } catch (error: SimpleOneXlsxException) {
            throw error
        } catch (error: Exception) {
            throw SimpleOneXlsxException("Не удалось прочитать файл.", error)
        }
    }

    private fun SAXParserFactory.setSafeFeature(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private fun makeRequest(row: Map<String, String>): SimpleOneRequest? {
        val number = row.value("Номер заявки")
        if (number.isBlank()) return null
        val information = row.firstValue("Информация", "Доп. информация", "Дополнительная информация")
        val info = parseInfo(information)
        val description = row.value("Описание")
        val closedAt = normalizeExcelDate(
            row.firstValue("Дата закрытия в МК", "Дата закрытия МК", "Дата закрытия"),
        )
        val completedAt = normalizeExcelDate(
            row.firstValue("Выполнена", "Время Выполнена", "Время \"Выполнена\""),
        )
        val registeredAt = normalizeExcelDate(
            row.firstValue("Время регистрации", "Дата регистрации", "Зарегистрирована"),
        )
        return SimpleOneRequest(
            source = SimpleOneRequestSource.Closed,
            sysId = row.value("sys_id"),
            number = number,
            incomingNumber = firstNonEmpty(
                row.value("Входящий номер"),
                info.value("Входящий номер", "Номер заявки Мультикарты", "Номер заявки Мультикарта"),
                number,
            ),
            registeredAt = registeredAt,
            state = row.value("Статус").ifBlank { "Закрыта" },
            stateRaw = row.value("Код статуса"),
            multicardState = firstNonEmpty(row.value("МК Статус"), info.value("МК Статус", "Статус заявки Мультикарта")),
            waitingReason = row.value("Причина ожидания"),
            shortDescription = row.value("Краткое описание"),
            initiator = row.value("Инициатор"),
            priority = row.value("Приоритет"),
            clientServiceParent = row.value("Родительская услуга"),
            clientService = row.value("Услуга"),
            assignmentGroup = row.firstValue("Рабочая группа", "Группа назначения"),
            requestType = row.value("Тип заявки"),
            address = firstNonEmpty(
                row.firstValue("Адрес установки терминала", "Адрес ТСП", "Адрес"),
                info.value("Адрес установки терминала", "Адрес ТСП", "Адрес"),
            ),
            customer = firstNonEmpty(
                row.firstValue("Заказчик", "Наименование юр.лица", "Наименование юр. лица", "Клиент"),
                info.value("Заказчик", "Наименование юр.лица", "Наименование юр. лица", "Клиент"),
            ),
            deadline = normalizeExcelDate(row.firstValue("Предельный срок СУТС", "Предельный срок")),
            resolvedAt = normalizeExcelDate(row.value("Дата выполнения")).ifBlank { completedAt.ifBlank { closedAt } },
            completedAt = completedAt,
            closedAt = closedAt,
            assignedUser = row.firstValue("Исполнитель.Имя Фамилия", "Исполнитель"),
            assignedUserId = row.value("ID исполнителя"),
            terminalModel = firstNonEmpty(
                row.value("Модель POS-терминала"),
                info.value("Модель POS-терминала", "Модель POS", "Модель терминала"),
            ),
            terminalId = firstNonEmpty(
                row.firstValue("ID терминал", "ID терминала"),
                info.value("ID терминал", "ID терминала", "Оборудование POS"),
            ),
            contactPerson = row.firstValue("Контактное лицо", "Kонтактное лицо"),
            contactPhone = firstNonEmpty(
                row.firstValue("Номер телефона ТСП", "Телефон ТСП"),
                info.value("Номер телефона ТСП", "Телефон ТСП", "Телефон"),
            ),
            engineerComment = row.value("Комментарий инженера"),
            closureCode = firstNonEmpty(
                row.firstValue("Код закрытия", "Код закрытия МК", "Код решения"),
                info.value("Код закрытия", "Код закрытия МК", "Код решения"),
            ),
            resolution = firstNonEmpty(row.value("Решение"), info.value("Решение")),
            additionalInformation = information,
            description = description,
            installedFiscalStorageSerialNumber = firstNonEmpty(
                row.value("Серийный номер установленного ФН"),
                info.value("Серийный номер установленного ФН", "Cерийный номер установленного ФН"),
            ),
            ofdTariffActivationCode = firstNonEmpty(row.value("Использованный код активации тарифа ОФД"), info.value("Использованный код активации тарифа ОФД")),
            usedSimCard = firstNonEmpty(row.value("Использованная SIM карта"), info.value("Использованная SIM карта")),
            tableFields = row,
        )
    }

    private fun normalizeExcelDate(raw: String): String {
        val value = raw.trim()
        val serial = value.toDoubleOrNull() ?: return value
        if (serial < 1.0 || serial > 1_000_000.0) return value
        val base = LocalDate.of(1899, 12, 30).atStartOfDay()
        return base.plusSeconds((serial * 86_400.0).roundToLong()).format(NORMALIZED_DATE_FORMATTER)
    }

    private fun parseInfo(raw: String): Map<String, String> = raw.lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            line.substring(0, separator).trim().takeIf(String::isNotBlank)?.let { key ->
                key to line.substring(separator + 1).trim()
            }
        }
        .toMap()

    private fun Map<String, String>.value(vararg names: String): String {
        val targets = names.map(::normalizeHeader).toSet()
        return entries.firstOrNull { normalizeHeader(it.key) in targets }?.value?.trim().orEmpty()
    }

    private fun Map<String, String>.firstValue(vararg names: String): String =
        names.firstNotNullOfOrNull { name -> value(name).takeIf(String::isNotBlank) }.orEmpty()

    private fun normalizeHeader(raw: String): String = raw
        .replace('\u00a0', ' ')
        .trim()
        .replace('ё', 'е')
        .replace('Ё', 'Е')
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

    private fun isIncludedStatus(raw: String): Boolean {
        val status = normalizeHeader(raw)
        return status == "completed" || status == "closed" || status.contains("выполн") ||
            status.contains("закрыт") || status.contains("отказ") || status.contains("отклон")
    }

    private fun isReturnEquipment(raw: String): Boolean {
        val type = normalizeHeader(raw)
        return type == "returnequip" || type == "return_equip" || type.contains("возврат")
    }

    private fun firstNonEmpty(vararg values: String): String = values.firstOrNull(String::isNotBlank).orEmpty()

    private class SharedStringsHandler : DefaultHandler() {
        val values = mutableListOf<String>()
        private var insideItem = false
        private var insideText = false
        private val current = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (elementName(localName, qName)) {
                "si" -> {
                    insideItem = true
                    current.clear()
                }
                "t" -> if (insideItem) insideText = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (insideText) {
                if (current.length + length > MAX_CELL_CHARACTERS) throw SimpleOneXlsxException("Слишком длинное значение ячейки.")
                current.append(ch, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (elementName(localName, qName)) {
                "t" -> insideText = false
                "si" -> {
                    if (values.size >= MAX_SHARED_STRINGS) {
                        throw SimpleOneXlsxException("В файле слишком много записей.")
                    }
                    values += current.toString()
                    insideItem = false
                }
            }
        }
    }

    private class WorksheetHandler(private val sharedStrings: List<String>) : DefaultHandler() {
        val rows = mutableListOf<Map<String, String>>()
        val headers = mutableMapOf<Int, String>()
        private var rowIndex = 0
        private var currentRow = mutableMapOf<String, String>()
        private var cellReference = ""
        private var cellType = ""
        private var readingValue = false
        private val currentValue = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (elementName(localName, qName)) {
                "row" -> {
                    rowIndex = attributes?.getValue("r")?.toIntOrNull() ?: 0
                    currentRow = mutableMapOf()
                }
                "c" -> {
                    cellReference = attributes?.getValue("r").orEmpty()
                    cellType = attributes?.getValue("t").orEmpty()
                    currentValue.clear()
                }
                "v", "t" -> readingValue = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (readingValue) {
                if (currentValue.length + length > MAX_CELL_CHARACTERS) throw SimpleOneXlsxException("Слишком длинное значение ячейки.")
                currentValue.append(ch, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (elementName(localName, qName)) {
                "v", "t" -> readingValue = false
                "c" -> commitCell()
                "row" -> if (rowIndex > 1 && currentRow.isNotEmpty()) {
                    if (rows.size >= MAX_RECORDS) throw SimpleOneXlsxException("В файле больше 100000 записей. Загрузка не сохранена.")
                    rows += currentRow
                }
            }
        }

        private fun commitCell() {
            val column = columnIndex(cellReference)
            val raw = currentValue.toString().trim()
            val value = if (cellType == "s") raw.toIntOrNull()?.let(sharedStrings::getOrNull).orEmpty() else raw
            if (rowIndex == 1) {
                headers[column] = value
            } else {
                headers[column]?.takeIf(String::isNotBlank)?.let { currentRow[it] = value }
            }
        }

        private fun columnIndex(reference: String): Int = reference
            .takeWhile(Char::isLetter)
            .fold(0) { value, char -> value * 26 + (char.uppercaseChar().code - 'A'.code + 1) }
    }

    private fun elementName(localName: String?, qName: String?): String =
        localName?.takeIf(String::isNotBlank) ?: qName.orEmpty().substringAfter(':')

    private val NORMALIZED_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
    private const val MAX_ARCHIVE_BYTES = 30 * 1024 * 1024
    private const val MAX_PART_BYTES = 96 * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 128 * 1024 * 1024
    private const val MAX_SHARED_STRINGS = 1_000_000
    private const val MAX_RECORDS = 100_000
    private const val MAX_CELL_CHARACTERS = 1_000_000
}

internal class SimpleOneXlsxException(message: String, cause: Throwable? = null) : Exception(message, cause)
