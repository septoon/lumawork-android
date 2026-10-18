package ru.lumastack.lumawork.data.notifications

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.model.AppUser
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneSnapshot

class RequestNotificationsTest {
    private val session = AppSession("fixture-token", AppUser("owner", "engineer@example.test", "user"))
    private fun request(id: String, assignee: String = "Иван") = SimpleOneRequest(
        id, "INC-$id", incomingNumber = "sutsprod-42", registeredAt = "2026-10-18 08:00:00",
        shortDescription = "Ремонт", assignedUser = assignee, terminalId = "123",
    )
    private fun snapshot(time: Long, vararg requests: SimpleOneRequest, fingerprint: String = "login-a") =
        SimpleOneSnapshot(activeRequests = requests.toList(), updatedAtEpochMillis = time, sessionFingerprint = fingerprint)

    @Test fun `first snapshot seeds baseline and restart delivers only newly added requests`() = runTest {
        val cache = MemoryCache(); val api = FakeApi(); val local = mutableListOf<NotificationEvent>()
        var repository = NotificationsRepository(api, cache, { session })
        repository.process(session, snapshot(1, request("1")), false) { local += it; true }
        assertTrue(local.isEmpty()); assertTrue(api.events.isEmpty())
        repository = NotificationsRepository(api, cache, { session })
        repository.process(session, snapshot(2, request("1"), request("2")), false) { local += it; true }
        assertEquals(listOf("2"), local.map { it.requestID })
        assertEquals(listOf("new:2:2026-10-18 08:00:00"), api.events.map { it.eventID })
        assertEquals("SUTSPROD-42", api.events.single().requestNumber)
    }

    @Test fun `equal or stale snapshot timestamps cannot turn detail edits into assignee events`() = runTest {
        val api = FakeApi(); val repository = NotificationsRepository(api, MemoryCache(), { session })
        repository.process(session, snapshot(2, request("1")), false) { true }
        repository.process(session, snapshot(2, request("1", "Петр")), false) { true }
        repository.process(session, snapshot(1, request("1", "Петр")), false) { true }
        assertTrue(api.events.isEmpty())
        repository.process(session, snapshot(3, request("1", " Петр ")), false) { true }
        val event = api.events.single()
        assertEquals("assignee_changed", event.kind)
        assertEquals("Иван", event.oldAssignee); assertEquals("Петр", event.newAssignee)
    }

    @Test fun `new SimpleOne login and different owner seed independent baselines`() = runTest {
        val api = FakeApi(); var active = session
        val repository = NotificationsRepository(api, MemoryCache(), { active })
        repository.process(session, snapshot(1, request("1")), false) { true }
        repository.process(session, snapshot(2, request("9"), fingerprint = "login-b"), false) { true }
        active = session.copy(user = session.user.copy(id = "other"))
        repository.process(active, snapshot(3, request("10")), false) { true }
        assertTrue(api.events.isEmpty())
    }

    @Test fun `failed email retries once and disabled kind never delivers pending email`() = runTest {
        val api = FakeApi(); val repository = NotificationsRepository(api, MemoryCache(), { session })
        repository.process(session, snapshot(1, request("1")), false) { true }
        api.failEmail = true
        repository.process(session, snapshot(2, request("1"), request("2")), false) { true }
        assertEquals(1, api.attempts)
        api.failEmail = false
        repository.process(session, snapshot(3, request("1"), request("2")), false) { true }
        repository.process(session, snapshot(4, request("1"), request("2")), false) { true }
        assertEquals(2, api.attempts); assertEquals(1, api.events.size)
        api.failEmail = true
        repository.process(session, snapshot(5, request("1"), request("2"), request("3")), false) { true }
        repository.setPreference(session, NotificationPreference.EmailNewRequests, false, offline = false)
        api.failEmail = false
        repository.process(session, snapshot(6, request("1"), request("2"), request("3")), false) { true }
        assertEquals(3, api.attempts)
    }

    @Test fun `local switches actually control delivery and repeated event is deduplicated`() = runTest {
        val api = FakeApi(); val repository = NotificationsRepository(api, MemoryCache(), { session })
        val local = mutableListOf<NotificationEvent>()
        repository.process(session, snapshot(1, request("1")), false) { local += it; true }
        repository.setPreference(session, NotificationPreference.LocalNewRequests, false, offline = true)
        repository.process(session, snapshot(2, request("1"), request("2")), false) { local += it; true }
        assertTrue(local.isEmpty())
        repository.setPreference(session, NotificationPreference.LocalNewRequests, true, offline = true)
        repository.process(session, snapshot(3, request("1")), false) { local += it; true }
        repository.process(session, snapshot(4, request("1"), request("2")), false) { local += it; true }
        repository.process(session, snapshot(5, request("1")), false) { local += it; true }
        repository.process(session, snapshot(6, request("1"), request("2")), false) { local += it; true }
        assertEquals(1, local.size)
    }

    @Test fun `stale preferences GET preserves edits and offline local edit preserves remote email flags`() = runTest {
        val api = FakeApi(); val repository = NotificationsRepository(api, MemoryCache(), { session })
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.onFetch = { started.complete(Unit); release.await(); NotificationPreferences(emailNewRequests = false) }
        val loading = async { repository.synchronize(session) }
        started.await()
        repository.setPreference(session, NotificationPreference.LocalNewRequests, false, offline = true)
        release.complete(Unit); loading.await()
        assertFalse(repository.settings(session.user.id).value.preferences.localNewRequests)
        assertFalse(api.saved.last().emailNewRequests)
        assertFalse(api.saved.last().localNewRequests)
    }

    @Test fun `PUT saves serialize and cannot leave older preference on server`() = runTest {
        val api = FakeApi(); val repository = NotificationsRepository(api, MemoryCache(), { session })
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.onSave = { if (api.saved.isEmpty()) { started.complete(Unit); release.await() } }
        val first = async { repository.setPreference(session, NotificationPreference.LocalNewRequests, false, false) }
        started.await()
        val last = async { repository.setPreference(session, NotificationPreference.LocalNewRequests, true, false) }
        release.complete(Unit); first.await(); last.await()
        assertTrue(api.saved.last().localNewRequests)
        assertFalse(repository.settings(session.user.id).value.saving)
    }

    @Test fun `quick full refresh during delayed preferences GET retains initial baseline and delivers the new request`() = runTest {
        val api = FakeApi(); val cache = MemoryCache()
        val repository = NotificationsRepository(api, cache, { session })
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<NotificationEvent>()
        api.onFetch = { started.complete(Unit); release.await(); NotificationPreferences() }
        val snapshots = MutableStateFlow(snapshot(1, request("1")))
        val observer = launch { repository.observeSnapshots(session, snapshots) { delivered.complete(it); true } }
        started.await()
        // Startup must persist only the restored list before the remote preference request can suspend.
        assertEquals(1L, cache.values.getValue("owner").snapshotTime)
        assertEquals(listOf("1"), cache.values.getValue("owner").baseline.map { it.id })
        snapshots.value = snapshot(2, request("1"), request("2"))
        release.complete(Unit)
        assertEquals("2", delivered.await().requestID)
        observer.cancelAndJoin()
        assertEquals(listOf("2"), api.events.map { it.requestID })
    }

    @Test fun `API uses authenticated exact paths flat PUT and canonical event key names`() = runTest {
        val requests = mutableListOf<okhttp3.Request>(); val bodies = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request(); requests += request
            bodies += request.body?.let { body -> okio.Buffer().also(body::writeTo).readUtf8() }.orEmpty()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (request.method == "GET") """{"preferences":{"localNewRequests":false,"localAssigneeChanges":true,"emailNewRequests":false,"emailAssigneeChanges":true}}""" else "{}").toResponseBody()).build()
        }).build()
        val api = OkHttpNotificationsApi("https://fixture.example.test", client = client)
        val preferences = api.fetch(session.token)
        assertFalse(preferences.localNewRequests)
        api.save(session.token, preferences)
        api.send(session.token, NotificationEvent("new:1:", "new_active_request", "1", "SUTSPROD-42", "Ремонт", terminalID = "123"))
        assertEquals(listOf("/api/v2/notifications/preferences", "/api/v2/notifications/preferences", "/api/v2/notifications/events"), requests.map { it.url.encodedPath })
        assertEquals(listOf("GET", "PUT", "POST"), requests.map { it.method })
        assertTrue(requests.all { it.header("Authorization") == "Bearer fixture-token" })
        assertEquals(setOf("localNewRequests", "localAssigneeChanges", "emailNewRequests", "emailAssigneeChanges"), Json.parseToJsonElement(bodies[1]).jsonObject.keys)
        val event = Json.parseToJsonElement(bodies[2]).jsonObject
        assertEquals("\"123\"", event["terminalID"].toString()); assertTrue(event.containsKey("eventID")); assertTrue(event.containsKey("requestID"))
    }

    private class MemoryCache : NotificationsCache {
        val values = mutableMapOf<String, NotificationStorage>()
        override suspend fun read(owner: String) = values[owner]
        override suspend fun save(owner: String, value: NotificationStorage) { values[owner] = value }
    }
    private class FakeApi : NotificationsApi {
        var preferences = NotificationPreferences(); val saved = mutableListOf<NotificationPreferences>()
        val events = mutableListOf<NotificationEvent>(); var failEmail = false; var attempts = 0
        var onFetch: (suspend () -> NotificationPreferences)? = null
        var onSave: (suspend () -> Unit)? = null
        override suspend fun fetch(token: String) = onFetch?.invoke() ?: preferences
        override suspend fun save(token: String, value: NotificationPreferences) { onSave?.invoke(); saved += value; preferences = value }
        override suspend fun send(token: String, event: NotificationEvent) { attempts++; if (failEmail) error("fixture unavailable"); events += event }
    }
}
