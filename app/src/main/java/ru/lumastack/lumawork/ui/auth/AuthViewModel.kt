package ru.lumastack.lumawork.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.auth.AuthFailure
import ru.lumastack.lumawork.data.auth.AuthRepository
import ru.lumastack.lumawork.data.auth.RestoreResult
import ru.lumastack.lumawork.domain.model.AppSession

enum class AuthPhase {
    Email,
    Code,
    Authenticated,
    AccessRevoked,
}

data class AuthUiState(
    val phase: AuthPhase,
    val email: String,
    val code: String = "",
    val session: AppSession? = null,
    val isLoading: Boolean = false,
    val isOffline: Boolean = false,
    val notice: String? = null,
    val errorMessage: String? = null,
)

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {
    private val cachedSession = repository.session
    private val mutableState = MutableStateFlow(
        AuthUiState(
            phase = if (cachedSession == null) AuthPhase.Email else AuthPhase.Authenticated,
            email = repository.rememberedEmail,
            session = cachedSession,
        ),
    )
    val state = mutableState.asStateFlow()

    init {
        if (cachedSession != null) refreshSession()
    }

    fun onEmailChange(value: String) {
        mutableState.update { it.copy(email = value, errorMessage = null, notice = null) }
    }

    fun onCodeChange(value: String) {
        val code = value.filter(Char::isDigit).take(CODE_LENGTH)
        mutableState.update { it.copy(code = code, errorMessage = null) }
    }

    fun requestCode() = launchAction {
        val email = state.value.email.trim().lowercase()
        if (email.isEmpty()) {
            mutableState.update { it.copy(errorMessage = "Введите почту.") }
            return@launchAction
        }

        try {
            val normalizedEmail = repository.requestCode(email)
            mutableState.update {
                it.copy(
                    phase = AuthPhase.Code,
                    email = normalizedEmail,
                    code = "",
                    notice = "Код отправлен на $normalizedEmail.",
                )
            }
        } catch (_: AuthFailure.AccessRevoked) {
            showAccessRevoked()
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = error.userMessage()) }
        }
    }

    fun verifyCode() = launchAction {
        val current = state.value
        if (current.code.length != CODE_LENGTH) {
            mutableState.update { it.copy(errorMessage = "Введите 6-значный код.") }
            return@launchAction
        }

        try {
            val session = repository.verifyCode(current.email, current.code)
            mutableState.update {
                it.copy(
                    phase = AuthPhase.Authenticated,
                    email = session.user.email,
                    code = "",
                    session = session,
                    isOffline = false,
                    notice = null,
                )
            }
        } catch (_: AuthFailure.AccessRevoked) {
            showAccessRevoked()
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = error.userMessage()) }
        }
    }

    fun backToEmail() {
        mutableState.update {
            it.copy(phase = AuthPhase.Email, code = "", notice = null, errorMessage = null)
        }
    }

    fun resetAccessRevoked() {
        mutableState.update {
            it.copy(phase = AuthPhase.Email, code = "", notice = null, errorMessage = null)
        }
    }

    fun logout() = launchAction {
        repository.logout()
        mutableState.update {
            AuthUiState(phase = AuthPhase.Email, email = it.email)
        }
    }

    private fun refreshSession() {
        viewModelScope.launch {
            when (val result = repository.restoreSession()) {
                is RestoreResult.Active -> mutableState.update {
                    it.copy(session = result.session, email = result.session.user.email, isOffline = false)
                }
                is RestoreResult.Offline -> mutableState.update {
                    it.copy(session = result.session, isOffline = true)
                }
                RestoreResult.SignedOut -> mutableState.update {
                    AuthUiState(phase = AuthPhase.Email, email = it.email)
                }
                RestoreResult.AccessRevoked -> showAccessRevoked()
            }
        }
    }

    private fun showAccessRevoked() {
        repository.clearSession()
        mutableState.update {
            it.copy(
                phase = AuthPhase.AccessRevoked,
                code = "",
                session = null,
                isOffline = false,
                notice = null,
                errorMessage = null,
            )
        }
    }

    private fun launchAction(action: suspend () -> Unit) {
        if (state.value.isLoading) return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                action()
            } finally {
                mutableState.update { it.copy(isLoading = false) }
            }
        }
    }

    companion object {
        private const val CODE_LENGTH = 6

        fun factory(repository: AuthRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AuthViewModel(repository) as T
                }
            }
    }
}

private fun Exception.userMessage(): String = when (this) {
    is AuthFailure.Http -> message ?: "Ошибка авторизации."
    is AuthFailure.InvalidResponse -> message.orEmpty()
    is AuthFailure.Network -> when (cause) {
        is SocketTimeoutException -> "Сервер авторизации не ответил вовремя."
        is UnknownHostException -> "Не удалось найти сервер авторизации: api.work.lumastack.ru."
        else -> "Не удалось подключиться к api.work.lumastack.ru."
    }
    else -> "Не удалось выполнить запрос."
}

