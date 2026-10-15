package ru.lumastack.lumawork.data.wiki

import ru.lumastack.lumawork.domain.wiki.WikiArticle
import ru.lumastack.lumawork.domain.wiki.WikiSearchSnapshot

class WikiRepository(
    private val api: WikiApi,
    private val cache: WikiCache,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun readSearch(ownerUserId: String): WikiSearchSnapshot? = cache.readSearch(ownerUserId)

    suspend fun search(ownerUserId: String, query: String): WikiSearchSnapshot = WikiSearchSnapshot(
        query = query,
        results = api.search(query),
        updatedAtEpochMillis = nowEpochMillis(),
    ).also { cache.saveSearch(ownerUserId, it) }

    suspend fun readArticle(articleId: String): WikiArticle? = cache.readArticle(articleId)

    suspend fun refreshArticle(articleId: String): WikiArticle {
        val article = api.article(articleId)
        cache.saveArticle(article)
        return article
    }

    suspend fun isPdfAvailable(articleId: String): Boolean = api.isPdfAvailable(articleId)
    fun pdfUrl(articleId: String): String = api.pdfUrl(articleId)
}
