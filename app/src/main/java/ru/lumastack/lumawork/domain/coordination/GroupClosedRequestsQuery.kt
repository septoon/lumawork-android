package ru.lumastack.lumawork.domain.coordination

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.lumastack.lumawork.domain.requests.ClosedRequestsItem
import ru.lumastack.lumawork.domain.requests.closedRequestTypeTitle
import ru.lumastack.lumawork.domain.requests.normalizedClosedRequestsText
import ru.lumastack.lumawork.domain.requests.prepareClosedRequests
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

fun groupClosedRequestTypeKey(request: SimpleOneRequest): String = request.requestType.trim()
    .split(GROUP_TYPE_WHITESPACE, limit = 2).first().lowercase(Locale.ROOT)

/** Group archive status is the native SimpleOne state, independent of personal closure codes. */
fun prepareGroupClosedRequests(records: List<SimpleOneRequest>): List<ClosedRequestsItem> =
    prepareClosedRequests(records).map { item ->
        val state = when (val raw = item.request.state.trim()) { "completed" -> "Выполнена"; "closed", "" -> "Закрыта"; else -> raw }
        item.copy(
            statusText = state,
            searchText = normalizedClosedRequestsText(listOf(
                item.request.searchText, item.typeText, state, item.timeText,
                item.date?.format(DateTimeFormatter.ofPattern("d MMMM yyyy EEEE", Locale.forLanguageTag("ru-RU"))).orEmpty(),
            ).joinToString("\n")),
        )
    }

fun groupClosedRequestTypes(records: List<SimpleOneRequest>): List<Pair<String, String>> = records
    .associate { groupClosedRequestTypeKey(it) to closedRequestTypeTitle(it.requestType).ifBlank { "Тип не указан" } }
    .toList().sortedBy { it.second }

/** Empty search is strictly today/yesterday; search always spans the entire archive. */
fun queryGroupClosedRequests(
    items: List<ClosedRequestsItem>,
    query: String,
    excludedTypeKeys: Set<String> = emptySet(),
    today: LocalDate = LocalDate.now(),
): List<ClosedRequestsItem> {
    val normalized = normalizedClosedRequestsText(query)
    val yesterday = today.minusDays(1)
    return items.filter { item ->
        val matches = if (normalized.isNotEmpty()) item.searchText.contains(normalized)
            else item.day?.let { it >= yesterday && it <= today } == true
        matches && (excludedTypeKeys.isEmpty() || groupClosedRequestTypeKey(item.request) !in excludedTypeKeys)
    }
}

private val GROUP_TYPE_WHITESPACE = Regex("\\s+")
