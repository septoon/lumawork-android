package ru.lumastack.lumawork.data.backpack

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.lumastack.lumawork.domain.backpack.BackpackPhoto
import ru.lumastack.lumawork.domain.backpack.BackpackPhotoCatalog
import ru.lumastack.lumawork.domain.backpack.photoFor

class BackpackPhotoRepositoryTest {
    @Test fun `office equipment uses its own endpoint and cache without replacing terminal photos`() = runBlocking {
        val terminals = PhotoCache(BackpackPhotoCatalog(items = listOf(BackpackPhoto(referenceName = "Pax D200"))))
        val equipment = PhotoCache()
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            paths += chain.request().url.encodedPath
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"items":[{"referenceName":"Ноутбук HUAWEI MateBook B3-520","aliases":["BDZ-WDH9A"],"url":"https://images.test/huawei.webp"}]}""".toResponseBody()).build()
        }.build()
        val repository = BackpackPhotoRepository("https://api.test", terminals, equipment, client)
        assertEquals(emptyList<BackpackPhoto>(), repository.cachedEquipment().items)
        val catalog = requireNotNull(repository.refreshEquipment())
        assertEquals(listOf("/api/v2/media/equipment"), paths)
        assertEquals("https://images.test/huawei.webp", catalog.photoFor("Ноутбук HUAWEI MateBook B3-520")?.resolvedUrl)
        assertEquals("https://images.test/huawei.webp", catalog.photoFor("BDZ-WDH9A")?.resolvedUrl)
        assertEquals(catalog, repository.cachedEquipment())
        assertEquals("Pax D200", repository.cached().items.single().referenceName)
    }

    @Test fun `equipment catalogue survives a failed refresh and repository recreation`() = runBlocking {
        val saved = BackpackPhotoCatalog(items = listOf(BackpackPhoto(referenceName = "Huawei", url = "https://images.test/huawei.webp")))
        val equipment = PhotoCache(saved)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Unavailable")
                .body("".toResponseBody()).build()
        }.build()
        val repository = BackpackPhotoRepository("https://api.test", PhotoCache(), equipment, client)
        assertNull(repository.refreshEquipment())
        assertEquals(saved, BackpackPhotoRepository("https://api.test", PhotoCache(), equipment, client).cachedEquipment())
    }
}

private class PhotoCache(var value: BackpackPhotoCatalog? = null) : BackpackPhotoCatalogCache {
    override suspend fun read() = value
    override suspend fun save(catalog: BackpackPhotoCatalog) { value = catalog }
}
