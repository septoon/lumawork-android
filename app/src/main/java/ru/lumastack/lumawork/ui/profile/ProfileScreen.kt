package ru.lumastack.lumawork.ui.profile

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.BuildConfig
import ru.lumastack.lumawork.data.profile.WorkDocumentsRepository
import ru.lumastack.lumawork.data.profile.OkHttpWorkDocumentsApi
import ru.lumastack.lumawork.data.profile.FileWorkDocumentsCache
import ru.lumastack.lumawork.data.simpleone.SimpleOneRepository
import ru.lumastack.lumawork.domain.simpleone.ClosedSimpleOneArchive
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import ru.lumastack.lumawork.data.auth.AuthRepository
import ru.lumastack.lumawork.data.auto.AutoRepository
import ru.lumastack.lumawork.data.auto.VehicleImageRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.auto.VehicleEditorFields
import ru.lumastack.lumawork.ui.theme.AppTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileRoute(
    session: AppSession,
    offline: Boolean,
    auth: AuthRepository,
    auto: AutoRepository,
    images: VehicleImageRepository,
    simpleOne: SimpleOneRepository,
    liveArchive: ClosedSimpleOneArchive,
    isArchiveLoading: Boolean,
    onDismiss: () -> Unit,
    startInEditor: Boolean = false,
) {
    val vm: ProfileViewModel = viewModel(key = "profile-${session.user.id}", factory = ProfileViewModel.factory(auth, auto))
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("profile-preferences", android.content.Context.MODE_PRIVATE) }
    var showVehicle by rememberSaveable { mutableStateOf(preferences.getBoolean("show-vehicle", true)) }
    var editing by rememberSaveable(session.user.id) { mutableStateOf(startInEditor) }
    val json = remember { Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true } }
    val documents = remember(context.applicationContext) {
        WorkDocumentsRepository(OkHttpWorkDocumentsApi(BuildConfig.API_ORIGIN, json), FileWorkDocumentsCache(context.applicationContext, json))
    }
    var documentsOpen by rememberSaveable(session.user.id) { mutableStateOf(false) }
    var documentCount by remember(session.user.id) { mutableStateOf(0) }
    var cachedArchive by remember(session.user.id) { mutableStateOf<ClosedSimpleOneArchive?>(null) }
    var readingArchive by remember(session.user.id) { mutableStateOf(true) }
    val archive = liveArchive.takeIf { it.updatedAtEpochMillis != null || it.isComplete || it.requests.isNotEmpty() } ?: cachedArchive
    LaunchedEffect(simpleOne, session.user.id) {
        try { cachedArchive = simpleOne.readClosedArchive(session.user.id) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { cachedArchive = null }
        finally { readingArchive = false }
    }
    LaunchedEffect(documents, session.user.id, session.token, offline) {
        documentCount = documents.read(session.user.id).size
        if (!offline) try { documentCount = documents.refresh(session).size }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* The document screen exposes retry and the cached list. */ }
    }
    LaunchedEffect(documents, session.user.id) {
        documents.changes.collect { (owner, items) -> if (owner == session.user.id) documentCount = items.size }
    }
    val close = {
        if (!state.busy) {
            if (documentsOpen) documentsOpen = false
            else if (editing && !startInEditor) { editing = false; vm.close(); vm.open(session, offline) }
            else { vm.close(); onDismiss() }
        }
    }
    LaunchedEffect(session, offline) { vm.open(session, offline) }
    DisposableEffect(vm, context) {
        onDispose { if ((context as? android.app.Activity)?.isChangingConfigurations != true) vm.close() }
    }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            preferences.edit().putBoolean("show-vehicle", showVehicle).apply()
            vm.close()
            onDismiss()
        }
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy)) {
        if (documentsOpen) WorkDocumentsScreen(session, offline, editing, onDismiss = { documentsOpen = false }, repository = documents)
        else Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(title = { Text("Профиль") }, navigationIcon = {
                    IconButton(onClick = close, enabled = !state.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Закрыть профиль") }
                }, actions = {
                    if (state.busy) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    else if (editing) TextButton(onClick = vm::save, enabled = !offline && profileValidationError(state.draft) == null) { Text("Сохранить") }
                })
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                if (!editing) ProfileOverview(session.user, state, images, showVehicle,
                    completedRecords = archive?.requests.orEmpty(),
                    statisticsLoading = (readingArchive || isArchiveLoading) && archive?.requests.isNullOrEmpty(),
                    hasArchive = archive != null, documentCount = documentCount,
                    onOpenDocuments = { documentsOpen = true })
                else LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item(key = "avatar") {
                        ProfileAvatarEditor(session.user, images, enabled = !offline && !state.busy, busy = state.busy,
                            onUpload = vm::uploadAvatar, onDelete = vm::deleteAvatar, onError = vm::showError)
                    }
                    if (offline) item(key = "offline") { Text("Подключитесь к интернету, чтобы сохранить профиль.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    state.error?.let { message -> item(key = "error") { Text(message, color = MaterialTheme.colorScheme.error) } }
                    item(key = "about-title") { ProfileHeading("О вас", "Все поля необязательные") }
                    item(key = "about") {
                        ProfileCard {
                            ProfileField("Фамилия", state.draft.lastName, state.busy) { vm.edit(state.draft.copy(lastName = it)) }
                            ProfileField("Имя", state.draft.firstName, state.busy) { vm.edit(state.draft.copy(firstName = it)) }
                            ProfileField("Отчество", state.draft.middleName, state.busy) { vm.edit(state.draft.copy(middleName = it)) }
                            ProfileField("Должность", state.draft.jobTitle, state.busy) { vm.edit(state.draft.copy(jobTitle = it)) }
                            ProfileField("Город", state.draft.city, state.busy) { vm.edit(state.draft.copy(city = it)) }
                        }
                    }
                    item(key = "documents-title") { ProfileHeading("Документы для работы", "Справки, договоры, положения и другие файлы") }
                    item(key = "documents") {
                        ProfileDocumentsLink(documentCount, canManage = true, onClick = { documentsOpen = true })
                    }
                    item(key = "contacts-title") { ProfileHeading("Контакты", "Для связи и автозаполнения") }
                    item(key = "contacts") {
                        ProfileCard {
                            ProfileField("Телефон", state.draft.personalPhone, state.busy, KeyboardType.Phone) { vm.edit(state.draft.copy(personalPhone = it)) }
                            ProfileField("Почта", state.draft.workEmail, state.busy, KeyboardType.Email) { vm.edit(state.draft.copy(workEmail = it)) }
                            profileValidationError(state.draft)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    item(key = "vehicle-title") { ProfileHeading("Основной автомобиль", "Синхронизируется с экраном «Авто»") }
                    item(key = "vehicle") {
                        ProfileCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Показывать в профиле")
                                    Text("Скрытие не удаляет сохраненные данные", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(showVehicle, { showVehicle = it }, enabled = !state.busy)
                            }
                            state.vehicleError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (showVehicle && state.vehicleReady) VehicleEditorFields(state.vehicle, vm::editVehicle, showPrimaryToggle = false, enabled = !state.busy)
                            else if (showVehicle && state.vehicleError == null) CircularProgressIndicator(Modifier.size(24.dp))
                            if (showVehicle && state.vehicleError != null) TextButton(onClick = { vm.retryVehicles() }, enabled = !state.busy && !offline) { Text("Повторить") }
                        }
                    }

                }
            }
        }
    }
}

@Composable
private fun ProfileCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ProfileHeading(title: String, caption: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ProfileField(label: String, value: String?, busy: Boolean, keyboard: KeyboardType = KeyboardType.Text, change: (String) -> Unit) {
    AppTextField(value.orEmpty(), change, label, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
