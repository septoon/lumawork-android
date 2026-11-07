package ru.lumastack.lumawork.data.personal

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PersonalStateApiTest {
    @Test fun `deleting a favorite sends explicit null with the application json configuration`() = runTest {
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"entries":[{"key":"/folder","value":null}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = OkHttpPersonalStateApi("https://example.test", Json { explicitNulls = false; encodeDefaults = true }, client)
        assertEquals(listOf(PersonalEntry("/folder", null)), api.sync("token", "ftp-favorites", emptyList(), listOf(PersonalEntry("/folder", null))))
        val request = captured!!
        assertEquals("Bearer token", request.header("Authorization"))
        assertEquals("/api/v2/personal-state/ftp-favorites/sync", request.url.encodedPath)
        val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
        val entry = Json.parseToJsonElement(buffer.readUtf8()).jsonObject.getValue("changes").jsonArray.single().jsonObject
        assertTrue(entry.containsKey("value")); assertEquals(JsonNull, entry["value"])
    }
}
