package ru.lumastack.lumawork.data.route

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.route.*
import ru.lumastack.lumawork.data.fuel.OkHttpFuelApi
import ru.lumastack.lumawork.domain.fuel.FuelDraft

class HomeRouteApiTest {
    private fun client(response: String, inspect: (Request) -> Unit = {}) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        assertEquals("Bearer token", request.header("Authorization"))
        inspect(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(response.toResponseBody()).build()
    }.build()
    @Test fun `ARM query and report payload match iOS work type contract`() = runBlocking {
        val settings = RouteSettings("Склад", "Дом")
        val api = OkHttpRouteApi("https://api.test", client("""{"records":[{"date":"2026-10-16","workType":"ARM","distanceKm":22}]}""") { request ->
            if (request.method == "GET") assertEquals("ARM", request.url.queryParameter("workType"))
            else {
                val payload = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
                assertEquals("ARM", payload["workType"]!!.jsonPrimitive.content)
            }
        })
        val day = api.fetchDay("token", "2026-10-16", settings, RouteWorkType.Arm)!!
        assertEquals(22, day.distanceKm)
        api.sendDay("token", day)
    }
    @Test fun `generated report without email confirmation must not count as sent`() = runBlocking {
        val api = OkHttpRouteApi("https://api.test", client("""{"success":true,"generated":true,"sent":false}"""))
        try { api.sendGsmReport("token", "2026-10"); fail("Email delivery must be confirmed") }
        catch (failure: RouteFailure.Http) { assertTrue(failure.message!!.contains("письмо не отправлено")) }
    }
    @Test fun `GSM report success and month payload match server confirmation`() = runBlocking {
        val api = OkHttpRouteApi("https://api.test", client("""{"success":true,"status":"SENT","outputFileName":"report.xlsx","sentToEmail":"user@example.com"}""") { request ->
            assertEquals("/api/v2/gsm/report", request.url.encodedPath)
            val payload = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            assertEquals("2026-10", payload["month"]!!.jsonPrimitive.content)
        })
        assertEquals("report.xlsx отправлен на user@example.com.", api.sendGsmReport("token", "2026-10").message)
    }
    @Test fun `fuel type is sent and survives server round trip`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val api = OkHttpFuelApi("https://api.test", json, client = client("""{"id":"fuel1","date":"2026-10-16","fuelType":"АИ-95","liters":10.5,"fuelCost":800}""") { request ->
            val payload = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            assertEquals("АИ-95", payload["fuelType"]!!.jsonPrimitive.content)
        })
        val saved = api.create("token", FuelDraft(date = "2026-10-16", fuelType = "АИ-95", liters = "10,5", fuelCost = "800"))
        assertEquals("АИ-95", FuelDraft(saved).fuelType)
        assertEquals(10.5, saved.liters!!, 0.0)
    }
}
