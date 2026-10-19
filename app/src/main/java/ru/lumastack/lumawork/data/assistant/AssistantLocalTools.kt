package ru.lumastack.lumawork.data.assistant

import java.time.Instant
import kotlinx.serialization.json.*
import ru.lumastack.lumawork.data.backpack.BackpackRepository
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

class AssistantLocalTools(private val owner: String, private val simpleOne: SimpleOneRepository,
    private val backpack: BackpackRepository,
) : AssistantTools {
    override suspend fun execute(requests: List<AssistantToolRequest>): List<AssistantToolResult> {
        require(requests.size in 1..4) { "Помощник запросил слишком много источников." }
        return requests.map { request ->
            val limit = (request.arguments["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 10)
            val query = request.arguments["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val payload = when (request.name) {
                "active_requests" -> {
                    val snapshot = simpleOne.readSnapshot(owner)
                    val current = if (snapshot?.updatedAtEpochMillis != null) snapshot else simpleOne.refresh(owner, snapshot)
                    requestsPayload(current.activeRequests, query, limit, false, current.updatedAtEpochMillis)
                }
                "closed_requests" -> {
                    val cached = simpleOne.readClosedArchive(owner)
                    val archive = cached ?: simpleOne.refreshClosedArchive(owner, simpleOne.readSnapshot(owner))
                    requestsPayload(archive.requests, query, limit, true, archive.updatedAtEpochMillis)
                }
                "request_details" -> {
                    val id = request.arguments["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val available = simpleOne.readSnapshot(owner)?.activeRequests.orEmpty() + simpleOne.readClosedArchive(owner)?.requests.orEmpty()
                    val record = if (id.isNotBlank()) available.firstOrNull { r -> listOf(r.sysId, r.number, r.terminalId).any { it.equals(id, true) } }
                        else selectRequests(available, query).firstOrNull()
                    if (record == null) buildJsonObject { put("status", "not_found") }
                    else summary(simpleOne.requestDetails(owner, record), closed = false, detailed = true)
                }
                "backpack" -> {
                    val user = simpleOne.readSnapshot(owner)?.currentUser ?: simpleOne.resolveCurrentUser(owner).currentUser
                        ?: error("Для доступа к рюкзаку войдите в SimpleOne.")
                    val snapshot = backpack.read(owner, user.sysId) ?: backpack.refresh(owner, user.sysId)
                    val tokens = tokens(query)
                    val matches = snapshot.items.filter { item -> tokens.isEmpty() || tokens.any { "${item.name} ${item.serialNumber}".lowercase().contains(it) } }
                    val selected = matches.ifEmpty { snapshot.items }.take(limit)
                    buildJsonObject {
                        put("updatedAt", snapshot.updatedAtEpochMillis?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull)
                        putJsonObject("totals") { put("positions", snapshot.items.size); put("quantity", snapshot.items.sumOf { it.quantity.coerceAtLeast(0) }) }
                        putJsonArray("items") { selected.forEach { item -> add(buildJsonObject {
                            put("id", item.id); put("type", "оборудование"); put("model", item.name)
                            put("quantity", item.quantity.coerceAtLeast(0)); put("updatedAt", item.receivedAt)
                        }) } }
                    }
                }
                else -> error("Помощник запросил недоступный источник данных.")
            }
            AssistantToolResult(request.name, payload)
        }
    }
    private fun requestsPayload(records: List<SimpleOneRequest>, query: String, limit: Int, closed: Boolean, updated: Long?) = buildJsonObject {
        put("count", records.size); put("updatedAt", updated?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull)
        put("requests", JsonArray(selectRequests(records, query).take(limit).map { summary(it, closed) }))
    }
    private fun summary(r: SimpleOneRequest, closed: Boolean, detailed: Boolean = false) = buildJsonObject {
        put("id", r.sysId); put("number", r.number); put("equipmentType", r.requestType); put("equipmentModel", r.terminalModel)
        put("symptom", if (detailed) r.description.ifBlank { r.shortDescription }.take(4000) else r.shortDescription.take(500))
        if (closed) { put("closedAt", r.closedAt.ifBlank { r.resolvedAt }); put("resolutionCode", r.closureCode); put("result", r.resolution.ifBlank { r.engineerComment }.take(2000)) }
        else { put("status", r.state); put("dueAt", r.deadline) }
        if (detailed) { put("project", r.clientServiceParent.ifBlank { r.assignmentGroup }); put("terminalId", r.terminalId)
            put("errorCode", r.waitingReason); put("performedActions", r.engineerComment.take(4000)); put("result", r.resolution.take(4000)); put("updatedAt", r.sysUpdatedAt) }
    }
    private fun selectRequests(records: List<SimpleOneRequest>, query: String): List<SimpleOneRequest> {
        val tokens = tokens(query)
        val sorted = records.sortedByDescending { it.registeredAt }
        if (tokens.isEmpty()) return sorted
        val scored = sorted.map { record -> record to tokens.count { token ->
            "${record.number} ${record.terminalModel} ${record.shortDescription} ${record.resolution} ${record.engineerComment}".lowercase().contains(token)
        } }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        return scored.ifEmpty { sorted }
    }
    private fun tokens(value: String) = value.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }
}
