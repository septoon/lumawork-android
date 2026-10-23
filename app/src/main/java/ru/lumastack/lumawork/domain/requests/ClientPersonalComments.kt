package ru.lumastack.lumawork.domain.requests

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

@Serializable
data class ClientPersonalComment(
    val id: String,
    val normalizedTIN: String = "",
    val displayTIN: String = "",
    val targets: List<ClientPersonalCommentTarget> = emptyList(),
    val terminalIDs: List<String> = emptyList(),
    val contactPerson: String = "",
    val phone: String = "",
    val email: String = "",
    val extraInfo: String? = null,
    val authorShortName: String? = null,
    val updatedAt: String = "",
)

@Serializable
data class ClientPersonalCommentTarget(val address: String) {
    val key: String get() = normalizedClientCommentScope(address)
    val displayText: String get() = address.trim().ifBlank { "Адрес не указан" }
}

data class ClientPersonalCommentTerminalOption(val terminalID: String, val address: String) {
    val key: String get() = normalizedClientCommentScope(terminalID)
    val displayText: String get() = listOf(terminalID, address.trim()).filter(String::isNotBlank).joinToString(" · ")
}

@Serializable
data class ClientPersonalCommentDraft(
    val serverID: String? = null,
    val sourceCommentID: String? = null,
    val tin: String = "",
    val targets: List<ClientPersonalCommentTarget> = emptyList(),
    val terminalIDs: List<String> = emptyList(),
    val contactPerson: String = "",
    val phone: String = "+7",
    val email: String = "",
    val extraInfo: String = "",
) {
    val normalizedTIN: String get() = normalizedClientCommentTIN(tin)
    val canSave: Boolean get() = normalizedTIN.isNotEmpty() &&
        (contactPerson.isNotBlank() || canonicalClientCommentPhone(phone).drop(2).isNotEmpty() ||
            email.isNotBlank() || extraInfo.isNotBlank())
    val targetKeys: Set<String> get() = targets.map { it.key }.toSet()

    fun addingTerminal(raw: String): ClientPersonalCommentDraft {
        val key = normalizedClientCommentScope(raw)
        return if (key.isEmpty() || terminalIDs.any { normalizedClientCommentScope(it) == key }) this
        else copy(terminalIDs = terminalIDs + raw.trim())
    }
}

fun normalizedClientCommentTIN(raw: String): String = raw.filter(Char::isDigit).takeIf { it.length == 10 || it.length == 12 }.orEmpty()

fun normalizedClientCommentScope(raw: String): String = raw.trim().replace('ё', 'е').replace('Ё', 'Е')
    .lowercase(Locale.forLanguageTag("ru-RU")).replace(Regex("\\s+"), " ")

fun canonicalClientCommentPhone(raw: String): String {
    var digits = raw.filter(Char::isDigit)
    digits = when {
        digits.length > 10 -> digits.takeLast(10)
        raw.trim().startsWith("+7") || digits.startsWith("7") || digits.startsWith("8") -> digits.drop(1)
        else -> digits
    }
    return "+7$digits"
}

fun displayClientCommentPhone(raw: String): String {
    val digits = raw.filter(Char::isDigit)
    if (digits.length != 10 && !(digits.length == 11 && digits.first() in "78")) return raw.trim()
    val national = digits.takeLast(10)
    return "+7 (${national.take(3)}) ${national.substring(3, 6)}-${national.substring(6, 8)}-${national.takeLast(2)}"
}

/** iOS priority: exact address within TIN, terminal within TIN, all addresses; terminal alone only without a valid TIN. */
class ClientPersonalCommentMatchingIndex(comments: List<ClientPersonalComment>) {
    private val ordered = comments.sortedByDescending { runCatching { Instant.parse(it.updatedAt) }.getOrDefault(Instant.MIN) }
    fun comment(tin: String, address: String = "", terminalId: String = ""): ClientPersonalComment? {
        val normalizedTIN = normalizedClientCommentTIN(tin)
        val addressKey = normalizedClientCommentScope(address)
        val terminalKey = normalizedClientCommentScope(terminalId)
        val valid = ordered.filter { normalizedClientCommentTIN(it.normalizedTIN.ifBlank { it.displayTIN }).isNotEmpty() }
        if (normalizedTIN.isEmpty()) return valid.firstOrNull { terminalKey.isNotEmpty() && it.terminalIDs.any { id -> normalizedClientCommentScope(id) == terminalKey } }
        val scoped = valid.filter { normalizedClientCommentTIN(it.normalizedTIN.ifBlank { it.displayTIN }) == normalizedTIN }
        scoped.firstOrNull { addressKey.isNotEmpty() && it.targets.any { target -> target.key == addressKey } }?.let { return it }
        // The iOS index assigns each terminal to its newest comment before checking its TIN.
        valid.firstOrNull { terminalKey.isNotEmpty() && it.terminalIDs.any { id -> normalizedClientCommentScope(id) == terminalKey } }
            ?.takeIf { normalizedClientCommentTIN(it.normalizedTIN.ifBlank { it.displayTIN }) == normalizedTIN }?.let { return it }
        return scoped.firstOrNull { it.targets.isEmpty() }
    }
}

fun SimpleOneRequest.supportsClientPersonalComments(): Boolean {
    val type = normalizedClientCommentScope(requestType.ifBlank { clientCommentField("Тип заявки") })
    return type != "returnequip" && type != "return_equip" && !type.contains("возврат")
}

fun SimpleOneRequest.clientCommentTIN(): String {
    for (label in listOf("ИНН ТСП", "ИНН", "ИНН клиента")) {
        normalizedClientCommentTIN(clientCommentField(label)).takeIf(String::isNotEmpty)?.let { return it }
    }
    val texts = listOf(description, additionalInformation, engineerComment)
    // Most archive rows have no TIN. Avoid parsing all detail labels just to discover that.
    if (texts.none { it.contains("ИНН", ignoreCase = true) }) return ""
    for (label in listOf("ИНН ТСП", "ИНН", "ИНН клиента")) {
        val pattern = Regex("(?:^|[\\n\\r;])\\s*${Regex.escape(label)}\\s*[:：=]\\s*([\\d \\-]+)", RegexOption.IGNORE_CASE)
        for (text in texts) for (match in pattern.findAll(text)) {
            normalizedClientCommentTIN(match.groupValues[1]).takeIf(String::isNotEmpty)?.let { return it }
        }
    }
    return normalizedClientCommentTIN(requestInformation(this)["инн тсп"].orEmpty())
}

private fun SimpleOneRequest.clientCommentField(vararg labels: String): String = labels.firstNotNullOfOrNull { label ->
    tableFields.entries.firstOrNull { normalizedClientCommentScope(it.key) == normalizedClientCommentScope(label) }?.value?.takeIf(String::isNotBlank)
}.orEmpty()

fun SimpleOneRequest.clientCommentAddress(): String = address.ifBlank { clientCommentField("Адрес установки терминала", "Адрес ТСП", "Адрес") }.trim()
fun SimpleOneRequest.clientCommentTerminalID(): String = terminalId.ifBlank { clientCommentField("ID терминал", "ID терминала", "ID терминала (TID)", "TID") }.trim()

fun clientCommentTargets(records: List<SimpleOneRequest>, tin: String, currentTarget: ClientPersonalCommentTarget? = null): List<ClientPersonalCommentTarget> {
    val normalized = normalizedClientCommentTIN(tin)
    val targets = if (normalized.isEmpty()) emptyList() else records.filter { it.supportsClientPersonalComments() && it.clientCommentTIN() == normalized }
        .mapNotNull { it.clientCommentAddress().takeIf(String::isNotBlank)?.let(::ClientPersonalCommentTarget) }
    return (listOfNotNull(currentTarget) + targets).distinctBy { it.key }.sortedBy { normalizedClientCommentScope(it.displayText) }
}

fun clientCommentTerminals(records: List<SimpleOneRequest>, tin: String, currentTerminalID: String = "", currentAddress: String = ""): List<ClientPersonalCommentTerminalOption> {
    val normalized = normalizedClientCommentTIN(tin)
    val options = if (normalized.isEmpty()) emptyList() else records.filter { it.clientCommentTIN() == normalized }
        .map { ClientPersonalCommentTerminalOption(it.clientCommentTerminalID(), it.clientCommentAddress()) }
    return (listOf(ClientPersonalCommentTerminalOption(currentTerminalID.trim(), currentAddress)) + options)
        .filter { it.key.isNotEmpty() }.groupBy { it.key }.values.map { group -> group.firstOrNull { it.address.isNotBlank() } ?: group.first() }
        .sortedWith(compareBy<ClientPersonalCommentTerminalOption> { it.terminalID.toLongOrNull() ?: Long.MAX_VALUE }.thenBy { it.key })
}

fun clientCommentEditorDraft(
    tin: String,
    existing: ClientPersonalComment?,
    currentTarget: ClientPersonalCommentTarget?,
    currentTerminalID: String,
    terminals: List<ClientPersonalCommentTerminalOption>,
    cachedDraft: ClientPersonalCommentDraft?,
): ClientPersonalCommentDraft {
    val normalized = normalizedClientCommentTIN(tin)
    if (cachedDraft != null && normalized.isNotEmpty() && cachedDraft.normalizedTIN == normalized &&
        cachedDraft.serverID == existing?.id && (existing != null || cachedDraft.targetKeys == setOfNotNull(currentTarget?.key))) return cachedDraft
    if (existing != null) {
        var draft = ClientPersonalCommentDraft(existing.id, tin = existing.displayTIN.ifBlank { tin }, targets = existing.targets,
            terminalIDs = existing.terminalIDs, contactPerson = existing.contactPerson, phone = canonicalClientCommentPhone(existing.phone),
            email = existing.email, extraInfo = existing.extraInfo.orEmpty())
        if (currentTarget != null && existing.targets.any { it.key == currentTarget.key }) draft = draft.addingTerminal(currentTerminalID)
        return draft
    }
    val ids = if (currentTarget == null) emptyList() else terminals.filter { normalizedClientCommentScope(it.address) == currentTarget.key }.map { it.terminalID }
    return ClientPersonalCommentDraft(tin = tin.filter(Char::isDigit), targets = listOfNotNull(currentTarget), terminalIDs = ids)
        .let { if (it.terminalIDs.isEmpty()) it.addingTerminal(currentTerminalID) else it }
}

fun clientPersonalCommentRows(comment: ClientPersonalComment): List<Pair<String, String>> = listOf(
    "Контактное лицо" to comment.contactPerson,
    "Телефон" to displayClientCommentPhone(comment.phone),
    "Почта" to comment.email,
    "Дополнительно" to comment.extraInfo.orEmpty(),
).filter { it.second.isNotBlank() }

fun clientPersonalCommentMetadata(comment: ClientPersonalComment): String {
    val date = runCatching { DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(comment.updatedAt)) }.getOrDefault("")
    return listOfNotNull(comment.authorShortName?.trim()?.takeIf(String::isNotBlank), date.takeIf(String::isNotBlank)).joinToString(" • ")
}
