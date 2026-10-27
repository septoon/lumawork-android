package ru.lumastack.lumawork.data.auth

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.lumastack.lumawork.domain.model.UserProfile

class ProfileApiTest {
    @Test
    fun `authenticated startup sends device and version headers`() = runBlocking {
        val headers = mapOf("X-LumaWork-Device-ID" to "11111111-2222-3333-4444-555555555555", "X-LumaWork-Device-Model" to "Samsung SM-G998B", "X-LumaWork-Version" to "0.1.24")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            headers.forEach { (name, value) -> assertEquals(value, request.header(name)) }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"user":{"id":"1","email":"user@example.test","role":"user"}}""".toResponseBody()).build()
        }.build()
        assertEquals("1", OkHttpAuthApi("https://api.test", client, deviceHeaders = headers).currentUser("token").id)
    }

    @Test
    fun `profile and avatar use the iOS endpoints and user envelope`() = runBlocking {
        val calls = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("Bearer session-token", request.header("Authorization"))
            calls += "${request.method} ${request.url.encodedPath}"
            if (request.method == "PUT") {
                val payload = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
                assertEquals("Симферополь", payload["city"]?.jsonPrimitive?.content)
                assertEquals("", payload["personalPhone"]?.jsonPrimitive?.content)
                assertEquals("Ленина, 1", payload["routeHomeAddress"]?.jsonPrimitive?.content)
            }
            if (request.method == "POST") {
                val payload = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
                assertEquals("AQID", payload["imageBase64"]?.jsonPrimitive?.content)
                assertEquals("image/jpeg", payload["mimeType"]?.jsonPrimitive?.content)
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"user":{"id":"1","email":"user@example.com","role":"user","avatarUrl":"https://example.com/avatar.webp","profile":{"city":"Симферополь","routeWarehouseAddress":"Склад, 1","routeHomeAddress":"Ленина, 1"}}}""".toResponseBody()).build()
        }.build()
        val api = OkHttpAuthApi("https://api.test", client)

        assertEquals("Симферополь", api.updateProfile("session-token", UserProfile(city = "Симферополь", personalPhone = "", routeHomeAddress = "Ленина, 1")).profile?.city)
        val restored = api.currentUser("session-token").profile
        assertEquals("Ленина, 1", restored?.routeHomeAddress)
        assertEquals("Склад, 1", restored?.routeWarehouseAddress)
        assertEquals("https://example.com/avatar.webp", api.uploadAvatar("session-token", byteArrayOf(1, 2, 3)).avatarUrl)
        api.deleteAvatar("session-token")
        assertEquals(listOf("PUT /api/v2/profile", "GET /me", "POST /api/v2/profile/avatar", "DELETE /api/v2/profile/avatar"), calls)
    }
}
