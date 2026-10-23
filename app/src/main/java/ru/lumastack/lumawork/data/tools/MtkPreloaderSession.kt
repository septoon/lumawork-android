package ru.lumastack.lumawork.data.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.io.RandomAccessFile
import kotlinx.serialization.Serializable

@Serializable data class MtkPreloaderInfo(val hwCode: Int, val hwSubCode: Int, val hwVersion: Int, val swVersion: Int, val bootloaderVersion: Int) {
    // MT6761 is shared by many products. There is no evidenced model field yet.
    val modelConfirmed: Boolean get() = false
}
/** Read-only allowlist: handshake, GET_BL_VER, GET_HW_CODE, GET_HW_SW_VER. No RAM agent or storage commands. */
class MtkPreloaderSession(private val wire: MtkWire) {
    private var inspected = false
    fun inspect(): MtkPreloaderInfo {
        var readyCount = 0
        while (true) {
            send(0xa0)
            when (wire.readExact(1)[0].toInt() and 255) {
                0x5f -> break
                'R'.code -> {
                    if (++readyCount > 16 || !wire.readExact(4).contentEquals("EADY".toByteArray(Charsets.US_ASCII)))
                        throw MtkProtocolException("MTK: неверный READY или окно handshake закрыто. Переподключите выключенный терминал.")
                }
                else -> throw MtkProtocolException("MTK: неверный ответ handshake.")
            }
        }
        for ((command, expected) in listOf(0x0a to 0xf5, 0x50 to 0xaf, 0x05 to 0xfa)) {
            send(command); expect(expected)
        }
        send(0xfe)
        val bootloader = wire.readExact(1)[0].toInt() and 255
        if (bootloader != 3) throw MtkProtocolException("MTK: версия Preloader не соответствует захвату (ожидалась 3).")
        send(0xfd); expect(0xfd)
        val hw = word(); status()
        if (hw != 0x0717) throw MtkProtocolException("MTK: неподдерживаемый SoC 0x%04X. Требуется MT6761 0x0717.".format(hw))
        send(0xfc); expect(0xfc)
        val sub = word(); val version = word(); val sw = word(); status()
        inspected = true
        return MtkPreloaderInfo(hw, sub, version, sw, bootloader)
    }
    fun loadAgent(agent: File): MtkXflashSession = loadAgent(MTK_P8_PROFILE.agent, agent)

    internal fun loadUpgradeAgent(pkg: MtkFirmwarePackage): MtkXflashSession {
        require(pkg.agentProfile==MTK_P8_PROFILE.agent && pkg.profile==MTK_P8_PROFILE.id)
        return loadAgent(MTK_P8_PROFILE.agent,pkg.agent,storageWrites=true)
    }

    internal fun loadAgent(profile: MtkAgentProfile, agent: File, storageWrites: Boolean=false): MtkXflashSession {
        check(inspected) { "Перед загрузкой агента нужен свежий Preloader handshake в том же USB-сеансе." }
        inspected = false
        MtkFirmwarePackageReader(MTK_P8_PROFILE.copy(agent = profile)).verifyAgent(agent)
        fun stage(part: MtkAgentStage): ByteArray {
            require(part.size in 1..(512L * 1024) && part.offset >= 0)
            return ByteArray(part.size.toInt()).also { bytes ->
                RandomAccessFile(agent, "r").use { file -> file.seek(part.offset); file.readFully(bytes) }
                require(mtkSha256(bytes) == part.sha256) { "Агент изменился перед загрузкой в RAM." }
            }
        }
        val first = stage(profile.stage1)
        val second = stage(profile.stage2)
        require(profile.stage1.address == 0x200000L && profile.stage2.address == 0x40000000L && profile.stage1.signatureSize in 0..first.size)
        send(0xd8); expect(0xd8)
        val security = ByteBuffer.wrap(wire.readExact(4)).order(ByteOrder.BIG_ENDIAN).int
        status()
        if (security and 0xffffff00.toInt() != 0 || security and 0x12 != 0)
            throw MtkProtocolException("MTK: требуется SLA/сертификат или неизвестная авторизация. Загрузка агента остановлена без обхода защиты.")
        send(0xd7); expect(0xd7)
        fun echo(value: Long) {
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()
            wire.writeAll(bytes)
            if (!wire.readExact(4).contentEquals(bytes)) throw MtkProtocolException("MTK: SEND_DA/JUMP_DA не подтвердил параметр.")
        }
        echo(profile.stage1.address); echo(first.size.toLong()); echo(profile.stage1.signatureSize.toLong()); status()
        wire.writeAll(first)
        if (word() != mtkSendDaChecksum(first)) throw MtkProtocolException("MTK: контрольная сумма SEND_DA не совпала.")
        status()
        send(0xd5); expect(0xd5); echo(profile.stage1.address); status(); expect(0xc0)
        return MtkXflashSession(wire, profile.stage2, second, storageWrites)
    }
    private fun send(byte: Int) = wire.writeAll(byteArrayOf(byte.toByte()))
    private fun expect(byte: Int) {
        if ((wire.readExact(1)[0].toInt() and 255) != byte) throw MtkProtocolException("MTK: неверное эхо команды. Сеанс остановлен.")
    }
    private fun word() = ByteBuffer.wrap(wire.readExact(2)).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    private fun status() { if (word() != 0) throw MtkProtocolException("MTK: Preloader не подтвердил запрос идентификаторов.") }
}
