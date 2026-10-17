package ru.lumastack.lumawork.data.profile

import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.profile.*

class WorkDocumentsApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val metadata = """{"id":"document-id-123456","category":"CERTIFICATE","title":"Справка","fileName":"Справка.pdf","mimeType":"application/pdf","sizeBytes":524291,"createdAt":"2026-10-17T10:00:00Z","updatedAt":"2026-10-17T10:00:00Z"}"""

    @Test fun `uploads exact authenticated work document contract in bounded sequential chunks`() = runBlocking {
        val payloads = mutableListOf<Map<String, String>>()
        val progress = mutableListOf<Double>()
        val bytes = ByteArray(512 * 1024 + 3) { (it % 251).toByte() }
        val api = api { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/v2/work-documents/chunk", request.url.encodedPath)
            assertEquals("Bearer secret", request.header("Authorization"))
            assertEquals("no-cache", request.header("Cache-Control"))
            payloads += json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject.mapValues { it.value.jsonPrimitive.content }
            200 to if (payloads.size == 1) """{"complete":false}""" else """{"complete":true,"document":$metadata}"""
        }
        val document = api.upload("secret", WorkDocumentInput(bytes, "Справка.pdf", "application/pdf"), "document-id-123456", WorkDocumentCategory.Certificate, " Справка ", progress::add)
        assertEquals("document-id-123456", document.id)
        assertEquals(listOf(.5, 1.0), progress)
        assertEquals(payloads[0]["uploadId"], payloads[1]["uploadId"])
        assertEquals(setOf("uploadId", "documentId", "category", "title", "fileName", "mimeType", "chunkIndex", "totalChunks", "chunkBase64"), payloads[0].keys)
        assertEquals("CERTIFICATE", payloads[0]["category"])
        assertEquals("Справка", payloads[0]["title"])
        assertEquals("document-id-123456", payloads[1]["documentId"])
        assertEquals("1", payloads[1]["chunkIndex"])
        assertArrayEquals(bytes, Base64.getDecoder().decode(payloads[0]["chunkBase64"]) + Base64.getDecoder().decode(payloads[1]["chunkBase64"]))
    }
    @Test fun `replacement requires completion confirmation for matching document`() = runBlocking {
        for (response in listOf("""{"complete":false}""", """{"complete":true}""", """{"complete":true,"document":${metadata.replace("document-id-123456", "wrong-id")}}""")) {
            val failure = runCatching { api { 200 to response }.upload("t", WorkDocumentInput(byteArrayOf(1), "a.pdf", "application/pdf"), "document-id-123456", WorkDocumentCategory.Other, "Название") {} }.exceptionOrNull()
            assertTrue(failure is WorkDocumentsFailure.InvalidResponse)
        }
    }
    @Test fun `metadata patch authenticated download and 204 delete encode document id`() = runBlocking {
        val methods = mutableListOf<String>()
        val api = api { request ->
            methods += request.method
            assertEquals("/api/v2/work-documents/doc%2Fid", request.url.encodedPath)
            assertEquals("Bearer private", request.header("Authorization"))
            when (request.method) {
                "PATCH" -> { val body = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
                    assertEquals(setOf("category", "title"), body.keys)
                    200 to """{"document":${metadata.replace("document-id-123456", "doc/id")}}""" }
                "GET" -> 200 to "%PDF-document"
                else -> 204 to ""
            }
        }
        val item = json.decodeFromString<WorkDocument>(metadata).copy(id = "doc/id")
        assertEquals("doc/id", api.update("private", item).id)
        assertArrayEquals("%PDF-document".toByteArray(), api.download("private", "doc/id"))
        api.delete("private", "doc/id")
        assertEquals(listOf("PATCH", "GET", "DELETE"), methods)
    }
    @Test fun `download rejects empty and streamed over limit data`() = runBlocking {
        for (body in listOf("", "x".repeat(WorkDocumentInput.MAX_BYTES + 1))) {
            assertTrue(runCatching { api { 200 to body }.download("t", "d") }.exceptionOrNull() is WorkDocumentsFailure.InvalidResponse)
        }
    }
    @Test fun `quota failure keeps server feedback and unauthorized never exposes token`() = runBlocking {
        val message = "Общий размер рабочих документов не должен превышать 500 МБ."
        val failure = runCatching { api { 413 to """{"message":"$message"}""" }.download("t", "d") }.exceptionOrNull()
        assertEquals(message, failure?.message)
        assertTrue(runCatching { api { 401 to "secret" }.fetch("secret") }.exceptionOrNull() is WorkDocumentsFailure.Unauthorized)
    }
    private fun api(response: (okhttp3.Request) -> Pair<Int, String>) = OkHttpWorkDocumentsApi("https://example.test", json,
        OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = response(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody()).build()
        }.build())
}
