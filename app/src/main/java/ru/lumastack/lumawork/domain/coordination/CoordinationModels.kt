package ru.lumastack.lumawork.domain.coordination

import java.util.Locale
import kotlinx.serialization.Serializable
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

enum class CoordinationSection(val title: String) {
    Distribution("На группу"),
    ReturnEquipment("Возврат ТО"),
}

@Serializable
enum class CoordinationRegion(
    val title: String,
    val assignmentGroupId: String,
    val companyLocationId: String,
) {
    Simferopol("Симферополь", "171872492502058527", "171878344203030065"),
    Alushta("Алушта", "171872459801458049", "171878330004108924"),
    Yalta("Ялта", "171872446905555450", "171878319402581938"),
    Evpatoria("Евпатория", "171872432707008559", "171886975407702941"),
    Krasnoperekopsk("Красноперекопск", "171872414002570226", "171878304603560601"),
    Dzhankoy("Джанкой", "171872396801307755", "171878294102695038"),
    Feodosia("Феодосия", "171872374107775143", "171878285308384162"),
    Kerch("Керчь", "171872325607628769", "171878277802582935"),
    Sevastopol("Севастополь", "171872303308964248", "171878265201212075"),
}

fun coordinationRegionForProfileCity(profileCity: String?): CoordinationRegion? {
    var city = profileCity.orEmpty().trim().lowercase(Locale.ROOT)
    val prefix = listOf("город ", "г. ", "г.", "г ").firstOrNull { city.startsWith(it) }
    if (prefix != null) city = city.removePrefix(prefix).trim()
    return CoordinationRegion.entries.firstOrNull { it.title.lowercase(Locale.ROOT) == city }
}

@Serializable
data class CoordinationRequestGroup(
    val requests: List<SimpleOneRequest> = emptyList(),
    val updatedAtEpochMillis: Long? = null,
)

@Serializable
data class CoordinationSnapshot(
    val simpleOneUserId: String = "",
    val regions: Map<String, CoordinationRequestGroup> = emptyMap(),
    val returnEquipment: CoordinationRequestGroup? = null,
    val selectedRegion: CoordinationRegion = CoordinationRegion.Alushta,
    val sessionFingerprint: String = "",
    val revision: Long = 0,
)

data class CoordinationEngineer(
    val id: String,
    val name: String,
    val requestCount: Int,
) {
    val isUnassigned: Boolean
        get() = id == UNASSIGNED_ID

    companion object {
        const val UNASSIGNED_ID = "coordination:unassigned"
    }
}

fun coordinationEngineers(requests: List<SimpleOneRequest>): List<CoordinationEngineer> = requests
    .groupBy(SimpleOneRequest::coordinationEngineerKey)
    .map { (key, grouped) ->
        val assignedName = grouped.firstNotNullOfOrNull {
            it.assignedUser.trim().takeIf(String::isNotEmpty)
        }
        CoordinationEngineer(
            id = key,
            name = when {
                key == CoordinationEngineer.UNASSIGNED_ID -> "Назначено на группу"
                assignedName != null -> assignedName
                else -> "Исполнитель не определён"
            },
            requestCount = grouped.size,
        )
    }
    .sortedWith(
        compareByDescending<CoordinationEngineer> { it.isUnassigned }
            .thenByDescending(CoordinationEngineer::requestCount)
            .thenBy(CoordinationEngineer::name),
    )

fun SimpleOneRequest.coordinationEngineerKey(): String {
    val name = ru.lumastack.lumawork.domain.requests.normalizedClosedRequestsText(assignedUser)
    if (name.isNotEmpty()) return "name:$name"
    val userId = assignedUserId.trim()
    if (userId.isNotEmpty()) return "id:$userId"
    return CoordinationEngineer.UNASSIGNED_ID
}

fun isReturnEquipmentRequest(raw: String): Boolean {
    val normalized = raw.trim().lowercase()
        .replace("_", "")
        .replace("-", "")
        .replace(" ", "")
    return normalized == "returnequip" || normalized.contains("возвратто")
}
