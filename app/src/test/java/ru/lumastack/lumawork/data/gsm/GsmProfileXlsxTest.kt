package ru.lumastack.lumawork.data.gsm

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.time.YearMonth
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class GsmProfileXlsxTest {
    private val projects = listOf(GsmProjectOption("pos", "POS тест", "TEST-BUDGET"))

    @Test fun `imports prefixed XML with arbitrary relationship prefixes and shared strings`() {
        val parts = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(workbook())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                parts[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        parts["xl/worksheets/custom3.xml"] = parts.getValue("xl/worksheets/custom3.xml")
            .replace("<c r=\"B3\" t=\"inlineStr\"><is><t>Тест Авто</t></is></c>", "<c r=\"B3\" t=\"s\"><v>0</v></c>")
        parts["xl/sharedStrings.xml"] = "<sst><si><t>Тест Авто</t></si></sst>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, xml) in parts) {
                val relationships = path.endsWith(".rels")
                val prefix = if (relationships) "p" else "s"
                val namespace = if (relationships) "http://schemas.openxmlformats.org/package/2006/relationships"
                    else "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                val prefixed = Regex("(<\\/?)([A-Za-z][A-Za-z0-9]*)").replace(xml) { "${it.groupValues[1]}$prefix:${it.groupValues[2]}" }
                    .replaceFirst(">", " xmlns:$prefix=\"$namespace\">")
                    .replace("xmlns:r=", "xmlns:links=").replace(" r:id=", " links:id=")
                zip.putNextEntry(ZipEntry(path)); zip.write(prefixed.toByteArray()); zip.closeEntry()
            }
        }
        val profile = GsmProfileXlsx.parse(out.toByteArray(), YearMonth.of(2026, 9), projects).profile
        assertEquals("Тестов Иван Иванович", profile.employeeFullName)
        assertEquals("Тест Авто", profile.carModel)
        assertEquals(10330, profile.defaultStartOdometer)
        assertEquals("pos", profile.posProjectId)
    }

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
        assertTrue(result.warnings.isEmpty())
    }

    @Test fun `another month imports recognized fields with period warning`() {
        val result = GsmProfileXlsx.parse(workbook(), YearMonth.of(2026, 8), projects)
        assertEquals("Тестов Иван Иванович", result.profile.employeeFullName)
        assertEquals("2026-10", result.profile.reportStartMonth)
        assertTrue(result.notes.any { it.contains("2026-08") && it.contains("2026-09") })
    }

    @Test fun `shifted header and missing norm still import other fields and name fallback`() {
        val bytes = workbook(overrides = mapOf("Отчет по ТК " to mapOf(
            "A3" to "", "C3" to "", "A4" to "", "A5" to "Вид топлива", "A6" to "АИ-95", "A7" to "Итого по:",
        )))
        val result = GsmProfileXlsx.parse(bytes, YearMonth.of(2026, 9), projects)
        assertEquals("Тестов Иван Иванович", result.profile.employeeFullName)
        assertEquals("Инженер", result.profile.employeeJobTitle)
        assertEquals("ООО Тест", result.profile.employeeCompany)
        assertEquals(10330, result.profile.defaultStartOdometer)
        assertEquals(listOf("АИ-95"), result.profile.fuelTypes)
        assertEquals(0.0, result.profile.fuelNorm, 0.0)
        assertFalse(result.sources.containsKey("fuelNorm"))
        assertTrue(result.sources.getValue("employeeFullName").contains("ПЛ-ТК!E5"))
        assertTrue(result.notes.any { it.contains("Отчет по ТК!C3") && it.contains("вручную") })
        assertTrue(result.warnings.any { it.contains("Отчет по ТК!C3") })
    }

    @Test fun `shifted fuel table with complete data needs no manual repair`() {
        val result = GsmProfileXlsx.parse(workbook(overrides = mapOf("Отчет по ТК " to mapOf(
            "A4" to "", "A5" to "Вид топлива", "A6" to "АИ-95", "A7" to "Итого по:",
        ))), YearMonth.of(2026, 9), projects)
        assertEquals(listOf("АИ-95"), result.profile.fuelTypes)
        assertEquals("Отчет по ТК!A6", result.sources["fuelTypes"])
        assertTrue(result.warnings.isEmpty())
    }

    @Test fun `missing company does not prevent recovering name and job title`() {
        val result = GsmProfileXlsx.parse(workbook(overrides = mapOf(
            "Отчет по ТК " to mapOf("A3" to ""),
            "ПЛ-ТК" to mapOf("E5" to "Тестов Иван Иванович, Инженер"),
        )), YearMonth.of(2026, 9), projects)
        assertEquals("Тестов Иван Иванович", result.profile.employeeFullName)
        assertEquals("Инженер", result.profile.employeeJobTitle)
        assertEquals("", result.profile.employeeCompany)
        assertTrue(result.sources.containsKey("employeeJobTitle"))
        assertFalse(result.sources.containsKey("employeeCompany"))
        assertTrue(result.warnings.any { it.contains("Компания") && it.contains("ПЛ-ТК!E5") })
    }

    @Test fun `missing job title does not shift company into job title`() {
        val result = GsmProfileXlsx.parse(workbook(overrides = mapOf(
            "Отчет по ТК " to mapOf("A3" to ""),
            "ПЛ-ТК" to mapOf("E5" to "Тестов Иван Иванович, , ООО Тест"),
        )), YearMonth.of(2026, 9), projects)
        assertEquals("Тестов Иван Иванович", result.profile.employeeFullName)
        assertEquals("", result.profile.employeeJobTitle)
        assertEquals("ООО Тест", result.profile.employeeCompany)
        assertFalse(result.sources.containsKey("employeeJobTitle"))
        assertTrue(result.sources.containsKey("employeeCompany"))
        assertTrue(result.warnings.any { it.contains("Должность") && it.contains("ПЛ-ТК!E5") })
    }

    @Test fun `ambiguous fuel headers leave fuel types for manual selection`() {
        val result = GsmProfileXlsx.parse(workbook(overrides = mapOf("Отчет по ТК " to mapOf("A5" to "Вид топлива"))), YearMonth.of(2026, 9), projects)
        assertTrue(result.profile.fuelTypes.isEmpty())
        assertFalse(result.sources.containsKey("fuelTypes"))
        assertTrue(result.warnings.any { it.contains("Виды топлива") && it.contains("вручную") })
        assertEquals(7.5, result.profile.fuelNorm, 0.0)
    }

    @Test fun `unrecognized odometer label does not import a number from an uncertain cell`() {
        val result = GsmProfileXlsx.parse(workbook(overrides = mapOf("ПЛ-ТК" to mapOf("Q8" to "Другое поле"))), YearMonth.of(2026, 9), projects)
        assertFalse(result.sources.containsKey("defaultStartOdometer"))
        assertTrue(result.warnings.any { it.contains("ПЛ-ТК!Q8") })
        assertEquals("Тестов Иван Иванович", result.profile.employeeFullName)
    }

    @Test fun `missing or invalid norm remains unknown and warns while keeping odometer`() {
        for (value in listOf("", "-1", "0", "NaN", "Infinity", "текст", "=SUM(A1:A3)")) {
            val result = GsmProfileXlsx.parse(workbook(overrides = mapOf("Отчет по ТК " to mapOf("C3" to value))), YearMonth.of(2026, 9), projects)
            assertFalse(result.sources.containsKey("fuelNorm"))
            assertEquals(0.0, result.profile.fuelNorm, 0.0)
            assertEquals(10330, result.profile.defaultStartOdometer)
            assertTrue(result.warnings.any { it.contains("Отчет по ТК!C3") && it.contains("вручную") })
        }
    }

    @Test fun `readable workbook without report sheets returns manual completion warnings`() {
        val result = GsmProfileXlsx.parse(workbook(missingSheets = setOf("ПЛ-ТК", "СЗ-ТК", "Отчет по ТК ")), YearMonth.of(2026, 9), projects)
        assertTrue(result.sources.isEmpty())
        assertEquals("", result.profile.employeeFullName)
        assertEquals("2026-10", result.profile.reportStartMonth)
        assertTrue(result.warnings.any { it.contains("Нет листа «ПЛ-ТК»") })
        assertTrue(result.warnings.any { it.contains("Нет листа «Отчет по ТК»") })
    }

    @Test fun `invalid month year odometer and norm are omitted without losing other fields`() {
        val bytes = workbook(end = "-1", month = "не месяц", year = "не год", overrides = mapOf(
            "Отчет по ТК " to mapOf("C3" to "NaN"),
        ))
        val result = GsmProfileXlsx.parse(bytes, YearMonth.of(2026, 9), projects)
        val message = result.notes.joinToString("\n")
        listOf("ПЛ-ТК!M3", "ПЛ-ТК!Q3", "ПЛ-ТК!AE8", "Отчет по ТК!C3")
            .forEach { assertTrue("Missing diagnostic: $it in $message", message.contains(it)) }
        assertEquals("Тестов Иван Иванович", result.profile.employeeFullName)
        assertEquals(0, result.profile.defaultStartOdometer)
        assertEquals(0.0, result.profile.fuelNorm, 0.0)
        listOf("defaultStartOdometer", "fuelNorm", "reportStartMonth").forEach { assertFalse(result.sources.containsKey(it)) }
    }

    @Test fun `missing sheets do not prevent importing available report cells`() {
        val result = GsmProfileXlsx.parse(workbook(missingSheets = setOf("ПЛ-ТК", "СЗ-ТК")), YearMonth.of(2026, 9), projects)
        val message = result.notes.joinToString("\n")
        assertTrue(message.contains("ПЛ-ТК"))
        assertTrue(message.contains("СЗ-ТК"))
        assertEquals("Тестов Иван Иванович", result.profile.employeeFullName)
        assertEquals("Тест Авто", result.profile.carModel)
        assertEquals("00001234", result.profile.fuelCardNumber)
        assertEquals(7.5, result.profile.fuelNorm, 0.0)
        assertFalse(result.sources.containsKey("defaultStartOdometer"))
    }

    @Test fun `month mismatch does not hide missing norm`() {
        val message = GsmProfileXlsx.parse(workbook(overrides = mapOf("Отчет по ТК " to mapOf("C3" to ""))), YearMonth.of(2026, 8), projects).notes.joinToString("\n")
        assertTrue(message.contains("2026-08"))
        assertTrue(message.contains("2026-09"))
        assertTrue(message.contains("Отчет по ТК!C3"))
    }

    @Test fun `missing optional fields still import with readable cell notes`() {
        val result = GsmProfileXlsx.parse(workbook(overrides = mapOf(
            "Отчет по ТК " to mapOf("A3" to ""), "ПЛ-ТК" to mapOf("E5" to ""),
        )), YearMonth.of(2026, 9), projects)
        assertEquals("", result.profile.employeeFullName)
        assertTrue(result.notes.any { it.contains("ФИО сотрудника") && it.contains("Отчет по ТК!A3") })
        assertFalse(result.notes.any { it.contains("employeeFullName") })
    }

    @Test fun `local incomplete workbook imports available fields when supplied outside repository`() {
        val path = System.getenv("GSM_DIAGNOSTIC_XLSX") ?: return
        val result = GsmProfileXlsx.parse(java.io.File(path).readBytes(), YearMonth.of(2026, 9), emptyList())
        assertEquals(3, result.profile.employeeFullName.split(' ').size)
        assertTrue(result.profile.carModel.isNotBlank())
        assertTrue(result.profile.defaultStartOdometer > 0)
        assertTrue(GsmPhoneFormatter.isComplete(result.profile.employeePhone))
        assertEquals(listOf("АИ-95"), result.profile.fuelTypes)
        assertFalse(result.sources.containsKey("fuelNorm"))
        assertTrue(result.notes.any { it.contains("Отчет по ТК!C3") })
    }

    @Test fun `does not invent project ID if directory is missing or ambiguous`() {
        assertNull(GsmProfileXlsx.parse(workbook(), YearMonth.of(2026, 9), emptyList()).profile.posProjectId)
        assertNull(GsmProfileXlsx.parse(workbook(), YearMonth.of(2026, 9), projects + projects[0].copy(id = "other")).profile.posProjectId)
    }

    @Test fun `missing or invalid odometer remains unknown rather than imported as zero`() {
        for (value in listOf("", "-1", "10330.5", "NaN", "Infinity", "2147483648")) {
            val result = GsmProfileXlsx.parse(workbook(end = value), YearMonth.of(2026, 9), projects)
            assertFalse(result.sources.containsKey("defaultStartOdometer"))
            assertTrue(result.notes.any { it.contains("ПЛ-ТК!AE8") })
            assertEquals(7.5, result.profile.fuelNorm, 0.0)
        }
        assertTrue(GsmProfileXlsx.parse(workbook(end = "0"), YearMonth.of(2026, 9), projects).sources.containsKey("defaultStartOdometer"))
    }

    @Test fun `resolves simple reference formulas but never evaluates arbitrary formulas`() {
        val result = GsmProfileXlsx.parse(workbook(end = "=SUM(A1:A3)"), YearMonth.of(2026, 9), projects)
        assertFalse(result.sources.containsKey("defaultStartOdometer"))
        assertTrue(result.notes.any { it.contains("ПЛ-ТК!AE8") })
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

    private fun workbook(end: String = "10330", month: String = "сентябрь", year: String = "2026", ambiguousArm: Boolean = false, catalog: Boolean = false,
        overrides: Map<String, Map<String, String>> = emptyMap(), missingSheets: Set<String> = emptySet()): ByteArray {
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
        overrides.forEach { (sheet, values) -> sheets[sheet] = sheets.getValue(sheet) + values }
        missingSheets.forEach(sheets::remove)
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
