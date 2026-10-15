package ru.lumastack.lumawork.domain.simpleone

import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleOneModelsTest {
    private val available = SimpleOneRequest(
        sysId = "1",
        number = "REQ1",
        incomingNumber = "SUTS-1",
        registeredAt = "2026-08-12 10:00:00",
        deadline = "2026-08-13 12:00:00",
        multicardState = "Работа в приложении",
        customer = "ООО Ромашка",
    )
    private val waiting = SimpleOneRequest(
        sysId = "2",
        number = "REQ2",
        registeredAt = "2026-08-12 11:00:00",
        deadline = "2026-08-12 12:00:00",
        multicardState = "В ожидании",
    )
    private val inProgress = SimpleOneRequest(
        sysId = "3",
        number = "REQ3",
        registeredAt = "2026-08-12 12:00:00",
        deadline = "2026-08-14 12:00:00",
        multicardState = "Выезд назначен",
    )

    @Test
    fun `active status filters match iOS semantics`() {
        val requests = listOf(available, waiting, inProgress)

        assertEquals(listOf("REQ1"), filtered(requests, ActiveRequestsStatusFilter.Available))
        assertEquals(listOf("REQ2"), filtered(requests, ActiveRequestsStatusFilter.Waiting))
        assertEquals(listOf("REQ3"), filtered(requests, ActiveRequestsStatusFilter.InProgress))
    }

    @Test
    fun `search covers incoming number and customer ignoring case`() {
        val requests = listOf(available, waiting)

        assertEquals(
            listOf("REQ1"),
            filterActiveRequests(
                requests,
                query = "ромашка",
                filter = ActiveRequestsStatusFilter.All,
                sortOrder = ActiveRequestsSortOrder.RegisteredAt,
            ).map { it.number },
        )
        assertEquals(
            listOf("REQ1"),
            filterActiveRequests(
                requests,
                query = "suts-1",
                filter = ActiveRequestsStatusFilter.All,
                sortOrder = ActiveRequestsSortOrder.RegisteredAt,
            ).map { it.number },
        )
    }

    @Test
    fun `active sort supports deadline and latest registration`() {
        val requests = listOf(available, waiting, inProgress)

        assertEquals(
            listOf("REQ2", "REQ1", "REQ3"),
            filterActiveRequests(
                requests,
                query = "",
                filter = ActiveRequestsStatusFilter.All,
                sortOrder = ActiveRequestsSortOrder.Deadline,
            ).map { it.number },
        )
        assertEquals(
            listOf("REQ3", "REQ2", "REQ1"),
            filterActiveRequests(
                requests,
                query = "",
                filter = ActiveRequestsStatusFilter.All,
                sortOrder = ActiveRequestsSortOrder.RegisteredAt,
            ).map { it.number },
        )
    }

    private fun filtered(
        requests: List<SimpleOneRequest>,
        filter: ActiveRequestsStatusFilter,
    ): List<String> = filterActiveRequests(
        requests,
        query = "",
        filter = filter,
        sortOrder = ActiveRequestsSortOrder.RegisteredAt,
    ).map { it.number }
}
