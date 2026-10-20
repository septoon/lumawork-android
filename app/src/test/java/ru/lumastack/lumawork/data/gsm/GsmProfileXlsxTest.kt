package ru.lumastack.lumawork.data.gsm

import java.io.ByteArrayOutputStream
import java.time.YearMonth
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class GsmProfileXlsxTest {
    private val projects = listOf(GsmProjectOption("pos", "POS тест", "TEST-BUDGET"))

    @Test fun `imports account fields and next month end odometer without route or fuel history`() {
        val result = GsmProfileXlsx.parse(workbook(), YearMonth.of(2026, 9), projects)
        val p = result.profile
        assertEquals("Тестов Иван Иванович", p.employeeFullName)
        assertEquals("Тестов И.И.", p.employeeShortName)
        assertEquals("Тестов И. И.", p.employeeReportSignName)
        assertEquals("Проверкин Петр Петрович", p.authorizedFullName)
        assertEquals("Проверкин П. П.", p.authorizedShortName)
        assertEquals("Инженер", p.employeeJobTitle)
        assertEquals("ООО Тест", p.employeeCompany)
        assertEquals("Город, улица, 1", p.employeeAddress)
        assertEquals("+7(900)000-00-00", p.employeePhone)
        assertEquals("0012345678", p.driverLicenseNumber)
        assertEquals("00001234", p.fuelCardNumber)
        assertEquals("Тест Авто", p.carModel)
        assertEquals("А000АА 00", p.licensePlate)
        assertEquals(7.5, p.fuelNorm, 0.0)
        assertEquals(listOf("АИ-95"), p.fuelTypes)
        assertEquals(10330, p.defaultStartOdometer)
        assertEquals("2026-10", p.reportStartMonth)
        assertEquals("pos", p.posProjectId)
        assertNull(p.vehicleId)
        assertNull(p.armProjectId)
        assertEquals("ПЛ-ТК!AE8", result.sources["defaultStartOdometer"])
        assertTrue(result.notes.any { it.contains("остат") })
    }

    @Test fun `rejects another month without modifying draft`() {
        assertThrows(GsmXlsxFailure::class.java) { GsmProfileXlsx.parse(workbook(), YearMonth.of(2026, 8), projects) }
    }

    @Test fun `does not invent project ID if directory is missing or ambiguous`() {
        assertNull(GsmProfileXlsx.parse(workbook(), YearMonth.of(2026, 9), emptyList()).profile.posProjectId)
        assertNull(GsmProfileXlsx.parse(workbook(), YearMonth.of(2026, 9), projects + projects[0].copy(id = "other")).profile.posProjectId)
    }

    @Test fun `rejects missing cached final odometer and negative or fractional mileage`() {
        for (value in listOf("", "-1", "10330.5")) {
            assertThrows(GsmXlsxFailure::class.java) { GsmProfileXlsx.parse(workbook(end = value), YearMonth.of(2026, 9), projects) }
        }
    }

    @Test fun `resolves simple reference formulas but never evaluates arbitrary formulas`() {
        assertThrows(GsmXlsxFailure::class.java) { GsmProfileXlsx.parse(workbook(end = "=SUM(A1:A3)"), YearMonth.of(2026, 9), projects) }
        assertEquals("Тест Авто", GsmProfileXlsx.parse(workbook(), YearMonth.of(2026, 9), projects).profile.carModel)
    }

    @Test fun `workbook project name disambiguates duplicate server budgets without choosing a legacy alias`() {
        val directory = projects + projects[0].copy(id = "legacy", name = "TEST-BUDGET")
        val result = GsmProfileXlsx.parse(workbook(catalog = true), YearMonth.of(2026, 9), directory)
        assertEquals("pos", result.profile.posProjectId)
        assertTrue(result.sources.getValue("posProjectId").contains("Список проектов!A7,B7"))
    }

    @Test fun `mixed Latin and Cyrillic ARM budgets remain ambiguous`() {
        val directory = projects + listOf(GsmProjectOption("a1", "АРМ 1", "ARM-1"), GsmProjectOption("a2", "АРМ 2", "ARM-2"))
        val result = GsmProfileXlsx.parse(workbook(ambiguousArm = true), YearMonth.of(2026, 9), directory)
        assertNull(result.profile.armProjectId)
        assertTrue(result.notes.any { it.contains("ARM") })
    }

    @Test fun `December report seeds January of next year`() {
        val result = GsmProfileXlsx.parse(workbook(month = "декабрь"), YearMonth.of(2026, 12), projects)
        assertEquals("2027-01", result.profile.reportStartMonth)
    }

    @Test fun `rejects invalid zip and excessive input before XML parsing`() {
        assertThrows(GsmXlsxFailure::class.java) { GsmProfileXlsx.parse("invalid".toByteArray(), YearMonth.of(2026, 9), projects) }
        assertThrows(GsmXlsxFailure::class.java) { GsmProfileXlsx.read(java.io.ByteArrayInputStream(ByteArray(GsmProfileXlsx.MAX_FILE_BYTES + 1))) }
    }

    @Test fun `empty detection ignores default month but considers any real profile data`() {
        assertTrue(GsmProfile().isEmptyForOnboarding)
        assertFalse(GsmProfile(fuelCardNumber = "1").isEmptyForOnboarding)
        assertFalse(GsmProfile(defaultStartOdometer = 1).isEmptyForOnboarding)
    }

    @Test fun `local real workbook contract when explicitly supplied outside repository`() {
        val path = System.getenv("GSM_LOCAL_XLSX") ?: return
        val r = GsmProfileXlsx.parse(java.io.File(path).readBytes(), YearMonth.of(2026, 9), emptyList())
        assertEquals(212996, r.profile.defaultStartOdometer)
        assertEquals("2026-10", r.profile.reportStartMonth)
        assertEquals(9.4, r.profile.fuelNorm, 0.0)
        assertEquals(listOf("АИ-95"), r.profile.fuelTypes)
        assertTrue(GsmPhoneFormatter.isComplete(r.profile.employeePhone))
        assertEquals(3, r.profile.employeeFullName.split(' ').size)
        assertEquals("ПЛ-ТК!AE8", r.sources["defaultStartOdometer"])
    }

    private fun workbook(end: String = "10330", month: String = "сентябрь", year: String = "2026", ambiguousArm: Boolean = false, catalog: Boolean = false): ByteArray {
        val sheets = linkedMapOf(
            "ПЛ-ТК" to mapOf("M3" to month, "Q3" to year, "B5" to "ФИО сотрудника,должность, компания",
                "E5" to "Тестов Иван Иванович, Инженер, ООО Тест", "E6" to "Город, улица, 1, +7(900)000-00-00",
                "AC5" to "0012345678", "AC6" to "00001234", "J7" to "='Отчет по ТК'!B3", "AE7" to "А000АА 00",
                "B8" to "Показания одометра на начало периода", "Q8" to "Показания одометра на конец периода",
                "N8" to "10000", "AE8" to end, "D11" to "TEST-BUDGET", "AJ11" to "POS",
                "C44" to "Тестов Иван Иванович", "C45" to "(ФИО сотрудника)",
                "C47" to "Проверкин Петр Петрович", "C48" to "(ФИО уполномоченного сотрудника)") +
                if (ambiguousArm) mapOf("D12" to "ARM-1", "AJ12" to "ARM", "D13" to "ARM-2", "AJ13" to "АРМ") else emptyMap(),
            "СЗ-ТК" to mapOf("C51" to "Сотрудник", "Q51" to "Тестов И.И."),
            "Отчет по ТК " to mapOf("A2" to "Ф.И.О.", "A3" to "Тестов Иван Иванович", "B3" to "Тест Авто", "C3" to "7.5",
                "D3" to "00001234", "A4" to "Вид топлива", "A5" to "АИ-95", "D5" to "транзакций не было",
                "A6" to "Итого по:", "B6" to "АИ-95", "A8" to "ФИО Тестов И. И."),
        )
        if (catalog) sheets["Список проектов"] = mapOf("A7" to "POS тест", "B7" to "TEST-BUDGET")
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun part(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            part("xl/workbook.xml", """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                sheets.keys.mapIndexed { i, name -> "<sheet name=\"${escape(name)}\" r:id=\"r${i + 1}\"/>" }.joinToString("") + "</sheets></workbook>")
            part("xl/_rels/workbook.xml.rels", "<Relationships>" + sheets.keys.indices.joinToString("") { "<Relationship Id=\"r${it + 1}\" Target=\"worksheets/custom${it + 1}.xml\"/>" } + "</Relationships>")
            sheets.values.forEachIndexed { i, cells ->
                part("xl/worksheets/custom${i + 1}.xml", "<worksheet><sheetData>" + cells.entries.joinToString("") { (ref, value) ->
                    if (value.startsWith('=')) "<c r=\"$ref\"><f>${escape(value.drop(1))}</f></c>"
                    else "<c r=\"$ref\" t=\"inlineStr\"><is><t>${escape(value)}</t></is></c>"
                } + "</sheetData></worksheet>")
            }
        }
        return out.toByteArray()
    }
}
