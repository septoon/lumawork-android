package ru.lumastack.lumawork.data.salary

import java.io.File
import java.nio.file.Files
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

class SalaryDocumentsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val pdf = "%PDF-1.4\ncontents".toByteArray()
    private val document = SalaryDocument("id-1", "2026-10", "Листок.pdf", "application/pdf", pdf.size, "now", "now")

    @Test fun `rejects disguised and oversized input before any upload`() {
        listOf(
            { SalaryDocumentInput("<html></html>".toByteArray(), "a.pdf", "application/pdf") },
            { SalaryDocumentInput(pdf, "a.html", "text/html") },
            { SalaryDocumentInput(pdf, "a.pdf", "text/html") },
            { SalaryDocumentInput(ByteArray(20 * 1024 * 1024 + 1), "a.pdf", "application/pdf") },
            { SalaryDocumentInput(byteArrayOf(), "a.pdf", "application/pdf") },
        ).forEach { assertTrue(runCatching(it).isFailure) }
        assertEquals("text/html", SalaryDocumentInput("<!doctype html><html>Листок</html>".toByteArray(), "a.htm", "text/html").mimeType)
        assertEquals("Листок.pdf", SalaryDocumentInput(pdf, "../../Листок.pdf", "application/pdf").fileName)
    }

    @Test fun `uploads authenticated exact chunk contract and requires matching final document`() = runBlocking {
        val bytes = pdf + ByteArray(512 * 1024)
        val payloads = mutableListOf<Map<String, String>>()
        val progress = mutableListOf<Double>()
        val api = api { request ->
            assertEquals("/api/v2/salary/documents/2026-10/chunk", request.url.encodedPath)
            assertEquals("POST", request.method)
            assertEquals("Bearer token", request.header("Authorization"))
            payloads += json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject.mapValues { it.value.jsonPrimitive.content }
            200 to if (payloads.size == 1) "{}" else """{"document":${json.encodeToString(SalaryDocument.serializer(), document.copy(sizeBytes = bytes.size))}}"""
        }
        assertEquals("id-1", api.upload("token", "2026-10", SalaryDocumentInput(bytes, "Листок.pdf", "application/pdf"), progress::add).id)
        assertEquals(setOf("uploadId", "fileName", "mimeType", "chunkIndex", "totalChunks", "chunkBase64"), payloads.first().keys)
        assertEquals(payloads[0]["uploadId"], payloads[1]["uploadId"])
        assertEquals("1", payloads[1]["chunkIndex"])
        assertEquals(listOf(.5, 1.0), progress)
        assertArrayEquals(bytes, Base64.getDecoder().decode(payloads[0]["chunkBase64"]) + Base64.getDecoder().decode(payloads[1]["chunkBase64"]))
        assertTrue(runCatching { api { 200 to "{}" }.upload("t", "2026-10", SalaryDocumentInput(pdf, "a.pdf", "application/pdf")) {} }.isFailure)
    }

    @Test fun `malformed metadata list does not become a confirmed empty list`() = runBlocking {
        assertTrue(runCatching { api { 200 to "{}" }.fetch("t") }.isFailure)
        assertTrue(runCatching { api { 200 to """{"documents":[{"id":"bad"}]}""" }.fetch("t") }.isFailure)
    }

    @Test fun `migration keeps local file until upload and refreshed metadata both confirm it`() = runBlocking {
        val legacy = MemoryLegacy(pdf)
        val api = MemoryApi(document)
        val repository = SalaryDocumentsRepository(api, MemoryCache(), legacy)
        api.failList = true
        assertTrue(runCatching { repository.refresh("owner", "t") }.isFailure)
        assertNotNull(legacy.bytes)
        api.failList = false
        api.omitUploadFromList = true
        repository.refresh("owner", "t")
        assertTrue(runCatching { repository.migrateLegacy("owner", "t") {} }.isFailure)
        assertNotNull(legacy.bytes)
        api.omitUploadFromList = false
        repository.refresh("owner", "t")
        repository.migrateLegacy("owner", "t") {}
        assertNull(legacy.bytes)
        assertEquals(document, repository.documents.value.single())
    }

    @Test fun `offline mutation cannot touch remote API or legacy files`() = runBlocking {
        val legacy = MemoryLegacy(pdf)
        val repository = SalaryDocumentsRepository(MemoryApi(document), MemoryCache(), legacy)
        assertTrue(runCatching { repository.upload("owner", "t", "2026-10", SalaryDocumentInput(pdf, "a.pdf", "application/pdf"), true) {} }.isFailure)
        assertTrue(runCatching { repository.delete("owner", "t", "2026-10", true) }.isFailure)
        assertNotNull(legacy.bytes)
    }

    @Test fun `explicit deletion removes legacy copy so migration cannot resurrect it`() = runBlocking {
        val legacy = MemoryLegacy(pdf)
        val api = MemoryApi(document).apply { uploaded = true }
        val repository = SalaryDocumentsRepository(api, MemoryCache(), legacy)
        repository.refresh("owner", "t")
        repository.delete("owner", "t", "2026-10", false)
        assertNull(legacy.bytes)
        repository.refresh("owner", "t")
        repository.migrateLegacy("owner", "t") {}
        assertTrue(repository.documents.value.isEmpty())
        assertFalse(api.uploaded)
    }

    @Test fun `metadata cache is scoped by owner`() = runBlocking {
        val directory = Files.createTempDirectory("salary-documents-test").toFile()
        try {
            val cache = FileSalaryDocumentsCache(directory, json)
            cache.save("owner-a", listOf(document))
            assertEquals(listOf(document), cache.read("owner-a"))
            assertTrue(cache.read("owner-b").isEmpty())
        } finally { directory.deleteRecursively() }
    }

    private fun api(response: (okhttp3.Request) -> Pair<Int, String>) = OkHttpSalaryDocumentsApi("https://example.test", json,
        OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = response(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody()).build()
        }.build())

    private class MemoryLegacy(var bytes: ByteArray?) : SalaryLegacyDocuments {
        override suspend fun months() = if (bytes == null) emptySet() else setOf("2026-10")
        override suspend fun input(month: String) = bytes?.let { SalaryDocumentInput(it, "Листок.pdf", "application/pdf") }
        override suspend fun delete(month: String) { bytes = null }
    }
    private class MemoryCache : SalaryDocumentsCache {
        val values = mutableMapOf<String, List<SalaryDocument>>()
        override suspend fun read(owner: String) = values[owner].orEmpty()
        override suspend fun save(owner: String, documents: List<SalaryDocument>) { values[owner] = documents }
    }
    private class MemoryApi(val document: SalaryDocument) : SalaryDocumentsApi {
        var failList = false
        var omitUploadFromList = false
        var uploaded = false
        override suspend fun fetch(token: String): List<SalaryDocument> {
            if (failList) error("network")
            return if (uploaded && !omitUploadFromList) listOf(document) else emptyList()
        }
        override suspend fun upload(token: String, month: String, input: SalaryDocumentInput, onProgress: (Double) -> Unit): SalaryDocument { uploaded = true; return document }
        override suspend fun download(token: String, month: String) = error("unexpected")
        override suspend fun delete(token: String, month: String) { uploaded = false }
    }
}
