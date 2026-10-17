package ru.lumastack.lumawork.data.ftp

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FtpArchiveDownloadsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val record = FtpTransfer(UUID.randomUUID().toString(), "owner", "/drivers", "drivers.zip", true, FtpTransferAction.Download, 1)
    private val url = "https://example.test/api/v2/ftp/download-folder?path=%2Fdrivers".toHttpUrl()
    @Test fun `archive client allows server preparation longer than native download timeout`() {
        assertEquals(TimeUnit.DAYS.toMillis(7), archiveHttpClient().readTimeoutMillis.toLong())
        assertFalse(archiveHttpClient().followRedirects)
    }
    @Test fun `complete archive is persisted and available after downloader recreation`() = runBlocking {
        val root = temporary.newFolder()
        val client = archiveHttpClient().newBuilder().addInterceptor { chain ->
            assertEquals("Bearer token", chain.request().header("Authorization"))
            assertEquals(url, chain.request().url)
            response(chain, "archive content".toResponseBody("application/zip".toMediaType()))
        }.build()
        val downloads = FtpArchiveDownloads({ root }, client)
        downloads.prepare(record)
        downloads.download(record, "token", url) { }
        assertEquals("archive content", managedFtpFile(root, record).readText())
        val restored = FtpArchiveDownloads({ root })
        assertEquals(FtpTransferState.Completed, restored.progress(record).state)
        assertEquals(15L, restored.progress(record).bytes)
    }
    @Test fun `interrupted archive cannot become a completed local file`() = runBlocking {
        val root = temporary.newFolder()
        val client = archiveHttpClient().newBuilder().addInterceptor { chain ->
            response(chain, object : ResponseBody() {
                override fun contentType() = "application/zip".toMediaType()
                override fun contentLength() = 100L
                override fun source(): BufferedSource = object : Source {
                    var sent = false
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (sent) throw IOException("Connection lost")
                        sent = true; sink.writeUtf8("partial"); return 7
                    }
                    override fun timeout() = Timeout.NONE
                    override fun close() = Unit
                }.buffer()
            })
        }.build()
        val downloads = FtpArchiveDownloads({ root }, client)
        downloads.prepare(record); downloads.download(record, "token", url) { }
        assertEquals(FtpTransferState.Failed, FtpArchiveDownloads({ root }).progress(record).state)
        assertFalse(managedFtpFile(root, record).exists())
        assertFalse(managedFtpFile(root, record).parentFile!!.resolve(".archive-part").exists())
    }
    @Test fun `cancelled archive is not restarted by stale service work`() = runBlocking {
        val root = temporary.newFolder(); var requested = false
        val client = archiveHttpClient().newBuilder().addInterceptor { chain -> requested = true; response(chain, "data".toResponseBody()) }.build()
        val downloads = FtpArchiveDownloads({ root }, client)
        downloads.prepare(record); downloads.remove(record)
        downloads.download(record, "token", url) { }
        assertFalse(requested)
        assertFalse(managedFtpFile(root, record).exists())
    }
    private fun response(chain: Interceptor.Chain, body: ResponseBody) = Response.Builder().request(chain.request())
        .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
}
