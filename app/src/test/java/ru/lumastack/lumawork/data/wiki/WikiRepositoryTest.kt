package ru.lumastack.lumawork.data.wiki

import ru.lumastack.lumawork.data.simpleone.testSimpleOneAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import ru.lumastack.lumawork.domain.wiki.WikiArticle
import ru.lumastack.lumawork.domain.wiki.WikiSearchResult
import ru.lumastack.lumawork.domain.wiki.WikiSearchSnapshot

class WikiRepositoryTest {
    @Test fun `Wiki denies cached search article and PDF without SO`() = runBlocking {
        val repository = WikiRepository(FakeWikiApi(), FakeWikiCache())
        org.junit.Assert.assertTrue(runCatching { repository.readSearch("owner") }.isFailure)
        org.junit.Assert.assertTrue(runCatching { repository.readArticle("1") }.isFailure)
        org.junit.Assert.assertTrue(runCatching { repository.search("owner", "secret") }.isFailure)
        org.junit.Assert.assertTrue(runCatching { repository.pdf("1") }.isFailure)
    }

    @Test
    fun `health returns the server snapshot version without requiring a token or using service updatedAt`() = runBlocking {
        listOf(
            """{"snapshotId":"20261022T234850Z","contentVersion":"legacy","updatedAt":"2026-10-23T11:34:59Z","articles":631}""" to "20261022T234850Z",
            """{"contentVersion":"20261022T234850Z"}""" to "20261022T234850Z",
            """{"updatedAt":"2026-10-23T11:34:59Z"}""" to null,
        ).forEach { (body, expected) ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals("/wiki/health", request.url.encodedPath)
                assertEquals(null, request.header("Authorization"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val api = OkHttpWikiApi("https://example.com/wiki", { "" }, Json { ignoreUnknownKeys = true }, client)
            assertEquals(expected, api.catalogVersion())
        }
    }

    @Test
    fun `catalog refresh saves the server version without replacing cached search data`() = runBlocking {
        val cache = FakeWikiCache()
        val result = WikiSearchResult("1", "VPN", "Сеть", "https://example.com", 9, "Инструкция")
        cache.searches["owner"] = WikiSearchSnapshot("VPN", listOf(result), 123L)
        val repository = WikiRepository(FakeWikiApi(version = "20261022T234850Z"), cache, access = testSimpleOneAccess())

        assertEquals("20261022T234850Z", repository.refreshCatalogVersion("owner"))
        assertEquals("20261022T234850Z", repository.readSearch("owner")?.snapshotId)
        assertEquals(listOf(result), repository.readSearch("owner")?.results)
        assertEquals(123L, repository.readSearch("owner")?.updatedAtEpochMillis)
        org.junit.Assert.assertTrue(runCatching { repository.readSearch("another-owner") }.isFailure)
    }

    @Test
    fun `new search preserves the last server catalog version`() = runBlocking {
        val cache = FakeWikiCache()
        val repository = WikiRepository(FakeWikiApi(version = "20261022T234850Z"), cache, access = testSimpleOneAccess()) { 999L }
        repository.refreshCatalogVersion("owner")

        val snapshot = repository.search("owner", "PinPad")

        assertEquals("20261022T234850Z", snapshot.snapshotId)
        assertEquals(999L, snapshot.updatedAtEpochMillis)
    }

    @Test
    fun `search saves scoped snapshot`() = runBlocking {
        val result = WikiSearchResult("1", "VPN", "Сеть", "https://example.com", 9, "Инструкция")
        val cache = FakeWikiCache()
        val repository = WikiRepository(FakeWikiApi(results = listOf(result)), cache, access = testSimpleOneAccess()) { 123L }

        val snapshot = repository.search("owner", "VPN")

        assertEquals("VPN", snapshot.query)
        assertEquals(123L, snapshot.updatedAtEpochMillis)
        assertEquals(listOf(result), cache.searches["owner"]?.results)
    }

    @Test
    fun `article refresh persists exact server article`() = runBlocking {
        val article = WikiArticle("1", "VPN", "Сеть", "https://example.com", content = "Текст")
        val cache = FakeWikiCache()
        val repository = WikiRepository(FakeWikiApi(article = article), cache, access = testSimpleOneAccess())

        val loaded = repository.refreshArticle("1")

        assertSame(article, loaded)
        assertEquals(article, repository.readArticle("1"))
    }
}

private class FakeWikiApi(
    private val results: List<WikiSearchResult> = emptyList(),
    private val article: WikiArticle? = null,
    private val version: String? = null,
) : WikiApi {
    override suspend fun catalogVersion(): String? = version
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
