package ru.lumastack.lumawork.domain.employees

import kotlinx.serialization.Serializable

@Serializable
data class EmployeeAddress(
    val sysId: String,
    val title: String,
    val region: String = "",
    val federalRegion: String = "",
) {
    val subtitle: String
        get() = listOf(region, federalRegion)
            .map(String::trim)
            .filter { it.isNotEmpty() && it != title }
            .distinct()
            .joinToString(" · ")
}

@Serializable
data class EmployeeField(
    val id: String,
    val title: String,
    val value: String,
)

@Serializable
data class EmployeeSection(
    val id: String,
    val title: String,
    val fields: List<EmployeeField>,
)

@Serializable
data class Employee(
    val sysId: String,
    val displayName: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val middleName: String = "",
    val login: String = "",
    val email: String = "",
    val position: String = "",
    val manager: String = "",
    val company: String = "",
    val department: String = "",
    val phone: String = "",
    val address: EmployeeAddress? = null,
    val isActive: Boolean? = null,
    val isLocked: Boolean? = null,
    val updatedAt: String = "",
    val detailSections: List<EmployeeSection> = emptyList(),
) {
    val fullName: String
        get() = listOf(lastName, firstName, middleName)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(" ")
            .ifBlank { displayName.ifBlank { login.ifBlank { "Сотрудник" } } }

    val sortName: String
        get() = listOf(fullName, displayName, login).firstOrNull(String::isNotBlank).orEmpty()

    val initials: String
        get() {
            val preferred = listOf(firstName, lastName)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .mapNotNull(String::firstOrNull)
            val fallback = fullName.split(Regex("\\s+")).mapNotNull(String::firstOrNull)
            return (preferred.ifEmpty { fallback }).take(2).joinToString("").uppercase().ifBlank { "?" }
        }

    val roleText: String
        get() = listOf(position, department).firstOrNull(String::isNotBlank) ?: "Должность не указана"

    val searchText: String
        get() = listOf(fullName, displayName, login, email, position, department, phone)
            .joinToString("\n")
            .normalizedEmployeeText()
}

@Serializable
data class EmployeeDirectory(
    val employees: List<Employee> = emptyList(),
    val totalCount: Int = 0,
    val updatedAtEpochMillis: Long? = null,
)

@Serializable
data class EmployeeSnapshot(
    val simpleOneUserId: String = "",
    val selectedAddress: EmployeeAddress? = null,
    val savedAddresses: List<EmployeeAddress> = emptyList(),
    val directoriesByAddressId: Map<String, EmployeeDirectory> = emptyMap(),
    val detailCache: Map<String, Employee> = emptyMap(),
)

fun filterEmployees(employees: List<Employee>, query: String): List<Employee> {
    val normalizedQuery = query.normalizedEmployeeText()
    return employees.asSequence()
        .filter { normalizedQuery.isEmpty() || it.searchText.contains(normalizedQuery) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.sortName })
        .toList()
}

fun sanitizeEmployeeConditionValue(value: String): String = value
    .replace('^', ' ')
    .replace('(', ' ')
    .replace(')', ' ')
    .replace('\n', ' ')
    .replace('\r', ' ')
    .split(Regex("\\s+"))
    .filter(String::isNotBlank)
    .joinToString(" ")
    .trim()

private fun String.normalizedEmployeeText(): String = trim()
    .replace('ё', 'е')
    .replace('Ё', 'Е')
    .lowercase()
    .replace(Regex("\\s+"), " ")

fun Employee.tenureText(today: java.time.LocalDate = java.time.LocalDate.now(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? {
    val field = detailSections.asSequence().flatMap { it.fields.asSequence() }.firstOrNull { field ->
        val title = field.title.lowercase().replace('ё', 'е')
        val system = field.id.lowercase()
        listOf("дата приема", "прием на работу", "дата трудоустройства", "дата начала работы").any(title::contains) ||
            listOf("hire_date", "employment_date", "employment_start", "work_start").any(system::contains)
    } ?: return null
    val raw = field.value.trim()
    val iso = runCatching { java.time.OffsetDateTime.parse(raw).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
    val start = iso ?: listOf("dd.MM.uuuu", "dd.MM.uuuu HH:mm", "dd.MM.uuuu HH:mm:ss", "uuuu-MM-dd", "uuuu-MM-dd HH:mm", "uuuu-MM-dd HH:mm:ss", "uuuu-MM-dd'T'HH:mm:ss", "d MMMM uuuu").firstNotNullOfOrNull { pattern ->
        runCatching {
            java.time.LocalDate.parse(raw, java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(java.util.Locale.forLanguageTag("ru")).withResolverStyle(java.time.format.ResolverStyle.STRICT))
        }.getOrNull()
    } ?: return null
    if (start > today) return null
    val period = java.time.Period.between(start, today)
    return buildList {
        if (period.years > 0) add("${period.years} ${employeeCountWord(period.years, "год", "года", "лет")}")
        if (period.months > 0) add("${period.months} ${employeeCountWord(period.months, "месяц", "месяца", "месяцев")}")
        if (period.days > 0 || isEmpty()) add("${period.days} ${employeeCountWord(period.days, "день", "дня", "дней")}")
    }.joinToString(" ")
}

fun employeeCountWord(count: Int, one: String, few: String, many: String): String = when {
    count % 100 in 11..14 -> many
    count % 10 == 1 -> one
    count % 10 in 2..4 -> few
    else -> many
}
