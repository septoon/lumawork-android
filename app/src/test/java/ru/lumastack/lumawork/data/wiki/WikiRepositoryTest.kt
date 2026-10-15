package ru.lumastack.lumawork.data.wiki

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import ru.lumastack.lumawork.domain.wiki.WikiArticle
import ru.lumastack.lumawork.domain.wiki.WikiSearchResult
import ru.lumastack.lumawork.domain.wiki.WikiSearchSnapshot

class WikiRepositoryTest {
    @Test
    fun `search saves scoped snapshot`() = runBlocking {
        val result = WikiSearchResult("1", "VPN", "Сеть", "https://example.com", 9, "Инструкция")
        val cache = FakeWikiCache()
        val repository = WikiRepository(FakeWikiApi(results = listOf(result)), cache) { 123L }

        val snapshot = repository.search("owner", "VPN")

        assertEquals("VPN", snapshot.query)
        assertEquals(123L, snapshot.updatedAtEpochMillis)
        assertEquals(listOf(result), cache.searches["owner"]?.results)
    }

    @Test
    fun `article refresh persists exact server article`() = runBlocking {
        val article = WikiArticle("1", "VPN", "Сеть", "https://example.com", content = "Текст")
        val cache = FakeWikiCache()
        val repository = WikiRepository(FakeWikiApi(article = article), cache)

        val loaded = repository.refreshArticle("1")

        assertSame(article, loaded)
        assertEquals(article, repository.readArticle("1"))
    }
}

private class FakeWikiApi(
    private val results: List<WikiSearchResult> = emptyList(),
    private val article: WikiArticle? = null,
) : WikiApi {
    override suspend fun search(query: String, limit: Int): List<WikiSearchResult> = results
    override suspend fun article(id: String, maxChars: Int): WikiArticle = requireNotNull(article)
    override suspend fun isPdfAvailable(id: String): Boolean = false
    override fun pdfUrl(id: String): String = "https://example.com/pdf/$id"
}

private class FakeWikiCache : WikiCache {
    val searches = mutableMapOf<String, WikiSearchSnapshot>()
    val articles = mutableMapOf<String, WikiArticle>()
    override suspend fun readSearch(ownerUserId: String): WikiSearchSnapshot? = searches[ownerUserId]
    override suspend fun saveSearch(ownerUserId: String, snapshot: WikiSearchSnapshot) { searches[ownerUserId] = snapshot }
    override suspend fun readArticle(articleId: String): WikiArticle? = articles[articleId]
    override suspend fun saveArticle(article: WikiArticle) { articles[article.id] = article }
}
