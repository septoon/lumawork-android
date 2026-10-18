package ru.lumastack.lumawork.data.fuel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.fuel.*

class FuelApiTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private fun api(body: (Request) -> String) = OkHttpFuelApi("https://fuel.test", json,
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body(chain.request()).toResponseBody()).build()
        }.build())

    @Test fun `database uppercase fuel and adjustment kinds load with all numeric fields`() = runBlocking {
        val api = api { """{"records":[
            {"id":"fuel","date":"2026-10-15","recordType":"FUEL","adjustmentKind":null,"mileage":120,"liters":25.5,"fuelCost":1700},
            {"id":"paid","date":"2026-10-01","recordType":"ADJUSTMENT","adjustmentKind":"COMPENSATION_PAYMENT","monthKey":"2026-10","amount":5000},
            {"id":"debt","date":"2026-09-01","recordType":"ADJUSTMENT","adjustmentKind":"DEBT_DEDUCTION","monthKey":"2026-09","amount":1000,"carryoverDebtRub":200}
        ]}""" }
        val records = api.records("token")
        assertEquals(3, records.size)
        assertEquals(FuelRecordType.Fuel, records[0].recordType)
        assertEquals(25.5, records[0].liters!!, 0.0)
        assertEquals(FuelAdjustmentKind.CompensationPayment, records[1].adjustmentKind)
        assertEquals(FuelAdjustmentKind.DebtDeduction, records[2].adjustmentKind)
        assertEquals(200.0, records[2].carryoverDebtRub!!, 0.0)
    }

    @Test fun `create and update decode database responses while writing lowercase contract values`() = runBlocking {
        val api = api { request ->
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            val sent = buffer.readUtf8()
            assertTrue(sent.contains("\"recordType\":\"adjustment\""))
            assertTrue(sent.contains("\"adjustmentKind\":\"compensation_payment\""))
            """{"id":"saved","date":"2026-10-01","recordType":"ADJUSTMENT","adjustmentKind":"COMPENSATION_PAYMENT","amount":500}"""
        }
        val draft = FuelDraft(recordType = FuelRecordType.Adjustment, amount = "500", monthKey = "2026-10")
        assertEquals(FuelAdjustmentKind.CompensationPayment, api.create("token", draft).adjustmentKind)
        assertEquals("saved", api.update("token", "saved", draft).id)
    }

    @Test fun `missing records fails instead of replacing cached data with an empty list`() = runBlocking {
        assertTrue(runCatching { api { "{}" }.records("token") }.exceptionOrNull() is FuelFailure.InvalidResponse)
    }

    @Test fun `lowercase records and absent adjustment kind remain compatible`() = runBlocking {
        val records = api { """{"records":[{"date":"2026-10-15","recordType":"fuel"},{"date":"2026-10-01","recordType":"adjustment","adjustmentKind":"debt_deduction"}]}""" }.records("token")
        assertEquals(FuelRecordType.Fuel, records.first().recordType)
        assertNull(records.first().adjustmentKind)
        assertEquals(FuelAdjustmentKind.DebtDeduction, records.last().adjustmentKind)
    }
}
