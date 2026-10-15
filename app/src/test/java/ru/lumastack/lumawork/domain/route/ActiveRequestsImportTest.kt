package ru.lumastack.lumawork.domain.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

class ActiveRequestsImportTest {
    private val date = "2026-08-12"
    private val base = defaultRouteDay(date, RouteSettings("Склад", ""))

    @Test
    fun `active requests replace empty middle and normalize address`() {
        val result = importActiveRequests(
            base.copy(distanceKm = 30),
            listOf(request("1", "Республика Крым,  г. Алушта , ул. Ленина,1")),
        )

        assertEquals(1, result.addedCount)
        assertEquals(3, result.record.stops.size)
        assertEquals("Алушта, ул. Ленина, 1", result.record.stops[1].address)
        assertEquals(null, result.record.distanceKm)
        assertFalse(result.record.sent)
    }

    @Test
    fun `duplicates already in route are skipped`() {
        val existing = base.copy(
            stops = base.stops.mapIndexed { index, stop ->
                if (index == 1) stop.copy(address = "Клиент", requestNumber = "SUTS-1") else stop
            },
        )

        val result = importActiveRequests(existing, listOf(request("1", "Алушта")))

        assertEquals(0, result.addedCount)
        assertTrue(result.message.contains("уже добавлены"))
        assertEquals(existing, result.record)
    }

    @Test
    fun `request without address is rejected`() {
        val result = importActiveRequests(base, listOf(request("1", "")))

        assertTrue(result.isError)
        assertEquals(0, result.addedCount)
    }

    private fun request(id: String, address: String) = SimpleOneRequest(
        sysId = id,
        number = "REQ-$id",
        incomingNumber = "SUTS-$id",
        address = address,
        customer = "Клиент",
        shortDescription = "Замена терминала",
        terminalId = "Y000$id",
    )
}
