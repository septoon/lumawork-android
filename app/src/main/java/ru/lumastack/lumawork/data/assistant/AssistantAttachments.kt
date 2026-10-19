package ru.lumastack.lumawork.data.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.ui.profile.readAvatarJpeg

internal class AssistantAttachments(private val context: Context) {
    suspend fun prepare(uri: Uri, imageOnly: Boolean = false): AssistantAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.txt"
        val mime = resolver.getType(uri).orEmpty()
        if (imageOnly || mime.startsWith("image/") || name.substringAfterLast('.').lowercase() in listOf("jpg", "jpeg", "png", "webp", "heic"))
            return@withContext AssistantAttachment(name, image = AssistantImage("image/jpeg", Base64.getEncoder().encodeToString(readAvatarJpeg(context, uri))))
        val bytes = requireNotNull(resolver.openInputStream(uri)) { "Файл недоступен." }.use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break
                require(output.size() + n <= AssistantDocuments.MAX_BYTES) { "Документ превышает 12 МБ." }; output.write(buffer, 0, n) }
            output.toByteArray()
        }
        if (mime == "application/pdf" || name.endsWith(".pdf", true)) pdf(bytes, name) else AssistantDocuments.prepare(bytes, name)
    }
    private fun pdf(bytes: ByteArray, name: String): AssistantAttachment {
        val file = File.createTempFile("assistant-pdf-", ".pdf", context.cacheDir)
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { pdf ->
                require(pdf.pageCount > 0) { "В PDF нет страниц." }
                val text = StringBuilder()
                if (Build.VERSION.SDK_INT >= 35) for (index in 0 until minOf(pdf.pageCount, 30)) {
                    pdf.openPage(index).use { page ->
                        val content = page.textContents.joinToString("\n") { it.text }
                        if (content.isNotBlank()) text.append("[Страница ${index + 1}]\n$content\n\n")
                    }
                    if (text.length >= 12_000) break
                }
                if (text.isNotBlank()) return AssistantAttachment(name, document = AssistantDocument(name.take(140), "application/pdf", text.toString().take(12_000),
                    "ios_pdf_text", bytes.size, wasTruncated = text.length > 12_000 || pdf.pageCount > 30, pageCount = pdf.pageCount))
                pdf.openPage(0).use { page ->
                    val scale = 1600f / maxOf(page.width, page.height)
                    val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val output = ByteArrayOutputStream(); check(bitmap.compress(Bitmap.CompressFormat.JPEG, 86, output))
                        return AssistantAttachment(name, image = AssistantImage("image/jpeg", Base64.getEncoder().encodeToString(output.toByteArray())),
                            note = "PDF передаётся как изображение первой страницы. Остальные страницы не анализируются.")
                    } finally { bitmap.recycle() }
                }
            } }
        } finally { file.delete() }
    }
}
