package ru.lumastack.lumawork.data.salary

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Serializable enum class SalaryKind {
    @SerialName("advance") Advance, @SerialName("salary") Salary, @SerialName("weekend") Weekend,
    @SerialName("gsm") Gsm, @SerialName("other") Other;
    val title: String get() = when (this) { Advance -> "Аванс"; Salary -> "Зарплата"; Weekend -> "Выходной"; Gsm -> "ГСМ"; Other -> "Другое" }
}

@Serializable data class SalaryEntry(
    val id: String? = null,
    val date: String,
    val baseSalary: Double = 0.0,
    val weekendPay: Double = 0.0,
    val periodMonth: String? = null,
    val amount: Double? = null,
    val kind: SalaryKind? = null,
    val comment: String? = null,
) {
    val accrualMonthKey: String get() = periodMonth?.takeIf(MONTH_REGEX::matches) ?: date.take(7)
    val usesNetAmount: Boolean get() = amount != null || date >= NET_START
    val payout: Double get() = if (usesNetAmount) amount ?: baseSalary + weekendPay else baseSalary + weekendPay - floor((baseSalary + weekendPay) * .13)
    val resolvedKind: SalaryKind get() = kind ?: if (date.takeLast(2).toIntOrNull()?.let { it <= 10 } == true) SalaryKind.Salary else SalaryKind.Advance
    companion object { const val NET_START = "2026-05-13" }
}

@Serializable data class SalarySnapshot(val records: List<SalaryEntry> = emptyList(), val updatedAtEpochMillis: Long? = null)

@Serializable data class SalaryDraft(
    val date: String = LocalDate.now().toString(), val periodMonth: String = defaultPeriod(LocalDate.now().toString()),
    val amount: String = "", val kind: SalaryKind = SalaryKind.Advance, val baseSalary: String = "", val weekendPay: String = "", val comment: String = "",
) {
    constructor(entry: SalaryEntry) : this(entry.date, entry.accrualMonthKey, entry.amount.numberText(), entry.resolvedKind, entry.baseSalary.numberText(), entry.weekendPay.numberText(), entry.comment.orEmpty())
}

data class SalaryMonth(val key: String, val entries: List<SalaryEntry>) {
    val total: Double get() = entries.sumOf(SalaryEntry::payout)
}
fun salaryMonths(records: List<SalaryEntry>): List<SalaryMonth> = records.groupBy(SalaryEntry::accrualMonthKey)
    .map { SalaryMonth(it.key, it.value.sortedByDescending(SalaryEntry::date)) }.sortedByDescending(SalaryMonth::key)
fun validateSalaryDraft(draft: SalaryDraft): String? {
    if (runCatching { LocalDate.parse(draft.date) }.isFailure) return "Укажите дату в формате ГГГГ-ММ-ДД."
    if (draft.date >= SalaryEntry.NET_START) {
        if (!MONTH_REGEX.matches(draft.periodMonth)) return "Укажите месяц в формате ГГГГ-ММ."
        val amount = draft.amount.number() ?: return "Укажите выплату на руки."
        if (amount < 0) return "Выплата должна быть неотрицательной."
    } else if (listOf(draft.baseSalary.number(), draft.weekendPay.number()).filterNotNull().any { it < 0 }) return "Суммы должны быть неотрицательными."
    return null
}

sealed class SalaryFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: IOException) : SalaryFailure("Не удалось подключиться к данным зарплаты.", cause)
    data object Unauthorized : SalaryFailure("Сессия LumaWork истекла.")
    class Http(status: Int) : SalaryFailure("Сервер зарплаты вернул HTTP $status.")
    data object InvalidResponse : SalaryFailure("Сервер зарплаты вернул некорректные данные.")
}
interface SalaryApi { suspend fun records(token: String): List<SalaryEntry>; suspend fun create(token: String, draft: SalaryDraft): SalaryEntry; suspend fun update(token: String, id: String, draft: SalaryDraft): SalaryEntry; suspend fun delete(token: String, id: String) }

class OkHttpSalaryApi(origin: String, private val json: Json, private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()) : SalaryApi {
    private val base = origin.trimEnd('/')
    override suspend fun records(token: String) = execute<SalaryEnvelope>(request("/api/v2/salary", token)).records
    override suspend fun create(token: String, draft: SalaryDraft) = execute<SalaryEntry>(request("/api/v2/salary", token, "POST", draft.payload(UUID.randomUUID().toString())))
    override suspend fun update(token: String, id: String, draft: SalaryDraft) = execute<SalaryEntry>(request("/api/v2/salary/$id", token, "PUT", draft.payload(id)))
    override suspend fun delete(token: String, id: String) { executeResponse(request("/api/v2/salary/$id", token, "DELETE")).close() }
    private fun request(path: String, token: String, method: String = "GET", body: String? = null): Request { val b = Request.Builder().url("$base$path").header("Authorization", "Bearer $token").header("Accept", "application/json"); return when (method) { "POST" -> b.post(requireNotNull(body).toRequestBody(JSON)).build(); "PUT" -> b.put(requireNotNull(body).toRequestBody(JSON)).build(); "DELETE" -> b.delete().build(); else -> b.get().build() } }
    private suspend inline fun <reified T> execute(request: Request): T = executeResponse(request).use { runCatching { json.decodeFromString<T>(it.body.string()) }.getOrElse { throw SalaryFailure.InvalidResponse } }
    private suspend fun executeResponse(request: Request): Response { val response = try { client.newCall(request).awaitSalary() } catch (error: IOException) { throw SalaryFailure.Network(error) }; if (response.code in listOf(401,403)) { response.close(); throw SalaryFailure.Unauthorized }; if (!response.isSuccessful) { val code=response.code; response.close(); throw SalaryFailure.Http(code) }; return response }
    private fun SalaryDraft.payload(id: String): String = json.encodeToString(SalaryEntry(id, date, baseSalary.number() ?: 0.0, weekendPay.number() ?: 0.0, periodMonth.takeIf { date >= SalaryEntry.NET_START }, amount.number().takeIf { date >= SalaryEntry.NET_START }, kind.takeIf { date >= SalaryEntry.NET_START }, comment.trim().ifBlank { null }))
    companion object { private val JSON = "application/json; charset=utf-8".toMediaType() }
}
@Serializable private data class SalaryEnvelope(val records: List<SalaryEntry> = emptyList())
interface SalaryCache { suspend fun read(owner: String): SalarySnapshot?; suspend fun save(owner: String, snapshot: SalarySnapshot) }
class FileSalaryCache(context: Context, private val json: Json) : SalaryCache { private val dir=File(context.noBackupFilesDir,"salary"); override suspend fun read(owner:String)=withContext(Dispatchers.IO){ val f=file(owner); if(!f.isFile)return@withContext null; runCatching{json.decodeFromString<SalarySnapshot>(f.readText())}.getOrNull() }; override suspend fun save(owner:String,snapshot:SalarySnapshot)=withContext(Dispatchers.IO){ val f=file(owner);f.parentFile?.mkdirs();val t=File(f.parentFile,"${f.name}.tmp");t.writeText(json.encodeToString(snapshot));if(!t.renameTo(f)){f.writeText(t.readText());t.delete()} }; private fun file(v:String)=File(dir,MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString(""){"%02x".format(it)}+".json") }
class SalaryRepository(private val api: SalaryApi, private val cache: SalaryCache, private val now:()->Long=System::currentTimeMillis) { suspend fun read(owner:String)=cache.read(owner); suspend fun refresh(owner:String,token:String)=SalarySnapshot(api.records(token).sortedByDescending(SalaryEntry::date),now()).also{cache.save(owner,it)}; suspend fun save(owner:String,token:String,id:String?,draft:SalaryDraft):SalarySnapshot { val saved=if(id==null)api.create(token,draft)else api.update(token,id,draft);val old=read(owner)?:SalarySnapshot();return old.copy(records=(if(id==null)old.records+saved else old.records.map{if(it.id==id)saved else it}).sortedByDescending(SalaryEntry::date),updatedAtEpochMillis=now()).also{cache.save(owner,it)} }; suspend fun delete(owner:String,token:String,entry:SalaryEntry):SalarySnapshot{val id=entry.id?:return read(owner)?:SalarySnapshot();api.delete(token,id);val old=read(owner)?:SalarySnapshot();return old.copy(records=old.records.filterNot{it.id==id},updatedAtEpochMillis=now()).also{cache.save(owner,it)}} }
private fun String.number()=trim().replace(',','.').toDoubleOrNull()
private fun Double?.numberText()=this?.let{if(it%1.0==0.0)it.toLong().toString()else it.toString()}.orEmpty()
private fun defaultPeriod(date:String):String { val ym=runCatching{YearMonth.parse(date.take(7))}.getOrElse{return date.take(7)};return if(date>=SalaryEntry.NET_START&&date.takeLast(2).toIntOrNull()?.let{it<=10}==true)ym.minusMonths(1).toString()else ym.toString() }
private val MONTH_REGEX=Regex("^\\d{4}-(0[1-9]|1[0-2])$")
private suspend fun Call.awaitSalary():Response=suspendCancellableCoroutine{c->c.invokeOnCancellation{cancel()};enqueue(object:Callback{override fun onFailure(call:Call,e:IOException){if(c.isActive)c.resumeWithException(e)};override fun onResponse(call:Call,response:Response){if(c.isActive)c.resume(response)else response.close()}})}
