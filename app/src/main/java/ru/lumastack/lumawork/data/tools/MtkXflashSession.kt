package ru.lumastack.lumawork.data.tools

import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.Serializable

@Serializable data class MtkChipInfo(val hwCode: Int, val hwSubCode: Int, val hwVersion: Int, val swVersion: Int, val evolution: Int)
@Serializable data class MtkRamInfo(val sramType: Long, val sramBase: Long, val sramSize: Long, val dramType: Long, val dramBase: Long, val dramSize: Long)
@Serializable data class MtkEmmcInfo(val type: Int, val blockSize: Int, val boot1Size: Long, val boot2Size: Long, val rpmbSize: Long,
    val gp1Size: Long, val gp2Size: Long, val gp3Size: Long, val gp4Size: Long, val userSize: Long)
@Serializable data class MtkTargetInfo(val chip: MtkChipInfo, val ram: MtkRamInfo, val emmc: MtkEmmcInfo,
    val writePacketLength: Int, val readPacketLength: Int, val partitionTableKind: String,
    // Hashed chip/eMMC binding is private journal data, not proof of the product model.
    val fingerprint: String, val deviceFirmwareInfoSize: Int,
    val gpt: MtkGptInfo? = null,
    val boardName: String? = null,
) {
    // This is the same software BOM compatibility field used by Agenew, not a hardware/eFuse attestation.
    val modelConfirmed: Boolean get() = boardName == MTK_P8_BOARD && chip.hwCode == 0x0717 &&
        chip.hwSubCode == 0x8a00 && chip.hwVersion == 0xca01 && chip.swVersion == 0x0200
    val compatible: Boolean get() = modelConfirmed && gpt?.matchesP8Profile == true
}

/** Obtained only for a freshly verified, explicitly requested upgrade. No command retry after failure. */
internal class MtkXflashWriter(private val codec: MtkXflashCodec, private val writeLength: Int,
    private val readLength: Int, private val checkpoint: ()->Unit, private val sleep: (Long)->Unit = { Thread.sleep(it) },
) : MtkUpgradeAgent {
    private var failed=false
    private var finished=false
    private var started=false
    init { require(writeLength in 1..MTK_MAX_FRAME && readLength in 1..MTK_MAX_FRAME) }
    override fun read(partition: MtkPartition, output: OutputStream) = guard {
        mtkReadUserData(codec,partition.startBytes,partition.sizeBytes,readLength,output)
    }
    override fun readLayout(): MtkGptInfo = guard { MtkGptReader(codec).inspect(31289507840L) }
    override fun beginUpgrade() = guard {
        check(!started) { "Журнал Download уже инициализирован. Повтор запрещён." }
        // Original Agenew calls START_DL_INFO before FORMAT. The stock DA's named
        // DOWNLOAD requires this journal; it may update flashinfo during the upgrade.
        word(0x10009);codec.expectSuccess();word(0x80001);codec.expectSuccess();codec.expectSuccess()
        started=true
    }
    override fun format(range: MtkFormatRange) = guard {
        check(started) { "Перед FORMAT нужен подтверждённый START_DL_INFO." }
        require(range.startBytes>=32768 && range.sizeBytes>0 && range.startBytes%512==0L && range.sizeBytes%512==0L &&
            range.startBytes<=31289507840L-16896-range.sizeBytes)
        word(0x10003); codec.expectSuccess()
        send(ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(8).putLong(range.startBytes).putLong(range.sizeBytes).array())
        codec.expectSuccess()
        var result=status()
        var polls=0
        while(result==0x40040004) {
            if (++polls>600) throw MtkProtocolException("MTK: FORMAT не завершён за допустимое число ответов.")
            val wait=status()
            if (wait !in 1..1000) throw MtkProtocolException("MTK: неизвестный интервал ожидания FORMAT.")
            checkpoint(); sleep(wait.toLong()); checkpoint()
            word(0); result=status()
        }
        if(result!=0x40040005) throw MtkProtocolException("MTK: FORMAT не подтвердил завершение, статус 0x%08X.".format(result))
    }
    override fun download(image: MtkImage, confirmed: (Long)->Unit) = guard {
        check(started) { "Перед DOWNLOAD нужен подтверждённый START_DL_INFO." }
        require(image.partitionName in MTK_P8_PROFILE.images.map { it.partitionName })
        require(image.file.isFile && image.file.length()==image.size && image.size>0)
        RandomAccessFile(image.file,"r").use { file ->
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            val buffer=ByteArray(writeLength)
            fun hash(): String {
                while(true) { checkpoint(); val n=file.read(buffer); if(n<0) break; require(n>0); digest.update(buffer,0,n) }
                return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            require(hash()==image.sha256 && file.length()==image.size) { "Образ ${image.file.name} изменился перед DOWNLOAD." }
            file.seek(0)
            word(0x10001); codec.expectSuccess()
            send(image.partitionName.toByteArray(Charsets.US_ASCII))
            send(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(image.size).array()); codec.expectSuccess()
            var done=0L
            while(done<image.size) {
                checkpoint()
                val count=minOf(buffer.size.toLong(),image.size-done).toInt()
                file.readFully(buffer,0,count)
                digest.update(buffer,0,count)
                var checksum=0
                for(i in 0 until count) checksum=(checksum+(buffer[i].toInt() and 255)) and 0xffff
                word(0);word(checksum)
                codec.writePayload(ByteArrayInputStream(buffer,0,count),count.toLong());codec.expectSuccess()
                done+=count; confirmed(done)
            }
            val sentHash=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(sentHash==image.sha256 && file.length()==image.size) { "Образ изменился во время DOWNLOAD. Требуется восстановление." }
            codec.expectSuccess() // Mandatory final partition acknowledgement, distinct from the last block.
        }
    }
    override fun finish() = guard {
        check(started) { "Журнал Download не инициализирован." }
        word(0x10009);codec.expectSuccess();word(0x80005);codec.expectSuccess()
        codec.expectSuccess() // Captured CC_OPTIONAL_DOWNLOAD_ACT has one final status, no query payload.
        word(0x10007);codec.expectSuccess()
        // SDK reboot_option: reboot, watchdog timeout_ms, async, charging/boot, dlbit, RTC; extension defaults0.
        send(ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(1);putInt(3000) }.array())
        codec.expectSuccess()
        finished=true
    }
    private fun status(): Int {
        val bytes=codec.readFrame().payload
        if(bytes.size!=4) throw MtkProtocolException("MTK: неверная длина статуса обновления.")
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }
    private fun word(value: Int)=send(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    private fun send(bytes: ByteArray)=codec.writePayload(ByteArrayInputStream(bytes),bytes.size.toLong())
    private inline fun <T> guard(block: ()->T): T {
        check(!failed && !finished) { "Сеанс обновления завершён. Автоматический повтор запрещён." }
        try { checkpoint();return block() } catch(failure: Throwable) { failed=true;throw failure }
    }
}

internal fun parseMtkRamInfo(bytes: ByteArray): MtkRamInfo {
    if (bytes.size != 48) throw MtkProtocolException("MTK: неподдерживаемый формат GET_RAM_INFO.")
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val values = LongArray(6) { b.long }
    if (values.any { it < 0 } || values[2] == 0L || values[5] == 0L)
        throw MtkProtocolException("MTK: некорректные размеры RAM.")
    return MtkRamInfo(values[0],values[1],values[2],values[3],values[4],values[5])
}
internal fun parseMtkEmmcInfo(bytes: ByteArray): MtkEmmcInfo {
    if (bytes.size != 104) throw MtkProtocolException("MTK: неподдерживаемый формат GET_EMMC_INFO.")
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val type = b.int; val block = b.int
    val sizes = LongArray(8) { b.long }
    if (type != 1 || block != 512 || sizes.any { it < 0 || it % block != 0L } || sizes[7] == 0L)
        throw MtkProtocolException("MTK: неподдерживаемая геометрия eMMC.")
    return MtkEmmcInfo(type, block, sizes[0],sizes[1],sizes[2],sizes[3],sizes[4],sizes[5],sizes[6],sizes[7])
}

/** Default diagnostic mode never grants storage writes. No register/OTP/eFuse/RPMB writes or authentication bypass. */
class MtkXflashSession internal constructor(wire: MtkWire, private val stage2: MtkAgentStage, private val agent: ByteArray,
    private val storageWrites: Boolean=false) {
    private val codec = MtkXflashCodec(wire)
    private var used = false
    private var inspectedTarget: MtkTargetInfo? = null
    private var layoutAttempted = false
    private var upgradeClaimed=false
    internal fun upgradeAgent(target: MtkTargetInfo, checkpoint: ()->Unit): MtkUpgradeAgent {
        check(storageWrites && !upgradeClaimed && layoutAttempted && target===inspectedTarget && target.compatible) {
            "Для записи нужен новый сеанс Firmware Upgrade с подтверждёнными GPT и BOM."
        }
        upgradeClaimed=true;inspectedTarget=null
        return MtkXflashWriter(codec,target.writePacketLength,target.readPacketLength,checkpoint)
    }
    fun inspectTarget(expected: MtkPreloaderInfo? = null): MtkTargetInfo {
        check(!used) { "Диагностический сеанс агента уже завершён. Автоматический повтор запрещён." }
        used = true
        require(agent.size.toLong() == stage2.size && mtkSha256(agent) == stage2.sha256 && stage2.address == 0x40000000L) { "Не совпадает исходная вторая ступень агента." }
        sendWord(0x434e5953)
        sendWord(0x010100)
        // log level 2, no USB log channel, Linux host, no UFS provisioning, reserved=0.
        send(words(2,0,1,0,0)); codec.expectSuccess()
        sendWord(0x010101); sendWord(0); codec.expectSuccess(); codec.expectWord(0x434e5953)
        val connection = query(0x04000a)
        if (!connection.contentEquals("preloader".toByteArray(Charsets.US_ASCII)))
            throw MtkProtocolException("MTK: агент запущен не из проверенного Preloader. BootROM/EMI fallback запрещён.")
        sendWord(0x010008); codec.expectSuccess()
        send(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(stage2.address).putLong(stage2.size).array())
        send(agent); codec.expectSuccess(); codec.expectWord(0x434e5953)
        val firmware = query(0x040013)
        if (firmware.size != 68) throw MtkProtocolException("MTK: неизвестный формат информации/авторизации агента.")
        val sla = query(0x040016)
        if (sla.size != 4 || ByteBuffer.wrap(sla).order(ByteOrder.LITTLE_ENDIAN).int != 0)
            throw MtkProtocolException("MTK: агент требует SLA/неизвестную авторизацию. Диагностика остановлена без обхода защиты.")
        // The firmware-info challenge is not a stable fingerprint and is never persisted or exported.
        val ram = parseMtkRamInfo(query(0x04000c))
        val emmcBytes = query(0x040001)
        val emmc = parseMtkEmmcInfo(emmcBytes)
        val chipBytes = query(0x04000d)
        if (chipBytes.size != 12) throw MtkProtocolException("MTK: неподдерживаемый GET_CHIP_ID.")
        val chipBuffer = ByteBuffer.wrap(chipBytes).order(ByteOrder.LITTLE_ENDIAN)
        fun word() = chipBuffer.short.toInt() and 0xffff
        val chip = MtkChipInfo(word(),word(),word(),word(),word())
        if (chip.hwCode != 0x0717 || expected != null && (chip.hwCode != expected.hwCode || chip.hwSubCode != expected.hwSubCode ||
                chip.hwVersion != expected.hwVersion || chip.swVersion != expected.swVersion))
            throw MtkProtocolException("MTK: идентификаторы агента не совпадают с проверенным Preloader MT6761.")
        val packet = query(0x040007)
        if (packet.size != 8) throw MtkProtocolException("MTK: неподдерживаемый GET_PACKET_LENGTH.")
        val lengths = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val writeLength = lengths.int; val readLength = lengths.int
        if (writeLength !in 1..MTK_MAX_FRAME || readLength !in 1..MTK_MAX_FRAME)
            throw MtkProtocolException("MTK: размер блока агента выходит за проверенный предел 2 МиБ.")
        val category = query(0x040009)
        if (category.size != 4 || ByteBuffer.wrap(category).order(ByteOrder.LITTLE_ENDIAN).int != 100)
            throw MtkProtocolException("MTK: агент не подтвердил тип таблицы GPT.")
        val fingerprint = mtkSha256(chipBytes + emmcBytes.copyOfRange(72,88))
        return MtkTargetInfo(chip, ram, emmc, writeLength, readLength, "GPT", fingerprint, firmware.size).also { inspectedTarget = it }
    }
    fun inspectLayout(target: MtkTargetInfo): MtkTargetInfo {
        check(target === inspectedTarget && !layoutAttempted) { "Для чтения GPT нужен проверенный агент в том же сеансе. Повтор после ошибки запрещён." }
        layoutAttempted = true
        val gpt = MtkGptReader(codec).inspect(target.emmc.userSize)
        val board = if (gpt.matchesP8Profile) MtkBootBoardReader(codec).inspect(gpt.partitions.single { it.name == "boot_a" },target.emmc.userSize) else null
        return target.copy(gpt = gpt,boardName = board).also { inspectedTarget = it }
    }
    private fun query(control: Int): ByteArray {
        // Every mandatory response is acknowledged before the next command.
        sendWord(0x010009); codec.expectSuccess(); sendWord(control); codec.expectSuccess()
        val payload = codec.readFrame().payload
        codec.expectSuccess()
        return payload
    }
    private fun sendWord(word: Int) = send(words(word))
    private fun send(bytes: ByteArray) = codec.writePayload(ByteArrayInputStream(bytes), bytes.size.toLong())
    private fun words(vararg words: Int) = ByteBuffer.allocate(words.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { words.forEach(::putInt) }.array()
}
