package ru.lumastack.lumawork.data.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AssistantControllerTest {
    private val api = TestAssistantApi()
    private val cache = MemoryAssistantCache()
    private fun controller(owner: String = "owner", tools: AssistantTools = AssistantTools { emptyList() }) = AssistantController(owner, "token", false, api, cache, tools)
    @Test fun `stream completion stores final answer once and survives restart by account`() = runTest {
        val model = controller(); model.restore(); model.draft("Hello"); model.send()
        assertEquals(listOf("user", "assistant"), model.state.value.messages.map { it.role })
        assertEquals("Final", model.state.value.messages.last().content)
        val restored = controller(); restored.restore()
        assertEquals(model.state.value.messages, restored.state.value.messages)
        val other = controller("other"); other.restore(); assertTrue(other.state.value.messages.isEmpty())
    }
    @Test fun `stream fallback reuses request id and never duplicates user message`() = runTest {
        api.streamFailure = true
        val model = controller(); model.restore(); model.draft("Question"); model.send()
        assertEquals(2, api.requests.size)
        assertEquals(api.requests[0].requestId, api.requests[1].requestId)
        assertEquals(1, model.state.value.messages.count { it.role == "user" })
    }
    @Test fun `failed request is retryable with original id after process recreation`() = runTest {
        api.failure = true
        val model = controller(); model.restore(); model.draft("Question"); model.send()
        val id = api.requests.first().requestId
        assertTrue(model.state.value.canRetry)
        api.failure = false
        val restored = controller(); restored.restore(); restored.retry()
        assertEquals(id, api.requests.last().requestId)
        assertEquals(1, restored.state.value.messages.count { it.role == "user" })
        assertFalse(restored.state.value.canRetry)
    }
    @Test fun `recovery finds server answer before offering retry`() = runTest {
        api.failure = true
        api.recovered = true
        val model = controller(); model.restore(); model.draft("Question"); model.send()
        assertEquals("Recovered", model.state.value.messages.last().content)
        assertFalse(model.state.value.canRetry)
    }
    @Test fun `new chat rejects late delta and final response from previous generation`() = runTest {
        api.gate = CompletableDeferred()
        val model = controller(); model.restore(); model.draft("Question")
        val job = async { model.send() }
        api.entered.await(); model.newChat(); api.gate!!.complete(Unit); job.await()
        assertTrue(model.state.value.messages.isEmpty())
        assertFalse(model.state.value.sending)
    }
    @Test fun `switching chats during message loading never leaves loading flag stuck`() = runTest {
        val model = controller(); model.restore()
        api.messageGate = CompletableDeferred()
        val first = async { model.open("first") }
        api.messageEntered.await()
        model.stop()
        val second = async { model.open("second") }
        api.messageGate!!.complete(Unit)
        first.await(); second.await()
        assertFalse(model.state.value.loadingMessages)
        assertEquals("second", model.state.value.saved.conversationId)
    }
    @Test fun `recovery preserves old cached messages and older page cursor`() = runTest {
        val conversation = "cached-chat"
        cache.values["owner"] = AssistantSavedState(conversationId = conversation,
            messages = mapOf(conversation to listOf(AssistantMessage("old", "assistant", "Old answer"))))
        api.failure = true; api.recovered = true; api.recoveryCursor = "older-page"
        val model = controller(); model.restore(); model.draft("Next question"); model.send()
        assertEquals("Old answer", model.state.value.messages.first().content)
        assertEquals("Recovered", model.state.value.messages.last().content)
        assertEquals("older-page", model.state.value.messageCursor)
    }
    @Test fun `bounded tool loop resubmits only requested results with same ids`() = runTest {
        api.toolCycles = 1
        val tools = AssistantTools { requests -> requests.map { AssistantToolResult(it.name, kotlinx.serialization.json.JsonNull) } }
        val model = controller(tools = tools); model.restore(); model.draft("My requests"); model.send()
        assertEquals("active_requests", api.requests.last().toolResults!!.single().name)
        assertEquals(api.requests.first().requestId, api.requests.last().requestId)
        assertEquals("Final", model.state.value.messages.last().content)
    }
    @Test fun `endless tool requests stop after two local tool cycles`() = runTest {
        api.toolCycles = 99
        val model = controller(); model.restore(); model.draft("Question"); model.send()
        assertEquals(3, api.requests.size)
        assertTrue(model.state.value.canRetry)
    }
    @Test fun `conversation limit removes unaccepted local user message and prevents resubmission`() = runTest {
        api.response = AssistantResponse("conversation_full", conversation = AssistantStatus(isFull = true))
        val model = controller(); model.restore(); model.draft("Question"); model.send()
        assertTrue(model.state.value.full)
        assertTrue(model.state.value.messages.isEmpty())
        model.send(); assertEquals(1, api.requests.size)
    }
    @Test fun `offline conversation preserves draft without making API requests`() = runTest {
        val model = AssistantController("owner", "token", true, api, cache, AssistantTools { emptyList() })
        model.restore(); model.draft("Offline draft"); model.send()
        assertEquals("Offline draft", model.state.value.saved.draft)
        assertTrue(api.requests.isEmpty())
    }
    @Test fun `rename and delete mutate real history and do not delete on server failure`() = runTest {
        api.history = listOf(AssistantConversation("123", "Old"))
        val model = controller(); model.restore(); model.loadHistory(); model.rename("123", "New")
        assertEquals("New", model.state.value.saved.conversations.single().title)
        api.mutationFailure = true; model.delete("123")
        assertEquals(1, model.state.value.saved.conversations.size)
        api.mutationFailure = false; model.delete("123")
        assertTrue(model.state.value.saved.conversations.isEmpty())
    }
    @Test fun `current chat cannot be deleted while a response can still recreate it`() = runTest {
        api.gate = CompletableDeferred()
        val model = controller(); model.restore(); model.draft("Question")
        val send = async { model.send() }; api.entered.await()
        model.delete(model.state.value.saved.conversationId)
        assertEquals(0, api.deletes)
        api.gate!!.complete(Unit); send.await()
        model.delete(model.state.value.saved.conversationId)
        assertEquals(1, api.deletes)
    }
    @Test fun `deletion waits for message loading to finish`() = runTest {
        val model = controller(); model.restore(); api.messageGate = CompletableDeferred()
        val open = async { model.open("target") }; api.messageEntered.await()
        model.delete("target"); assertEquals(0, api.deletes)
        api.messageGate!!.complete(Unit); open.await()
    }
    @Test fun `send and open cannot race with an in-flight delete of the same chat`() = runTest {
        val model = controller(); model.restore(); model.draft("First question"); model.send()
        val id = model.state.value.saved.conversationId
        api.deleteGate = CompletableDeferred()
        val deleting = async { model.delete(id) }; api.deleteEntered.await()
        model.draft("Second question"); model.send()
        assertEquals(1, api.requests.size)
        api.messageCalls = 0; model.open(id); assertEquals(0, api.messageCalls)
        api.deleteGate!!.complete(Unit); deleting.await()
        assertTrue(model.state.value.saved.conversations.none { it.id == id })
    }
    @Test fun `failed request cannot be retried while its chat is being deleted`() = runTest {
        api.failure = true
        val model = controller(); model.restore(); model.draft("Question"); model.send()
        assertTrue(model.state.value.canRetry)
        val count = api.requests.size
        api.deleteGate = CompletableDeferred()
        val deleting = async { model.delete(model.state.value.saved.conversationId) }; api.deleteEntered.await()
        assertFalse(model.state.value.canRetry)
        model.retry(); assertEquals(count, api.requests.size)
        api.deleteGate!!.complete(Unit); deleting.await()
    }
    @Test fun `unknown answer kind with text is accepted but empty answer is rejected`() = runTest {
        api.response = AssistantResponse("future_kind", "Answer")
        val model = controller(); model.restore(); model.draft("Question"); model.send()
        assertEquals("Answer", model.state.value.messages.last().content)
        model.newChat(); api.response = AssistantResponse("answer", ""); model.draft("Question"); model.send()
        assertTrue(model.state.value.canRetry)
    }
}
private class MemoryAssistantCache : AssistantCache {
    val values = mutableMapOf<String, AssistantSavedState>()
    override suspend fun read(owner: String) = values[owner]
    override suspend fun write(owner: String, state: AssistantSavedState) { values[owner] = state }
}
private class TestAssistantApi : AssistantApi {
    var streamFailure = false; var failure = false; var recovered = false; var toolCycles = 0; var mutationFailure = false
    var response = AssistantResponse("answer", "Final")
    var history = emptyList<AssistantConversation>()
    var deletes = 0
    var messageCalls = 0
    val deleteEntered = CompletableDeferred<Unit>(); var deleteGate: CompletableDeferred<Unit>? = null
    val requests = mutableListOf<AssistantRunRequest>()
    val messageEntered = CompletableDeferred<Unit>(); var messageGate: CompletableDeferred<Unit>? = null; var recoveryCursor: String? = null
    val entered = CompletableDeferred<Unit>(); var gate: CompletableDeferred<Unit>? = null
    override suspend fun run(token: String, request: AssistantRunRequest, streaming: Boolean, onDelta: (String) -> Unit): AssistantResponse {
        requests += request; entered.complete(Unit); gate?.await(); onDelta("Partial")
        if (failure || (streaming && streamFailure)) throw AssistantFailure("Network")
        if (toolCycles-- > 0) return AssistantResponse("tool_request", requests = listOf(AssistantToolRequest("active_requests")))
        return response
    }
    override suspend fun conversations(token: String, cursor: String?) = AssistantConversationPage(history)
    override suspend fun messages(token: String, conversationId: String, cursor: String?): AssistantMessagePage {
        messageCalls++; messageEntered.complete(Unit); messageGate?.await()
        return if (recovered) AssistantMessagePage(listOf(
            AssistantMessage("server", "assistant", "Recovered", requests.first().requestId.uppercase())), recoveryCursor) else AssistantMessagePage(emptyList())
    }
    override suspend fun rename(token: String, conversationId: String, title: String) { if (mutationFailure) error("Network") }
    override suspend fun delete(token: String, conversationId: String) { deletes++; deleteEntered.complete(Unit); deleteGate?.await(); if (mutationFailure) error("Network") }
    override suspend fun feedback(token: String, body: kotlinx.serialization.json.JsonObject) {}
}
