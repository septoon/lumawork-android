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
    fun prepareProlin(input: InputStream, destination: File, model: PaxModel): PaxFirmwarePackage {
        require(model != PaxModel.S210) { "Для S210 используется его комплект OS/APP." }
        return prepare(input, destination, model, PaxArchiveKind.Prolin)
    }

    fun combine(firmware: PaxFirmwarePackage?, prolin: PaxFirmwarePackage?, destination: File, model: PaxModel): PaxFirmwarePackage {
        require(firmware != null || prolin != null) { "Выберите архив прошивки или Prolin." }
        require(listOfNotNull(firmware, prolin).all { it.model == model }) { "Комплект относится к другой модели." }
        require(prolin == null || model != PaxModel.S210) { "Для S210 не нужен Prolin." }
        // Recheck the original immutable imports before making a self-contained installation snapshot.
        listOfNotNull(firmware, prolin).forEach { readPaxPackage(it.directory, model) }
        require(!destination.exists() && destination.mkdir()) { "Не удалось создать комплект установки." }
        try {
            val records = linkedMapOf<String, JsonObject>()
            fun copy(asset: PaxFirmwareAsset?, prefix: String): String? = asset?.let {
                val path = "$prefix/${it.name}"
                safePath(path)
                val file = File(destination, path)
                require(path !in records && file.parentFile!!.let { dir -> dir.isDirectory || dir.mkdirs() }) { "Повторяющийся файл комплекта." }
                it.file.inputStream().use { input -> file.outputStream().use { output ->
                    copyLimited(input, MAX_FILE) { bytes, count -> output.write(bytes, 0, count) }
                } }
                require(file.length() == it.size && hash(file) == it.sha256) { "Исходный комплект изменён." }
                records[path] = buildJsonObject { put("size", it.size); put("sha256", it.sha256) }
                path
            }
            val osSource = prolin ?: firmware
            val selection = Selection(copy(firmware?.application, "firmware"), copy(osSource?.boot, "system"), copy(osSource?.os, "system"),
                firmware?.appData.orEmpty().map { copy(it, "firmware")!! }, firmware?.options.orEmpty().map { copy(it, "firmware")!! },
                osVersion = osSource?.expectedOs, bootVersion = osSource?.expectedBoot)
            writeManifest(destination, model, selection, firmware?.expectedMainApp, JsonObject(records))
            return read(destination, model)
        } catch (failure: Exception) { destination.deleteRecursively(); throw failure }
    }
    private const val MANIFEST = ".pax-import.json"
    private const val MAX_ARCHIVE = 256L * 1024 * 1024
    private const val MAX_FILE = 256L * 1024 * 1024
    private const val MAX_TOTAL = 1024L * 1024 * 1024
    private const val MAX_ENTRIES = 10000
    private const val MAX_METADATA = 64L * 1024
    private val json = Json
    private data class Selection(
        val application: String?,
        val boot: String? = null,
        val os: String? = null,
        val appData: List<String> = emptyList(),
        val options: List<String> = emptyList(),
        val script: String? = null,
        val osVersion: String? = null,
        val bootVersion: String? = null,
    )
    private data class Stage(val path: String, val product: String, val version: String?)

    fun prepare(input: InputStream, destination: File, model: PaxModel): PaxFirmwarePackage =
        prepare(input, destination, model, PaxArchiveKind.Firmware)

    private fun prepare(input: InputStream, destination: File, model: PaxModel, kind: PaxArchiveKind): PaxFirmwarePackage {
        require(!destination.exists() && !Files.isSymbolicLink(destination.toPath())) { "Каталог импорта уже существует" }
        val parent = requireNotNull(destination.absoluteFile.parentFile)
        require(parent.isDirectory || parent.mkdirs()) { "Не удалось создать каталог импорта" }
        val archive = File.createTempFile("pax-catalog-", ".zip", parent)
        val temporaryArchives = mutableListOf<File>()
        var created = false
        try {
            archive.outputStream().use { out -> input.use { copyLimited(it, MAX_ARCHIVE) { bytes, count -> out.write(bytes, 0, count) } } }
            val source = unwrap(archive, parent, temporaryArchives, kind)
            validateCentralDirectory(source)
            openZip(source).use { zip ->
                val entries = checkedEntries(zip)
                val scoped = if (kind == PaxArchiveKind.Prolin) entries else {
                    val roots = modelRoots(entries.keys, model)
                    require(roots.size == 1) { "Папка ${model.archiveFolder} отсутствует или неоднозначна" }
                    val root = roots.single()
                    entries.filterKeys { it.startsWith("$root/") }.mapKeys { it.key.removePrefix("$root/") }
                }
                val selection = if (kind == PaxArchiveKind.Prolin) selectSystem(zip, scoped, model)
                    else if (model == PaxModel.S210) selectS210(scoped.keys) else selectProlin(zip, scoped, model)
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
                val mainVersion = if (model == PaxModel.S210) null else selection.application?.let { applicationVersion(File(destination, it)) }
                writeManifest(destination, model, selection, mainVersion, records)
            }
            return read(destination, model)
        } catch (failure: Exception) {
            if (created) destination.deleteRecursively()
            throw failure
        } finally { temporaryArchives.forEach(File::delete); archive.delete() }
    }

    /** FTP folder downloads wrap the original vendor archive. Follow one unambiguous
     * archive per layer, never an arbitrary candidate or nested OS/app payload. */
    private fun writeManifest(directory: File, model: PaxModel, selection: Selection, mainVersion: String?, records: JsonObject) {
        val manifest = buildJsonObject {
            put("format", 1); put("model", model.name)
            selection.application?.let { put("application", it) }
            selection.boot?.let { put("boot", it) }; selection.os?.let { put("os", it) }
            selection.script?.let { put("script", it) }
            put("appData", JsonArray(selection.appData.map(::JsonPrimitive)))
            put("options", JsonArray(selection.options.map(::JsonPrimitive)))
            mainVersion?.let { put("expectedMainApp", it) }
            selection.osVersion?.let { put("expectedOs", it) }; selection.bootVersion?.let { put("expectedBoot", it) }
            put("files", records)
        }
        File(directory, MANIFEST).writeText(manifest.toString())
    }

    private fun unwrap(input: File, parent: File, temporary: MutableList<File>, kind: PaxArchiveKind): File {
        var current = input
        repeat(4) { depth ->
            if (PaxCompressedArchive.isCompressed(current)) {
                val normalized = File.createTempFile("pax-unpacked-", ".zip", parent).also { temporary += it }
                PaxCompressedArchive.convert(current, normalized)
                if (current != input) current.delete()
                current = normalized
            }
            val metadata = validateCentralDirectory(current, allowEncrypted = kind == PaxArchiveKind.Prolin)
            // Android 9 ZipFile accepts the ZIP64 directory but misreads entry offsets.
            // Normalize only the outer container; the selected firmware payload bytes stay intact.
            if (metadata.zip64 || metadata.encrypted) {
                val normalized = File.createTempFile("pax-normalized-zip-", ".zip", parent).also { temporary += it }
                if (metadata.encrypted) PaxCompressedArchive.convertRemoteOs(current, normalized)
                else PaxCompressedArchive.convertZip(current, normalized)
                if (current != input) current.delete()
                current = normalized
            }
            validateCentralDirectory(current)
            val nested = openZip(current).use { zip ->
                val entries = checkedEntries(zip)
                if (kind == PaxArchiveKind.Prolin) {
                    val remote = entries.filterKeys { it.substringAfterLast('/').matches(Regex("(?i)OS_RemoteUpdatePkg_[0-9.]+\\.zip")) }
                    require(remote.size <= 1) { "Внутри несколько версий OS RemoteUpdate. Выберите один архив." }
                    if (remote.isNotEmpty()) {
                        val entry = remote.values.single()
                        return@use File.createTempFile("pax-remote-os-", ".zip", parent).also { file ->
                            temporary += file
                            file.outputStream().use { out -> checkedCopy(zip, entry, MAX_ARCHIVE) { bytes, count -> out.write(bytes, 0, count) } }
                        }
                    }
                    return current
                }
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
        val boot = string("boot"); val os = string("os"); val application = string("application")
        require(application != null || os != null) { "Комплект не содержит прошивки или ОС." }
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
        return PaxFirmwarePackage(directory, model, boot?.let(assets::getValue), os?.let(assets::getValue), application?.let(assets::getValue), data.map(assets::getValue), options.map(assets::getValue), string("expectedOs"), string("expectedMainApp"), string("expectedBoot"))
    }

    private fun selectSystem(zip: ZipFile, entries: Map<String, ZipEntry>, model: PaxModel): Selection {
        val stages = entries.filterKeys { it.endsWith(".zip", true) }.mapNotNull { (path, entry) -> stageMetadata(zip, entry, path, model, strict = false) }
        val systems = stages.filter { it.product == "OS" }; val boots = stages.filter { it.product == "BOOT" }
        require(systems.size == 1 && boots.size <= 1) { "Не найдена однозначная ОС для ${model.title}. Выберите архив с одной совместимой версией." }
        return Selection(null, boots.singleOrNull()?.path, systems.single().path,
            osVersion = systems.single().version, bootVersion = boots.singleOrNull()?.version)
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

    private fun stageMetadata(outer: ZipFile, entry: ZipEntry, path: String, model: PaxModel, strict: Boolean = true): Stage? {
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
                if ("PAX${model.name}" !in models.map { it.uppercase(Locale.ROOT) }) {
                    require(!strict) { "OS/BOOT несовместимы с ${model.title}" }; return@use null
                }
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

    /** Single-disk ZIP/ZIP64 only, with the same bounded payloads and regular files. */
    internal data class ZipMetadata(val encrypted: Boolean, val zip64: Boolean)

    internal fun validateCentralDirectory(file: File, allowSignedTrailer: Boolean = false, allowEncrypted: Boolean = false): ZipMetadata =
        RandomAccessFile(file, "r").use { input ->
            var encrypted = false
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
            fun u64(bytes: ByteArray, at: Int): Long =
                (u32(bytes, at) or (u32(bytes, at + 4) shl 32)).also { require(it >= 0) { "Некорректный ZIP64" } }
            val endOffset = input.length() - tailSize + end
            var count = u16(tail, end + 10).toLong()
            var start = u32(tail, end + 16)
            var length = u32(tail, end + 12)
            var centralEnd = endOffset
            val zip64 = listOf(4, 6, 8, 10).any { u16(tail, end + it) == 65535 } ||
                start == 0xffffffffL || length == 0xffffffffL
            if (zip64) {
                require(endOffset >= 76) { "Нет каталога ZIP64" }
                val locator = ByteArray(20)
                input.seek(endOffset - 20); input.readFully(locator)
                require(u32(locator, 0) == 0x07064b50L && u32(locator, 4) == 0L && u32(locator, 16) == 1L) { "Многотомный или повреждённый ZIP64" }
                centralEnd = u64(locator, 8)
                require(centralEnd <= endOffset - 76) { "Некорректный каталог ZIP64" }
                val record = ByteArray(56)
                input.seek(centralEnd); input.readFully(record)
                require(u32(record, 0) == 0x06064b50L && u64(record, 4) == endOffset - 32 - centralEnd) { "Некорректный каталог ZIP64" }
                require(u32(record, 16) == 0L && u32(record, 20) == 0L) { "Многотомный ZIP64 запрещён" }
                count = u64(record, 32); start = u64(record, 48); length = u64(record, 40)
                require(count == u64(record, 24)) { "Некорректное число файлов ZIP64" }
                for ((offset, value) in listOf(4 to 0L, 6 to 0L, 8 to count, 10 to count)) {
                    require(u16(tail, end + offset).let { it == 65535 || it.toLong() == value }) { "Несогласованный каталог ZIP64" }
                }
                require(u32(tail, end + 12).let { it == 0xffffffffL || it == length } &&
                    u32(tail, end + 16).let { it == 0xffffffffL || it == start }) { "Несогласованный каталог ZIP64" }
            } else {
                require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0) { "Многотомный ZIP запрещён" }
                require(count == u16(tail, end + 8).toLong()) { "Некорректное число файлов ZIP" }
            }
            require(count in 1..MAX_ENTRIES.toLong()) { "Некорректное число файлов ZIP" }
            require(start <= centralEnd && length == centralEnd - start) { "Некорректный каталог ZIP" }
            input.seek(start)
            repeat(count.toInt()) {
                require(input.filePointer + 46 <= centralEnd) { "Повреждённый каталог ZIP" }
                val header = ByteArray(46); input.readFully(header)
                val protected = u16(header, 8) and 1 != 0
                encrypted = encrypted || protected
                require(u32(header, 0) == 0x02014b50L && (!protected || allowEncrypted)) { "Неподдерживаемый ZIP" }
                val mode = (u32(header, 38) ushr 16).toInt() and 0xf000
                require(mode in setOf(0, 0x4000, 0x8000)) { "Ссылки и специальные файлы ZIP запрещены" }
                val nameSize = u16(header, 28); val extraSize = u16(header, 30); val commentSize = u16(header, 32)
                require(nameSize + extraSize + commentSize <= centralEnd - input.filePointer) { "Повреждённый каталог ZIP" }
                input.seek(input.filePointer + nameSize)
                val extra = ByteArray(extraSize); input.readFully(extra)
                var uncompressed = u32(header, 24); var compressed = u32(header, 20)
                var offset = u32(header, 42); var disk = u16(header, 34).toLong()
                if (listOf(uncompressed, compressed, offset).any { it == 0xffffffffL } || disk == 65535L) {
                    var cursor = 0
                    var found = false
                    while (cursor < extra.size) {
                        require(cursor + 4 <= extra.size) { "Повреждённое поле ZIP64" }
                        val size = u16(extra, cursor + 2)
                        require(size <= extra.size - cursor - 4) { "Повреждённое поле ZIP64" }
                        if (u16(extra, cursor) == 1) {
                            require(!found) { "Повторяющееся поле ZIP64" }; found = true
                            var position = cursor + 4
                            fun next(): Long {
                                require(position + 8 <= cursor + 4 + size) { "Неполное поле ZIP64" }
                                return u64(extra, position).also { position += 8 }
                            }
                            if (uncompressed == 0xffffffffL) uncompressed = next()
                            if (compressed == 0xffffffffL) compressed = next()
                            if (offset == 0xffffffffL) offset = next()
                            if (disk == 65535L) {
                                require(position + 4 <= cursor + 4 + size) { "Неполное поле ZIP64" }
                                disk = u32(extra, position)
                            }
                        }
                        cursor += 4 + size
                    }
                    require(found) { "Нет поля ZIP64" }
                }
                require(disk == 0L) { "Многотомный ZIP запрещён" }
                require(uncompressed <= MAX_FILE && compressed <= MAX_ARCHIVE && offset < start) { "Превышен размер или смещение файла ZIP" }
                input.seek(input.filePointer + commentSize)
            }
            require(input.filePointer == centralEnd) { "Некорректный каталог ZIP" }
            ZipMetadata(encrypted, zip64)
        }
}
