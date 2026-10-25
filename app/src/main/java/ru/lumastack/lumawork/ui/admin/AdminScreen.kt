package ru.lumastack.lumawork.ui.admin

import ru.lumastack.lumawork.ui.theme.AppContentHorizontalPadding
import ru.lumastack.lumawork.ui.theme.AppRefreshContainer
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import ru.lumastack.lumawork.ui.theme.AppTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.admin.AdminGsmTemplate
import ru.lumastack.lumawork.data.admin.AdminServerImage
import ru.lumastack.lumawork.data.admin.AdminFailure
import ru.lumastack.lumawork.data.admin.AdminRepository
import ru.lumastack.lumawork.data.admin.AdminSnapshot
import ru.lumastack.lumawork.data.admin.AdminUser
import ru.lumastack.lumawork.data.feedback.FeedbackMessage
import ru.lumastack.lumawork.data.feedback.FeedbackPriority
import ru.lumastack.lumawork.data.feedback.FeedbackRepository
import ru.lumastack.lumawork.data.feedback.FeedbackSnapshot
import ru.lumastack.lumawork.data.feedback.FeedbackStatus
import ru.lumastack.lumawork.domain.model.AdminPermission
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.shell.AppHeaderSegments
import ru.lumastack.lumawork.ui.theme.appScreenBackground
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton
import ru.lumastack.lumawork.ui.theme.AppPanel
import ru.lumastack.lumawork.ui.theme.AppSurfaces

private enum class AdminSection(val title: String) { Overview("Сводка"), Users("Пользователи"), Images("Изображения"), GsmTemplate("Шаблон ГСМ"), Feedback("Обратная связь") }

data class AdminUiState(
    val snapshot: AdminSnapshot = AdminSnapshot(),
    val feedback: FeedbackSnapshot = FeedbackSnapshot(),
    val section: String = AdminSection.Overview.name,
    val selectedUser: AdminUser? = null,
    val selectedFeedback: FeedbackMessage? = null,
    val selectedImage: AdminServerImage? = null,
    val deleteConfirmation: String = "",
    val adminNote: String = "",
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

class AdminViewModel(
    private val repository: AdminRepository,
    private val feedbackRepository: FeedbackRepository,
    private val session: AppSession,
    private val offline: Boolean,
) : ViewModel() {
    private val mutable = MutableStateFlow(AdminUiState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            repository.read(session.user.id)?.let { cached -> mutable.update { it.copy(snapshot = cached) } }
            feedbackRepository.read(session.user.id, admin = true)?.let { cached -> mutable.update { it.copy(feedback = cached) } }
            if (!offline) refresh(false)
        }
    }

    fun section(value: String) = mutable.update { it.copy(section = value) }
    fun refresh() = viewModelScope.launch { refresh(true) }
    fun openUser(user: AdminUser) = mutable.update { it.copy(selectedUser = user, deleteConfirmation = "", error = null) }
    fun closeUser() = mutable.update { it.copy(selectedUser = null, deleteConfirmation = "") }
    fun deleteConfirmation(value: String) = mutable.update { it.copy(deleteConfirmation = value) }
    fun openFeedback(message: FeedbackMessage) = mutable.update { it.copy(selectedFeedback = message, adminNote = message.adminNote.orEmpty()) }
    fun closeFeedback() = mutable.update { it.copy(selectedFeedback = null, adminNote = "") }
    fun adminNote(value: String) = mutable.update { it.copy(adminNote = value) }
    fun openImage(image: AdminServerImage) = mutable.update { it.copy(selectedImage = image) }
    fun closeImage() = mutable.update { it.copy(selectedImage = null) }

    fun uploadImage(bytes: ByteArray) {
        val image = state.value.selectedImage ?: return
        viewModelScope.launch { mutate {
            val snapshot = repository.uploadImage(session.user.id, session.token, image, bytes)
            mutable.update { it.copy(snapshot = snapshot, selectedImage = snapshot.images.firstOrNull { value -> value.id == image.id && value.kind == image.kind }) }
        } }
    }

    fun deleteImage() {
        val image = state.value.selectedImage ?: return
        viewModelScope.launch { mutate {
            val snapshot = repository.deleteImage(session.user.id, session.token, image)
            mutable.update { it.copy(snapshot = snapshot, selectedImage = null) }
        } }
    }

    fun downloadGsm(context: Context) = viewModelScope.launch { mutate {
        val metadata = state.value.snapshot.gsmTemplate ?: throw AdminFailure.InvalidResponse
        val bytes = repository.gsmFile(session.token)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!hash.equals(metadata.sha256, ignoreCase = true)) throw AdminFailure.InvalidResponse
        val file = withContext(Dispatchers.IO) { File(context.cacheDir, "admin-gsm/${metadata.fileName}").apply { parentFile?.mkdirs(); writeBytes(bytes) } }
        shareAdminFile(context, file, metadata.mimeType)
    } }

    fun replaceGsm(fileName: String, bytes: ByteArray) = viewModelScope.launch { mutate {
        val snapshot = repository.replaceGsm(session.user.id, session.token, fileName, bytes)
        mutable.update { it.copy(snapshot = snapshot) }
    } }

    fun toggleBlock() = mutateUser { user ->
        repository.block(
            session.user.id,
            session.token,
            user.id,
            if (user.isBlocked) null else Instant.now().plus(365, ChronoUnit.DAYS).toString(),
        )
    }

    fun toggleAdmin() = mutateUser { user ->
        val makeAdmin = user.role.lowercase() != "admin"
        repository.access(
            session.user.id,
            session.token,
            user.id,
            if (makeAdmin) "admin" else "user",
            if (makeAdmin) listOf(AdminPermission.ViewOverview.rawValue, AdminPermission.ViewUsers.rawValue) else emptyList(),
        )
    }

    fun togglePermission(permission: AdminPermission) = mutateUser { user ->
        val permissions = user.adminPermissions.toMutableSet().apply {
            if (!add(permission.rawValue)) remove(permission.rawValue)
        }
        repository.access(session.user.id, session.token, user.id, "admin", permissions.sorted())
    }

    fun deleteUser() {
        val user = state.value.selectedUser ?: return
        if (state.value.deleteConfirmation != user.email) {
            mutable.update { it.copy(error = "Введите email пользователя точно.") }
            return
        }
        viewModelScope.launch {
            mutate {
                val snapshot = repository.delete(session.user.id, session.token, user.id, user.email)
                mutable.update { it.copy(snapshot = snapshot, selectedUser = null, deleteConfirmation = "") }
            }
        }
    }

    fun updateFeedback(status: FeedbackStatus, priority: FeedbackPriority) {
        val report = state.value.selectedFeedback ?: return
        viewModelScope.launch {
            mutate {
                val snapshot = feedbackRepository.updateAdmin(
                    session.user.id, session.token, report.id, status, priority, state.value.adminNote,
                )
                mutable.update {
                    it.copy(feedback = snapshot, selectedFeedback = snapshot.messages.firstOrNull { item -> item.id == report.id })
                }
            }
        }
    }

    fun retryEmail() {
        val report = state.value.selectedFeedback ?: return
        viewModelScope.launch { mutate { feedbackRepository.retryEmail(session.token, report.id) } }
    }

    private fun mutateUser(block: suspend (AdminUser) -> AdminSnapshot) {
        val user = state.value.selectedUser ?: return
        if (user.isProtectedAdmin || user.id == session.user.id) {
            mutable.update { it.copy(error = "Защищённого или текущего администратора менять нельзя.") }
            return
        }
        viewModelScope.launch {
            mutate {
                val snapshot = block(user)
                mutable.update { it.copy(snapshot = snapshot, selectedUser = snapshot.users.firstOrNull { item -> item.id == user.id }) }
            }
        }
    }

    private suspend fun mutate(block: suspend () -> Unit) {
        mutable.update { it.copy(saving = true, error = null, notice = null) }
        try {
            block()
            mutable.update { it.copy(saving = false, notice = "Изменения сохранены.") }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutable.update { it.copy(saving = false, error = error.userMessage()) }
        }
    }

    private suspend fun refresh(showError: Boolean) {
        mutable.update { it.copy(loading = true, error = null) }
        try {
            val snapshot = repository.refresh(
                session.user.id,
                session.token,
                session.user.can(AdminPermission.ViewOverview),
                session.user.can(AdminPermission.ViewUsers),
                session.user.can(AdminPermission.ViewImages),
                session.user.can(AdminPermission.ViewGsmTemplate),
            )
            val feedback = if (session.user.can(AdminPermission.ViewFeedback)) {
                feedbackRepository.refresh(session.user.id, session.token, admin = true)
            } else state.value.feedback
            mutable.update { it.copy(snapshot = snapshot, feedback = feedback, loading = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutable.update { it.copy(loading = false, error = if (showError) error.userMessage() else null) }
        }
    }

    private fun Throwable.userMessage() = (this as? AdminFailure)?.message ?: "Админка недоступна."

    companion object {
        fun factory(repository: AdminRepository, feedbackRepository: FeedbackRepository, session: AppSession, offline: Boolean) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AdminViewModel(repository, feedbackRepository, session, offline) as T
            }
    }
}

@Composable
fun AdminRoute(
    session: AppSession,
    startOffline: Boolean,
    repository: AdminRepository,
    feedbackRepository: FeedbackRepository,
    widthClass: AppWidthClass,
    modifier: Modifier = Modifier,
) {
    val model: AdminViewModel = viewModel(
        key = "admin-${session.user.id}",
        factory = AdminViewModel.factory(repository, feedbackRepository, session, startOffline),
    )
    val state by model.state.collectAsStateWithLifecycle()
    AdminScreen(session, state, widthClass, model, modifier)
}

@Composable
private fun AdminScreen(session: AppSession, state: AdminUiState, widthClass: AppWidthClass, model: AdminViewModel, modifier: Modifier) {
    val background = appScreenBackground()
    val sections = buildList {
        if (session.user.can(AdminPermission.ViewOverview)) add(AdminSection.Overview)
        if (session.user.can(AdminPermission.ViewUsers)) add(AdminSection.Users)
        if (session.user.can(AdminPermission.ViewImages)) add(AdminSection.Images)
        if (session.user.can(AdminPermission.ViewGsmTemplate)) add(AdminSection.GsmTemplate)
        if (session.user.can(AdminPermission.ViewFeedback)) add(AdminSection.Feedback)
    }
    val selectedSection = runCatching { AdminSection.valueOf(state.section) }.getOrNull().takeIf { it in sections }
        ?: sections.firstOrNull()
    if (sections.isNotEmpty()) AppHeaderSegments(sections.map { it.title }, sections.indexOf(selectedSection),
        { model.section(sections[it].name) })
    AppRefreshContainer(modifier.fillMaxSize().background(background), isRefreshing = state.loading, onRefresh = model::refresh,
        enabled = !state.saving, contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 1100.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = AppContentHorizontalPadding, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Управление", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            state.error?.let { item { AdminCard("Ошибка", it, error = true) } }
            state.notice?.let { item { AdminCard("Готово", it) } }
            if (sections.isEmpty()) item { AdminCard("Нет доступных разделов", "У аккаунта нет разрешений просмотра.") }
            when (selectedSection) {
                AdminSection.Overview -> overviewItems(state, widthClass)
                AdminSection.Users -> {
                    items(state.snapshot.users, key = AdminUser::id) { user ->
                        AppPanel(
                            Modifier.fillMaxWidth().clickable { model.openUser(user) },
                        ) {
                            Column(Modifier.padding(15.dp)) {
                                Text(user.email, fontWeight = FontWeight.Bold)
                                Text("${user.roleTitle()} · ${if (user.isBlocked) "заблокирован" else "активен"}${if (user.isProtectedAdmin) " · защищённый" else ""}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                AdminSection.Images -> {
                    if (state.snapshot.images.isEmpty()) item { AdminCard("Изображений нет", "Доступных изображений пока нет.") }
                    items(state.snapshot.images, key = { "${it.kind}-${it.id}" }) { image ->
                        AppPanel(Modifier.fillMaxWidth().clickable { model.openImage(image) }) {
                            Column(Modifier.padding(15.dp)) {
                                Text(image.title, fontWeight = FontWeight.Bold)
                                Text("${image.kind.title} · ${if (image.isMissing) "файл не загружен" else bytes(image.sizeBytes?.toLong() ?: 0)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Использований: ${image.usageCount}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                AdminSection.GsmTemplate -> item { GsmTemplateCard(session, state.snapshot.gsmTemplate, state.saving, model) }
                null -> Unit
                AdminSection.Feedback -> {
                    items(state.feedback.messages, key = FeedbackMessage::id) { report ->
                        AppPanel(
                            Modifier.fillMaxWidth().clickable { model.openFeedback(report) },
                        ) {
                            Column(Modifier.padding(15.dp)) {
                                Text("${report.number} · ${report.status.title}", fontWeight = FontWeight.Bold)
                                Text(report.title)
                                Text("${report.reporterEmail} · ${report.priority.title}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    state.selectedUser?.let { user -> UserDialog(session, state, user, model) }
    state.selectedFeedback?.let { report -> FeedbackDialog(session, state, report, model) }
    state.selectedImage?.let { image -> ImageDialog(session, state, image, model) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.overviewItems(state: AdminUiState, widthClass: AppWidthClass) {
    state.snapshot.overview?.let { overview ->
        item { AdminCard("Сервис ${overview.system.status}", "Пользователи: ${overview.stats.users}, администраторы: ${overview.stats.admins}, активны за 7 дней: ${overview.stats.activeUsers7d}") }
        overview.resources?.let { resources -> item {
            if (widthClass == AppWidthClass.Compact) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminMetric("Процессор", resources.cpuUsagePercent); AdminMetric("Память", resources.ramUsagePercent); AdminMetric("Диск", resources.diskUsagePercent)
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { AdminMetric("Процессор", resources.cpuUsagePercent) }
                Box(Modifier.weight(1f)) { AdminMetric("Память", resources.ramUsagePercent) }
                Box(Modifier.weight(1f)) { AdminMetric("Диск", resources.diskUsagePercent) }
            }
        } }
        overview.dataStorage?.let { storage -> item { AdminCard("Данные приложения «Инженер»", "Всего ${bytes(storage.totalBytes)} · PostgreSQL ${bytes(storage.databaseBytes)} · файлы ${bytes(storage.filesBytes)}") } }
        if (overview.recentActions.isNotEmpty()) item { AdminCard("Последние действия", overview.recentActions.take(8).joinToString("\n") { "${it.actorEmail}: ${it.summary ?: it.action}" }) }
    }
}

@Composable private fun UserDialog(session: AppSession, state: AdminUiState, user: AdminUser, model: AdminViewModel) {
    AlertDialog(
        onDismissRequest = model::closeUser,
        title = { Text(user.email) },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Роль: ${user.roleTitle()}. ${if (user.isBlocked) "Заблокирован" else "Активен"}.") }
            if (session.user.can(AdminPermission.ManageUserPermissions) && !user.isProtectedAdmin && user.id != session.user.id) {
                item { AppOutlinedButton(onClick = model::toggleAdmin, modifier = Modifier.fillMaxWidth()) { Text(if (user.role == "admin") "Сделать пользователем" else "Сделать администратором") } }
                if (user.role == "admin") item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AdminPermission.entries.forEach { permission ->
                            AppOutlinedButton(onClick = { model.togglePermission(permission) }, modifier = Modifier.fillMaxWidth()) {
                                Text("${if (permission.rawValue in user.adminPermissions) "✓ " else ""}${permission.title()}")
                            }
                        }
                    }
                }
            }
            if (session.user.can(AdminPermission.BlockUsers) && !user.isProtectedAdmin && user.id != session.user.id) item { AppOutlinedButton(onClick = model::toggleBlock, modifier = Modifier.fillMaxWidth()) { Text(if (user.isBlocked) "Разблокировать" else "Заблокировать на год") } }
            if (session.user.can(AdminPermission.DeleteUsers) && !user.isProtectedAdmin && user.id != session.user.id) item {
                AppTextField(state.deleteConfirmation, model::deleteConfirmation, label = "Email для удаления", modifier = Modifier.fillMaxWidth())
            }
        } },
        confirmButton = {
            if (session.user.can(AdminPermission.DeleteUsers) && !user.isProtectedAdmin && user.id != session.user.id) AppButton(onClick = model::deleteUser, enabled = !state.saving) { Text("Удалить") }
        },
        dismissButton = { TextButton(onClick = model::closeUser) { Text("Закрыть") } },
    )
}

@Composable private fun FeedbackDialog(session: AppSession, state: AdminUiState, report: FeedbackMessage, model: AdminViewModel) {
    AlertDialog(
        onDismissRequest = model::closeFeedback,
        title = { Text("${report.number} · ${report.status.title}") },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(report.title, fontWeight = FontWeight.Bold); Text(report.message); Text(report.reporterEmail, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (session.user.can(AdminPermission.ManageFeedback)) {
                item { AppTextField(state.adminNote, model::adminNote, label = "Внутренняя заметка", minLines = 2, modifier = Modifier.fillMaxWidth()) }
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FeedbackStatus.entries.filterNot { it == FeedbackStatus.Draft }.forEach { status -> AppOutlinedButton(onClick = { model.updateFeedback(status, report.priority) }) { Text(status.title) } }
                } }
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FeedbackPriority.entries.forEach { priority -> AppOutlinedButton(onClick = { model.updateFeedback(report.status, priority) }) { Text(priority.title) } }
                } }
                item { AppOutlinedButton(onClick = model::retryEmail, modifier = Modifier.fillMaxWidth()) { Text("Повторить отправку письма") } }
            }
        } },
        confirmButton = {},
        dismissButton = { TextButton(onClick = model::closeFeedback) { Text("Закрыть") } },
    )
}

@Composable private fun ImageDialog(session: AppSession, state: AdminUiState, image: AdminServerImage, model: AdminViewModel) {
    val context = LocalContext.current
    val canUpload = image.capabilities.canUpload && session.user.can(if (image.isMissing) AdminPermission.CreateImages else AdminPermission.EditImages)
    val canDelete = image.capabilities.canDelete && session.user.can(AdminPermission.DeleteImages)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { readAdminUri(context, it, 20 * 1024 * 1024)?.second?.let(model::uploadImage) }
    }
    AlertDialog(
        onDismissRequest = model::closeImage,
        title = { Text(image.title) },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { Text("Тип: ${image.kind.title}"); Text(image.purpose); Text("Использований: ${image.usageCount}") }
            image.path?.let { item { Text("Путь: $it", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            image.sizeBytes?.let { item { Text("Размер: ${bytes(it.toLong())}") } }
            if (image.width != null && image.height != null) item { Text("Разрешение: ${image.width} × ${image.height}") }
            if (image.bindings.isNotEmpty()) item { Column { Text("Связанные переменные", fontWeight = FontWeight.Bold); image.bindings.forEach { Text("${it.name}: ${it.value}") } } }
            if (canUpload) item { AppOutlinedButton(onClick = { picker.launch("image/*") }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text(if (image.isMissing) "Загрузить изображение" else "Заменить изображение") } }
            if (canDelete) item { AppOutlinedButton(onClick = model::deleteImage, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text("Удалить изображение") } }
        } },
        confirmButton = {},
        dismissButton = { TextButton(onClick = model::closeImage) { Text("Закрыть") } },
    )
}

@Composable private fun GsmTemplateCard(session: AppSession, template: AdminGsmTemplate?, saving: Boolean, model: AdminViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { readAdminUri(context, it, 20 * 1024 * 1024)?.let { file -> if (file.first.endsWith(".xltx", true)) model.replaceGsm(file.first, file.second) } }
    }
    AppPanel {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Шаблон ГСМ-отчёта", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (template == null) Text("Шаблон недоступен", color = MaterialTheme.colorScheme.onSurfaceVariant) else {
                Text(template.fileName, fontWeight = FontWeight.SemiBold)
                Text("XLTX · ${bytes(template.sizeBytes)} · обновлён ${template.updatedAt}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("SHA-256: ${template.sha256.take(12)}…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppOutlinedButton(onClick = { model.downloadGsm(context) }, enabled = !saving) { Text("Скачать") }
                    if (session.user.can(AdminPermission.ReplaceGsmTemplate)) AppButton(onClick = { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.template", "application/octet-stream")) }, enabled = !saving) { Text("Заменить") }
                }
            }
        }
    }
}

@Composable private fun AdminCard(title: String, value: String, error: Boolean = false) {
    AppPanel(color = if (error) MaterialTheme.colorScheme.errorContainer else AppSurfaces.card) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(value, color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable private fun AdminMetric(title: String, value: Double) { AppPanel { Column(Modifier.fillMaxWidth().padding(15.dp)) { Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("%.1f%%".format(value), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) } } }
private fun bytes(value: Long): String = when { value >= 1_073_741_824 -> "%.1f ГБ".format(value / 1_073_741_824.0); value >= 1_048_576 -> "%.1f МБ".format(value / 1_048_576.0); else -> "${value / 1024} КБ" }
private fun AdminUser.roleTitle() = if (role == "admin") "Администратор" else "Пользователь"
private fun AdminPermission.title() = when (this) {
    AdminPermission.ViewOverview -> "Просмотр сводки"
    AdminPermission.ViewAuditLog -> "Просмотр журнала действий"
    AdminPermission.ViewGsmTemplate -> "Просмотр шаблона GSM"
    AdminPermission.ReplaceGsmTemplate -> "Замена шаблона GSM"
    AdminPermission.ViewUsers -> "Просмотр пользователей"
    AdminPermission.ManageUserPermissions -> "Управление правами"
    AdminPermission.BlockUsers -> "Блокировка пользователей"
    AdminPermission.DeleteUsers -> "Удаление пользователей"
    AdminPermission.ViewImages -> "Просмотр изображений"
    AdminPermission.CreateImages -> "Добавление изображений"
    AdminPermission.EditImages -> "Изменение изображений"
    AdminPermission.DeleteImages -> "Удаление изображений"
    AdminPermission.ViewFeedback -> "Просмотр обратной связи"
    AdminPermission.ManageFeedback -> "Управление обратной связью"
}
private fun readAdminUri(context:Context,uri:Uri,maxBytes:Int):Pair<String,ByteArray>?{val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}?:"file";context.contentResolver.openAssetFileDescriptor(uri,"r")?.use{if(it.length>maxBytes)return null};val data=context.contentResolver.openInputStream(uri)?.use{input->val output=ByteArrayOutputStream();val buffer=ByteArray(32*1024);var total=0;while(true){val count=input.read(buffer);if(count<0)break;total+=count;if(total>maxBytes)return null;output.write(buffer,0,count)};output.toByteArray()}?:return null;return name to data}
private fun shareAdminFile(context:Context,file:File,mime:String){val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=mime;putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Сохранить шаблон"))}
