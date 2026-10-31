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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import ru.lumastack.lumawork.domain.simpleone.ClosedArchiveSource
import ru.lumastack.lumawork.domain.simpleone.ActiveRequestsStatusFilter
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
import ru.lumastack.lumawork.domain.simpleone.ClosedRequestsSyncScope
import ru.lumastack.lumawork.domain.simpleone.ClosedRequestsSyncRecord
import ru.lumastack.lumawork.domain.simpleone.ClosedRequestsSyncCursor

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
    private val detailMutex = Mutex()
    private val detailScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class DetailKey(val owner: String, val fingerprint: String, val request: SimpleOneRequest, val unknownVersionRequired: Boolean)
    private val detailTasks = mutableMapOf<DetailKey, Deferred<SimpleOneRequest>>()

    fun rememberedStatusFilter(ownerUserId: String): ActiveRequestsStatusFilter = sessionStore.readStatusFilter(ownerUserId)
    fun saveStatusFilter(ownerUserId: String, filter: ActiveRequestsStatusFilter) = sessionStore.saveStatusFilter(ownerUserId, filter)

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

    suspend fun refresh(
        ownerUserId: String,
        cached: SimpleOneSnapshot?,
        onSnapshot: suspend (SimpleOneSnapshot, Set<String>) -> Unit = { _, _ -> },
    ): SimpleOneSnapshot {
        val session = storedSession(ownerUserId) ?: throw SimpleOneFailure.MissingCredentials
        return try {
            val fingerprint = simpleOneSessionFingerprint(session)
            val currentUser = cached?.takeIf { it.sessionFingerprint == fingerprint }?.currentUser ?: api.currentUser(session.authKey)
            val listed = api.activeRequests(currentUser.sysId, session.authKey)
            var snapshot = SimpleOneSnapshot(currentUser, listed, nowEpochMillis(), fingerprint)
            // Persist and publish the useful list before any detail request can delay it.
            withCurrentSession(ownerUserId, session) { cache.save(ownerUserId, snapshot) }
            val pending = listed.mapTo(mutableSetOf()) { it.id }
            withCurrentSession(ownerUserId, session) { onSnapshot(snapshot, pending.toSet()) }
            coroutineScope {
                val queue = Channel<IndexedValue<SimpleOneRequest>>(Channel.UNLIMITED)
                listed.forEachIndexed { index, request -> queue.trySend(IndexedValue(index, request)) }
                queue.close()
                val completed = Channel<Pair<Int, SimpleOneRequest>>(4)
                val workers = List(minOf(4, listed.size)) {
                    launch {
                        for ((index, request) in queue) {
                            val detailed = try { requestDetails(ownerUserId, request) }
                            catch (error: CancellationException) {
                                // A cancelled child launch does not fail coroutineScope by itself.
                                // Wake the collector rather than leaving an expected item missing.
                                completed.close(error)
                                throw error
                            }
                            catch (error: SimpleOneFailure.Unauthorized) { throw error }
                            catch (_: Exception) { request }
                            completed.send(index to detailed)
                        }
                    }
                }
                try {
                    val requests = listed.toMutableList()
                    repeat(listed.size) {
                        val (index, detailed) = completed.receive()
                        requests[index] = detailed
                        pending.remove(listed[index].id)
                        snapshot = snapshot.copy(activeRequests = requests.toList())
                        withCurrentSession(ownerUserId, session) { onSnapshot(snapshot, pending.toSet()) }
                    }
                    workers.forEach { it.join() }
                } finally { completed.cancel(); queue.cancel() }
            }
            withCurrentSession(ownerUserId, session) { cache.save(ownerUserId, snapshot) }
            snapshot
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
        scope: ClosedRequestsSyncScope = ClosedRequestsSyncScope.All,
        onProgress: (String) -> Unit = {},
    ): ClosedSimpleOneArchive = archiveMutex.withLock {
        val session = storedSession(ownerUserId) ?: throw SimpleOneFailure.MissingCredentials
        try {
            val currentUser = cachedSnapshot?.takeIf { it.sessionFingerprint == simpleOneSessionFingerprint(session) }?.currentUser ?: api.currentUser(session.authKey)
            val previous = supplementalCache.readClosedArchive(ownerUserId)
            val hasScopeCursor = when (scope) {
                ClosedRequestsSyncScope.Monthly -> previous?.monthlyCursor != null
                ClosedRequestsSyncScope.All -> previous?.wideCursor != null
            }
            val hasCompletedBaseline = hasScopeCursor || previous?.lastWideSyncAtEpochMillis != null
            if (previous?.isReady == true && hasCompletedBaseline && (previous.syncUserId == null || previous.syncUserId == currentUser.sysId)) {
                // Older/imported archives can have rows and a monthly cursor without a wide baseline.
                // A completed full baseline remains valid when optional heads were unavailable or empty:
                // retain its null cursor and scan safely rather than repeatedly regenerating XLSX.
                return@withLock updateClosedArchive(ownerUserId, session, currentUser.sysId,
                    previous.copy(syncRevision = 1, syncUserId = currentUser.sysId), scope, onProgress)
            }
            // As on iOS, capture the server heads before the full XLSX baseline, never from the device clock.
            val heads = coroutineScope {
                ClosedRequestsSyncScope.entries.map { syncScope -> async {
                    try { api.closedSyncHead(currentUser.sysId, session.authKey, syncScope) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (unauthorized: SimpleOneFailure.Unauthorized) { throw unauthorized }
                    catch (_: Exception) { null }
                } }.awaitAll()
            }
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
            onProgress("Проверяю поля архива: ${listed.size} заявок…")
            android.util.Log.i("SimpleOneSync", "baseline received=${listed.size} source=$source")
            val completed = completeClosedArchive(ownerUserId, session, currentUser.sysId, listed, onProgress)
            val records = withContext(Dispatchers.Default) {
                val existing = previous?.takeIf { it.syncUserId == null || it.syncUserId == currentUser.sysId }?.requests.orEmpty()
                val merged = existing.associateBy { it.number }.toMutableMap()
                completed.forEach { record ->
                    val old = merged[record.number]
                    val updated = if (old != null) record.mergeMissing(old) else record
                    var fields = old?.tableFields.orEmpty() + record.tableFields
                    val tin = closedMerchantTIN(record).ifBlank { old?.let(::closedMerchantTIN).orEmpty() }
                    if (tin.isNotBlank()) fields = fields + ("ИНН ТСП" to tin)
                    merged[record.number] = updated.copy(tableFields = fields)
                }
                merged.values.toList()
            }
            val archive = ClosedSimpleOneArchive(records, nowEpochMillis(), true, source, 2,
                syncUpdatedAt = heads[1]?.updatedAt, syncUserId = currentUser.sysId,
                monthlyCursor = heads[0], wideCursor = heads[1], syncRevision = 1,
                lastWideSyncAtEpochMillis = nowEpochMillis())
            supplementalCache.saveClosedArchive(ownerUserId, archive) { commit -> withCurrentSession(ownerUserId, session, commit) }
            android.util.Log.i("SimpleOneSync", "baseline saved=${archive.requests.size} monthly=${archive.monthlyCursor != null} wide=${archive.wideCursor != null}")
            archive
        } catch (error: SimpleOneFailure.Unauthorized) {
            sessionMutex.withLock {
                if (storedSession(ownerUserId) == session) withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) }
            }
            throw error
        }
    }

    private suspend fun updateClosedArchive(
        owner: String,
        session: StoredSimpleOneSession,
        userId: String,
        previous: ClosedSimpleOneArchive,
        scope: ClosedRequestsSyncScope,
        onProgress: (String) -> Unit,
    ): ClosedSimpleOneArchive {
        onProgress(if (scope == ClosedRequestsSyncScope.Monthly) "Проверяю текущий и прошлый месяцы…" else "Проверяю изменения архива…")
        val (knownVersions, recoveryCursor) = withContext(Dispatchers.Default) {
            val numbers = previous.requests.map { it.number }.toSet()
            val known = previous.syncRecords.filterValues { it.excluded || it.number in numbers }.mapValues { it.value.updatedAt }
            val missing = previous.syncRecords.filterValues { !it.excluded && it.number !in numbers }
                .map { (id, record) -> ClosedRequestsSyncCursor(record.updatedAt, id) }.minOrNull()
            known to missing
        }
        val batch = api.closedSyncChanges(userId, session.authKey, scope,
            recoveryCursor ?: if (scope == ClosedRequestsSyncScope.Monthly) previous.monthlyCursor else previous.wideCursor,
            knownVersions, onProgress)
        val archive = withContext(Dispatchers.Default) {
            val known = previous.requests.associateBy { it.number }
            val merged = known.toMutableMap()
            val metadata = previous.syncRecords.toMutableMap()
            val idsByNumber = metadata.entries.associate { it.value.number to it.key }.toMutableMap()
            for (change in batch.changes) {
                if (metadata[change.sysId]?.number?.let { it != change.number } == true ||
                    idsByNumber[change.number]?.let { it != change.sysId } == true ||
                    known[change.number]?.sysId?.takeIf(String::isNotBlank)?.let { it != change.sysId } == true) {
                    throw SimpleOneFailure.Http(409, "SimpleOne вернул конфликт идентификаторов для заявки ${change.number}. Архив не обновлён.")
                }
                change.request?.let { record ->
                    val oldFields = known[record.number]?.tableFields.orEmpty()
                    var fields = oldFields + record.tableFields
                    val tin = closedMerchantTIN(record).ifBlank { known[record.number]?.let(::closedMerchantTIN).orEmpty() }
                    if (tin.isNotBlank()) fields = fields + ("ИНН ТСП" to tin)
                    merged[record.number] = record.copy(tableFields = fields)
                }
                metadata[change.sysId] = ClosedRequestsSyncRecord(change.number, change.cursor.updatedAt, change.request == null)
                idsByNumber[change.number] = change.sysId
            }
            previous.copy(requests = if (merged == known) previous.requests else merged.values.toList(),
                updatedAtEpochMillis = nowEpochMillis(), syncRecords = metadata, syncUserId = userId,
                monthlyCursor = if (scope == ClosedRequestsSyncScope.Monthly) batch.cursor ?: previous.monthlyCursor else previous.monthlyCursor,
                wideCursor = if (scope == ClosedRequestsSyncScope.All) batch.cursor ?: previous.wideCursor else previous.wideCursor,
                syncUpdatedAt = if (scope == ClosedRequestsSyncScope.All) batch.cursor?.updatedAt ?: previous.syncUpdatedAt else previous.syncUpdatedAt,
                lastWideSyncAtEpochMillis = if (scope == ClosedRequestsSyncScope.All) nowEpochMillis() else previous.lastWideSyncAtEpochMillis)
        }
        supplementalCache.saveClosedArchive(owner, archive) { commit -> withCurrentSession(owner, session, commit) }
        android.util.Log.i("SimpleOneSync", "sync scope=$scope changes=${batch.changes.size} saved=${archive.requests.size}")
        return archive
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
            supplementalCache.saveClosedArchive(historyCacheScope(session, terminal), ClosedSimpleOneArchive(
                requests = records, updatedAtEpochMillis = nowEpochMillis(), isComplete = true, queryRevision = 2,
            )) { commit -> withCurrentSession(ownerUserId, session, commit) }
            return withCurrentSession(ownerUserId, session) { records }
        } catch (error: SimpleOneFailure.Unauthorized) {
            sessionMutex.withLock { if (storedSession(ownerUserId) == session) withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) } }
            throw error
        }
    }

    private fun historyCacheScope(session: StoredSimpleOneSession, terminalId: String): String =
        "${session.ownerUserId}|terminal-history-v2|${simpleOneSessionFingerprint(session)}|$terminalId"

    /** Optional card enrichment from local storage only; navigation must never start HTTP. */
    suspend fun cachedRequestDetails(ownerUserId: String, request: SimpleOneRequest): SimpleOneRequest? {
        val session = storedSession(ownerUserId) ?: return null
        val fingerprint = simpleOneSessionFingerprint(session)
        val entry = supplementalCache.readDetail(ownerUserId, request.id)
            ?.takeIf { it.matches(request, fingerprint, false) } ?: return null
        return withCurrentSession(ownerUserId, session) { entry.request.mergeMissing(request).copy(source = request.source) }
    }

    private fun SimpleOneDetailCacheEntry.matches(request: SimpleOneRequest, fingerprint: String, requiresCurrentVersion: Boolean): Boolean =
        sessionFingerprint == fingerprint &&
            (if (requiresCurrentVersion) request.sysUpdatedAt.isNotBlank() && request.sysUpdatedAt == this.request.sysUpdatedAt
            else request.sysUpdatedAt.isBlank() || request.sysUpdatedAt == this.request.sysUpdatedAt) &&
            this.request.state == request.state && this.request.stateRaw == request.stateRaw &&
            (request.multicardState.isBlank() || this.request.multicardState == request.multicardState) &&
            this.request.deadline == request.deadline

    suspend fun requestDetails(ownerUserId: String, request: SimpleOneRequest, force: Boolean = false, requiresCurrentVersion: Boolean = false): SimpleOneRequest {
        val session = storedSession(ownerUserId)
            ?: if (requiresCurrentVersion) throw SimpleOneFailure.MissingCredentials else return request
        val fingerprint = simpleOneSessionFingerprint(session)
        val task = detailMutex.withLock {
            val stored = supplementalCache.readDetail(ownerUserId, request.id)
            val cached = stored?.takeIf { it.matches(request, fingerprint, requiresCurrentVersion) }
            if (!force && cached != null && nowEpochMillis() - cached.cachedAtEpochMillis in 0 until DETAIL_CACHE_LIFETIME_MILLIS) {
                return withCurrentSession(ownerUserId, session) { cached.request.mergeMissing(request).copy(source = request.source) }
            }

            // Version is authoritative; for records without a version include all list fields.
            val identity = if (request.sysUpdatedAt.isNotBlank()) SimpleOneRequest(request.sysId, "", sysUpdatedAt = request.sysUpdatedAt) else request
            val key = DetailKey(ownerUserId, fingerprint, identity, requiresCurrentVersion && request.sysUpdatedAt.isBlank())
            detailTasks[key] ?: detailScope.async(start = CoroutineStart.LAZY) {
                try {
                    val detailed = api.requestDetails(request, session.authKey).mergeMissing(request)
                    if (detailed.id != request.id || detailed.number != request.number) throw SimpleOneFailure.InvalidResponse
                    supplementalCache.saveDetail(ownerUserId, SimpleOneDetailCacheEntry(detailed, nowEpochMillis(), fingerprint)) {
                        commit -> withCurrentSession(ownerUserId, session, commit)
                    }
                    detailed
                } catch (error: SimpleOneFailure.Unauthorized) {
                    sessionMutex.withLock {
                        if (storedSession(ownerUserId) == session) withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) }
                    }
                    throw error
                } catch (error: CancellationException) { throw error
                } catch (error: Exception) {
                    withCurrentSession(ownerUserId, session) { cached?.request ?: throw error }
                } finally {
                    val taskJob = currentCoroutineContext()[Job]
                    withContext(NonCancellable) {
                        detailMutex.withLock { if (detailTasks[key] === taskJob) detailTasks.remove(key) }
                    }
                }
            }.also { detailTasks[key] = it; it.start() }
        }
        // A card leaving composition only cancels its wait, never the shared network request.
        val detailed = task.await()
        return withCurrentSession(ownerUserId, session) { detailed.mergeMissing(request).copy(source = request.source) }
    }

    suspend fun signOut(ownerUserId: String) {
        sessionMutex.withLock {
            generations[ownerUserId] = (generations[ownerUserId] ?: 0L) + 1
            withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) }
        }
        detailMutex.withLock { detailTasks.filterKeys { it.owner == ownerUserId }.values.forEach { it.cancel() } }
    }

    companion object {
        private const val DETAIL_CACHE_LIFETIME_MILLIS = 24L * 60 * 60 * 1_000
    }
}

internal fun simpleOneSessionFingerprint(session: StoredSimpleOneSession): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest("${session.ownerUserId.length}:${session.ownerUserId}${session.authKey}".toByteArray()).joinToString("") { "%02x".format(it) }

internal fun closedSyncTimestamp(value: String): String? {
    if (!value.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"))) return null
    return runCatching {
        java.time.LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT))
        value
    }.getOrNull()
}
