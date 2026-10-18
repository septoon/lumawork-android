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
                withTimeout(5000) { while (controller.draft == null && failure.get() == null) delay(10) }
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
                assertEquals("Repeat opening must reuse archive options", 200, reads.get())
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
                    withContext(ui) { controller.openEditor(first) }
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
