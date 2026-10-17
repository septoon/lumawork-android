package ru.lumastack.lumawork.data.simpleone

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleOneAuthApiTest {
    @Test
    fun `sign in and initial requests consume response bodies outside the caller thread`() = runBlocking {
        val callerThread = Thread.currentThread()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val payload = when (request.url.encodedPath) {
                "/v1/auth/login" -> """{"status":"OK","data":{"auth_key":"test-key"}}"""
                "/v1/user/me" -> {
                    assertEquals("auth=test-key", request.header("Cookie"))
                    """{"status":"OK","data":{"sys_id":"user-1","username":"engineer"}}"""
                }
                "/v1/list/itsm_request" -> {
                    assertEquals("auth=test-key", request.header("Cookie"))
                    assertTrue(request.url.queryParameter("condition")!!.contains("assigned_user=user-1"))
                    """{"status":"OK","data":{"items":[]}}"""
                }
                else -> error("Unexpected endpoint")
            }
            val stream = object : FilterInputStream(ByteArrayInputStream(payload.toByteArray())) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    check(Thread.currentThread() !== callerThread) {
                        "Response body must not be read on the UI caller thread"
                    }
                    return super.read(bytes, offset, length)
                }
            }
            val body = object : ResponseBody() {
                private val bufferedSource = stream.source().buffer()
                override fun contentType(): MediaType? = null
                override fun contentLength(): Long = -1
                override fun source(): BufferedSource = bufferedSource
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build()
        }.build()
        val api = OkHttpSimpleOneApi("https://sd.test/v1", client)

        val key = api.login("engineer", "password")
        val user = api.currentUser(key)
        val requests = api.activeRequests(user.sysId, key)

        assertEquals("test-key", key)
        assertEquals("user-1", user.sysId)
        assertTrue(requests.isEmpty())
    }
}
