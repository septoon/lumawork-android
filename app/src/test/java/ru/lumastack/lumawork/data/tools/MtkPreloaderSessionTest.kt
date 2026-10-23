package ru.lumastack.lumawork.data.tools

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class MtkPreloaderSessionTest {
    @get:Rule val temp = TemporaryFolder()
    // Full P8 capture, device 5: READY may arrive after the first A0; that A0 is repeated.
    private val captured = "52454144595ff5affa03fd07170000fc8a00ca0102000000".hexBytes()
    @Test fun captured_ready_then_echo_reads_identifiers_without_agent_or_storage_commands() {
        val io = MtkFakeIo(captured)
        val info = MtkPreloaderSession(MtkStreamWire(io)).inspect()
        assertEquals(0x0717, info.hwCode); assertEquals(0x8a00, info.hwSubCode)
        assertEquals(0xca01, info.hwVersion); assertEquals(0x0200, info.swVersion)
        assertEquals(3, info.bootloaderVersion)
        assertFalse(info.modelConfirmed)
        assertArrayEquals("a0a00a5005fefdfc".hexBytes(), io.output.toByteArray())
    }
    @Test fun direct_echo_supported_and_wrong_echo_or_status_stops_before_next_command() {
        val direct = MtkFakeIo(captured.drop(5).toByteArray())
        MtkPreloaderSession(MtkStreamWire(direct)).inspect()
        assertArrayEquals("a00a5005fefdfc".hexBytes(), direct.output.toByteArray())
        listOf("00", "5245414458", "5ff5affa03fd07170001", "5ff5affa03fd07170000fc8a00ca0102000001").forEach { input ->
            assertThrows(Exception::class.java) { MtkPreloaderSession(MtkStreamWire(MtkFakeIo(input.hexBytes()))).inspect() }
        }
    }
    @Test fun endless_ready_and_unexpected_soc_do_not_imply_compatible_model() {
        assertThrows(MtkProtocolException::class.java) { MtkPreloaderSession(MtkStreamWire(MtkFakeIo("READY".repeat(17).toByteArray()))).inspect() }
        val io = MtkFakeIo(captured.copyOf().apply { this[12] = 0x12; this[13] = 0x34 })
        assertThrows(MtkProtocolException::class.java) { MtkPreloaderSession(MtkStreamWire(io)).inspect() }
    }
    @Test fun signed_agent_uses_verified_stage_offsets_send_da_big_endian_and_xor_checksum() {
        val agentBytes = byteArrayOf(1,2,3,4)
        val agent = temp.newFile().apply { writeBytes(agentBytes) }
        val profile = MtkAgentProfile(4, mtkSha256(agentBytes),
            MtkAgentStage(0,2,mtkSha256(byteArrayOf(1,2)),0x200000,1),
            MtkAgentStage(2,2,mtkSha256(byteArrayOf(3,4)),0x40000000,0))
        val io = MtkFakeIo(captured.drop(5).toByteArray() + "d8000000050000d7002000000000000200000001000002010000d5002000000000c0".hexBytes())
        val session = MtkPreloaderSession(MtkStreamWire(io))
        session.inspect(); session.loadAgent(profile, agent)
        assertArrayEquals("a00a5005fefdfcd8d70020000000000002000000010102d500200000".hexBytes(), io.output.toByteArray())
        assertThrows(IllegalStateException::class.java) { session.loadAgent(profile, agent) }
    }
    @Test fun authentication_requirement_and_changed_agent_stop_before_send_da() {
        val bytes = byteArrayOf(1,2,3,4)
        val agent = temp.newFile().apply { writeBytes(bytes) }
        val profile = MtkAgentProfile(4,mtkSha256(bytes), MtkAgentStage(0,2,mtkSha256(byteArrayOf(1,2)),0x200000,1), MtkAgentStage(2,2,mtkSha256(byteArrayOf(3,4)),0x40000000,0))
        val io = MtkFakeIo(captured.drop(5).toByteArray() + "d8000000020000".hexBytes())
        val session = MtkPreloaderSession(MtkStreamWire(io)); session.inspect()
        assertThrows(MtkProtocolException::class.java) { session.loadAgent(profile,agent) }
        assertFalse(io.output.toByteArray().contains(0xd7.toByte()))
        val changedIo = MtkFakeIo(captured.drop(5).toByteArray())
        val changed = MtkPreloaderSession(MtkStreamWire(changedIo)); changed.inspect()
        agent.writeBytes(byteArrayOf(9,9,9,9))
        assertThrows(IllegalArgumentException::class.java) { changed.loadAgent(profile,agent) }
        assertFalse(changedIo.output.toByteArray().contains(0xd8.toByte()))
    }
    private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
