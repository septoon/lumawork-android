package ru.lumastack.lumawork.data.tools

import org.junit.Assert.*
import org.junit.Test

class PaxCompressedArchiveTest {
    @Test fun `7z signature and FTP source are accepted`() {
        assertTrue(PaxCompressedArchive.hasSignature(byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c, 0, 4)))
        assertTrue("firmware.7Z".isPaxArchiveName())
        assertFalse("firmware.7z.exe".isPaxArchiveName())
    }

    @Test fun `RAR4 and RAR5 signatures are distinct from ZIP and fake rar suffixes`() {
        assertTrue(PaxCompressedArchive.hasSignature(byteArrayOf(82,97,114,33,26,7,0)))
        assertTrue(PaxCompressedArchive.hasSignature(byteArrayOf(82,97,114,33,26,7,1,0)))
        assertFalse(PaxCompressedArchive.hasSignature(byteArrayOf(82,97,114,33,26,7,1)))
        assertFalse(PaxCompressedArchive.hasSignature("PK fake.rar".toByteArray()))
    }
    @Test fun `RAR paths preserve model folders and normalize windows separators`() {
        assertEquals("base/Pax_Prolin_Nuxiums/MAINAPP.aip", PaxCompressedArchive.checkedPath("base\\Pax_Prolin_Nuxiums\\MAINAPP.aip", false))
        assertEquals("soft PP pax s210/APP", PaxCompressedArchive.checkedPath("soft PP pax s210/APP/", true))
    }
    @Test fun `RAR traversal absolute paths and alternate data streams are rejected`() {
        listOf("../outside", "/root/file", "C:\\file", "folder/../file", "folder//file", "file:stream", "folder/./file", "file\u0000").forEach { path ->
            assertTrue(path, runCatching { PaxCompressedArchive.checkedPath(path, false) }.isFailure)
        }
    }
    @Test fun `FTP source accepts rar only in the PAX firmware tree`() {
        fun source(path: String, name: String) = LocalToolFile("id", name, 1, 0, 0, "local", remotePath = path).isPaxFirmwareSource()
        assertTrue(source("$PAX_FIRMWARE_FTP_PATH/version/Firmware.RAR", "Firmware.RAR"))
        assertTrue(source("$PAX_FIRMWARE_FTP_PATH/version/Firmware.zip", "Firmware.zip"))
        assertFalse(source("/other/Firmware.rar", "Firmware.rar"))
        assertFalse(source("$PAX_FIRMWARE_FTP_PATH/version/file.exe", "file.exe"))
    }
}
