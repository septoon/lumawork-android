package ru.lumastack.lumawork.data.backpack

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.simpleone.SimpleOneApi
import ru.lumastack.lumawork.data.simpleone.SimpleOneFailure
import ru.lumastack.lumawork.data.simpleone.SimpleOneSessionStore
import ru.lumastack.lumawork.domain.backpack.BackpackSnapshot

class BackpackRepository(
    private val api: SimpleOneApi,
    private val sessionStore: SimpleOneSessionStore,
    private val cache: BackpackCache,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun read(ownerUserId: String, simpleOneUserId: String): BackpackSnapshot? =
        cache.read(ownerUserId)?.takeIf { it.simpleOneUserId == simpleOneUserId }

    suspend fun refresh(ownerUserId: String, simpleOneUserId: String): BackpackSnapshot {
        val authKey = sessionStore.read(ownerUserId)?.authKey ?: throw SimpleOneFailure.MissingCredentials
        return try {
            BackpackSnapshot(
                simpleOneUserId = simpleOneUserId,
                items = api.backpackItems(authKey),
                updatedAtEpochMillis = nowEpochMillis(),
            ).also { cache.save(ownerUserId, it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimpleOneFailure.Unauthorized) {
            withContext(Dispatchers.IO) { sessionStore.clear(ownerUserId) }
            throw error
        }
    }
}
