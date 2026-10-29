package ru.lumastack.lumawork.data.simpleone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.data.ftp.*

class SimpleOneAccessTest {
    private var owner: String? = "owner"
    private var session: StoredSimpleOneSession? = StoredSimpleOneSession("owner", "so-token", "engineer")
    private val access = SimpleOneAccess({ owner }, { id -> session?.takeIf { it.ownerUserId == id } })

    @Test fun `missing SO session and wrong app owner are denied`() {
        session = null
        assertThrows(SimpleOneAccess.Denied::class.java) { access.require("owner") }
        session = StoredSimpleOneSession("owner", "so-token", "engineer")
        assertThrows(SimpleOneAccess.Denied::class.java) { access.require("other") }
        owner = null
        assertEquals("Доступ запрещен", assertThrows(SimpleOneAccess.Denied::class.java) { access.require("owner") }.message)
    }
    @Test fun `only an explicit SO denial revokes credentials`() {
        val guarded = SimpleOneAccess({ owner }, { session }, { session = null })
        fun request(soDenied: Boolean) {
            val client = okhttp3.OkHttpClient.Builder()
                .addInterceptor(SimpleOneAccessInterceptor(guarded))
                .addInterceptor { chain ->
                    okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(403).message("Forbidden")
                        .apply { if (soDenied) header("X-SimpleOne-Access", "denied") }
                        .body(okhttp3.ResponseBody.create(null, "")).build()
                }.build()
            client.newCall(okhttp3.Request.Builder().url("https://example.test").build()).execute().close()
        }
        request(false)
        assertNotNull(session)
        assertThrows(SimpleOneAccess.Denied::class.java) { request(true) }
        assertNull(session)
    }

    @Test fun `authorized owner retains access`() {
        assertEquals("so-token", access.require("owner").authKey)
    }
    @Test fun `session revocation rejects a late result`() = runBlocking {
        try {
            access.protect("owner") { session = null; "secret" }
            fail("Late result exposed")
        } catch (_: SimpleOneAccess.Denied) { }
    }
    @Test fun `FTP default boundary denies both cache reads and network requests`() = runBlocking {
        var reads = 0; var calls = 0
        val cache = object : FtpCache {
            override suspend fun read(ownerUserId: String): FtpSnapshot? { reads++; return FtpSnapshot() }
            override suspend fun save(ownerUserId: String, snapshot: FtpSnapshot) = Unit
        }
        val api = object : FtpApi {
            override suspend fun files(token: String, path: String): FtpDirectoryResponse { calls++; return FtpDirectoryResponse(path) }
        }
        val repository = FtpRepository(api, cache)
        try { repository.read("owner"); fail("Cache exposed") } catch (_: SimpleOneAccess.Denied) { }
        try { repository.load("owner", "app-token", "/"); fail("Network reached") } catch (_: SimpleOneAccess.Denied) { }
        assertEquals(0, reads); assertEquals(0, calls)
    }
}

fun testSimpleOneAccess(owner: String = "owner") = SimpleOneAccess({ owner }, { id ->
    StoredSimpleOneSession(id, "test-so-token", "engineer")
})
