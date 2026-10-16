package ru.lumastack.lumawork.data.auto

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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.lumastack.lumawork.domain.auto.VehicleDocumentInput
import ru.lumastack.lumawork.domain.auto.VehicleDocumentKind

class VehicleDocumentsApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val metadata = """{"id":"doc-id","kind":"OSAGO","fileName":"Полис.pdf","mimeType":"application/pdf","sizeBytes":524291,"createdAt":"2026-10-17T10:00:00.000Z"}"""

    @Test
    fun `upload sends sequential 512 KiB chunks with stable ids exact metadata auth and completion`() = runBlocking {
        val data = ByteArray(VehicleDocumentInput.CHUNK_BYTES + 3) { (it % 251).toByte() }
        val payloads = mutableListOf<Map<String, String>>()
        val progress = mutableListOf<Double>()
        val api = api { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/v2/vehicles/car/documents/chunk", request.url.encodedPath)
            assertEquals("Bearer secret", request.header("Authorization"))
            assertEquals("no-cache", request.header("Cache-Control"))
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            payloads += json.parseToJsonElement(body).jsonObject.mapValues { it.value.jsonPrimitive.content }
            if (payloads.size == 1) 200 to """{"complete":false,"received":1,"totalChunks":2}"""
            else 200 to """{"complete":true,"document":$metadata}"""
        }
        val document = api.uploadDocument("secret", "car", VehicleDocumentInput(data, "Полис.pdf", "application/pdf", VehicleDocumentKind.Osago, "doc-id"), progress::add)
        assertEquals("doc-id", document.id)
        assertEquals(VehicleDocumentKind.Osago, document.kind)
        assertEquals(listOf(.5, 1.0), progress)
        assertEquals(2, payloads.size)
        assertEquals(payloads[0]["uploadId"], payloads[1]["uploadId"])
        assertTrue(payloads[0]["uploadId"]!!.isNotBlank())
        payloads.forEachIndexed { index, payload ->
            assertEquals(setOf("uploadId", "documentId", "kind", "fileName", "mimeType", "chunkIndex", "totalChunks", "chunkBase64"), payload.keys)
            assertEquals("doc-id", payload["documentId"])
            assertEquals("OSAGO", payload["kind"])
            assertEquals("Полис.pdf", payload["fileName"])
            assertEquals("application/pdf", payload["mimeType"])
            assertEquals(index.toString(), payload["chunkIndex"])
            assertEquals("2", payload["totalChunks"])
        }
        val first = Base64.getDecoder().decode(payloads[0]["chunkBase64"])
        val last = Base64.getDecoder().decode(payloads[1]["chunkBase64"])
        assertEquals(512 * 1024, first.size)
        assertEquals(3, last.size)
        assertArrayEquals(data, first + last)
    }

    @Test
    fun `upload rejects missing server confirmation and wrong returned id`() = runBlocking {
        for (response in listOf("""{"complete":false}""", """{"complete":true}""", """{"complete":true,"document":${metadata.replace("doc-id", "wrong")}}""")) {
            val api = api { 200 to response }
            val failure = runCatching { api.uploadDocument("t", "v", VehicleDocumentInput(byteArrayOf(1), "a.pdf", "application/pdf", VehicleDocumentKind.Other, "doc-id")) {} }.exceptionOrNull()
            assertTrue(failure is AutoFailure.InvalidResponse)
        }
    }

    @Test
    fun `authenticated download and empty 204 deletion use encoded independent path segments`() = runBlocking {
        val methods = mutableListOf<String>()
        val api = api { request ->
            methods += request.method
            assertEquals("/api/v2/vehicles/car%2Fid/documents/doc%2Fid", request.url.encodedPath)
            assertEquals("Bearer private", request.header("Authorization"))
            assertEquals("no-cache", request.header("Cache-Control"))
            if (request.method == "GET") 200 to "%PDF-data" else 204 to ""
        }
        assertArrayEquals("%PDF-data".toByteArray(), api.downloadDocument("private", "car/id", "doc/id"))
        api.deleteDocument("private", "car/id", "doc/id")
        assertEquals(listOf("GET", "DELETE"), methods)
    }

    @Test
    fun `document requests preserve authorization and HTTP failures`() = runBlocking {
        for (status in listOf(401, 403, 404)) {
            val api = api { status to "{}" }
            val failure = runCatching { api.downloadDocument("t", "v", "d") }.exceptionOrNull()
            if (status == 404) assertEquals(status, (failure as AutoFailure.Http).status)
            else assertTrue(failure is AutoFailure.Unauthorized)
        }
    }

    @Test
    fun `empty download is not a usable document`() = runBlocking {
        val failure = runCatching { api { 200 to "" }.downloadDocument("t", "v", "d") }.exceptionOrNull()
        assertTrue(failure is AutoFailure.InvalidResponse)
    }

    @Test
    fun `document quota rejection preserves the server message for retry`() = runBlocking {
        val message = "Общий размер документов автомобиля не должен превышать 100 МБ."
        val failure = runCatching {
            api { 413 to """{"error":"DOCUMENTS_TOO_LARGE","message":"$message"}""" }
                .uploadDocument("t", "v", VehicleDocumentInput(byteArrayOf(1), "a.pdf", "application/pdf", VehicleDocumentKind.Other)) {}
        }.exceptionOrNull()
        assertTrue(failure is AutoFailure.Document)
        assertEquals(message, failure?.message)
    }

    private fun api(response: (okhttp3.Request) -> Pair<Int, String>): OkHttpAutoApi = OkHttpAutoApi(
        "https://example.test", json,
        OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = response(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("test").body(body.toResponseBody()).build()
        }.build(),
    )
}
