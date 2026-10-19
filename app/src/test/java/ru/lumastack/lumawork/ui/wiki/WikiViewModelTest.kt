package ru.lumastack.lumawork.ui.wiki

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.data.wiki.*
import ru.lumastack.lumawork.domain.wiki.*

@OptIn(ExperimentalCoroutinesApi::class)
class WikiViewModelTest {
    @Test fun `offline search and article use cache without API access`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = WikiTestApi(); val cache = WikiTestCache()
            val model = WikiViewModel(WikiRepository(api, cache), "owner", true)
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
            val model = WikiViewModel(WikiRepository(api, cache), "owner", false)
            advanceUntilIdle()
            assertEquals(listOf(cache.result), model.state.value.results)
            assertNotNull(model.state.value.notice)
        } finally { Dispatchers.resetMain() }
    }
}
private class WikiTestApi : WikiApi {
    var calls = 0
    override suspend fun search(query: String, limit: Int): List<WikiSearchResult> { calls++; throw WikiFailure.InvalidResponse }
    override suspend fun article(id: String, maxChars: Int): WikiArticle { calls++; throw WikiFailure.InvalidResponse }
    override suspend fun isPdfAvailable(id: String): Boolean { calls++; return false }
    override fun pdfUrl(id: String) = "https://example.com"
}
private class WikiTestCache : WikiCache {
    val result = WikiSearchResult("article", "Title", "Section", "https://example.com", 1, "excerpt")
    override suspend fun readSearch(ownerUserId: String) = WikiSearchSnapshot("инструкция", listOf(result))
    override suspend fun saveSearch(ownerUserId: String, snapshot: WikiSearchSnapshot) {}
    override suspend fun readArticle(articleId: String) = WikiArticle(articleId, "Title", "Section", "https://example.com", content = "Cached content")
    override suspend fun saveArticle(article: WikiArticle) {}
}
