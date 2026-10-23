package ru.lumastack.lumawork.data.auth

import ru.lumastack.lumawork.data.gsm.GsmProfile
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import ru.lumastack.lumawork.domain.model.UserProfile
import ru.lumastack.lumawork.data.session.RememberedEmailStore
import ru.lumastack.lumawork.data.session.SessionStore
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.model.AppUser

class AuthRepositoryTest {
    @Test
    fun `GSM import merges fresh server profile and persists updated session`() = runBlocking {
        val api = FakeAuthApi()
        val remote = UserProfile(firstName = "Серверное имя", workEmail = "Keep@Example.COM", city = "Ялта", departmentTitle = "Dept")
        api.updateProfile("token", remote)
        val store = FakeSessionStore(session())
        val repository = AuthRepository(api, store, FakeEmailStore())
        val gsm = GsmProfile(employeeFullName = "Иванов Иван Иванович", employeeJobTitle = "Инженер", employeeAddress = "г. Алушта, ул. Тестовая")
        repository.fillProfileFromGsm(gsm, "1")
        val expected = remote.copy(lastName = "Иванов", middleName = "Иванович", jobTitle = "Инженер")
        assertEquals(expected, repository.session?.user?.profile)
        assertEquals(expected, store.read()?.user?.profile)
        val writes = api.profileWrites
        repository.fillProfileFromGsm(gsm, "1")
        assertEquals(writes, api.profileWrites)
    }

    @Test
    fun `GSM import rejects other account before any profile write`() = runBlocking {
        val api = FakeAuthApi()
        val repository = repository(api, session())
        val result = runCatching { repository.fillProfileFromGsm(GsmProfile(employeeFullName = "Иванов Иван Иванович"), "other") }
        assertEquals(true, result.isFailure)
        assertEquals(0, api.profileWrites)
        assertEquals("1", repository.session?.user?.id)
    }

    @Test
    fun `failed fresh profile read makes no user mutation`() = runBlocking {
        val api = FakeAuthApi(currentUserError = IOException("offline"))
        val repository = repository(api, session())
        val result = runCatching { repository.fillProfileFromGsm(GsmProfile(), "1") }
        assertEquals(true, result.isFailure)
        assertEquals(0, api.profileWrites)
        assertNull(repository.session?.user?.profile)
    }

    @Test
    fun `late profile response cannot restore a session after logout`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = FakeAuthApi(beforeProfileUpdate = { started.complete(Unit); release.await() })
        val repository = repository(api, session())
        val saving = async { runCatching { repository.updateProfile(UserProfile(firstName = "Updated")) } }
        started.await()
        repository.logout()
        release.complete(Unit)
        assertEquals(true, saving.await().isFailure)
        assertNull(repository.session)
    }
    @Test
    fun `saved profile updates the encrypted session and avatar mutations keep the profile`() = runBlocking {
        val store = FakeSessionStore(session())
        val repository = AuthRepository(FakeAuthApi(), store, FakeEmailStore())
        val profile = UserProfile(firstName = "Тигран", city = "Симферополь")
        repository.updateProfile(profile)
        assertEquals(profile, store.read()?.user?.profile)
        repository.uploadAvatar(byteArrayOf(1))
        assertEquals("https://example.com/avatar.webp", repository.session?.user?.avatarUrl)
        assertEquals(profile, repository.session?.user?.profile)
        repository.deleteAvatar()
        assertNull(repository.session?.user?.avatarUrl)
        assertEquals(profile, AuthRepository(FakeAuthApi(), store, FakeEmailStore()).session?.user?.profile)
    }
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
    private val beforeProfileUpdate: suspend () -> Unit = {},
) : AuthApi {
    var requestedEmail: String? = null
    var profileWrites = 0
    private var user = AppUser(id = "1", email = "user@example.com", role = "user")

    override suspend fun updateProfile(token: String, profile: UserProfile): AppUser {
        beforeProfileUpdate()
        profileWrites += 1
        return user.copy(profile = profile).also { user = it }
    }
    override suspend fun uploadAvatar(token: String, bytes: ByteArray): AppUser = user.copy(avatarUrl = "https://example.com/avatar.webp").also { user = it }
    override suspend fun deleteAvatar(token: String): AppUser = user.copy(avatarUrl = null).also { user = it }

    override suspend fun requestCode(email: String) {
        requestedEmail = email
    }

    override suspend fun verifyCode(email: String, code: String): AppSession = error("unused")

    override suspend fun currentUser(token: String): AppUser {
        currentUserError?.let { throw it }
        return user
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

