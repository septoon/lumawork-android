package ru.lumastack.lumawork.data.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

internal fun mtkGptFixture(userSize: Long = 31289507840L, name: String = "proinfo", first: Long = 64, last: Long = 127): Pair<ByteArray,ByteArray> {
    val total = userSize / 512
    val entries = ByteArray(16384)
    ByteBuffer.wrap(entries).order(ByteOrder.LITTLE_ENDIAN).apply {
        putLong(1); position(16); putLong(2); position(32); putLong(first); putLong(last)
        position(56); put(name.toByteArray(Charsets.UTF_16LE))
    }
    fun header(current: Long, other: Long, table: Long): ByteArray {
        val h = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        h.put("EFI PART".toByteArray()); h.putInt(0x10000); h.putInt(92); h.putInt(0); h.putInt(0)
        h.putLong(current); h.putLong(other); h.putLong(34); h.putLong(total-34)
        h.putLong(3); h.putLong(0); h.putLong(table); h.putInt(128); h.putInt(128)
        h.putInt(CRC32().apply { update(entries) }.value.toInt())
        val bytes = h.array(); val crc = CRC32().apply { update(bytes,0,92) }.value.toInt()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(16,crc)
        return bytes
    }
    val primary = ByteArray(32768)
    header(1,total-1,2).copyInto(primary,512); entries.copyInto(primary,1024)
    val backup = entries + header(total-1,1,total-33)
    return primary to backup
}
class MtkGptTest {
    @Test fun boot_header_bom_is_read_only_from_the_verified_profile_partition() {
        val header=ByteArray(4096)
        "ANDROID!".toByteArray().copyInto(header)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(36,2048).putInt(40,2)
        "H1502BP_P1".toByteArray().copyInto(header,48)
        assertEquals("H1502BP_P1",parseMtkBootBoard(header))
        val io=MtkFakeIo(mtkStatus()+mtkStatus()+mtkStatus()+mtkFrame(header)+mtkStatus())
        val board=MtkBootBoardReader(MtkXflashCodec(MtkStreamWire(io))).inspect(MtkPartition("boot_a",0x19000000,0x2000000),31289507840L)
        assertEquals("H1502BP_P1",board)
        val bad=header.copyOf().apply { this[0]=0 }
        assertThrows(MtkProtocolException::class.java) { parseMtkBootBoard(bad) }
        assertThrows(MtkProtocolException::class.java) { MtkBootBoardReader(MtkXflashCodec(MtkStreamWire(MtkFakeIo()))).inspect(MtkPartition("boot_a",Long.MAX_VALUE,4096),31289507840L) }
    }
    @Test fun verifies_both_headers_crc_entries_and_disk_geometry() {
        val (primary,backup) = mtkGptFixture()
        val result = parseMtkGpt(primary,backup,31289507840L)
        assertEquals("proinfo",result.partitions.single().name)
        assertEquals(32768L,result.partitions.single().startBytes)
        assertEquals(32768L,result.partitions.single().sizeBytes)
        assertFalse(result.matchesP8Profile)
        assertEquals(64,result.layoutSha256.length)
    }
    @Test fun corrupted_header_entries_backup_and_geometry_are_rejected() {
        val (primary,backup) = mtkGptFixture()
        for ((a,b,size) in listOf(Triple(primary.copyOf().apply { this[528]=1 },backup,31289507840L),
            Triple(primary.copyOf().apply { this[1100]=1 },backup,31289507840L),
            Triple(primary,backup.copyOf().apply { this[16384]=0 },31289507840L),
            Triple(primary,backup,31289507840L+512))) {
            assertThrows(MtkProtocolException::class.java) { parseMtkGpt(a,b,size) }
        }
    }
    @Test fun out_of_range_and_reversed_partitions_are_rejected_even_with_valid_crc() {
        for ((first,last) in listOf(1L to 100L,100L to 99L,64L to 999999999L)) {
            val (p,b)=mtkGptFixture(first=first,last=last)
            assertThrows(MtkProtocolException::class.java) { parseMtkGpt(p,b,31289507840L) }
        }
    }
    @Test fun upload_layout_requires_both_primary_and_backup_acknowledgements() {
        val (p,b)=mtkGptFixture()
        val response = mtkStatus()+mtkStatus()+mtkFrame(mtkLongs(p.size.toLong()))+mtkStatus()+mtkFrame(p)+mtkStatus()+
            mtkStatus()+mtkStatus()+mtkFrame(mtkLongs(b.size.toLong()))+mtkStatus()+mtkFrame(b)+mtkStatus()
        val io=MtkFakeIo(response,chunk=511)
        val result=MtkGptReader(MtkXflashCodec(MtkStreamWire(io))).inspect(31289507840L)
        assertEquals("proinfo",result.partitions.single().name)
        val missing=MtkFakeIo(response.dropLast(16).toByteArray())
        assertThrows(Exception::class.java) { MtkGptReader(MtkXflashCodec(MtkStreamWire(missing))).inspect(31289507840L) }
    }
}
