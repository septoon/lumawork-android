package ru.lumastack.lumawork.ui.backpack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.backpack.BackpackPhotoRepository
import ru.lumastack.lumawork.data.backpack.BackpackRepository
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.domain.backpack.BackpackPhotoCatalog
import ru.lumastack.lumawork.domain.backpack.BackpackSnapshot

data class BackpackUiState(
    val simpleOneUserId: String = "",
    val isAuthorized: Boolean = false,
    val snapshot: BackpackSnapshot? = null,
    val photoCatalog: BackpackPhotoCatalog = BackpackPhotoCatalog(),
    val query: String = "",
    val selectedGroupId: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class BackpackViewModel(
    private val repository: BackpackRepository,
    private val photoRepository: BackpackPhotoRepository,
    private val ownerUserId: String,
    private val startOffline: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BackpackUiState())
    val state: StateFlow<BackpackUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val cached = runCatching { photoRepository.cached() }.getOrNull()
            if (cached != null) mutableState.update { it.copy(photoCatalog = cached) }
            if (!startOffline) {
                photoRepository.refresh()?.let { catalog ->
                    mutableState.update { it.copy(photoCatalog = catalog) }
                }
            }
        }
    }

    fun activate(simpleOneUserId: String, isAuthorized: Boolean) {
        if (simpleOneUserId.isBlank()) {
            mutableState.update { it.copy(isAuthorized = false) }
            return
        }
        if (mutableState.value.simpleOneUserId == simpleOneUserId) {
            mutableState.update { it.copy(isAuthorized = isAuthorized) }
            if (isAuthorized && !startOffline && mutableState.value.snapshot == null) refresh()
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val cached = runCatching { repository.read(ownerUserId, simpleOneUserId) }.getOrNull()
            mutableState.update {
                it.copy(
                    simpleOneUserId = simpleOneUserId,
                    isAuthorized = isAuthorized,
                    snapshot = cached,
                    query = "",
                    selectedGroupId = null,
                    errorMessage = null,
                )
            }
            if (isAuthorized && !startOffline) refreshCurrent(showError = cached == null)
        }
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { refreshCurrent(showError = true) }
    }

    fun updateQuery(value: String) {
        mutableState.update { it.copy(query = value) }
    }

    fun openGroup(groupId: String) {
        mutableState.update { it.copy(selectedGroupId = groupId) }
    }

    fun closeGroup() {
        mutableState.update { it.copy(selectedGroupId = null) }
    }

    private suspend fun refreshCurrent(showError: Boolean) {
        val current = mutableState.value
        if (!current.isAuthorized || current.simpleOneUserId.isBlank()) return
        mutableState.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val snapshot = repository.refresh(ownerUserId, current.simpleOneUserId)
            mutableState.update { it.copy(snapshot = snapshot, isAuthorized = true, isLoading = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimpleOneFailure.Unauthorized) {
            mutableState.update {
                it.copy(
                    isAuthorized = false,
                    isLoading = false,
                    errorMessage = if (showError) "Сессия SimpleOne истекла. Показан сохранённый рюкзак." else null,
                )
            }
        } catch (error: Exception) {
            mutableState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = if (showError) error.userMessage() else null,
                )
            }
        }
    }

    private fun Throwable.userMessage(): String = when (this) {
        is SimpleOneFailure -> message ?: "Не удалось загрузить рюкзак."
        else -> "Не удалось загрузить рюкзак."
    }

    companion object {
        fun factory(
            repository: BackpackRepository,
            photoRepository: BackpackPhotoRepository,
            ownerUserId: String,
            startOffline: Boolean,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BackpackViewModel(
                repository,
                photoRepository,
                ownerUserId,
                startOffline,
            ) as T
        }
    }
}
