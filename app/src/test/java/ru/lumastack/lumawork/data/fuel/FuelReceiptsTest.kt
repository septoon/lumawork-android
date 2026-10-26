package ru.lumastack.lumawork.data.fuel

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
import ru.lumastack.lumawork.domain.fuel.FuelRecord
import ru.lumastack.lumawork.domain.fuel.FuelRecordType
import ru.lumastack.lumawork.ui.fuel.fuelReceiptOnlyMonths

class FuelReceiptsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val pdf = "%PDF-1.4\nreceipt".toByteArray()
    private val id = "2bd0566e-f2b9-4d8e-8256-a34577f42c92"
    private val receipt get() = FuelReceipt(id, "2026-10", "fiscal", "Чек.pdf", "application/pdf", pdf.size, "2026-10-26T00:00:00Z", "2026-10-26T00:00:00Z")

    @Test fun `validates file bytes size mime and safe name before upload`() {
        assertEquals("Чек.pdf", FuelReceiptInput(pdf, "../../Чек.pdf", "application/pdf").fileName)
        assertEquals("image/jpeg", FuelReceiptInput(byteArrayOf(-1, -40, -1, 1), "receipt.jpeg", "image/jpeg").mimeType)
        assertEquals("image/png", FuelReceiptInput(byteArrayOf(-119,80,78,71,13,10,26,10), "receipt.png", null).mimeType)
        val heic = byteArrayOf(0,0,0,24) + "ftypheic".toByteArray() + ByteArray(4) + "mif1heic".toByteArray()
        assertEquals("image/heic", FuelReceiptInput(heic, "receipt.heic", "image/heic").mimeType)
        listOf(
            { FuelReceiptInput("bad".toByteArray(), "a.pdf", null) },
            { FuelReceiptInput(pdf, "a.png", null) },
            { FuelReceiptInput(pdf, "a.pdf", "image/png") },
            { FuelReceiptInput(ByteArray(20 * 1024 * 1024 + 1), "a.pdf", null) },
            { FuelReceiptInput(byteArrayOf(), "a.pdf", null) },
            { FuelReceiptInput(pdf, "a.html", null) },
        ).forEach { assertTrue(runCatching(it).isFailure) }
    }

    @Test fun `uploads sequential authenticated chunks and verifies complete receipt identity`() = runBlocking {
        val bytes = pdf + ByteArray(512 * 1024)
        val payloads = mutableListOf<Map<String, String>>()
        val progress = mutableListOf<Double>()
        val api = api { request ->
            assertEquals("/api/v2/fuel/receipts/2026-10/$id/chunk", request.url.encodedPath)
            assertEquals("Bearer token", request.header("Authorization"))
            payloads += json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject.mapValues { it.value.jsonPrimitive.content }
            200 to if (payloads.size == 1) """{"complete":false,"received":1,"totalChunks":2}""" else response(receipt.copy(sizeBytes = bytes.size))
        }
        assertEquals(id, api.upload("token", "2026-10", id, "fiscal", FuelReceiptInput(bytes, "Чек.pdf", null), progress::add).id)
        assertEquals(setOf("uploadId", "kind", "fileName", "mimeType", "chunkIndex", "totalChunks", "chunkBase64"), payloads.first().keys)
        assertEquals(payloads[0]["uploadId"], payloads[1]["uploadId"])
        assertEquals("fiscal", payloads.first()["kind"])
        assertEquals(listOf(.5, 1.0), progress)
        assertArrayEquals(bytes, Base64.getDecoder().decode(payloads[0]["chunkBase64"]) + Base64.getDecoder().decode(payloads[1]["chunkBase64"]))
        listOf("{}", response(receipt, false), response(receipt.copy(kind = "bank")), response(receipt.copy(month = "2026-09")), response(receipt.copy(sizeBytes = 1)), response(receipt.copy(mimeType = "image/png")), response(receipt.copy(id = "e13976eb-7ff9-42e4-b5cd-f63222ae55ff"))).forEach { body ->
            assertTrue(runCatching { api { 200 to body }.upload("t", "2026-10", id, "fiscal", FuelReceiptInput(pdf, "Чек.pdf", null)) {} }.isFailure)
        }
    }

    @Test fun `list permits multiple receipts in a month but rejects duplicate ids and malformed metadata`() = runBlocking {
        val receipts = listOf(receipt, receipt.copy(id = "e13976eb-7ff9-42e4-b5cd-f63222ae55ff", kind = "bank"))
        val body = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(FuelReceipt.serializer()), receipts)
        assertEquals(2, api { 200 to """{"receipts":$body}""" }.fetch("t").size)
        listOf("{}", """{"receipts":[${json.encodeToString(FuelReceipt.serializer(), receipt)},${json.encodeToString(FuelReceipt.serializer(), receipt)}]}""").forEach { invalid ->
            assertTrue(runCatching { api { 200 to invalid }.fetch("t") }.isFailure)
        }
    }

    @Test fun `cache isolates metadata and previews by owner and keeps failed replacement`() = runBlocking {
        val directory = Files.createTempDirectory("fuel-receipts").toFile()
        try {
            val cache = FileFuelReceiptsCache(directory, json)
            cache.save("a", listOf(receipt))
            assertEquals(listOf(receipt), cache.read("a"))
            assertTrue(cache.read("b").isEmpty())
            var downloads = 0
            val api = object : FuelReceiptsApi {
                override suspend fun fetch(token: String) = listOf(receipt)
                override suspend fun upload(token: String, month: String, receiptId: String, kind: String, input: FuelReceiptInput, onProgress: (Double) -> Unit): FuelReceipt = error("network")
                override suspend fun download(token: String, receiptId: String): ByteArray { downloads++; return pdf }
                override suspend fun delete(token: String, receiptId: String) = Unit
            }
            val a = FuelReceiptsRepository("a", api, cache, directory)
            val b = FuelReceiptsRepository("b", api, cache, directory)
            a.hydrate()
            assertEquals(listOf(receipt), a.receipts.value)
            assertTrue(runCatching { a.upload("t", receipt.month, receipt.id, receipt.kind, FuelReceiptInput(pdf, "Чек.pdf", null), false) {} }.isFailure)
            assertEquals(listOf(receipt), a.receipts.value)
            a.preview("t", receipt, false)
            a.preview("t", receipt, true)
            assertEquals(1, downloads)
            assertTrue(runCatching { b.preview("t", receipt, true) }.isFailure)
            assertTrue(runCatching { a.upload("t", receipt.month, receipt.id, "bank", FuelReceiptInput(pdf, "Чек.pdf", null), false) {} }.isFailure)
            assertTrue(runCatching { a.delete("t", receipt, true) }.isFailure)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `auth denial clears private cache and blocks cached actions until successful refresh`() = runBlocking {
        assertEquals(401, (runCatching { api { 401 to "{}" }.fetch("t") }.exceptionOrNull() as FuelReceiptsFailure).status)
        val directory = Files.createTempDirectory("fuel-receipts-auth").toFile()
        try {
            val cache = FileFuelReceiptsCache(directory, json)
            var denied = false
            val api = object : FuelReceiptsApi {
                override suspend fun fetch(token: String): List<FuelReceipt> {
                    if (denied) throw FuelReceiptsFailure("expired", 401)
                    return listOf(receipt)
                }
                override suspend fun upload(token: String, month: String, receiptId: String, kind: String, input: FuelReceiptInput, onProgress: (Double) -> Unit): FuelReceipt = error("unexpected")
                override suspend fun download(token: String, receiptId: String) = pdf
                override suspend fun delete(token: String, receiptId: String) = Unit
            }
            val repository = FuelReceiptsRepository("owner", api, cache, directory)
            repository.refresh("t")
            val file = repository.preview("t", receipt, false)
            assertTrue(file.isFile)
            denied = true
            assertTrue(runCatching { repository.refresh("t") }.isFailure)
            assertTrue(repository.receipts.value.isEmpty())
            assertTrue(cache.read("owner").isEmpty())
            assertFalse(file.exists())
            assertTrue(runCatching { repository.preview("t", receipt, true) }.isFailure)
            assertTrue(runCatching { repository.delete("t", receipt, false) }.isFailure)
            denied = false
            repository.refresh("new-token")
            assertTrue(repository.preview("new-token", receipt, false).isFile)
        } finally { directory.deleteRecursively() }
    }

    @Test fun `replacing and deleting one id preserves other receipts in the same month`() = runBlocking {
        val directory = Files.createTempDirectory("fuel-receipts-siblings").toFile()
        try {
            val sibling = receipt.copy(id = "e13976eb-7ff9-42e4-b5cd-f63222ae55ff", kind = "bank")
            val cache = FileFuelReceiptsCache(directory, json)
            val api = object : FuelReceiptsApi {
                override suspend fun fetch(token: String) = listOf(receipt, sibling)
                override suspend fun upload(token: String, month: String, receiptId: String, kind: String, input: FuelReceiptInput, onProgress: (Double) -> Unit) = receipt.copy(fileName = input.fileName)
                override suspend fun download(token: String, receiptId: String) = pdf
                override suspend fun delete(token: String, receiptId: String) { assertEquals(id, receiptId) }
            }
            val repository = FuelReceiptsRepository("owner", api, cache, directory)
            repository.refresh("t")
            val replaced = repository.upload("t", receipt.month, id, "fiscal", FuelReceiptInput(pdf, "Новый чек.pdf", null), false) {}
            assertEquals("Новый чек.pdf", replaced.fileName)
            assertEquals(2, repository.receipts.value.size)
            repository.delete("t", replaced, false)
            assertEquals(listOf(sibling), repository.receipts.value)
            assertEquals(listOf(sibling), cache.read("owner"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun `receipt-only months survive final record deletion without duplicates or archive duplication`() {
        val receipts = listOf(
            receipt,
            receipt.copy(id = "2bd0566e-f2b9-4d8e-8256-a34577f42c93", kind = "bank"),
            receipt.copy(id = "2bd0566e-f2b9-4d8e-8256-a34577f42c94", month = "2025-09"),
            receipt.copy(id = "2bd0566e-f2b9-4d8e-8256-a34577f42c95", month = "2026-04"),
            receipt.copy(id = "2bd0566e-f2b9-4d8e-8256-a34577f42c96", month = "2026-08"),
        )
        val lastRecord = FuelRecord(id = "last", date = "2026-10-02", mileage = 100.0)
        val archived = FuelRecord(id = "archive", date = "2026-04-02", liters = 10.0)
        val adjustment = FuelRecord(id = "adjustment", recordType = FuelRecordType.Adjustment, monthKey = "2026-08", date = "2026-10-01")
        assertEquals(listOf("2025-09"), fuelReceiptOnlyMonths(receipts, listOf(lastRecord, archived, adjustment)))
        assertEquals(listOf("2026-10", "2025-09"), fuelReceiptOnlyMonths(receipts, listOf(archived, adjustment)))
    }

    private fun response(value: FuelReceipt, complete: Boolean = true) = """{"complete":$complete,"receipt":${json.encodeToString(FuelReceipt.serializer(), value)}}"""
    private fun api(response: (okhttp3.Request) -> Pair<Int, String>) = OkHttpFuelReceiptsApi("https://example.test", json,
        OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = response(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody()).build()
        }.build())
}
