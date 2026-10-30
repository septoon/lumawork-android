package ru.lumastack.lumawork.data.simpleone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import ru.lumastack.lumawork.domain.simpleone.*
import ru.lumastack.lumawork.domain.requests.normalizedClientCommentTIN

private val closedTinPattern = Regex("ИНН\\s*(?:ТСП|клиента)?\\s*[:：=]\\s*([0-9][0-9 \\t-]{8,20})", RegexOption.IGNORE_CASE)
private val closedTinBreaks = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val closedTinTags = Regex("<[^>]+>")

/** The narrow iOS TIN extractor; avoids parsing every request detail label during bulk merge. */
internal fun resolvedClosedMerchantTIN(direct: String, information: String): String {
    normalizedClientCommentTIN(direct).takeIf(String::isNotBlank)?.let { return it }
    if (!information.contains("ИНН", ignoreCase = true)) return ""
    val plain = information.replace("\\r\\n", "\n").replace("\\n", "\n").replace("&#xA;", "\n")
        .replace(closedTinBreaks, "\n").replace(closedTinTags, "")
    return closedTinPattern.findAll(plain).map { normalizedClientCommentTIN(it.groupValues[1]) }
        .firstOrNull(String::isNotBlank).orEmpty()
}

internal fun closedMerchantTIN(request: SimpleOneRequest): String {
    for (label in listOf("ИНН ТСП", "ИНН", "ИНН клиента")) {
        normalizedClientCommentTIN(request.tableFields[label].orEmpty()).takeIf(String::isNotBlank)?.let { return it }
    }
    return resolvedClosedMerchantTIN("", request.description + "\n" + request.additionalInformation)
}

/** Same scan, cell-value versions and export-row completion as iOS ClosedRequestsIncrementalSync. */
internal class SimpleOneClosedSyncClient(
    private val origin: String,
    private val get: suspend (String) -> JsonObject,
    private val wideCondition: () -> String,
    private val monthlyCondition: (String) -> String,
) {
    private data class CompletedRow(val id: String, val cursor: ClosedRequestsSyncCursor, val change: ClosedRequestsSyncChange?)

    suspend fun head(userId: String, scope: ClosedRequestsSyncScope): ClosedRequestsSyncCursor? =
        page(userId, scope, 1).first.firstOrNull()?.let { cursor(identity(it).first) }

    suspend fun changes(
        userId: String, scope: ClosedRequestsSyncScope, since: ClosedRequestsSyncCursor?,
        knownVersions: Map<String, String>, onProgress: (String) -> Unit,
    ): ClosedRequestsSyncBatch {
        val started = System.nanoTime()
        val cursorCalls = AtomicInteger()
        val cursorNanos = AtomicLong()
        val completionCalls = AtomicInteger()
        val completionNanos = AtomicLong()
        val changes = linkedMapOf<String, ClosedRequestsSyncChange>()
        var watermark = since
        repeat(3) {
            var pageNumber = 1
            var previous: ClosedRequestsSyncCursor? = null
            var anchor: ClosedRequestsSyncCursor? = null
            var reachedBoundary = false
            var changed = false
            val seen = mutableSetOf<String>()
            do {
                currentCoroutineContext().ensureActive()
                val (items, hasMore) = page(userId, scope, pageNumber)
                val before = seen.size
                // Fetch at most four rows ahead, but validate and apply in server order.
                // Results beyond the stopping boundary are speculative and never committed.
                coroutineScope {
                    val queued = mutableSetOf<String>()
                    val rows = items.filter { item ->
                        val id = item.field("sys_id")
                        // Validate malformed rows only when reached in order, just like cursor failures.
                        id.isBlank() || item.field("number").isBlank() || (id !in seen && queued.add(id))
                    }
                    val inFlight = ArrayDeque<Deferred<Result<CompletedRow>>>()
                    var scheduled = 0
                    fun schedule() {
                        if (scheduled >= rows.size) return
                        val item = rows[scheduled++]
                        inFlight.addLast(async {
                            try {
                                val (id, number) = identity(item)
                                val cursorStarted = System.nanoTime()
                                val version = cursor(id)
                                cursorCalls.incrementAndGet()
                                cursorNanos.addAndGet(System.nanoTime() - cursorStarted)
                                val needsCompletion = (since == null || version.updatedAt >= since.updatedAt) &&
                                    knownVersions[id]?.let { it >= version.updatedAt } != true
                                val change = if (needsCompletion) {
                                    val completionStarted = System.nanoTime()
                                    val row = completeRow(item, id)
                                    completionCalls.incrementAndGet()
                                    completionNanos.addAndGet(System.nanoTime() - completionStarted)
                                    val record = SimpleOneXlsxParser.recordFromExportRow(row)?.copy(sysId = id, sysUpdatedAt = version.updatedAt)
                                    ClosedRequestsSyncChange(id, number, version, record)
                                } else null
                                Result.success(CompletedRow(id, version, change))
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { Result.failure(error) }
                        })
                    }
                    repeat(minOf(4, rows.size)) { schedule() }
                    try {
                        while (inFlight.isNotEmpty()) {
                            val (id, version, change) = inFlight.removeFirst().await().getOrThrow()
                            seen.add(id)
                            if (seen.size > 100_000) throw SimpleOneFailure.InvalidResponse
                            if (previous?.let { version > it } == true) throw SimpleOneFailure.InvalidResponse
                            previous = version
                            if (anchor == null) anchor = version
                            if (since != null && version.updatedAt < since.updatedAt) {
                                reachedBoundary = true
                                break
                            }
                            if (change != null) {
                                if (changes[id]?.cursor?.let { it >= version } != true) changes[id] = change
                                changed = true
                            }
                            schedule()
                        }
                    } finally { inFlight.forEach { it.cancel() } }
                }
                onProgress("Проверено заявок: ${seen.size}")
                if (!reachedBoundary && items.isNotEmpty() && seen.size == before) throw SimpleOneFailure.InvalidResponse
                if (reachedBoundary || !hasMore) break
                pageNumber++
            } while (true)
            val first = anchor
            if (first != null && (watermark == null || first > watermark!!)) watermark = first
            // iOS rechecks the head only if this scan actually observed changes.
            if (!changed || first == null || head(userId, scope)?.let { it > first } != true) {
                android.util.Log.i("SimpleOneSync", "scope=$scope changes=${changes.size} cursorCalls=${cursorCalls.get()} " +
                    "cursorMs=${cursorNanos.get() / 1_000_000} completionCalls=${completionCalls.get()} " +
                    "completionMs=${completionNanos.get() / 1_000_000} totalMs=${(System.nanoTime() - started) / 1_000_000}")
                return ClosedRequestsSyncBatch(changes.values.sortedByDescending { it.cursor }, watermark)
            }
        }
        throw SimpleOneFailure.Http(409, "Список закрытых заявок изменяется слишком быстро. Повторите синхронизацию.")
    }

    private suspend fun page(userId: String, scope: ClosedRequestsSyncScope, page: Int): Pair<List<JsonObject>, Boolean> {
        val condition = when (scope) {
            ClosedRequestsSyncScope.Monthly -> monthlyCondition(userId)
            ClosedRequestsSyncScope.All -> wideCondition()
        }
        val url = "$origin/list/itsm_request".toHttpUrl().newBuilder()
            .addQueryParameter("condition", "$condition^ORDERBYDESCsys_updated_at^ORDERBYDESCsys_id")
            .addQueryParameter("page", page.toString()).addQueryParameter("per_page", "20").build()
        val started = System.nanoTime()
        val response = get(url.toString())
        val items = simpleOneListItems(response) ?: throw SimpleOneFailure.InvalidResponse
        val total = simpleOneTotalCount(response)
        android.util.Log.i("SimpleOneSync", "scope=$scope page=$page rows=${items.size} total=$total listMs=${(System.nanoTime() - started) / 1_000_000}")
        return items to (total?.let { page.toLong() * 20 < it } ?: (items.size == 20))
    }

    private fun identity(item: JsonObject): Pair<String, String> {
        val id = item.field("sys_id")
        val number = item.field("number")
        if (id.isBlank() || number.isBlank()) throw SimpleOneFailure.InvalidResponse
        return id to number
    }

    private suspend fun cursor(id: String): ClosedRequestsSyncCursor {
        val value = cell("sys_updated_at", id)
        val version = closedSyncTimestamp(value) ?: throw SimpleOneFailure.InvalidResponse
        return ClosedRequestsSyncCursor(version, id)
    }

    private suspend fun cell(column: String, id: String): String {
        val url = origin.toHttpUrl().newBuilder().addPathSegment("list-cell-value").addPathSegment("itsm_request")
            .addPathSegment(column).addPathSegment(id).build()
        val data = get(url.toString())["data"] as? JsonObject ?: throw SimpleOneFailure.InvalidResponse
        if (!readable(data) || "value" !in data) throw SimpleOneFailure.InvalidResponse
        return fieldValue(data["value"], false)
    }

    private suspend fun completeRow(item: JsonObject, id: String): Map<String, String> {
        val row = linkedMapOf<String, String>("sys_id" to id)
        var detail: JsonObject? = null
        suspend fun details(): JsonObject {
            detail?.let { return it }
            val raw = simpleOneRecordItem(get("$origin/record/itsm_request/$id?open_first_rel_list=0"))
                ?: throw SimpleOneFailure.InvalidResponse
            val flat = flattenSimpleOneRecord(raw).toMutableMap()
            (raw["sections"] as? JsonArray)?.filterIsInstance<JsonObject>()?.forEach { section ->
                (section["elements"] as? JsonArray)?.filterIsInstance<JsonObject>()?.forEach { element ->
                    if (element["read_access"] == JsonPrimitive(false)) {
                        listOf("sys_column_name", "system_name", "name").map { element.field(it) }
                            .filter(String::isNotBlank).forEach { flat[it] = buildJsonObject { put("read_access", false) } }
                    }
                }
            }
            return JsonObject(flat).also { detail = it }
        }
        for ((column, title) in columns) {
            var raw = item[column]
            if (!readable(raw)) {
                raw = details()[column]
                if (!readable(raw)) {
                    // A record form can omit a column even though its list-cell endpoint is readable.
                    raw = JsonPrimitive(try { cell(column, id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (unauthorized: SimpleOneFailure.Unauthorized) { throw unauthorized }
                    catch (_: Exception) {
                        throw SimpleOneFailure.Http(422, "SimpleOne не вернул поле «$title». Сохранённый архив не изменён.")
                    })
                }
            }
            row[title] = when (column) {
                "state" -> databaseValue(raw)
                "assigned_user", "new_closure_code" -> fieldValue(raw, true)
                else -> fieldValue(raw, false)
            }
        }
        row["Модель терминала"] = item.field("terminal_model")
        var tin = normalizedClientCommentTIN(item.field("multicard_merchant_tin", "multicard_tsp_inn", "merchant_tin", "inn_tsp", "ИНН ТСП"))
        if (tin.isBlank()) {
            val record = details()
            tin = resolvedClosedMerchantTIN(record.field("multicard_merchant_tin", "multicard_tsp_inn", "merchant_tin", "inn_tsp", "ИНН ТСП"),
                record.field("description"))
            record.field("description").takeIf(String::isNotBlank)?.let { row["Информация"] = it }
        }
        if (tin.isNotBlank()) row["ИНН ТСП"] = tin
        for ((column, title) in textColumns) {
            val value = row[title].orEmpty()
            if (value.length < 500 && !(value.length >= 497 && value.endsWith("..."))) continue
            row[title] = try { cell(column, id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (unauthorized: SimpleOneFailure.Unauthorized) { throw unauthorized }
            catch (error: Exception) {
                val raw = details()[column]
                val full = fieldValue(raw, false)
                if (!readable(raw) || (full.isBlank() && value.isNotBlank())) throw error
                full
            }
        }
        return row
    }

    companion object {
        private fun readable(raw: JsonElement?): Boolean = when (raw) {
            null -> false
            is JsonObject -> raw["read_access"] != JsonPrimitive(false) && (raw["value"]?.let(::readable) ?: true)
            else -> true
        }

        private fun databaseValue(raw: JsonElement?): String = if (raw is JsonObject) {
            raw.field("database_value").ifBlank { raw["value"]?.let(::databaseValue) ?: fieldValue(raw, false) }
        } else fieldValue(raw, false)

        fun exportFields(item: JsonObject): Map<String, String> = buildMap {
            for ((column, title) in columns) {
                val raw = item[column]
                if (!readable(raw)) continue
                put(title, if (column == "state") databaseValue(raw)
                    else fieldValue(raw, column == "assigned_user" || column == "new_closure_code"))
            }
            if ("terminal_model" in item) put("Модель терминала", item.field("terminal_model"))
        }

        private val columns = linkedMapOf(
            "number" to "Номер заявки", "short_description" to "Краткое описание", "state" to "Статус",
            "multicard_request_type" to "Тип заявки", "multicard_terminal_address" to "Адрес установки терминала",
            "multicard_name_client" to "Заказчик", "multicard_terminal_model" to "Модель POS-терминала",
            "description" to "Информация", "multicard_id_terminal" to "ID терминал", "multicard_comment_ing" to "Комментарий инженера",
            "multicard_additional_information" to "Доп. информация", "assigned_user" to "Исполнитель",
            "multicard_contact_person" to "Kонтактное лицо", "multicard_deadline" to "Предельный срок СУТС",
            "pb_sn_pos_install" to "SN POS установка", "pb_sn_pos_uninstall" to "SN POS демонтаж", "multicard_pos" to "Оборудование POS",
            "multicard_return_number_pos" to "Номер принятого оборудования POS", "multicard_pin_pad" to "Оборудование Pin Pad",
            "multicard_return_number_pin" to "Номер принятого оборудования PIN", "multicard_closing_date" to "Дата закрытия в МК",
            "resolved_at" to "Время \"Выполнена\"", "sys_created_at" to "Время регистрации", "new_closure_code" to "Код закрытия",
            "closure_notes" to "Решение",
        )
        private val textColumns = columns.filterKeys { it in setOf("description", "multicard_comment_ing", "multicard_additional_information", "closure_notes") }
    }
}
