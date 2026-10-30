package ru.lumastack.lumawork.ui.requests

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.data.requests.*
import ru.lumastack.lumawork.domain.model.*
import ru.lumastack.lumawork.domain.requests.*
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class ClientPersonalCommentsControllerTest {
    @Test fun `opening editor never traverses request archive on UI thread`() = runBlocking {
        newSingleThreadContext("comment-ui").use { ui ->
            val failure = AtomicReference<Throwable?>(null)
            val scope = CoroutineScope(SupervisorJob() + ui + CoroutineExceptionHandler { _, error -> failure.set(error) })
            val request = SimpleOneRequest("request", "R", address = "Адрес", terminalId = "0001", tableFields = mapOf("ИНН" to "1234567890"))
            val controller = ClientPersonalCommentsController(repository(), scope)
            val reads = AtomicInteger()
            controller.records = object : AbstractList<SimpleOneRequest>() {
                override val size = 100
                override fun get(index: Int): SimpleOneRequest {
                    check(!Thread.currentThread().name.startsWith("comment-ui")) { "Archive parsing blocks UI thread" }
                    reads.incrementAndGet()
                    return request
                }
            }
            try {
                withContext(ui) { controller.openEditor(request) }
                withTimeout(5000) { while ((controller.draft == null || controller.isLoadingOptions) && failure.get() == null) delay(10) }
                assertNull("Opening must not parse the archive on UI thread", failure.get())
                withContext(ui) {
                    assertEquals(listOf("0001"), controller.draft!!.terminalIDs)
                    assertEquals("Адрес", controller.targetOptions("1234567890").single().address)
                    assertEquals("0001", controller.terminalOptions("1234567890").single().terminalID)
                    val preparedReads = reads.get()
                    controller.updateDraft(controller.draft!!.copy(extraInfo = "Комментарий"))
                    assertEquals("Комментарий", controller.draft!!.extraInfo)
                    controller.targetOptions(controller.draft!!.tin)
                    controller.terminalOptions(controller.draft!!.tin)
                    assertEquals("Typing and recomposition must reuse prepared options", preparedReads, reads.get())
                    controller.dismissEditor()
                    controller.openEditor(request)
                }
                withTimeout(5000) { while (controller.draft == null) delay(10) }
                assertNull(failure.get())
                assertEquals("Repeat opening must reuse one archive index", 100, reads.get())
            } finally { scope.cancel() }
        }
    }

    @Test fun `changing TIN prepares new options in background and closing cancels presentation`() = runBlocking {
        newSingleThreadContext("comment-ui").use { ui ->
            val failure = AtomicReference<Throwable?>(null)
            val scope = CoroutineScope(SupervisorJob() + ui + CoroutineExceptionHandler { _, error -> failure.set(error) })
            val first = SimpleOneRequest("first", "R1", address = "Первый", terminalId = "001", tableFields = mapOf("ИНН" to "1234567890"))
            val second = SimpleOneRequest("second", "R2", address = "Второй", terminalId = "002", tableFields = mapOf("ИНН" to "9999999999"))
            val controller = ClientPersonalCommentsController(repository(), scope)
            controller.records = listOf(first, second)
            try {
                withContext(ui) { controller.openEditor() }
                withTimeout(5000) { while (controller.draft == null) delay(10) }
                withContext(ui) { controller.updateDraft(controller.draft!!.copy(tin = "9999999999")) }
                withTimeout(5000) { while (withContext(ui) { controller.terminalOptions("9999999999").isEmpty() }) delay(10) }
                withContext(ui) {
                    assertEquals("002", controller.terminalOptions("9999999999").single().terminalID)
                    assertEquals("Второй", controller.targetOptions("9999999999").single().address)
                    controller.dismissEditor()
                }
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                controller.records = object : AbstractList<SimpleOneRequest>() {
                    override val size = 1
                    override fun get(index: Int): SimpleOneRequest {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        return first
                    }
                }
                try {
                    withContext(ui) { controller.openEditor(first); assertNotNull("Form must precede archive traversal", controller.draft) }
                    assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
                    withContext(ui) { controller.dismissEditor(); assertFalse(controller.isOpening) }
                } finally { release.countDown() }
                // Wait until the cancelled preparation has returned to the owning UI dispatcher.
                scope.coroutineContext[Job]!!.children.toList().joinAll()
                assertNull(controller.draft)
                assertNull(failure.get())
            } finally { scope.cancel() }
        }
    }

    @Test fun `typing coalesces disk writes and dismissal flushes the latest draft`() = runBlocking {
        newSingleThreadContext("comment-ui").use { ui ->
            val scope = CoroutineScope(SupervisorJob() + ui)
            val written = mutableListOf<ClientPersonalCommentDraft?>()
            val cache = object : ClientPersonalCommentsCache {
                override suspend fun read(userId: String) = ClientPersonalCommentsSnapshot()
                override suspend fun write(userId: String, snapshot: ClientPersonalCommentsSnapshot) = error("Draft cannot rewrite comments")
                override suspend fun writeDraft(userId: String, draft: ClientPersonalCommentDraft?) { written += draft }
            }
            val api = object : ClientPersonalCommentsApi {
                override suspend fun comments(token: String) = emptyList<ClientPersonalComment>()
                override suspend fun save(token: String, draft: ClientPersonalCommentDraft) = error("Unused")
            }
            val repo = ClientPersonalCommentsRepository(AppSession("token", AppUser("owner", "test@example.test", "Test")), api, cache)
            val controller = ClientPersonalCommentsController(repo, scope)
            try {
                withContext(ui) {
                    controller.openEditor()
                    repeat(20) { controller.updateDraft(controller.draft!!.copy(extraInfo = "Текст $it")) }
                    assertTrue("Typing must not queue a write per character", written.isEmpty())
                    controller.dismissEditor()
                }
                scope.coroutineContext[Job]!!.children.toList().joinAll()
                assertEquals(listOf("Текст 19"), written.map { it?.extraInfo })
                assertEquals("Текст 19", repo.snapshot.value.draft?.extraInfo)
            } finally { scope.cancel() }
        }
    }

    @Test fun `typing before hydration preserves edits and restores existing mutation identity`() = runBlocking {
        newSingleThreadContext("comment-ui").use { ui ->
            val scope = CoroutineScope(SupervisorJob() + ui)
            val readStarted = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()
            val existing = ClientPersonalComment("existing", displayTIN = "1234567890", contactPerson = "Контакт", extraInfo = "Старое")
            val cache = object : ClientPersonalCommentsCache {
                override suspend fun read(userId: String): ClientPersonalCommentsSnapshot {
                    readStarted.complete(Unit); releaseRead.await(); return ClientPersonalCommentsSnapshot(listOf(existing))
                }
                override suspend fun write(userId: String, snapshot: ClientPersonalCommentsSnapshot) {}
                override suspend fun writeDraft(userId: String, draft: ClientPersonalCommentDraft?) {}
            }
            val submitted = CompletableDeferred<ClientPersonalCommentDraft>()
            val api = object : ClientPersonalCommentsApi {
                override suspend fun comments(token: String) = emptyList<ClientPersonalComment>()
                override suspend fun save(token: String, draft: ClientPersonalCommentDraft): ClientPersonalComment { submitted.complete(draft); return existing.copy(extraInfo = draft.extraInfo) }
            }
            val repo = ClientPersonalCommentsRepository(AppSession("token", AppUser("owner", "test@example.test", "Test")), api, cache)
            val controller = ClientPersonalCommentsController(repo, scope)
            val request = SimpleOneRequest("request", "R", tableFields = mapOf("ИНН" to "1234567890"))
            try {
                withContext(ui) { controller.openEditor(request) }
                readStarted.await()
                withContext(ui) { controller.updateDraft(controller.draft!!.copy(extraInfo = "Новый текст")) }
                releaseRead.complete(Unit)
                withTimeout(5000) { while (controller.isLoadingOptions) delay(10) }
                withContext(ui) {
                    assertEquals("existing", controller.draft!!.serverID)
                    assertEquals("Контакт", controller.draft!!.contactPerson)
                    assertEquals("Новый текст", controller.draft!!.extraInfo)
                    controller.save()
                }
                assertEquals("existing", withTimeout(5000) { submitted.await() }.serverID)
            } finally { scope.cancel() }
        }
    }

    private fun repository(): ClientPersonalCommentsRepository {
        val cache = object : ClientPersonalCommentsCache {
            private var value = ClientPersonalCommentsSnapshot()
            override suspend fun read(userId: String) = value
            override suspend fun write(userId: String, snapshot: ClientPersonalCommentsSnapshot) { value = snapshot }
        }
        val api = object : ClientPersonalCommentsApi {
            override suspend fun comments(token: String) = emptyList<ClientPersonalComment>()
            override suspend fun save(token: String, draft: ClientPersonalCommentDraft): ClientPersonalComment = error("No server writes")
        }
        return ClientPersonalCommentsRepository(AppSession("token", AppUser("owner", "test@example.test", "Test")), api, cache)
    }
}
