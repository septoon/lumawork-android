package ru.lumastack.lumawork.data.fuel

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.BuildConfig

interface FuelReceiptsCache {
    suspend fun read(owner: String): List<FuelReceipt>
    suspend fun save(owner: String, receipts: List<FuelReceipt>)
    suspend fun clear(owner: String)
}

class FileFuelReceiptsCache(private val directory: File, private val json: Json) : FuelReceiptsCache {
    override suspend fun read(owner: String): List<FuelReceipt> = withContext(Dispatchers.IO) {
        val file = File(directory, "${receiptHash(owner)}.json")
        if (!file.isFile || file.length() > 2 * 1024 * 1024) return@withContext emptyList()
        runCatching {
            json.decodeFromString<List<FuelReceipt>>(file.readText()).onEach { it.validate() }.also {
                require(it.map(FuelReceipt::id).distinct().size == it.size)
            }
        }.getOrDefault(emptyList())
    }
    override suspend fun clear(owner: String) = withContext(Dispatchers.IO) {
        val file = File(directory, "${receiptHash(owner)}.json")
        check(!file.exists() || file.delete()) { "Не удалось удалить кеш чеков." }
    }
    override suspend fun save(owner: String, receipts: List<FuelReceipt>) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val destination = File(directory, "${receiptHash(owner)}.json")
        val temporary = File(directory, "${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(json.encodeToString(receipts))
            currentCoroutineContext().ensureActive()
            check(temporary.renameTo(destination)) { "Не удалось сохранить список чеков." }
        } finally { temporary.delete() }
    }
}

// A repository never changes account; delayed network results cannot reach another owner's state.
class FuelReceiptsRepository(
    private val owner: String, private val api: FuelReceiptsApi, private val cache: FuelReceiptsCache,
    private val previewDirectory: File,
) {
    init { require(owner.isNotBlank()) }
    private val mutableReceipts = MutableStateFlow<List<FuelReceipt>>(emptyList())
    val receipts = mutableReceipts.asStateFlow()
    private val lock = Mutex()
    private val mutableAuthorizationRequired = MutableStateFlow(false)
    val authorizationRequired = mutableAuthorizationRequired.asStateFlow()
    suspend fun hydrate() = lock.withLock {
        if (!mutableAuthorizationRequired.value) mutableReceipts.value = cache.read(owner)
    }
    suspend fun refresh(token: String) = lock.withLock {
        val fetched = remote { api.fetch(token) }
        currentCoroutineContext().ensureActive()
        cache.save(owner, fetched)
        mutableReceipts.value = fetched
        mutableAuthorizationRequired.value = false
    }
    suspend fun upload(token: String, month: String, receiptId: String, kind: String, input: FuelReceiptInput, offline: Boolean, onProgress: (Double) -> Unit): FuelReceipt = lock.withLock {
        requireOnline(offline)
        mutableReceipts.value.firstOrNull { it.id == receiptId }?.let {
            require(it.month == month && it.kind == kind) { "При замене нельзя изменить месяц или тип чека." }
        }
        val receipt = remote { api.upload(token, month, receiptId, kind, input, onProgress) }.validate()
        require(receipt.id == receiptId && receipt.month == month && receipt.kind == kind && receipt.sizeBytes == input.data.size && receipt.mimeType == input.mimeType) { "Сервер не подтвердил сохранение чека." }
        currentCoroutineContext().ensureActive()
        val updated = (mutableReceipts.value.filterNot { it.id == receiptId } + receipt).sortedByDescending { it.createdAt }
        cache.save(owner, updated)
        mutableReceipts.value = updated
        withContext(Dispatchers.IO) { receiptDirectory(receiptId).deleteRecursively() }
        receipt
    }
    suspend fun delete(token: String, receipt: FuelReceipt, offline: Boolean) = lock.withLock {
        requireOnline(offline)
        receipt.validate()
        remote { api.delete(token, receipt.id) }
        currentCoroutineContext().ensureActive()
        val updated = mutableReceipts.value.filterNot { it.id == receipt.id }
        cache.save(owner, updated)
        mutableReceipts.value = updated
        withContext(Dispatchers.IO) { receiptDirectory(receipt.id).deleteRecursively() }
        Unit
    }
    suspend fun preview(token: String, receipt: FuelReceipt, offline: Boolean): File = lock.withLock {
        withContext(Dispatchers.IO) {
            requireAccess()
            receipt.validate()
            val version = receiptHash("${receipt.updatedAt}|${receipt.sizeBytes}|${receipt.mimeType}")
            val extension = when (receipt.mimeType) { "application/pdf" -> "pdf"; "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/heic" -> "heic"; else -> "heif" }
            val file = File(receiptDirectory(receipt.id), "$version/Чек.$extension")
            if (file.isFile && file.length() == receipt.sizeBytes.toLong()) {
                val valid = try {
                    FuelReceiptInput(file.inputStream().use { readFuelReceiptBytes(it) }, file.name, receipt.mimeType)
                    true
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    false
                }
                if (valid) return@withContext file
                file.delete()
            }
            requireOnline(offline)
            val input = FuelReceiptInput(remote { api.download(token, receipt.id) }, file.name, receipt.mimeType)
            require(input.data.size == receipt.sizeBytes) { "Размер чека не совпадает с данными сервера. Обновите список." }
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
            try {
                temporary.writeBytes(input.data)
                currentCoroutineContext().ensureActive()
                check(temporary.renameTo(file)) { "Не удалось сохранить чек для просмотра." }
            } finally { temporary.delete() }
            file
        }
    }
    private fun receiptDirectory(id: String) = File(previewDirectory, "fuel-receipts/${receiptHash(owner)}/${receiptHash(id)}")
    private suspend fun <T> remote(action: suspend () -> T): T = try { action() }
    catch (failure: FuelReceiptsFailure) {
        if (failure.status == 401 || failure.status == 403) {
            mutableAuthorizationRequired.value = true
            mutableReceipts.value = emptyList()
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { cache.clear(owner) }
                runCatching { File(previewDirectory, "fuel-receipts/${receiptHash(owner)}").deleteRecursively() }
            }
        }
        throw failure
    }
    private fun requireAccess() {
        if (mutableAuthorizationRequired.value) throw FuelReceiptsFailure("Сессия приложения «Инженер» истекла. Войдите заново и обновите список чеков.", 401)
    }
    private fun requireOnline(offline: Boolean) {
        requireAccess()
        if (offline) throw FuelReceiptsFailure("Для изменения чеков и первого просмотра требуется интернет.")
    }
    companion object {
        fun create(context: Context, owner: String): FuelReceiptsRepository {
            val json = Json { ignoreUnknownKeys = true }
            return FuelReceiptsRepository(owner, OkHttpFuelReceiptsApi(BuildConfig.API_ORIGIN, json),
                FileFuelReceiptsCache(File(context.noBackupFilesDir, "fuel-receipts"), json), context.cacheDir)
        }
    }
}
private fun receiptHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
