package ru.lumastack.lumawork.data.tools

import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry

/** RAR/7z is normalized into a private temporary ZIP, then passes the same model/package validation.
 * No archive paths are ever extracted to the filesystem. Only the detected RAR or 7z reader is enabled. */
internal object PaxCompressedArchive {
    private const val MAX_FILE = 256L * 1024 * 1024
    private const val MAX_TOTAL = 1024L * 1024 * 1024
    private const val MAX_ENTRIES = 10000

    fun isCompressed(file: File): Boolean = file.inputStream().use { input ->
        val header = ByteArray(8)
        val count = input.read(header)
        hasSignature(header.copyOf(count.coerceAtLeast(0)))
    }

    internal fun hasSignature(header: ByteArray): Boolean {
        if (is7z(header)) return true
        val prefix = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)
        return header.size >= 7 && header.take(6).toByteArray().contentEquals(prefix) &&
            (header[6] == 0.toByte() || (header.size >= 8 && header[6] == 1.toByte() && header[7] == 0.toByte()))
    }

    private fun is7z(header: ByteArray) = header.size >= 6 &&
        header.take(6).toByteArray().contentEquals(byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c))

    internal fun checkedPath(raw: String, directory: Boolean): String {
        val path = raw.replace('\\', '/').let { if (directory) it.removeSuffix("/") else it }
        require(path.isNotEmpty() && path.length <= 1024 && !path.startsWith('/') &&
            path.none { it == ':' || it.code < 32 || it == '\u007f' } &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Недопустимый путь в архиве" }
        return path
    }

    fun convert(source: File, destination: File) = convert(source, destination, null)

    fun convertZip(source: File, destination: File) = convert(source, destination, null, zipArchive = true)

    // OS RemoteUpdate uses the format passphrase distributed inside POSLoader itself.
    // It is not an FTP/account credential. Other password-protected archives remain unsupported.
    fun convertRemoteOs(source: File, destination: File) = convert(source, destination, remoteOsPassphrase, zipArchive = true)

    private val remoteOsPassphrase = byteArrayOf(72, 101, 108, 108, 48, 72, 97, 99, 107, 101, 114)

    private fun convert(source: File, destination: File, passphrase: ByteArray?, zipArchive: Boolean = false) {
        require(zipArchive || isCompressed(source)) { "Нужен ZIP, RAR4/RAR5 или 7z" }
        val sevenZip = source.inputStream().use { input ->
            val header = ByteArray(8); input.read(header); is7z(header)
        }
        val format = if (zipArchive) { if (passphrase != null) "OS RemoteUpdate ZIP" else "ZIP64" } else if (sevenZip) "7z" else "RAR"
        if (!zipArchive && !sevenZip) requireSingleVolume(source)
        val archive = Archive.readNew()
        try {
            // Android native locale is C; 7z stores UTF-16LE paths, including Cyrillic folders.
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray(Charsets.UTF_8))
            Archive.readSupportFilterNone(archive)
            if (zipArchive) {
                Archive.readSupportFormatZip(archive)
                if (passphrase != null) Archive.readAddPassphrase(archive, passphrase)
            } else if (sevenZip) Archive.readSupportFormat7zip(archive) else {
                Archive.readSupportFormatRar(archive)
                Archive.readSupportFormatRar5(archive)
            }
            Archive.readOpenFileName(archive, source.absolutePath.toByteArray(Charsets.UTF_8), 32768)
            val names = mutableSetOf<String>()
            var entries = 0
            var total = 0L
            ZipOutputStream(LimitedOutput(destination.outputStream(), MAX_FILE).buffered()).use { zip ->
                val bytes = ByteArray(32768)
                val buffer = ByteBuffer.wrap(bytes)
                while (true) {
                    val entry = Archive.readNextHeader(archive)
                    if (entry == 0L) break
                    require(++entries <= MAX_ENTRIES) { "Слишком много файлов в $format" }
                    require(passphrase != null || !ArchiveEntry.isEncrypted(entry)) { "${format} с паролем не поддерживается" }
                    val type = ArchiveEntry.filetype(entry)
                    require(type == ArchiveEntry.AE_IFREG || type == ArchiveEntry.AE_IFDIR) { "Ссылки и специальные файлы $format запрещены" }
                    require(ArchiveEntry.symlink(entry) == null && ArchiveEntry.hardlink(entry) == null) { "Ссылки в $format запрещены" }
                    // RAR5 retains UTF-8 names even when conversion to Android's C locale
                    // makes pathname() null. 7z can instead expose only the configured raw UTF-8.
                    val name = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.let { raw ->
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
                    } ?: error("Не удалось прочитать имя файла $format")
                    val path = checkedPath(name, type == ArchiveEntry.AE_IFDIR)
                    require(names.add(path.lowercase(Locale.ROOT))) { "Повторяющийся путь в $format" }
                    val size = ArchiveEntry.size(entry)
                    require(size in 0..MAX_FILE && size <= MAX_TOTAL - total) { "Превышен размер $format" }
                    if (type == ArchiveEntry.AE_IFDIR) {
                        require(size == 0L) { "Некорректная папка $format" }
                        zip.putNextEntry(ZipEntry("$path/")); zip.closeEntry()
                        continue
                    }
                    zip.putNextEntry(ZipEntry(path))
                    var copied = 0L
                    while (true) {
                        buffer.clear()
                        Archive.readData(archive, buffer)
                        val count = buffer.position()
                        if (count == 0) break
                        require(count <= size - copied && count <= MAX_TOTAL - total) { "Превышен размер файла $format" }
                        zip.write(bytes, 0, count)
                        copied += count; total += count
                    }
                    require(copied == size) { "${format} повреждён или не хватает тома" }
                    zip.closeEntry()
                }
                require(entries > 0 && (passphrase != null || Archive.readHasEncryptedEntries(archive) <= 0)) { "Пустой $format или архив с паролем" }
            }
        } catch (failure: Exception) {
            destination.delete()
            throw IllegalArgumentException("Не удалось прочитать $format: ${failure.message ?: "повреждённый или неподдерживаемый архив"}", failure)
        } finally {
            Archive.readFree(archive)
        }
    }

    private fun requireSingleVolume(source: File) = RandomAccessFile(source, "r").use { input ->
        input.seek(6)
        if (input.readUnsignedByte() == 0) {
            input.seek(9)
            require(input.readUnsignedByte() == 0x73) { "Некорректный заголовок RAR4" }
            require(input.readUnsignedByte() and 1 == 0) { "Многотомный RAR не поддерживается" }
        } else {
            input.seek(12) // RAR5 signature and header CRC32.
            fun vint(): Long {
                var value = 0L
                for (index in 0..8) {
                    val byte = input.readUnsignedByte()
                    value = value or ((byte and 127).toLong() shl (index * 7))
                    if (byte and 128 == 0) return value
                }
                error("Некорректный заголовок RAR5")
            }
            val size = vint()
            require(size in 3..65536) { "Некорректный заголовок RAR5" }
            require(vint() == 1L) { "RAR с зашифрованными именами не поддерживается" }
            val flags = vint()
            if (flags and 1L != 0L) vint() // Extra area size.
            if (flags and 2L != 0L) vint() // Data area size.
            require(vint() and 1L == 0L) { "Многотомный RAR не поддерживается" }
        }
    }

    private class LimitedOutput(output: OutputStream, private val limit: Long) : FilterOutputStream(output) {
        private var written = 0L
        override fun write(value: Int) { require(written < limit) { "Распакованный комплект слишком большой" }; out.write(value); written++ }
        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            require(count <= limit - written) { "Распакованный комплект слишком большой" }
            out.write(bytes, offset, count); written += count
        }
    }
}
