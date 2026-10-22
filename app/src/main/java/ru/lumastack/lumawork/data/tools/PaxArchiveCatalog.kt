package ru.lumastack.lumawork.data.tools

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.serialization.json.*

/** A local integrity manifest preserves the selected model and exact imported bytes.
 * Neither this manifest nor metadata inside a package establishes vendor trust. */
object PaxArchiveCatalog {
    private const val MANIFEST = ".pax-import.json"
    private const val MAX_ARCHIVE = 256L * 1024 * 1024
    private const val MAX_FILE = 256L * 1024 * 1024
    private const val MAX_TOTAL = 1024L * 1024 * 1024
    private const val MAX_ENTRIES = 10000
    private const val MAX_METADATA = 64L * 1024
    private val json = Json
    private data class Selection(
        val application: String,
        val boot: String? = null,
        val os: String? = null,
        val appData: List<String> = emptyList(),
        val options: List<String> = emptyList(),
        val script: String? = null,
        val osVersion: String? = null,
        val bootVersion: String? = null,
    )
    private data class Stage(val path: String, val product: String, val version: String?)

    fun prepare(input: InputStream, destination: File, model: PaxModel): PaxFirmwarePackage {
        require(!destination.exists() && !Files.isSymbolicLink(destination.toPath())) { "Каталог импорта уже существует" }
        val parent = requireNotNull(destination.absoluteFile.parentFile)
        require(parent.isDirectory || parent.mkdirs()) { "Не удалось создать каталог импорта" }
        val archive = File.createTempFile("pax-catalog-", ".zip", parent)
        val temporaryArchives = mutableListOf<File>()
        var created = false
        try {
            archive.outputStream().use { out -> input.use { copyLimited(it, MAX_ARCHIVE) { bytes, count -> out.write(bytes, 0, count) } } }
            val source = unwrap(archive, parent, temporaryArchives)
            validateCentralDirectory(source)
            openZip(source).use { zip ->
                val entries = checkedEntries(zip)
                val roots = modelRoots(entries.keys, model)
                require(roots.size == 1) { "Папка ${model.archiveFolder} отсутствует или неоднозначна" }
                val root = roots.single()
                val scoped = entries.filterKeys { it.startsWith("$root/") }.mapKeys { it.key.removePrefix("$root/") }
                val selection = if (model == PaxModel.S210) selectS210(scoped.keys) else selectProlin(zip, scoped, model)
                require(destination.mkdir()) { "Не удалось создать каталог импорта" }
                created = true
                val paths = (listOfNotNull(selection.boot, selection.os, selection.application, selection.script) + selection.appData + selection.options)
                require(paths.distinct().size == paths.size) { "Файл используется несколькими этапами" }
                val records = buildJsonObject {
                    paths.forEach { path ->
                        safePath(path)
                        val entry = requireNotNull(scoped[path]) { "Отсутствует файл $path" }
                        val file = File(destination, path)
                        require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Не удалось создать папку файла" }
                        file.outputStream().use { out -> checkedCopy(zip, entry, MAX_FILE) { bytes, count -> out.write(bytes, 0, count) } }
                        put(path, buildJsonObject { put("size", file.length()); put("sha256", hash(file)) })
                    }
                }
                // appinfo is authoritative for MAINAPP; filenames are deliberately ignored.
                val mainVersion = if (model == PaxModel.S210) null else applicationVersion(File(destination, selection.application))
                val manifest = buildJsonObject {
                    put("format", 1); put("model", model.name)
                    put("application", selection.application)
                    selection.boot?.let { put("boot", it) }; selection.os?.let { put("os", it) }
                    selection.script?.let { put("script", it) }
                    put("appData", JsonArray(selection.appData.map(::JsonPrimitive)))
                    put("options", JsonArray(selection.options.map(::JsonPrimitive)))
                    mainVersion?.let { put("expectedMainApp", it) }
                    selection.osVersion?.let { put("expectedOs", it) }; selection.bootVersion?.let { put("expectedBoot", it) }
                    put("files", records)
                }
                File(destination, MANIFEST).writeText(manifest.toString())
            }
            return read(destination, model)
        } catch (failure: Exception) {
            if (created) destination.deleteRecursively()
            throw failure
        } finally { temporaryArchives.forEach(File::delete); archive.delete() }
    }

    /** FTP folder downloads wrap the original vendor archive. Follow one unambiguous
     * archive per layer, never an arbitrary candidate or nested OS/app payload. */
    private fun unwrap(input: File, parent: File, temporary: MutableList<File>): File {
        var current = input
        repeat(4) { depth ->
            if (PaxCompressedArchive.isCompressed(current)) {
                val normalized = File.createTempFile("pax-unpacked-", ".zip", parent).also { temporary += it }
                PaxCompressedArchive.convert(current, normalized)
                if (current != input) current.delete()
                current = normalized
            }
            validateCentralDirectory(current)
            val nested = openZip(current).use { zip ->
                val entries = checkedEntries(zip)
                if (PaxModel.entries.any { modelRoots(entries.keys, it).isNotEmpty() }) return current
                val archives = entries.filterKeys { it.isPaxArchiveName() }
                if (archives.isEmpty()) return current
                require(archives.size == 1) { "Внутри несколько архивов. Выберите архив с папкой нужной модели." }
                require(depth < 3) { "Слишком много вложенных архивов" }
                val entry = archives.values.single()
                File.createTempFile("pax-nested-", ".archive", parent).also { file ->
                    temporary += file
                    file.outputStream().use { out -> checkedCopy(zip, entry, MAX_ARCHIVE) { bytes, count -> out.write(bytes, 0, count) } }
                }
            }
            if (current != input) current.delete()
            current = nested
        }
        error("Слишком много вложенных архивов")
    }

    private fun modelRoots(paths: Set<String>, model: PaxModel): List<String> {
        val roots = paths.flatMap { path ->
            val parts = path.split('/')
            parts.indices.filter { index -> parts[index] == model.archiveFolder || (model == PaxModel.S210 && parts[index] == "soft PP pax s200") }
                .map { index -> parts.take(index + 1).joinToString("/") }
        }.distinct()
        return if (model == PaxModel.S210) roots.filter { root ->
            paths.any { path -> path.startsWith("$root/") && isS210Application(path.removePrefix("$root/")) }
        } else roots
    }

    fun read(directory: File, model: PaxModel): PaxFirmwarePackage {
        require(directory.isDirectory) { "Каталог прошивки недоступен" }
        rejectAncestorLinks(directory, directory)
        val manifestFile = File(directory, MANIFEST)
        require(manifestFile.isFile && !Files.isSymbolicLink(manifestFile.toPath()) && manifestFile.length() <= MAX_METADATA) { "Нет корректного манифеста импорта" }
        val manifest = json.parseToJsonElement(manifestFile.readText()).jsonObject
        require(manifest["format"]?.jsonPrimitive?.int == 1 && manifest["model"]?.jsonPrimitive?.content == model.name) { "Комплект относится к другой модели" }
        fun string(key: String) = manifest[key]?.jsonPrimitive?.content
        fun list(key: String) = manifest.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        val boot = string("boot"); val os = string("os"); val application = requireNotNull(string("application"))
        val data = list("appData"); val options = list("options")
        val references = listOfNotNull(boot, os, application, string("script")) + data + options
        require(references.distinct().size == references.size) { "Повторяющийся файл в комплекте" }
        val records = manifest.getValue("files").jsonObject
        require(records.keys == references.toSet() && records.size in 1..MAX_ENTRIES) { "Неполный манифест импорта" }
        var total = 0L
        val assets = records.mapValues { (path, value) ->
            safePath(path)
            val file = File(directory, path)
            rejectAncestorLinks(file, directory)
            require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "Файл вне комплекта" }
            val record = value.jsonObject
            val size = record.getValue("size").jsonPrimitive.long
            val expected = record.getValue("sha256").jsonPrimitive.content
            require(size in 1..MAX_FILE && size <= MAX_TOTAL - total && file.isFile && file.length() == size) { "Размер $path изменён" }
            total += size
            require(expected.matches(Regex("[a-f0-9]{64}")) && hash(file) == expected) { "Файл $path изменён" }
            PaxFirmwareAsset(file.name, file, expected)
        }
        // Reject links, added files and case collisions as well as changes to selected files.
        val actual = mutableSetOf<String>()
        directory.walkTopDown().onEnter { dir ->
            require(!Files.isSymbolicLink(dir.toPath())) { "Ссылки в комплекте запрещены" }; true
        }.forEach { file ->
            require(!Files.isSymbolicLink(file.toPath())) { "Ссылки в комплекте запрещены" }
            if (file.isFile) actual += file.relativeTo(directory).invariantSeparatorsPath
            require(actual.size <= MAX_ENTRIES) { "Слишком много файлов" }
        }
        require(actual == records.keys + MANIFEST) { "Состав комплекта изменён" }
        return PaxFirmwarePackage(directory, model, boot?.let(assets::getValue), os?.let(assets::getValue), assets.getValue(application), data.map(assets::getValue), options.map(assets::getValue), string("expectedOs"), string("expectedMainApp"), string("expectedBoot"))
    }

    private fun selectProlin(zip: ZipFile, entries: Map<String, ZipEntry>, model: PaxModel): Selection {
        val scripts = entries.keys.filter { it.substringAfterLast('/') == "script_VSS.list" }
        require(scripts.size == 1) { "Нужен один список установки выбранной модели" }
        val script = scripts.single()
        val base = script.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val sections = parseIni(readEntry(zip, entries.getValue(script), MAX_METADATA).toString(Charsets.UTF_8))
        require(sections.keys.all { it in setOf("app-remove", "app0", "app1", "ipkg-install") }) { "Неподдерживаемые команды установки" }
        sections["app-remove"]?.let { require(it == listOf("app1" to "MAINAPP")) { "Неподдерживаемое удаление приложения" } }
        val app = requireNotNull(sections["app0"])
        require(app.size == 1 && app.single().first == "package") { "Нужен один пакет MAINAPP" }
        fun resolve(name: String): String {
            safePath(name)
            val path = base + name
            require(path in entries) { "Отсутствует файл выбранной модели: $name" }
            return path
        }
        val application = resolve(app.single().second)
        require(application.endsWith(".aip", true) || application.endsWith(".zip", true)) { "Неподдерживаемый пакет MAINAPP" }
        val app1 = sections["app1"].orEmpty()
        require(app1.isEmpty() || app1.first() == "id" to "MAINAPP") { "Данные относятся к другому приложению" }
        val data = app1.drop(1).map { (key, value) -> require(key.matches(Regex("appdata[1-9][0-9]*"))) { "Неподдерживаемые данные приложения" }; resolve(value) }
        val options = sections["ipkg-install"].orEmpty().map { (key, value) -> require(key.matches(Regex("file[1-9][0-9]*"))) { "Неподдерживаемая команда установки" }; resolve(value) }
        val stages = entries.filterKeys { path -> path.endsWith(".zip", true) && path != application && path.substringBeforeLast('/', "") in setOf(base.removeSuffix("/"), "OS", "BOOT", "os", "boot") }
            .mapNotNull { (path, entry) -> stageMetadata(zip, entry, path, model) }
        require(stages.count { it.product == "OS" } <= 1 && stages.count { it.product == "BOOT" } <= 1) { "Неоднозначные OS или BOOT" }
        val os = stages.singleOrNull { it.product == "OS" }; val boot = stages.singleOrNull { it.product == "BOOT" }
        return Selection(application, boot?.path, os?.path, data, options, script, osVersion = os?.version, bootVersion = boot?.version)
    }

    private fun isS210Application(path: String) = path.substringBeforeLast('/', "") == "APP" &&
        path.endsWith(".APP", true) && path.substringAfterLast('/').contains(Regex("(?i)(?<![A-Z0-9])S210(?![0-9])"))

    private fun selectS210(paths: Set<String>): Selection {
        // The source may be named s200, but a S200 payload must never be used on S210.
        val apps = paths.filter(::isS210Application)
        val systems = paths.filter { it.substringBeforeLast('/', "") == "OS" && it.endsWith(".BIN", true) && it.substringAfterLast('/').contains(Regex("(?i)(?<![A-Z0-9])S210(?![0-9])")) }
        require(apps.size == 1 && systems.size <= 1) { "Не найден однозначный комплект APP/OS для S210; S200 несовместим" }
        return Selection(apps.single(), os = systems.singleOrNull())
    }

    private fun applicationVersion(file: File): String? {
        validateCentralDirectory(file, allowSignedTrailer = true)
        return openZip(file).use { zip ->
            val entries = checkedEntries(zip)
            val info = entries["appinfo"] ?: return@use null
            val app = parseIni(readEntry(zip, info, MAX_METADATA).toString(Charsets.UTF_8))["app"] ?: return@use null
            require(app.singleOrNull { it.first == "id" }?.second == "MAINAPP") { "Пакет содержит другое приложение" }
            app.singleOrNull { it.first == "version" }?.second?.takeIf(::safeVersion)
        }
    }

    private fun stageMetadata(outer: ZipFile, entry: ZipEntry, path: String, model: PaxModel): Stage? {
        val temporary = File.createTempFile("pax-metadata-", ".zip")
        try {
            temporary.outputStream().use { out -> checkedCopy(outer, entry, MAX_FILE) { bytes, count -> out.write(bytes, 0, count) } }
            validateCentralDirectory(temporary)
            return openZip(temporary).use { zip ->
                val entries = checkedEntries(zip)
                val release = entries["RELEASE.TXT"] ?: return@use null
                val fields = readEntry(zip, release, MAX_METADATA).toString(Charsets.UTF_8).lineSequence().mapNotNull { line ->
                    line.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
                }.toList()
                require(fields.map { it.first }.distinct().size == fields.size) { "Неоднозначные метаданные пакета" }
                val values = fields.toMap()
                val product = values["Product"]?.uppercase(Locale.ROOT)
                if (product !in setOf("OS", "BOOT")) return@use null
                val models = values["Model"]?.split(Regex("[^A-Za-z0-9]+")) ?: return@use null
                require("PAX${model.name}" in models.map { it.uppercase(Locale.ROOT) }) { "OS/BOOT несовместимы с ${model.title}" }
                Stage(path, requireNotNull(product), values["Version"]?.takeIf(::safeVersion))
            }
        } finally { temporary.delete() }
    }

    private fun safeVersion(value: String) = value.isNotBlank() && value.length <= 100 && value.all { it.isLetterOrDigit() || it in ".-_()" }

    private fun parseIni(text: String): Map<String, List<Pair<String, String>>> {
        val sections = linkedMapOf<String, MutableList<Pair<String, String>>>()
        var current: MutableList<Pair<String, String>>? = null
        for (line in text.removePrefix("\uFEFF").lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith(';') && !it.startsWith('#') }) {
            if (line.startsWith('[') && line.endsWith(']')) {
                val name = line.substring(1, line.length - 1)
                require(name.isNotBlank() && name !in sections) { "Повторяющийся раздел списка установки" }
                current = mutableListOf<Pair<String, String>>().also { sections[name] = it }
            } else {
                val pair = line.split('=', limit = 2)
                val target = requireNotNull(current) { "Значение вне раздела" }
                require(pair.size == 2 && pair[0].trim().isNotEmpty()) { "Некорректный список установки" }
                val key = pair[0].trim()
                require(target.none { it.first == key }) { "Повторяющийся ключ списка установки" }
                target += key to pair[1].trim()
            }
        }
        return sections
    }

    private fun safePath(path: String) {
        require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && ':' !in path && path.none { it.code < 32 }) { "Недопустимый путь ZIP" }
        val parts = path.split('/')
        require(parts.size <= 32 && parts.none { it.isEmpty() || it == "." || it == ".." }) { "Недопустимый путь ZIP" }
        require(parts.none { it.endsWith('.') || it.endsWith(' ') }) { "Неоднозначный путь ZIP" }
    }

    private fun rejectAncestorLinks(file: File, directory: File) {
        var current: File? = file.absoluteFile
        while (current != null) {
            require(!Files.isSymbolicLink(current.toPath())) { "Ссылки в пути импорта запрещены" }
            if (current == directory.absoluteFile) break
            current = current.parentFile
        }
    }

    private val legacyZipCharset = Charset.forName("CP437")
    // ZipFile still honors the UTF-8 flag. Unflagged Windows names may instead carry
    // Info-ZIP Unicode Path (0x7075), with CRC32 over the original filename bytes.
    private fun openZip(file: File) = ZipFile(file, legacyZipCharset)

    private fun unicodeEntryName(entry: ZipEntry): String {
        val extra = entry.extra ?: return entry.name
        val raw = runCatching { legacyZipCharset.newEncoder().encode(CharBuffer.wrap(entry.name)) }.getOrNull()
            ?: return entry.name
        val bytes = ByteArray(raw.remaining()).also { raw.get(it) }
        val expected = CRC32().apply { update(bytes) }.value
        fun u16(at: Int) = (extra[at].toInt() and 255) or ((extra[at + 1].toInt() and 255) shl 8)
        var offset = 0
        var unicode: String? = null
        while (offset + 4 <= extra.size) {
            val tag = u16(offset)
            val size = u16(offset + 2)
            val start = offset + 4
            require(size <= extra.size - start) { "Повреждены метаданные имён ZIP" }
            if (tag == 0x7075 && size >= 5 && extra[start] == 1.toByte()) {
                var crc = 0L
                for (i in 0..3) crc = crc or ((extra[start + 1 + i].toLong() and 255) shl (8 * i))
                if (crc == expected) {
                    val name = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(extra, start + 5, size - 5)).toString()
                    require(unicode == null || unicode == name) { "Неоднозначное имя ZIP" }
                    unicode = name
                }
            }
            offset = start + size
        }
        require(offset == extra.size) { "Повреждены метаданные имён ZIP" }
        return unicode ?: entry.name
    }

    private fun checkedEntries(zip: ZipFile): Map<String, ZipEntry> {
        val entries = linkedMapOf<String, ZipEntry>()
        val all = mutableSetOf<String>()
        var total = 0L
        val enumeration = zip.entries()
        var count = 0
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            require(++count <= MAX_ENTRIES) { "Слишком много записей ZIP" }
            val path = unicodeEntryName(entry).removeSuffix("/")
            safePath(path)
            require(all.add(path.lowercase(Locale.ROOT))) { "Коллизия путей ZIP" }
            require(entry.size in 0..MAX_FILE && entry.size <= MAX_TOTAL - total) { "Превышен размер ZIP" }
            total += entry.size
            if (!entry.isDirectory) entries[path] = entry
        }
        val files = entries.keys.map { it.lowercase(Locale.ROOT) }.toSet()
        for (path in all) {
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(parent !in files) { "Коллизия файла и каталога ZIP" }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        require(entries.isNotEmpty()) { "Пустой ZIP" }
        return entries
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { copyLimited(it, MAX_FILE) { bytes, count -> digest.update(bytes, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun readEntry(zip: ZipFile, entry: ZipEntry, limit: Long): ByteArray = ByteArrayOutputStream().also { out -> checkedCopy(zip, entry, limit) { bytes, count -> out.write(bytes, 0, count) } }.toByteArray()
    private fun checkedCopy(zip: ZipFile, entry: ZipEntry, limit: Long, consume: (ByteArray, Int) -> Unit) {
        require(entry.size in 0..limit) { "Слишком большой файл ZIP" }
        val crc = CRC32()
        val size = zip.getInputStream(entry).use { copyLimited(it, limit) { bytes, count -> crc.update(bytes, 0, count); consume(bytes, count) } }
        require(size == entry.size && crc.value == entry.crc) { "Повреждённый файл ZIP" }
    }
    private fun copyLimited(input: InputStream, limit: Long, consume: (ByteArray, Int) -> Unit): Long {
        val bytes = ByteArray(32768)
        var total = 0L
        while (true) {
            val count = input.read(bytes)
            if (count < 0) return total
            if (count == 0) continue
            require(count <= limit - total) { "Превышен размер файла" }
            consume(bytes, count); total += count
        }
    }

    /** ZIP64, encrypted archives, special UNIX files and multi-disk ZIPs are unsupported. */
    private fun validateCentralDirectory(file: File, allowSignedTrailer: Boolean = false) {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= 22) { "Некорректный ZIP" }
            val tailSize = minOf(input.length(), 65557L).toInt()
            val tail = ByteArray(tailSize)
            input.seek(input.length() - tailSize); input.readFully(tail)
            fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
            fun u32(bytes: ByteArray, at: Int) = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)
            val signedSuffix = tail.takeLast(16).toByteArray().toString(Charsets.US_ASCII).matches(Regex("SIGNED_VER:[0-9]{5}"))
            val end = (tail.size - 22 downTo 0).firstOrNull {
                if (u32(tail, it) != 0x06054b50L) false else {
                    val trailing = tail.size - (it + 22 + u16(tail, it + 20))
                    trailing == 0 || (allowSignedTrailer && signedSuffix && trailing in 16..4096)
                }
            } ?: error("ZIP не завершён")
            require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0) { "Многотомный ZIP запрещён" }
            val count = u16(tail, end + 10)
            require(count in 1..MAX_ENTRIES && count == u16(tail, end + 8)) { "Некорректное число файлов ZIP" }
            val start = u32(tail, end + 16); val length = u32(tail, end + 12)
            require(start + length == input.length() - tailSize + end) { "Некорректный каталог ZIP" }
            input.seek(start)
            repeat(count) {
                require(input.filePointer + 46 <= start + length) { "Повреждённый каталог ZIP" }
                val header = ByteArray(46); input.readFully(header)
                require(u32(header, 0) == 0x02014b50L && u16(header, 8) and 1 == 0) { "Неподдерживаемый ZIP" }
                val mode = (u32(header, 38) ushr 16).toInt() and 0xf000
                require(mode in setOf(0, 0x4000, 0x8000)) { "Ссылки и специальные файлы ZIP запрещены" }
                require(listOf(20, 24, 42).none { u32(header, it) == 0xffffffffL }) { "ZIP64 запрещён" }
                input.seek(input.filePointer + u16(header, 28) + u16(header, 30) + u16(header, 32))
            }
            require(input.filePointer == start + length) { "Некорректный каталог ZIP" }
        }
    }
}
