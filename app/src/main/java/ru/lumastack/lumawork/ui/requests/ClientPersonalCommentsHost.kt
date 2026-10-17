package ru.lumastack.lumawork.ui.requests

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.data.requests.ClientPersonalCommentsRepository
import ru.lumastack.lumawork.data.requests.FileClientPersonalCommentsCache
import ru.lumastack.lumawork.data.requests.OkHttpClientPersonalCommentsApi
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.requests.ClientPersonalComment
import ru.lumastack.lumawork.domain.requests.ClientPersonalCommentDraft
import ru.lumastack.lumawork.domain.requests.ClientPersonalCommentMatchingIndex
import ru.lumastack.lumawork.domain.requests.ClientPersonalCommentTarget
import ru.lumastack.lumawork.domain.requests.ClientPersonalCommentTerminalOption
import ru.lumastack.lumawork.domain.requests.canonicalClientCommentPhone
import ru.lumastack.lumawork.domain.requests.clientCommentAddress
import ru.lumastack.lumawork.domain.requests.clientCommentEditorDraft
import ru.lumastack.lumawork.domain.requests.clientCommentTIN
import ru.lumastack.lumawork.domain.requests.clientCommentTargets
import ru.lumastack.lumawork.domain.requests.clientCommentTerminalID
import ru.lumastack.lumawork.domain.requests.clientCommentTerminals
import ru.lumastack.lumawork.domain.requests.clientPersonalCommentMetadata
import ru.lumastack.lumawork.domain.requests.clientPersonalCommentRows
import ru.lumastack.lumawork.domain.requests.normalizedClientCommentScope
import ru.lumastack.lumawork.domain.requests.supportsClientPersonalComments
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.ui.theme.AppFormDialog
import ru.lumastack.lumawork.ui.theme.AppTextField

/** Create only after the Requests initial gate; changing account, token or origin replaces all transient state. */
@Composable
fun rememberClientPersonalCommentsController(
    session: AppSession,
    offline: Boolean,
    records: List<SimpleOneRequest>,
    baseOrigin: String,
    json: Json,
): ClientPersonalCommentsController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(session.user.id, session.token, baseOrigin, json) {
        ClientPersonalCommentsController(ClientPersonalCommentsRepository(session,
            OkHttpClientPersonalCommentsApi(baseOrigin, json), FileClientPersonalCommentsCache(context, json)), scope)
    }
    SideEffect { controller.offline = offline; controller.records = records }
    LaunchedEffect(controller) {
        controller.repository.hydrate()
        controller.repository.snapshot.collect { controller.updateComments(it.comments) }
    }
    LaunchedEffect(controller, offline) { controller.repository.refresh(offline = { controller.offline }) }
    return controller
}

class ClientPersonalCommentsController internal constructor(
    internal val repository: ClientPersonalCommentsRepository,
    private val scope: CoroutineScope,
) {
    var offline by mutableStateOf(false)
        internal set
    internal var records: List<SimpleOneRequest> = emptyList()
    private var matching by mutableStateOf(ClientPersonalCommentMatchingIndex(emptyList()))
    var comments by mutableStateOf<List<ClientPersonalComment>>(emptyList())
        private set
    var draft by mutableStateOf<ClientPersonalCommentDraft?>(null)
        private set
    var isSaving by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    internal var currentTarget: ClientPersonalCommentTarget? = null
    internal var currentTerminalID = ""
    internal var originalComment: ClientPersonalComment? = null

    internal fun updateComments(value: List<ClientPersonalComment>) {
        comments = value
        matching = ClientPersonalCommentMatchingIndex(value)
    }

    fun commentFor(request: SimpleOneRequest): ClientPersonalComment? = if (!request.supportsClientPersonalComments()) null
        else matching.comment(request.clientCommentTIN(), request.clientCommentAddress(), request.clientCommentTerminalID())

    fun canEdit(request: SimpleOneRequest): Boolean = !offline && request.supportsClientPersonalComments() &&
        (request.clientCommentTIN().isNotEmpty() || commentFor(request) != null)

    /** null request is the top menu's blank «Комментарий» action. */
    fun openEditor(request: SimpleOneRequest? = null) {
        if (offline || (request != null && !canEdit(request))) return
        scope.launch {
            repository.hydrate()
            if (offline) return@launch
            val existing = request?.let(::commentFor)
            currentTarget = request?.clientCommentAddress()?.takeIf(String::isNotBlank)?.let(::ClientPersonalCommentTarget)
            currentTerminalID = request?.clientCommentTerminalID().orEmpty()
            originalComment = existing
            val tin = existing?.displayTIN?.takeIf(String::isNotBlank) ?: request?.clientCommentTIN().orEmpty()
            val terminals = terminalOptions(tin)
            draft = if (request == null) repository.snapshot.value.draft ?: ClientPersonalCommentDraft()
                else clientCommentEditorDraft(tin, existing, currentTarget, currentTerminalID, terminals, repository.snapshot.value.draft)
            saveError = null
        }
    }

    internal fun targetOptions(tin: String): List<ClientPersonalCommentTarget> =
        (clientCommentTargets(records, tin, currentTarget) + draft?.targets.orEmpty()).distinctBy { it.key }

    internal fun terminalOptions(tin: String): List<ClientPersonalCommentTerminalOption> =
        clientCommentTerminals(records, tin, currentTerminalID, currentTarget?.address.orEmpty())

    fun updateDraft(value: ClientPersonalCommentDraft) {
        if (isSaving) return
        draft = value
        saveError = null
        // Start immediately and finish queued draft writes across configuration/account disposal.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try { repository.persistDraft(value) }
                catch (_: Exception) { saveError = "Не удалось сохранить черновик на устройстве." }
            }
        }
    }

    fun dismissEditor() { if (!isSaving) { draft = null; saveError = null } }

    fun save() {
        val value = draft ?: return
        if (isSaving || offline || !value.canSave) return
        isSaving = true
        saveError = null
        scope.launch {
            try {
                repository.save(value, offline = { offline })
                updateComments(repository.snapshot.value.comments)
                draft = null
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { saveError = error.message ?: "Не удалось сохранить комментарий." }
            finally { isSaving = false }
        }
    }
}

/** Render once in the Requests root Box. Per-card content uses [ClientPersonalCommentBlock]. */
@Composable
fun ClientPersonalCommentsHost(controller: ClientPersonalCommentsController, modifier: Modifier = Modifier) {
    val snackbar = remember(controller) { SnackbarHostState() }
    LaunchedEffect(controller) {
        controller.repository.error.collect { message ->
            if (message != null) { snackbar.showSnackbar(message); controller.repository.clearError() }
        }
    }
    SnackbarHost(snackbar, modifier)
    val draft = controller.draft ?: return
    ClientPersonalCommentEditor(controller, draft)
}

@Composable
private fun ClientPersonalCommentEditor(controller: ClientPersonalCommentsController, draft: ClientPersonalCommentDraft) {
    var newTerminal by remember(controller) { mutableStateOf("") }
    val targetOptions = controller.targetOptions(draft.tin)
    val terminalOptions = controller.terminalOptions(draft.tin)
    val enabled = !controller.isSaving && !controller.offline
    AppFormDialog(title = "Личный комментарий", onDismiss = controller::dismissEditor, confirmLabel = "Сохранить",
        onConfirm = controller::save, isBusy = controller.isSaving, confirmEnabled = draft.canSave && !controller.offline,
        errorMessage = if (controller.offline) "Сохранение комментария недоступно в офлайн-режиме." else controller.saveError) {
        item {
            AppTextField(draft.tin, { controller.updateDraft(draft.copy(tin = it.filter(Char::isDigit))) }, "ИНН",
                enabled = enabled && draft.serverID == null, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        item { AppTextField(draft.contactPerson, { controller.updateDraft(draft.copy(contactPerson = it)) }, "Контактное лицо", enabled = enabled, singleLine = true) }
        item { AppTextField(draft.phone, { controller.updateDraft(draft.copy(phone = canonicalClientCommentPhone(it))) }, "Телефон", enabled = enabled,
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)) }
        item { AppTextField(draft.email, { controller.updateDraft(draft.copy(email = it)) }, "Почта", enabled = enabled,
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false)) }
        item { AppTextField(draft.extraInfo, { controller.updateDraft(draft.copy(extraInfo = it)) }, "Дополнительно (Wi-Fi, ориентир...)", enabled = enabled, minLines = 2, maxLines = 4) }
        if (targetOptions.isNotEmpty()) {
            item { Text("Привязка к адресу", style = MaterialTheme.typography.titleSmall) }
            item { CommentCheckRow("Все адреса", draft.targets.isEmpty(), enabled) { all ->
                controller.updateDraft(if (all) draft.copy(targets = emptyList(), sourceCommentID = null)
                    else draft.copy(targets = listOfNotNull(controller.currentTarget ?: targetOptions.firstOrNull())))
            } }
            items(targetOptions, key = { "address:${it.key}" }) { target ->
                CommentCheckRow(target.displayText, target.key in draft.targetKeys, enabled) { selected ->
                    val targets = if (selected) (draft.targets + target).distinctBy { it.key }
                        else if (draft.targets.size > 1) draft.targets.filterNot { it.key == target.key } else draft.targets
                    controller.updateDraft(draft.copy(targets = targets))
                }
            }
            if (controller.originalComment?.targets?.isEmpty() == true && draft.serverID != null && controller.currentTarget != null) {
                item { TextButton(enabled = enabled, onClick = {
                    val target = controller.currentTarget ?: return@TextButton
                    controller.updateDraft(draft.copy(sourceCommentID = draft.serverID, serverID = null, targets = listOf(target),
                        terminalIDs = terminalOptions.filter { normalizedClientCommentScope(it.address) == target.key }.map { it.terminalID })
                        .addingTerminal(controller.currentTerminalID))
                }) { Text("Отдельный комментарий для этого адреса") } }
            }
        }
        item { Text("ID терминалов", style = MaterialTheme.typography.titleSmall) }
        items(draft.terminalIDs, key = { "terminal:$it" }) { terminal ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(terminal, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(enabled = enabled, onClick = { controller.updateDraft(draft.copy(terminalIDs = draft.terminalIDs - terminal)) }) { Text("Убрать") }
            }
        }
        item {
            AppTextField(newTerminal, { newTerminal = it }, "ID терминала", enabled = enabled, singleLine = true)
            TextButton(enabled = enabled && newTerminal.isNotBlank(), onClick = { controller.updateDraft(draft.addingTerminal(newTerminal)); newTerminal = "" }) { Text("Добавить ID терминала") }
        }
        val suggestions = terminalOptions.filter { option ->
            draft.terminalIDs.none { normalizedClientCommentScope(it) == option.key } &&
                (draft.targets.isEmpty() || normalizedClientCommentScope(option.address) in draft.targetKeys)
        }
        if (suggestions.isNotEmpty()) {
            item { Text("ID из заявок", style = MaterialTheme.typography.labelLarge) }
            items(suggestions, key = { "suggestion:${it.key}" }) { option ->
                TextButton(enabled = enabled, onClick = { controller.updateDraft(draft.addingTerminal(option.terminalID)) }) { Text(option.displayText) }
            }
        }
    }
}

@Composable
private fun CommentCheckRow(title: String, selected: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!selected) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, onCheckedChange = onChange, enabled = enabled)
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun ClientPersonalCommentBlock(comment: ClientPersonalComment, modifier: Modifier = Modifier, onEdit: (() -> Unit)? = null) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Личный комментарий", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (onEdit != null) TextButton(onClick = onEdit) { Text("Изменить") }
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    clientPersonalCommentRows(comment).forEach { (title, value) ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            val metadata = clientPersonalCommentMetadata(comment)
            if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
