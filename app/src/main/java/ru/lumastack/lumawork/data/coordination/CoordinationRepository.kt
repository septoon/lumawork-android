package ru.lumastack.lumawork.data.coordination

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.simpleone.*
import ru.lumastack.lumawork.domain.coordination.*
import ru.lumastack.lumawork.domain.requests.normalizedClosedRequestsText
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

class CoordinationRepository(
    private val api: SimpleOneApi,
    private val sessionStore: SimpleOneSessionStore,
    private val cache: CoordinationCache,
    val groupClosedRepository: CoordinationGroupClosedRequestsRepository? = null,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val loads = mutableMapOf<String, Long>()
    suspend fun read(ownerUserId: String, simpleOneUserId: String): CoordinationSnapshot? {
        val session = session(ownerUserId) ?: return null
        return cache.read(ownerUserId)?.takeIf { it.simpleOneUserId == simpleOneUserId &&
            it.sessionFingerprint == coordinationSessionFingerprint(session.authKey, simpleOneUserId) }
    }
    suspend fun refreshRegion(ownerUserId: String, simpleOneUserId: String, region: CoordinationRegion): CoordinationSnapshot =
        refresh(ownerUserId, simpleOneUserId, region.name) { auth -> api.coordinationRequests(region, auth) }
    suspend fun refreshReturnEquipment(ownerUserId: String, simpleOneUserId: String): CoordinationSnapshot =
        refresh(ownerUserId, simpleOneUserId, "return") { auth -> api.returnEquipmentRequests(simpleOneUserId, auth) }

    private suspend fun refresh(owner: String, user: String, scope: String, fetch: suspend (String) -> List<SimpleOneRequest>): CoordinationSnapshot {
        val session = session(owner) ?: throw SimpleOneFailure.MissingCredentials
        val key = "$owner:$user:$scope"
        val generation = mutex.withLock { ((loads[key] ?: 0) + 1).also { loads[key] = it } }
        try {
            val fetched = fetch(session.authKey)
            return mutex.withLock {
                checkSession(owner, session)
                if (loads[key] != generation) throw CancellationException("Newer coordination refresh")
                val previous = read(owner, user) ?: emptySnapshot(user, session)
                val oldGroup = if (scope == "return") previous.returnEquipment else previous.regions[scope]
                val group = CoordinationRequestGroup(preserveDetails(fetched, oldGroup?.requests.orEmpty()), nowEpochMillis())
                val updated = if (scope == "return") previous.copy(returnEquipment = group) else previous.copy(regions = previous.regions + (scope to group))
                updated.copy(revision = previous.revision + 1).also { cache.save(owner, it) }
            }
        } catch (error: SimpleOneFailure.Unauthorized) {
            mutex.withLock { if (session(owner) == session) withContext(Dispatchers.IO) { sessionStore.clear(owner) } }
            throw error
        }
    }
    suspend fun selectRegion(owner: String, user: String, region: CoordinationRegion): CoordinationSnapshot = mutex.withLock {
        val session = session(owner) ?: throw SimpleOneFailure.MissingCredentials
        val previous = read(owner, user) ?: emptySnapshot(user, session)
        previous.copy(selectedRegion = region, revision = previous.revision + 1).also { cache.save(owner, it) }
    }
    suspend fun replaceRequest(ownerUserId: String, simpleOneUserId: String, request: SimpleOneRequest, expectedVersion: String? = null): CoordinationSnapshot? =
        replaceRequests(ownerUserId, simpleOneUserId, listOf(request), expectedVersion?.let { mapOf(request.id to it) }.orEmpty())
    suspend fun replaceRequests(ownerUserId: String, simpleOneUserId: String, requests: List<SimpleOneRequest>, expectedVersions: Map<String, String>): CoordinationSnapshot? = mutex.withLock {
        val session = session(ownerUserId) ?: return@withLock null
        val snapshot = read(ownerUserId, simpleOneUserId) ?: return@withLock null
        val replacements = requests.associateBy { it.id }
        fun List<SimpleOneRequest>.replace() = map {
            val replacement = replacements[it.id]
            if (replacement != null && (it.id !in expectedVersions || it.sysUpdatedAt == expectedVersions[it.id])) replacement else it
        }
        checkSession(ownerUserId, session)
        snapshot.copy(regions = snapshot.regions.mapValues { (_, group) -> group.copy(requests = group.requests.replace()) },
            returnEquipment = snapshot.returnEquipment?.let { it.copy(requests = it.requests.replace()) }, revision = snapshot.revision + 1)
            .also { cache.save(ownerUserId, it); checkSession(ownerUserId, session) }
    }
    private suspend fun session(owner: String) = withContext(Dispatchers.IO) { sessionStore.read(owner) }
    private suspend fun checkSession(owner: String, captured: StoredSimpleOneSession) {
        currentCoroutineContext().ensureActive()
        if (session(owner) != captured) throw CancellationException("SimpleOne session changed")
    }
    private fun emptySnapshot(user: String, session: StoredSimpleOneSession) = CoordinationSnapshot(user,
        sessionFingerprint = coordinationSessionFingerprint(session.authKey, user))
    private fun preserveDetails(fetched: List<SimpleOneRequest>, cached: List<SimpleOneRequest>): List<SimpleOneRequest> {
        val oldById = cached.associateBy { it.id }
        return fetched.distinctBy { it.id }.map { fresh ->
            val old = oldById[fresh.id]
            if (old == null || fresh.sysUpdatedAt.isBlank() || fresh.sysUpdatedAt != old.sysUpdatedAt) fresh else {
                val fields = fresh.tableFields.toMutableMap()
                val keys = fields.keys.associateBy(::normalizedClosedRequestsText)
                old.tableFields.forEach { (key, value) ->
                    val normalized = normalizedClosedRequestsText(key)
                    val present = keys[normalized]
                    if (normalized in DETAIL_KEYS && value.isNotBlank() && fields[present].isNullOrBlank()) fields[present ?: key] = value
                }
                fresh.copy(waitingReason = fresh.waitingReason.ifBlank { old.waitingReason }, initiator = fresh.initiator.ifBlank { old.initiator },
                    priority = fresh.priority.ifBlank { old.priority }, closureCode = fresh.closureCode.ifBlank { old.closureCode },
                    resolution = fresh.resolution.ifBlank { old.resolution },
                    installedFiscalStorageSerialNumber = fresh.installedFiscalStorageSerialNumber.ifBlank { old.installedFiscalStorageSerialNumber },
                    ofdTariffActivationCode = fresh.ofdTariffActivationCode.ifBlank { old.ofdTariffActivationCode }, usedSimCard = fresh.usedSimCard.ifBlank { old.usedSimCard }, tableFields = fields)
            }
        }
    }
    companion object {
        private val DETAIL_KEYS = listOf("ID СБП", "Код закрытия", "Решение", "Дата закрытия в МК", "Оборудование POS", "Терминал вендора POS",
            "Номер принятого оборудования POS", "Оборудование Pin Pad", "Терминал вендора PIN", "Модель PIN-Pad", "Номер принятого оборудования PIN",
            "Серийный номер демонтируемого ТО", "Серийный номер демонтируемого PIN", "Производитель устанавливаемого ТО", "Модель устанавливаемого ТО",
            "Тип устанавливаемого ТО", "Принадлежность оборудования по заявке", "Время создания в МК", "МК Сотрудник склада", "Город склада").map(::normalizedClosedRequestsText).toSet()
    }
}
internal fun coordinationSessionFingerprint(auth: String, user: String): String = MessageDigest.getInstance("SHA-256")
    .digest("${user.length}:$user$auth".toByteArray()).joinToString("") { "%02x".format(it) }
