package ru.lumastack.lumawork.data.backpack

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lumastack.lumawork.data.simpleone.SimpleOneApi
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.simpleone.SimpleOneSessionStore
import ru.lumastack.lumawork.domain.backpack.*

class BackpackRepository(
    private val api: SimpleOneApi,
    private val sessionStore: SimpleOneSessionStore,
    private val cache: BackpackCache,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val cacheMutex = Mutex()
    suspend fun read(ownerUserId: String, simpleOneUserId: String): BackpackSnapshot? =
        cache.read(ownerUserId)?.takeIf { it.simpleOneUserId == simpleOneUserId }

    suspend fun refresh(ownerUserId: String, simpleOneUserId: String): BackpackSnapshot {
        val items = authorized(ownerUserId) { api.backpackList(it) }
        currentCoroutineContext().ensureActive()
        return update(ownerUserId, simpleOneUserId) { previous ->
            val cached = previous.items.associateBy(BackpackItem::id)
            previous.copy(items = items.map { it.preserveDetails(cached[it.id]) }, updatedAtEpochMillis = nowEpochMillis())
        }
    }

    suspend fun hydrate(owner: String, user: String, snapshot: BackpackSnapshot, force: Boolean, onProgress: (BackpackSnapshot, Set<String>) -> Unit) {
        val pending = snapshot.items.filter { it.needsDetails(nowEpochMillis(), force) }
        val remaining = pending.map(BackpackItem::id).toMutableSet()
        if (pending.isEmpty()) return
        onProgress(snapshot, remaining.toSet())
        for (chunk in pending.chunked(4)) {
            val detailed = authorized(owner) { key -> coroutineScope {
                chunk.map { item -> async {
                    try { api.backpackItemDetails(item, key).copy(detailsFetchedAtEpochMillis = nowEpochMillis()) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (unauthorized: SimpleOneFailure.Unauthorized) { throw unauthorized }
                    catch (_: Exception) { item }
                } }.awaitAll()
            } }
            currentCoroutineContext().ensureActive()
            val byId = detailed.associateBy(BackpackItem::id)
            val updated = update(owner, user) { current -> current.copy(items = current.items.map { byId[it.id] ?: it }) }
            remaining.removeAll(byId.keys)
            onProgress(updated, remaining.toSet())
        }
    }

    suspend fun updateLocation(owner: String, user: String, item: BackpackItem, location: BackpackLocation) =
        update(owner, user) { it.copy(locationOverrides = it.locationOverrides + (item.storageIdentity to location)) }

    suspend fun refreshReturns(owner: String, user: String): BackpackSnapshot {
        val requests = authorized(owner) { key ->
            val listed = api.returnEquipmentRequests(user, key)
            listed.chunked(4).flatMap { chunk -> coroutineScope {
                chunk.map { request -> async {
                    if (returnEquipmentSerials(listOf(request)).isNotEmpty()) request else try { api.requestDetails(request, key) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (unauthorized: SimpleOneFailure.Unauthorized) { throw unauthorized }
                    catch (_: Exception) { request }
                } }.awaitAll()
            } }
        }
        currentCoroutineContext().ensureActive()
        return update(owner, user) { it.copy(returnSerialNumbers = returnEquipmentSerials(requests), returnUpdatedAtEpochMillis = nowEpochMillis()) }
    }

    suspend fun refreshOffice(owner: String, user: String): BackpackSnapshot {
        val items = authorized(owner) { api.officeEquipment(it) }
        currentCoroutineContext().ensureActive()
        return update(owner, user) { it.copy(officeEquipment = items, officeUpdatedAtEpochMillis = nowEpochMillis()) }
    }

    suspend fun officeDetails(owner: String, user: String, item: OfficeEquipmentItem): OfficeEquipmentItem {
        val detailed = authorized(owner) { api.officeEquipmentDetails(item, it) }
        update(owner, user) { current -> current.copy(officeEquipment = current.officeEquipment.map { if (it.id == detailed.id) detailed else it }) }
        return detailed
    }

    private suspend fun update(owner: String, user: String, transform: (BackpackSnapshot) -> BackpackSnapshot): BackpackSnapshot = cacheMutex.withLock {
        currentCoroutineContext().ensureActive()
        val previous = read(owner, user) ?: BackpackSnapshot(user)
        transform(previous).also { cache.save(owner, it) }
    }

    private suspend fun <T> authorized(owner: String, action: suspend (String) -> T): T {
        val key = sessionStore.read(owner)?.authKey ?: throw SimpleOneFailure.MissingCredentials
        return try { action(key) }
        catch (unauthorized: SimpleOneFailure.Unauthorized) {
            withContext(Dispatchers.IO) { sessionStore.clear(owner) }
            throw unauthorized
        }
    }
}
