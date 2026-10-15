package ru.lumastack.lumawork.data.coordination

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.simpleone.SimpleOneApi
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.simpleone.SimpleOneSessionStore
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion
import ru.lumastack.lumawork.domain.coordination.CoordinationRequestGroup
import ru.lumastack.lumawork.domain.coordination.CoordinationSnapshot
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

class CoordinationRepository(
    private val api: SimpleOneApi,
    private val sessionStore: SimpleOneSessionStore,
    private val cache: CoordinationCache,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun read(ownerUserId: String, simpleOneUserId: String): CoordinationSnapshot? =
        cache.read(ownerUserId)?.takeIf { it.simpleOneUserId == simpleOneUserId }

    suspend fun refreshRegion(
        ownerUserId: String,
        simpleOneUserId: String,
        region: CoordinationRegion,
    ): CoordinationSnapshot {
        val authKey = sessionStore.read(ownerUserId)?.authKey ?: throw SimpleOneFailure.MissingCredentials
        return try {
            val previous = read(ownerUserId, simpleOneUserId) ?: CoordinationSnapshot(simpleOneUserId)
            val cachedRequests = previous.regions[region.name]?.requests.orEmpty()
            val requests = preserveDetails(
                fetched = api.coordinationRequests(region, authKey),
                cached = cachedRequests,
            )
            previous.copy(
                regions = previous.regions + (
                    region.name to CoordinationRequestGroup(requests, nowEpochMillis())
                ),
            ).also { cache.save(ownerUserId, it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimpleOneFailure.Unauthorized) {
            withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) }
            throw error
        }
    }

    suspend fun refreshReturnEquipment(
        ownerUserId: String,
        simpleOneUserId: String,
    ): CoordinationSnapshot {
        val authKey = sessionStore.read(ownerUserId)?.authKey ?: throw SimpleOneFailure.MissingCredentials
        return try {
            val previous = read(ownerUserId, simpleOneUserId) ?: CoordinationSnapshot(simpleOneUserId)
            val requests = preserveDetails(
                fetched = api.returnEquipmentRequests(simpleOneUserId, authKey),
                cached = previous.returnEquipment?.requests.orEmpty(),
            )
            previous.copy(
                returnEquipment = CoordinationRequestGroup(requests, nowEpochMillis()),
            ).also { cache.save(ownerUserId, it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimpleOneFailure.Unauthorized) {
            withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) }
            throw error
        }
    }

    suspend fun replaceRequest(
        ownerUserId: String,
        simpleOneUserId: String,
        request: SimpleOneRequest,
    ): CoordinationSnapshot? {
        val snapshot = read(ownerUserId, simpleOneUserId) ?: return null
        val updated = snapshot.copy(
            regions = snapshot.regions.mapValues { (_, group) ->
                group.copy(requests = group.requests.replace(request))
            },
            returnEquipment = snapshot.returnEquipment?.let { group ->
                group.copy(requests = group.requests.replace(request))
            },
        )
        cache.save(ownerUserId, updated)
        return updated
    }

    private fun List<SimpleOneRequest>.replace(request: SimpleOneRequest): List<SimpleOneRequest> =
        map { if (it.id == request.id) request else it }

    private fun preserveDetails(
        fetched: List<SimpleOneRequest>,
        cached: List<SimpleOneRequest>,
    ): List<SimpleOneRequest> {
        val cachedById = cached.associateBy(SimpleOneRequest::id)
        return fetched.map { fresh ->
            val old = cachedById[fresh.id] ?: return@map fresh
            fresh.copy(
                waitingReason = fresh.waitingReason.ifBlank { old.waitingReason },
                initiator = fresh.initiator.ifBlank { old.initiator },
                priority = fresh.priority.ifBlank { old.priority },
                closureCode = fresh.closureCode.ifBlank { old.closureCode },
                resolution = fresh.resolution.ifBlank { old.resolution },
                installedFiscalStorageSerialNumber = fresh.installedFiscalStorageSerialNumber.ifBlank {
                    old.installedFiscalStorageSerialNumber
                },
                ofdTariffActivationCode = fresh.ofdTariffActivationCode.ifBlank { old.ofdTariffActivationCode },
                usedSimCard = fresh.usedSimCard.ifBlank { old.usedSimCard },
                tableFields = if (fresh.tableFields.isEmpty()) old.tableFields else old.tableFields + fresh.tableFields,
            )
        }
    }
}
