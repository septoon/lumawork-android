package ru.lumastack.lumawork.data.assistant

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Owns one signed-in account. Generation checks prevent late responses from touching another chat. */
class AssistantController(
    private val owner: String, private val token: String, private val offline: Boolean,
    private val api: AssistantApi, private val cache: AssistantCache, private val tools: AssistantTools,
) {
    private val mutable = MutableStateFlow(AssistantState())
    val state = mutable.asStateFlow()
    private val disk = Mutex()
    @Volatile private var generation = 0L
    private var historyRevision = 0L
    suspend fun restore() {
        try { cache.read(owner)?.let { value -> mutable.update { it.copy(saved = value) } } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notice("Не удалось прочитать сохранённые чаты.") }
        finally { mutable.update { it.copy(initializing = false) } }
    }
    fun draft(value: String) { mutable.update { it.copy(saved = it.saved.copy(draft = value.take(4_000))) } }
    fun attachment(value: AssistantAttachment?) { mutable.update { it.copy(attachment = value) } }
    fun preparing(value: Boolean) { mutable.update { it.copy(preparingAttachment = value) } }
    fun notice(value: String?) { mutable.update { it.copy(notice = value) } }
    suspend fun save() = disk.withLock { cache.write(owner, state.value.saved) }
    suspend fun newChat() {
        generation++
        mutable.update { it.copy(saved = it.saved.copy(conversationId = assistantId(), draft = "", pending = null),
            sending = false, loadingMessages = false, activity = null, attachment = null, messageCursor = null) }
        save()
    }
    fun stop() {
        generation++
        mutable.update { it.copy(sending = false, loadingMessages = false, activity = null, saved = it.saved.copy(pending = null)) }
    }
    suspend fun loadHistory(more: Boolean = false) {
        if (offline || state.value.loadingHistory || state.value.initializing) return
        val revision = historyRevision
        val cursor = if (more) state.value.historyCursor ?: return else null
        mutable.update { it.copy(loadingHistory = true) }
        try {
            val page = api.conversations(token, cursor)
            if (revision == historyRevision) {
                val remote = page.conversations.map { it.copy(id = it.id.lowercase()) }
                mutable.update { old ->
                    // Retain a local pending conversation until the server finishes accepting it.
                    val pendingId = old.saved.pending?.conversationId
                    val pending = old.saved.conversations.filter { it.id == pendingId && remote.none { r -> r.id == it.id } }
                    val all = if (more) old.saved.conversations + remote else pending + remote
                    old.copy(saved = old.saved.copy(conversations = all.distinctBy { it.id }), historyCursor = page.nextCursor)
                }
                save()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { notice(message(error)) }
        finally { mutable.update { it.copy(loadingHistory = false) } }
    }
    suspend fun open(id: String, more: Boolean = false) {
        if (state.value.initializing || (more && (state.value.loadingMessages || state.value.sending))) return
        val target = id.lowercase()
        if (target in state.value.deleting) { notice("Чат удаляется. Дождитесь завершения операции."); return }
        val previous = state.value.saved.conversationId
        val cursor = if (more) state.value.messageCursor ?: return else null
        val revision = if (more) generation else ++generation
        if (!more) mutable.update { it.copy(sending = false, activity = null, attachment = null) }
        if (offline) {
            if (state.value.saved.messages.containsKey(target)) mutable.update { it.copy(saved = it.saved.copy(conversationId = target, pending = null, draft = "")) }
            else notice("Этот чат ещё не сохранён на устройстве. Подключитесь к сети.")
            save(); return
        }
        mutable.update { it.copy(loadingMessages = true) }
        if (!more && state.value.saved.messages.containsKey(target)) mutable.update { it.copy(saved = it.saved.copy(conversationId = target)) }
        try {
            val page = api.messages(token, target, cursor)
            if (revision != generation) return
            mutable.update { old ->
                val messages = if (more) mergeMessages(page.messages, old.saved.messages[target].orEmpty()) else page.messages
                old.copy(saved = old.saved.copy(conversationId = target, messages = old.saved.messages + (target to messages),
                    draft = if (target == previous) old.saved.draft else "", pending = old.saved.pending?.takeIf { it.conversationId == target }),
                    messageCursor = page.nextCursor)
            }
            applyStatus(target, page.conversation); save()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (revision == generation) notice(message(error)) }
        finally { if (revision == generation) mutable.update { it.copy(loadingMessages = false) } }
    }
    suspend fun rename(id: String, title: String) {
        val trimmed = title.trim().take(80)
        if (id.lowercase() in state.value.deleting) return
        if (trimmed.isEmpty() || offline) { notice("Для изменения названия требуется сеть и непустое название."); return }
        try {
            api.rename(token, id, trimmed); historyRevision++
            mutable.update { old -> old.copy(saved = old.saved.copy(conversations = old.saved.conversations.map { if (it.id.equals(id, true)) it.copy(title = trimmed) else it })) }
            save()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { notice(message(error)) }
    }
    suspend fun delete(id: String) {
        val target = id.lowercase()
        if (target in state.value.deleting) return
        if (state.value.loadingMessages || (state.value.sending && state.value.saved.conversationId == target)) {
            notice("Дождитесь окончания загрузки или остановите ответ перед удалением чата."); return
        }
        if (offline) { notice("Для удаления чата подключитесь к сети."); return }
        historyRevision++
        mutable.update { it.copy(deleting = it.deleting + target) }
        try {
            try { api.delete(token, target) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if ((error as? AssistantFailure)?.status != 404) { notice(message(error)); return } }
            historyRevision++
            if (state.value.saved.conversationId == target) newChat()
            mutable.update { old -> old.copy(saved = old.saved.copy(conversations = old.saved.conversations.filterNot { it.id == target }, messages = old.saved.messages - target)) }
            save()
        } finally { mutable.update { it.copy(deleting = it.deleting - target) } }
    }
    suspend fun send() {
        val current = state.value
        if (current.saved.conversationId in current.deleting) { notice("Чат удаляется. Создайте новый чат или дождитесь завершения операции."); return }
        if (current.initializing || current.sending || current.loadingMessages || current.full || current.preparingAttachment) return
        if (offline) { notice("Для ответа помощника подключитесь к сети. Черновик сохранён."); save(); return }
        val text = current.saved.draft.trim()
        val attachment = current.attachment
        if (text.isEmpty() && attachment == null) return
        val revision = ++generation
        mutable.update { it.copy(sending = true) }
        val request = AssistantRunRequest(assistantId(), current.saved.conversationId, text, image = attachment?.image, document = attachment?.document)
        val display = text.ifEmpty { attachment?.title ?: "Изображение" }
        val metadata = attachment?.let { buildJsonObject { put("kind", if (it.document != null) "document" else "image"); it.document?.let { d -> put("fileName", d.fileName); put("wasTruncated", d.wasTruncated) } } }
        updateMessages { it + AssistantMessage("local-user-${request.requestId}", "user", display, request.requestId, metadata, Instant.now().toString()) }
        historyRevision++
        mutable.update { old ->
            val existing = old.conversation
            val now = Instant.now().toString()
            val conversation = existing?.copy(preview = display.take(140), lastMessageAt = now)
                ?: AssistantConversation(request.conversationId, display.take(80), display.take(140), now, now)
            old.copy(saved = old.saved.copy(conversations = listOf(conversation) + old.saved.conversations.filterNot { it.id == conversation.id }, draft = "", pending = request), attachment = null)
        }
        try {
            save()
            if (revision == generation && state.value.saved.conversationId == request.conversationId) perform(request, revision)
        } finally { if (revision == generation) mutable.update { it.copy(sending = false, activity = null) } }
    }
    suspend fun retry() {
        val request = state.value.saved.pending ?: return
        if (!state.value.canRetry || offline || state.value.full || request.conversationId != state.value.saved.conversationId) return
        updateMessages { it.filterNot { m -> m.role == "assistant" && m.requestId.equals(request.requestId, true) } }
        perform(request)
    }
    private suspend fun perform(original: AssistantRunRequest, revision: Long = ++generation) {
        if (original.conversationId in state.value.deleting) return
        fun active() = revision == generation && state.value.saved.conversationId == original.conversationId
        mutable.update { it.copy(sending = true, activity = if (it.conversation?.willCompressOnNextRun == true) "Сжимаем контекст…" else "Думаю…", notice = null) }
        var request = original
        try {
            for (cycle in 0..2) {
                val response = try {
                    coroutineScope {
                        // OkHttp callbacks run off-main; consume all deltas on the controller context before applying done.
                        val deltas = Channel<String>(Channel.UNLIMITED)
                        val consumer = launch { for (delta in deltas) if (active()) appendDelta(original.requestId, delta) }
                        try { api.run(token, request, true) { delta -> deltas.trySend(delta) } }
                        finally { deltas.close(); consumer.join() }
                    }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (!active()) return
                    if (error is AssistantFailure && !error.retryable) throw error
                    api.run(token, request, false) {}
                }
                if (!active()) return
                applyStatus(original.conversationId, response.conversation)
                when (response.kind) {
                    "tool_request" -> {
                        check(cycle < 2 && response.requests.isNotEmpty()) { "Не удалось получить необходимые данные за два шага." }
                        mutable.update { it.copy(activity = "Загружаем источники…") }
                        val results = tools.execute(response.requests)
                        if (!active()) return
                        request = original.copy(image = null, toolResults = results)
                        mutable.update { it.copy(activity = "Думаю…") }
                    }
                    "conversation_full" -> {
                        updateMessages { it.filterNot { m -> m.requestId.equals(original.requestId, true) } }
                        mutable.update { it.copy(saved = it.saved.copy(pending = null, draft = original.message)) }
                        notice(response.text ?: "Лимит этого чата исчерпан. Создайте новый чат."); save(); return
                    }
                    else -> {
                        val text = response.text?.takeIf(String::isNotBlank) ?: error("Помощник вернул пустой ответ.")
                        val metadata = buildJsonObject {
                            if (response.sources.isNotEmpty()) put("sources", JsonArray(response.sources.map(::JsonPrimitive)))
                            response.knowledge?.let { put("knowledge", it) }
                        }
                        replaceAnswer(original.requestId, if (response.truncated) "$text\n\nОтвет сокращён до установленного лимита." else text, metadata,
                            response.messageTimestamps?.assistant ?: Instant.now().toString())
                        response.messageTimestamps?.user?.let { time -> updateMessages { it.map { m -> if (m.role == "user" && m.requestId.equals(original.requestId, true)) m.copy(createdAt = time) else m } } }
                        mutable.update { it.copy(saved = it.saved.copy(pending = null)) }
                        historyRevision++
                        save(); return
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (!active()) return
            for (pause in listOf(250L, 500L, 750L, 1000L)) {
                delay(pause)
                if (!active()) return
                try {
                    val page = api.messages(token, original.conversationId)
                    if (active() && page.messages.any { it.role == "assistant" && it.requestId.equals(original.requestId, true) }) {
                        mutable.update { old -> old.copy(saved = old.saved.copy(messages = old.saved.messages +
                            (original.conversationId to mergeMessages(old.saved.messages[original.conversationId].orEmpty(), page.messages)), pending = null),
                            messageCursor = page.nextCursor ?: old.messageCursor) }
                        applyStatus(original.conversationId, page.conversation); save(); return
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Recovery never replaces the original failure. */ }
            }
            if (active()) { notice(message(error)); save() }
        } finally { if (active()) mutable.update { it.copy(sending = false, activity = null) } }
    }
    suspend fun feedback(message: AssistantMessage, feedback: String) {
        if (offline || feedback !in listOf("support", "contradict")) return
        val target = state.value.saved.conversationId
        val primary = message.primaryClaim ?: return
        val knowledge = message.attachmentMeta?.get("knowledge")?.jsonObject ?: return
        try {
            api.feedback(token, buildJsonObject {
                put("claimId", primary["id"] ?: return); put("answerTraceId", knowledge["answerTraceId"] ?: return)
                put("feedback", feedback); put("source", if (feedback == "support") "lumawork_like" else "lumawork_dislike"); put("clientRequestId", assistantId()); put("conversationId", target)
            })
            if (state.value.saved.conversationId != target) return
            updateMessages { it.map { m -> if (m.id == message.id) m.copy(attachmentMeta = JsonObject(m.attachmentMeta.orEmpty() + ("knowledgeFeedback" to JsonPrimitive(feedback)))) else m } }
            save()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { notice(message(error)) }
    }
    private fun applyStatus(id: String, status: AssistantStatus?) { mutable.update { old -> old.copy(saved = old.saved.copy(conversations = old.saved.conversations.map { if (it.id == id) it.withStatus(status) else it })) } }
    private fun updateMessages(transform: (List<AssistantMessage>) -> List<AssistantMessage>) { mutable.update { old ->
        old.copy(saved = old.saved.copy(messages = old.saved.messages + (old.saved.conversationId to transform(old.messages))))
    } }
    private fun appendDelta(id: String, delta: String) {
        if (delta.isEmpty()) return
        val existing = state.value.messages.firstOrNull { it.role == "assistant" && it.requestId.equals(id, true) }
        replaceAnswer(id, existing?.content.orEmpty() + delta, existing?.attachmentMeta, existing?.createdAt ?: Instant.now().toString())
        mutable.update { it.copy(activity = null) }
    }
    private fun replaceAnswer(id: String, text: String, metadata: JsonObject?, time: String) = updateMessages { messages ->
        val message = AssistantMessage("local-assistant-$id", "assistant", text, id, metadata, time)
        val index = messages.indexOfFirst { it.role == "assistant" && it.requestId.equals(id, true) }
        if (index < 0) messages + message else messages.toMutableList().apply { this[index] = message }
    }
    private fun mergeMessages(older: List<AssistantMessage>, newer: List<AssistantMessage>): List<AssistantMessage> {
        fun key(message: AssistantMessage) = message.requestId?.let { "${message.role}:${it.lowercase()}" } ?: message.id
        val incoming = newer.map(::key).toSet()
        return (older.filterNot { key(it) in incoming } + newer).distinctBy(::key)
    }
    private fun message(error: Exception) = (error as? AssistantFailure)?.message ?: error.message?.takeIf { it.length <= 180 } ?: "Помощник временно недоступен. Повторите запрос."
}
