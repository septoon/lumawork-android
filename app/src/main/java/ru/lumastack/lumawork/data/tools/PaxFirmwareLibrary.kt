package ru.lumastack.lumawork.data.tools

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class PaxArchiveVersion(
    val id: String, val model: PaxModel, val kind: PaxArchiveKind, val name: String,
    val version: String?, val directory: String, val sourcePath: String? = null,
)
@Serializable data class PaxArchiveDownload(val transferId: String, val model: PaxModel, val kind: PaxArchiveKind)
@Serializable data class PaxLibrarySnapshot(
    val archives: List<PaxArchiveVersion> = emptyList(), val selected: Map<String, String> = emptyMap(),
    val bundles: Map<String, String> = emptyMap(), val pending: PaxArchiveDownload? = null,
    val pinpadVersions: Map<String, String> = emptyMap(),
) {
    fun selected(model: PaxModel, kind: PaxArchiveKind): String? = selected["${model.name}:${kind.name}"]
}

/** Account-scoped, immutable imports. Install journals keep their own pinned bundle directory. */
class PaxFirmwareLibrary(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun read(): PaxLibrarySnapshot = synchronized(lock) {
        val file = File(root, "library.json")
        if (!file.exists()) PaxLibrarySnapshot() else json.decodeFromString<PaxLibrarySnapshot>(file.readText()).also { state ->
            require(state.archives.map { it.id }.distinct().size == state.archives.size)
            state.archives.forEach { checked(it.directory) }
            state.bundles.values.forEach(::checked)
        }
    }
    fun bundle(model: PaxModel): PaxFirmwarePackage? = synchronized(lock) {
        read().bundles[model.name]?.let { PaxArchiveCatalog.read(checked(it), model) }
    }
    fun importArchive(input: InputStream, model: PaxModel, kind: PaxArchiveKind, name: String, sourcePath: String? = null): PaxLibrarySnapshot = synchronized(lock) {
        val state = read()
        val transferId = state.pending?.takeIf { it.model == model && it.kind == kind }?.transferId
        state.archives.firstOrNull { it.id == transferId && it.model == model && it.kind == kind }?.let { existing ->
            input.close()
            return@synchronized select(existing.id)
        }
        val destination = destination()
        try {
            val bundle = if (kind == PaxArchiveKind.Prolin) PaxArchiveCatalog.prepareProlin(input, destination, model)
                else if (model != PaxModel.D270) PaxArchiveCatalog.prepare(input, destination, model)
                else {
                    // Keep the fingerprinted D270 capture kit import working alongside vendor model folders.
                    val source = File.createTempFile("pax-source-", ".zip", root)
                    try {
                        input.use { stream -> source.outputStream().use { output ->
                            val bytes = ByteArray(32768); var size = 0L
                            while (true) {
                                val count = stream.read(bytes); if (count < 0) break
                                size += count; require(size <= 256L * 1024 * 1024) { "Архив слишком большой." }
                                output.write(bytes, 0, count)
                            }
                        } }
                        try { source.inputStream().use { PaxArchiveCatalog.prepare(it, destination, model) } }
                        catch (failure: Exception) {
                            try { source.inputStream().use { PaxFirmwareBundleReader.prepare(it, destination).asPackage() } }
                            catch (_: Exception) { throw failure }
                        }
                    } finally { source.delete() }
                }
            val entry = PaxArchiveVersion(transferId ?: UUID.randomUUID().toString(), model, kind, name,
                if (kind == PaxArchiveKind.Prolin) bundle.expectedOs else bundle.expectedMainApp ?: bundle.application?.name, destination.name, sourcePath)
            activate(state.copy(archives = state.archives + entry), entry)
        } catch (failure: Exception) { destination.deleteRecursively(); throw failure }
    }
    fun select(id: String): PaxLibrarySnapshot = synchronized(lock) {
        val state = read()
        val entry = requireNotNull(state.archives.singleOrNull { it.id == id }) { "Сохранённый архив не найден." }
        if (state.selected(entry.model, entry.kind) == id) {
            bundle(entry.model); state
        } else activate(state, entry)
    }
    fun pending(value: PaxArchiveDownload?): PaxLibrarySnapshot = synchronized(lock) {
        require(value == null || value.model != PaxModel.S210 || value.kind != PaxArchiveKind.Prolin)
        read().copy(pending = value).also(::write)
    }
    fun adopt(bundle: PaxFirmwarePackage): PaxLibrarySnapshot = synchronized(lock) {
        val state = read()
        if (state.archives.any { it.model == bundle.model }) return@synchronized state
        require(checked(bundle.directory.name).canonicalFile == bundle.directory.canonicalFile)
        readPaxPackage(bundle.directory, bundle.model)
        val entry = PaxArchiveVersion(UUID.randomUUID().toString(), bundle.model, PaxArchiveKind.Firmware,
            "Сохранённый комплект", bundle.expectedMainApp, bundle.directory.name)
        activate(state.copy(archives = state.archives + entry), entry)
    }
    private fun activate(state: PaxLibrarySnapshot, entry: PaxArchiveVersion): PaxLibrarySnapshot {
        val selected = state.selected + ("${entry.model.name}:${entry.kind.name}" to entry.id)
        fun source(kind: PaxArchiveKind) = selected["${entry.model.name}:${kind.name}"]?.let { id ->
            val version = state.archives.single { it.id == id }
            require(version.model == entry.model && version.kind == kind)
            readPaxPackage(checked(version.directory), entry.model)
        }
        val choice = "${entry.model.name}\n${selected["${entry.model.name}:${PaxArchiveKind.Firmware.name}"].orEmpty()}\n${selected["${entry.model.name}:${PaxArchiveKind.Prolin.name}"].orEmpty()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(choice.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val destination = checked("bundle-$digest")
        val created = !destination.exists()
        try {
            val firmware = source(PaxArchiveKind.Firmware)
            val prolin = source(PaxArchiveKind.Prolin)
            if (created) PaxArchiveCatalog.combine(firmware, prolin, destination, entry.model)
            else PaxArchiveCatalog.read(destination, entry.model)
            return state.copy(selected = selected, bundles = state.bundles + (entry.model.name to destination.name)).also(::write)
        } catch (failure: Exception) { if (created) destination.deleteRecursively(); throw failure }
    }
    private fun destination(): File {
        require(root.isDirectory || root.mkdirs()) { "Хранилище прошивок недоступно." }
        return File(root, "bundle-${UUID.randomUUID()}")
    }
    fun pinpadVersion(id: String, version: String): PaxLibrarySnapshot = synchronized(lock) {
        val state = read()
        require(state.archives.any { it.id == id && it.model == PaxModel.S210 && it.kind == PaxArchiveKind.Firmware })
        require(version.length <= 96 && version.all { it.isLetterOrDigit() || it in ".-_()" })
        state.copy(pinpadVersions = state.pinpadVersions + (id to version)).also(::write)
    }
    private fun checked(name: String): File {
        require(name.startsWith("bundle-") && name.none { it == '/' || it == '\\' } && name != "bundle-..")
        val file = File(root, name)
        require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == root.canonicalFile) { "Комплект вне хранилища пользователя." }
        return file
    }
    private fun write(state: PaxLibrarySnapshot) {
        require(root.isDirectory || root.mkdirs())
        val temporary = File(root, "library-${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { stream -> stream.write(json.encodeToString(state).toByteArray()); stream.fd.sync() }
            Files.move(temporary.toPath(), File(root, "library.json").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }
    companion object { private val lock = Any() }
}
