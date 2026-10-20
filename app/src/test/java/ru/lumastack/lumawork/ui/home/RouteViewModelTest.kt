package ru.lumastack.lumawork.ui.home

import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.lumastack.lumawork.data.route.RouteApi
import ru.lumastack.lumawork.data.route.RouteCache
import ru.lumastack.lumawork.data.route.RouteRepository
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.model.AppUser
import ru.lumastack.lumawork.domain.route.RouteDayRecord
import ru.lumastack.lumawork.domain.route.RouteEndpointKind
import ru.lumastack.lumawork.domain.route.RouteEndpointRole
import ru.lumastack.lumawork.domain.route.RouteWorkType
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.route.RouteSettings
import ru.lumastack.lumawork.domain.route.defaultRouteDay

@OptIn(ExperimentalCoroutinesApi::class)
class RouteViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 10, 16)
    private val session = AppSession("token", AppUser("owner", "user@example.com", "user"))
    private val settings = RouteSettings.from(null)
    private val cache = MemoryRouteCache()
    private val api = DeferredRouteApi()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `refresh cannot replace an edit made while request was in flight`() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.updateStop(model.state.value.record.stops[1].id, "Локальный адрес", "")
        runCurrent()
        api.responseFor(today).complete(defaultRouteDay(today.toString(), settings).copy(distanceKm = 999))
        advanceUntilIdle()

        assertEquals("Локальный адрес", model.state.value.record.stops[1].address)
        assertEquals("Локальный адрес", cache.days[today.toString()]?.stops?.get(1)?.address)
    }

    @Test fun `edit before initial cache read is not replaced by cached data`() = runTest(dispatcher) {
        cache.days[today.toString()] = defaultRouteDay(today.toString(), settings).let { day ->
            day.copy(stops = day.stops.mapIndexed { i, stop -> if (i == 1) stop.copy(address = "Старый адрес") else stop })
        }
        val model = model(offline = true)
        model.updateStop(model.state.value.record.stops[1].id, "Новый адрес", "")
        advanceUntilIdle()

        assertEquals("Новый адрес", model.state.value.record.stops[1].address)
        assertEquals("Новый адрес", cache.days[today.toString()]?.stops?.get(1)?.address)
    }

    @Test fun `repeated send during same frame submits once`() = runTest(dispatcher) {
        val model = model(offline = true)
        advanceUntilIdle()
        model.updateStop(model.state.value.record.stops[1].id, "Клиент", "")
        model.updateDistance("12")
        model.updateOdometer("1000")
        model.send()
        model.send()
        advanceUntilIdle()

        assertEquals(1, api.sent.size)
        assertTrue(model.state.value.record.sent)
        assertEquals("Клиент", model.state.value.record.stops[1].address)
        assertEquals(12, model.state.value.record.distanceKm)
    }

    @Test fun `switching dates immediately persists each edited snapshot under its own day`() = runTest(dispatcher) {
        val model = model(offline = true)
        advanceUntilIdle()
        model.updateStop(model.state.value.record.stops[1].id, "Сегодня", "00001")
        model.selectDate(today.minusDays(1))
        model.updateStop(model.state.value.record.stops[1].id, "Вчера", "00002")
        model.selectDate(today)
        advanceUntilIdle()

        assertEquals("Сегодня", model.state.value.record.stops[1].address)
        assertEquals("Вчера", cache.days[today.minusDays(1).toString()]?.stops?.get(1)?.address)
        assertEquals("00001", cache.days[today.toString()]?.stops?.get(1)?.requestNumber)
    }

    @Test fun `late response for previous day cannot enter selected day or replace its cache`() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.selectDate(today.minusDays(1))
        runCurrent()
        api.responseFor(today.minusDays(1)).complete(defaultRouteDay(today.minusDays(1).toString(), settings).copy(distanceKm = 22))
        api.responseFor(today).complete(defaultRouteDay(today.toString(), settings).copy(distanceKm = 99))
        advanceUntilIdle()

        assertEquals(today.minusDays(1).toString(), model.state.value.record.date)
        assertEquals(22, model.state.value.record.distanceKm)
        assertNull(cache.days[today.toString()])
        assertEquals(22, cache.days[today.minusDays(1).toString()]?.distanceKm)
    }

    @Test fun `route edits preserve endpoint and one middle point invariants`() = runTest(dispatcher) {
        val model = model(offline = true)
        advanceUntilIdle()
        val base = model.state.value.record
        model.removeStop(base.stops[1].id)
        model.removeStop(base.stops.first().id)
        assertEquals(base, model.state.value.record)
        model.addStop()
        val added = model.state.value.record.stops[2]
        model.updateStop(added.id, "Новая точка", "000123")
        model.reorderStop(added.id, base.stops[1].id)
        assertEquals(added.id, model.state.value.record.stops[1].id)
        model.reorderStop(added.id, base.stops.last().id)
        assertEquals(added.id, model.state.value.record.stops[1].id)
        model.removeStop(base.stops.last().id)
        assertEquals(base.stops.last(), model.state.value.record.stops.last())
        model.removeStop(added.id)
        assertEquals(3, model.state.value.record.stops.size)
        advanceUntilIdle()
        assertEquals(model.state.value.record, cache.days[today.toString()])
    }

    @Test fun `only an address change invalidates mileage when editing a point`() = runTest(dispatcher) {
        val model = model(offline = true)
        advanceUntilIdle()
        val id = model.state.value.record.stops[1].id
        model.updateStop(id, "Клиент", "0001")
        model.updateDistance("15")
        model.updateStop(id, " Клиент ", "0002")
        assertEquals(15, model.state.value.record.distanceKm)
        model.updateStop(id, "Другой клиент", "0002")
        assertNull(model.state.value.record.distanceKm)
        assertFalse(model.state.value.record.sent)
        advanceUntilIdle()
    }

    @Test fun `send completion preserves edits and a subsequently selected day`() = runTest(dispatcher) {
        api.sendGate = CompletableDeferred()
        val model = model(offline = true)
        advanceUntilIdle()
        val id = model.state.value.record.stops[1].id
        model.updateStop(id, "Клиент", "0001")
        model.updateDistance("15")
        model.updateOdometer("1000")
        model.send()
        runCurrent()
        model.updateStop(id, "Новый адрес", "0001")
        model.selectDate(today.minusDays(1))
        runCurrent()
        api.sendGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(today.minusDays(1).toString(), model.state.value.record.date)
        assertEquals("Новый адрес", cache.days[today.toString()]?.stops?.get(1)?.address)
        assertEquals(false, cache.days[today.toString()]?.sent)
        assertFalse(model.state.value.isSending)
    }

    @Test fun `missing home address cannot replace a valid endpoint`() = runTest(dispatcher) {
        val model = model(offline = true)
        advanceUntilIdle()
        model.updateEndpoint(RouteEndpointRole.Start, RouteEndpointKind.Home)
        assertEquals(settings.startAddress, model.state.value.record.stops.first().address)
    }

    @Test fun `successful send is saved and survives reentry on same date`() = runTest(dispatcher) {
        val model = model(offline = true)
        advanceUntilIdle()
        model.updateStop(model.state.value.record.stops[1].id, "Клиент", "00001")
        model.updateDistance("12")
        model.updateOdometer("1000")
        model.send()
        advanceUntilIdle()
        model.selectDate(today.minusDays(1))
        advanceUntilIdle()
        model.selectDate(today)
        advanceUntilIdle()
        assertTrue(model.state.value.record.sent)
        assertEquals("Клиент", model.state.value.record.stops[1].address)
        assertEquals(12, model.state.value.record.distanceKm)
    }

    @Test fun `work types retain independent drafts on the same day`() = runTest(dispatcher) {
        val model = model(offline = true)
        advanceUntilIdle()
        model.updateStop(model.state.value.record.stops[1].id, "POS адрес", "00001")
        model.selectWorkType(RouteWorkType.Arm)
        advanceUntilIdle()
        assertFalse(model.state.value.record.hasWorkDataForTest())
        model.updateStop(model.state.value.record.stops[1].id, "АРМ адрес", "00002")
        model.selectWorkType(RouteWorkType.Pos)
        advanceUntilIdle()
        assertEquals("POS адрес", model.state.value.record.stops[1].address)
        model.selectWorkType(RouteWorkType.Arm)
        advanceUntilIdle()
        assertEquals("АРМ адрес", model.state.value.record.stops[1].address)
    }

    @Test fun `monthly odometer is shared between days and work types`() = runTest(dispatcher) {
        val model = model(offline = true)
        advanceUntilIdle()
        model.updateOdometer("12345")
        model.selectDate(today.minusDays(1))
        advanceUntilIdle()
        assertEquals(12345, model.state.value.record.periodStartOdometer)
        model.selectWorkType(RouteWorkType.Arm)
        advanceUntilIdle()
        assertEquals(12345, model.state.value.record.periodStartOdometer)
        model.selectDate(today.minusMonths(1))
        advanceUntilIdle()
        assertNull(model.state.value.record.periodStartOdometer)
    }

    @Test fun `closed requests only fill an empty POS route and never overwrite edits`() = runTest(dispatcher) {
        val model = model(offline = true)
        advanceUntilIdle()
        val requests = listOf(SimpleOneRequest("closed", "REQ1", incomingNumber = "00023",
            address = "Ялта, Ленина, 1", closedAt = "16.10.2026 12:00:00"))
        model.autoImportClosedRequests(requests)
        assertEquals("00023", model.state.value.record.stops[1].requestNumber)
        model.updateStop(model.state.value.record.stops[1].id, "Мой адрес", "00099")
        model.autoImportClosedRequests(requests)
        assertEquals("Мой адрес", model.state.value.record.stops[1].address)
        model.selectWorkType(RouteWorkType.Arm)
        advanceUntilIdle()
        model.autoImportClosedRequests(requests)
        assertEquals("", model.state.value.record.stops[1].address)
    }

    @Test fun `clearing monthly odometer does not resurrect an older day value`() = runTest(dispatcher) {
        cache.days[today.minusDays(1).toString()] = defaultRouteDay(today.minusDays(1).toString(), settings).copy(periodStartOdometer = 1000)
        val model = model(offline = true)
        advanceUntilIdle()
        model.updateOdometer("")
        model.selectDate(today.minusDays(1))
        advanceUntilIdle()
        assertNull(model.state.value.record.periodStartOdometer)
    }

    @Test fun `late POS response cannot replace selected ARM on the same date`() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.selectWorkType(RouteWorkType.Arm)
        runCurrent()
        api.responseFor(today, RouteWorkType.Arm).complete(defaultRouteDay(today.toString(), settings, RouteWorkType.Arm).copy(distanceKm = 25))
        api.responseFor(today).complete(defaultRouteDay(today.toString(), settings).copy(distanceKm = 99))
        advanceUntilIdle()
        assertEquals(RouteWorkType.Arm, model.state.value.record.workType)
        assertEquals(25, model.state.value.record.distanceKm)
    }

    @Test fun `network failure queues exact report and reentry retries it once`() = runTest(dispatcher) {
        api.sendFailure = ru.lumastack.lumawork.data.route.RouteFailure.Network(java.io.IOException("offline"))
        val model = model(offline = true)
        advanceUntilIdle()
        model.updateStop(model.state.value.record.stops[1].id, "Клиент", "0001")
        model.updateDistance("12")
        model.updateOdometer("1000")
        model.send()
        advanceUntilIdle()
        assertTrue(model.state.value.record.queuedForSend)
        assertTrue(cache.days[today.toString()]!!.queuedForSend)
        api.sendFailure = null
        val reentered = model()
        advanceUntilIdle()
        assertTrue(reentered.state.value.record.sent)
        assertFalse(reentered.state.value.record.queuedForSend)
        assertEquals(2, api.sent.size)
    }

    @Test fun `profile address changes update endpoints without losing customer stops`() = runTest(dispatcher) {
        val model = model(offline = true)
        advanceUntilIdle()
        model.updateStop(model.state.value.record.stops[1].id, "Клиент", "0001")
        model.updateDistance("15")
        model.updateSettings(RouteSettings("Новый склад", "Дом"))
        assertEquals("Новый склад", model.state.value.record.stops.first().address)
        assertEquals("Новый склад", model.state.value.record.stops.last().address)
        assertEquals("Клиент", model.state.value.record.stops[1].address)
        assertNull(model.state.value.record.distanceKm)
        model.updateEndpoint(RouteEndpointRole.Finish, RouteEndpointKind.Home)
        assertEquals("Дом", model.state.value.record.stops.last().address)
        advanceUntilIdle()
    }

    @Test fun `confirmed GSM profile refreshes previously missing odometer without losing route draft`() = runTest(dispatcher) {
        val repository = RouteRepository(api, cache)
        val model = RouteViewModel(repository, session, false, today)
        api.responseFor(today).complete(null)
        advanceUntilIdle()
        assertNull(model.state.value.record.periodStartOdometer)
        model.updateStop(model.state.value.record.stops[1].id, "Черновик", "00001")
        model.updateDistance("27")
        advanceUntilIdle()
        api.suggestedValue = 10330
        repository.gsmProfileSaved(session.user.id)
        advanceUntilIdle()
        assertEquals(10330, model.state.value.record.periodStartOdometer)
        assertEquals("Черновик", model.state.value.record.stops[1].address)
        assertEquals(27, model.state.value.record.distanceKm)
        assertTrue(api.sent.isEmpty())
    }

    @Test fun `profile event cannot replace manual odometer or cross account`() = runTest(dispatcher) {
        val repository = RouteRepository(api, cache)
        val model = RouteViewModel(repository, session, false, today)
        api.responseFor(today).complete(null)
        advanceUntilIdle()
        api.suggestedValue = 10330
        repository.gsmProfileSaved("another-account")
        advanceUntilIdle()
        assertNull(model.state.value.record.periodStartOdometer)
        model.updateOdometer("9000")
        advanceUntilIdle()
        repository.gsmProfileSaved(session.user.id)
        advanceUntilIdle()
        assertEquals(9000, model.state.value.record.periodStartOdometer)
    }

    private fun RouteDayRecord.hasWorkDataForTest() = stops.drop(1).dropLast(1).any { it.address.isNotBlank() || it.requestNumber.isNotBlank() }

    private fun model(offline: Boolean = false) = RouteViewModel(RouteRepository(api, cache), session, offline, today)
}

private class MemoryRouteCache : RouteCache {
    val days = mutableMapOf<String, RouteDayRecord>()
    private val odometers = mutableMapOf<String, Int?>()
    override suspend fun hasOdometerSetting(userId: String, monthKey: String) = odometers.containsKey(monthKey) || readOdometer(userId, monthKey) != null
    override suspend fun readOdometer(userId: String, monthKey: String) = if (odometers.containsKey(monthKey)) odometers[monthKey] else days.values.firstNotNullOfOrNull { it.periodStartOdometer.takeIf { _ -> it.date.startsWith(monthKey) } }
    override suspend fun saveOdometer(userId: String, monthKey: String, value: Int?) { odometers[monthKey] = value }
    override suspend fun read(userId: String, date: String, workType: RouteWorkType) = days[workType.storageKey(date)]
    override suspend fun readQueued(userId: String) = days.values.filter { it.queuedForSend }
    override suspend fun readMonth(userId: String, monthKey: String) = days.values.filter { it.date.startsWith("$monthKey-") }
    override suspend fun save(userId: String, record: RouteDayRecord) { days[record.workType.storageKey(record.date)] = record }
}

private class DeferredRouteApi : RouteApi {
    private val responses = mutableMapOf<String, CompletableDeferred<RouteDayRecord?>>()
    fun responseFor(date: LocalDate, workType: RouteWorkType = RouteWorkType.Pos) = responses.getOrPut(workType.storageKey(date.toString())) { CompletableDeferred() }
    var suggestedValue: Int? = null
    override suspend fun suggestedOdometer(token: String, month: String): Int? = suggestedValue
    var sendGate: CompletableDeferred<Unit>? = null
    var sendFailure: Exception? = null
    val sent = mutableListOf<RouteDayRecord>()
    override suspend fun fetchDay(token: String, date: String, settings: RouteSettings, workType: RouteWorkType) = withContext(NonCancellable) {
        responses.getOrPut(workType.storageKey(date)) { CompletableDeferred() }.await()
    }
    override suspend fun sendDay(token: String, record: RouteDayRecord) { sent += record; sendGate?.await(); sendFailure?.let { throw it } }
}
