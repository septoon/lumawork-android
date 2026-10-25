package ru.lumastack.lumawork.data.wiki

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.lumastack.lumawork.domain.wiki.WikiArticle
import ru.lumastack.lumawork.domain.wiki.WikiSearchSnapshot

class WikiRepository(
    private val api: WikiApi,
    private val cache: WikiCache,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val searchCacheMutex = Mutex()

    suspend fun readSearch(ownerUserId: String): WikiSearchSnapshot? = searchCacheMutex.withLock { cache.readSearch(ownerUserId) }

    suspend fun search(ownerUserId: String, query: String): WikiSearchSnapshot {
        val results = api.search(query)
        return searchCacheMutex.withLock {
            WikiSearchSnapshot(query, results, nowEpochMillis(), cache.readSearch(ownerUserId)?.snapshotId)
                .also { cache.saveSearch(ownerUserId, it) }
        }
    }

    suspend fun refreshCatalogVersion(ownerUserId: String): String? {
        val version = api.catalogVersion()?.takeIf(String::isNotBlank) ?: return null
        searchCacheMutex.withLock {
            val cached = cache.readSearch(ownerUserId) ?: WikiSearchSnapshot("инструкция", emptyList())
            cache.saveSearch(ownerUserId, cached.copy(snapshotId = version))
        }
        return version
    }

    suspend fun readArticle(articleId: String): WikiArticle? = cache.readArticle(articleId)

    suspend fun refreshArticle(articleId: String): WikiArticle {
        val article = api.article(articleId)
        cache.saveArticle(article)
        return article
    }

    suspend fun isPdfAvailable(articleId: String): Boolean = api.isPdfAvailable(articleId)
    fun pdfUrl(articleId: String): String = api.pdfUrl(articleId)
}
