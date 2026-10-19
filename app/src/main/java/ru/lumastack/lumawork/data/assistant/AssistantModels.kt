package ru.lumastack.lumawork.data.assistant

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun assistantId() = UUID.randomUUID().toString()
@Serializable data class AssistantStatus(
    val usedTokens: Int = 0, val tokenLimit: Int = 0, val isFull: Boolean = false,
    val contextTokens: Int = 0, val compressionThreshold: Int = 0, val compactions: Int = 0, val willCompressOnNextRun: Boolean = false,
)
@Serializable data class AssistantConversation(
    val id: String, val title: String, val preview: String = "", val lastMessageAt: String = "", val createdAt: String = "",
    val usedTokens: Int? = null, val tokenLimit: Int? = null, val isFull: Boolean? = null,
    val contextTokens: Int? = null, val compressionThreshold: Int? = null, val compactions: Int? = null, val willCompressOnNextRun: Boolean? = null,
) {
    fun withStatus(status: AssistantStatus?) = if (status == null) this else copy(usedTokens = status.usedTokens, tokenLimit = status.tokenLimit,
        isFull = status.isFull, contextTokens = status.contextTokens, compressionThreshold = status.compressionThreshold,
        compactions = status.compactions, willCompressOnNextRun = status.willCompressOnNextRun)
}
@Serializable data class AssistantMessage(
    val id: String, val role: String, val content: String, val requestId: String? = null,
    val attachmentMeta: JsonObject? = null, val createdAt: String = "",
) {
    val sources: List<String> get() = runCatching { attachmentMeta?.get("sources")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull().orEmpty()
    val primaryClaim: JsonObject? get() = runCatching {
        attachmentMeta?.get("knowledge")?.jsonObject?.get("claims")?.jsonArray?.map { it.jsonObject }
            ?.filter { it["role"]?.jsonPrimitive?.contentOrNull == "primary" }?.singleOrNull()
    }.getOrNull()
}
@Serializable data class AssistantImage(val mimeType: String, val base64: String)
@Serializable data class AssistantDocument(
    val fileName: String, val mimeType: String, val text: String = "", val extraction: String,
    val sourceBytes: Int, val wasTruncated: Boolean = false, val pageCount: Int? = null,
    val sheetNames: List<String> = emptyList(), val rawBase64: String? = null,
)
@Serializable data class AssistantAttachment(val title: String, val image: AssistantImage? = null, val document: AssistantDocument? = null, val note: String? = null)
@Serializable data class AssistantToolRequest(val name: String, val arguments: JsonObject = JsonObject(emptyMap()))
@Serializable data class AssistantToolResult(val name: String, val payload: JsonElement)
@Serializable data class AssistantRunRequest(
    val requestId: String, val conversationId: String, val message: String,
    val currentScreen: String = "assistant", val image: AssistantImage? = null,
    val document: AssistantDocument? = null, val toolResults: List<AssistantToolResult>? = null,
)
@Serializable data class AssistantTimestamps(val user: String? = null, val assistant: String? = null)
@Serializable data class AssistantResponse(
    val kind: String, val text: String? = null, val requests: List<AssistantToolRequest> = emptyList(),
    val sources: List<String> = emptyList(), val truncated: Boolean = false, val contextCompacted: Boolean = false,
    val conversation: AssistantStatus? = null, val knowledge: JsonObject? = null, val messageTimestamps: AssistantTimestamps? = null,
)
@Serializable data class AssistantConversationPage(val conversations: List<AssistantConversation>, val nextCursor: String? = null)
@Serializable data class AssistantMessagePage(val messages: List<AssistantMessage>, val nextCursor: String? = null, val conversation: AssistantStatus? = null)
@Serializable data class AssistantSavedState(
    val conversations: List<AssistantConversation> = emptyList(), val conversationId: String = assistantId(),
    val messages: Map<String, List<AssistantMessage>> = emptyMap(), val draft: String = "", val pending: AssistantRunRequest? = null,
)
data class AssistantState(
    val saved: AssistantSavedState = AssistantSavedState(), val initializing: Boolean = true,
    val sending: Boolean = false, val loadingHistory: Boolean = false, val loadingMessages: Boolean = false,
    val historyCursor: String? = null, val messageCursor: String? = null, val activity: String? = null,
    val notice: String? = null, val deleting: Set<String> = emptySet(), val attachment: AssistantAttachment? = null, val preparingAttachment: Boolean = false,
) {
    val conversation get() = saved.conversations.firstOrNull { it.id.equals(saved.conversationId, true) }
    val messages get() = saved.messages[saved.conversationId].orEmpty()
    val full get() = conversation?.isFull == true
    val canRetry get() = !sending && saved.conversationId !in deleting && saved.pending?.conversationId == saved.conversationId
}
interface AssistantApi {
    suspend fun run(token: String, request: AssistantRunRequest, streaming: Boolean, onDelta: (String) -> Unit): AssistantResponse
    suspend fun conversations(token: String, cursor: String? = null): AssistantConversationPage
    suspend fun messages(token: String, conversationId: String, cursor: String? = null): AssistantMessagePage
    suspend fun rename(token: String, conversationId: String, title: String)
    suspend fun delete(token: String, conversationId: String)
    suspend fun feedback(token: String, body: JsonObject)
}
interface AssistantCache {
    suspend fun read(owner: String): AssistantSavedState?
    suspend fun write(owner: String, state: AssistantSavedState)
}
fun interface AssistantTools { suspend fun execute(requests: List<AssistantToolRequest>): List<AssistantToolResult> }
class AssistantFailure(message: String, val status: Int? = null, val retryable: Boolean = true) : Exception(message)
