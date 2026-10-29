package ru.lumastack.lumawork.data.wiki

import ru.lumastack.lumawork.data.simpleone.SimpleOneAccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lumastack.lumawork.domain.wiki.WikiArticle
import ru.lumastack.lumawork.domain.wiki.WikiSearchSnapshot

class WikiRepository(
    private val api: WikiApi,
    private val cache: WikiCache,
    private val access: SimpleOneAccess = SimpleOneAccess.DenyAll,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val searchCacheMutex = Mutex()

    suspend fun readSearch(ownerUserId: String): WikiSearchSnapshot? = access.protect(ownerUserId) { searchCacheMutex.withLock { cache.readSearch(ownerUserId) } }

    suspend fun search(ownerUserId: String, query: String): WikiSearchSnapshot = access.protect(ownerUserId) {
        val results = api.search(query)
        searchCacheMutex.withLock {
            access.require(ownerUserId)
            WikiSearchSnapshot(query, results, nowEpochMillis(), cache.readSearch(ownerUserId)?.snapshotId)
                .also { cache.saveSearch(ownerUserId, it) }
        }
    }

    suspend fun refreshCatalogVersion(ownerUserId: String): String? = access.protect(ownerUserId) {
        val version = api.catalogVersion()?.takeIf(String::isNotBlank) ?: return@protect null
        searchCacheMutex.withLock {
            val cached = cache.readSearch(ownerUserId) ?: WikiSearchSnapshot("инструкция", emptyList())
            cache.saveSearch(ownerUserId, cached.copy(snapshotId = version))
        }
        version
    }

    suspend fun readArticle(articleId: String): WikiArticle? = access.protect(access.requireCurrent().ownerUserId) { cache.readArticle(articleId) }

    suspend fun refreshArticle(articleId: String): WikiArticle = access.protect(access.requireCurrent().ownerUserId) {
        val article = api.article(articleId)
        access.requireCurrent()
        cache.saveArticle(article)
        article
    }

    suspend fun isPdfAvailable(articleId: String): Boolean = access.protect(access.requireCurrent().ownerUserId) { api.isPdfAvailable(articleId) }
    suspend fun pdf(articleId: String): ByteArray = access.protect(access.requireCurrent().ownerUserId) { api.pdf(articleId) }
}
