package ru.lumastack.lumawork.ui.requests

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import ru.lumastack.lumawork.domain.requests.clientCommentPhoneForCopy
import ru.lumastack.lumawork.domain.requests.clientCommentTargets
import ru.lumastack.lumawork.domain.requests.clientCommentTerminalID
import ru.lumastack.lumawork.domain.requests.clientCommentTerminals
import ru.lumastack.lumawork.domain.requests.clientPersonalCommentMetadata
import ru.lumastack.lumawork.domain.requests.clientPersonalCommentRows
import ru.lumastack.lumawork.domain.requests.normalizedClientCommentTIN
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
    var isOpening by mutableStateOf(false)
        private set
    private var editorJob: Job? = null
    private var editorRevision = 0L
    private var options by mutableStateOf(CommentEditorOptions())
    private var cachedOptions: CommentEditorOptions? = null
    private var optionRecords: List<SimpleOneRequest>? = null
    private var optionTarget: ClientPersonalCommentTarget? = null
    private var optionTerminal = ""
    var isSaving by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    internal var currentTarget: ClientPersonalCommentTarget? = null
    internal var currentTerminalID = ""
    internal var originalComment: ClientPersonalComment? = null

    internal fun updateComments(value: List<ClientPersonalComment>) {
        if (comments === value) return
        comments = value
        matching = ClientPersonalCommentMatchingIndex(value)
    }

    fun commentFor(request: SimpleOneRequest): ClientPersonalComment? = if (!request.supportsClientPersonalComments()) null
        else matching.comment(request.clientCommentTIN(), request.clientCommentAddress(), request.clientCommentTerminalID())

    fun canEdit(request: SimpleOneRequest): Boolean = !offline && request.supportsClientPersonalComments()

    /** null request is the top menu's blank «Комментарий» action. */
    fun openEditor(request: SimpleOneRequest? = null) {
        if (isOpening || isSaving || offline || (request != null && !canEdit(request))) return
        val revision = ++editorRevision
        editorJob?.cancel()
        isOpening = true
        editorJob = scope.launch {
            try {
                repository.hydrate()
                if (offline) return@launch
                val existing = request?.let(::commentFor)
                val target = request?.clientCommentAddress()?.takeIf(String::isNotBlank)?.let(::ClientPersonalCommentTarget)
                val terminal = request?.clientCommentTerminalID().orEmpty()
                val cached = repository.snapshot.value.draft
                val tin = if (request == null) cached?.tin.orEmpty()
                    else existing?.displayTIN?.takeIf(String::isNotBlank) ?: request.clientCommentTIN()
                val prepared = prepareOptions(tin, records, target, terminal)
                if (offline || revision != editorRevision) return@launch
                currentTarget = target
                currentTerminalID = terminal
                originalComment = existing
                options = prepared
                draft = if (request == null) cached ?: ClientPersonalCommentDraft()
                    else clientCommentEditorDraft(tin, existing, target, terminal, prepared.terminals, cached)
                saveError = null
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { saveError = "Не удалось открыть личный комментарий. Попробуйте ещё раз." }
            finally { if (revision == editorRevision) isOpening = false }
        }
    }

    // Compose reads prepared options only; neither rendering nor typing scans the archive.
    internal fun targetOptions(tin: String): List<ClientPersonalCommentTarget> =
        ((if (options.tin == normalizedClientCommentTIN(tin)) options.targets else listOfNotNull(currentTarget)) +
            draft?.targets.orEmpty()).distinctBy { it.key }

    internal fun terminalOptions(tin: String): List<ClientPersonalCommentTerminalOption> =
        if (options.tin == normalizedClientCommentTIN(tin)) options.terminals else emptyList()

    private suspend fun prepareOptions(tin: String, source: List<SimpleOneRequest>,
        target: ClientPersonalCommentTarget?, terminal: String): CommentEditorOptions {
        val normalized = normalizedClientCommentTIN(tin)
        cachedOptions?.let { if (optionRecords === source && it.tin == normalized && optionTarget == target && optionTerminal == terminal) return it }
        val prepared = withContext(Dispatchers.Default) {
            CommentEditorOptions(normalized, clientCommentTargets(source, normalized, target),
                clientCommentTerminals(source, normalized, terminal, target?.address.orEmpty()))
        }
        cachedOptions = prepared
        optionRecords = source
        optionTarget = target
        optionTerminal = terminal
        return prepared
    }

    private fun reloadOptions(tin: String) {
        val revision = ++editorRevision
        editorJob?.cancel()
        editorJob = scope.launch {
            val prepared = prepareOptions(tin, records, currentTarget, currentTerminalID)
            if (revision == editorRevision && draft?.normalizedTIN == normalizedClientCommentTIN(tin)) options = prepared
        }
    }

    fun updateDraft(value: ClientPersonalCommentDraft) {
        if (isSaving) return
        val changedTIN = draft?.normalizedTIN != value.normalizedTIN
        draft = value
        if (changedTIN) reloadOptions(value.tin)
        saveError = null
        // Start immediately and finish queued draft writes across configuration/account disposal.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try { repository.persistDraft(value) }
                catch (_: Exception) { saveError = "Не удалось сохранить черновик на устройстве." }
            }
        }
    }

    fun dismissEditor() {
        if (!isSaving) {
            editorRevision++
            editorJob?.cancel()
            isOpening = false
            draft = null
            saveError = null
        }
    }

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

private data class CommentEditorOptions(
    val tin: String = "",
    val targets: List<ClientPersonalCommentTarget> = emptyList(),
    val terminals: List<ClientPersonalCommentTerminalOption> = emptyList(),
)

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
    if (controller.isOpening) {
        AppFormDialog(title = "Личный комментарий", onDismiss = controller::dismissEditor,
            confirmLabel = "Сохранить", onConfirm = {}, confirmEnabled = false) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("Подготовка адресов и терминалов…")
                }
            }
        }
        return
    }
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
    val context = LocalContext.current
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .22f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Личный комментарий", style = MaterialTheme.typography.bodyMedium, fontSize = 14.sp, lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (onEdit != null) IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = .18f)) {
                        Icon(Icons.Default.Edit, "Редактировать комментарий", Modifier.padding(7.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    clientPersonalCommentRows(comment).forEach { (title, value) ->
                        val copyValue = when (title) {
                            "Контактное лицо" -> comment.contactPerson
                            "Телефон" -> clientCommentPhoneForCopy(comment.phone)
                            "Почта" -> comment.email
                            else -> null
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            Text(title, Modifier.weight(.4f), style = MaterialTheme.typography.bodySmall, fontSize = 11.sp, lineHeight = 16.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, Modifier.weight(.6f).then(if (!copyValue.isNullOrBlank()) Modifier.clickable(onClickLabel = "Скопировать $title") {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText(title, copyValue))
                                if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, when (title) {
                                    "Контактное лицо" -> "Имя скопировано"
                                    "Телефон" -> "Телефон скопирован"
                                    else -> "Почта скопирована"
                                }, Toast.LENGTH_SHORT).show()
                            } else Modifier), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp), fontWeight = FontWeight.Medium,
                                color = if (title == "Почта") androidx.compose.ui.graphics.Color(0xFF009DFF) else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            val metadata = clientPersonalCommentMetadata(comment)
            if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
