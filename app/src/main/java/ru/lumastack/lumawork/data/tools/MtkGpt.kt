package ru.lumastack.lumawork.data.tools

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlinx.serialization.Serializable

@Serializable data class MtkPartition(val name: String, val startBytes: Long, val sizeBytes: Long)
@Serializable data class MtkGptInfo(val userSize: Long, val partitions: List<MtkPartition>, val layoutSha256: String) {
    // Canonical names/offsets/sizes from the CRC-valid reference PGPT; no device GUIDs or banking data.
    val matchesP8Profile: Boolean get() = userSize == 31289507840L && layoutSha256 == "94d753b45de3798de2b30b49476bbd7796bc4756c844be3270bc828e36b33e2e"
}
private data class MtkGptHeader(val firstUsable: Long, val lastUsable: Long, val guid: ByteArray, val entries: ByteArray)

/** Limited to the captured 512-byte-sector GPT layout. Neither repairs nor writes either copy. */
internal fun parseMtkGpt(primary: ByteArray, backup: ByteArray, userSize: Long): MtkGptInfo {
    fun fail(message: String): Nothing = throw MtkProtocolException("MTK GPT: $message")
    if (userSize <= 0 || userSize % 512 != 0L || userSize / 512 < 128 || primary.size != 32768 || backup.size != 16896)
        fail("неподдерживаемая геометрия таблицы.")
    val sectors = userSize / 512
    fun header(blob: ByteArray, offset: Int, baseLba: Long, current: Long, other: Long, tableLba: Long): MtkGptHeader {
        val b = ByteBuffer.wrap(blob,offset,92).slice().order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8).also(b::get)
        if (!magic.contentEquals("EFI PART".toByteArray(Charsets.US_ASCII)) || b.int != 0x10000 || b.int != 92) fail("неверная сигнатура или версия.")
        val storedCrc = b.int.toLong() and 0xffffffffL
        if (b.int != 0) fail("ненулевое зарезервированное поле заголовка.")
        val headerBytes = blob.copyOfRange(offset,offset+92).apply { fill(0,16,20) }
        if (CRC32().apply { update(headerBytes) }.value != storedCrc) fail("CRC заголовка не совпала.")
        if (b.long != current || b.long != other) fail("основная и резервная таблицы указывают неверные LBA.")
        val first = b.long; val last = b.long
        if (first < 34 || last < first || last >= sectors-33) fail("неверные границы доступных LBA.")
        val guid = ByteArray(16).also(b::get)
        // The CRC-valid Agenew reference uses an all-zero disk GUID. Require equality of copies below.
        if (b.long != tableLba || b.int != 128 || b.int != 128) fail("неподдерживаемый массив разделов.")
        val tableCrc = b.int.toLong() and 0xffffffffL
        val relative = tableLba-baseLba
        if (relative < 0 || relative > (blob.size-16384)/512) fail("массив разделов вне полученного блока.")
        val start = (relative*512).toInt()
        val entries = blob.copyOfRange(start,start+16384)
        if (CRC32().apply { update(entries) }.value != tableCrc) fail("CRC массива разделов не совпала.")
        return MtkGptHeader(first,last,guid,entries)
    }
    val a = header(primary,512,0,1,sectors-1,2)
    val b = header(backup,16384,sectors-33,sectors-1,1,sectors-33)
    if (a.firstUsable != b.firstUsable || a.lastUsable != b.lastUsable || !a.guid.contentEquals(b.guid) || !a.entries.contentEquals(b.entries))
        fail("PGPT и SGPT не совпадают. Автоматическое восстановление запрещено.")
    val names = mutableSetOf<String>()
    val guids = mutableSetOf<String>()
    val parts = (0 until 128).mapNotNull { index ->
        val entry = a.entries.copyOfRange(index*128,(index+1)*128)
        if (entry.take(16).all { it == 0.toByte() }) return@mapNotNull null
        val unique = entry.copyOfRange(16,32)
        if (unique.all { it == 0.toByte() } || !guids.add(mtkSha256(unique))) fail("повторяющийся или пустой GUID раздела.")
        val e = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN)
        val first = e.getLong(32); val last = e.getLong(40)
        if (first < a.firstUsable || last < first || last > a.lastUsable) fail("раздел выходит за границы eMMC.")
        val name = String(entry,56,72,Charsets.UTF_16LE).substringBefore('\u0000')
        if (name.isBlank() || !name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' } || !names.add(name)) fail("неверное или повторяющееся имя раздела.")
        MtkPartition(name,first*512,(last-first+1)*512)
    }.sortedBy { it.startBytes }
    if (parts.isEmpty() || parts.zipWithNext().any { (left,right) -> left.startBytes+left.sizeBytes > right.startBytes }) fail("разделы отсутствуют или перекрываются.")
    val canonical = "$userSize\n" + parts.joinToString("\n") { "${it.name}:${it.startBytes}:${it.sizeBytes}" }
    return MtkGptInfo(userSize,parts,mtkSha256(canonical.toByteArray(Charsets.UTF_8)))
}

/** UPLOAD is a terminal-to-host read command, distinct from firmware DOWNLOAD. */
internal class MtkGptReader(private val codec: MtkXflashCodec) {
    fun inspect(userSize: Long): MtkGptInfo {
        val primary = readTable("PGPT",32768)
        val backup = readTable("SGPT",16896)
        return parseMtkGpt(primary,backup,userSize)
    }
    private fun readTable(name: String, expectedSize: Int): ByteArray {
        fun send(bytes: ByteArray) = codec.writePayload(ByteArrayInputStream(bytes),bytes.size.toLong())
        fun word(value: Int) = send(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        word(0x010002); codec.expectSuccess()
        send(name.toByteArray(Charsets.US_ASCII)); codec.expectSuccess()
        val length = codec.readFrame().payload
        if (length.size != 8 || ByteBuffer.wrap(length).order(ByteOrder.LITTLE_ENDIAN).long != expectedSize.toLong())
            throw MtkProtocolException("MTK: неподдерживаемый размер $name. Чтение таблицы остановлено.")
        codec.expectSuccess()
        val bytes = codec.readFrame().payload
        if (bytes.size != expectedSize) throw MtkProtocolException("MTK: неполная таблица $name.")
        word(0); codec.expectSuccess()
        return bytes
    }
}

internal const val MTK_P8_BOARD = "H1502BP_P1"
/** Agenew CheckBOMTypeCommand reads the legacy Android BOOT_A header's 16-byte board/name field. */
internal fun parseMtkBootBoard(bytes: ByteArray): String {
    if (bytes.size < 64 || !bytes.copyOfRange(0,8).contentEquals("ANDROID!".toByteArray(Charsets.US_ASCII)))
        throw MtkProtocolException("MTK: boot_a не содержит поддерживаемый Android boot header.")
    val header=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val page=header.getInt(36); val version=header.getInt(40)
    if (page !in 2048..16384 || page and (page-1) != 0 || version !in 0..2)
        throw MtkProtocolException("MTK: версия boot header не позволяет проверить BOM.")
    val name=String(bytes,48,16,Charsets.US_ASCII).substringBefore('\u0000')
    if (name.isBlank() || !name.all { it.isLetterOrDigit() || it in "_.-" })
        throw MtkProtocolException("MTK: отсутствует поддерживаемый BOM в boot_a.")
    return name
}
internal class MtkBootBoardReader(private val codec: MtkXflashCodec) {
    fun inspect(partition: MtkPartition, userSize: Long): String {
        if (partition.name != "boot_a" || partition.sizeBytes < 4096 || partition.startBytes < 0 || partition.startBytes > userSize-4096)
            throw MtkProtocolException("MTK: boot_a вне проверенной геометрии eMMC.")
        val output=java.io.ByteArrayOutputStream(4096)
        mtkReadUserData(codec,partition.startBytes,4096,4096,output)
        return parseMtkBootBoard(output.toByteArray())
    }
}

internal fun mtkReadUserData(codec: MtkXflashCodec, address: Long, length: Long, packetLength: Int, output: java.io.OutputStream) {
    require(address >= 0 && length > 0 && address <= 31289507840L-length && packetLength in 1..MTK_MAX_FRAME)
    fun send(bytes: ByteArray) = codec.writePayload(ByteArrayInputStream(bytes),bytes.size.toLong())
    fun word(value: Int) = send(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    word(0x010005); codec.expectSuccess()
    // eMMC USER; NAND extension is irrelevant and zeroed rather than copied from host pointers.
    send(ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(8).putLong(address).putLong(length).array())
    codec.expectSuccess(); codec.expectSuccess()
    var remaining=length
    var emptyStatuses=0
    while (remaining>0) {
        val received=codec.readPayload(output,minOf(remaining,packetLength.toLong()))
        if (received==0L) {
            if (++emptyStatuses>8) throw MtkProtocolException("MTK: слишком много статусов readback без данных. Резервное чтение не завершено.")
            continue
        }
        emptyStatuses=0
        remaining-=received
        word(0)
    }
    codec.expectSuccess()
}
