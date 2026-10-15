package ru.lumastack.lumawork.data.simpleone

import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.lumastack.lumawork.domain.coordination.CoordinationRegion
import ru.lumastack.lumawork.domain.coordination.isReturnEquipmentRequest
import ru.lumastack.lumawork.data.timereport.TimeReportParser
import ru.lumastack.lumawork.domain.backpack.BackpackItem
import ru.lumastack.lumawork.domain.backpack.NOT_SET
import ru.lumastack.lumawork.domain.employees.Employee
import ru.lumastack.lumawork.domain.employees.EmployeeAddress
import ru.lumastack.lumawork.domain.employees.EmployeeField
import ru.lumastack.lumawork.domain.employees.EmployeeSection
import ru.lumastack.lumawork.domain.employees.sanitizeEmployeeConditionValue
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequestSource
import ru.lumastack.lumawork.domain.simpleone.SimpleOneUser
import ru.lumastack.lumawork.domain.timereport.TimeReportEntry

interface SimpleOneApi {
    suspend fun login(username: String, password: String): String
    suspend fun currentUser(authKey: String): SimpleOneUser
    suspend fun activeRequests(userId: String, authKey: String): List<SimpleOneRequest>
    suspend fun closedRequests(userId: String, authKey: String): List<SimpleOneRequest>
    suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray
    suspend fun timeReportEntries(authKey: String): List<TimeReportEntry>
    suspend fun exportTimeReport(userId: String, authKey: String): ByteArray
    suspend fun backpackItems(authKey: String): List<BackpackItem>
    suspend fun requestDetails(request: SimpleOneRequest, authKey: String): SimpleOneRequest
    suspend fun coordinationRequests(region: CoordinationRegion, authKey: String): List<SimpleOneRequest>
    suspend fun returnEquipmentRequests(userId: String, authKey: String): List<SimpleOneRequest>
    suspend fun employeeAddresses(query: String, authKey: String): List<EmployeeAddress> = emptyList()
    suspend fun employees(
        address: EmployeeAddress,
        query: String,
        authKey: String,
    ): Pair<List<Employee>, Int> = emptyList<Employee>() to 0
    suspend fun employeeDetails(employee: Employee, authKey: String): Employee = employee
}

sealed class SimpleOneFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: IOException) : SimpleOneFailure("Не удалось подключиться к SimpleOne.", cause)
    data object MissingCredentials : SimpleOneFailure("Войдите в SimpleOne, чтобы загрузить заявки.")
    data object Unauthorized : SimpleOneFailure("Сессия SimpleOne истекла. Войдите снова.")
    class Http(val status: Int, message: String) : SimpleOneFailure(message)
    data object InvalidResponse : SimpleOneFailure("SimpleOne вернул неожиданный ответ.")
}

class OkHttpSimpleOneApi(
    baseOrigin: String,
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SimpleOneApi {
    private val origin = baseOrigin.trimEnd('/')
    private val exportClient = SimpleOneExportClient(origin, client, json)

    override suspend fun login(username: String, password: String): String {
        val payload = buildJsonObject {
            put("username", username)
            put("password", password)
            put("language", "ru")
        }
        val response = request(
            Request.Builder()
                .url("$origin/auth/login")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        return ((response["data"] as? JsonObject)?.string("auth_key"))
            ?.takeIf(String::isNotBlank)
            ?: throw serverFailure(response)
            ?: SimpleOneFailure.InvalidResponse
    }

    override suspend fun currentUser(authKey: String): SimpleOneUser {
        val response = request(authenticatedRequest("$origin/user/me", authKey))
        val data = response["data"] as? JsonObject ?: throw serverFailure(response)
            ?: SimpleOneFailure.InvalidResponse
        return SimpleOneUser(
            sysId = data.string("sys_id"),
            username = data.string("username"),
            firstName = data.string("first_name"),
            lastName = data.string("last_name"),
        ).takeIf { it.sysId.isNotBlank() } ?: throw SimpleOneFailure.InvalidResponse
    }

    override suspend fun activeRequests(userId: String, authKey: String): List<SimpleOneRequest> =
        fetchAllRequests(
            condition = activeCondition(userId),
            source = SimpleOneRequestSource.Active,
            authKey = authKey,
            columns = ACTIVE_COLUMNS,
            maxPages = MAX_ACTIVE_PAGES,
        )

    override suspend fun closedRequests(userId: String, authKey: String): List<SimpleOneRequest> =
        fetchAllRequests(
            condition = closedCondition(userId),
            source = SimpleOneRequestSource.Closed,
            authKey = authKey,
            columns = CLOSED_COLUMNS,
            maxPages = MAX_CLOSED_PAGES,
        )

    override suspend fun exportClosedRequests(userId: String, authKey: String): ByteArray =
        exportClient.exportClosedRequests(userId, authKey)

    override suspend fun timeReportEntries(authKey: String): List<TimeReportEntry> {
        val entries = mutableListOf<TimeReportEntry>()
        val seenIds = mutableSetOf<String>()
        var page = 1
        while (page <= MAX_TIME_REPORT_PAGES) {
            val url = "$origin/list/itsm_tchnsrv_time_report".toHttpUrl().newBuilder()
                .addQueryParameter("condition", TIME_REPORT_CONDITION)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", PAGE_SIZE.toString())
                .build()
            val response = request(authenticatedRequest(url.toString(), authKey))
            val items = simpleOneListItems(response) ?: throw serverFailure(response)
                ?: SimpleOneFailure.InvalidResponse
            val previousCount = entries.size
            items.mapNotNull(TimeReportParser::parseRecord).forEach { entry ->
                if (seenIds.add(entry.stableId)) entries += entry
            }
            val total = simpleOneTotalCount(response)
            val hasMore = total?.let { page * PAGE_SIZE < it } ?: (items.size == PAGE_SIZE)
            if (!hasMore || entries.size == previousCount) break
            page += 1
        }
        return entries.sortedWith(
            compareByDescending<TimeReportEntry> { it.createdAtDateTime() }
                .thenBy(TimeReportEntry::activity),
        )
    }

    override suspend fun exportTimeReport(userId: String, authKey: String): ByteArray =
        exportClient.exportTimeReport(userId, authKey)

    override suspend fun backpackItems(authKey: String): List<BackpackItem> {
        val items = mutableListOf<BackpackItem>()
        val seenIds = mutableSetOf<String>()
        var page = 1
        while (page <= MAX_BACKPACK_PAGES) {
            val url = "$origin/list/itsm_tchnsrv_balances_in_warehouses".toHttpUrl().newBuilder()
                .addQueryParameter("condition", BACKPACK_CONDITION)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", PAGE_SIZE.toString())
                .build()
            val response = request(authenticatedRequest(url.toString(), authKey))
            val records = simpleOneListItems(response) ?: throw serverFailure(response)
                ?: SimpleOneFailure.InvalidResponse
            records.map(::parseBackpackItem)
                .filter { it.name != NOT_SET || it.serialNumber != NOT_SET }
                .forEach { item -> if (seenIds.add(item.id)) items += item }
            if (records.size < PAGE_SIZE) break
            page += 1
        }

        return items.chunked(BACKPACK_DETAIL_CONCURRENCY).flatMap { chunk ->
            coroutineScope {
                chunk.map { item ->
                    async {
                        try {
                            backpackItemDetails(item, authKey)
                        } catch (error: SimpleOneFailure.Unauthorized) {
                            throw error
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            item
                        }
                    }
                }.awaitAll()
            }
        }.sortedWith(
            compareByDescending<BackpackItem> { it.receivedAtEpochMillis }
                .thenBy { it.name }
                .thenBy { it.serialNumber },
        )
    }

    private suspend fun backpackItemDetails(item: BackpackItem, authKey: String): BackpackItem {
        val response = request(
            authenticatedRequest("$origin/record/itsm_tchnsrv_balances_in_warehouses/${item.id}", authKey),
        )
        val record = simpleOneRecordItem(response) ?: throw serverFailure(response)
            ?: SimpleOneFailure.InvalidResponse
        return parseBackpackItem(flattenSimpleOneRecord(record)).mergeMissing(item)
    }

    override suspend fun requestDetails(request: SimpleOneRequest, authKey: String): SimpleOneRequest {
        if (request.sysId.isBlank()) return request
        val response = request(authenticatedRequest("$origin/record/itsm_request/${request.sysId}", authKey))
        val item = simpleOneRecordItem(response) ?: throw serverFailure(response)
            ?: SimpleOneFailure.InvalidResponse
        return parseSimpleOneRequest(flattenSimpleOneRecord(item), request.source).mergeMissing(request)
    }

    override suspend fun coordinationRequests(
        region: CoordinationRegion,
        authKey: String,
    ): List<SimpleOneRequest> = fetchAllRequests(
        condition = coordinationCondition(region),
        source = SimpleOneRequestSource.Active,
        authKey = authKey,
        columns = COORDINATION_COLUMNS,
        maxPages = MAX_COORDINATION_PAGES,
    )

    override suspend fun returnEquipmentRequests(
        userId: String,
        authKey: String,
    ): List<SimpleOneRequest> = fetchAllRequests(
        condition = returnEquipmentCondition(userId),
        source = SimpleOneRequestSource.Active,
        authKey = authKey,
        columns = COORDINATION_COLUMNS,
        maxPages = MAX_COORDINATION_PAGES,
    ).filter { isReturnEquipmentRequest(it.requestType) }

    override suspend fun employeeAddresses(query: String, authKey: String): List<EmployeeAddress> {
        val normalized = sanitizeEmployeeConditionValue(query)
        if (normalized.length < 2) return emptyList()
        val url = "$origin/list/itsm_tchnsrv_tgr".toHttpUrl().newBuilder()
            .addQueryParameter("condition", "(localityLIKE$normalized)")
            .addQueryParameter("page", "1")
            .addQueryParameter("per_page", EMPLOYEE_ADDRESS_LIMIT.toString())
            .build()
        val response = request(authenticatedRequest(url.toString(), authKey))
        val records = simpleOneListItems(response) ?: throw serverFailure(response)
            ?: SimpleOneFailure.InvalidResponse
        return records.mapNotNull(::parseEmployeeAddress)
            .distinctBy(EmployeeAddress::sysId)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, EmployeeAddress::title))
    }

    override suspend fun employees(
        address: EmployeeAddress,
        query: String,
        authKey: String,
    ): Pair<List<Employee>, Int> {
        val search = sanitizeEmployeeConditionValue(query)
        val condition = buildString {
            append("(c_tgr=")
            append(address.sysId)
            if (search.isNotEmpty()) append("^keywordsARE").append(search)
            append(')')
        }
        val employees = mutableListOf<Employee>()
        val seenIds = mutableSetOf<String>()
        var totalCount = 0
        var page = 1
        while (page <= MAX_EMPLOYEE_PAGES) {
            val url = "$origin/list/employee".toHttpUrl().newBuilder()
                .addQueryParameter("condition", condition)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", PAGE_SIZE.toString())
                .build()
            val response = request(authenticatedRequest(url.toString(), authKey))
            val records = simpleOneListItems(response) ?: throw serverFailure(response)
                ?: SimpleOneFailure.InvalidResponse
            totalCount = maxOf(totalCount, simpleOneTotalCount(response) ?: 0)
            val previousCount = employees.size
            records.map { parseEmployee(it, address) }
                .filter { it.sysId.isNotBlank() }
                .forEach { employee -> if (seenIds.add(employee.sysId)) employees += employee }
            val hasMore = simpleOneTotalCount(response)?.let { page * PAGE_SIZE < it }
                ?: (records.size == PAGE_SIZE)
            if (!hasMore || employees.size == previousCount) break
            page += 1
        }
        return employees.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Employee::sortName)) to
            maxOf(totalCount, employees.size)
    }

    override suspend fun employeeDetails(employee: Employee, authKey: String): Employee {
        if (employee.sysId.isBlank()) return employee
        val response = request(authenticatedRequest("$origin/record/employee/${employee.sysId}", authKey))
        val record = simpleOneRecordItem(response) ?: throw serverFailure(response)
            ?: SimpleOneFailure.InvalidResponse
        val flattened = flattenSimpleOneRecord(record)
        val detailed = parseEmployee(flattened, employee.address).mergeMissing(employee)
        return detailed.copy(detailSections = parseEmployeeSections(record))
    }

    private suspend fun fetchAllRequests(
        condition: String,
        source: SimpleOneRequestSource,
        authKey: String,
        columns: List<String>,
        maxPages: Int,
    ): List<SimpleOneRequest> {
        val records = mutableListOf<SimpleOneRequest>()
        val seenIds = mutableSetOf<String>()
        var page = 1

        while (page <= maxPages) {
            val url = "$origin/list/itsm_request".toHttpUrl().newBuilder()
                .addQueryParameter("condition", condition)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", PAGE_SIZE.toString())
                .addQueryParameter("columns", columns.joinToString(","))
                .build()
            val response = request(authenticatedRequest(url.toString(), authKey))
            val items = simpleOneListItems(response) ?: throw serverFailure(response)
                ?: SimpleOneFailure.InvalidResponse
            val previousCount = records.size
            items.map { parseSimpleOneRequest(it, source) }
                .filter { it.number.isNotBlank() }
                .forEach { record ->
                    if (record.id.isBlank() || seenIds.add(record.id)) records += record
                }
            val total = simpleOneTotalCount(response)
            val hasMore = total?.let { page * PAGE_SIZE < it } ?: (items.size == PAGE_SIZE)
            if (!hasMore || records.size == previousCount) break
            page += 1
        }

        return when (source) {
            SimpleOneRequestSource.Active -> records.sortedByDescending(SimpleOneRequest::deadline)
            SimpleOneRequestSource.Closed -> records.sortedByDescending {
                it.resolvedAt.ifBlank { it.closedAt }
            }
        }
    }

    private fun authenticatedRequest(url: String, authKey: String): Request = Request.Builder()
        .url(url)
        .header("Cookie", "auth=$authKey")
        .get()
        .build()

    private suspend fun request(request: Request): JsonObject {
        val prepared = request.newBuilder()
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()
        val call = client.newCall(prepared)
        call.timeout().timeout(25, TimeUnit.SECONDS)
        val response = try {
            call.awaitSimpleOne()
        } catch (error: IOException) {
            throw SimpleOneFailure.Network(error)
        }
        return response.use { httpResponse ->
            val body = httpResponse.body.string()
            if (httpResponse.code == 401 || httpResponse.code == 403) throw SimpleOneFailure.Unauthorized
            if (!httpResponse.isSuccessful) {
                throw SimpleOneFailure.Http(
                    httpResponse.code,
                    "SimpleOne вернул HTTP ${httpResponse.code}.",
                )
            }
            val payload = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw SimpleOneFailure.InvalidResponse
            serverFailure(payload)?.let { throw it }
            payload
        }
    }

    private fun serverFailure(response: JsonObject): SimpleOneFailure? {
        if (response.string("status") == "OK") return null
        val errors = response["errors"] as? JsonArray
        val errorMessage = errors?.mapNotNull { (it as? JsonObject)?.string("message") }
            ?.firstOrNull(String::isNotBlank)
        if (errorMessage != null) {
            return if (errorMessage.contains("credentials", ignoreCase = true)) {
                SimpleOneFailure.Unauthorized
            } else {
                SimpleOneFailure.Http(200, errorMessage)
            }
        }
        return response.string("message", "error").takeIf(String::isNotBlank)?.let {
            SimpleOneFailure.Http(200, it)
        }
    }

    companion object {
        private const val PAGE_SIZE = 100
        private const val MAX_ACTIVE_PAGES = 10
        private const val MAX_CLOSED_PAGES = 50
        private const val MAX_COORDINATION_PAGES = 30
        private const val MAX_TIME_REPORT_PAGES = 100
        private const val MAX_BACKPACK_PAGES = 50
        private const val MAX_EMPLOYEE_PAGES = 50
        private const val EMPLOYEE_ADDRESS_LIMIT = 40
        private const val BACKPACK_DETAIL_CONCURRENCY = 4
        private const val TIME_REPORT_CONDITION =
            "(personDYNAMIC156957141416138823)^ORDERBYDESCdate_of_work"
        private const val BACKPACK_CONDITION =
            "(quantity>0^(warehouse.responsible_for_write_offCONTAINS_DYNAMIC161312729414608208" +
                "^ORactivity.service_managerDYNAMIC156957141416138823" +
                "^ORactivity.balance_management_managersCONTAINS_DYNAMIC161312729414608208" +
                "^ORactivity.project_managerDYNAMIC156957141416138823))"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val ACTIVE_COLUMNS = listOf(
            "sys_id",
            "number",
            "incoming_number",
            "registered_at",
            "opened_at",
            "sys_created_at",
            "state",
            "multicard_state",
            "short_description",
            "assignment_group",
            "multicard_request_type",
            "multicard_terminal_address",
            "multicard_name_client",
            "multicard_deadline",
            "assigned_user",
            "assigned_user.c_full_name",
            "multicard_terminal_model",
            "multicard_id_terminal",
        )
        private val CLOSED_COLUMNS = ACTIVE_COLUMNS + listOf(
            "resolved_at",
            "completed_at",
            "closed_at",
            "multicard_contact_person",
            "multicard_contact_phone",
            "multicard_comment_ing",
            "multicard_information",
            "description",
            "close_code",
            "close_notes",
        )
        private val COORDINATION_COLUMNS = (ACTIVE_COLUMNS + CLOSED_COLUMNS + listOf(
            "multicard_status",
            "multicard_mk_status",
            "u_multicard_state",
            "u_multicard_status",
            "u_mk_status",
            "mk_status",
            "multicard_request_state",
            "client_service_id.parent",
            "client_service_id",
            "itsm_request_type",
            "multicard_merchant_tin",
            "multicard_phone_tsp",
            "multicard_additional_information",
            "additional_information",
        )).distinct()

        private fun activeCondition(userId: String): String = listOf(
            "((assignment_group=171871931206710051^ORassignment_group=171872459801458049)",
            "^related_inquiry.company_location=171878330004108924",
            "^client_service_idLIKEСервисные заявки БЧ",
            "^client_service_idNOTLIKEЭкспертиза. Сервисные заявки БЧ",
            "^stateNOT INcancelled@closed@escalated@completed",
            "^assigned_user=$userId)",
        ).joinToString("")

        private fun closedCondition(userId: String): String =
            "((multicard_engineer=$userId^ORassigned_user=$userId^ORengineer_schedule.employee=$userId)" +
                "^(resolved_atONopt:167638634011265859^ORresolved_atONopt:167638599011967439))"

        private fun coordinationCondition(region: CoordinationRegion): String = listOf(
            "((assignment_group=171871931206710051^ORassignment_group=${region.assignmentGroupId})",
            "^related_inquiry.company_location=${region.companyLocationId}",
            "^client_service_idLIKEСервисные заявки БЧ",
            "^client_service_idNOTLIKEЭкспертиза. Сервисные заявки БЧ",
            "^stateNOT INcancelled@closed@escalated@completed)",
        ).joinToString("")

        private fun returnEquipmentCondition(userId: String): String = listOf(
            "(assigned_user=$userId",
            "^multicard_request_type=returnEquip",
            "^stateNOT INcancelled@closed_by_user@closed@escalated@completed)",
        ).joinToString("")

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

internal fun parseSimpleOneRequest(
    item: JsonObject,
    source: SimpleOneRequestSource = SimpleOneRequestSource.Active,
): SimpleOneRequest {
    val information = firstNonEmpty(
        item.field("multicard_information", "multicard_info", "information", "Информация"),
        item.field("multicard_additional_information", "additional_information"),
        item.field("Доп. информация", "Дополнительная информация"),
    )
    val description = item.field("description", "Описание")
    val infoTexts = listOf(information, description)
    return SimpleOneRequest(
        source = source,
        sysId = item.field("sys_id"),
        number = item.field("number", "__display_value"),
        incomingNumber = firstNonEmpty(
            item.field(
                "incoming_number",
                "multicard_incoming_number",
                "multicard_request_number",
                "multicard_number",
                "u_incoming_number",
            ),
            labeledValue(infoTexts, "Входящий номер", "Номер заявки Мультикарты", "Номер заявки"),
            item.field("short_description").takeIf { it.startsWith("SUTS") }.orEmpty(),
        ),
        registeredAt = item.field("registered_at", "opened_at", "sys_created_at", "created_at"),
        waitingReason = item.fieldDisplay(
            "waiting_reason",
            "hold_reason",
            "pending_reason",
            "multicard_waiting_reason",
            "Причина ожидания",
        ),
        state = item.fieldDisplay("state"),
        stateRaw = item.field("state"),
        multicardState = firstNonEmpty(
            item.fieldDisplay(
                "multicard_state",
                "multicard_status",
                "multicard_mk_status",
                "u_multicard_state",
                "u_multicard_status",
                "u_mk_status",
                "mk_status",
                "multicard_request_state",
                "МК Статус",
                "Статус заявки Мультикарта",
            ),
            labeledValue(infoTexts, "МК Статус", "Статус заявки Мультикарта"),
        ),
        shortDescription = item.field("short_description"),
        assignmentGroup = item.fieldDisplay("assignment_group"),
        initiator = item.field("initiator.c_full_name", "opened_by.c_full_name", "caller_id.c_full_name"),
        priority = item.fieldDisplay("priority", "Приоритет"),
        clientServiceParent = item.fieldDisplay(
            "client_service_id.parent",
            "client_service.parent",
            "client_service_parent",
        ),
        clientService = item.fieldDisplay("client_service_id", "client_service"),
        requestType = item.field(
            "multicard_request_type",
            "request_type",
            "itsm_request_type",
            "service_request_type",
            "multicard_type",
            "type",
        ),
        address = firstNonEmpty(
            item.field("multicard_terminal_address", "Адрес установки терминала"),
            labeledValue(infoTexts, "Адрес установки терминала", "Адрес ТСП", "Адрес"),
        ),
        customer = firstNonEmpty(
            item.field("multicard_name_client", "Заказчик"),
            labeledValue(infoTexts, "Заказчик", "Наименование юр.лица", "Клиент"),
        ),
        deadline = item.field("multicard_deadline", "Предельный срок СУТС", "Предельный срок"),
        resolvedAt = item.field(
            "completed_at",
            "completion_time",
            "multicard_completed_at",
            "multicard_closing_date",
            "resolved_at",
        ),
        completedAt = item.field("completed_at", "completion_time", "multicard_completed_at"),
        closedAt = item.field("closed_at", "multicard_closing_date", "resolved_at"),
        assignedUser = item.fieldDisplay("assigned_user.c_full_name", "assigned_user"),
        assignedUserId = item.field(
            "assigned_user.value",
            "assigned_user.database_value",
            "assigned_user.sys_id",
        ),
        terminalModel = firstNonEmpty(
            item.field("multicard_terminal_model", "Модель POS-терминала"),
            labeledValue(infoTexts, "Модель POS-терминала", "Модель терминала"),
        ),
        terminalId = firstNonEmpty(
            item.field("multicard_id_terminal", "ID терминал", "ID терминала"),
            labeledValue(infoTexts, "ID терминал", "ID терминала", "Оборудование POS"),
        ),
        contactPerson = item.field("multicard_contact_person", "Контактное лицо", "Kонтактное лицо"),
        contactPhone = firstNonEmpty(
            item.field("multicard_contact_phone", "multicard_phone", "contact_phone", "phone"),
            labeledValue(infoTexts, "Номер телефона ТСП", "Телефон ТСП", "Телефон"),
        ),
        engineerComment = item.field("multicard_comment_ing"),
        closureCode = firstNonEmpty(
            item.fieldDisplay("close_code", "closure_code", "resolution_code", "multicard_close_code"),
            labeledValue(infoTexts, "Код закрытия"),
        ),
        resolution = firstNonEmpty(
            item.field("close_notes", "resolution", "solution", "resolved_notes", "multicard_resolution"),
            labeledValue(infoTexts, "Решение"),
        ),
        additionalInformation = information,
        description = description,
        installedFiscalStorageSerialNumber = item.field(
            "multicard_installed_fn_serial_number",
            "multicard_fiscal_storage_serial_number",
            "multicard_fn_serial_number",
        ),
        ofdTariffActivationCode = item.field(
            "multicard_ofd_tariff_activation_code",
            "multicard_used_ofd_tariff_activation_code",
            "multicard_ofd_activation_code",
        ),
        usedSimCard = item.field("multicard_used_sim_card", "multicard_sim_card", "multicard_sim"),
    )
}

internal fun parseBackpackItem(item: JsonObject): BackpackItem {
    val sysId = item.field("sys_id", "id")
    val searchCode = item.field("Код поиска ЗИП", "search_code", "code", "name")
    val name = firstNonEmpty(
        item.fieldDisplay("ЗИП.Наименование"),
        item.fieldDisplay("zip.name", "zip_id.name", "spare_part.name", "spare.name"),
        item.field("zip.name", "zip_id.name", "spare_part.name", "spare.name", "item.name"),
    )
    val serialNumber = firstNonEmpty(
        item.field("ЗИП.S/N"),
        item.field("zip.s_n", "zip_id.s_n", "spare_part.s_n"),
        item.field("zip.serial_number", "zip_id.serial_number", "spare_part.serial_number"),
        item.field("serial_number", "sn", "s/n", "s_n", "serial"),
    )
    val responsible = firstNonEmpty(
        item.fieldDisplay("Склад.Ответственные за списание"),
        item.fieldDisplay("warehouse.responsible_for_write_off"),
        item.field("warehouse.responsible_for_write_off", "responsible_for_write_off"),
    )
    val receivedAtRaw = firstNonEmpty(
        item.field("Когда изменено"),
        item.field("sys_updated_at", "updated_at"),
    )
    val receivedAt = normalizeBackpackDate(receivedAtRaw)
    val quantityRaw = firstNonEmpty(
        item.field("Остаток", "Количество"),
        item.field("quantity"),
    ).replace(',', '.')
    val quantity = quantityRaw.toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0
    val normalizedName = name.ifBlank { NOT_SET }
    val normalizedSerial = serialNumber.ifBlank { NOT_SET }
    val normalizedResponsible = responsible.ifBlank { NOT_SET }
    val id = firstNonEmpty(
        sysId,
        searchCode,
        listOf(normalizedName, normalizedSerial, normalizedResponsible).joinToString("|"),
    )
    return BackpackItem(
        id = id,
        name = normalizedName,
        serialNumber = normalizedSerial,
        responsible = normalizedResponsible,
        receivedAt = receivedAt.first.ifBlank { NOT_SET },
        receivedAtEpochMillis = receivedAt.second,
        quantity = quantity,
    )
}

internal fun parseEmployeeAddress(item: JsonObject): EmployeeAddress? {
    val sysId = firstNonEmpty(item.field("sys_id"), item.fieldDisplay("sys_id"))
    val title = firstNonEmpty(item.field("locality"), item.fieldDisplay("__display_value"))
    if (sysId.isBlank() || title.isBlank()) return null
    return EmployeeAddress(
        sysId = sysId,
        title = title,
        region = item.fieldDisplay("regions"),
        federalRegion = item.fieldDisplay("federal_region"),
    )
}

internal fun parseEmployee(item: JsonObject, fallbackAddress: EmployeeAddress? = null): Employee {
    val firstName = item.field("first_name")
    val lastName = item.field("last_name")
    val middleName = item.field("middle_name")
    val assembledName = listOf(lastName, firstName, middleName)
        .filter(String::isNotBlank)
        .joinToString(" ")
    val address = parseEmployeeReferenceAddress(item) ?: fallbackAddress
    return Employee(
        sysId = firstNonEmpty(item.field("sys_id"), item.fieldDisplay("sys_id")),
        displayName = firstNonEmpty(item.field("c_fio"), assembledName, item.fieldDisplay("__display_value")),
        firstName = firstName,
        lastName = lastName,
        middleName = middleName,
        login = item.field("username"),
        email = item.field("email"),
        position = item.fieldDisplay("c_position_id"),
        manager = item.fieldDisplay("manager"),
        company = item.fieldDisplay("company"),
        department = item.fieldDisplay("department"),
        phone = item.field("mobile_phone"),
        address = address,
        isActive = item.rawField("active").simpleOneBoolean(),
        isLocked = item.rawField("locked_out").simpleOneBoolean(),
        updatedAt = item.field("sys_updated_at"),
    )
}

internal fun parseEmployeeSections(item: JsonObject): List<EmployeeSection> {
    val sections = item["sections"] as? JsonArray ?: return emptyList()
    return sections.mapIndexedNotNull { sectionIndex, rawSection ->
        val section = rawSection as? JsonObject ?: return@mapIndexedNotNull null
        val elements = section["elements"] as? JsonArray ?: return@mapIndexedNotNull null
        val fields = elements.mapIndexedNotNull { fieldIndex, rawField ->
            parseEmployeeField(rawField as? JsonObject ?: return@mapIndexedNotNull null, fieldIndex)
        }
        if (fields.isEmpty()) return@mapIndexedNotNull null
        val title = firstNonEmpty(section.string("name"), section.string("title"), "Информация")
            .trim('*', ' ')
        EmployeeSection("$sectionIndex|$title", title, fields)
    }
}

private fun parseEmployeeReferenceAddress(item: JsonObject): EmployeeAddress? {
    val raw = item.rawField("c_tgr")
    val sysId = raw.fieldDatabaseValue()
    val title = fieldValue(raw, preferDisplay = true)
    if (sysId.isBlank() || title.isBlank()) return null
    return EmployeeAddress(
        sysId = sysId,
        title = title,
        region = item.fieldDisplay("c_tgr.regions"),
        federalRegion = item.fieldDisplay("c_tgr.federal_region"),
    )
}

private fun parseEmployeeField(element: JsonObject, index: Int): EmployeeField? {
    if (element["hidden"].simpleOneBoolean() == true) return null
    val columnName = element.string("sys_column_name")
    val title = element.string("name").trim('*', ' ')
    if (title.isBlank() || columnName.equals("password_hash", ignoreCase = true) ||
        title.equals("пароль", ignoreCase = true)
    ) return null
    val rawValue = element["value"] ?: return null
    val value = if (element.string("column_type") == "boolean") {
        rawValue.simpleOneBoolean()?.let { if (it) "Да" else "Нет" }.orEmpty()
    } else {
        fieldValue(rawValue, preferDisplay = true).plainSimpleOneText()
    }
    if (value.isBlank()) return null
    val systemName = firstNonEmpty(element.string("system_name"), columnName, index.toString())
    return EmployeeField("$systemName|$index", title, value)
}

private fun Employee.mergeMissing(fallback: Employee): Employee = copy(
    sysId = sysId.ifBlank { fallback.sysId },
    displayName = displayName.ifBlank { fallback.displayName },
    firstName = firstName.ifBlank { fallback.firstName },
    lastName = lastName.ifBlank { fallback.lastName },
    middleName = middleName.ifBlank { fallback.middleName },
    login = login.ifBlank { fallback.login },
    email = email.ifBlank { fallback.email },
    position = position.ifBlank { fallback.position },
    manager = manager.ifBlank { fallback.manager },
    company = company.ifBlank { fallback.company },
    department = department.ifBlank { fallback.department },
    phone = phone.ifBlank { fallback.phone },
    address = address ?: fallback.address,
    isActive = isActive ?: fallback.isActive,
    isLocked = isLocked ?: fallback.isLocked,
    updatedAt = updatedAt.ifBlank { fallback.updatedAt },
    detailSections = detailSections.ifEmpty { fallback.detailSections },
)

private fun BackpackItem.mergeMissing(fallback: BackpackItem): BackpackItem = copy(
    id = id.ifBlank { fallback.id },
    name = name.takeUnless { it == NOT_SET }.orEmpty().ifBlank { fallback.name },
    serialNumber = serialNumber.takeUnless { it == NOT_SET }.orEmpty().ifBlank { fallback.serialNumber },
    responsible = responsible.takeUnless { it == NOT_SET }.orEmpty().ifBlank { fallback.responsible },
    receivedAt = receivedAt.takeUnless { it == NOT_SET }.orEmpty().ifBlank { fallback.receivedAt },
    receivedAtEpochMillis = receivedAtEpochMillis ?: fallback.receivedAtEpochMillis,
    quantity = quantity.takeIf { it > 0 } ?: fallback.quantity,
)

private fun normalizeBackpackDate(raw: String): Pair<String, Long?> {
    val value = raw.trim()
    if (value.isEmpty()) return "" to null
    val localDateTime = runCatching { LocalDateTime.parse(value, BACKPACK_INPUT_DATE_FORMATTER) }.getOrNull()
        ?: return value to null
    val instant = localDateTime.toInstant(ZoneOffset.UTC)
    return BACKPACK_OUTPUT_DATE_FORMATTER.format(instant.atZone(ZoneId.systemDefault())) to instant.toEpochMilli()
}

internal fun simpleOneListItems(response: JsonObject): List<JsonObject>? {
    val data = response["data"] as? JsonObject
    val containers = listOfNotNull(data, response)
    containers.forEach { container ->
        listOf("items", "list", "records").forEach { key ->
            val values = container[key] as? JsonArray
            if (values != null) return values.mapNotNull { it as? JsonObject }
        }
    }
    return null
}

internal fun simpleOneRecordItem(response: JsonObject): JsonObject? {
    val data = response["data"] as? JsonObject
    data?.let { container ->
        listOf("item", "record", "fields").forEach { key ->
            (container[key] as? JsonObject)?.let { return it }
        }
        if (container.isNotEmpty()) return container
    }
    listOf("item", "record").forEach { key ->
        (response[key] as? JsonObject)?.let { return it }
    }
    return null
}

internal fun flattenSimpleOneRecord(item: JsonObject): JsonObject {
    val sections = item["sections"] as? JsonArray ?: return item
    val additions = mutableMapOf<String, JsonElement>()
    sections.mapNotNull { it as? JsonObject }.forEach { section ->
        val elements = section["elements"] as? JsonArray ?: return@forEach
        elements.mapNotNull { it as? JsonObject }.forEach { element ->
            val value = element["value"] ?: return@forEach
            listOf("sys_column_name", "system_name", "name").forEach { keyName ->
                val key = element.string(keyName)
                if (key.isNotBlank() && item.field(key).isBlank() && key !in additions) {
                    additions[key] = value
                }
            }
        }
    }
    if (additions.isEmpty()) return item
    return buildJsonObject {
        item.forEach { (key, value) -> put(key, value) }
        additions.forEach { (key, value) -> put(key, value) }
    }
}

private fun simpleOneTotalCount(response: JsonObject): Int? {
    val containers = listOfNotNull(response["data"] as? JsonObject, response)
    return containers.firstNotNullOfOrNull { container ->
        listOf("total", "total_count", "count").firstNotNullOfOrNull { key ->
            fieldValue(container[key], preferDisplay = false).toIntOrNull()
        }
    }
}

private fun SimpleOneRequest.mergeMissing(fallback: SimpleOneRequest): SimpleOneRequest = copy(
    sysId = sysId.ifBlank { fallback.sysId },
    number = number.ifBlank { fallback.number },
    incomingNumber = incomingNumber.ifBlank { fallback.incomingNumber },
    registeredAt = registeredAt.ifBlank { fallback.registeredAt },
    waitingReason = waitingReason.ifBlank { fallback.waitingReason },
    state = state.ifBlank { fallback.state },
    stateRaw = stateRaw.ifBlank { fallback.stateRaw },
    multicardState = multicardState.ifBlank { fallback.multicardState },
    shortDescription = shortDescription.ifBlank { fallback.shortDescription },
    assignmentGroup = assignmentGroup.ifBlank { fallback.assignmentGroup },
    initiator = initiator.ifBlank { fallback.initiator },
    priority = priority.ifBlank { fallback.priority },
    clientServiceParent = clientServiceParent.ifBlank { fallback.clientServiceParent },
    clientService = clientService.ifBlank { fallback.clientService },
    requestType = requestType.ifBlank { fallback.requestType },
    address = address.ifBlank { fallback.address },
    customer = customer.ifBlank { fallback.customer },
    deadline = deadline.ifBlank { fallback.deadline },
    resolvedAt = resolvedAt.ifBlank { fallback.resolvedAt },
    completedAt = completedAt.ifBlank { fallback.completedAt },
    closedAt = closedAt.ifBlank { fallback.closedAt },
    assignedUser = assignedUser.ifBlank { fallback.assignedUser },
    assignedUserId = assignedUserId.ifBlank { fallback.assignedUserId },
    terminalModel = terminalModel.ifBlank { fallback.terminalModel },
    terminalId = terminalId.ifBlank { fallback.terminalId },
    contactPerson = contactPerson.ifBlank { fallback.contactPerson },
    contactPhone = contactPhone.ifBlank { fallback.contactPhone },
    engineerComment = engineerComment.ifBlank { fallback.engineerComment },
    closureCode = closureCode.ifBlank { fallback.closureCode },
    resolution = resolution.ifBlank { fallback.resolution },
    additionalInformation = additionalInformation.ifBlank { fallback.additionalInformation },
    description = description.ifBlank { fallback.description },
    installedFiscalStorageSerialNumber = installedFiscalStorageSerialNumber.ifBlank {
        fallback.installedFiscalStorageSerialNumber
    },
    ofdTariffActivationCode = ofdTariffActivationCode.ifBlank { fallback.ofdTariffActivationCode },
    usedSimCard = usedSimCard.ifBlank { fallback.usedSimCard },
    tableFields = if (tableFields.isEmpty()) fallback.tableFields else tableFields,
)

private fun firstNonEmpty(vararg values: String): String = values.firstOrNull(String::isNotBlank).orEmpty()

private fun labeledValue(texts: List<String>, vararg labels: String): String {
    val normalizedLabels = labels.map { it.trim().lowercase().replace('ё', 'е') }
    texts.forEach { text ->
        text.lineSequence().forEach { line ->
            val separator = listOf(':', '=').map(line::indexOf).filter { it > 0 }.minOrNull() ?: return@forEach
            val key = line.substring(0, separator).trim().lowercase().replace('ё', 'е')
            if (normalizedLabels.any { key == it }) {
                return line.substring(separator + 1).trim()
            }
        }
    }
    return ""
}

private suspend fun Call.awaitSimpleOne(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

private fun JsonObject.string(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
    (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
}.orEmpty()

private fun JsonObject.field(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
    fieldValue(rawField(key), preferDisplay = false).takeIf(String::isNotBlank)
}.orEmpty()

private fun JsonObject.fieldDisplay(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
    fieldValue(rawField(key), preferDisplay = true).takeIf(String::isNotBlank)
}.orEmpty()

private fun JsonObject.rawField(key: String): JsonElement? {
    get(key)?.let { return it }
    val parts = key.split('.')
    if (parts.size == 1) return null
    var current: JsonElement = this
    parts.forEach { part ->
        current = (current as? JsonObject)?.get(part) ?: return null
    }
    return current
}

private fun fieldValue(raw: JsonElement?, preferDisplay: Boolean): String = when (raw) {
    null, JsonNull -> ""
    is JsonPrimitive -> raw.contentOrNull?.trim().orEmpty()
    is JsonArray -> raw.map { fieldValue(it, preferDisplay = true) }
        .filter(String::isNotBlank)
        .joinToString(", ")
    is JsonObject -> {
        if (preferDisplay) raw.string("display_value").takeIf(String::isNotBlank)?.let { return it }
        raw["value"]?.let { value ->
            fieldValue(value, preferDisplay).takeIf(String::isNotBlank)?.let { return it }
        }
        raw.string("display_value").ifBlank { raw.string("database_value") }
    }
}

private fun JsonElement?.fieldDatabaseValue(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> contentOrNull?.trim().orEmpty()
    is JsonArray -> firstNotNullOfOrNull { it.fieldDatabaseValue().takeIf(String::isNotBlank) }.orEmpty()
    is JsonObject -> {
        get("value")?.fieldDatabaseValue()?.takeIf(String::isNotBlank)
            ?: string("database_value").ifBlank { string("display_value") }
    }
}

private fun JsonElement?.simpleOneBoolean(): Boolean? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> when (contentOrNull?.trim()?.lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> null
    }
    is JsonObject -> (get("value") ?: get("database_value")).simpleOneBoolean()
    else -> null
}

private fun String.plainSimpleOneText(): String = replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .trim()

private val BACKPACK_INPUT_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val BACKPACK_OUTPUT_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
