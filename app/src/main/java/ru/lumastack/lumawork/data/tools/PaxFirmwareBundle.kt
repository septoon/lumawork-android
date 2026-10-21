package ru.lumastack.lumawork.data.tools

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class PaxFirmwareAsset(val name: String, val file: File, val sha256: String) {
    val size: Long get() = file.length()
}

data class PaxFirmwareBundle(
    val directory: File,
    val boot: PaxFirmwareAsset,
    val os: PaxFirmwareAsset,
    val application: PaxFirmwareAsset,
    val appData: List<PaxFirmwareAsset>,
    val options: List<PaxFirmwareAsset>,
    val expectedOs: String = "Nuxiums3.1.24",
    val expectedMainApp: String = "3.0.65.014",
    val expectedBoot: String = "8.1.12.274(R)",
) {
    val totalBytes: Long get() = (listOf(boot, os, application) + appData + options).sumOf { it.size }
}

internal data class PaxFirmwareFingerprint(val size: Long, val sha256: String)
internal data class PaxBundleLimits(
    val fileBytes: Long = 128L * 1024 * 1024,
    val totalBytes: Long = 512L * 1024 * 1024,
    val entries: Int = 1024,
)

/** Imports original payloads only. Fingerprints identify the supplied successful capture;
 * they do not verify vendor signatures or establish trust in firmware. */
object PaxFirmwareBundleReader {
    private const val SCRIPT = "script_VSS.list"
    private val names = listOf(
        "boot-8.1.12.274R_20240729_SIG.zip", "Nuxiums-std-3.1.24.12234R-Inpas_SIG.zip",
        "TPTP_BASE_3.0.65.014_RU_NUX_sign.aip", "Config.sys", "agent.mod", "cah.mod", "kdh.mod",
        "RootCA1-ITS_RootCA0.pem", "fonts_sig.tar.gz", "libbt-2.9.12_SIG.tar.gz",
    )
    private val capturedProfile = listOf(
        283242L to "0c1c48d7021c276ea9abf8ded789753714b4c76f428e9a97afe36e5c0c27eaa3",
        20582999L to "9563b73fcb5030d4999bcb3ee102b6946335c2ef2581889e620ecb381016e1bc",
        7844854L to "1918f7da20493b189c1961304ce18e6776b06bd712ad03af857d9a82fac69491",
        23L to "14c4d0f3b299b0c5519e3912c9910d623094f86128ff048d313a356845ffc90f",
        3065L to "891623ef61b0fcea25e6a88078849fe554afe58960361d9e16910adab29be77d",
        1194L to "1986452d3b964b3abb72277cd737232fc4f47e50ff714c8b7ac9653186f5ee7d",
        1421L to "2a4b4d96e4a94879c73caf096d4e5e6a1a9f1e6f3e1213c30524cead0ccb7140",
        3373L to "16634c062c8dde0c82af0e52d4620cc2c9ac650aa77955a11b35f3b8bdf277e1",
        427308L to "d8d76dec55f1f09de112b8eb9912439f84b3c2403a5c2a533b659e86acae3458",
        2290056L to "6838ead3fcafedcd1e72cd8b9a0f272ee66c2928ff41de50c04c63247b42e825",
    ).mapIndexed { index, (size, hash) -> names[index] to PaxFirmwareFingerprint(size, hash) }.toMap()

    fun prepare(input: InputStream, destination: File): PaxFirmwareBundle = prepare(input, destination, capturedProfile)
    fun read(directory: File): PaxFirmwareBundle = read(directory, capturedProfile)

    internal fun prepare(
        input: InputStream,
        destination: File,
        profile: Map<String, PaxFirmwareFingerprint>,
        limits: PaxBundleLimits = PaxBundleLimits(),
    ): PaxFirmwareBundle {
        require(limits.fileBytes > 0 && limits.totalBytes > 0 && limits.entries > 0) { "Некорректные ограничения архива" }
        require(!destination.exists()) { "Каталог импорта уже существует" }
        val parent = requireNotNull(destination.absoluteFile.parentFile)
        require(parent.isDirectory || parent.mkdirs()) { "Не удалось создать каталог импорта" }
        require(!Files.isSymbolicLink(destination.toPath())) { "Ссылка в пути импорта" }
        val archive = File.createTempFile("pax-bundle-", ".zip", parent)
        try {
            archive.outputStream().use { output -> input.use { copyLimited(it, limits.totalBytes) { bytes, count -> output.write(bytes, 0, count) } } }
            validateCentralDirectory(archive, limits.entries)
            ZipFile(archive).use { zip ->
                val entries = checkedEntries(zip, limits)
                val script = selectScript(entries.keys)
                val scriptBytes = readEntry(zip, entries.getValue(script), 16 * 1024L)
                validateScript(scriptBytes)
                val prefix = script.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                val selected = names.mapIndexed { index, name ->
                    val candidates = if (index < 2) entries.keys.filter { it.substringAfterLast('/') == name }
                    else listOf(prefix + name, name).distinct().filter { it in entries }
                    require(candidates.size == 1) { "Отсутствует или неоднозначен файл $name" }
                    name to entries.getValue(candidates.single())
                }
                require(destination.mkdir()) { "Не удалось создать каталог импорта" }
                File(destination, SCRIPT).writeBytes(scriptBytes)
                for ((name, entry) in selected) {
                    File(destination, name).outputStream().use { output ->
                        checkedCopy(zip, entry, limits.fileBytes) { bytes, count -> output.write(bytes, 0, count) }
                    }
                }
            }
            return read(destination, profile)
        } catch (failure: Exception) {
            destination.deleteRecursively()
            throw failure
        } finally {
            archive.delete()
        }
    }

    internal fun read(directory: File, profile: Map<String, PaxFirmwareFingerprint>): PaxFirmwareBundle {
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "Каталог прошивки недоступен" }
        require(profile.keys == names.toSet()) { "Неполный профиль прошивки" }
        val entries = linkedMapOf<String, File>()
        directory.walkTopDown().maxDepth(17).forEach { file ->
            require(!Files.isSymbolicLink(file.toPath())) { "Ссылки в комплекте запрещены" }
            require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator) || file == directory) { "Файл вне комплекта" }
            if (file.isFile) entries[file.relativeTo(directory).invariantSeparatorsPath] = file
            require(entries.size <= 1024) { "Слишком много файлов" }
        }
        val script = selectScript(entries.keys)
        val scriptFile = entries.getValue(script)
        require(scriptFile.length() <= 16 * 1024) { "Слишком большой список установки" }
        validateScript(scriptFile.readBytes())
        val prefix = script.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val assets = names.mapIndexed { index, name ->
            val candidates = if (index < 2) entries.filterKeys { it.substringAfterLast('/') == name }.values.toList()
            else listOf(prefix + name, name).distinct().mapNotNull { entries[it] }
            require(candidates.size == 1) { "Отсутствует или неоднозначен файл $name" }
            val file = candidates.single()
            val expected = profile.getValue(name)
            require(file.length() > 0 && file.length() == expected.size) { "Размер $name не соответствует комплекту" }
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { copyLimited(it, expected.size) { bytes, count -> hash.update(bytes, 0, count) } }
            val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(digest == expected.sha256) { "Файл $name изменён или относится к другому комплекту" }
            if (index < 2) validatePayloadZip(file)
            PaxFirmwareAsset(name, file, digest)
        }
        return PaxFirmwareBundle(directory, assets[0], assets[1], assets[2], assets.subList(3, 8), assets.subList(8, 10))
    }

    private fun selectScript(paths: Set<String>): String {
        val candidates = paths.filter { path ->
            path == SCRIPT || (path.substringAfterLast('/') == SCRIPT && path.split('/').dropLast(1).contains("Pax_Prolin_Nuxiums"))
        }
        require(candidates.size == 1) { "Нужен один список установки Pax_Prolin_Nuxiums" }
        return candidates.single()
    }

    private fun validateScript(bytes: ByteArray) {
        val expected = linkedMapOf(
            "app-remove" to listOf("app1" to "MAINAPP"),
            "app0" to listOf("package" to names[2]),
            "app1" to listOf("id" to "MAINAPP") + names.subList(3, 8).mapIndexed { index, name -> "appdata${index + 1}" to name },
            "ipkg-install" to names.subList(8, 10).mapIndexed { index, name -> "file${index + 1}" to name },
        )
        val parsed = linkedMapOf<String, MutableList<Pair<String, String>>>()
        var current: MutableList<Pair<String, String>>? = null
        for (line in bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
            if (line.startsWith('[') && line.endsWith(']')) {
                val section = line.substring(1, line.length - 1)
                require(section in expected && section !in parsed) { "Неподдерживаемый раздел списка установки" }
                current = mutableListOf<Pair<String, String>>().also { parsed[section] = it }
            } else {
                val pair = line.split('=', limit = 2)
                require(pair.size == 2 && current != null) { "Некорректная команда списка установки" }
                current.add(pair[0].trim() to pair[1].trim())
            }
        }
        require(parsed.keys.toList() == expected.keys.toList() && parsed == expected) { "Список установки не соответствует полному комплекту D270" }
    }

    private fun checkedEntries(zip: ZipFile, limits: PaxBundleLimits): Map<String, ZipEntry> {
        val result = linkedMapOf<String, ZipEntry>()
        val seen = mutableSetOf<String>()
        var total = 0L
        var count = 0
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            require(++count <= limits.entries) { "Слишком много записей ZIP" }
            val path = entry.name.removeSuffix("/")
            require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && ':' !in path && '\u0000' !in path) { "Недопустимый путь ZIP" }
            val components = path.split('/')
            require(components.size <= 16 && components.none { it.isEmpty() || it == "." || it == ".." }) { "Недопустимый путь ZIP" }
            require(seen.add(path.lowercase(Locale.ROOT))) { "Повторяющийся путь ZIP" }
            require(entry.size >= 0 && entry.size <= limits.fileBytes) { "Слишком большой файл ZIP" }
            require(entry.size <= limits.totalBytes - total) { "Слишком большой комплект ZIP" }
            total += entry.size
            if (!entry.isDirectory) result[path] = entry
        }
        // A regular file must never double as the parent of another entry.
        for (path in seen) {
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(result.keys.none { it.lowercase(Locale.ROOT) == parent }) { "Коллизия файла и каталога ZIP" }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        require(result.isNotEmpty()) { "Пустой ZIP" }
        return result
    }

    private fun readEntry(zip: ZipFile, entry: ZipEntry, limit: Long): ByteArray = java.io.ByteArrayOutputStream().also { output ->
        checkedCopy(zip, entry, limit) { bytes, count -> output.write(bytes, 0, count) }
    }.toByteArray()

    private fun checkedCopy(zip: ZipFile, entry: ZipEntry, limit: Long, consume: (ByteArray, Int) -> Unit) {
        val crc = CRC32()
        val count = zip.getInputStream(entry).use { input -> copyLimited(input, limit) { bytes, size -> crc.update(bytes, 0, size); consume(bytes, size) } }
        require(count == entry.size && crc.value == entry.crc) { "Повреждённый файл ZIP" }
    }

    private fun copyLimited(input: InputStream, limit: Long, consume: (ByteArray, Int) -> Unit): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            require(count.toLong() <= limit - total) { "Превышен размер файла" }
            consume(buffer, count)
            total += count
        }
        return total
    }

    private fun validatePayloadZip(file: File) {
        val limits = PaxBundleLimits()
        validateCentralDirectory(file, limits.entries)
        ZipFile(file).use { zip ->
            checkedEntries(zip, limits).values.forEach { checkedCopy(zip, it, limits.fileBytes) { _, _ -> } }
        }
    }

    /** java.util.zip does not expose UNIX modes; inspect central headers to reject symlinks.
     * ZIP64, encrypted and multi-disk archives are deliberately unsupported. */
    private fun validateCentralDirectory(file: File, entryLimit: Int) {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 22) { "Некорректный ZIP" }
            val tailSize = minOf(input.length(), 65557L).toInt()
            val tail = ByteArray(tailSize)
            input.seek(input.length() - tailSize); input.readFully(tail)
            fun u16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
            fun u32(bytes: ByteArray, at: Int): Long = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)
            val end = (tail.size - 22 downTo 0).firstOrNull { u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == tail.size }
                ?: error("ZIP не завершён")
            require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0) { "Многотомный ZIP запрещён" }
            val count = u16(tail, end + 10)
            require(count in 1..entryLimit && count == u16(tail, end + 8) && count != 65535) { "Некорректное число файлов ZIP" }
            val start = u32(tail, end + 16)
            val length = u32(tail, end + 12)
            require(start + length == input.length() - tailSize + end) { "Некорректный каталог ZIP" }
            input.seek(start)
            repeat(count) {
                val header = ByteArray(46); input.readFully(header)
                require(u32(header, 0) == 0x02014b50L && u16(header, 8) and 1 == 0) { "Неподдерживаемый ZIP" }
                val mode = (u32(header, 38) ushr 16).toInt()
                require(mode and 0xf000 != 0xa000) { "Ссылки ZIP запрещены" }
                require(u32(header, 20) != 0xffffffffL && u32(header, 24) != 0xffffffffL && u32(header, 42) != 0xffffffffL) { "ZIP64 запрещён" }
                input.seek(input.filePointer + u16(header, 28) + u16(header, 30) + u16(header, 32))
            }
            require(input.filePointer == start + length) { "Некорректный каталог ZIP" }
        }
    }
}
