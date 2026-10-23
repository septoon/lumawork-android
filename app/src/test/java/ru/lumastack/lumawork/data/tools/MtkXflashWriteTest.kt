package ru.lumastack.lumawork.data.tools

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MtkXflashWriteTest {
    @get:Rule val temp=TemporaryFolder()
    private fun writer(input: ByteArray,begin: Boolean=false,sleep: (Long)->Unit={}): Pair<MtkXflashWriter,MtkFakeIo> {
        val io=MtkFakeIo((if(begin) mtkStatus()+mtkStatus()+mtkStatus() else byteArrayOf())+input,511)
        val writer=MtkXflashWriter(MtkXflashCodec(MtkStreamWire(io)),4,8,{},sleep)
        if(begin) { writer.beginUpgrade();io.output.reset() }
        return writer to io
    }
    @Test fun download_info_initialization_is_mandatory_once_and_requires_all_captured_statuses() {
        val (writer,io)=writer(mtkStatus()+mtkStatus()+mtkStatus())
        assertThrows(IllegalStateException::class.java) { writer.format(MtkFormatRange(32768,4096)) }
        assertEquals(0,io.output.size())
        val (ready,out)=writer(mtkStatus()+mtkStatus()+mtkStatus())
        ready.beginUpgrade()
        assertEquals(listOf(0x10009,0x80001),frames(out.output.toByteArray()).map(::word))
        assertThrows(IllegalStateException::class.java) { ready.beginUpgrade() }
        val (missing,failed)=writer(mtkStatus()+mtkStatus())
        assertThrows(Exception::class.java) { missing.beginUpgrade() }
        val count=failed.output.size()
        assertThrows(IllegalStateException::class.java) { missing.format(MtkFormatRange(32768,4096)) }
        assertEquals(count,failed.output.size())
    }
    @Test fun format_continue_contains_delay_not_percentage_and_requires_complete() {
        val delays=mutableListOf<Long>()
        val (writer,io)=writer(mtkStatus()+mtkStatus()+mtkStatus(0x40040004)+mtkStatus(100)+mtkStatus(0x40040005),begin=true,sleep={delays+=it})
        writer.format(MtkFormatRange(32768,4096))
        assertEquals(listOf(100L),delays)
        val frames=frames(io.output.toByteArray())
        assertEquals(0x10003,word(frames[0])); assertEquals(56,frames[1].size); assertEquals(0,word(frames[2]))
        val (broken,out)=writer(mtkStatus()+mtkStatus()+mtkStatus(0x12345678),begin=true)
        assertThrows(MtkProtocolException::class.java) { broken.format(MtkFormatRange(32768,4096)) }
        val size=out.output.size()
        assertThrows(IllegalStateException::class.java) { broken.finish() }
        assertEquals(size,out.output.size())
    }
    @Test fun named_download_checks_original_bytes_each_block_ack_and_final_partition_ack() {
        val bytes=byteArrayOf(-1,2,3,4,5,6,7,8,9)
        val file=temp.newFile().apply { writeBytes(bytes) }
        val image=MtkImage("logo",file,bytes.size.toLong(),mtkSha256(bytes))
        val (writer,io)=writer((1..6).fold(byteArrayOf()) { a,_->a+mtkStatus() },begin=true)
        val confirmed=mutableListOf<Long>()
        writer.download(image) { confirmed+=it }
        assertEquals(listOf(4L,8L,9L),confirmed)
        val frames=frames(io.output.toByteArray())
        assertEquals(0x10001,word(frames[0])); assertEquals("logo",String(frames[1]))
        assertArrayEquals(mtkLongs(9),frames[2])
        assertEquals(264,word(frames[4])); assertArrayEquals(bytes.copyOfRange(0,4),frames[5])
        val (missing,_)=writer((1..5).fold(byteArrayOf()) { a,_->a+mtkStatus() },begin=true)
        assertThrows(Exception::class.java) { missing.download(image) {} }
        val (changed,empty)=writer(byteArrayOf(),begin=true)
        assertThrows(IllegalArgumentException::class.java) { changed.download(image.copy(sha256="wrong")) {} }
        assertEquals(0,empty.output.size())
    }
    @Test fun finalizer_matches_capture_and_missing_last_ack_never_succeeds() {
        val input=(1..5).fold(byteArrayOf()) { a,_->a+mtkStatus() }
        val (writer,io)=writer(input,begin=true)
        writer.finish()
        val frames=frames(io.output.toByteArray())
        assertEquals(listOf(0x10009,0x80005,0x10007),frames.take(3).map(::word))
        assertArrayEquals(mtkWords(1,3000,0,0,0,0,0),frames[3])
        val (missing,_)=writer(input.dropLast(16).toByteArray(),begin=true)
        assertThrows(Exception::class.java) { missing.finish() }
    }
    @Test fun user_readback_streams_multiple_frames_and_acknowledges_each() {
        val input=mtkStatus()+mtkStatus()+mtkStatus()+mtkFrame(ByteArray(8) { 1 })+mtkFrame(ByteArray(8) { 2 })+mtkStatus()
        val (writer,io)=writer(input)
        val output=ByteArrayOutputStream()
        writer.read(MtkPartition("nvram",32768,16),output)
        assertArrayEquals(ByteArray(8) { 1 }+ByteArray(8) { 2 },output.toByteArray())
        assertEquals(2,frames(io.output.toByteArray()).drop(2).size)
    }
    @Test fun premature_status_is_not_treated_as_readback_bytes_or_acknowledged_as_data() {
        val (writer,io)=writer(mtkStatus()+mtkStatus()+mtkStatus()+mtkStatus(0x12345678))
        val output=ByteArrayOutputStream()
        val error=assertThrows(MtkProtocolException::class.java) { writer.read(MtkPartition("nvram",32768,16),output) }
        assertTrue(error.message.orEmpty().contains("0x12345678"))
        assertEquals(0,output.size())
        assertEquals(2,frames(io.output.toByteArray()).size)
    }
    @Test fun zero_read_status_between_payloads_is_not_data_or_an_ack_request() {
        val input=mtkStatus()+mtkStatus()+mtkStatus()+mtkStatus()+mtkFrame(ByteArray(8) { 1 })+
            mtkStatus()+mtkFrame(ByteArray(8) { 2 })+mtkStatus()
        val (writer,io)=writer(input)
        val output=ByteArrayOutputStream()
        writer.read(MtkPartition("nvram",32768,16),output)
        assertArrayEquals(ByteArray(8) { 1 }+ByteArray(8) { 2 },output.toByteArray())
        assertEquals(4,frames(io.output.toByteArray()).size)
    }
    @Test fun endless_zero_read_status_cannot_complete_backup_or_acknowledge_data() {
        val input=(1..12).fold(byteArrayOf()) { bytes,_->bytes+mtkStatus() }
        val (writer,io)=writer(input)
        val output=ByteArrayOutputStream()
        assertThrows(MtkProtocolException::class.java) { writer.read(MtkPartition("nvram",32768,16),output) }
        assertEquals(0,output.size())
        assertEquals(2,frames(io.output.toByteArray()).size)
        assertThrows(IllegalStateException::class.java) { writer.finish() }
    }
    private fun word(b: ByteArray)=java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
    private fun frames(bytes: ByteArray): List<ByteArray> {
        val codec=MtkXflashCodec(MtkStreamWire(MtkFakeIo(bytes,16384)))
        val result=mutableListOf<ByteArray>(); var offset=0
        while(offset<bytes.size) { val b=codec.readFrame().payload;result+=b;offset+=12+b.size }
        return result
    }
}
