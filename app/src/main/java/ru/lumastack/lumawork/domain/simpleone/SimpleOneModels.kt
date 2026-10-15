package ru.lumastack.lumawork.domain.simpleone

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

@Serializable
data class SimpleOneUser(
    val sysId: String,
    val username: String,
    val firstName: String,
    val lastName: String,
) {
    val displayName: String
        get() = listOf(firstName, lastName).map(String::trim).filter(String::isNotEmpty).joinToString(" ")
}

@Serializable
enum class SimpleOneRequestSource {
    Active,
    Closed,
}

@Serializable
data class SimpleOneRequest(
    val sysId: String,
    val number: String,
    val source: SimpleOneRequestSource = SimpleOneRequestSource.Active,
    val incomingNumber: String = "",
    val registeredAt: String = "",
    val waitingReason: String = "",
    val state: String = "",
    val stateRaw: String = "",
    val multicardState: String = "",
    val shortDescription: String = "",
    val assignmentGroup: String = "",
    val initiator: String = "",
    val priority: String = "",
    val clientServiceParent: String = "",
    val clientService: String = "",
    val requestType: String = "",
    val address: String = "",
    val customer: String = "",
    val deadline: String = "",
    val resolvedAt: String = "",
    val completedAt: String = "",
    val closedAt: String = "",
    val assignedUser: String = "",
    val assignedUserId: String = "",
    val terminalModel: String = "",
    val terminalId: String = "",
    val contactPerson: String = "",
    val contactPhone: String = "",
    val engineerComment: String = "",
    val closureCode: String = "",
    val resolution: String = "",
    val additionalInformation: String = "",
    val description: String = "",
    val installedFiscalStorageSerialNumber: String = "",
    val ofdTariffActivationCode: String = "",
    val usedSimCard: String = "",
    val tableFields: Map<String, String> = emptyMap(),
) {
    val id: String
        get() = sysId.ifBlank { number }

    val displayNumber: String
        get() = incomingNumber.ifBlank { number }

    val displayState: String
        get() = multicardState.ifBlank { state }

    val searchText: String
        get() = listOf(
            number,
            incomingNumber,
            registeredAt,
            waitingReason,
            state,
            stateRaw,
            multicardState,
            shortDescription,
            assignmentGroup,
            initiator,
            priority,
            clientServiceParent,
            clientService,
            requestType,
            address,
            customer,
            deadline,
            resolvedAt,
            completedAt,
            closedAt,
            assignedUser,
            terminalModel,
            terminalId,
            contactPerson,
            contactPhone,
            engineerComment,
            closureCode,
            resolution,
            additionalInformation,
            description,
            installedFiscalStorageSerialNumber,
            ofdTariffActivationCode,
            usedSimCard,
        ).plus(tableFields.flatMap { listOf(it.key, it.value) })
            .joinToString("\n")
            .normalizedSimpleOneText()

    fun deadlineDateTime(): LocalDateTime? = parseSimpleOneDate(deadline)

    fun registeredDateTime(): LocalDateTime? = parseSimpleOneDate(registeredAt)

    fun resolvedDateTime(): LocalDateTime? = parseSimpleOneDate(resolvedAt.ifBlank { closedAt })

    fun isOverdue(now: LocalDateTime = LocalDateTime.now()): Boolean =
        source == SimpleOneRequestSource.Active && deadlineDateTime()?.isBefore(now) == true

    fun isDueToday(today: LocalDate = LocalDate.now()): Boolean =
        deadlineDateTime()?.toLocalDate() == today
}

@Serializable
data class SimpleOneSnapshot(
    val currentUser: SimpleOneUser? = null,
    val activeRequests: List<SimpleOneRequest> = emptyList(),
    val updatedAtEpochMillis: Long? = null,
)

@Serializable
data class ClosedSimpleOneArchive(
    val requests: List<SimpleOneRequest> = emptyList(),
    val updatedAtEpochMillis: Long? = null,
    val isComplete: Boolean = false,
    val source: ClosedArchiveSource = ClosedArchiveSource.ListApi,
)

@Serializable
enum class ClosedArchiveSource {
    Xlsx,
    ListApi,
}

enum class RequestsMode(val title: String) {
    Active("Активные"),
    Closed("Закрытые SO"),
}

enum class ActiveRequestsStatusFilter(val title: String) {
    All("Все"),
    Available("Доступные"),
    InProgress("В работе"),
    Waiting("В ожидании");

    fun contains(rawStatus: String): Boolean {
        val status = rawStatus.normalizedSimpleOneText()
        val available = "Работа в приложении".normalizedSimpleOneText()
        val waiting = "В ожидании".normalizedSimpleOneText()
        return when (this) {
            All -> true
            Available -> status == available
            Waiting -> status == waiting
            InProgress -> status != available && status != waiting
        }
    }
}

enum class ActiveRequestsSortOrder(val title: String) {
    Deadline("По предельному сроку"),
    RegisteredAt("По регистрации"),
}

fun filterActiveRequests(
    requests: List<SimpleOneRequest>,
    query: String,
    filter: ActiveRequestsStatusFilter,
    sortOrder: ActiveRequestsSortOrder,
): List<SimpleOneRequest> {
    val normalizedQuery = query.normalizedSimpleOneText()
    return requests.asSequence()
        .filter { filter.contains(it.displayState.ifBlank { "МК Статус не загружен" }) }
        .filter { normalizedQuery.isEmpty() || it.searchText.contains(normalizedQuery) }
        .sortedWith(
            when (sortOrder) {
                ActiveRequestsSortOrder.Deadline -> compareBy { it.deadlineDateTime() ?: LocalDateTime.MAX }
                ActiveRequestsSortOrder.RegisteredAt -> compareByDescending {
                    it.registeredDateTime() ?: LocalDateTime.MIN
                }
            },
        )
        .toList()
}

fun filterClosedRequests(requests: List<SimpleOneRequest>, query: String): List<SimpleOneRequest> {
    val normalizedQuery = query.normalizedSimpleOneText()
    return requests.asSequence()
        .filter { normalizedQuery.isEmpty() || it.searchText.contains(normalizedQuery) }
        .sortedByDescending { it.resolvedDateTime() ?: LocalDateTime.MIN }
        .toList()
}

internal fun String.normalizedSimpleOneText(): String = trim()
    .replace('ё', 'е')
    .replace('Ё', 'Е')
    .lowercase()
    .replace(Regex("\\s+"), " ")

private fun parseSimpleOneDate(value: String): LocalDateTime? {
    val normalized = value.trim()
    if (normalized.isEmpty()) return null
    val parsed = SIMPLE_ONE_DATE_FORMATTERS.firstNotNullOfOrNull { formatter ->
        runCatching { LocalDateTime.parse(normalized, formatter) }.getOrNull()
    } ?: return null
    return parsed.takeIf { it.year > 1970 }
}

private val SIMPLE_ONE_DATE_FORMATTERS = listOf(
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd HH:mm",
    "dd.MM.yyyy HH:mm:ss",
    "dd.MM.yyyy HH:mm",
    "MM.dd.yyyy HH:mm:ss",
    "MM.dd.yyyy HH:mm",
).map(DateTimeFormatter::ofPattern)

fun SimpleOneSnapshot.updatedAtDateTime(zoneId: ZoneId = ZoneId.systemDefault()): LocalDateTime? =
    updatedAtEpochMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(zoneId).toLocalDateTime() }

fun ClosedSimpleOneArchive.updatedAtDateTime(zoneId: ZoneId = ZoneId.systemDefault()): LocalDateTime? =
    updatedAtEpochMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(zoneId).toLocalDateTime() }
