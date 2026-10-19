package ru.lumastack.lumawork.ui.assistant

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import ru.lumastack.lumawork.BuildConfig
import ru.lumastack.lumawork.data.assistant.*
import ru.lumastack.lumawork.data.backpack.BackpackRepository
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.domain.model.AppSession

class AssistantViewModel(context: Context, session: AppSession, offline: Boolean,
    simpleOne: SimpleOneRepository, backpack: BackpackRepository,
) : ViewModel() {
    val controller = AssistantController(session.user.id, session.token, offline, OkHttpAssistantApi(BuildConfig.API_ORIGIN),
        FileAssistantCache(context), AssistantLocalTools(session.user.id, simpleOne, backpack))
    val state = controller.state
    private val attachments = AssistantAttachments(context.applicationContext)
    private var sendJob: Job? = null
    private var draftJob: Job? = null
    private var attachmentJob: Job? = null
    init { launch {
        controller.restore()
        controller.loadHistory()
        if (state.value.saved.pending == null && state.value.conversation != null) controller.open(state.value.saved.conversationId)
    } }
    fun draft(value: String) {
        controller.draft(value); draftJob?.cancel()
        draftJob = launch { delay(350); controller.save() }
    }
    fun send() { if (sendJob?.isActive == true) return; draftJob?.cancel(); sendJob = launch { controller.send() } }
    fun retry() { if (sendJob?.isActive == true) return; sendJob = launch { controller.retry() } }
    fun stop() { controller.stop(); sendJob?.cancel(); launch { controller.save() } }
    fun newChat() { stop(); attachmentJob?.cancel(); controller.preparing(false); launch { controller.newChat() } }
    fun open(id: String) { stop(); attachmentJob?.cancel(); controller.preparing(false); launch { controller.open(id) } }
    fun history(more: Boolean = false) { launch { controller.loadHistory(more) } }
    fun moreMessages() { launch { controller.open(state.value.saved.conversationId, more = true) } }
    fun rename(id: String, title: String) { launch { controller.rename(id, title) } }
    fun delete(id: String) { launch { controller.delete(id) } }
    fun feedback(message: AssistantMessage, value: String) { launch { controller.feedback(message, value) } }
    fun attach(uri: Uri, image: Boolean = false, cleanup: (() -> Unit)? = null) {
        attachmentJob?.cancel()
        attachmentJob = launch {
            controller.preparing(true)
            try { controller.attachment(attachments.prepare(uri, image)) }
            finally { cleanup?.invoke(); controller.preparing(false) }
        }
    }
    fun removeAttachment() { attachmentJob?.cancel(); controller.attachment(null); controller.preparing(false) }
    fun notice(value: String?) = controller.notice(value)
    private fun launch(block: suspend () -> Unit): Job = viewModelScope.launch {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { controller.notice((error as? AssistantFailure)?.message ?: error.message?.takeIf { it.length <= 180 } ?: "Не удалось выполнить действие. Попробуйте ещё раз.") }
    }
    override fun onCleared() { controller.stop() }
    companion object {
        fun factory(context: Context, session: AppSession, offline: Boolean, simpleOne: SimpleOneRepository, backpack: BackpackRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AssistantViewModel(context.applicationContext, session, offline, simpleOne, backpack) as T
        }
    }
}
