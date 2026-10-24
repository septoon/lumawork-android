package ru.lumastack.lumawork.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lumastack.lumawork.data.route.RouteFailure
import ru.lumastack.lumawork.data.route.RouteRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.route.RouteWorkType
import ru.lumastack.lumawork.domain.route.RouteStopStatus
import ru.lumastack.lumawork.domain.route.closedRouteTemplate
import ru.lumastack.lumawork.domain.route.RouteDayRecord
import ru.lumastack.lumawork.domain.route.RouteEndpointKind
import ru.lumastack.lumawork.domain.route.RouteEndpointRole
import ru.lumastack.lumawork.domain.route.RouteSettings
import ru.lumastack.lumawork.domain.route.RouteStop
import ru.lumastack.lumawork.domain.route.defaultRouteDay
import ru.lumastack.lumawork.domain.route.hasWorkData
import ru.lumastack.lumawork.domain.route.importActiveRequests
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

data class RouteUiState(
    val record: RouteDayRecord,
    val dateTitle: String,
    val settings: RouteSettings,
    val isRefreshing: Boolean = true,
    val isDeletingArchivedDay: Boolean = false,
    val isSending: Boolean = false,
    val sentReport: RouteDayRecord? = null,
    val officeAddresses: List<String> = emptyList(),
    val isLoadingOffices: Boolean = false,
    val notice: String? = null,
    val errorMessage: String? = null,
) {
    val distanceText: String get() = record.distanceKm?.toString().orEmpty()
    val odometerText: String get() = record.periodStartOdometer?.toString().orEmpty()
    val canSend: Boolean get() = !isDeletingArchivedDay && !isSending && reportValidationError(record) == null
}

class RouteViewModel(
    private val repository: RouteRepository,
    private val session: AppSession,
    private val startOffline: Boolean,
    today: LocalDate = LocalDate.now(),
) : ViewModel() {
    private var settings = RouteSettings.from(session.user.profile)
    private val persistMutex = Mutex()
    private var loadJob: Job? = null
    private var generation = 0L
    private var editRevision = 0L
    private var excludedDates = emptySet<String>()
    private val requestedOdometerMonths = mutableSetOf<String>()
    private var odometerRevision = 0L
    private var isProcessingQueue = false
    private val mutableState = MutableStateFlow(dayState(today))
    val state: StateFlow<RouteUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.gsmProfileChanges.collect { owner ->
                if (owner == session.user.id) {
                    requestedOdometerMonths.remove(state.value.record.date.take(7))
                    // Existing monthly settings and user edits retain priority in suggestOdometer().
                    suggestOdometer()
                }
            }
        }
        load()
    }

    fun updateSettings(updated: RouteSettings) {
        if (settings == updated) return
        val previous = settings
        settings = updated
        edit { record ->
            val stops = record.stops.mapIndexed { index, stop ->
                if (index != 0 && index != record.stops.lastIndex) return@mapIndexed stop
                val address = when {
                    previous.homeAddress.isNotBlank() && stop.address.trim().equals(previous.homeAddress.trim(), true) -> updated.homeAddress.ifBlank { updated.startAddress }
                    stop.address.isBlank() || stop.address.trim().equals(previous.startAddress.trim(), true) -> updated.startAddress
                    else -> stop.address
                }
                stop.copy(address = address)
            }
            record.copy(stops = stops, distanceKm = record.distanceKm.takeIf { stops == record.stops })
        }
        mutableState.update { it.copy(settings = updated) }
    }

    fun refresh() {
        if (state.value.isDeletingArchivedDay) return
        if (state.value.isRefreshing || state.value.isSending) return
        mutableState.update { it.copy(isRefreshing = true) }
        load()
    }

    fun selectDate(date: LocalDate) {
        if (state.value.isDeletingArchivedDay) return
        if (date.toString() == state.value.record.date) return
        mutableState.value = dayState(date, state.value.record.workType).copy(isSending = state.value.isSending, officeAddresses = state.value.officeAddresses, isLoadingOffices = state.value.isLoadingOffices)
        load()
    }

    fun selectWorkType(workType: RouteWorkType) {
        if (state.value.isDeletingArchivedDay) return
        if (state.value.record.workType == workType) return
        mutableState.value = dayState(LocalDate.parse(state.value.record.date), workType).copy(isSending = state.value.isSending, officeAddresses = state.value.officeAddresses, isLoadingOffices = state.value.isLoadingOffices)
        load()
        if (workType == RouteWorkType.Arm) refreshOfficeAddresses()
    }

    fun refreshOfficeAddresses() {
        if (state.value.isLoadingOffices || startOffline) return
        mutableState.update { it.copy(isLoadingOffices = true) }
        viewModelScope.launch {
            try {
                val addresses = repository.officeAddresses(session)
                mutableState.update { it.copy(officeAddresses = addresses) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.update { it.copy(errorMessage = "Не удалось загрузить отделения.") } }
            finally { mutableState.update { it.copy(isLoadingOffices = false) } }
        }
    }

    fun autoImportClosedRequests(requests: List<SimpleOneRequest>) {
        val current = state.value
        if (current.record.date in excludedDates || current.isDeletingArchivedDay || current.isRefreshing || current.record.workType != RouteWorkType.Pos || current.record.hasWorkData()) return
        val template = closedRouteTemplate(requests, current.record.date, settings.startAddress)
        if (template.isEmpty()) return
        edit { record -> record.copy(stops = listOf(record.stops.first()) + template.map { request ->
            RouteStop(id = UUID.randomUUID().toString(), address = request.address, org = request.customer,
                tid = request.terminalId, status = RouteStopStatus.Done, requestNumber = request.incomingNumber.ifBlank {
                    request.tableFields.entries.firstOrNull { it.key in listOf("Входящий номер", "Номер заявки Мультикарты", "Номер заявки Мультикарта", "Номер заявки") && it.value.isNotBlank() }?.value
                        ?: request.shortDescription.takeIf { it.startsWith("SUTS") } ?: request.number
                })
        } + record.stops.last(), distanceKm = null) }
    }

    suspend fun deleteArchivedDay(date: String) {
        if (startOffline) throw IllegalStateException("Для удаления требуется подключение к интернету.")
        if (isProcessingQueue || state.value.isSending || state.value.isDeletingArchivedDay) {
            throw IllegalStateException("Дождитесь завершения отправки маршрута.")
        }
        mutableState.update { it.copy(isDeletingArchivedDay = true) }
        ++generation
        loadJob?.cancel()
        try {
            persistMutex.withLock {
                repository.deleteDay(session, date)
                excludedDates = excludedDates + date
                editRevision++
                if (state.value.record.date == date) {
                    val cleared = defaultRouteDay(date, settings, state.value.record.workType)
                        .copy(periodStartOdometer = state.value.record.periodStartOdometer)
                    mutableState.update { it.copy(record = cleared, sentReport = null) }
                }
            }
        } finally {
            mutableState.update { it.copy(isDeletingArchivedDay = false, isRefreshing = false) }
        }
    }

    fun dismissSentReport() { mutableState.update { it.copy(sentReport = null) } }

    fun dismissMessage() { mutableState.update { it.copy(notice = null, errorMessage = null) } }

    fun updateDistance(value: String) {
        updateNumber(value, maxLength = 5) { record, number -> record.copy(distanceKm = number) }
    }

    fun updateOdometer(value: String) {
        odometerRevision++
        updateNumber(value, maxLength = 8) { record, number -> record.copy(periodStartOdometer = number) }
        val record = state.value.record
        viewModelScope.launch { persistMutex.withLock { repository.saveOdometer(session, record.date.take(7), record.periodStartOdometer) } }
    }

    fun addStop() = edit { record ->
        val stops = record.stops.toMutableList()
        stops.add(stops.lastIndex, RouteStop(id = UUID.randomUUID().toString()))
        record.copy(stops = stops, distanceKm = null)
    }

    fun updateStop(id: String, address: String, requestNumber: String) = edit { record ->
        val index = record.stops.indexOfFirst { it.id == id }
        if (index !in 1 until record.stops.lastIndex) return@edit record
        val previous = record.stops[index]
        val stops = record.stops.toMutableList()
        stops[index] = previous.copy(address = address, requestNumber = requestNumber)
        record.copy(stops = stops, distanceKm = record.distanceKm.takeIf { previous.address.trim() == address.trim() })
    }

    fun removeStop(id: String) = edit { record ->
        val index = record.stops.indexOfFirst { it.id == id }
        if (record.stops.size <= 3 || index !in 1 until record.stops.lastIndex) return@edit record
        record.copy(stops = record.stops.filterIndexed { i, _ -> i != index }, distanceKm = null)
    }

    fun reorderStop(id: String, targetId: String) = edit { record ->
        val source = record.stops.indexOfFirst { it.id == id }
        val target = record.stops.indexOfFirst { it.id == targetId }
        if (source == target || source !in 1 until record.stops.lastIndex || target !in 1 until record.stops.lastIndex) return@edit record
        val stops = record.stops.toMutableList()
        stops.add(target, stops.removeAt(source))
        record.copy(stops = stops, distanceKm = null)
    }

    fun updateEndpoint(role: RouteEndpointRole, kind: RouteEndpointKind) {
        val address = settings.address(kind)
        if (address.isBlank()) return
        edit { record ->
            val index = if (role == RouteEndpointRole.Start) 0 else record.stops.lastIndex
            val stops = record.stops.toMutableList()
            val changed = stops[index].address.trim() != address.trim()
            stops[index] = stops[index].copy(address = address)
            record.copy(stops = stops, distanceKm = record.distanceKm.takeUnless { changed })
        }
    }

    fun importActiveRequests(requests: List<SimpleOneRequest>) {
        if (state.value.record.workType != RouteWorkType.Pos) return
        val result = importActiveRequests(state.value.record, requests)
        if (result.addedCount > 0) edit { result.record }
        mutableState.update { it.copy(notice = result.message.takeUnless { result.isError }, errorMessage = result.message.takeIf { result.isError }) }
    }

    fun send() {
        if (state.value.isDeletingArchivedDay || isProcessingQueue || state.value.isSending) return
        val submitted = state.value.record
        val validationError = reportValidationError(submitted)
        if (validationError != null) {
            mutableState.update { it.copy(errorMessage = validationError, notice = null) }
            return
        }
        // Block another tap synchronously, before starting the network coroutine.
        mutableState.update { it.copy(isSending = true, errorMessage = null, notice = null) }
        viewModelScope.launch {
            try {
                val sent = repository.send(session, submitted)
                excludedDates = excludedDates - submitted.date
                persistMutex.withLock {
                    // A send must not erase newer edits, even after the selected date changes.
                    if (repository.readLocal(session, submitted.date, settings, submitted.workType) == submitted) {
                        repository.saveLocal(session, sent)
                    }
                }
                mutableState.update {
                    it.copy(record = if (it.record == submitted) sent else it.record, isSending = false, sentReport = sent, notice = "Рабочий день отправлен.")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = if (error is RouteFailure && error.isRetryable) {
                    persistMutex.withLock {
                        if (repository.readLocal(session, submitted.date, settings, submitted.workType) == submitted) {
                            repository.saveLocal(session, submitted.copy(queuedForSend = true))
                            mutableState.update { it.copy(record = if (it.record == submitted) submitted.copy(queuedForSend = true) else it.record) }
                        }
                    }
                    "Маршрут сохранён в очередь. Повторим отправку при обновлении или следующем входе с сетью."
                } else error.message?.takeIf { error is RouteFailure } ?: "Не удалось отправить рабочий день."
                mutableState.update { it.copy(isSending = false, errorMessage = message) }
            }
        }
    }

    private fun load() {
        val requestedGeneration = ++generation
        val date = state.value.record.date
        val workType = state.value.record.workType
        val initialRevision = editRevision
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                excludedDates = repository.excludedDates(session)
                if (!startOffline) {
                    try { excludedDates = repository.syncExcludedDates(session) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Persisted exclusions still suppress automatic re-import while offline. */ }
                }
                if (generation != requestedGeneration) return@launch
                val current = state.value.record
                if (editRevision == initialRevision && date in excludedDates && date !in repository.pendingRestores(session)
                    && (current.sent || current.queuedForSend)) {
                    mutableState.update { it.copy(record = defaultRouteDay(date, settings, workType).copy(periodStartOdometer = current.periodStartOdometer)) }
                }
                val local = persistMutex.withLock { repository.readLocal(session, date, settings, workType) }
                if (generation != requestedGeneration) return@launch
                val odometer = repository.readOdometer(session, date.take(7))
                val hasOdometerSetting = repository.hasOdometerSetting(session, date.take(7))
                if (generation != requestedGeneration) return@launch
                if (editRevision == initialRevision) mutableState.update { it.copy(record = (local ?: it.record).copy(periodStartOdometer = if (hasOdometerSetting) odometer else (local ?: it.record).periodStartOdometer)) }
                val requestedRecord = state.value.record
                val requestedRevision = editRevision
                if (!startOffline) {
                    val remote = repository.refresh(session, date, settings, requestedRecord, workType)
                    if (remote != null) persistMutex.withLock {
                        if (generation == requestedGeneration && editRevision == requestedRevision) {
                            val accepted = remote.copy(periodStartOdometer = if (hasOdometerSetting) odometer else remote.periodStartOdometer)
                            repository.saveLocal(session, accepted)
                            if (generation == requestedGeneration && editRevision == requestedRevision) {
                                mutableState.update { it.copy(record = accepted) }
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep the selected local day usable when a background load fails.
            } finally {
                if (generation == requestedGeneration) {
                    mutableState.update { it.copy(isRefreshing = false) }
                    suggestOdometer()
                    processQueue()
                }
            }
        }
    }

    private fun processQueue() {
        if (state.value.isDeletingArchivedDay || startOffline || isProcessingQueue || state.value.isSending) return
        isProcessingQueue = true
        viewModelScope.launch {
            try {
                for (queued in repository.readQueued(session)) {
                    if (queued.date in excludedDates && queued.date !in repository.pendingRestores(session)) continue
                    val current = persistMutex.withLock { repository.readLocal(session, queued.date, settings, queued.workType) }
                    if (current == null || !current.queuedForSend || reportValidationError(current) != null) continue
                    mutableState.update { it.copy(isSending = true) }
                    val sent = repository.send(session, current)
                    persistMutex.withLock {
                        if (repository.readLocal(session, current.date, settings, current.workType) == current) {
                            repository.saveLocal(session, sent)
                            mutableState.update { it.copy(record = if (it.record == current) sent else it.record) }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep queued reports for the next refresh or online launch. */ }
            finally { isProcessingQueue = false; mutableState.update { it.copy(isSending = false) } }
        }
    }

    private fun suggestOdometer() {
        val month = state.value.record.date.take(7)
        if (startOffline || state.value.record.periodStartOdometer != null || !requestedOdometerMonths.add(month)) return
        val revision = odometerRevision
        viewModelScope.launch {
            try {
                val value = repository.suggestedOdometer(session, month) ?: return@launch
                if (value < 0 || state.value.record.date.take(7) != month || state.value.record.periodStartOdometer != null || odometerRevision != revision) return@launch
                if (repository.hasOdometerSetting(session, month)) return@launch
                if (state.value.record.date.take(7) != month || state.value.record.periodStartOdometer != null || odometerRevision != revision) return@launch
                updateOdometer(value.toString())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { requestedOdometerMonths.remove(month) }
        }
    }

    private fun updateNumber(raw: String, maxLength: Int, transform: (RouteDayRecord, Int?) -> RouteDayRecord) {
        val normalized = raw.filter(Char::isDigit).take(maxLength)
        if (raw.isNotEmpty() && normalized.isEmpty()) return
        edit { transform(it, normalized.toIntOrNull()) }
    }

    private fun edit(transform: (RouteDayRecord) -> RouteDayRecord) {
        if (state.value.isDeletingArchivedDay) return
        val previous = state.value.record
        val updated = transform(previous)
        if (previous == updated) return
        editRevision++
        val snapshot = updated.copy(sent = false, queuedForSend = false, reportedDistanceKm = null,
            reportedPeriodStartOdometer = null, routeSummary = null, requestNumbersSummary = null)
        mutableState.update { it.copy(record = snapshot, notice = null, errorMessage = null) }
        viewModelScope.launch {
            persistMutex.withLock { repository.saveLocal(session, snapshot) }
        }
    }

    private fun dayState(date: LocalDate, workType: RouteWorkType = RouteWorkType.Pos) = RouteUiState(
        record = defaultRouteDay(date.toString(), settings, workType),
        dateTitle = date.format(DATE_TITLE_FORMATTER).replaceFirstChar { it.titlecase(RUSSIAN_LOCALE) },
        settings = settings,
    )

    companion object {
        private val RUSSIAN_LOCALE = Locale.forLanguageTag("ru")
        private val DATE_TITLE_FORMATTER = DateTimeFormatter.ofPattern("d MMMM", RUSSIAN_LOCALE)
        fun factory(repository: RouteRepository, session: AppSession, startOffline: Boolean, initialDate: LocalDate = LocalDate.now()): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = RouteViewModel(repository, session, startOffline, initialDate) as T
            }
    }
}

private fun reportValidationError(record: RouteDayRecord): String? = when {
    !record.hasWorkData() -> "Добавьте адрес или номер заявки в маршрут."
    record.date >= "2026-04-01" && record.distanceKm == null -> "Заполните пробег за день."
    record.date >= "2026-04-01" && record.periodStartOdometer == null -> "Заполните одометр на начало месяца."
    record.workType == RouteWorkType.Arm && record.stops.drop(1).dropLast(1).any { it.address.isBlank() } -> "Для АРМ выберите адрес отделения у каждой точки."
    else -> null
}
