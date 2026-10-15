package ru.lumastack.lumawork.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lumastack.lumawork.data.route.RouteFailure
import ru.lumastack.lumawork.data.route.RouteRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.route.RouteDayRecord
import ru.lumastack.lumawork.domain.route.RouteSettings
import ru.lumastack.lumawork.domain.route.defaultRouteDay
import ru.lumastack.lumawork.domain.route.hasWorkData
import ru.lumastack.lumawork.domain.route.importActiveRequests
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

data class RouteUiState(
    val record: RouteDayRecord,
    val dateTitle: String,
    val isRefreshing: Boolean = true,
    val isSending: Boolean = false,
    val notice: String? = null,
    val errorMessage: String? = null,
) {
    val distanceText: String
        get() = record.distanceKm?.toString().orEmpty()

    val odometerText: String
        get() = record.periodStartOdometer?.toString().orEmpty()

    val canSend: Boolean
        get() = record.hasWorkData() &&
            record.distanceKm != null &&
            record.periodStartOdometer != null &&
            !isSending
}

class RouteViewModel(
    private val repository: RouteRepository,
    private val session: AppSession,
    private val startOffline: Boolean,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {
    private val date = today.toString()
    private val settings = RouteSettings.from(session.user.profile)
    private val persistMutex = Mutex()
    private val mutableState = MutableStateFlow(
        RouteUiState(
            record = defaultRouteDay(date, settings),
            dateTitle = today.format(DATE_TITLE_FORMATTER).replaceFirstChar { it.titlecase(RUSSIAN_LOCALE) },
        ),
    )

    val state: StateFlow<RouteUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun updateDistance(value: String) {
        updateNumber(value, maxLength = 5) { record, number -> record.copy(distanceKm = number) }
    }

    fun updateOdometer(value: String) {
        updateNumber(value, maxLength = 8) { record, number -> record.copy(periodStartOdometer = number) }
    }

    fun updatePrimaryStopAddress(value: String) {
        updatePrimaryStop { it.copy(address = value) }
    }

    fun updatePrimaryStopRequestNumber(value: String) {
        updatePrimaryStop { it.copy(requestNumber = value) }
    }

    fun importActiveRequests(requests: List<SimpleOneRequest>) {
        val result = importActiveRequests(mutableState.value.record, requests)
        mutableState.update {
            it.copy(
                record = result.record,
                notice = result.message.takeUnless { result.isError },
                errorMessage = result.message.takeIf { result.isError },
            )
        }
        if (result.addedCount > 0) persistLatest()
    }

    fun send() {
        val current = mutableState.value.record
        val validationError = validate(current)
        if (validationError != null) {
            mutableState.update { it.copy(errorMessage = validationError, notice = null) }
            return
        }

        viewModelScope.launch {
            mutableState.update { it.copy(isSending = true, errorMessage = null, notice = null) }
            try {
                val reset = repository.send(session, current, settings)
                mutableState.update {
                    it.copy(
                        record = reset,
                        isSending = false,
                        notice = "Рабочий день отправлен.",
                    )
                }
            } catch (error: RouteFailure) {
                val message = if (error.isRetryable) {
                    "Данные сохранены локально. Повторите отправку после восстановления связи."
                } else {
                    error.message ?: "Не удалось отправить рабочий день."
                }
                mutableState.update { it.copy(isSending = false, errorMessage = message) }
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(isSending = false, errorMessage = "Не удалось отправить рабочий день.")
                }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val local = runCatching { repository.readLocal(session, date, settings) }.getOrNull()
            if (local != null) mutableState.update { it.copy(record = local) }

            if (startOffline) {
                mutableState.update { it.copy(isRefreshing = false) }
                return@launch
            }

            try {
                val remote = repository.refresh(session, date, settings, local)
                mutableState.update { state ->
                    state.copy(record = remote ?: state.record, isRefreshing = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A local/default day stays interactive while a background refresh fails.
                mutableState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private fun updateNumber(
        raw: String,
        maxLength: Int,
        transform: (RouteDayRecord, Int?) -> RouteDayRecord,
    ) {
        val normalized = raw.filter(Char::isDigit).take(maxLength)
        if (raw.isNotEmpty() && normalized.isEmpty()) return
        mutableState.update {
            it.copy(
                record = transform(it.record, normalized.toIntOrNull()).copy(sent = false),
                notice = null,
                errorMessage = null,
            )
        }
        persistLatest()
    }

    private fun updatePrimaryStop(transform: (ru.lumastack.lumawork.domain.route.RouteStop) -> ru.lumastack.lumawork.domain.route.RouteStop) {
        mutableState.update { state ->
            val stops = state.record.stops.toMutableList()
            if (stops.size >= 3) stops[1] = transform(stops[1])
            state.copy(
                record = state.record.copy(stops = stops, sent = false),
                notice = null,
                errorMessage = null,
            )
        }
        persistLatest()
    }

    private fun persistLatest() {
        viewModelScope.launch {
            persistMutex.withLock {
                repository.saveLocal(session, mutableState.value.record)
            }
        }
    }

    private fun validate(record: RouteDayRecord): String? = when {
        !record.hasWorkData() -> "Добавьте адрес или номер заявки в маршрут."
        record.date >= REPORT_START_DATE && record.distanceKm == null ->
            "Заполните пробег за день."
        record.date >= REPORT_START_DATE && record.periodStartOdometer == null ->
            "Заполните одометр на начало месяца."
        else -> null
    }

    companion object {
        private const val REPORT_START_DATE = "2026-04-01"
        private val RUSSIAN_LOCALE = Locale.forLanguageTag("ru")
        private val DATE_TITLE_FORMATTER = DateTimeFormatter.ofPattern("d MMMM", RUSSIAN_LOCALE)

        fun factory(
            repository: RouteRepository,
            session: AppSession,
            startOffline: Boolean,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RouteViewModel(repository, session, startOffline) as T
            }
    }
}
