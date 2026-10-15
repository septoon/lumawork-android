package ru.lumastack.lumawork.data.auth

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import ru.lumastack.lumawork.data.session.RememberedEmailStore
import ru.lumastack.lumawork.data.session.SessionStore
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.model.AppUser

class AuthRepositoryTest {
    @Test
    fun `email is trimmed and lowercased before request`() = runBlocking {
        val api = FakeAuthApi()
        val repository = repository(api = api, session = null)

        val normalized = repository.requestCode("  User@Example.COM ")

        assertEquals("user@example.com", normalized)
        assertEquals("user@example.com", api.requestedEmail)
    }

    @Test
    fun `transient restore failure keeps cached session`() = runBlocking {
        val cached = session()
        val api = FakeAuthApi(currentUserError = AuthFailure.Network(IOException("offline")))
        val store = FakeSessionStore(cached)
        val repository = AuthRepository(api, store, FakeEmailStore())

        val result = repository.restoreSession()

        assertEquals(RestoreResult.Offline(cached), result)
        assertSame(cached, repository.session)
        assertEquals(0, store.clearCount)
    }

    @Test
    fun `unauthorized restore clears cached session`() = runBlocking {
        val cached = session()
        val store = FakeSessionStore(cached)
        val repository = AuthRepository(
            FakeAuthApi(currentUserError = AuthFailure.Http(401, "unauthorized")),
            store,
            FakeEmailStore(),
        )

        val result = repository.restoreSession()

        assertEquals(RestoreResult.SignedOut, result)
        assertNull(repository.session)
        assertEquals(1, store.clearCount)
    }

    private fun repository(api: FakeAuthApi, session: AppSession?): AuthRepository =
        AuthRepository(api, FakeSessionStore(session), FakeEmailStore())

    private fun session() = AppSession(
        token = "token",
        user = AppUser(id = "1", email = "user@example.com", role = "user"),
    )
}

private class FakeAuthApi(
    private val currentUserError: Exception? = null,
) : AuthApi {
    var requestedEmail: String? = null

    override suspend fun requestCode(email: String) {
        requestedEmail = email
    }

    override suspend fun verifyCode(email: String, code: String): AppSession = error("unused")

    override suspend fun currentUser(token: String): AppUser {
        currentUserError?.let { throw it }
        return AppUser(id = "1", email = "user@example.com", role = "user")
    }

    override suspend fun logout(token: String) = Unit
}

private class FakeSessionStore(initial: AppSession?) : SessionStore {
    private var value = initial
    var clearCount = 0

    override fun read(): AppSession? = value

    override fun save(session: AppSession) {
        value = session
    }

    override fun clear() {
        clearCount += 1
        value = null
    }
}

private class FakeEmailStore : RememberedEmailStore {
    private var value = ""

    override fun read(): String = value

    override fun save(email: String) {
        value = email
    }
}

