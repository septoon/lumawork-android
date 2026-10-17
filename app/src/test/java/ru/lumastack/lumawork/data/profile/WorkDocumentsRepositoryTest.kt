package ru.lumastack.lumawork.data.profile

import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.model.AppUser
import ru.lumastack.lumawork.domain.profile.*

class WorkDocumentsRepositoryTest {
    private val session = AppSession("secret", AppUser("user-a", "a@example.test", "engineer"))
    private val document = WorkDocument("id", title = "Справка", fileName = "a.pdf", mimeType = "application/pdf")
    @Test fun `offline list and all offline mutations never touch network`() = runBlocking {
        val api = FakeApi()
        val cache = MemoryCache().apply { data[session.user.id] = listOf(document) }
        val repo = WorkDocumentsRepository(api, cache)
        assertEquals(listOf(document), repo.refresh(session, offline = true))
        val input = WorkDocumentInput(byteArrayOf(1), "a.pdf", "application/pdf")
        assertTrue(runCatching { repo.upload(session, input, offline = true) }.exceptionOrNull() is WorkDocumentsFailure.Offline)
        assertTrue(runCatching { repo.update(session, document, offline = true) }.exceptionOrNull() is WorkDocumentsFailure.Offline)
        assertTrue(runCatching { repo.delete(session, "id", offline = true) }.exceptionOrNull() is WorkDocumentsFailure.Offline)
        assertTrue(runCatching { repo.download(session, "id", offline = true) }.exceptionOrNull() is WorkDocumentsFailure.Offline)
        assertEquals(0, api.calls)
    }
    @Test fun `cancelled network response cannot replace owner cache`() = runBlocking {
        val api = FakeApi().apply { cancelFetch = true }
        val cache = MemoryCache().apply { data[session.user.id] = listOf(document) }
        val repo = WorkDocumentsRepository(api, cache)
        assertTrue(runCatching { repo.refresh(session) }.exceptionOrNull() is CancellationException)
        assertEquals(listOf(document), repo.read(session.user.id))
    }
    @Test fun `replacement keeps id title category and owner metadata isolated`() = runBlocking {
        val api = FakeApi()
        val cache = MemoryCache().apply { data[session.user.id] = listOf(document) }
        val repo = WorkDocumentsRepository(api, cache)
        val saved = repo.upload(session, WorkDocumentInput(byteArrayOf(1), "new.pdf", "application/pdf"), replacing = document)
        assertEquals(1, saved.size)
        assertEquals(document.id, saved.single().id)
        assertEquals(document.title, saved.single().title)
        assertTrue(repo.read("other-user").isEmpty())
        assertEquals(session.user.id, repo.changes.replayCache.last().first)
    }
    @Test fun `persisted metadata survives restart without token or owner filename`() = runBlocking {
        val directory = Files.createTempDirectory("work-document-test").toFile()
        try {
            val cache = FileWorkDocumentsCache(directory, Json)
            cache.save("private-owner", listOf(document))
            assertEquals(listOf(document), FileWorkDocumentsCache(directory, Json).read("private-owner"))
            assertTrue(cache.read("another-owner").isEmpty())
            assertFalse(directory.listFiles()!!.single().name.contains("private-owner"))
            assertFalse(directory.listFiles()!!.single().readText().contains("secret"))
        } finally { directory.deleteRecursively() }
    }
    private class MemoryCache : WorkDocumentsCache {
        val data = mutableMapOf<String, List<WorkDocument>>()
        override suspend fun read(ownerUserId: String) = data[ownerUserId].orEmpty()
        override suspend fun save(ownerUserId: String, documents: List<WorkDocument>) { data[ownerUserId] = documents }
    }
    private class FakeApi : WorkDocumentsApi {
        var calls = 0
        var cancelFetch = false
        override suspend fun fetch(token: String): List<WorkDocument> { calls++; if (cancelFetch) throw CancellationException(); return emptyList() }
        override suspend fun upload(token: String, input: WorkDocumentInput, documentId: String, category: WorkDocumentCategory, title: String, onProgress: (Double) -> Unit): WorkDocument {
            calls++; return WorkDocument(documentId, category, title, input.fileName, input.mimeType, input.data.size)
        }
        override suspend fun update(token: String, document: WorkDocument): WorkDocument { calls++; return document }
        override suspend fun delete(token: String, documentId: String) { calls++ }
        override suspend fun download(token: String, documentId: String): ByteArray { calls++; return byteArrayOf(1) }
    }
}
