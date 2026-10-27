package ru.lumastack.lumawork.data.simpleone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import ru.lumastack.lumawork.domain.simpleone.ClosedArchiveSource
import ru.lumastack.lumawork.domain.simpleone.ClosedSimpleOneArchive
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneSnapshot
import ru.lumastack.lumawork.domain.requests.normalizeHistoryTerminalId
import ru.lumastack.lumawork.domain.requests.terminalRequestHistory
import ru.lumastack.lumawork.domain.requests.closedRequestType
import ru.lumastack.lumawork.domain.requests.closedCompletionTime
import ru.lumastack.lumawork.domain.requests.closedRegistrationTime
import ru.lumastack.lumawork.domain.requests.closedClosureCode
import ru.lumastack.lumawork.domain.requests.isClosedReturnEquipment
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequestSource

class SimpleOneRepository(
    private val api: SimpleOneApi,
    private val sessionStore: SimpleOneSessionStore,
    private val cache: SimpleOneCache,
    private val supplementalCache: SimpleOneSupplementalCache = EmptySimpleOneSupplementalCache,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val sessionMutex = Mutex()
    private val archiveMutex = Mutex()
    private val generations = mutableMapOf<String, Long>()

    suspend fun storedSession(ownerUserId: String): StoredSimpleOneSession? = withContext(Dispatchers.IO) {
        sessionStore.read(ownerUserId)
    }

    suspend fun rememberedUsername(ownerUserId: String): String = withContext(Dispatchers.IO) {
        sessionStore.readUsername(ownerUserId)
    }

    suspend fun readSnapshot(ownerUserId: String): SimpleOneSnapshot? {
        val session = storedSession(ownerUserId) ?: return null
        return cache.read(ownerUserId)?.takeIf { it.sessionFingerprint == simpleOneSessionFingerprint(session) }
    }

    /** Resolves identity for Coordination without fetching active or closed request lists. */
    suspend fun resolveCurrentUser(ownerUserId: String): SimpleOneSnapshot {
        val session = storedSession(ownerUserId) ?: throw SimpleOneFailure.MissingCredentials
        try {
            val user = api.currentUser(session.authKey)
            return withCurrentSession(ownerUserId, session) {
                val previous = cache.read(ownerUserId)?.takeIf { it.sessionFingerprint == simpleOneSessionFingerprint(session) }
                (previous ?: SimpleOneSnapshot()).copy(currentUser = user, sessionFingerprint = simpleOneSessionFingerprint(session))
                    .also { cache.save(ownerUserId, it) }
            }
        } catch (error: SimpleOneFailure.Unauthorized) {
            sessionMutex.withLock { if (storedSession(ownerUserId) == session) withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) } }
            throw error
        }
    }

    suspend fun readClosedArchive(ownerUserId: String): ClosedSimpleOneArchive? =
        supplementalCache.readClosedArchive(ownerUserId)

    suspend fun signIn(
        ownerUserId: String,
        username: String,
        password: String,
        cached: SimpleOneSnapshot?,
    ): SimpleOneSnapshot {
        authenticate(ownerUserId, username, password)
        return refresh(ownerUserId, cached)
    }

    /** Initial archive login never starts an active-list request. */
    suspend fun authenticate(ownerUserId: String, username: String, password: String) {
        val generation = sessionMutex.withLock { generations[ownerUserId] ?: 0L }
        val authKey = api.login(username.trim(), password.trim())
        sessionMutex.withLock {
            currentCoroutineContext().ensureActive()
            if ((generations[ownerUserId] ?: 0L) != generation) throw CancellationException("SimpleOne session changed")
            withContext(Dispatchers.IO) { sessionStore.save(StoredSimpleOneSession(ownerUserId, authKey, username.trim())) }
        }
    }

    private suspend fun <T> withCurrentSession(owner: String, session: StoredSimpleOneSession, block: suspend () -> T): T =
        sessionMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (storedSession(owner) != session) throw CancellationException("SimpleOne session changed")
            block()
        }

    suspend fun refresh(ownerUserId: String, cached: SimpleOneSnapshot?): SimpleOneSnapshot {
        val session = storedSession(ownerUserId) ?: throw SimpleOneFailure.MissingCredentials
        return try {
            val currentUser = cached?.takeIf { it.sessionFingerprint == simpleOneSessionFingerprint(session) }?.currentUser ?: api.currentUser(session.authKey)
            val listed = api.activeRequests(currentUser.sysId, session.authKey)
            val requests = listed.chunked(4).flatMap { chunk -> coroutineScope {
                chunk.map { request -> async {
                    try { requestDetails(ownerUserId, request) }
                    catch (error: CancellationException) { throw error }
                    catch (error: SimpleOneFailure.Unauthorized) { throw error }
                    catch (_: Exception) { request }
                } }.awaitAll()
            } }
            SimpleOneSnapshot(
                currentUser = currentUser,
                activeRequests = requests,
                updatedAtEpochMillis = nowEpochMillis(),
                sessionFingerprint = simpleOneSessionFingerprint(session),
            ).also { snapshot -> withCurrentSession(ownerUserId, session) { cache.save(ownerUserId, snapshot) } }
        } catch (error: SimpleOneFailure.Unauthorized) {
            sessionMutex.withLock {
                if (storedSession(ownerUserId) == session) withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) }
            }
            throw error
        }
    }

    suspend fun refreshClosedArchive(
        ownerUserId: String,
        cachedSnapshot: SimpleOneSnapshot? = null,
        onProgress: (String) -> Unit = {},
    ): ClosedSimpleOneArchive = archiveMutex.withLock {
        val session = storedSession(ownerUserId) ?: throw SimpleOneFailure.MissingCredentials
        try {
            val currentUser = cachedSnapshot?.takeIf { it.sessionFingerprint == simpleOneSessionFingerprint(session) }?.currentUser ?: api.currentUser(session.authKey)
            onProgress("Подготовка полного архива SimpleOne…")
            val (listed, source) = try {
                val xlsx = api.exportClosedRequests(currentUser.sysId, session.authKey)
                onProgress("Обработка закрытых заявок…")
                val records = withContext(Dispatchers.Default) { SimpleOneXlsxParser.parse(xlsx) }
                records to ClosedArchiveSource.Xlsx
            } catch (error: CancellationException) { throw error }
            catch (error: SimpleOneFailure.Unauthorized) { throw error }
            catch (_: Exception) {
                onProgress("Загрузка всех страниц закрытых заявок…")
                api.closedRequests(currentUser.sysId, session.authKey) to ClosedArchiveSource.ListApi
            }
            val records = completeClosedArchive(ownerUserId, session, currentUser.sysId, listed, onProgress)
            val archive = ClosedSimpleOneArchive(records, nowEpochMillis(), true, source, 2)
            withCurrentSession(ownerUserId, session) { supplementalCache.saveClosedArchive(ownerUserId, archive) }
            archive
        } catch (error: SimpleOneFailure.Unauthorized) {
            sessionMutex.withLock {
                if (storedSession(ownerUserId) == session) withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) }
            }
            throw error
        }
    }

    private fun SimpleOneRequest.needsClosedDetails(): Boolean = closedRequestType().isBlank() ||
        (if (isClosedReturnEquipment(closedRequestType())) closedRegistrationTime().isBlank()
        else closedCompletionTime().isBlank() || closedClosureCode().isBlank())

    private suspend fun completeClosedArchive(
        owner: String,
        session: StoredSimpleOneSession,
        userId: String,
        listed: List<SimpleOneRequest>,
        onProgress: (String) -> Unit,
    ): List<SimpleOneRequest> {
        // Exports from older server presets may omit sys_id; resolve only their canonical REQ numbers.
        val identities = if (listed.any { it.needsClosedDetails() && it.sysId.isBlank() }) {
            onProgress("Поиск карточек неполных заявок…")
            api.closedRequests(userId, session.authKey).groupBy { it.number }
        } else emptyMap()
        val records = listed.map { record ->
            if (record.needsClosedDetails() && record.sysId.isBlank()) {
                val identity = identities[record.number]?.singleOrNull()?.takeIf { it.sysId.isNotBlank() }
                    ?: throw SimpleOneFailure.Http(409, "Не удалось найти карточку ${record.number}. Повторите обновление архива.")
                record.mergeMissing(identity).copy(source = SimpleOneRequestSource.Closed)
            } else record.copy(source = SimpleOneRequestSource.Closed)
        }
        val incomplete = records.filter { it.needsClosedDetails() }
        if (incomplete.isEmpty()) return records
        val completed = mutableMapOf<String, SimpleOneRequest>()
        onProgress("Дополнение полей архива: 0 из ${incomplete.size}")
        for (batch in incomplete.chunked(4)) {
            currentCoroutineContext().ensureActive()
            if (storedSession(owner) != session) throw CancellationException("SimpleOne session changed")
            val details = coroutineScope {
                batch.map { record -> async {
                    val detailed = api.requestDetails(record, session.authKey).mergeMissing(record)
                    if (detailed.id != record.id || detailed.number != record.number) throw SimpleOneFailure.InvalidResponse
                    if (detailed.closedRequestType().isBlank() ||
                        detailed.closedCompletionTime().isBlank() && detailed.closedRegistrationTime().isBlank()) {
                        throw SimpleOneFailure.Http(422, "SimpleOne не вернул тип или дату заявки ${record.number}. Архив не обновлён; сохранённые данные остаются на устройстве.")
                    }
                    detailed.copy(source = SimpleOneRequestSource.Closed)
                } }.awaitAll()
            }
            details.forEach { completed[it.id] = it }
            onProgress("Дополнение полей архива: ${completed.size} из ${incomplete.size}")
        }
        return records.map { completed[it.id] ?: it }
    }

    suspend fun importClosedArchive(ownerUserId: String, bytes: ByteArray): ClosedSimpleOneArchive = archiveMutex.withLock {
        val imported = withContext(Dispatchers.Default) { SimpleOneXlsxParser.parse(bytes) }
        val existing = supplementalCache.readClosedArchive(ownerUserId)?.requests.orEmpty()
        val records = (existing + imported).associateBy { it.number.ifBlank { it.id } }.values.toList()
        ClosedSimpleOneArchive(records, nowEpochMillis(), true, ClosedArchiveSource.Xlsx, 2).also {
            currentCoroutineContext().ensureActive()
            supplementalCache.saveClosedArchive(ownerUserId, it)
        }
    }

    suspend fun exportClosedArchive(ownerUserId: String): ByteArray {
        val records = supplementalCache.readClosedArchive(ownerUserId)?.requests.orEmpty()
        return withContext(Dispatchers.Default) { ClosedRequestsXlsxExport.create(records) }
    }

    /** Device-local cleanup. This never calls the SimpleOne API. */
    suspend fun cleanupClosedArchive(ownerUserId: String, shouldRemove: (SimpleOneRequest) -> Boolean): ClosedSimpleOneArchive =
        archiveMutex.withLock {
            val archive = supplementalCache.readClosedArchive(ownerUserId) ?: ClosedSimpleOneArchive(isComplete = true, queryRevision = 2)
            val updated = withContext(Dispatchers.Default) { archive.copy(requests = archive.requests.filterNot(shouldRemove)) }
            supplementalCache.saveClosedArchive(ownerUserId, updated)
            updated
        }

    suspend fun warehouseRequests(ownerUserId: String, terminalId: String): List<SimpleOneRequest> {
        val session = storedSession(ownerUserId) ?: throw SimpleOneFailure.MissingCredentials
        fun normalize(raw: String) = raw.filter(Char::isLetterOrDigit).uppercase()
        val target = normalize(terminalId)
        return api.warehouseRequests(terminalId, session.authKey).filter { request ->
            val info = ru.lumastack.lumawork.domain.requests.requestInformation(request)
            val terminal = request.terminalId.ifBlank { info["id терминал"] ?: info["id терминала"].orEmpty() }
            val text = listOf(request.requestType, request.shortDescription, request.assignmentGroup, request.description,
                request.additionalInformation, request.clientService, request.clientServiceParent, request.tableFields.toString()).joinToString(" ").lowercase()
            val isWarehouse = text.contains("returnequip") || text.contains("return_equip") || text.contains("возврат") ||
                listOf("город склада", "складская заявка", "складские заявки", "номер принятого оборудования").any(text::contains)
            isWarehouse && target == normalize(terminal)
        }.distinctBy { it.id }.sortedByDescending { it.registeredAt }
    }

    suspend fun cachedTerminalHistory(ownerUserId: String, terminalId: String): List<SimpleOneRequest>? {
        val terminal = normalizeHistoryTerminalId(terminalId).takeIf(String::isNotEmpty) ?: return null
        val session = storedSession(ownerUserId) ?: return null
        return withCurrentSession(ownerUserId, session) {
            supplementalCache.readClosedArchive(historyCacheScope(session, terminal))?.requests
                ?.let { terminalRequestHistory(it, terminal) }
        }
    }

    suspend fun terminalHistory(ownerUserId: String, terminalId: String): List<SimpleOneRequest> {
        val terminal = normalizeHistoryTerminalId(terminalId)
        require(terminal.isNotEmpty()) { "Некорректный ID терминала." }
        val session = storedSession(ownerUserId) ?: throw SimpleOneFailure.MissingCredentials
        try {
            val records = terminalRequestHistory(api.terminalHistory(terminal, session.authKey), terminal)
            return withCurrentSession(ownerUserId, session) {
                supplementalCache.saveClosedArchive(historyCacheScope(session, terminal), ClosedSimpleOneArchive(
                    requests = records, updatedAtEpochMillis = nowEpochMillis(), isComplete = true, queryRevision = 2,
                ))
                records
            }
        } catch (error: SimpleOneFailure.Unauthorized) {
            sessionMutex.withLock { if (storedSession(ownerUserId) == session) withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) } }
            throw error
        }
    }

    private fun historyCacheScope(session: StoredSimpleOneSession, terminalId: String): String =
        "${session.ownerUserId}|terminal-history-v2|${simpleOneSessionFingerprint(session)}|$terminalId"

    suspend fun requestDetails(ownerUserId: String, request: SimpleOneRequest, force: Boolean = false, requiresCurrentVersion: Boolean = false): SimpleOneRequest {
        val requiredSession = if (requiresCurrentVersion) storedSession(ownerUserId) ?: throw SimpleOneFailure.MissingCredentials else null
        val stored = supplementalCache.readDetail(ownerUserId, request.id)
        val cached = stored?.takeIf { !requiresCurrentVersion || (request.sysUpdatedAt.isNotBlank() && request.sysUpdatedAt == it.request.sysUpdatedAt) }
        if (!force && cached != null && nowEpochMillis() - cached.cachedAtEpochMillis < DETAIL_CACHE_LIFETIME_MILLIS &&
            cached.request.state == request.state && cached.request.stateRaw == request.stateRaw &&
            (request.multicardState.isBlank() || cached.request.multicardState == request.multicardState) &&
            cached.request.deadline == request.deadline) {
            return cached.request
        }

        val session = requiredSession ?: storedSession(ownerUserId) ?: return cached?.request ?: request
        return try {
            api.requestDetails(request, session.authKey).also { detailed ->
                withCurrentSession(ownerUserId, session) {
                    supplementalCache.saveDetail(ownerUserId, SimpleOneDetailCacheEntry(detailed, nowEpochMillis()))
                }
            }
        } catch (error: SimpleOneFailure.Unauthorized) {
            sessionMutex.withLock {
                if (storedSession(ownerUserId) == session) withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) }
            }
            throw error
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            cached?.request ?: throw error
        }
    }

    suspend fun signOut(ownerUserId: String) {
        sessionMutex.withLock {
            generations[ownerUserId] = (generations[ownerUserId] ?: 0L) + 1
            withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) }
        }
    }

    companion object {
        private const val DETAIL_CACHE_LIFETIME_MILLIS = 24L * 60 * 60 * 1_000
    }
}

internal fun simpleOneSessionFingerprint(session: StoredSimpleOneSession): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest("${session.ownerUserId.length}:${session.ownerUserId}${session.authKey}".toByteArray()).joinToString("") { "%02x".format(it) }
