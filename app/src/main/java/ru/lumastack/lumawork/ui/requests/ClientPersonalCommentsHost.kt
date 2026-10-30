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
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import ru.lumastack.lumawork.domain.requests.clientCommentTerminalID
import ru.lumastack.lumawork.domain.requests.clientPersonalCommentMetadata
import ru.lumastack.lumawork.domain.requests.clientPersonalCommentRows
import ru.lumastack.lumawork.domain.requests.normalizedClientCommentTIN
import ru.lumastack.lumawork.domain.requests.normalizedClientCommentScope
import ru.lumastack.lumawork.domain.requests.supportsClientPersonalComments
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.ui.theme.AppFormDialog
import ru.lumastack.lumawork.ui.theme.AppTextField

/** Keep composed at the account shell, independently of the selected destination. */
@Composable
fun rememberClientPersonalCommentsRepository(
    session: AppSession,
    baseOrigin: String,
    json: Json,
    offline: Boolean,
): ClientPersonalCommentsRepository {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val sessionScope = remember(session.user.id, session.token, baseOrigin, json) {
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    }
    val repository = remember(sessionScope) {
        ClientPersonalCommentsRepository(session, OkHttpClientPersonalCommentsApi(baseOrigin, json), FileClientPersonalCommentsCache(context, json), sessionScope = sessionScope)
    }
    DisposableEffect(sessionScope) { onDispose { sessionScope.cancel() } }
    LaunchedEffect(repository, offline) { repository.initialize { offline } }
    return repository
}

@Composable
fun rememberClientPersonalCommentsController(
    repository: ClientPersonalCommentsRepository,
    offline: Boolean,
    records: List<SimpleOneRequest>,
): ClientPersonalCommentsController {
    val scope = rememberCoroutineScope()
    val controller = remember(repository) { ClientPersonalCommentsController(repository, scope) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    SideEffect { controller.offline = offline; controller.records = records }
    LaunchedEffect(controller) {
        controller.repository.hydrate()
        controller.repository.snapshot.collect { controller.updateComments(it.comments) }
    }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.flushDraft() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.flushDraft() }
    }
    return controller
}

class ClientPersonalCommentsController internal constructor(
    internal val repository: ClientPersonalCommentsRepository,
    private val scope: CoroutineScope,
) {
    var offline by mutableStateOf(false)
        internal set
    internal var records: List<SimpleOneRequest> = emptyList()
        set(value) {
            if (field === value) return
            field = value
            if (!isLoadingOptions) draft?.let { reloadOptions(it.tin) }
        }
    private var matching by mutableStateOf(ClientPersonalCommentMatchingIndex(emptyList()))
    var comments by mutableStateOf<List<ClientPersonalComment>>(emptyList())
        private set
    var draft by mutableStateOf<ClientPersonalCommentDraft?>(null)
        private set
    var isOpening by mutableStateOf(false)
        private set
    private var editorJob: Job? = null
    private var editorRevision = 0L
    private var draftWriteJob: Job? = null
    var isLoadingOptions by mutableStateOf(false)
        private set
    private var options by mutableStateOf(CommentEditorOptions())
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
        if (isSaving || offline || (request != null && !canEdit(request))) return
        editorJob?.cancel()
        updateComments(repository.snapshot.value.comments)
        val existing = request?.let(::commentFor)
        currentTarget = request?.clientCommentAddress()?.takeIf(String::isNotBlank)?.let(::ClientPersonalCommentTarget)
        currentTerminalID = request?.clientCommentTerminalID().orEmpty()
        originalComment = existing
        val cached = repository.snapshot.value.draft
        val tin = if (request == null) cached?.tin.orEmpty()
            else existing?.displayTIN?.takeIf(String::isNotBlank) ?: request.clientCommentTIN()
        options = CommentEditorOptions(normalizedClientCommentTIN(tin), listOfNotNull(currentTarget),
            listOfNotNull(currentTerminalID.takeIf(String::isNotBlank)?.let { ClientPersonalCommentTerminalOption(it, currentTarget?.address.orEmpty()) }))
        val initial = if (request == null) cached ?: ClientPersonalCommentDraft()
            else clientCommentEditorDraft(tin, existing, currentTarget, currentTerminalID, options.terminals, cached)
        // Publish the editable form before touching disk or traversing the archive.
        draft = initial
        saveError = null
        val revision = ++editorRevision
        isLoadingOptions = true
        editorJob = scope.launch {
            try {
                repository.hydrate()
                if (revision != editorRevision || offline) return@launch
                updateComments(repository.snapshot.value.comments)
                val restored = if (request == null) repository.snapshot.value.draft ?: initial else {
                    val comment = commentFor(request)
                    originalComment = comment
                    clientCommentEditorDraft(tin, comment, currentTarget, currentTerminalID, options.terminals, repository.snapshot.value.draft)
                }
                val hadEdits = draft !== initial
                draft = draft?.let { current ->
                    if (current === initial) restored else current.copy(
                        serverID = restored.serverID,
                        sourceCommentID = restored.sourceCommentID,
                        tin = if (current.tin == initial.tin) restored.tin else current.tin,
                        targets = if (current.targets == initial.targets) restored.targets else current.targets,
                        terminalIDs = if (current.terminalIDs == initial.terminalIDs) restored.terminalIDs else current.terminalIDs,
                        contactPerson = if (current.contactPerson == initial.contactPerson) restored.contactPerson else current.contactPerson,
                        phone = if (current.phone == initial.phone) restored.phone else current.phone,
                        email = if (current.email == initial.email) restored.email else current.email,
                        extraInfo = if (current.extraInfo == initial.extraInfo) restored.extraInfo else current.extraInfo,
                    )
                }
                val openingDraft = draft ?: return@launch
                if (hadEdits) scheduleDraftPersistence(openingDraft)
                val source = records
                val prepared = prepareOptions(openingDraft.tin, source, currentTarget, currentTerminalID)
                if (revision != editorRevision || draft?.normalizedTIN != openingDraft.normalizedTIN) return@launch
                if (source !== records) { reloadOptions(openingDraft.tin); return@launch }
                options = prepared
                // Preserve the original address-wide terminal selection, but never overwrite user edits.
                if (request != null && originalComment == null && cached == null) {
                    val value = draft ?: return@launch
                    if (value.targets == initial.targets && value.terminalIDs == initial.terminalIDs) {
                        val populated = clientCommentEditorDraft(value.tin, null, currentTarget, currentTerminalID, prepared.terminals, null)
                        draft = value.copy(terminalIDs = populated.terminalIDs)
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (revision == editorRevision) saveError = "Не удалось подготовить адреса и терминалы. Поля можно заполнить вручную." }
            finally { if (revision == editorRevision) isLoadingOptions = false }
        }
    }

    // Compose reads prepared options only; neither rendering nor typing scans the archive.
    internal fun targetOptions(tin: String): List<ClientPersonalCommentTarget> =
        ((if (options.tin == normalizedClientCommentTIN(tin)) options.targets else listOfNotNull(currentTarget)) +
            draft?.targets.orEmpty()).distinctBy { it.key }

    internal fun terminalOptions(tin: String): List<ClientPersonalCommentTerminalOption> =
        if (options.tin == normalizedClientCommentTIN(tin)) options.terminals else emptyList()

    private suspend fun prepareOptions(tin: String, source: List<SimpleOneRequest>,
        target: ClientPersonalCommentTarget?, terminal: String): CommentEditorOptions = withContext(Dispatchers.Default) {
        val index = repository.optionsIndex(source)
        CommentEditorOptions(normalizedClientCommentTIN(tin), index.targets(tin, target), index.terminals(tin, terminal, target?.address.orEmpty()))
    }

    private fun reloadOptions(tin: String) {
        val revision = ++editorRevision
        editorJob?.cancel()
        isLoadingOptions = true
        editorJob = scope.launch {
            try {
                val source = records
                val prepared = prepareOptions(tin, source, currentTarget, currentTerminalID)
                if (revision == editorRevision && draft?.normalizedTIN == normalizedClientCommentTIN(tin)) {
                    if (source !== records) reloadOptions(tin) else options = prepared
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (revision == editorRevision) saveError = "Не удалось подготовить адреса и терминалы." }
            finally { if (revision == editorRevision) isLoadingOptions = false }
        }
    }

    fun updateDraft(value: ClientPersonalCommentDraft) {
        if (isSaving) return
        val changedTIN = draft?.normalizedTIN != value.normalizedTIN
        draft = value
        if (changedTIN) reloadOptions(value.tin)
        saveError = null
        scheduleDraftPersistence(value)
    }

    private fun scheduleDraftPersistence(value: ClientPersonalCommentDraft) {
        draftWriteJob?.cancel()
        draftWriteJob = scope.launch {
            delay(300)
            persistDraft(value)
        }
    }

    private suspend fun persistDraft(value: ClientPersonalCommentDraft) {
        try { repository.persistDraft(value) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { saveError = "Не удалось сохранить черновик на устройстве." }
    }

    internal fun flushDraft() {
        draftWriteJob?.cancel()
        val value = draft ?: return
        if (isSaving) return
        repository.persistDraftOnExit(value, scope)
    }

    fun dismissEditor() {
        if (!isSaving) {
            flushDraft()
            editorRevision++
            editorJob?.cancel()
            isOpening = false
            isLoadingOptions = false
            draft = null
            saveError = null
        }
    }

    fun save() {
        val value = draft ?: return
        if (isSaving || offline || !value.canSave) return
        isSaving = true
        draftWriteJob?.cancel()
        editorJob?.cancel()
        editorRevision++
        isLoadingOptions = false
        saveError = null
        scope.launch {
            try {
                repository.save(value, offline = { offline })
                updateComments(repository.snapshot.value.comments)
                draft = null
            } catch (error: CancellationException) {
                // The session owns the mutation and its persisted draft after navigation.
                // Do not let disposal flush an old editor over a completed session save.
                draft = null
                throw error
            }
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
        if (controller.isLoadingOptions) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Подготовка адресов и терминалов…", style = MaterialTheme.typography.bodySmall)
            }
        }
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
