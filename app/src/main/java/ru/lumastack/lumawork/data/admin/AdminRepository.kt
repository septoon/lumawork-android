package ru.lumastack.lumawork.data.admin

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Serializable data class AdminSystem(val service:String="",val status:String="unknown",val serverTime:String?=null,val startedAt:String?=null,val uptimeSeconds:Long=0,val nodeVersion:String="",val memoryResidentBytes:Long=0)
@Serializable data class AdminResources(val cpuUsagePercent:Double=0.0,val cpuCores:Int=0,val loadAverage1m:Double=0.0,val ramUsedBytes:Long=0,val ramTotalBytes:Long=0,val ramAvailableBytes:Long=0,val ramUsagePercent:Double=0.0,val diskUsedBytes:Long=0,val diskTotalBytes:Long=0,val diskAvailableBytes:Long=0,val diskUsagePercent:Double=0.0)
@Serializable data class AdminStats(val users:Int=0,val admins:Int=0,val blockedUsers:Int=0,val activeUsers7d:Int=0,val vehicles:Int=0,val vehicleCatalogs:Int=0,val missingVehicleImages:Int=0,val pendingVehicleImages:Int=0,val backpackBindings:Int=0,val missingBackpackImages:Int=0,val orphanImages:Int=0)
@Serializable data class AdminUserStorage(val userId:String="",val email:String="",val displayName:String="",val databaseBytes:Long=0,val fileBytes:Long=0,val totalBytes:Long=0,val recordCount:Int=0,val fileCount:Int=0)
@Serializable data class AdminDataStorage(val totalBytes:Long=0,val databaseBytes:Long=0,val filesBytes:Long=0,val uploadBytes:Long=0,val reportBytes:Long=0,val templateBytes:Long=0,val databaseBackupBytes:Long=0,val usersVisible:Boolean=false,val users:List<AdminUserStorage> = emptyList())
@Serializable data class AdminAction(val id:String,val actorEmail:String="",val action:String="",val targetType:String="",val targetId:String?=null,val summary:String?=null,val createdAt:String?=null)
@Serializable data class AdminOverview(val system:AdminSystem=AdminSystem(),val resources:AdminResources?=null,val stats:AdminStats=AdminStats(),val dataStorage:AdminDataStorage?=null,val recentActions:List<AdminAction> = emptyList())
@Serializable data class AdminUser(val id:String,val email:String,val role:String="user",val adminPermissions:List<String> = emptyList(),val isProtectedAdmin:Boolean=false,val registeredAt:String?=null,val lastActiveAt:String?=null,val isBlocked:Boolean=false,val blockedUntil:String?=null)
@Serializable enum class AdminImageKind(val title:String){@SerialName("userAvatar")UserAvatar("Аватары"),@SerialName("vehicleCatalog")VehicleCatalog("Автомобили"),@SerialName("backpackTerminal")BackpackTerminal("Рюкзак"),@SerialName("system")System("Системные"),@SerialName("orphan")Orphan("Без связей")}
@Serializable data class AdminImageBinding(val name:String,val value:String)
@Serializable data class AdminImageCapabilities(val canUpload:Boolean=false,val canDelete:Boolean=false,val canEditMetadata:Boolean=false)
@Serializable data class AdminServerImage(val id:String,val kind:AdminImageKind,val title:String,val subtitle:String?=null,val purpose:String="",val imageUrl:String?=null,val path:String?=null,val isMissing:Boolean=false,val usageCount:Int=0,val sharedReferenceCount:Int=0,val sizeBytes:Int?=null,val mimeType:String?=null,val width:Int?=null,val height:Int?=null,val updatedAt:String?=null,val bindings:List<AdminImageBinding> = emptyList(),val capabilities:AdminImageCapabilities=AdminImageCapabilities(),val metadata:Map<String,String> = emptyMap(),val aliases:List<String> = emptyList())
@Serializable data class AdminGsmTemplate(val fileName:String,val mimeType:String="application/vnd.openxmlformats-officedocument.spreadsheetml.template",val sizeBytes:Long=0,val updatedAt:String="",val sha256:String="")
@Serializable data class AdminGsmChunkResult(val complete:Boolean=false,val template:AdminGsmTemplate?=null,val backupCreated:String?=null,val validation:AdminGsmValidation?=null)
@Serializable data class AdminGsmValidation(val generatorDryRun:Boolean=false)
@Serializable data class AdminSnapshot(val overview:AdminOverview?=null,val users:List<AdminUser> = emptyList(),val images:List<AdminServerImage> = emptyList(),val gsmTemplate:AdminGsmTemplate?=null,val updatedAtEpochMillis:Long?=null)
@Serializable private data class OverviewEnvelope(val overview:AdminOverview?=null,val data:AdminOverview?=null)
@Serializable private data class UsersEnvelope(val users:List<AdminUser> = emptyList())
@Serializable private data class UserEnvelope(val user:AdminUser)
@Serializable private data class BlockPayload(val blockedUntil:String?=null)
@Serializable private data class AccessPayload(val role:String,val permissions:List<String>)
@Serializable private data class DeletePayload(val confirmationEmail:String)
@Serializable private data class AdminImagesEnvelope(val images:List<AdminServerImage> = emptyList())
@Serializable private data class AdminGsmEnvelope(val template:AdminGsmTemplate)
@Serializable private data class AdminImageChunkPayload(val uploadId:String,val chunkIndex:Int,val totalChunks:Int,val chunkBase64:String)
@Serializable private data class AdminGsmChunkPayload(val uploadId:String,val chunkIndex:Int,val totalChunks:Int,val chunkBase64:String,val fileName:String,val expectedSha256:String)
sealed class AdminFailure(message:String,cause:Throwable?=null):Exception(message,cause){class Network(cause:IOException):AdminFailure("Не удалось подключиться к админке.",cause);data object Forbidden:AdminFailure("Недостаточно прав администратора.");class Http(status:Int):AdminFailure("Админка вернула HTTP $status.");data object InvalidResponse:AdminFailure("Админка вернула некорректные данные.")}
interface AdminApi{suspend fun overview(token:String):AdminOverview;suspend fun users(token:String):List<AdminUser>;suspend fun block(token:String,id:String,blockedUntil:String?):AdminUser;suspend fun access(token:String,id:String,role:String,permissions:List<String>):AdminUser;suspend fun delete(token:String,id:String,email:String);suspend fun images(token:String):List<AdminServerImage> = emptyList();suspend fun uploadImage(token:String,image:AdminServerImage,bytes:ByteArray) = Unit;suspend fun deleteImage(token:String,image:AdminServerImage) = Unit;suspend fun gsmTemplate(token:String):AdminGsmTemplate? = null;suspend fun gsmFile(token:String):ByteArray = ByteArray(0);suspend fun replaceGsm(token:String,fileName:String,expectedSha256:String,bytes:ByteArray):AdminGsmChunkResult = AdminGsmChunkResult()}
class OkHttpAdminApi(private val origin:String,private val json:Json,private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS).build()):AdminApi{
    override suspend fun overview(token:String):AdminOverview{val body=executeText(request("/api/v2/admin/overview",token));return runCatching{json.decodeFromString<AdminOverview>(body)}.getOrElse{val e=runCatching{json.decodeFromString<OverviewEnvelope>(body)}.getOrElse{throw AdminFailure.InvalidResponse};e.overview?:e.data?:throw AdminFailure.InvalidResponse}}
    override suspend fun users(token:String)=decode<UsersEnvelope>(request("/api/v2/admin/users",token)).users
    override suspend fun block(token:String,id:String,blockedUntil:String?)=decodeUser(executeText(request("/api/v2/admin/users/$id/block",token,"POST",json.encodeToString(BlockPayload(blockedUntil)))))
    override suspend fun access(token:String,id:String,role:String,permissions:List<String>)=decodeUser(executeText(request("/api/v2/admin/users/$id/access",token,"PATCH",json.encodeToString(AccessPayload(role,permissions)))))
    override suspend fun delete(token:String,id:String,email:String){executeResponse(request("/api/v2/admin/users/$id",token,"DELETE",json.encodeToString(DeletePayload(email)))).close()}
    override suspend fun images(token:String)=decode<AdminImagesEnvelope>(request("/api/v2/admin/images",token)).images
    override suspend fun uploadImage(token:String,image:AdminServerImage,bytes:ByteArray){require(bytes.isNotEmpty()&&bytes.size<=20*1024*1024);val chunkSize=512*1024;val total=(bytes.size+chunkSize-1)/chunkSize;val uploadId=UUID.randomUUID().toString().lowercase();repeat(total){index->val start=index*chunkSize;val chunk=bytes.copyOfRange(start,minOf(start+chunkSize,bytes.size));executeResponse(request("/api/v2/admin/images/${image.kind.serialValue()}/${image.id}/image-chunk",token,"POST",json.encodeToString(AdminImageChunkPayload(uploadId,index,total,Base64.encodeToString(chunk,Base64.NO_WRAP))))).close()}}
    override suspend fun deleteImage(token:String,image:AdminServerImage){executeResponse(request("/api/v2/admin/images/${image.kind.serialValue()}/${image.id}",token,"DELETE","{}")).close()}
    override suspend fun gsmTemplate(token:String)=decode<AdminGsmEnvelope>(request("/api/v2/admin/gsm-template",token)).template
    override suspend fun gsmFile(token:String)=executeResponse(request("/api/v2/admin/gsm-template/file",token)).use{it.body.bytes()}
    override suspend fun replaceGsm(token:String,fileName:String,expectedSha256:String,bytes:ByteArray):AdminGsmChunkResult{require(bytes.isNotEmpty()&&bytes.size<=20*1024*1024);val chunkSize=512*1024;val total=(bytes.size+chunkSize-1)/chunkSize;val uploadId=UUID.randomUUID().toString().lowercase();var result=AdminGsmChunkResult();repeat(total){index->val start=index*chunkSize;val chunk=bytes.copyOfRange(start,minOf(start+chunkSize,bytes.size));result=decode(request("/api/v2/admin/gsm-template/chunk",token,"POST",json.encodeToString(AdminGsmChunkPayload(uploadId,index,total,Base64.encodeToString(chunk,Base64.NO_WRAP),fileName,expectedSha256))))};return result}
    private fun AdminImageKind.serialValue()=when(this){AdminImageKind.UserAvatar->"userAvatar";AdminImageKind.VehicleCatalog->"vehicleCatalog";AdminImageKind.BackpackTerminal->"backpackTerminal";AdminImageKind.System->"system";AdminImageKind.Orphan->"orphan"}
    private fun decodeUser(body:String)=runCatching{json.decodeFromString<UserEnvelope>(body).user}.getOrElse{throw AdminFailure.InvalidResponse}
    private inline fun<reified T>decodeBody(body:String):T=runCatching{json.decodeFromString<T>(body)}.getOrElse{throw AdminFailure.InvalidResponse}
    private suspend inline fun<reified T>decode(request:Request):T=decodeBody(executeText(request))
    private fun request(path:String,token:String,method:String="GET",body:String?=null):Request{val b=Request.Builder().url(origin.trimEnd('/')+path).header("Authorization","Bearer $token").header("Accept","application/json");return when(method){"POST"->b.post(requireNotNull(body).toRequestBody(JSON)).build();"PATCH"->b.patch(requireNotNull(body).toRequestBody(JSON)).build();"DELETE"->b.delete((body?:"{}").toRequestBody(JSON)).build();else->b.get().build()}}
    private suspend fun executeText(request:Request)=executeResponse(request).use{it.body.string()}
    private suspend fun executeResponse(request:Request):Response{val r=try{client.newCall(request).awaitAdmin()}catch(e:IOException){throw AdminFailure.Network(e)};if(r.code in listOf(401,403)){r.close();throw AdminFailure.Forbidden};if(!r.isSuccessful){val c=r.code;r.close();throw AdminFailure.Http(c)};return r}
    companion object{private val JSON="application/json; charset=utf-8".toMediaType()}
}
interface AdminCache{suspend fun read(owner:String):AdminSnapshot?;suspend fun save(owner:String,snapshot:AdminSnapshot)}
class FileAdminCache(context:Context,private val json:Json):AdminCache{private val dir=File(context.noBackupFilesDir,"admin");override suspend fun read(owner:String)=withContext(Dispatchers.IO){val f=file(owner);if(!f.isFile)return@withContext null;runCatching{json.decodeFromString<AdminSnapshot>(f.readText())}.getOrNull()};override suspend fun save(owner:String,snapshot:AdminSnapshot)=withContext(Dispatchers.IO){val f=file(owner);f.parentFile?.mkdirs();val t=File(f.parentFile,"${f.name}.tmp");t.writeText(json.encodeToString(snapshot));if(!t.renameTo(f)){f.writeText(t.readText());t.delete()}};private fun file(v:String)=File(dir,MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString(""){"%02x".format(it)}+".json")}
class AdminRepository(private val api:AdminApi,private val cache:AdminCache,private val now:()->Long=System::currentTimeMillis){suspend fun read(owner:String)=cache.read(owner);suspend fun refresh(owner:String,token:String,loadOverview:Boolean,loadUsers:Boolean,loadImages:Boolean=false,loadGsm:Boolean=false):AdminSnapshot{val old=read(owner)?:AdminSnapshot();val result=old.copy(overview=if(loadOverview)api.overview(token)else old.overview,users=if(loadUsers)api.users(token)else old.users,images=if(loadImages)api.images(token)else old.images,gsmTemplate=if(loadGsm)api.gsmTemplate(token)else old.gsmTemplate,updatedAtEpochMillis=now());cache.save(owner,result);return result};suspend fun block(owner:String,token:String,id:String,until:String?):AdminSnapshot=replace(owner,api.block(token,id,until));suspend fun access(owner:String,token:String,id:String,role:String,permissions:List<String>):AdminSnapshot=replace(owner,api.access(token,id,role,permissions));suspend fun delete(owner:String,token:String,id:String,email:String):AdminSnapshot{api.delete(token,id,email);val old=read(owner)?:AdminSnapshot();return old.copy(users=old.users.filterNot{it.id==id},updatedAtEpochMillis=now()).also{cache.save(owner,it)}};suspend fun uploadImage(owner:String,token:String,image:AdminServerImage,bytes:ByteArray)=refreshImages(owner,token){api.uploadImage(token,image,bytes)};suspend fun deleteImage(owner:String,token:String,image:AdminServerImage)=refreshImages(owner,token){api.deleteImage(token,image)};suspend fun gsmFile(token:String)=api.gsmFile(token);suspend fun replaceGsm(owner:String,token:String,fileName:String,bytes:ByteArray):AdminSnapshot{val old=read(owner)?:AdminSnapshot();val expected=old.gsmTemplate?.sha256?:throw AdminFailure.InvalidResponse;val result=api.replaceGsm(token,fileName,expected,bytes);if(!result.complete||result.template==null||result.validation?.generatorDryRun!=true)throw AdminFailure.InvalidResponse;return old.copy(gsmTemplate=result.template,updatedAtEpochMillis=now()).also{cache.save(owner,it)}};private suspend fun refreshImages(owner:String,token:String,action:suspend()->Unit):AdminSnapshot{action();val old=read(owner)?:AdminSnapshot();return old.copy(images=api.images(token),updatedAtEpochMillis=now()).also{cache.save(owner,it)}};private suspend fun replace(owner:String,user:AdminUser):AdminSnapshot{val old=read(owner)?:AdminSnapshot();return old.copy(users=(old.users.filterNot{it.id==user.id}+user).sortedBy{it.email},updatedAtEpochMillis=now()).also{cache.save(owner,it)}}}
private suspend fun Call.awaitAdmin():Response=suspendCancellableCoroutine{c->c.invokeOnCancellation{cancel()};enqueue(object:Callback{override fun onFailure(call:Call,e:IOException){if(c.isActive)c.resumeWithException(e)};override fun onResponse(call:Call,response:Response){if(c.isActive)c.resume(response)else response.close()}})}
