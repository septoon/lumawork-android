package ru.lumastack.lumawork.data.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

internal fun mtkWords(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putInt) }.array()
internal fun mtkLongs(vararg values: Long) = ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putLong) }.array()
internal fun mtkFrame(payload: ByteArray) = mtkWords(0xfeeeeeef.toInt(), 1, payload.size) + payload
internal fun mtkStatus(value: Int = 0) = mtkFrame(mtkWords(value))

class MtkXflashSessionTest {
    @Test fun diagnostic_agent_and_unbound_target_cannot_obtain_storage_write_capability() {
        val io=MtkFakeIo(initialized()+target())
        val session=MtkXflashSession(MtkStreamWire(io),stage,stage2)
        val inspected=session.inspectTarget()
        val before=io.output.size()
        assertThrows(IllegalStateException::class.java) { session.upgradeAgent(inspected) {} }
        assertEquals(before,io.output.size())
        val upgrade=MtkXflashSession(MtkStreamWire(MtkFakeIo(initialized()+target())),stage,stage2,storageWrites=true)
        upgrade.inspectTarget()
        assertThrows(IllegalStateException::class.java) { upgrade.upgradeAgent(mtkWriteTarget()) {} }
    }
    private val stage2 = byteArrayOf(3,4,5)
    private val stage = MtkAgentStage(0, 3, mtkSha256(stage2), 0x40000000, 0)
    private fun query(payload: ByteArray) = mtkStatus() + mtkStatus() + mtkFrame(payload) + mtkStatus()
    private fun initialized() = mtkStatus() + mtkStatus() + mtkStatus(0x434e5953) +
        query("preloader".toByteArray()) + mtkStatus() + mtkStatus() + mtkStatus(0x434e5953)
    private fun target(sla: Int = 0, hw: Int = 0x0717): ByteArray {
        val fw = ByteArray(68)
        val ram = mtkLongs(1,0x200000,0x40000,2,0x40000000,0x80000000)
        val emmc = mtkWords(1,512) + mtkLongs(0x400000,0x400000,0x400000,0,0,0,0,0x7454f8000) + ByteArray(32)
        val chip = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putShort(hw.toShort()).putShort(0x8a00.toShort()).putShort(0xca01.toShort()).putShort(0x0200).putShort(1).putShort(0).array()
        return query(fw) + query(mtkWords(sla)) + query(ram) + query(emmc) + query(chip) + query(mtkWords(2097152,2097152)) + query(mtkWords(100))
    }
    @Test fun agent_initialization_and_queries_parse_runtime_values_without_storage_writes() {
        val io = MtkFakeIo(initialized() + target(), chunk = 19)
        val info = MtkXflashSession(MtkStreamWire(io), stage, stage2).inspectTarget()
        assertEquals(0x0717, info.chip.hwCode)
        assertEquals(0x80000000L, info.ram.dramSize)
        assertEquals(0x7454f8000L, info.emmc.userSize)
        assertEquals(2097152, info.writePacketLength)
        assertEquals("GPT", info.partitionTableKind)
        assertFalse(info.modelConfirmed); assertFalse(info.compatible)
        assertEquals(64, info.fingerprint.length)
        val frames = sentFrames(io.output.toByteArray())
        assertArrayEquals(mtkWords(0x434e5953), frames[0])
        assertArrayEquals(mtkWords(2,0,1,0,0), frames[2])
        assertTrue(frames.any { it.contentEquals(mtkLongs(0x40000000,3)) })
        assertTrue(frames.any { it.contentEquals(stage2) })
        val forbidden = listOf(0x010001,0x010003,0x010004,0x01000d,0x01000e)
        assertFalse(frames.any { it.size == 4 && ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int in forbidden })
    }
    @Test fun sla_authorization_stops_before_ram_or_storage_queries_and_cannot_be_retried() {
        val io = MtkFakeIo(initialized() + target(sla = 1))
        val session = MtkXflashSession(MtkStreamWire(io), stage, stage2)
        assertThrows(MtkProtocolException::class.java) { session.inspectTarget() }
        val before = io.output.size()
        assertThrows(IllegalStateException::class.java) { session.inspectTarget() }
        assertEquals(before, io.output.size())
        assertFalse(sentFrames(io.output.toByteArray()).any { it.contentEquals(mtkWords(0x04000c)) })
    }
    @Test fun wrong_soc_same_usb_id_is_not_a_compatible_target() {
        val io = MtkFakeIo(initialized() + target(hw = 0x1234))
        assertThrows(MtkProtocolException::class.java) { MtkXflashSession(MtkStreamWire(io), stage, stage2).inspectTarget() }
        assertFalse(sentFrames(io.output.toByteArray()).any { it.contentEquals(mtkWords(0x010003)) })
    }
    @Test fun modified_second_stage_and_wrong_sync_fail_before_following_commands() {
        val io = MtkFakeIo(initialized() + target())
        assertThrows(IllegalArgumentException::class.java) { MtkXflashSession(MtkStreamWire(io), stage, byteArrayOf(9,9,9)).inspectTarget() }
        assertEquals(0, io.output.size())
        val wrong = MtkFakeIo(mtkStatus() + mtkStatus() + mtkStatus(7))
        assertThrows(MtkProtocolException::class.java) { MtkXflashSession(MtkStreamWire(wrong), stage, stage2).inspectTarget() }
        assertFalse(sentFrames(wrong.output.toByteArray()).any { it.contentEquals(mtkWords(0x010008)) })
    }
    @Test fun truncated_ram_and_negative_sizes_are_rejected() {
        assertThrows(MtkProtocolException::class.java) { parseMtkRamInfo(ByteArray(16)) }
        assertThrows(MtkProtocolException::class.java) { parseMtkRamInfo(mtkLongs(1,0,1,2,0,-1)) }
        assertThrows(MtkProtocolException::class.java) { parseMtkEmmcInfo(ByteArray(104)) }
    }
    @Test fun captured_geometry_parses_without_caching_runtime_pointers_or_exposing_cid() {
        // Captured replies; CID, firmware version and reserved eMMC bytes are removed from the fixture.
        fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val ram = parseMtkRamInfo("020000000000000000002000000000000000040000000000010000000000000000000040000000000000000001000000".hex())
        val emmc = parseMtkEmmcInfo("0100000000020000000040000000000000004000000000000000000100000000000000000000000000000000000000000000000000000000000000000000000000000049070000000000000000000000000000000000000000000000000000000000000000000000".hex())
        assertEquals(4294967296L, ram.dramSize)
        assertEquals(31289507840L, emmc.userSize)
        assertEquals(16777216L, emmc.rpmbSize)
    }
    private fun sentFrames(bytes: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>(); var pos = 0
        while (pos < bytes.size) {
            val header = ByteBuffer.wrap(bytes,pos,12).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0xfeeeeeef.toInt(), header.int); assertEquals(1,header.int)
            val size = header.int; frames += bytes.copyOfRange(pos+12,pos+12+size); pos += 12+size
        }
        return frames
    }
}
