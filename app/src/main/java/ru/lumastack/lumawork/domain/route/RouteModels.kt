package ru.lumastack.lumawork.domain.route

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import ru.lumastack.lumawork.domain.model.UserProfile
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

@Serializable
enum class RouteStopStatus {
    @SerialName("pending")
    Pending,
    @SerialName("done")
    Done,
    @SerialName("declined")
    Declined,
}

@Serializable
data class RouteStop(
    val id: String,
    val address: String = "",
    val org: String = "",
    val tid: String = "",
    val reason: String = "",
    val status: RouteStopStatus = RouteStopStatus.Pending,
    val declineReason: String = "",
    val requestNumber: String = "",
)

@Serializable
data class RouteDayRecord(
    val date: String,
    val stops: List<RouteStop>,
    val distanceKm: Int? = null,
    val periodStartOdometer: Int? = null,
    val reportedDistanceKm: Double? = null,
    val routeSummary: String? = null,
    val requestNumbersSummary: String? = null,
    val reportedPeriodStartOdometer: Int? = null,
    val fuelDate: String? = null,
    val fuelLiters: Double? = null,
    val fuelCostRub: Double? = null,
    val sent: Boolean = false,
)

data class RouteSettings(
    val warehouseAddress: String,
    val homeAddress: String,
) {
    val startAddress: String
        get() = warehouseAddress.ifBlank { OFFICE_ADDRESS }

    val endAddress: String
        get() = warehouseAddress.ifBlank { OFFICE_ADDRESS }

    companion object {
        const val OFFICE_ADDRESS = "Алушта, ул. В. Хромых, 11"

        fun from(profile: UserProfile?): RouteSettings = RouteSettings(
            warehouseAddress = profile?.routeWarehouseAddress.clean() ?: OFFICE_ADDRESS,
            homeAddress = profile?.routeHomeAddress.clean().orEmpty(),
        )
    }
}

fun defaultRouteDay(date: String, settings: RouteSettings): RouteDayRecord = RouteDayRecord(
    date = date,
    stops = listOf(
        RouteStop(
            id = "start-$date",
            address = settings.startAddress,
            reason = "Подготовка оборудования",
            status = RouteStopStatus.Done,
        ),
        RouteStop(id = "middle-$date"),
        RouteStop(
            id = "finish-$date",
            address = settings.endAddress,
            reason = "Сдача оборудования",
            status = RouteStopStatus.Done,
        ),
    ),
)

fun normalizeRouteDay(
    record: RouteDayRecord,
    date: String,
    settings: RouteSettings,
): RouteDayRecord {
    val base = defaultRouteDay(date, settings)
    val hydrated = when {
        record.stops.isEmpty() -> base.stops
        record.stops.size == 1 -> base.stops
        record.stops.size == 2 -> listOf(record.stops.first(), base.stops[1], record.stops.last())
        else -> record.stops
    }.mapIndexed { index, stop ->
        when (index) {
            0 -> stop.copy(
                id = stop.id.ifBlank { base.stops.first().id },
                reason = stop.reason.ifBlank { base.stops.first().reason },
                status = RouteStopStatus.Done,
            )
            (if (record.stops.size == 2) 2 else record.stops.lastIndex) -> stop.copy(
                id = stop.id.ifBlank { base.stops.last().id },
                reason = stop.reason.ifBlank { base.stops.last().reason },
                status = RouteStopStatus.Done,
            )
            else -> stop.copy(id = stop.id.ifBlank { "stop-$date-$index" })
        }
    }

    return record.copy(date = date, stops = hydrated)
}

fun RouteDayRecord.hasWorkData(): Boolean {
    if (stops.size < 3) return false
    return stops.subList(1, stops.lastIndex).any { stop ->
        stop.address.isNotBlank() || stop.requestNumber.isNotBlank()
    }
}

data class ActiveRequestsImportResult(
    val record: RouteDayRecord,
    val addedCount: Int,
    val message: String,
    val isError: Boolean = false,
)

fun importActiveRequests(
    record: RouteDayRecord,
    requests: List<SimpleOneRequest>,
): ActiveRequestsImportResult {
    val importable = requests.filter { it.address.isNotBlank() }
    if (importable.isEmpty()) {
        return ActiveRequestsImportResult(
            record,
            0,
            "В активных заявках нет адресов для импорта.",
            isError = true,
        )
    }
    if (record.stops.size < 2) {
        return ActiveRequestsImportResult(
            record,
            0,
            "Не удалось подготовить маршрут для импорта.",
            isError = true,
        )
    }

    val knownNumbers = record.stops.map { it.requestNumber.trim() }
        .filter(String::isNotEmpty)
        .toMutableSet()
    val imported = importable.mapNotNull { request ->
        val number = request.displayNumber.trim()
        if (number.isNotEmpty() && !knownNumbers.add(number)) return@mapNotNull null
        RouteStop(
            id = "simpleone-${request.id}",
            address = normalizeRouteAddress(request.address),
            org = request.customer.trim(),
            tid = request.terminalId.trim(),
            reason = request.shortDescription.trim(),
            status = RouteStopStatus.Pending,
            requestNumber = number,
        )
    }
    if (imported.isEmpty()) {
        return ActiveRequestsImportResult(
            record,
            0,
            "Все активные заявки уже добавлены в маршрут.",
        )
    }

    val currentMiddle = record.stops.drop(1).dropLast(1).filter { stop ->
        stop.address.isNotBlank() || stop.requestNumber.isNotBlank() || stop.reason.isNotBlank()
    }
    val updated = record.copy(
        stops = listOf(record.stops.first()) + currentMiddle + imported + record.stops.last(),
        distanceKm = null,
        sent = false,
    )
    return ActiveRequestsImportResult(
        record = updated,
        addedCount = imported.size,
        message = "Добавлено ${imported.size} точек из активных заявок.",
    )
}

fun normalizeRouteAddress(value: String): String {
    val trimmed = value.trim()
    val cityIndex = ROUTE_CITIES.map { city -> trimmed.indexOf(city, ignoreCase = true) }
        .filter { it >= 0 }
        .minOrNull()
    val fromCity = cityIndex?.let(trimmed::substring) ?: trimmed
    return fromCity.replace(ROUTE_COMMA_SPACING, ", ").trim()
}

private fun String?.clean(): String? = this?.trim()?.ifBlank { null }

private val ROUTE_CITIES = listOf("Алушта", "Ялта", "Севастополь", "Симферополь", "Джанкой")
private val ROUTE_COMMA_SPACING = Regex("\\s*,\\s*")
