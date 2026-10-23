package ru.lumastack.lumawork.data.tools

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

const val MTK_P8_FTP_PATH = "/VTB_TO/TO/Vendor/Telepower/UnitodiP8bio/OS/download tools"
const val MTK_P8_ARCHIVE = "P8KC_20260311_v1.0.48_emmc_pbf_oldkey_config.zip"
private const val MTK_SCATTER = "MT6761_Android_scatter.txt"
internal const val MTK_WORK_RESERVE = 128L * 1024 * 1024

data class MtkImageSpec(val partitionName: String, val fileName: String, val size: Long, val sha256: String)
data class MtkAgentStage(val offset: Long, val size: Long, val sha256: String, val address: Long, val signatureSize: Int)
data class MtkAgentProfile(val size: Long, val sha256: String, val stage1: MtkAgentStage, val stage2: MtkAgentStage)
data class MtkPackageProfile(val id: String, val images: List<MtkImageSpec>, val scatterSha256: String, val agent: MtkAgentProfile) {
    val requiredBytes: Long get() = images.distinctBy { it.fileName }.sumOf { it.size } + agent.size + 1024 * 1024
}
data class MtkImage(val partitionName: String, val file: File, val size: Long, val sha256: String)
data class MtkFirmwarePackage(val directory: File, val profile: String, val images: List<MtkImage>, val agent: File, val agentProfile: MtkAgentProfile, val sha256: String)

val MTK_P8_PROFILE = MtkPackageProfile(
    "P8KC / MT6761 · 20260311 v1.0.48 · emmc_pbf_oldkey_config",
    listOf(
        MtkImageSpec("preloader", "preloader_P8KC.bin", 241420L, "0ed00707a0b28129fa5ff24001fe747792e477561c30e11a8fdf5a13af55efdc"),
        MtkImageSpec("preloader_backup", "preloader_P8KC.bin", 241420L, "0ed00707a0b28129fa5ff24001fe747792e477561c30e11a8fdf5a13af55efdc"),
        MtkImageSpec("logo", "logo-verified.bin", 3028704L, "9e4e8ceab4903b2047ed33ccf70f10d2dbc0ac87c77f8b43ac830f48ff2cdd15"),
        MtkImageSpec("md1img_a", "md1img-verified.img", 50249536L, "3ef82361b4886e572c4d95dbdae57f58a9b056837f9c3b6a174077ccd67e6242"),
        MtkImageSpec("spmfw_a", "spmfw-verified.img", 64496L, "6e1ae97b63f0a8a1d21598a7782099c10790259f140a28cb949b9b69bc912fd1"),
        MtkImageSpec("scp_a", "scp-verified.img", 279664L, "57d99d8f5120dfebe16b362b5dae0cf6f5bbd02ab4eb3cd9f56f4a3c53b3290a"),
        MtkImageSpec("sspm_a", "sspm-verified.img", 442800L, "098765fc8812f01c4da8a3e8696ae9ab58b5ca8aba711d765262248c819e64bc"),
        MtkImageSpec("lk_a", "lk-verified.img", 861664L, "c0e41dff6fbeba64c5c0d3b4b190e350ff17eef9baaeee12fe5f18bb30aeb720"),
        MtkImageSpec("boot_a", "boot.img", 33554432L, "5766fc4316694395f8862cfaa167288af253c7f0655eab5fce887ec4318a5975"),
        MtkImageSpec("dtbo_a", "dtbo-verified.img", 29328L, "cbf7d1b811083ef755823dadf65cb02613705e41da4fb8ce1fc414711e912a07"),
        MtkImageSpec("tee_a", "tee-verified.img", 124080L, "a8d65d835c48bc21eea316bdbd7d87003c29c5702f4134eb4423da9a96b15aa7"),
        MtkImageSpec("vbmeta_a", "vbmeta.img", 4096L, "257be8c32317e6abbaf5fc838f2acb712af7d06dfa8442d84c8eed118696e94c"),
        MtkImageSpec("vbmeta_system_a", "vbmeta_system.img", 4096L, "767aff933537c4da2ec40a031dab058c3901f6caf21c7b63a02faba545c05abf"),
        MtkImageSpec("vbmeta_vendor_a", "vbmeta_vendor.img", 4096L, "d6a63a7732634965c06881ec70b933ec873a415ee666b6dfa560e67818ee4179"),
        MtkImageSpec("super", "super.img", 2348906824L, "1ff16f2a49e4a66d51bcd444a152b8b70e1c4b3bb07db309780c2e826c90d9b8"),
        MtkImageSpec("privdata", "privdata.img", 318767104L, "696db2161f95e896dfdbe1bff80e86034e1a96b4c593e8ddc27ae027502e8501"),
        MtkImageSpec("keychain", "keychain.img", 49252L, "54d0d97861baf9d17355541f39bdb6068c3f354d574ed484dd5c9758357f01eb"),
        MtkImageSpec("hardwareinfo", "hardwareinfo.img", 45132L, "a68f0d4c00227183f7bb4bf29c8bb2d49e444f2020d094e71abf4401e590361d"),
        MtkImageSpec("userdata", "userdata.img", 98572L, "d73c220df6699e09bc461381ee88b4f1cf6828a4bb162c6f2b0459c1c670e967"),
    ),
    "ac1ac72928c22955992c300bd0acf8d68539f4c34b47e5aea7afc940b572bd49",
    MtkAgentProfile(591104, "a2d01c083f9b84eec1431a6cc0733fb06ae041f984abedd4065cdfbdf8834375",
        MtkAgentStage(14812, 240920, "fd7d5ead51ccc0bb567d3649544dfdbc90398c16cc00fb1b1872b40bf04b3a65", 0x00200000, 256),
        MtkAgentStage(255732, 335116, "58075d57817809bf6c3413ef9375d397eb7cd4fd7eeb36667f68912c561cd208", 0x40000000, 256)),
)

internal fun mtkArchivePath(name: String, seen: MutableSet<String>): String {
    require(name.isNotEmpty() && !name.startsWith('/') && ':' !in name && '\\' !in name) { "Недопустимый путь в ZIP." }
    val path = name.removeSuffix("/")
    require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." } && seen.add(path)) { "Повторяющийся или небезопасный путь в ZIP." }
    return path
}
internal fun mtkSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

/** The only public profile is the captured P8KC package. Tests supply a small profile internally. */
class MtkFirmwarePackageReader internal constructor(
    private val profile: MtkPackageProfile,
    private val availableSpace: (File) -> Long = { it.usableSpace },
    private val checkpoint: () -> Unit = {},
    private val progress: (String, Long, Long) -> Unit = { _, _, _ -> },
) {
    constructor(checkpoint: () -> Unit = {}, progress: (String, Long, Long) -> Unit = { _, _, _ -> }) : this(MTK_P8_PROFILE, checkpoint = checkpoint, progress = progress)

    fun prepare(input: InputStream, destination: File, agent: File): MtkFirmwarePackage = stage(destination, agent) { staging ->
        val seen = mutableSetOf<String>()
        val fileNames = mutableSetOf<String>()
        var count = 0
        var remaining = profile.requiredBytes
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                checkpoint()
                val entry = zip.nextEntry ?: break
                require(++count <= 128) { "В ZIP слишком много файлов." }
                val path = mtkArchivePath(entry.name, seen)
                if (entry.isDirectory) {
                    require(zip.read() == -1) { "Каталог ZIP содержит неожиданные данные." }
                } else {
                    val name = path.substringAfterLast('/')
                    require(fileNames.add(name)) { "Повторяющийся файл в комплекте: $name" }
                    val expected = profile.images.firstOrNull { it.fileName == name }?.size ?: if (name == MTK_SCATTER) 65536L else 0L
                    require(expected > 0) { "Неожиданный файл в комплекте: $name" }
                    val target = File(staging, path)
                    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                    val copied = copy(zip, target, minOf(expected, remaining), name)
                    remaining -= copied
                }
                zip.closeEntry()
            }
        }
    }

    /** SAF directories are streamed, without raw expansion or loading images into RAM. */
    fun prepareDirectory(source: File, destination: File, agent: File): MtkFirmwarePackage = stage(destination, agent) { staging ->
        val root = source.canonicalFile
        (profile.images.map { it.fileName }.distinct() + MTK_SCATTER).forEach { name ->
            val file = File(root, name)
            require(file.canonicalFile.parentFile == root && file.isFile) { "Нет исходного файла $name." }
            val limit = profile.images.firstOrNull { it.fileName == name }?.size ?: 65536L
            file.inputStream().use { copy(it, File(staging, name), limit, name) }
        }
    }

    fun inspect(directory: File): MtkFirmwarePackage {
        val scatter = directory.walkTopDown().filter { it.isFile && it.name == MTK_SCATTER }.toList()
        require(scatter.size == 1) { "Нужен один комплект MT6761/P8KC со scatter." }
        val base = scatter.single().parentFile!!
        require(scatter.single().length() <= 65536 && hash(scatter.single()) == profile.scatterSha256) { "Scatter не соответствует проверенному MT6761/P8KC." }
        val text = scatter.single().readText()
        require(listOf("platform: MT6761", "project: P8KC", "storage: EMMC").all(text::contains)) { "Неподдерживаемый профиль scatter." }
        val checked = mutableMapOf<String, MtkImage>()
        val images = profile.images.map { expected ->
            val image = checked.getOrPut(expected.fileName) {
                val file = File(base, expected.fileName)
                require(file.canonicalFile.parentFile == base.canonicalFile && file.isFile && file.length() == expected.size) { "Неверный размер или отсутствует ${expected.fileName}." }
                require(hash(file) == expected.sha256) { "SHA-256 не совпадает: ${expected.fileName}." }
                MtkImage(expected.partitionName, file, expected.size, expected.sha256)
            }
            image.copy(partitionName = expected.partitionName)
        }
        val agent = File(directory, "agent.bin")
        verifyAgent(agent)
        val identity = mtkSha256((profile.id + "\n" + profile.scatterSha256 + "\n" + profile.agent.sha256 + "\n" +
            images.joinToString("\n") { "${it.partitionName}:${it.size}:${it.sha256}" }).toByteArray())
        return MtkFirmwarePackage(directory, profile.id, images, agent, profile.agent, identity)
    }

    fun verifyAgent(agent: File) {
        require(agent.isFile && agent.length() == profile.agent.size && hash(agent) == profile.agent.sha256) { "Выберите исходный MTK_AllInOne_DA.bin из Agenew (591104 байта). SHA-256 не совпадает." }
        for (part in listOf(profile.agent.stage1, profile.agent.stage2)) {
            require(part.offset >= 0 && part.size > 0 && part.offset + part.size <= agent.length())
            agent.inputStream().use { input ->
                var skip = part.offset
                while (skip > 0) { val n = input.skip(skip); require(n > 0); skip -= n }
                val hash = digest(input, part.size, "Download Agent", exact = true)
                require(hash == part.sha256) { "Не совпадает ступень Download Agent." }
            }
        }
    }

    private fun stage(destination: File, agent: File, extract: (File) -> Unit): MtkFirmwarePackage {
        require(!destination.exists()) { "Каталог комплекта уже существует." }
        val parent = destination.absoluteFile.parentFile!!
        check(parent.isDirectory || parent.mkdirs())
        require(availableSpace(parent) >= profile.requiredBytes + MTK_WORK_RESERVE) { "Недостаточно места: требуется комплект и резерв 128 МиБ." }
        verifyAgent(agent)
        val staging = File(parent, ".mtk-staging-${UUID.randomUUID()}")
        check(staging.mkdir())
        var moved = false
        try {
            agent.inputStream().use { copy(it, File(staging, "agent.bin"), profile.agent.size, "Download Agent") }
            extract(staging)
            val prepared = inspect(staging)
            checkpoint()
            check(staging.renameTo(destination)) { "Не удалось сохранить комплект." }
            moved = true
            return prepared.copy(directory = destination, agent = File(destination, "agent.bin"), images = prepared.images.map { image ->
                image.copy(file = File(destination, image.file.relativeTo(staging).path))
            })
        } catch (failure: Throwable) {
            if (moved) destination.deleteRecursively()
            throw failure
        } finally { staging.deleteRecursively() }
    }
    private fun copy(input: InputStream, file: File, limit: Long, name: String): Long {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        file.outputStream().use { output ->
            while (true) {
                checkpoint()
                val n = input.read(buffer)
                if (n < 0) break
                require(n > 0 && n.toLong() <= limit - total) { "Размер $name превышает профиль." }
                output.write(buffer, 0, n); total += n
                progress(name, total, limit)
            }
        }
        return total
    }
    internal fun hash(file: File): String = file.inputStream().use { digest(it, file.length(), file.name, exact = false) }
    private fun digest(input: InputStream, size: Long, name: String, exact: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (total < size) {
            checkpoint()
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - total).toInt())
            require(n > 0) { "Неполный файл $name." }
            digest.update(buffer, 0, n); total += n
            progress(name, total, size)
        }
        require(exact || input.read() == -1) { "Файл изменился во время проверки: $name." }
        return digest.digest().hex()
    }
}
