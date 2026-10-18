package ru.lumastack.lumawork.data.gsm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.auto.Vehicle
import ru.lumastack.lumawork.domain.auto.VehicleGsmSettings

class GsmProfileTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test fun `profile accepts supported wrappers snake aliases numeric strings and legacy fuel`() {
        for (wrapper in listOf("profile", "gsmProfile", "gsm_profile", "data")) {
            val result = GsmProfileCodec.decode(json.parseToJsonElement("""{"$wrapper":{"vehicle_id":42,"pos_project_id":"p","arm_project_id":null,"employee_phone":"8 999 1234567","fuel_norm":"8.5","default_start_odometer":"12345","fuel_type":"ДТ","report_start_month":null},"availableFuelTypes":["ДТ","",null]}"""))
            assertEquals("42", result.profile.vehicleId)
            assertEquals("p", result.profile.posProjectId)
            assertNull(result.profile.armProjectId)
            assertEquals(8.5, result.profile.fuelNorm, 0.0)
            assertEquals(12345, result.profile.defaultStartOdometer)
            assertEquals("+7(999)123-45-67", result.profile.employeePhone)
            assertEquals("2026-04", result.profile.reportStartMonth)
            assertEquals(listOf("ДТ"), result.profile.fuelTypes)
            assertEquals(listOf("ДТ"), result.availableFuelTypes)
        }
    }

    @Test fun `invalid project entries are omitted without losing valid budget codes`() {
        val projects = GsmProfileCodec.projects(json.parseToJsonElement("""{"projects":[{"id":"pos","name":"POS","budgetCode":"B123"},{"id":"arm","name":"АРМ","budgetCode":"B456"},{"id":"missing","name":"Без бюджета"},null]}"""))
        assertEquals(listOf(GsmProjectOption("pos", "POS", "B123"), GsmProjectOption("arm", "АРМ", "B456")), projects)
    }

    @Test fun `save sends explicit null IDs masked phone all report fields and unchanged legacy values`() = runBlocking {
        var requestBody = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("PUT", request.method)
            assertEquals("/api/v2/gsm/profile", request.url.encodedPath)
            assertEquals("Bearer token", request.header("Authorization"))
            val buffer = okio.Buffer()
            request.body!!.writeTo(buffer)
            requestBody = buffer.readUtf8()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"profile\":null}".toResponseBody()).build()
        }.build()
        val original = GsmProfile(employeePhone = "89991234567", employeeTitleCompany = "legacy title", employeeAddressPhone = "legacy address", budgetCode = "B", projectName = "legacy project", defaultStartOdometer = 345)
        val saved = OkHttpGsmProfileApi("https://example.test", json, client).save("token", original)
        val body = json.parseToJsonElement(requestBody).jsonObject
        listOf("vehicleId", "posProjectId", "armProjectId").forEach { assertEquals(JsonNull, body[it]) }
        assertEquals("+7(999)123-45-67", body["employeePhone"]!!.jsonPrimitive.content)
        assertEquals("legacy title", body["employeeTitleCompany"]!!.jsonPrimitive.content)
        assertEquals("legacy address", body["employeeAddressPhone"]!!.jsonPrimitive.content)
        assertEquals("B", body["budgetCode"]!!.jsonPrimitive.content)
        assertEquals("legacy project", body["projectName"]!!.jsonPrimitive.content)
        assertEquals("345", body["defaultStartOdometer"]!!.jsonPrimitive.content)
        assertEquals(original.copy(employeePhone = "+7(999)123-45-67"), saved)
    }

    @Test fun `new profile sends selected POS and visible fields without empty optional legacy strings rejected by backend`() = runBlocking {
        var submitted = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = okio.Buffer()
            request.body!!.writeTo(buffer)
            val payload = json.parseToJsonElement(buffer.readUtf8()).jsonObject
            // server.ts gsmProfileSchema: these optional fields reject empty strings before
            // the handler can derive the project budget/name and combined contact strings.
            listOf("budgetCode", "projectName", "employeeTitleCompany", "employeeAddressPhone").forEach {
                assertFalse("New profile must omit blank $it", payload.containsKey(it))
            }
            assertEquals("pos-project", payload["posProjectId"]!!.jsonPrimitive.content)
            assertEquals("vehicle", payload["vehicleId"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, payload["armProjectId"])
            assertEquals("Должность", payload["employeeJobTitle"]!!.jsonPrimitive.content)
            assertEquals("Компания", payload["employeeCompany"]!!.jsonPrimitive.content)
            assertEquals("Адрес", payload["employeeAddress"]!!.jsonPrimitive.content)
            assertEquals("+7(999)123-45-67", payload["employeePhone"]!!.jsonPrimitive.content)
            assertEquals("[\"АИ-95\"]", payload["fuelTypes"].toString())
            assertEquals("7.5", payload["fuelNorm"]!!.jsonPrimitive.content)
            submitted = true
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"profile\":null}".toResponseBody()).build()
        }.build()
        val profile = GsmProfile(vehicleId = "vehicle", posProjectId = "pos-project", employeeFullName = "Иванов Иван Иванович",
            employeeShortName = "Иванов И.И.", employeeReportSignName = "Иванов", authorizedFullName = "Петров Пётр Петрович",
            authorizedShortName = "Петров П.П.", employeeJobTitle = "Должность", employeeCompany = "Компания", employeeAddress = "Адрес",
            employeePhone = "89991234567", driverLicenseNumber = "1234567890", fuelCardNumber = "123456", carModel = "Lada Vesta",
            licensePlate = "А123ВС 82", fuelNorm = 7.5, fuelType = "АИ-95", fuelTypes = listOf("АИ-95"))
        val saved = OkHttpGsmProfileApi("https://example.test", json, client).save("token", profile)
        assertTrue(submitted)
        assertEquals(profile.copy(employeePhone = "+7(999)123-45-67"), saved)
    }

    @Test fun `incomplete phone blocks save before any request`() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { calls++; error("must not request") }.build()
        try { OkHttpGsmProfileApi("https://example.test", json, client).save("token", GsmProfile(employeePhone = "999")); fail("expected validation") }
        catch (error: GsmProfileFailure.Validation) { assertTrue(error.message!!.contains("телефон")) }
        assertEquals(0, calls)
    }

    @Test fun `phone mask handles pasted national and country prefixes and separator backspace`() {
        assertEquals("+7(999)123-45-67", GsmPhoneFormatter.format("+7 999 1234567"))
        assertEquals("+7(999)123-45-67", GsmPhoneFormatter.format("89991234567"))
        assertFalse(GsmPhoneFormatter.isComplete("+7(999)123"))
        assertTrue(GsmPhoneFormatter.isComplete("9991234567"))
        val edited = GsmPhoneFormatter.edit("+7(999)123-45-67", 10, 11, "")
        assertEquals("+7(999)124-56-7", edited.text)
        assertEquals(9, edited.cursorOffset)
        val replaced = GsmPhoneFormatter.edit("+7(999)123-45-67", 0, 16, "8 912 3456789")
        assertEquals("+7(912)345-67-89", replaced.text)
    }

    @Test fun `last fuel type cannot be removed and primary follows first selected`() {
        val one = GsmProfile(fuelTypes = listOf("АИ-95"), fuelType = "АИ-95")
        assertEquals(one, one.toggleFuelType("АИ-95"))
        val two = one.toggleFuelType("ДТ")
        assertEquals(listOf("АИ-95", "ДТ"), two.fuelTypes)
        assertEquals("ДТ", two.toggleFuelType("АИ-95").fuelType)
    }

    @Test fun `confirmed GSM vehicle applies model settings without replacing existing allowed fuels or odometer`() {
        val vehicle = Vehicle("v2", make = "Lada", model = "Vesta", licensePlate = "А123ВС 82", gsmSettings = VehicleGsmSettings(7.8, "ДТ", 999, "2026-06"))
        val current = GsmProfile(vehicleId = "v1", fuelType = "АИ-95", fuelTypes = listOf("АИ-95"), defaultStartOdometer = 123)
        val next = current.withVehicle(vehicle)
        assertEquals("v2", next.vehicleId)
        assertEquals("Lada Vesta", next.carModel)
        assertEquals("А123ВС 82", next.licensePlate)
        assertEquals(7.8, next.fuelNorm, 0.0)
        assertEquals("2026-06", next.reportStartMonth)
        assertEquals(listOf("АИ-95"), next.fuelTypes)
        assertEquals(123, next.defaultStartOdometer)
        assertEquals(listOf("ДТ"), GsmProfile().withVehicle(vehicle).fuelTypes)
    }

    @Test fun `late cached or refreshed profile preserves edited draft while advancing persisted baseline`() {
        var editor = GsmProfileEditor()
        editor = editor.edit { it.copy(employeeFullName = "Мой черновик") }
        editor = editor.loaded(GsmProfileSnapshot(GsmProfile(employeeFullName = "Сервер"), updatedAtEpochMillis = 10))
        assertEquals("Мой черновик", editor.draft.employeeFullName)
        assertEquals("Сервер", editor.snapshot.profile.employeeFullName)
        assertTrue(editor.isEdited)
    }

    @Test fun `save response does not overwrite edits made while request is running`() {
        var editor = GsmProfileEditor()
        editor = editor.loaded(GsmProfileSnapshot(GsmProfile(employeeFullName = "Исходное")))
        val saveRevision = editor.revision
        editor = editor.edit { it.copy(employeeFullName = "Новое") }
        editor = editor.saved(GsmProfileSnapshot(GsmProfile(employeeFullName = "Сохранённое")), saveRevision)
        assertEquals("Новое", editor.draft.employeeFullName)
        assertEquals("Сохранённое", editor.snapshot.profile.employeeFullName)
        assertTrue(editor.isEdited)
    }

    @Test fun `repository caches profile projects allowed types per owner without remote save on load`() = runBlocking {
        val cache = MemoryGsmCache()
        val api = FakeGsmApi()
        val repository = GsmProfileRepository(api, cache) { 42L }
        val loaded = repository.refresh("owner-a", "token")
        assertEquals("Сервер", loaded.profile.employeeFullName)
        assertEquals(listOf("ДТ"), loaded.availableFuelTypes)
        assertEquals(listOf(GsmProjectOption("p", "POS", "B")), loaded.projects)
        assertEquals(42L, repository.read("owner-a")!!.updatedAtEpochMillis)
        assertNull(repository.read("owner-b"))
        assertEquals(0, api.saves)
    }

    @Test fun `refresh started before save cannot replace saved cache`() = runBlocking {
        val deferred = CompletableDeferred<GsmProfileLoadResult>()
        val api = object : FakeGsmApi() { override suspend fun fetch(token: String) = deferred.await() }
        val repository = GsmProfileRepository(api, MemoryGsmCache())
        val load = async(start = CoroutineStart.UNDISPATCHED) { repository.refresh("owner", "token") }
        val saved = repository.save("owner", "token", GsmProfile(employeeFullName = "Сохранено", employeePhone = "9991234567"))
        deferred.complete(GsmProfileLoadResult(GsmProfile(employeeFullName = "Устарело")))
        load.await()
        assertEquals(saved.profile, repository.read("owner")!!.profile)
    }

    @Test fun `refresh project failure retains cached options and still persists profile`() = runBlocking {
        val cached = GsmProfileSnapshot(projects = listOf(GsmProjectOption("cached", "POS", "B")))
        val cache = MemoryGsmCache().apply { values["owner"] = cached }
        val api = object : FakeGsmApi() { override suspend fun projects(token: String): List<GsmProjectOption> = error("offline project list") }
        val result = GsmProfileRepository(api, cache).refresh("owner", "token")
        assertEquals("Сервер", result.profile.employeeFullName)
        assertEquals(cached.projects, result.projects)
    }
}

private class MemoryGsmCache : GsmProfileCache {
    val values = mutableMapOf<String, GsmProfileSnapshot>()
    override suspend fun read(owner: String) = values[owner]
    override suspend fun save(owner: String, snapshot: GsmProfileSnapshot) { values[owner] = snapshot }
}

private open class FakeGsmApi : GsmProfileApi {
    var saves = 0
    override suspend fun fetch(token: String) = GsmProfileLoadResult(GsmProfile(employeeFullName = "Сервер"), listOf("ДТ"))
    override suspend fun projects(token: String) = listOf(GsmProjectOption("p", "POS", "B"))
    override suspend fun save(token: String, profile: GsmProfile): GsmProfile { saves++; return profile }
}
