package ru.lumastack.lumawork.data.simpleone

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleOneXlsxParserTest {
    @Test
    fun `parser preserves all 25 export columns and maps detail fields`() {
        val closedDateSerial = LocalDate.of(2026, 8, 12).toEpochDay() -
            LocalDate.of(1899, 12, 30).toEpochDay() + 0.5
        val headers = closedExportHeaders()
        val values = listOf(
            "REQ001",
            "Замена терминала",
            "Закрыта",
            "replaceEquip",
            "Симферополь, ул. Ленина, 1",
            "ООО Ромашка",
            "Verifone V240m",
            "Полное описание",
            "T-42",
            "Работы выполнены",
            "Номер телефона ТСП: +7 999 100-20-30\nМК Статус: Выполнено",
            "Иван Инженеров",
            "Пётр Петров",
            "2026-08-12 15:30:00",
            "NEW-POS-1",
            "OLD-POS-1",
            "POS-SET",
            "RETURN-POS-1",
            "PIN-SET",
            "RETURN-PIN-1",
            closedDateSerial.toString(),
            closedDateSerial.toString(),
            "2026-08-10 09:00:00",
            "Успешно",
            "Оборудование заменено",
        )

        val records = SimpleOneXlsxParser.parse(makeSimpleOneXlsx(headers, listOf(values)))

        val record = records.single()
        assertEquals("REQ001", record.number)
        assertEquals("+7 999 100-20-30", record.contactPhone)
        assertEquals("T-42", record.terminalId)
        assertEquals("2026-08-12 12:00:00", record.closedAt)
        assertEquals("RETURN-POS-1", record.tableFields["Номер принятого оборудования POS"])
        assertEquals("RETURN-PIN-1", record.tableFields["Номер принятого оборудования PIN"])
        assertEquals(25, record.tableFields.size)
    }

    @Test
    fun `parser excludes open rows but keeps return equipment`() {
        val headers = listOf("Номер заявки", "Статус", "Тип заявки")
        val rows = listOf(
            listOf("REQ-OPEN", "В работе", "install"),
            listOf("REQ-RETURN", "В работе", "Возврат ТО"),
            listOf("REQ-CLOSED", "Выполнена", "install"),
        )

        val records = SimpleOneXlsxParser.parse(makeSimpleOneXlsx(headers, rows))

        assertEquals(listOf("REQ-RETURN", "REQ-CLOSED"), records.map { it.number })
        assertTrue(records.all { it.tableFields.isNotEmpty() })
    }
}

internal fun closedExportHeaders(): List<String> = listOf(
    "Номер заявки",
    "Краткое описание",
    "Статус",
    "Тип заявки",
    "Адрес установки терминала",
    "Заказчик",
    "Модель POS-терминала",
    "Описание",
    "ID терминал",
    "Комментарий инженера",
    "Информация",
    "Исполнитель.Имя Фамилия",
    "Kонтактное лицо",
    "Предельный срок СУТС",
    "Серийный номер установленного ТО",
    "Серийный номер демонтируемого ТО",
    "Оборудование POS",
    "Номер принятого оборудования POS",
    "Оборудование Pin Pad",
    "Номер принятого оборудования PIN",
    "Дата закрытия в МК",
    "Выполнена",
    "Время регистрации",
    "Код закрытия",
    "Решение",
)

internal fun makeSimpleOneXlsx(headers: List<String>, rows: List<List<String>>): ByteArray {
    val sharedStrings = (headers + rows.flatten())
        .filterNot { it.toDoubleOrNull() != null }
        .distinct()
    val indexes = sharedStrings.withIndex().associate { it.value to it.index }
    val worksheet = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
        append("<row r=\"1\">")
        headers.forEachIndexed { index, value -> appendSharedCell(index, 1, indexes.getValue(value)) }
        append("</row>")
        rows.forEachIndexed { rowIndex, row ->
            append("<row r=\"${rowIndex + 2}\">")
            row.forEachIndexed { column, value ->
                val numeric = value.toDoubleOrNull()
                if (numeric != null) appendNumericCell(column, rowIndex + 2, value)
                else appendSharedCell(column, rowIndex + 2, indexes.getValue(value))
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }
    val stringsXml = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sharedStrings.forEach { append("<si><t>${it.xmlEscaped()}</t></si>") }
        append("</sst>")
    }
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
        zip.write(stringsXml.toByteArray())
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
        zip.write(worksheet.toByteArray())
        zip.closeEntry()
    }
    return output.toByteArray()
}

private fun StringBuilder.appendSharedCell(column: Int, row: Int, index: Int) {
    append("<c r=\"${columnName(column)}$row\" t=\"s\"><v>$index</v></c>")
}

private fun StringBuilder.appendNumericCell(column: Int, row: Int, value: String) {
    append("<c r=\"${columnName(column)}$row\"><v>$value</v></c>")
}

private fun columnName(index: Int): String {
    var value = index + 1
    val result = StringBuilder()
    while (value > 0) {
        val remainder = (value - 1) % 26
        result.append(('A'.code + remainder).toChar())
        value = (value - 1) / 26
    }
    return result.reverse().toString()
}

private fun String.xmlEscaped(): String = replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
