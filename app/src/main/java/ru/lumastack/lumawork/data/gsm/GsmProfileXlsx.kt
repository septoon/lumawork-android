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
data class GsmProfileImport(val profile: GsmProfile, val sources: Map<String, String>, val notes: List<String>)

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
        val missingSheets = listOf("ПЛ-ТК", "СЗ-ТК", "Отчет по ТК").filterNot(book::hasSheet)
        if (missingSheets.isNotEmpty()) throw GsmXlsxFailure(
            "Не удалось импортировать отчёт ГСМ.\n\n" + missingSheets.joinToString("\n") { "• Нет листа «$it»." },
        )
        val pl = book.sheet("ПЛ-ТК")
        val sz = book.sheet("СЗ-ТК")
        val fuel = book.sheet("Отчет по ТК")
        val projectCatalog = book.optionalSheet("Список проектов")
        val problems = mutableListOf<String>()
        fun required(sheet: String, ref: String, label: String): String {
            val value = book.value(sheet, ref)
            if (value.isBlank()) problems += "$label — $sheet!$ref: нет значения. Заполните ячейку; если в ней формула — пересчитайте и сохраните файл в Excel."
            return value
        }
        fun marker(sheet: String, cells: Map<String, String>, ref: String, text: String, exact: Boolean = false) {
            fun matches(value: String) = if (exact) value == text else value.contains(text)
            if (matches(cells[ref].orEmpty())) return
            val found = cells.entries.filter { matches(it.value) }.take(3).joinToString(", ") { "$sheet!${it.key}" }
            problems += "$sheet!$ref: ожидается заголовок «$text». " +
                if (found.isNotEmpty()) "Найден в $found — расположение отличается от поддерживаемого шаблона."
                else "Заголовок не найден на листе."
        }
        marker("ПЛ-ТК", pl, "B5", "ФИО сотрудника")
        marker("ПЛ-ТК", pl, "Q8", "конец периода")
        marker("Отчет по ТК", fuel, "A4", "Вид топлива", exact = true)
        val months = listOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")
        val monthText = required("ПЛ-ТК", "M3", "Месяц отчёта")
        val yearText = required("ПЛ-ТК", "Q3", "Год отчёта")
        val month = months.indexOf(monthText.lowercase(java.util.Locale.ROOT)) + 1
        val year = yearText.toDoubleOrNull()?.toInt() ?: 0
        if (monthText.isNotBlank() && month == 0) problems += "Месяц отчёта — ПЛ-ТК!M3: укажите название месяца, например «сентябрь»."
        if (yearText.isNotBlank() && year !in 2000..2100) problems += "Год отчёта — ПЛ-ТК!Q3: укажите год от 2000 до 2100."
        val sourceMonth = if (month != 0 && year in 2000..2100) YearMonth.of(year, month) else null
        if (sourceMonth != null && sourceMonth != expectedMonth)
            problems += "Период отчёта — ПЛ-ТК!M3,Q3: выберите отчёт за $expectedMonth. В файле указан $sourceMonth."
        val endText = required("ПЛ-ТК", "AE8", "Конечный одометр")
        val end = endText.replace(',', '.').toDoubleOrNull()
        if (endText.isNotBlank() && (end == null || !end.isFinite() || end < 0 || end > Int.MAX_VALUE || end % 1.0 != 0.0))
            problems += "Конечный одометр — ПЛ-ТК!AE8 (R8C31): укажите целое число от 0 до ${Int.MAX_VALUE}."
        val normText = required("Отчет по ТК", "C3", "Норма топлива")
        val norm = normText.replace(',', '.').toDoubleOrNull()
        if (normText.isNotBlank() && (norm == null || !norm.isFinite() || norm <= 0))
            problems += "Норма топлива — Отчет по ТК!C3: укажите число больше нуля."
        val sources = linkedMapOf<String, String>()
        val notes = mutableListOf("В отчёте нет остатка топлива, а в ГСМ-профиле нет такого поля. Остаток не вычисляется и не принимается за ноль. История маршрутов и заправок не импортируется.")
        fun field(key: String, sheet: String, ref: String, label: String): String {
            val value = book.value(sheet, ref)
            if (value.isNotBlank()) sources[key] = "$sheet!$ref" else {
                notes += "$label — $sheet!$ref: нет значения; заполните вручную."
            }
            return value
        }
        val fullName = field("employeeFullName", "Отчет по ТК", "A3", "ФИО сотрудника")
        val title = field("employeeTitleCompany", "ПЛ-ТК", "E5", "ФИО, должность и компания")
        val parts = if (fullName.isNotBlank() && title.startsWith("$fullName,")) title.removePrefix(fullName).trimStart(',', ' ').split(',', limit = 2) else emptyList()
        if (parts.size != 2) notes += "Проверьте должность и компанию: не удалось разделить ПЛ-ТК!E5."
        sources["employeeJobTitle"] = "ПЛ-ТК!E5 (после ФИО, до первой запятой)"
        sources["employeeCompany"] = "ПЛ-ТК!E5 (после должности)"
        val contact = field("employeeAddressPhone", "ПЛ-ТК", "E6", "Адрес и телефон сотрудника")
        val phoneMatch = Regex("(?:\\+?7|8)[\\s(]*[0-9]{3}[\\s)]*[0-9]{3}[-\\s]*[0-9]{2}[-\\s]*[0-9]{2}\\s*$").find(contact)
        val phone = phoneMatch?.value?.let(GsmPhoneFormatter::format).orEmpty()
        val address = if (phoneMatch != null) contact.take(phoneMatch.range.first).trimEnd(',', ';', ' ') else contact
        sources["employeeAddress"] = "ПЛ-ТК!E6 (до телефона)"
        sources["employeePhone"] = "ПЛ-ТК!E6 (телефон в конце строки)"
        if (phone.isBlank()) notes += "Телефон сотрудника — ПЛ-ТК!E6: не распознан; заполните вручную."
        fun rowOf(cells: Map<String, String>, column: String, predicate: (String) -> Boolean) = cells.entries
            .filter { it.key.matches(Regex("$column[0-9]+")) && predicate(it.value) }.singleOrNull()?.key?.drop(column.length)?.toIntOrNull()
        val shortRow = rowOf(sz, "C") { it.trim() == "Сотрудник" }
        val short = shortRow?.let { field("employeeShortName", "СЗ-ТК", "Q$it", "Фамилия и инициалы сотрудника") }.orEmpty()
        val signRef = fuel.entries.singleOrNull { it.key.matches(Regex("A[0-9]+")) && it.value.startsWith("ФИО ") }?.key
        val sign = signRef?.let { field("employeeReportSignName", "Отчет по ТК", it, "Подпись сотрудника в отчёте").removePrefix("ФИО ").trim() }.orEmpty()
        val authorizedRow = rowOf(pl, "C") { it.contains("ФИО уполномоченного сотрудника") }
        val authorized = authorizedRow?.let { field("authorizedFullName", "ПЛ-ТК", "C${it - 1}", "ФИО уполномоченного сотрудника") }.orEmpty()
        val authorizedParts = authorized.split(Regex("\\s+")).filter(String::isNotBlank)
        val authorizedShort = if (authorizedParts.size == 3) "${authorizedParts[0]} ${authorizedParts[1].first()}. ${authorizedParts[2].first()}." else ""
        if (authorizedShort.isNotBlank()) sources["authorizedShortName"] = "${sources["authorizedFullName"]} (фамилия и инициалы)"
        val totalRow = fuel.entries.filter { it.key.matches(Regex("A[0-9]+")) && it.value.trim() == "Итого по:" }.minOfOrNull { it.key.drop(1).toInt() }
        val types = fuel.entries.filter { it.key.matches(Regex("A[0-9]+")) && it.key.drop(1).toInt() >= 5 &&
            (totalRow == null || it.key.drop(1).toInt() < totalRow) }.map { it.value }.filter(String::isNotBlank).distinct()
        val typeRefs = fuel.entries.filter { it.key.matches(Regex("A[0-9]+")) && it.value in types }.map { it.key }
        sources["fuelTypes"] = "Отчет по ТК!${typeRefs.joinToString(",")}"; sources["fuelType"] = sources.getValue("fuelTypes")
        sources["fuelNorm"] = "Отчет по ТК!C3"
        sources["defaultStartOdometer"] = "ПЛ-ТК!AE8"
        sources["reportStartMonth"] = "ПЛ-ТК!M3,Q3 + 1 месяц"
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
            else if (kind == "POS" || budgets.isNotEmpty()) notes += "Выберите проект $kind вручную: однозначное соответствие справочнику не найдено."
            return found
        }
        val pos = project("POS"); val arm = project("ARM")
        val driverLicense = field("driverLicenseNumber", "ПЛ-ТК", "AC5", "Номер водительского удостоверения")
        val fuelCard = field("fuelCardNumber", "ПЛ-ТК", "AC6", "Номер топливной карты")
        val carModel = field("carModel", "ПЛ-ТК", "J7", "Марка автомобиля")
        val licensePlate = field("licensePlate", "ПЛ-ТК", "AE7", "Государственный номер ТС")
        if (problems.isNotEmpty()) throw GsmXlsxFailure(buildString {
            append("Не удалось импортировать отчёт ГСМ.\n\nИсправьте следующие поля или шаблон:\n")
            append(problems.joinToString("\n") { "• $it" })
            if (notes.size > 1) {
                append("\n\nТакже проверьте поля профиля:\n")
                append(notes.drop(1).joinToString("\n") { "• $it" })
            }
        })
        return GsmProfileImport(GsmProfile(
            posProjectId = pos?.id, armProjectId = arm?.id, budgetCode = pos?.budgetCode.orEmpty(), projectName = pos?.name.orEmpty(),
            employeeFullName = fullName, employeeShortName = short, employeeReportSignName = sign,
            authorizedFullName = authorized, authorizedShortName = authorizedShort,
            employeeJobTitle = parts.getOrNull(0)?.trim().orEmpty(), employeeCompany = parts.getOrNull(1)?.trim().orEmpty(),
            employeeAddress = address, employeePhone = phone, employeeTitleCompany = title, employeeAddressPhone = contact,
            driverLicenseNumber = driverLicense, fuelCardNumber = fuelCard,
            carModel = carModel, licensePlate = licensePlate,
            fuelNorm = requireNotNull(norm), fuelTypes = types, fuelType = types.firstOrNull().orEmpty(),
            defaultStartOdometer = requireNotNull(end).toInt(), reportStartMonth = requireNotNull(sourceMonth).plusMonths(1).toString(),
        ), sources, notes)
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
        fun sheet(name: String): Map<String, String> {
            if (name !in cells) throw GsmXlsxFailure("Нет листа «$name».")
            return optionalSheet(name)
        }
        fun optionalSheet(name: String): Map<String, String> = cells[name].orEmpty().mapValues { (ref, _) -> value(name, ref) }
        fun value(sheet: String, ref: String, depth: Int = 0): String {
            val cell = cells[sheet]?.get(ref) ?: return ""
            if (cell.value.isNotBlank()) return cell.value
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
