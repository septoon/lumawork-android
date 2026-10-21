package ru.lumastack.lumawork.data.backpack

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import ru.lumastack.lumawork.domain.backpack.BackpackPhotoCatalog
import ru.lumastack.lumawork.domain.backpack.BackpackPhoto

class BackpackPhotoRepository(
    private val origin: String,
    private val cache: BackpackPhotoCatalogCache,
    private val equipmentCache: BackpackPhotoCatalogCache,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun cached(): BackpackPhotoCatalog = cache.read() ?: FALLBACK_CATALOG

    suspend fun cachedEquipment(): BackpackPhotoCatalog = equipmentCache.read() ?: BackpackPhotoCatalog()

    suspend fun refresh(): BackpackPhotoCatalog? = refresh("backpack-terminals", cache)

    suspend fun refreshEquipment(): BackpackPhotoCatalog? = refresh("equipment", equipmentCache)

    private suspend fun refresh(endpoint: String, targetCache: BackpackPhotoCatalogCache): BackpackPhotoCatalog? {
        val request = Request.Builder()
            .url("${origin.trimEnd('/')}/api/v2/media/$endpoint")
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            val response = client.newCall(request).awaitPhotoCatalog()
            response.use {
                if (!it.isSuccessful) return null
                val catalog = json.decodeFromString<BackpackPhotoCatalog>(it.body.string())
                if (catalog.items.isEmpty()) return null
                withContext(Dispatchers.IO) { targetCache.save(catalog) }
                catalog
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val FALLBACK_CATALOG = BackpackPhotoCatalog(
            items = listOf(
                photo("Стационарный Unitodi MF960 AL", "unitodi-mf960-stationary.webp"),
                photo(
                    "Переносной Unitodi MF 960",
                    "unitodi-mf960-portable.webp",
                    listOf("Переносной POS-терминал MoreFun UniTodi 960 AL"),
                ),
                photo("Внешняя PIN-клавиатура MoreFun Vanstone (Aisino) UniTodi V10", "unitodi-v10.webp"),
                photo("AISINO V73", "aisino-v73.webp"),
                photo("Feitian F20", "feitian-f20.webp"),
                photo("PAX AF6", "pax-af6.webp"),
                photo("Pax D190", "pax-d190.webp"),
                photo("Pax D200", "pax-d200.webp"),
                photo("Pax D230", "pax-d230.webp"),
                photo("Pax D270", "pax-d270.webp"),
                photo("Pax Q25", "pax-q25.webp"),
                photo("Pax S200", "pax-s200.webp"),
                photo("PAX S210", "pax-s210.webp"),
                photo("Pax S300", "pax-s300.webp"),
                photo("Pax S920", "pax-s920.webp"),
                photo("UniTodi P8", "unitodi-p8.webp", listOf("Интеллектуальный PIN-PAD Telepower UniTodi P8Bio")),
            ),
        )

        private fun photo(name: String, fileName: String, aliases: List<String> = emptyList()) = BackpackPhoto(
            referenceName = name,
            aliases = aliases,
            fileName = fileName,
            url = "https://api.work.lumastack.ru/uploads/vehicle-images/backpack-terminals/$fileName?v=20260716",
        )
    }
}

interface BackpackPhotoCatalogCache {
    suspend fun read(): BackpackPhotoCatalog?
    suspend fun save(catalog: BackpackPhotoCatalog)
}

class FileBackpackPhotoCatalogCache(
    context: android.content.Context,
    private val json: Json,
    fileName: String = "photo-catalog.json",
) : BackpackPhotoCatalogCache {
    private val file = java.io.File(context.noBackupFilesDir, "backpack/$fileName")

    override suspend fun read(): BackpackPhotoCatalog? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString<BackpackPhotoCatalog>(file.readText()) }.getOrNull()
    }

    override suspend fun save(catalog: BackpackPhotoCatalog) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temporary = java.io.File(file.parentFile, "${file.name}.tmp")
        val encoded = json.encodeToString(BackpackPhotoCatalog.serializer(), catalog)
        temporary.writeText(encoded)
        if (!temporary.renameTo(file)) {
            file.writeText(encoded)
            temporary.delete()
        }
    }
}

private suspend fun Call.awaitPhotoCatalog(): Response = suspendCancellableCoroutine { continuation ->
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
