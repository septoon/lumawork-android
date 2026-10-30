package ru.lumastack.lumawork.data.requests

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.model.AppUser
import ru.lumastack.lumawork.domain.requests.*

class ClientPersonalCommentsTest {
    @Test fun `copied phone keeps country prefix and all digits without mask`() {
        assertEquals("+79781234567", clientCommentPhoneForCopy("+7 (978) 123-45-67"))
        assertEquals("+79781234567", clientCommentPhoneForCopy("8 (978) 123-45-67"))
        assertEquals("+79781234567", clientCommentPhoneForCopy("9781234567"))
        assertEquals("+797812345671234", clientCommentPhoneForCopy("+7 (978) 123-45-67 доб. 1234"))
        assertEquals("+7123", clientCommentPhoneForCopy("+7 (123)"))
        assertEquals("", clientCommentPhoneForCopy(""))
    }

    @Test fun `card hides matching scope while editor retains it`() {
        val comment = ClientPersonalComment("comment", displayTIN = "1234567890",
            targets = listOf(ClientPersonalCommentTarget("Адрес клиента")), terminalIDs = listOf("000123"),
            contactPerson = "Контакт", phone = "+79780000000", email = "contact@example.test", extraInfo = "Заметка")
        assertEquals(listOf("Контактное лицо", "Телефон", "Почта", "Дополнительно"),
            clientPersonalCommentRows(comment).map { it.first })
        val draft = clientCommentEditorDraft(comment.displayTIN, comment, null, "", emptyList(), null)
        assertEquals(comment.displayTIN, draft.tin)
        assertEquals(comment.targets, draft.targets)
        assertEquals(comment.terminalIDs, draft.terminalIDs)
    }

    @Test fun `matching prefers address then terminal then all-address fallback within correct TIN`() {
        val tin = "1234567890"
        val comments = listOf(
            ClientPersonalComment("global", displayTIN = tin),
            ClientPersonalComment("address", displayTIN = tin, targets = listOf(ClientPersonalCommentTarget("  УЛИЦА  1 "))),
            ClientPersonalComment("terminal", displayTIN = tin, targets = listOf(ClientPersonalCommentTarget("Другой адрес")), terminalIDs = listOf("000123")),
            ClientPersonalComment("other", displayTIN = "9999999999", terminalIDs = listOf("999")),
        )
        val index = ClientPersonalCommentMatchingIndex(comments)
        assertEquals("address", index.comment(tin, "улица 1", "000123")?.id)
        assertEquals("terminal", index.comment(tin, "новый адрес", "000123")?.id)
        assertEquals("global", index.comment(tin, "новый адрес", "999")?.id)
        assertEquals("other", index.comment("", "", "999")?.id)
        assertNull(index.comment("1111111111", "улица 1", "000123"))
    }

    @Test fun `new comment omits optional IDs and accepts nullable backend fields`() = runBlocking {
        var sent: JsonObject? = null
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            assertEquals("2", request.header("X-LumaWork-Personal-Comments-Version"))
            assertEquals("Bearer app-token", request.header("Authorization"))
            request.body?.let { body -> val buffer = Buffer(); body.writeTo(buffer); sent = json.parseToJsonElement(buffer.readUtf8()).jsonObject }
            val comment = """{"id":"new","displayTIN":"1234567890","contactPerson":null,"phone":null,"email":null,"extraInfo":"Текст","updatedAt":"2026-10-17T10:00:00Z"}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (request.method == "POST") """{"comment":$comment}""" else """{"comments":[$comment]}""").toResponseBody()).build()
        }).build()
        val api = OkHttpClientPersonalCommentsApi("https://app.test", json, client)
        val saved = api.save("app-token", ClientPersonalCommentDraft(tin = "1234567890", extraInfo = "Текст"))
        val payload = requireNotNull(sent)
        assertFalse(payload.containsKey("id"))
        assertFalse(payload.containsKey("sourceCommentID"))
        assertEquals("", saved.contactPerson)
        assertEquals("", api.comments("app-token").single().email)
    }

    @Test fun `save starts during GET and stale refresh preserves saved comment and terminal transfer`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = ClientPersonalComment("source", displayTIN = "1234567890", terminalIDs = listOf("001", "002"))
        val saved = ClientPersonalComment("saved", displayTIN = "1234567890", terminalIDs = listOf("001"), extraInfo = "Новое")
        val cache = object : ClientPersonalCommentsCache {
            override suspend fun read(userId: String) = ClientPersonalCommentsSnapshot(listOf(source))
            override suspend fun write(userId: String, snapshot: ClientPersonalCommentsSnapshot) {}
        }
        val api = object : ClientPersonalCommentsApi {
            override suspend fun comments(token: String): List<ClientPersonalComment> { started.complete(Unit); release.await(); return listOf(source) }
            override suspend fun save(token: String, draft: ClientPersonalCommentDraft) = saved
        }
        val repo = ClientPersonalCommentsRepository(AppSession("token", AppUser("owner", "test@example.test", "Test")), api, cache)
        val refresh = launch { repo.refresh({ false }, force = true) }
        started.await()
        try {
            withTimeout(1000) { repo.save(ClientPersonalCommentDraft(sourceCommentID = "source", tin = "1234567890", terminalIDs = listOf("001"), extraInfo = "Новое")) { false } }
        } finally { release.complete(Unit); refresh.join() }
        assertEquals("Новое", repo.snapshot.value.comments.first { it.id == "saved" }.extraInfo)
        assertEquals(listOf("002"), repo.snapshot.value.comments.first { it.id == "source" }.terminalIDs)
        assertNull(repo.snapshot.value.draft)
    }

    @Test fun `simultaneous forced refresh shares one GET`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val api = object : ClientPersonalCommentsApi {
            override suspend fun comments(token: String): List<ClientPersonalComment> { calls++; started.complete(Unit); release.await(); return listOf(ClientPersonalComment("remote")) }
            override suspend fun save(token: String, draft: ClientPersonalCommentDraft) = error("Unused")
        }
        val cache = object : ClientPersonalCommentsCache {
            override suspend fun read(userId: String) = ClientPersonalCommentsSnapshot()
            override suspend fun write(userId: String, snapshot: ClientPersonalCommentsSnapshot) {}
        }
        val repo = ClientPersonalCommentsRepository(AppSession("token", AppUser("owner", "test@example.test", "Test")), api, cache)
        val first = launch { repo.refresh({ false }, force = true) }
        started.await()
        val second = launch(start = CoroutineStart.UNDISPATCHED) { repo.refresh({ false }, force = true) }
        release.complete(Unit)
        joinAll(first, second)
        assertEquals(1, calls)
        assertEquals("remote", repo.snapshot.value.comments.single().id)
    }

    @Test fun `draft writes preserve comments payload and migrate embedded legacy draft`() = runBlocking {
        val directory = Files.createTempDirectory("comments-cache").toFile()
        try {
            val json = Json { encodeDefaults = true }
            val cache = FileClientPersonalCommentsCache(directory, json)
            val initial = ClientPersonalCommentsSnapshot(listOf(ClientPersonalComment("existing")), ClientPersonalCommentDraft(tin = "1234567890", extraInfo = "Старое"))
            cache.write("owner", initial)
            // Simulate an existing v2 snapshot from an earlier app version.
            directory.listFiles()!!.filter { it.name.endsWith("-draft.json") }.forEach { it.delete() }
            val payload = directory.listFiles()!!.single { it.name.endsWith("-v2.json") }
            payload.writeText(json.encodeToString(ClientPersonalCommentsSnapshot.serializer(), initial))
            assertEquals("Старое", cache.read("owner").draft?.extraInfo)
            val before = payload.readBytes()
            val repo = ClientPersonalCommentsRepository(AppSession("token", AppUser("owner", "test@example.test", "Test")), object : ClientPersonalCommentsApi {
                override suspend fun comments(token: String) = emptyList<ClientPersonalComment>()
                override suspend fun save(token: String, draft: ClientPersonalCommentDraft) = error("Unused")
            }, cache)
            repo.persistDraft(initial.draft!!.copy(extraInfo = "Новое"))
            assertArrayEquals(before, payload.readBytes())
            assertEquals("Новое", cache.read("owner").draft?.extraInfo)
            assertEquals("existing", cache.read("owner").comments.single().id)
            cache.writeDraft("owner", null)
            assertNull("Clearing the separate draft must not revive the embedded legacy draft", cache.read("owner").draft)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `session initialization never repeats GET after TTL or navigation`() = runBlocking {
        var clock = 0L
        var calls = 0
        val api = object : ClientPersonalCommentsApi {
            override suspend fun comments(token: String): List<ClientPersonalComment> { calls++; return listOf(ClientPersonalComment("remote")) }
            override suspend fun save(token: String, draft: ClientPersonalCommentDraft) = error("Unused")
        }
        val cache = object : ClientPersonalCommentsCache {
            override suspend fun read(userId: String) = ClientPersonalCommentsSnapshot()
            override suspend fun write(userId: String, snapshot: ClientPersonalCommentsSnapshot) {}
        }
        val repo = ClientPersonalCommentsRepository(AppSession("token", AppUser("owner", "test@example.test", "Test")), api, cache, now = { clock })
        repo.initialize { true }
        assertEquals(0, calls)
        repo.initialize { false }
        clock = 600_000
        repeat(5) { repo.initialize { false } }
        assertEquals(1, calls)
        repo.refresh({ false }, force = true)
        assertEquals(2, calls)
        assertEquals("remote", repo.snapshot.value.comments.single().id)
    }

    @Test fun `equal request snapshots reuse the same session options index`() = runBlocking {
        val api = object : ClientPersonalCommentsApi {
            override suspend fun comments(token: String) = emptyList<ClientPersonalComment>()
            override suspend fun save(token: String, draft: ClientPersonalCommentDraft) = error("Unused")
        }
        val cache = object : ClientPersonalCommentsCache {
            override suspend fun read(userId: String) = ClientPersonalCommentsSnapshot()
            override suspend fun write(userId: String, snapshot: ClientPersonalCommentsSnapshot) {}
        }
        val repo = ClientPersonalCommentsRepository(AppSession("token", AppUser("owner", "test@example.test", "Test")), api, cache)
        val first = listOf(ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest("one", "R", terminalId = "001", tableFields = mapOf("ИНН" to "1234567890")))
        val original = repo.optionsIndex(first)
        assertSame(original, repo.optionsIndex(first.map { it.copy() }))
        val changed = repo.optionsIndex(first.map { it.copy(terminalId = "002") })
        assertNotSame(original, changed)
        assertEquals("002", changed.terminals("1234567890").single().terminalID)
    }

    @Test fun `navigation cancels save waiter but session completes mutation and coalesces retry`() = runBlocking {
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var saves = 0
        val api = object : ClientPersonalCommentsApi {
            override suspend fun comments(token: String) = emptyList<ClientPersonalComment>()
            override suspend fun save(token: String, draft: ClientPersonalCommentDraft): ClientPersonalComment {
                saves++; started.complete(Unit); release.await()
                return ClientPersonalComment("saved", displayTIN = draft.tin, extraInfo = draft.extraInfo)
            }
        }
        val cache = object : ClientPersonalCommentsCache {
            override suspend fun read(userId: String) = ClientPersonalCommentsSnapshot()
            override suspend fun write(userId: String, snapshot: ClientPersonalCommentsSnapshot) {}
            override suspend fun writeDraft(userId: String, draft: ClientPersonalCommentDraft?) { if (draft == null) finished.complete(Unit) }
        }
        val repo = ClientPersonalCommentsRepository(AppSession("token", AppUser("owner", "test@example.test", "Test")), api, cache, sessionScope = sessionScope)
        val draft = ClientPersonalCommentDraft(tin = "1234567890", extraInfo = "Текст")
        try {
            val waiter = launch { repo.save(draft) { false } }
            started.await()
            waiter.cancelAndJoin()
            val retry = launch(start = CoroutineStart.UNDISPATCHED) { repo.save(draft) { false } }
            release.complete(Unit)
            retry.join()
            withTimeout(1000) { finished.await() }
            assertEquals(1, saves)
            assertEquals("Текст", repo.snapshot.value.comments.single().extraInfo)
            assertNull(repo.snapshot.value.draft)
        } finally { sessionScope.cancel() }
    }

    @Test fun `cancelled old session cannot overwrite current draft`() = runBlocking {
        val directory = Files.createTempDirectory("comments-cancelled").toFile()
        try {
            val cache = FileClientPersonalCommentsCache(directory, Json { encodeDefaults = true })
            cache.writeDraft("owner", ClientPersonalCommentDraft(extraInfo = "Актуальное"))
            val cancelledScope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
            val stale = cancelledScope.launch(start = CoroutineStart.UNDISPATCHED) {
                cache.writeDraft("owner", ClientPersonalCommentDraft(extraInfo = "Устаревшее"))
            }
            stale.join()
            assertEquals("Актуальное", cache.read("owner").draft?.extraInfo)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `failed save retains draft and existing comments and offline never sends`() = runBlocking {
        val values = mutableMapOf<String, ClientPersonalCommentsSnapshot>()
        val cache = object : ClientPersonalCommentsCache {
            override suspend fun read(userId: String) = values[userId] ?: ClientPersonalCommentsSnapshot()
            override suspend fun write(userId: String, snapshot: ClientPersonalCommentsSnapshot) { values[userId] = snapshot }
        }
        var saves = 0
        val api = object : ClientPersonalCommentsApi {
            override suspend fun comments(token: String) = emptyList<ClientPersonalComment>()
            override suspend fun save(token: String, draft: ClientPersonalCommentDraft): ClientPersonalComment { saves++; throw ClientPersonalCommentsFailure.Http(500) }
        }
        val repo = ClientPersonalCommentsRepository(AppSession("token", AppUser("owner", "engineer@example.test", "engineer")), api, cache)
        val draft = ClientPersonalCommentDraft(tin = "1234567890", extraInfo = "Черновик")
        assertTrue(runCatching { repo.save(draft) { false } }.isFailure)
        assertEquals(draft, values["owner"]?.draft)
        assertTrue(runCatching { repo.save(draft) { true } }.isFailure)
        assertEquals(1, saves)
        assertNull(values["other"])
    }
}
