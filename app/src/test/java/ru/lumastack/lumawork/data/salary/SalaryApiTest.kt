package ru.lumastack.lumawork.data.salary

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SalaryApiTest {
    @Test fun `salary response shapes infer accrual month like iOS and reject malformed payload`() = runBlocking {
        for (key in listOf("records", "entries", "items", "data")) {
            val entry = api { """{"$key":[{"_id":"entry-1","date":"2026-06-05","baseSalary":1000}]}""" }.records("token").single()
            assertEquals("entry-1", entry.id)
            assertEquals("2026-05", entry.accrualMonthKey)
        }
        org.junit.Assert.assertTrue(runCatching { api { """{"unexpected":true}""" }.records("token") }.isFailure)
    }

    @Test fun `net create sends amount as base salary and clears unrelated comments`() = runBlocking {
        val api = api { request ->
            val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            assertEquals("1000.0", body["baseSalary"]?.toString())
            assertEquals("0.0", body["weekendPay"]?.toString())
            assertNull(body["comment"])
            body.toString()
        }
        api.create("token", SalaryDraft(amount = "1000", comment = "Старая заметка", kind = SalaryKind.Salary))
        Unit
    }

    @Test fun `vacation extension payload is restored from the v2 response`() = runBlocking {
        val api = api { """{"records":[{"id":"1","date":"2026-10-15","amount":1000,"payload":{"kind":"vacation","vacationStartDate":"2026-10-19","vacationEndDate":"2026-10-30"}}]}""" }
        val entry = api.records("token").single()
        assertEquals(SalaryKind.Vacation, entry.resolvedKind)
        assertEquals("2026-10-19", entry.vacationStartDate)
        assertEquals("2026-10-30", entry.vacationEndDate)
    }

    @Test fun `vacation dates are sent only for vacation payments`() = runBlocking {
        val api = api { request ->
            val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            if (body["kind"]?.jsonPrimitive?.content == "vacation") {
                assertEquals("2026-10-19", body["vacationStartDate"]?.jsonPrimitive?.content)
                assertEquals("2026-10-30", body["vacationEndDate"]?.jsonPrimitive?.content)
            } else {
                assertNull(body["vacationStartDate"])
                assertNull(body["vacationEndDate"])
            }
            body.toString()
        }
        val draft = SalaryDraft(date = "2026-10-15", amount = "1000", kind = SalaryKind.Vacation,
            vacationStartDate = "2026-10-19", vacationEndDate = "2026-10-30")
        assertEquals(SalaryKind.Vacation, api.create("token", draft).resolvedKind)
        assertNull(api.update("token", "1", draft.copy(kind = SalaryKind.Salary)).vacationStartDate)
    }

    private fun api(body: (okhttp3.Request) -> String): OkHttpSalaryApi = OkHttpSalaryApi("https://api.test", Json { ignoreUnknownKeys = true; explicitNulls = false },
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body(request).toResponseBody()).build()
        }.build())
}
