package ru.lumastack.lumawork.domain.analytics

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable
import ru.lumastack.lumawork.domain.profile.*
import ru.lumastack.lumawork.domain.requests.isClosedReturnEquipment
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest

val workStatisticsZone: ZoneId = ZoneId.of("Europe/Moscow")
val workCategories = listOf("portable", "stationary", "integration", "fiscal")
val workOperations = ProfileWorkOperation.entries.map { it.rawValue }

@Serializable enum class RankingDateContext { ApiUtc, MoscowDisplay }
@Serializable data class RankingDates(
    val context: RankingDateContext,
    val completedAt: String = "",
    val resolvedAt: String = "",
    val closedAt: String = "",
    val complete: Boolean = true,
)

@Serializable data class WorkMetrics(
    val closedCount: Int = 0,
    val completedWithVisitCount: Int = 0,
    val returnEquipmentCount: Int = 0,
    val unclassifiedCompletedCount: Int = 0,
    val byCategory: Map<String, Map<String, Int>> = workCategories.associateWith { workOperations.associateWith { 0 } },
) {
    fun validate() {
        require(listOf(closedCount, completedWithVisitCount, returnEquipmentCount, unclassifiedCompletedCount).all { it >= 0 })
        require(byCategory.keys == workCategories.toSet())
        require(byCategory.values.all { it.keys == workOperations.toSet() && it.values.all { n -> n >= 0 } })
        require(completedWithVisitCount.toLong() + returnEquipmentCount <= closedCount)
        require(byCategory.values.sumOf { it.values.sumOf(Int::toLong) } + unclassifiedCompletedCount == completedWithVisitCount.toLong())
    }
    operator fun plus(other: WorkMetrics): WorkMetrics {
        fun add(a: Int, b: Int): Int = (a.toLong() + b).also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
        return WorkMetrics(add(closedCount, other.closedCount), add(completedWithVisitCount, other.completedWithVisitCount),
            add(returnEquipmentCount, other.returnEquipmentCount), add(unclassifiedCompletedCount, other.unclassifiedCompletedCount),
            workCategories.associateWith { c -> workOperations.associateWith { o -> add(byCategory.getValue(c).getValue(o), other.byCategory.getValue(c).getValue(o)) } })
    }
}
@Serializable data class WorkContribution(val monthKey: String?, val metrics: WorkMetrics)
@Serializable data class WorkStatisticsMonth(val month: String, val metrics: WorkMetrics)
@Serializable data class WorkStatisticsUpload(
    val schemaVersion: Int = 1, val rulesVersion: Int = 1, val requestId: String,
    val expectedRevision: Int, val sourceUserId: String, val sourceSyncedAt: String,
    val timezone: String = "Europe/Moscow", val months: List<WorkStatisticsMonth>, val unknownDate: WorkMetrics,
) {
    fun validate() {
        require(schemaVersion == 1 && rulesVersion == 1 && timezone == "Europe/Moscow" && expectedRevision >= 0)
        java.util.UUID.fromString(requestId)
        require(sourceUserId.isNotBlank() && sourceUserId.length <= 128)
        val synced = Instant.parse(sourceSyncedAt)
        require(months.size <= 1200 && months.map { it.month } == months.map { it.month }.distinct().sorted())
        months.forEach { require(YearMonth.parse(it.month) <= YearMonth.from(synced.atZone(workStatisticsZone))); it.metrics.validate() }
        unknownDate.validate()
    }
}
@Serializable data class WorkStatisticsMe(val serverRevision: Int, val hasSnapshot: Boolean, val sourceUserId: String,
    val sourceSyncedAt: String?, val receivedAt: String?, val rulesVersion: Int)
@Serializable data class WorkStatisticsReceipt(val accepted: Boolean, val serverRevision: Int, val requestId: String,
    val sourceSyncedAt: String, val receivedAt: String)

fun workContribution(record: SimpleOneRequest): WorkContribution {
    val dates = requireNotNull(record.rankingDates) { "Untrusted legacy date provenance" }
    require(dates.complete) { "Incomplete source fields" }
    val fields = record.workInfoFields()
    val returned = isClosedReturnEquipment(record.requestType.ifBlank { fields.firstWorkInfoValue("Тип заявки") })
    val closure = record.closureCode.ifBlank { fields.firstWorkInfoValue("Код закрытия") }
        .trim().lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("[\\s\\p{Z}]+"), " ")
    val completed = !returned && closure.startsWith("решено с выездом")
    val classification = if (completed) classifyCompletedWork(record, fields) else null
    val category = classification?.first?.name?.lowercase(Locale.ROOT)
    val operation = classification?.second?.rawValue
    val metrics = WorkMetrics(closedCount = 1, completedWithVisitCount = if (completed) 1 else 0,
        returnEquipmentCount = if (returned) 1 else 0, unclassifiedCompletedCount = if (completed && classification == null) 1 else 0,
        byCategory = workCategories.associateWith { c -> workOperations.associateWith { o -> if (c == category && o == operation) 1 else 0 } })
    fun parse(raw: String) = parseRankingDate(raw, dates.context)
    fun group(structured: String, labels: List<String>): LocalDateTime? = parse(structured) ?: labels.firstNotNullOfOrNull { label ->
        // The shared fixture adapter also carries raw UTC alongside a display column.
        val raw = fields.firstWorkInfoValue("__workStatisticsUTC:$label")
        if (raw.isNotBlank()) parseRankingDate(raw, RankingDateContext.ApiUtc) else parse(fields.firstWorkInfoValue(label))
    }
    val date = group(dates.completedAt, listOf("Выполнена", "Время Выполнена", "Время \"Выполнена\"", "Время выполнения"))
        ?: group(dates.resolvedAt, listOf("Время решения"))
        ?: group(dates.closedAt, listOf("закрытие в МК", "closedInMulticardAt", "closedAt", "Дата закрытия в МК", "Дата закрытия МК", "Дата закрытия"))
    return WorkContribution(date?.let { YearMonth.from(it).toString() }, metrics)
}

fun parseRankingDate(raw: String, context: RankingDateContext): LocalDateTime? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    runCatching { OffsetDateTime.parse(value).atZoneSameInstant(workStatisticsZone).toLocalDateTime() }.getOrNull()?.let { return it }
    if (context == RankingDateContext.MoscowDisplay) value.toDoubleOrNull()?.takeIf { it.isFinite() && it > 25569 && it < 2958466 }?.let {
        // Calendar arithmetic before zone binding avoids Moscow's historic 1899 offset.
        return LocalDateTime.of(1899, 12, 30, 0, 0).plusSeconds((it * 86400).roundToLong())
    }
    val patterns = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy HH:mm", "dd.MM.yyyy")
    val parsed = patterns.firstNotNullOfOrNull { pattern ->
        val formatter = DateTimeFormatter.ofPattern(pattern)
        runCatching { LocalDateTime.parse(value, formatter) }.getOrNull()
            ?: runCatching { LocalDate.parse(value, formatter).atStartOfDay() }.getOrNull()
    }?.takeIf { it.year > 1970 } ?: return null
    return if (context == RankingDateContext.ApiUtc && value.getOrNull(4) == '-') parsed.atOffset(ZoneOffset.UTC).atZoneSameInstant(workStatisticsZone).toLocalDateTime() else parsed
}
