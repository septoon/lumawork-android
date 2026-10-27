package ru.lumastack.lumawork.data.simpleone

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

/** A real OOXML workbook, with text cells preserving IDs, leading zeroes and formula-like text. */
internal object ClosedRequestsXlsxExport {
    fun create(requests: List<SimpleOneRequest>): ByteArray {
        if (requests.size > 100_000) throw SimpleOneXlsxException("Архив слишком велик для выгрузки.")
        val columns = linkedSetOf<String>().apply {
            addAll(values(SimpleOneRequest("", "")).keys)
            requests.forEach { addAll(it.tableFields.keys) }
        }.toList()
        return createTable("Закрытые заявки", columns, requests.map { request ->
            val fields = values(request)
            columns.map { fields[it].orEmpty() }
        })
    }

    internal fun createTable(sheetName: String, columns: List<String>, rows: List<List<String>>): ByteArray {
        if (rows.size > 1_048_575) throw SimpleOneXlsxException("Архив слишком велик для выгрузки.")
        if (columns.size > 16_384) throw SimpleOneXlsxException("Слишком много колонок для XLSX.")
        val output = ByteArrayOutputStream()
        var xmlSize = 0L
        ZipOutputStream(output).use { zip ->
            fun xml(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                xmlSize += bytes.size
                if (xmlSize > 128L * 1024 * 1024 || output.size() > MAX_BYTES) {
                    throw SimpleOneXlsxException("Архив слишком велик для выгрузки.")
                }
                zip.write(bytes)
            }
            fun part(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                xml(content)
                zip.closeEntry()
            }
            part("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
            part("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            part("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${sheetName.xmlEscaped()}" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            part("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            xml("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            fun row(index: Int, cells: List<String>) {
                xml("<row r=\"$index\">")
                cells.forEachIndexed { column, value ->
                    if (value.length > 32_767) throw SimpleOneXlsxException("Значение ячейки превышает ограничение Excel в 32767 символов.")
                    xml("<c r=\"${columnName(column)}$index\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${value.xmlEscaped()}</t></is></c>")
                }
                xml("</row>")
            }
            row(1, columns)
            rows.forEachIndexed { index, cells -> row(index + 2, cells) }
            xml("</sheetData></worksheet>")
            zip.closeEntry()
        }
        if (output.size() > MAX_BYTES) throw SimpleOneXlsxException("Размер файла превышает 30 МиБ.")
        return output.toByteArray()
    }

    private fun values(request: SimpleOneRequest): Map<String, String> = linkedMapOf(
        "Номер заявки" to request.number,
        "Краткое описание" to request.shortDescription,
        "Статус" to request.state,
        "Тип заявки" to request.requestType,
        "Адрес установки терминала" to request.address,
        "Заказчик" to request.customer,
        "Модель POS-терминала" to request.terminalModel,
        "Описание" to request.description,
        "ID терминал" to request.terminalId,
        "Комментарий инженера" to request.engineerComment,
        "Информация" to request.additionalInformation,
        "Исполнитель.Имя Фамилия" to request.assignedUser,
        "Kонтактное лицо" to request.contactPerson,
        "Предельный срок СУТС" to request.deadline,
        "Серийный номер установленного ТО" to "",
        "Серийный номер демонтируемого ТО" to "",
        "Оборудование POS" to "",
        "Номер принятого оборудования POS" to "",
        "Оборудование Pin Pad" to "",
        "Номер принятого оборудования PIN" to "",
        "Дата закрытия в МК" to request.closedAt,
        "Выполнена" to request.completedAt,
        "Время регистрации" to request.registeredAt,
        "Код закрытия" to request.closureCode,
        "Решение" to request.resolution,
        "sys_id" to request.sysId,
        "Входящий номер" to request.incomingNumber,
        "Код статуса" to request.stateRaw,
        "МК Статус" to request.multicardState,
        "Причина ожидания" to request.waitingReason,
        "Рабочая группа" to request.assignmentGroup,
        "Инициатор" to request.initiator,
        "Приоритет" to request.priority,
        "Родительская услуга" to request.clientServiceParent,
        "Услуга" to request.clientService,
        "Дата выполнения" to request.resolvedAt,
        "ID исполнителя" to request.assignedUserId,
        "Номер телефона ТСП" to request.contactPhone,
        "Серийный номер установленного ФН" to request.installedFiscalStorageSerialNumber,
        "Использованный код активации тарифа ОФД" to request.ofdTariffActivationCode,
        "Использованная SIM карта" to request.usedSimCard,
    ).apply {
        request.tableFields.forEach { (key, value) ->
            if (get(key).isNullOrBlank()) put(key, value)
        }
    }

    private fun columnName(index: Int): String {
        var value = index + 1
        val name = StringBuilder()
        while (value > 0) {
            name.append(('A'.code + (value - 1) % 26).toChar())
            value = (value - 1) / 26
        }
        return name.reverse().toString()
    }

    private fun String.xmlEscaped(): String = buildString(length) {
        this@xmlEscaped.forEach { character ->
            append(when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\r' -> "&#13;"
                else -> if (character == '\t' || character == '\n' || character.code >= 0x20) character.toString() else ""
            })
        }
    }

    private const val MAX_BYTES = 30 * 1024 * 1024
}
