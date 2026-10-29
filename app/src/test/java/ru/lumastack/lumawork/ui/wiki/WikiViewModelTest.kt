package ru.lumastack.lumawork.ui.wiki

import ru.lumastack.lumawork.data.simpleone.testSimpleOneAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.data.wiki.*
import ru.lumastack.lumawork.domain.wiki.*

@OptIn(ExperimentalCoroutinesApi::class)
class WikiViewModelTest {
    @Test fun `offline catalog info restores the server date without network access`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = WikiTestApi()
            val model = WikiViewModel(WikiRepository(api, WikiTestCache("20261022T234850Z"), access = testSimpleOneAccess()), "owner", true)
            advanceUntilIdle()
            model.refreshCatalogVersion(); advanceUntilIdle()
            assertEquals("20261022T234850Z", model.state.value.snapshotId)
            assertEquals(0, api.calls)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `catalog info loads server date independently of a failing search`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val model = WikiViewModel(WikiRepository(WikiTestApi("20261022T234850Z"), WikiTestCache(), access = testSimpleOneAccess()), "owner", false)
            advanceUntilIdle()
            assertEquals("20261022T234850Z", model.state.value.snapshotId)
            assertTrue(model.state.value.results.isNotEmpty())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `catalog failure preserves the cached server date`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val model = WikiViewModel(WikiRepository(WikiTestApi(), WikiTestCache("20261022T234850Z"), access = testSimpleOneAccess()), "owner", false)
            advanceUntilIdle()
            assertEquals("20261022T234850Z", model.state.value.snapshotId)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `offline search and article use cache without API access`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = WikiTestApi(); val cache = WikiTestCache()
            val model = WikiViewModel(WikiRepository(api, cache, access = testSimpleOneAccess()), "owner", true)
            advanceUntilIdle(); model.open(cache.result); advanceUntilIdle()
            assertEquals("Cached content", model.state.value.article?.content)
            model.updateQuery("terminal"); advanceUntilIdle()
            assertEquals(0, api.calls)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `transient error preserves cached results and reports notice`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = WikiTestApi(); val cache = WikiTestCache()
            val model = WikiViewModel(WikiRepository(api, cache, access = testSimpleOneAccess()), "owner", false)
            advanceUntilIdle()
            assertEquals(listOf(cache.result), model.state.value.results)
            assertNotNull(model.state.value.notice)
        } finally { Dispatchers.resetMain() }
    }
}
private class WikiTestApi(private val version: String? = null) : WikiApi {
    var calls = 0
    override suspend fun catalogVersion(): String? { calls++; return version ?: throw WikiFailure.InvalidResponse }
    override suspend fun search(query: String, limit: Int): List<WikiSearchResult> { calls++; throw WikiFailure.InvalidResponse }
    override suspend fun article(id: String, maxChars: Int): WikiArticle { calls++; throw WikiFailure.InvalidResponse }
    override suspend fun isPdfAvailable(id: String): Boolean { calls++; return false }
    override fun pdfUrl(id: String) = "https://example.com"
}
private class WikiTestCache(private val version: String? = null) : WikiCache {
    val result = WikiSearchResult("article", "Title", "Section", "https://example.com", 1, "excerpt")
    override suspend fun readSearch(ownerUserId: String) = WikiSearchSnapshot("инструкция", listOf(result), snapshotId = version)
    override suspend fun saveSearch(ownerUserId: String, snapshot: WikiSearchSnapshot) {}
    override suspend fun readArticle(articleId: String) = WikiArticle(articleId, "Title", "Section", "https://example.com", content = "Cached content")
    override suspend fun saveArticle(article: WikiArticle) {}
}
