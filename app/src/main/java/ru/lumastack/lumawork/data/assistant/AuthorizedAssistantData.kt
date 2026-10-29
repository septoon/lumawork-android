package ru.lumastack.lumawork.data.assistant

import kotlinx.serialization.json.JsonObject
import ru.lumastack.lumawork.data.simpleone.SimpleOneAccess

class AuthorizedAssistantApi(private val delegate: AssistantApi, private val owner: String, private val access: SimpleOneAccess) : AssistantApi {
    override suspend fun run(token: String, request: AssistantRunRequest, streaming: Boolean, onDelta: (String) -> Unit) = access.protect(owner) {
        delegate.run(token, request, streaming) { delta -> access.require(owner); onDelta(delta) }
    }
    override suspend fun conversations(token: String, cursor: String?) = access.protect(owner) { delegate.conversations(token, cursor) }
    override suspend fun messages(token: String, conversationId: String, cursor: String?) = access.protect(owner) { delegate.messages(token, conversationId, cursor) }
    override suspend fun rename(token: String, conversationId: String, title: String) = access.protect(owner) { delegate.rename(token, conversationId, title) }
    override suspend fun delete(token: String, conversationId: String) = access.protect(owner) { delegate.delete(token, conversationId) }
    override suspend fun feedback(token: String, body: JsonObject) = access.protect(owner) { delegate.feedback(token, body) }
}

class AuthorizedAssistantCache(private val delegate: AssistantCache, private val access: SimpleOneAccess) : AssistantCache {
    override suspend fun read(owner: String) = access.protect(owner) { delegate.read(owner) }
    override suspend fun write(owner: String, state: AssistantSavedState) = access.protect(owner) { delegate.write(owner, state) }
}

class AuthorizedAssistantTools(private val delegate: AssistantTools, private val owner: String, private val access: SimpleOneAccess) : AssistantTools {
    override suspend fun execute(requests: List<AssistantToolRequest>) = access.protect(owner) { delegate.execute(requests) }
}
