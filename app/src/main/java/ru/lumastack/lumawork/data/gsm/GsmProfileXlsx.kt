package ru.lumastack.lumawork.data.gsm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.YearMonth
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlinx.serialization.Serializable
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

class GsmXlsxFailure(message: String) : Exception(message)

@Serializable
data class GsmProfileImport(val profile: GsmProfile, val sources: Map<String, String>, val notes: List<String>, val warnings: List<String> = emptyList())

/** Reads values/cached values, never executes workbook formulas, macros, links or instructions. */
object GsmProfileXlsx {
    const val MAX_FILE_BYTES = 30 * 1024 * 1024

    fun read(input: InputStream): ByteArray = limited(input, MAX_FILE_BYTES)

    fun parse(bytes: ByteArray, expectedMonth: YearMonth, projects: List<GsmProjectOption>): GsmProfileImport {
        try { return import(bytes, expectedMonth, projects) }
        catch (e: GsmXlsxFailure) { throw e }
        catch (_: Exception) { throw GsmXlsxFailure("Не удалось прочитать XLSX отчёт ГСМ.") }
    }

    private fun import(bytes: ByteArray, expectedMonth: YearMonth, projects: List<GsmProjectOption>): GsmProfileImport {
        val book = Workbook(bytes)
        val warnings = mutableListOf<String>()
        listOf("ПЛ-ТК", "СЗ-ТК", "Отчет по ТК").filterNot(book::hasSheet).forEach {
            warnings += "Нет листа «$it». Данные с этого листа нужно заполнить вручную."
        }
        val pl = book.optionalSheet("ПЛ-ТК")
        val sz = book.optionalSheet("СЗ-ТК")
        val fuel = book.optionalSheet("Отчет по ТК")
        val projectCatalog = book.optionalSheet("Список проектов")
        val sources = linkedMapOf<String, String>()
        fun readValue(sheet: String, ref: String, label: String): String {
            val value = book.value(sheet, ref)
            if (value.isBlank()) warnings += "$label — $sheet!$ref: нет значения; заполните вручную в профиле."
            return value
        }
        fun rowOf(cells: Map<String, String>, column: String, predicate: (String) -> Boolean) = cells.entries
            .filter { it.key.matches(Regex("$column[0-9]+")) && predicate(it.value) }.singleOrNull()?.key?.drop(column.length)?.toIntOrNull()
        val months = listOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")
        val monthText = readValue("ПЛ-ТК", "M3", "Месяц отчёта")
        val yearText = readValue("ПЛ-ТК", "Q3", "Год отчёта")
        val month = months.indexOf(monthText.lowercase(java.util.Locale.ROOT)) + 1
        val year = yearText.toDoubleOrNull()?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in 2000.0..2100.0 }?.toInt() ?: 0
        if (monthText.isNotBlank() && month == 0) warnings += "Месяц отчёта — ПЛ-ТК!M3: не распознан; выберите месяц начала отчётности вручную."
        if (yearText.isNotBlank() && year == 0) warnings += "Год отчёта — ПЛ-ТК!Q3: не распознан; выберите год начала отчётности вручную."
        val sourceMonth = if (month != 0 && year in 2000..2100) YearMonth.of(year, month) else null
        if (sourceMonth != null && sourceMonth != expectedMonth)
            warnings += "Период отчёта — ПЛ-ТК!M3,Q3: ожидался $expectedMonth, в файле указан $sourceMonth. Проверьте месяц начала отчётности и стартовый одометр вручную."
        if (sourceMonth != null) sources["reportStartMonth"] = "ПЛ-ТК!M3,Q3 + 1 месяц"
        else warnings += "Месяц начала отчётности не определён. Выберите его вручную; по умолчанию выбран ${expectedMonth.plusMonths(1)}."
        val endText = readValue("ПЛ-ТК", "AE8", "Конечный одометр")
        val end = endText.replace(',', '.').toDoubleOrNull()?.takeIf {
            it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 && pl["Q8"].orEmpty().contains("конец периода")
        }
        if (end != null) sources["defaultStartOdometer"] = "ПЛ-ТК!AE8"
        else if (endText.isNotBlank()) warnings += "Конечный одометр — ПЛ-ТК!AE8 (R8C31): число или заголовок в ПЛ-ТК!Q8 не распознаны; заполните стартовый одометр вручную."
        val normText = readValue("Отчет по ТК", "C3", "Норма топлива")
        val norm = normText.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        if (norm != null) sources["fuelNorm"] = "Отчет по ТК!C3"
        else if (normText.isNotBlank()) warnings += "Норма топлива — Отчет по ТК!C3: число не распознано; укажите положительную норму вручную."
        fun field(key: String, sheet: String, ref: String, label: String, fallback: Pair<String, String>? = null): String {
            val primary = book.value(sheet, ref)
            val value = primary.ifBlank { fallback?.let { book.value(it.first, it.second) }.orEmpty() }
            val source = if (primary.isNotBlank()) "$sheet!$ref" else fallback?.let { "${it.first}!${it.second}" }
            if (value.isNotBlank() && source != null) sources[key] = source
            else {
                val alternative = fallback?.let { "; ${it.first}!${it.second}" }.orEmpty()
                warnings += "$label — $sheet!$ref$alternative: нет значения; заполните вручную."
            }
            return value
        }
        val title = field("employeeTitleCompany", "ПЛ-ТК", "E5", "ФИО, должность и компания")
        val titleParts = title.split(',', limit = 3).map(String::trim)
        val titleName = titleParts.first().takeIf { titleParts.size >= 2 && it.split(Regex("\\s+")).size == 3 }.orEmpty()
        val reportName = book.value("Отчет по ТК", "A3")
        val fullName = reportName.ifBlank { titleName }
        if (fullName.isNotBlank()) sources["employeeFullName"] = if (reportName.isNotBlank()) "Отчет по ТК!A3" else "ПЛ-ТК!E5 (до первой запятой)"
        else warnings += "ФИО сотрудника — Отчет по ТК!A3; ПЛ-ТК!E5: не распознано; заполните вручную."
        val parts = if (fullName.isNotBlank() && title.startsWith("$fullName,")) title.removePrefix("$fullName,").split(',', limit = 2).map(String::trim) else emptyList()
        if (!parts.getOrNull(0).isNullOrBlank()) sources["employeeJobTitle"] = "ПЛ-ТК!E5 (после ФИО, до первой запятой)"
        else warnings += "Должность — ПЛ-ТК!E5: не распознана однозначно; заполните вручную."
        if (!parts.getOrNull(1).isNullOrBlank()) sources["employeeCompany"] = "ПЛ-ТК!E5 (после должности)"
        else warnings += "Компания — ПЛ-ТК!E5: не распознана однозначно; заполните вручную."
        val contact = field("employeeAddressPhone", "ПЛ-ТК", "E6", "Адрес и телефон сотрудника")
        val phoneMatch = Regex("(?:\\+?7|8)[\\s(]*[0-9]{3}[\\s)]*[0-9]{3}[-\\s]*[0-9]{2}[-\\s]*[0-9]{2}\\s*$").find(contact)
        val phone = phoneMatch?.value?.let(GsmPhoneFormatter::format).orEmpty()
        val address = if (phoneMatch != null) contact.take(phoneMatch.range.first).trimEnd(',', ';', ' ') else contact
        if (address.isNotBlank()) sources["employeeAddress"] = "ПЛ-ТК!E6 (до телефона)"
        else warnings += "Адрес сотрудника — ПЛ-ТК!E6: не распознан; заполните вручную."
        if (phone.isNotBlank()) sources["employeePhone"] = "ПЛ-ТК!E6 (телефон в конце строки)"
        else warnings += "Телефон сотрудника — ПЛ-ТК!E6: не распознан; заполните вручную."
        val shortRow = rowOf(sz, "C") { it.trim() == "Сотрудник" }
        val short = shortRow?.let { field("employeeShortName", "СЗ-ТК", "Q$it", "Фамилия и инициалы сотрудника") }.orEmpty()
        if (shortRow == null) warnings += "Фамилия и инициалы сотрудника — СЗ-ТК: строка «Сотрудник» в столбце C не найдена однозначно; заполните вручную."
        val signRow = rowOf(fuel, "A") { it == "ФИО" || it.startsWith("ФИО ") }
        val sign = signRow?.let { book.value("Отчет по ТК", "A$it").removePrefix("ФИО").trim() }.orEmpty()
        if (sign.isNotBlank()) sources["employeeReportSignName"] = "Отчет по ТК!A$signRow"
        else warnings += "Подпись сотрудника — Отчет по ТК!${signRow?.let { "A$it" } ?: "A:A"}: ФИО не распознано; заполните вручную."
        val authorizedRow = rowOf(pl, "C") { it.contains("ФИО уполномоченного сотрудника") }
        val authorized = authorizedRow?.let { field("authorizedFullName", "ПЛ-ТК", "C${it - 1}", "ФИО уполномоченного сотрудника") }.orEmpty()
        if (authorizedRow == null) warnings += "ФИО уполномоченного сотрудника — ПЛ-ТК: строка подписи в столбце C не найдена однозначно; заполните вручную."
        val authorizedParts = authorized.split(Regex("\\s+")).filter(String::isNotBlank)
        val authorizedShort = if (authorizedParts.size == 3) "${authorizedParts[0]} ${authorizedParts[1].first()}. ${authorizedParts[2].first()}." else ""
        if (authorizedShort.isNotBlank()) sources["authorizedShortName"] = "${sources["authorizedFullName"]} (фамилия и инициалы)"
        else warnings += "Фамилия и инициалы уполномоченного сотрудника — ПЛ-ТК!${authorizedRow?.let { "C${it - 1}" } ?: "C:C"}: не распознаны; заполните вручную."
        val fuelHeaderRow = rowOf(fuel, "A") { it == "Вид топлива" }
        val totalRow = fuel.entries.filter { it.key.matches(Regex("A[0-9]+")) && (it.value.trim() == "Итого по:" || it.value.startsWith("ФИО")) &&
            it.key.drop(1).toInt() > (fuelHeaderRow ?: Int.MAX_VALUE) }.minOfOrNull { it.key.drop(1).toInt() }
        val typeCells = fuel.entries.filter { it.key.matches(Regex("A[0-9]+")) && it.key.drop(1).toInt() > (fuelHeaderRow ?: Int.MAX_VALUE) &&
            (totalRow == null || it.key.drop(1).toInt() < totalRow) && it.value.isNotBlank() }.sortedBy { it.key.drop(1).toInt() }
        val types = typeCells.map { it.value }.distinct()
        if (types.isNotEmpty()) {
            sources["fuelTypes"] = "Отчет по ТК!${typeCells.joinToString(",") { it.key }}"
            sources["fuelType"] = sources.getValue("fuelTypes")
        } else warnings += "Виды топлива — Отчет по ТК, столбец A: таблица с заголовком «Вид топлива» не распознана или пуста; выберите топливо вручную."
        fun project(kind: String): GsmProjectOption? {
            val kinds = if (kind == "ARM") setOf("ARM", "АРМ") else setOf(kind)
            val budgetCells = pl.entries.filter { it.key.matches(Regex("D[0-9]+")) &&
                pl["AJ${it.key.drop(1)}"]?.uppercase(java.util.Locale.ROOT) in kinds }
            val budgets = budgetCells.map { it.value }.distinct()
            val candidates = if (budgets.size == 1) projects.filter { it.budgetCode == budgets.single() } else emptyList()
            val catalogRow = if (budgets.size == 1) projectCatalog.entries.singleOrNull {
                it.key.matches(Regex("B[0-9]+")) && it.value == budgets.single()
            }?.key?.drop(1) else null
            val catalogName = catalogRow?.let { projectCatalog["A$it"] }
            val found = candidates.singleOrNull() ?: candidates.filter { !catalogName.isNullOrBlank() && it.name == catalogName }.singleOrNull()
            if (found != null) {
                val catalogSource = if (candidates.size > 1 && catalogRow != null) "; Список проектов!A$catalogRow,B$catalogRow" else ""
                val source = "ПЛ-ТК!${budgetCells.joinToString(",") { it.key }} (AJ=$kind)$catalogSource → справочник по budgetCode и названию"
                sources[if (kind == "POS") "posProjectId" else "armProjectId"] = source
                if (kind == "POS") { sources["budgetCode"] = source; sources["projectName"] = source }
            }
            else if (kind == "POS" || budgets.isNotEmpty()) warnings += "Выберите проект $kind вручную: однозначное соответствие справочнику не найдено."
            return found
        }
        val pos = project("POS"); val arm = project("ARM")
        val driverLicense = field("driverLicenseNumber", "ПЛ-ТК", "AC5", "Номер водительского удостоверения")
        val fuelCard = field("fuelCardNumber", "ПЛ-ТК", "AC6", "Номер топливной карты", "Отчет по ТК" to "D3")
        val carModel = field("carModel", "ПЛ-ТК", "J7", "Марка автомобиля", "Отчет по ТК" to "B3")
        val licensePlate = field("licensePlate", "ПЛ-ТК", "AE7", "Государственный номер ТС")
        return GsmProfileImport(GsmProfile(
            posProjectId = pos?.id, armProjectId = arm?.id, budgetCode = pos?.budgetCode.orEmpty(), projectName = pos?.name.orEmpty(),
            employeeFullName = fullName, employeeShortName = short, employeeReportSignName = sign,
            authorizedFullName = authorized, authorizedShortName = authorizedShort,
            employeeJobTitle = parts.getOrNull(0)?.trim().orEmpty(), employeeCompany = parts.getOrNull(1)?.trim().orEmpty(),
            employeeAddress = address, employeePhone = phone, employeeTitleCompany = title, employeeAddressPhone = contact,
            driverLicenseNumber = driverLicense, fuelCardNumber = fuelCard,
            carModel = carModel, licensePlate = licensePlate,
            fuelNorm = norm ?: 0.0, fuelTypes = types, fuelType = types.firstOrNull().orEmpty(),
            defaultStartOdometer = end?.toInt() ?: 0, reportStartMonth = (sourceMonth ?: expectedMonth).plusMonths(1).toString(),
        ), sources, listOf("В отчёте нет остатка топлива, а в ГСМ-профиле нет такого поля. Остаток не вычисляется и не принимается за ноль. История маршрутов и заправок не импортируется.") + warnings, warnings)
    }

    private data class Cell(val value: String, val formula: String)
    private class Workbook(bytes: ByteArray) {
        private val parts = linkedMapOf<String, ByteArray>()
        private val cells = linkedMapOf<String, Map<String, Cell>>()
        init {
            if (bytes.size > MAX_FILE_BYTES) throw GsmXlsxFailure("Размер XLSX превышает 30 МиБ.")
            var total = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val data = limited(zip, minOf(16 * 1024 * 1024, 48 * 1024 * 1024 - total))
                    total += data.size
                    if (parts.size >= 512 || parts.put(entry.name, data) != null) throw GsmXlsxFailure("Некорректная структура XLSX.")
                    entry = zip.nextEntry
                }
            }
            if (parts["xl/workbook.xml"] == null || parts["xl/_rels/workbook.xml.rels"] == null)
                throw GsmXlsxFailure("Не удалось прочитать структуру XLSX. Выберите неповреждённый отчёт ГСМ.")
            val names = linkedMapOf<String, String>(); val relations = linkedMapOf<String, String>()
            xml(parts["xl/workbook.xml"]) { tag, a -> if (tag == "sheet") names[a.getValue("name").trim()] =
                a.getValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id") ?: a.getValue("r:id") }
            xml(parts["xl/_rels/workbook.xml.rels"]) { tag, a ->
                if (tag == "Relationship" && a.getValue("TargetMode") != "External") {
                    val target = a.getValue("Target").orEmpty()
                    if (!target.contains("..")) relations[a.getValue("Id")] = if (target.startsWith('/')) target.drop(1) else "xl/$target"
                }
            }
            val shared = mutableListOf<String>(); val item = StringBuilder(); var inText = false
            sax(parts["xl/sharedStrings.xml"], object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) {
                    val name = elementName(localName, qName)
                    if (name == "si") item.clear(); if (name == "t") inText = true
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { if (inText) item.append(ch, start, length) }
                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    val name = elementName(localName, qName)
                    if (name == "t") inText = false; if (name == "si") shared += item.toString()
                }
            })
            for ((name, id) in names) {
                if (name !in setOf("ПЛ-ТК", "СЗ-ТК", "Отчет по ТК", "Список проектов")) continue
                val sheet = linkedMapOf<String, Cell>(); var ref = ""; var type = ""; var tag = ""
                val value = StringBuilder(); val formula = StringBuilder()
                sax(parts[relations[id]], object : DefaultHandler() {
                    override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) {
                        val name = elementName(localName, qName)
                        if (name == "c") { ref = a.getValue("r"); type = a.getValue("t").orEmpty(); value.clear(); formula.clear() }
                        if (name in listOf("v", "t", "f")) tag = name
                    }
                    override fun characters(ch: CharArray, start: Int, length: Int) {
                        if (tag == "f") formula.append(ch, start, length) else if (tag == "v" || tag == "t") value.append(ch, start, length)
                    }
                    override fun endElement(uri: String?, localName: String?, qName: String?) {
                        val name = elementName(localName, qName)
                        if (name in listOf("v", "t", "f")) tag = ""
                        if (name == "c") sheet[ref] = Cell(if (type == "s") value.toString().toIntOrNull()?.let(shared::getOrNull).orEmpty() else if (type == "e") "" else value.toString().trim(), formula.toString())
                    }
                })
                cells[name] = sheet
            }
        }
        fun hasSheet(name: String): Boolean = name in cells
        fun optionalSheet(name: String): Map<String, String> = cells[name].orEmpty().mapValues { (ref, _) -> value(name, ref) }
        fun value(sheet: String, ref: String, depth: Int = 0): String {
            val cell = cells[sheet]?.get(ref) ?: return ""
            if (cell.value.isNotBlank()) return cell.value.trim()
            if (depth >= 8 || cell.formula.isBlank()) return ""
            val match = Regex("(?:(?:'([^']+)'|([^!]+))!)?\\$?([A-Z]+)\\$?([0-9]+)").matchEntire(cell.formula) ?: return ""
            return value(match.groupValues[1].ifBlank { match.groupValues[2] }.trim().ifBlank { sheet }, match.groupValues[3] + match.groupValues[4], depth + 1)
        }
    }

    private fun elementName(localName: String?, qName: String?): String = localName.orEmpty().ifBlank { qName.orEmpty().substringAfter(':') }
    private fun xml(bytes: ByteArray?, onElement: (String, Attributes) -> Unit) = sax(bytes, object : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) = onElement(elementName(localName, qName), a)
    })
    private fun sax(bytes: ByteArray?, handler: DefaultHandler) {
        if (bytes == null) return
        if (bytes.toString(Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) throw GsmXlsxFailure("Внешние XML-сущности не поддерживаются.")
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true; isValidating = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities", "http://apache.org/xml/features/nonvalidating/load-external-dtd").forEach { runCatching { setFeature(it, false) } }
        }
        factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
    }
    private fun limited(input: InputStream, max: Int): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            if (output.size() + n > max) throw GsmXlsxFailure("XLSX содержит слишком много данных.")
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
}
