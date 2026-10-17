package ru.lumastack.lumawork.data.feedback

import android.content.Context
import android.os.Build
import android.util.Base64
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
import ru.lumastack.lumawork.BuildConfig

@Serializable enum class FeedbackKind(val title:String){@SerialName("ERROR")Error("Ошибка"),@SerialName("IMPROVEMENT")Improvement("Предложение"),@SerialName("FREEZE")Freeze("Зависание"),@SerialName("SLOWDOWN")Slowdown("Торможение"),@SerialName("LONG_LOADING")LongLoading("Долгая загрузка"),@SerialName("UI_INCONVENIENT")UiInconvenient("Неудобный интерфейс"),@SerialName("UI_INAPPROPRIATE")UiInappropriate("Неуместный элемент"),@SerialName("OTHER")Other("Другое")}
@Serializable enum class FeedbackImpact(val title:String){@SerialName("MINOR")Minor("Незначительно"),@SerialName("INTERFERES")Interferes("Мешает работе"),@SerialName("BLOCKS")Blocks("Работа невозможна")}
@Serializable enum class FeedbackFrequency(val title:String){@SerialName("ONCE")Once("Один раз"),@SerialName("SOMETIMES")Sometimes("Иногда"),@SerialName("ALWAYS")Always("Всегда")}
@Serializable enum class FeedbackStatus(val title:String){@SerialName("DRAFT")Draft("Черновик"),@SerialName("NEW")New("Новое"),@SerialName("IN_PROGRESS")InProgress("В работе"),@SerialName("RESOLVED")Resolved("Решено"),@SerialName("CLOSED")Closed("Закрыто")}
@Serializable enum class FeedbackPriority(val title:String){@SerialName("LOW")Low("Низкий"),@SerialName("NORMAL")Normal("Обычный"),@SerialName("HIGH")High("Высокий"),@SerialName("CRITICAL")Critical("Критический")}
@Serializable enum class FeedbackEmailStatus { @SerialName("NOT_QUEUED")NotQueued,@SerialName("PENDING")Pending,@SerialName("SENDING")Sending,@SerialName("SENT")Sent,@SerialName("FAILED")Failed }
@Serializable data class FeedbackAttachment(val id:String,val fileName:String="",val mimeType:String="image/jpeg",val sizeBytes:Int=0,val width:Int=0,val height:Int=0,val createdAt:String="")
@Serializable data class FeedbackAddition(val id:String,val text:String,val createdAt:String="")
@Serializable data class FeedbackMessage(val id:String,val number:String="",val kind:FeedbackKind=FeedbackKind.Other,val title:String="",val message:String="",val reproductionSteps:String?=null,val expectedResult:String?=null,val areaCodes:List<String> = emptyList(),val otherArea:String?=null,val impact:FeedbackImpact=FeedbackImpact.Minor,val frequency:FeedbackFrequency=FeedbackFrequency.Once,val status:FeedbackStatus=FeedbackStatus.New,val priority:FeedbackPriority=FeedbackPriority.Normal,val adminNote:String?=null,val reporterEmail:String="",val reporterName:String?=null,val submittedAt:String?=null,val createdAt:String="",val updatedAt:String="",val resolvedAt:String?=null,val emailStatus:FeedbackEmailStatus=FeedbackEmailStatus.NotQueued,val emailError:String?=null,val attachments:List<FeedbackAttachment> = emptyList(),val additions:List<FeedbackAddition> = emptyList())
@Serializable data class FeedbackDraft(val clientRequestId:String=UUID.randomUUID().toString(),val kind:FeedbackKind=FeedbackKind.Error,val title:String="",val message:String="",val reproductionSteps:String="",val expectedResult:String="",val areaCodes:Set<String> = emptySet(),val otherArea:String="",val impact:FeedbackImpact=FeedbackImpact.Minor,val frequency:FeedbackFrequency=FeedbackFrequency.Once,val resubmittedFromId:String?=null){val valid:Boolean get()=title.trim().length>=5&&message.trim().length>=20&&areaCodes.isNotEmpty()&&("other" !in areaCodes||otherArea.isNotBlank())}
data class FeedbackImage(val name:String,val bytes:ByteArray)
@Serializable data class FeedbackSnapshot(val messages:List<FeedbackMessage> = emptyList(),val updatedAtEpochMillis:Long?=null)
@Serializable private data class MessagesEnvelope(val messages:List<FeedbackMessage> = emptyList())
@Serializable private data class MessageEnvelope(val message:FeedbackMessage)
@Serializable private data class DeviceInfo(val deviceModel:String,val osVersion:String,val appVersion:String,val language:String,val timeZone:String,val capturedAt:String)
@Serializable private data class FeedbackCreatePayload(val clientRequestId:String,val kind:FeedbackKind,val title:String,val message:String,val reproductionSteps:String?=null,val expectedResult:String?=null,val areaCodes:List<String>,val otherArea:String?=null,val impact:FeedbackImpact,val frequency:FeedbackFrequency,val deviceInfo:DeviceInfo,val resubmittedFromId:String?=null)
@Serializable private data class ChunkPayload(val uploadId:String,val imageId:String,val fileName:String,val chunkIndex:Int,val totalChunks:Int,val chunkBase64:String)
@Serializable private data class AdditionPayload(val text:String)
@Serializable private data class AdminFeedbackPayload(val status:FeedbackStatus,val priority:FeedbackPriority,val note:String?=null)

sealed class FeedbackFailure(message:String,cause:Throwable?=null):Exception(message,cause){class Network(cause:IOException):FeedbackFailure("Не удалось подключиться к обратной связи.",cause);data object Unauthorized:FeedbackFailure("Сессия приложения «Инженер» истекла.");class Http(status:Int):FeedbackFailure("Не удалось выполнить действие с обращением. Попробуйте ещё раз.");data object InvalidResponse:FeedbackFailure("Не удалось загрузить обращения.")}
interface FeedbackApi{suspend fun messages(token:String,admin:Boolean=false):List<FeedbackMessage>;suspend fun submit(token:String,draft:FeedbackDraft,images:List<FeedbackImage>):FeedbackMessage;suspend fun add(token:String,id:String,text:String):FeedbackMessage;suspend fun attachment(token:String,reportId:String,attachmentId:String,admin:Boolean=false):ByteArray;suspend fun updateAdmin(token:String,id:String,status:FeedbackStatus,priority:FeedbackPriority,note:String):FeedbackMessage;suspend fun retryEmail(token:String,id:String)}

class OkHttpFeedbackApi(private val origin:String,private val json:Json,private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).build()):FeedbackApi{
    override suspend fun messages(token:String,admin:Boolean)=decode<MessagesEnvelope>(request(if(admin)"/api/v2/admin/feedback" else "/api/v2/feedback",token)).messages
    override suspend fun submit(token:String,draft:FeedbackDraft,images:List<FeedbackImage>):FeedbackMessage{val payload=FeedbackCreatePayload(draft.clientRequestId,draft.kind,draft.title.trim(),draft.message.trim(),draft.reproductionSteps.trim().ifBlank{null},draft.expectedResult.trim().ifBlank{null},draft.areaCodes.sorted(),draft.otherArea.trim().takeIf{"other" in draft.areaCodes&&it.isNotBlank()},draft.impact,draft.frequency,DeviceInfo(Build.MODEL,"Android ${Build.VERSION.RELEASE}","${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",Locale.getDefault().displayLanguage,TimeZone.getDefault().id,Instant.now().toString()),draft.resubmittedFromId);val created=decode<MessageEnvelope>(request("/api/v2/feedback",token,"POST",json.encodeToString(payload))).message;images.take(4).forEach{upload(token,created.id,it)};return decode<MessageEnvelope>(request("/api/v2/feedback/${created.id}/submit",token,"POST","{}")).message}
    override suspend fun add(token:String,id:String,text:String)=decode<MessageEnvelope>(request("/api/v2/feedback/$id/additions",token,"POST",json.encodeToString(AdditionPayload(text.trim())))).message
    override suspend fun attachment(token:String,reportId:String,attachmentId:String,admin:Boolean):ByteArray=execute(request(if(admin)"/api/v2/admin/feedback/$reportId/attachments/$attachmentId" else "/api/v2/feedback/$reportId/attachments/$attachmentId",token)).use{it.body.bytes()}
    override suspend fun updateAdmin(token:String,id:String,status:FeedbackStatus,priority:FeedbackPriority,note:String)=decode<MessageEnvelope>(request("/api/v2/admin/feedback/$id",token,"PATCH",json.encodeToString(AdminFeedbackPayload(status,priority,note.trim().ifBlank{null})))).message
    override suspend fun retryEmail(token:String,id:String){execute(request("/api/v2/admin/feedback/$id/retry-email",token,"POST","{}")).close()}
    private suspend fun upload(token:String,id:String,image:FeedbackImage){require(image.bytes.size<=20*1024*1024);val chunkSize=512*1024;val total=(image.bytes.size+chunkSize-1)/chunkSize;val uploadId=UUID.randomUUID().toString();val imageId=UUID.randomUUID().toString();repeat(total){index->val start=index*chunkSize;val bytes=image.bytes.copyOfRange(start,minOf(start+chunkSize,image.bytes.size));execute(request("/api/v2/feedback/$id/attachments/chunk",token,"POST",json.encodeToString(ChunkPayload(uploadId,imageId,image.name,index,total,Base64.encodeToString(bytes,Base64.NO_WRAP))))).close()}}
    private fun request(path:String,token:String,method:String="GET",body:String?=null):Request{val b=Request.Builder().url(origin.trimEnd('/')+path).header("Authorization","Bearer $token").header("Accept","application/json");return when(method){"POST"->b.post(requireNotNull(body).toRequestBody(JSON)).build();"PATCH"->b.patch(requireNotNull(body).toRequestBody(JSON)).build();else->b.get().build()}}
    private suspend inline fun<reified T>decode(request:Request):T=execute(request).use{runCatching{json.decodeFromString<T>(it.body.string())}.getOrElse{throw FeedbackFailure.InvalidResponse}}
    private suspend fun execute(request:Request):Response{val r=try{client.newCall(request).awaitFeedback()}catch(e:IOException){throw FeedbackFailure.Network(e)};if(r.code in listOf(401,403)){r.close();throw FeedbackFailure.Unauthorized};if(!r.isSuccessful){val c=r.code;r.close();throw FeedbackFailure.Http(c)};return r}
    companion object{private val JSON="application/json; charset=utf-8".toMediaType()}
}
interface FeedbackCache{suspend fun read(owner:String,admin:Boolean=false):FeedbackSnapshot?;suspend fun save(owner:String,snapshot:FeedbackSnapshot,admin:Boolean=false)}
class FileFeedbackCache(context:Context,private val json:Json):FeedbackCache{private val dir=File(context.noBackupFilesDir,"feedback");override suspend fun read(owner:String,admin:Boolean)=withContext(Dispatchers.IO){val f=file(owner,admin);if(!f.isFile)return@withContext null;runCatching{json.decodeFromString<FeedbackSnapshot>(f.readText())}.getOrNull()};override suspend fun save(owner:String,snapshot:FeedbackSnapshot,admin:Boolean)=withContext(Dispatchers.IO){val f=file(owner,admin);f.parentFile?.mkdirs();val t=File(f.parentFile,"${f.name}.tmp");t.writeText(json.encodeToString(snapshot));if(!t.renameTo(f)){f.writeText(t.readText());t.delete()}};private fun file(v:String,a:Boolean)=File(dir,(if(a)"admin-"else "")+MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString(""){"%02x".format(it)}+".json")}
class FeedbackRepository(private val api:FeedbackApi,private val cache:FeedbackCache,private val now:()->Long=System::currentTimeMillis){suspend fun read(owner:String,admin:Boolean=false)=cache.read(owner,admin);suspend fun refresh(owner:String,token:String,admin:Boolean=false)=FeedbackSnapshot(api.messages(token,admin),now()).also{cache.save(owner,it,admin)};suspend fun submit(owner:String,token:String,draft:FeedbackDraft,images:List<FeedbackImage>):FeedbackSnapshot=replace(owner,api.submit(token,draft,images));suspend fun add(owner:String,token:String,id:String,text:String)=replace(owner,api.add(token,id,text));suspend fun attachment(token:String,report:String,attachment:String,admin:Boolean=false)=api.attachment(token,report,attachment,admin);suspend fun updateAdmin(owner:String,token:String,id:String,status:FeedbackStatus,priority:FeedbackPriority,note:String)=replace(owner,api.updateAdmin(token,id,status,priority,note),true);suspend fun retryEmail(token:String,id:String)=api.retryEmail(token,id);private suspend fun replace(owner:String,message:FeedbackMessage,admin:Boolean=false):FeedbackSnapshot{val old=read(owner,admin)?:FeedbackSnapshot();return old.copy(messages=(old.messages.filterNot{it.id==message.id}+message).sortedByDescending{it.submittedAt?:it.createdAt},updatedAtEpochMillis=now()).also{cache.save(owner,it,admin)}}}
private suspend fun Call.awaitFeedback():Response=suspendCancellableCoroutine{c->c.invokeOnCancellation{cancel()};enqueue(object:Callback{override fun onFailure(call:Call,e:IOException){if(c.isActive)c.resumeWithException(e)};override fun onResponse(call:Call,response:Response){if(c.isActive)c.resume(response)else response.close()}})}
