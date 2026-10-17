package ru.lumastack.lumawork.data.ftp

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import androidx.lifecycle.viewModelScope
import org.junit.Assert.*
import org.junit.Test
import ru.lumastack.lumawork.domain.model.*
import ru.lumastack.lumawork.ui.ftp.FtpViewModel

class FtpRepositoryTest {
    @Test fun `concurrent folder loads merge into the current cache`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        var calls = 0
        val api = object : FtpApi {
            override suspend fun files(token: String, path: String): FtpDirectoryResponse {
                if (++calls == 2) entered.complete(Unit)
                if (path == "/one") first.await() else second.await()
                return FtpDirectoryResponse(path, listOf(FtpItem(path, "$path/file", FtpItemType.File)))
            }
        }
        val cache = MemoryFtpCache()
        val repository = FtpRepository(api, cache)
        val one = async { repository.load("owner", "token", "/one") }
        val two = async { repository.load("owner", "token", "/two") }
        entered.await(); first.complete(Unit); one.await(); second.complete(Unit); two.await()
        assertEquals(setOf("/one", "/two"), repository.read("owner")!!.directories.keys)
        assertNull(repository.read("other"))
    }

    @Test fun `favorites persist by account and root is never a favorite`() = runBlocking {
        val cache = MemoryFtpCache()
        val api = object : FtpApi { override suspend fun files(token: String, path: String) = FtpDirectoryResponse(path) }
        val repository = FtpRepository(api, cache)
        repository.toggleFavorite("owner", "/one//two/")
        repository.toggleFavorite("owner", "/")
        repository.load("owner", "token", "/folder")
        val restored = FtpRepository(api, cache)
        assertEquals(listOf("/one/two"), restored.read("owner")!!.favoritePaths)
        assertEquals(2L, restored.read("owner")!!.revision)
        assertNull(restored.read("other"))
        assertTrue(restored.toggleFavorite("owner", "/one/two").favoritePaths.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `offline folder navigation and refresh never start a network request`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var calls = 0
        val api = object : FtpApi {
            override suspend fun files(token: String, path: String): FtpDirectoryResponse { calls++; return FtpDirectoryResponse(path) }
        }
        val folder = FtpItem("Папка", "/folder", FtpItemType.Directory)
        val cache = MemoryFtpCache().apply { values["owner"] = FtpSnapshot(mapOf("/" to listOf(folder), "/folder" to emptyList())) }
        val model = FtpViewModel(FtpRepository(api, cache), AppSession("token", AppUser("owner", "user@example.test", "engineer")), true)
        try {
            advanceUntilIdle(); model.open(folder); advanceUntilIdle(); model.refresh(); advanceUntilIdle()
            assertEquals(0, calls)
            assertEquals("/folder", model.state.value.path)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
private class MemoryFtpCache : FtpCache {
    val values = mutableMapOf<String, FtpSnapshot>()
    override suspend fun read(ownerUserId: String) = values[ownerUserId]
    override suspend fun save(ownerUserId: String, snapshot: FtpSnapshot) { values[ownerUserId] = snapshot }
}
