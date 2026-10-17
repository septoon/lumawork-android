package ru.lumastack.lumawork.domain.profile

import java.text.Normalizer
import java.util.Locale
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

enum class ProfileWorkCategory(val title: String) {
    Portable("Переносные"),
    Stationary("Стационарные"),
    Integration("Интеграции"),
    Fiscal("Кассы");

    companion object {
        fun fromTerminalType(value: String): ProfileWorkCategory? = when (value.trim().lowercase(Locale.ROOT)) {
            "portable_pos", "universal_pos" -> Portable
            "stat_pos" -> Stationary
            "intel_pin" -> Integration
            "fiscal_pos" -> Fiscal
            else -> null
        }
    }
}

enum class ProfileWorkOperation(val rawValue: String, val title: String) {
    Install("install", "Установки"),
    Replacement("replacement", "Замены"),
    ServiceStd("serviceStd", "Сервис"),
    Dismounting("dismounting", "Демонтаж");
}

class ProfileCompletedWorkStatistics(records: List<SimpleOneRequest>) {
    data class CategorySummary(
        val category: ProfileWorkCategory,
        val counts: Map<ProfileWorkOperation, Int> = emptyMap(),
    ) {
        val total: Int get() = counts.values.sum()
        val operations: List<ProfileWorkOperation>
            get() = ProfileWorkOperation.entries.filter {
                it != ProfileWorkOperation.Dismounting || count(it) > 0
            }

        fun count(operation: ProfileWorkOperation): Int = counts[operation] ?: 0
    }

    val categories: List<CategorySummary>

    init {
        val counts = mutableMapOf<ProfileWorkCategory, MutableMap<ProfileWorkOperation, Int>>()
        val seen = mutableSetOf<String>()
        records.forEach { record ->
            val fields = record.workInfoFields()
            val closureCode = record.closureCode.ifBlank { fields.firstValue("Код закрытия") }
            if (!closureCode.folded().replace(WHITESPACE, " ").startsWith("решено с выездом")) return@forEach
            val operation = ProfileWorkOperation.entries.firstOrNull { it.rawValue == record.requestType.trim() }
                ?: return@forEach
            val labels = if (operation == ProfileWorkOperation.Dismounting) {
                listOf("Тип демонтируемого ТО", "Тип устанавливаемого ТО", "Тип терминала")
            } else listOf("Тип устанавливаемого ТО", "Тип терминала")
            val category = labels.firstNotNullOfOrNull { label ->
                ProfileWorkCategory.fromTerminalType(fields.firstValue(label))
            } ?: return@forEach
            val number = record.number.trim()
            if (number.isEmpty() || !seen.add(number)) return@forEach
            val categoryCounts = counts.getOrPut(category) { mutableMapOf() }
            categoryCounts[operation] = (categoryCounts[operation] ?: 0) + 1
        }
        categories = ProfileWorkCategory.entries.map { CategorySummary(it, counts[it]?.toMap().orEmpty()) }
    }
}

// Android retains SO information as text and original table columns rather than iOS infoFields.
private fun SimpleOneRequest.workInfoFields(): List<Pair<String, String>> {
    val texts = listOf(
        additionalInformation,
        description,
        tableFields.entries.firstOrNull { it.key.folded() == "информация" }?.value.orEmpty(),
        tableFields.entries.firstOrNull { it.key.folded() == "доп. информация" }?.value.orEmpty(),
    )
    val parsed = texts.flatMap { text ->
        text.lineSequence().mapNotNull { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@mapNotNull null
            val separator = line.indexOf(':')
            if (separator < 0) return@mapNotNull line to ""
            val key = line.substring(0, separator).trim()
            key.takeIf(String::isNotEmpty)?.let { it to line.substring(separator + 1).trim() }
        }.toList()
    }
    return parsed + tableFields.entries.filter { it.key.isNotEmpty() && it.value.isNotBlank() }
        .map { it.key to it.value.trim() }
}

private fun List<Pair<String, String>>.firstValue(label: String): String = firstOrNull {
    it.first.trim { character -> character.isWhitespace() || character == ':' }.equals(label, ignoreCase = true)
}?.second.orEmpty()

private fun String.folded(): String = Normalizer.normalize(trim(), Normalizer.Form.NFD)
    .replace(DIACRITICS, "")
    .lowercase(Locale.ROOT)

private val WHITESPACE = Regex("[\\s\\p{Z}]+")
private val DIACRITICS = Regex("\\p{M}+")
