package ru.lumastack.lumawork.data.fuel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.fuel.*

class FuelImportApiTest {
    @Test fun `preview posts authenticated base64 files and decodes optional incomplete fields`() = runBlocking {
        val api = api("""{"items":[{"id":"id","fileName":"test.xlsx","fileHash":"hash","status":"warning","entries":[{"row":9}],"unknown":true}]}""") { request ->
            assertEquals("/api/v2/fuel/imports/preview", request.url.encodedPath)
            assertEquals("Bearer token", request.header("Authorization"))
            assertEquals("POST", request.method)
            assertEquals("UEs=", body(request)["files"]!!.jsonArray[0].jsonObject["dataBase64"]!!.jsonPrimitive.content)
        }
        val item = api.preview("token", listOf(FuelImportUpload("test.xlsx", "UEs="))).single()
        assertTrue(item.entries.single().isIncomplete)
        assertNull(item.period)
        assertEquals(FuelImportStatus.Warning, item.status)
    }

    @Test fun `commit sends explicit replacements and numeric corrections without null fields`() = runBlocking {
        val api = api("""{"importedMonths":1,"skippedMonths":1,"importedRefuels":2,"totalLiters":12.5,"totalCost":750,"results":[{"fileName":"bad.xlsx","status":"skipped","message":"Конфликт","refuelCount":0}]}""") { request ->
            assertEquals("/api/v2/fuel/imports/commit", request.url.encodedPath)
            val payload = body(request)
            assertEquals("old", payload["replaceImportIds"]!!.jsonArray.single().jsonPrimitive.content)
            val entry = payload["corrections"]!!.jsonArray.single().jsonObject["entries"]!!.jsonArray.single().jsonObject
            assertEquals(12.5, entry["liters"]!!.jsonPrimitive.double, 0.0)
            assertFalse(entry.containsKey("cost"))
        }
        val result = api.commit("token", listOf(FuelImportUpload("test.xlsx", "UEs=")), setOf("old"),
            listOf(FuelImportCorrection("a".repeat(64), "2026-04", listOf(FuelImportEntryCorrection(9, liters = "12,5")))))
        assertEquals(1, result.importedMonths)
        assertEquals("Конфликт", result.results.single().message)
    }

    @Test fun `error previews do not invalidate corrections for ready files in mixed batch`() = runBlocking {
        val api = api("""{"importedMonths":1,"skippedMonths":1,"importedRefuels":1,"totalLiters":10,"totalCost":500}""") { request ->
            val corrections = body(request)["corrections"]!!.jsonArray
            assertEquals(1, corrections.size)
            assertEquals("a".repeat(64), corrections.single().jsonObject["fileHash"]!!.jsonPrimitive.content)
        }
        val result = api.commit("token", uploads(), emptySet(), listOf(
            FuelImportCorrection("", null, emptyList()), FuelImportCorrection("a".repeat(64), "2026-04", emptyList()),
        ))
        assertEquals(1, result.importedMonths)
    }

    @Test fun `authorization and malformed server responses fail safely`() = runBlocking {
        try { api("{}", 401).preview("token", uploads()); fail("Expected authorization failure") }
        catch (error: FuelImportFailure) { assertTrue(error is FuelImportFailure.Unauthorized) }
        try { api("oops").preview("token", uploads()); fail("Expected invalid response") }
        catch (error: FuelImportFailure) { assertTrue(error is FuelImportFailure.InvalidResponse) }
    }

    private fun uploads() = listOf(FuelImportUpload("test.xlsx", "UEs="))
    private fun body(request: Request) = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
    private fun api(response: String, code: Int = 200, inspect: (Request) -> Unit = {}): OkHttpFuelImportApi = OkHttpFuelImportApi(
        "https://example.test", client = OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test").body(response.toResponseBody()).build()
        }.build(),
    )
}
