package ru.lumastack.lumawork.data.salary

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
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

interface SalaryDocumentsCache {
    suspend fun read(owner: String): List<SalaryDocument>
    suspend fun save(owner: String, documents: List<SalaryDocument>)
}

class FileSalaryDocumentsCache(private val directory: File, private val json: Json) : SalaryDocumentsCache {
    override suspend fun read(owner: String): List<SalaryDocument> = withContext(Dispatchers.IO) {
        val file = File(directory, "${salaryDocumentsHash(owner)}.json")
        if (!file.isFile || file.length() > 2 * 1024 * 1024) return@withContext emptyList()
        runCatching { json.decodeFromString<List<SalaryDocument>>(file.readText()).onEach { it.validate() } }.getOrDefault(emptyList())
    }
    override suspend fun save(owner: String, documents: List<SalaryDocument>) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val destination = File(directory, "${salaryDocumentsHash(owner)}.json")
        val temporary = File(directory, "${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(json.encodeToString(documents))
            currentCoroutineContext().ensureActive()
            check(temporary.renameTo(destination)) { "Не удалось сохранить список расчётных листков." }
        } finally { temporary.delete() }
    }
}

interface SalaryLegacyDocuments {
    suspend fun months(): Set<String>
    suspend fun input(month: String): SalaryDocumentInput?
    suspend fun delete(month: String)
}

private class LocalSalaryLegacyDocuments(private val store: SalarySlipStore) : SalaryLegacyDocuments {
    override suspend fun months() = store.months()
    override suspend fun input(month: String): SalaryDocumentInput? = withContext(Dispatchers.IO) {
        require(SALARY_DOCUMENT_MONTH.matches(month))
        store.file(month)?.let { file ->
            require(file.length() <= SalaryDocumentInput.MAX_BYTES) { "Расчётный листок не должен превышать 20 МБ." }
            SalaryDocumentInput(file.inputStream().use(::readSalaryDocumentBytes), file.name, null)
        }
    }
    override suspend fun delete(month: String) { store.delete(month) }
}

class SalaryDocumentsRepository(
    private val api: SalaryDocumentsApi,
    private val cache: SalaryDocumentsCache,
    private val legacy: SalaryLegacyDocuments? = null,
) {
    private val mutableDocuments = MutableStateFlow<List<SalaryDocument>>(emptyList())
    val documents = mutableDocuments.asStateFlow()
    private val lock = Mutex()
    private var confirmedOwner: String? = null
    // A failed metadata recheck keeps the original file. A subsequent recheck can finish
    // the same migration without overwriting a server document that already exists.
    private val pendingMigration = mutableMapOf<String, SalaryDocument>()

    suspend fun hydrate(owner: String) = lock.withLock { mutableDocuments.value = cache.read(owner) }

    suspend fun refresh(owner: String, token: String) = lock.withLock {
        val fetched = api.fetch(token)
        cache.save(owner, fetched)
        mutableDocuments.value = fetched
        confirmedOwner = owner
    }

    suspend fun migrateLegacy(owner: String, token: String, onProgress: (Double) -> Unit) = lock.withLock {
        check(confirmedOwner == owner) { "Сначала обновите список расчётных листков с сервера." }
        val source = legacy ?: return@withLock
        for (month in source.months().sorted()) {
            currentCoroutineContext().ensureActive()
            val existing = mutableDocuments.value.firstOrNull { it.month == month }
            val pending = pendingMigration[month]
            if (pending != null && existing == pending) {
                source.delete(month)
                pendingMigration.remove(month)
                continue
            }
            // Never replace an existing server document during automatic migration.
            if (existing != null) continue
            val input = source.input(month) ?: continue
            val uploaded = api.upload(token, month, input, onProgress)
            pendingMigration[month] = uploaded
            val refreshed = api.fetch(token)
            cache.save(owner, refreshed)
            mutableDocuments.value = refreshed
            if (refreshed.none { it == uploaded }) throw SalaryDocumentsFailure("Локальный листок сохранён: сервер пока не подтвердил его в списке документов.")
            currentCoroutineContext().ensureActive()
            source.delete(month)
            pendingMigration.remove(month)
        }
    }

    suspend fun legacyInput(month: String): SalaryDocumentInput? = legacy?.input(month)
    suspend fun legacyMonths(): Set<String> = legacy?.months().orEmpty()

    suspend fun upload(owner: String, token: String, month: String, input: SalaryDocumentInput, offline: Boolean, onProgress: (Double) -> Unit): SalaryDocument = lock.withLock {
        requireOnline(offline)
        val document = api.upload(token, month, input, onProgress)
        val updated = (mutableDocuments.value.filterNot { it.month == month } + document).sortedByDescending { it.month }
        cache.save(owner, updated)
        mutableDocuments.value = updated
        document
    }

    suspend fun delete(owner: String, token: String, month: String, offline: Boolean) = lock.withLock {
        requireOnline(offline)
        api.delete(token, month)
        // The explicit month deletion also removes its legacy copy, so a later migration cannot restore it.
        legacy?.delete(month)
        val updated = mutableDocuments.value.filterNot { it.month == month }
        cache.save(owner, updated)
        mutableDocuments.value = updated
    }

    suspend fun documentInput(token: String, document: SalaryDocument, offline: Boolean, cacheDirectory: File): SalaryDocumentInput = withContext(Dispatchers.IO) {
        document.validate()
        val file = File(cacheDirectory, "${salaryDocumentsHash(document.id + document.updatedAt)}.${if (document.mimeType == "application/pdf") "pdf" else "html"}")
        if (file.isFile && file.length() <= SalaryDocumentInput.MAX_BYTES) {
            val cached = runCatching { SalaryDocumentInput(file.inputStream().use(::readSalaryDocumentBytes), document.fileName, document.mimeType) }.getOrNull()
            if (cached != null && cached.data.size == document.sizeBytes) return@withContext cached
            file.delete()
        }
        requireOnline(offline)
        val input = SalaryDocumentInput(api.download(token, document.month), document.fileName, document.mimeType)
        require(input.data.size == document.sizeBytes) { "Размер расчётного листка не совпадает с данными сервера. Обновите список." }
        cacheDirectory.mkdirs()
        val temporary = File(cacheDirectory, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeBytes(input.data)
            currentCoroutineContext().ensureActive()
            check(temporary.renameTo(file)) { "Не удалось сохранить расчётный листок для просмотра." }
        } finally { temporary.delete() }
        input
    }

    private fun requireOnline(offline: Boolean) {
        if (offline) throw SalaryDocumentsFailure("Для работы с серверными расчётными листками требуется подключение к интернету.")
    }

    companion object {
        fun create(context: Context, owner: String): SalaryDocumentsRepository {
            val json = Json { ignoreUnknownKeys = true }
            return SalaryDocumentsRepository(OkHttpSalaryDocumentsApi(BuildConfig.API_ORIGIN, json),
                FileSalaryDocumentsCache(File(context.noBackupFilesDir, "salary-documents"), json),
                LocalSalaryLegacyDocuments(SalarySlipStore(context, owner)))
        }
    }
}

fun salaryDocumentsCacheDirectory(context: Context, owner: String, month: String): File {
    require(SALARY_DOCUMENT_MONTH.matches(month))
    return File(context.cacheDir, "salary-documents/${salaryDocumentsHash(owner)}/$month")
}

internal fun salaryDocumentsHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
