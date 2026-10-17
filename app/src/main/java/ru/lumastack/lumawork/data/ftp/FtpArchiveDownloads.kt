package ru.lumastack.lumawork.data.ftp

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Folder ZIPs are generated before response headers arrive, so DownloadManager's short read timeout cannot be used. */
internal class FtpArchiveDownloads(
    private val root: () -> File,
    private val client: OkHttpClient = archiveHttpClient(),
) {
    private val calls = mutableMapOf<String, Call>()
    private val cancelled = mutableSetOf<String>()
    private val live = mutableMapOf<String, FtpTransferProgress>()
    @Synchronized fun prepare(record: FtpTransfer) {
        val file = managedFtpFile(root(), record)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        write(record, FtpTransferProgress(FtpTransferState.Preparing))
    }
    @Synchronized fun progress(record: FtpTransfer): FtpTransferProgress {
        live[record.id]?.let { return it }
        val status = statusFile(record)
        val result = if (status.isFile) Json.decodeFromString<FtpTransferProgress>(status.readText()) else FtpTransferProgress(FtpTransferState.Preparing)
        return if (result.state == FtpTransferState.Completed && !managedFtpFile(root(), record).isFile)
            result.copy(state = FtpTransferState.Failed, error = "Скачанный файл больше не найден на устройстве.") else result
    }
    @Synchronized fun remove(record: FtpTransfer) {
        if (record.state.isActive) cancelled.add(record.id)
        calls.remove(record.id)?.cancel()
        live.remove(record.id)
        listOf(managedFtpFile(root(), record), partFile(record), statusFile(record)).forEach {
            if (it.exists()) check(it.delete()) { "Не удалось удалить файл с устройства." }
        }
    }
    @Synchronized fun fail(record: FtpTransfer, message: String) = write(record, FtpTransferProgress(FtpTransferState.Failed, error = message))
    @Synchronized fun interrupt() {
        calls.values.forEach(Call::cancel)
        calls.clear(); live.clear() // Persisted Preparing state allows restart after a system stop.
    }
    suspend fun download(record: FtpTransfer, token: String, url: HttpUrl, onProgress: (FtpTransferProgress) -> Unit) = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").header("Cache-Control", "no-cache").build())
        synchronized(this@FtpArchiveDownloads) {
            if (record.id in cancelled || calls.containsKey(record.id) || progress(record).state.let { !it.isActive }) return@withContext
            calls[record.id] = call
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw FtpFailure.Http(response.code)
                val body = response.body ?: throw IOException("Пустой ответ FTP.")
                val total = body.contentLength().takeIf { it >= 0 }
                var bytes = 0L
                var lastUpdate = 0L
                partFile(record).outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            if (call.isCanceled()) throw IOException("Загрузка прервана.")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count); bytes += count
                            val now = System.nanoTime()
                            if (now - lastUpdate >= TimeUnit.SECONDS.toNanos(1)) {
                                val value = FtpTransferProgress(FtpTransferState.Downloading, bytes, total)
                                synchronized(this@FtpArchiveDownloads) { if (calls[record.id] === call) live[record.id] = value }
                                onProgress(value); lastUpdate = now
                            }
                        }
                    }
                }
                if (total != null && bytes != total) throw IOException("Архив скачан не полностью.")
                synchronized(this@FtpArchiveDownloads) {
                    if (calls[record.id] !== call || call.isCanceled()) return@withContext
                    check(partFile(record).renameTo(managedFtpFile(root(), record))) { "Не удалось сохранить архив." }
                    write(record, FtpTransferProgress(FtpTransferState.Completed, bytes, total))
                }
            }
        } catch (error: Exception) {
            synchronized(this@FtpArchiveDownloads) {
                if (calls[record.id] === call && !call.isCanceled()) {
                    partFile(record).delete()
                    write(record, FtpTransferProgress(FtpTransferState.Failed, error = when (error) {
                        is FtpFailure -> error.message
                        else -> "Не удалось скачать архив. Проверьте подключение и свободное место."
                    }))
                }
            }
        } finally { synchronized(this@FtpArchiveDownloads) { if (calls[record.id] === call) calls.remove(record.id) } }
    }
    private fun statusFile(record: FtpTransfer) = File(managedFtpFile(root(), record).parentFile, ".archive-state")
    private fun partFile(record: FtpTransfer) = File(managedFtpFile(root(), record).parentFile, ".archive-part")
    private fun write(record: FtpTransfer, progress: FtpTransferProgress) {
        val status = statusFile(record)
        val temporary = File.createTempFile("archive-", ".tmp", status.parentFile)
        try { temporary.writeText(Json.encodeToString(progress)); check(temporary.renameTo(status)) }
        finally { temporary.delete() }
        live[record.id] = progress
    }
}
internal fun archiveHttpClient() = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(7, TimeUnit.DAYS).followRedirects(false).followSslRedirects(false).build()
