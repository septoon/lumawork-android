package ru.lumastack.lumawork.data.notifications

import kotlinx.serialization.Serializable
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

@Serializable
data class NotificationPreferences(
    val localNewRequests: Boolean = true,
    val localAssigneeChanges: Boolean = true,
    val emailNewRequests: Boolean = true,
    val emailAssigneeChanges: Boolean = true,
) {
    fun enabled(key: NotificationPreference): Boolean = when (key) {
        NotificationPreference.LocalNewRequests -> localNewRequests
        NotificationPreference.LocalAssigneeChanges -> localAssigneeChanges
        NotificationPreference.EmailNewRequests -> emailNewRequests
        NotificationPreference.EmailAssigneeChanges -> emailAssigneeChanges
    }
    fun changing(key: NotificationPreference, value: Boolean): NotificationPreferences = when (key) {
        NotificationPreference.LocalNewRequests -> copy(localNewRequests = value)
        NotificationPreference.LocalAssigneeChanges -> copy(localAssigneeChanges = value)
        NotificationPreference.EmailNewRequests -> copy(emailNewRequests = value)
        NotificationPreference.EmailAssigneeChanges -> copy(emailAssigneeChanges = value)
    }
    fun localEnabled(event: NotificationEvent) = if (event.kind == "new_active_request") localNewRequests else localAssigneeChanges
    fun emailEnabled(event: NotificationEvent) = if (event.kind == "new_active_request") emailNewRequests else emailAssigneeChanges
    val anyEnabled get() = localNewRequests || localAssigneeChanges || emailNewRequests || emailAssigneeChanges
}

@Serializable enum class NotificationPreference {
    LocalNewRequests, LocalAssigneeChanges, EmailNewRequests, EmailAssigneeChanges;
    val isEmail get() = this == EmailNewRequests || this == EmailAssigneeChanges
}

@Serializable
data class NotificationEvent(
    val eventID: String,
    val kind: String,
    val requestID: String,
    val requestNumber: String,
    val shortDescription: String,
    val requestType: String? = null,
    val address: String? = null,
    val customer: String? = null,
    val terminalID: String? = null,
    val oldAssignee: String? = null,
    val newAssignee: String? = null,
) {
    val title get() = if (kind == "new_active_request") "Новая задача" else "Изменен исполнитель"
    val body get() = if (kind == "new_active_request")
        "Появилась новая активная заявка $requestNumber: ${shortDescription.ifBlank { "без описания" }}."
    else "В заявке $requestNumber исполнитель изменен с ${oldAssignee.orEmpty().ifBlank { "не указан" }} на ${newAssignee.orEmpty().ifBlank { "не указан" }}."
    // The server requires a nonempty newAssignee for assignee_changed.
    val canEmail get() = kind != "assignee_changed" || !newAssignee.isNullOrBlank()
}

@Serializable data class NotificationBaseline(val id: String, val assignee: String)

@Serializable
data class NotificationStorage(
    val preferences: NotificationPreferences = NotificationPreferences(),
    val dirty: Set<NotificationPreference> = emptySet(),
    val revision: Long = 0,
    val fingerprint: String? = null,
    val snapshotTime: Long? = null,
    val baseline: List<NotificationBaseline> = emptyList(),
    val deliveredLocal: List<String> = emptyList(),
    val deliveredEmail: List<String> = emptyList(),
    val pendingEmail: List<NotificationEvent> = emptyList(),
)

data class NotificationSettingsState(
    val preferences: NotificationPreferences = NotificationPreferences(),
    val ready: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

internal fun notificationEvents(previous: List<NotificationBaseline>, current: List<SimpleOneRequest>): List<NotificationEvent> {
    val old = previous.associateBy { it.id }
    return current.distinctBy { it.id }.mapNotNull { record ->
        val before = old[record.id]
        val assignee = record.assignedUser.trim()
        if (before != null && before.assignee == assignee) return@mapNotNull null
        val number = listOf(record.incomingNumber, record.shortDescription).firstNotNullOfOrNull {
            Regex("SUTSPROD-\\d+", RegexOption.IGNORE_CASE).find(it)?.value?.uppercase()
        } ?: record.displayNumber.trim().ifBlank { record.id }
        NotificationEvent(
            eventID = if (before == null) "new:${record.id}:${record.registeredAt}" else "assignee:${record.id}:${before.assignee}:$assignee",
            kind = if (before == null) "new_active_request" else "assignee_changed",
            requestID = record.sysId.ifBlank { record.id }, requestNumber = number, shortDescription = record.shortDescription,
            requestType = record.requestType, address = record.address, customer = record.customer, terminalID = record.terminalId,
            oldAssignee = before?.assignee, newAssignee = assignee,
        )
    }
}
