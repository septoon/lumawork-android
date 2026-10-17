package ru.lumastack.lumawork.ui.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.update.UpdateRelease
import ru.lumastack.lumawork.data.update.UpdateRepository

data class UpdateState(val visible: Boolean = false, val busy: Boolean = false, val message: String = "",
    val release: UpdateRelease? = null, val progress: Float? = null, val file: File? = null, val waitingPermission: Boolean = false)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UpdateRepository(application)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var downloadJob: Job? = null
    init { check(manual = false) }
    fun check(manual: Boolean = true) {
        if (mutable.value.busy) return
        mutable.value = UpdateState(visible = manual, busy = true, message = "Проверяем обновления…")
        viewModelScope.launch {
            try {
                val release = repository.check()
                val newer = release.isNewerThan(repository.code(repository.installed()))
                mutable.value = UpdateState(visible = manual || newer, release = release.takeIf { newer }, message = if (newer) "Доступна версия ${release.versionName}" else "Установлена актуальная версия.")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = UpdateState(visible = manual, message = e.message ?: "Не удалось проверить обновления.")
            }
        }
    }
    fun download() {
        val release = mutable.value.release ?: return
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, progress = 0f, message = "Загрузка обновления…")
        downloadJob = viewModelScope.launch {
            try {
                val file = repository.download(release) { progress -> mutable.value = mutable.value.copy(progress = progress) }
                mutable.value = mutable.value.copy(busy = false, progress = null, file = file, message = "APK проверен. Подтвердите системную установку.")
            } catch (e: Exception) {
                if (e is CancellationException) {
                    mutable.value = UpdateState(visible = true, release = release, message = "Загрузка отменена.")
                    throw e
                }
                mutable.value = mutable.value.copy(busy = false, progress = null, file = null, message = e.message ?: "Ошибка загрузки обновления.")
            }
        }
    }
    fun cancelDownload() { downloadJob?.cancel(); repository.cancelDownload() }
    fun verifyAndInstall(install: (File) -> Unit) {
        val snapshot = mutable.value
        val file = snapshot.file ?: return
        val release = snapshot.release ?: return
        if (snapshot.busy) return
        mutable.value = snapshot.copy(busy = true, waitingPermission = false)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.verifyArchive(file, release) }
                install(file)
                mutable.value = mutable.value.copy(busy = false, message = "Подтвердите обновление в системном окне Android.")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = mutable.value.copy(busy = false, file = null, progress = null, message = e.message ?: "Не удалось открыть системную установку.")
            }
        }
    }
    fun permissionRequested() { mutable.value = mutable.value.copy(waitingPermission = true) }
    fun message(text: String) { mutable.value = mutable.value.copy(busy = false, waitingPermission = false, message = text) }
    fun dismiss() { if (!mutable.value.busy) mutable.value = mutable.value.copy(visible = false) }
}
