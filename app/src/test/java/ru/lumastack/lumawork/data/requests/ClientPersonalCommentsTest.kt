package ru.lumastack.lumawork.data.requests

import kotlinx.coroutines.runBlocking
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
