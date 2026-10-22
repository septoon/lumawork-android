/* Embedded RAR5 fixtures and verification pattern:
 * Copyright (c) 2018 Grzegorz Antoniak
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR(S) ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR(S) BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package ru.lumastack.lumawork.data.tools

import android.app.Instrumentation
import android.os.Bundle
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry

/** Runs the actual JNI decoder on Android; no USB or firmware service is involved. */
class RarImportInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val directory = File(targetContext.cacheDir, "rar-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val app = byteArrayOf(1, 2, 3, 4)
            val os = byteArrayOf(5, 6, 7)
            val archive = File(directory, "package.rar")
            archive.writeBytes(rar4(linkedMapOf("wrapper/soft PP pax s210/APP/S210_new.APP" to app,
                "wrapper/soft PP pax s210/OS/S210_OS.BIN" to os)))
            val prepared = archive.inputStream().use { PaxArchiveCatalog.prepare(it, File(directory, "import"), PaxModel.S210) }
            check(prepared.application!!.file.readBytes().contentEquals(app))
            check(prepared.os!!.file.readBytes().contentEquals(os))
            check(prepared.model == PaxModel.S210)
            check(directory.listFiles()!!.none { it.name.startsWith("pax-rar-") || it.name.startsWith("pax-catalog-") })
            val badModel = File(directory, "wrong-model")
            check(runCatching { archive.inputStream().use { PaxArchiveCatalog.prepare(it, badModel, PaxModel.D270) } }.isFailure)
            check(!badModel.exists())
            val seven = File(directory, "firmware.7z")
            // Cyrillic entry names generated with bsdtar; Android writer also depends on locale.
            seven.writeBytes(Base64.decode("N3q8ryccAAOsV8fV6QAAAAAAAAAiAAAAAAAAAOLNdCQAAoG9YXK19xGNq3//+IMgAAAAgTMHrg/PBHGMB8hDgIOBW/+sds94Pw5fNkVjb4D3Yyp0QXh8Vn2G2+79SlTlez+SOHHbpDeztY9bnNhkn8hEYAApUA/t9ij4oEOXQSgiWxekeRBvY3cg+DiU1GKFhFJcMOTtD4SwcTFxbSo24lK/oySl0FN4PAfZa9cjx87jf6y2BAAAmwZzU5uJTbAsy+a272xJ4reRODqpdVEWTmnDeEv3kjgV4q0NxPrxyX6EqVGDigZ7lwqNyOTCymb4ZYZMyHzMMBwgdK3rB3dOKq5IQeL++l/pgBcGEQEJgNgABwsBAAEjAwEBBV0AAIAADIJ2CgE4BX9xAAA=", Base64.DEFAULT))
            val fromSeven = seven.inputStream().use { PaxArchiveCatalog.prepare(it, File(directory, "7z-import"), PaxModel.S210) }
            check(fromSeven.application!!.file.readBytes().contentEquals(app))
            check(fromSeven.os!!.file.readBytes().contentEquals(os))
            val wrapper = File(directory, "ftp-folder.zip")
            ZipOutputStream(wrapper.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("FTP папка/прошивка.7z")); output.write(seven.readBytes()); output.closeEntry()
            }
            val fromFtp = wrapper.inputStream().use { PaxArchiveCatalog.prepare(it, File(directory, "ftp-import"), PaxModel.S210) }
            check(fromFtp.application!!.sha256 == fromSeven.application.sha256)
            check(fromFtp.os!!.sha256 == fromSeven.os.sha256)
            check(directory.listFiles()!!.none { it.name.startsWith("pax-nested-") || it.name.startsWith("pax-unpacked-") || it.name.startsWith("pax-catalog-") })
            sevenZip(seven, mapOf("../outside" to app))
            check(runCatching { PaxCompressedArchive.convert(seven, File(directory, "7z-unsafe.zip")) }.isFailure)
            check(!File(directory, "7z-unsafe.zip").exists())
            sevenZip(seven, mapOf("test" to app))
            seven.writeBytes(seven.readBytes().copyOf(20))
            check(runCatching { PaxCompressedArchive.convert(seven, File(directory, "7z-broken.zip")) }.isFailure)
            // libarchive BSD-licensed test fixture: libarchive/test/test_read_format_rar5_stored.rar.uu
            // https://github.com/libarchive/libarchive/blob/master/libarchive/test/test_read_format_rar5_stored.rar.uu
            val rar5 = File(directory, "v5.rar").apply { writeBytes(Base64.decode("UmFyIRoHAQAzkrXlCgEFBgAFAQGAgAA4MAZjLAIDC50ABJ0ApIMCtEOglYAAAQ5oZWxsb3dvcmxkLnR4dAoDE34Oq1tW6Q4aaGVsbG8gbGliYXJjaGl2ZSB0ZXN0IHN1aXRlIQodd1ZRAwUEAA==", Base64.DEFAULT)) }
            val zip = File(directory, "v5.zip")
            PaxCompressedArchive.convert(rar5, zip)
            ZipFile(zip).use { check(it.getInputStream(it.getEntry("helloworld.txt")).bufferedReader().readText() == "hello libarchive test suite!\n") }
            // Additional fixtures from the same libarchive BSD-licensed test suite.
            rar5.writeBytes(Base64.decode("UmFyIRoHAQDz4YLrCwEFBwAGAQGAgIAAicZf2iYCAwvpAgSwCaSDAs1wynyABQEIdGVzdC5iaW4KAxOLV6xb+BitHsr0ZQEnZWBUH1V2Xb+UknHJz2WWWQxw2WWTGMWSE4ZCSSThkJCSSSQk45JJIQhJJCEJJJCSSEISEs21b3t9Mw6AfR0Drj+eup/3Qf732vfdea86X+gAQAEAyCCfn49vX08vHv7uzr6uno5+Xk4+Lh4N/d3NrZ19bV1NPS0M/NzMrJyMfGxcTDwb+9vLu5uLe2tbSzsrGvrq2rqqmnpaSjoqGgn52cm5mXlpSTSyUjIR6FAfjoyKiYc6cNmoaEgoB+fHt6eHZ0c3Jxb21raWdlY2FfXVtZV1VTITf1/I/QJT6JTn+ESXRYowZ5YXajgppimUF+ZC0XadRjLDOMCoR/+glMZpCl9lUKxWVguLAZFoNy4HdeEGwA8xBIyCeZhWNAumoZbYN5uHg4Bgcg+uYROgpnYYDwNt6Hu9h1fQjfxYQI0YMecKHk0Jg2L44OY6HCIEqeGFFjtjQ/nxUIBsIQ0IhNIxnJAvJQAdd1ZRAwUEAA==", Base64.DEFAULT))
            PaxCompressedArchive.convert(rar5, zip)
            ZipFile(zip).use { result ->
                val data = result.getInputStream(result.getEntry("test.bin")).readBytes()
                check(data.size == 1200)
                val values = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                for (k in 1..300) check(values.int == (k * k - 3 * k + 1).coerceAtLeast(0))
            }
            rar5.writeBytes(Base64.decode("UmFyIRoHAQAzkrXlCgEFBgAFAQGAgAA1/zIeHwIDCxIEEiBVblrugAAABWEudHh0CgMCfCGEo4J82gFUaGlzIGlzIGZyb20gYS50eHR6QXGzUAIDPDAEEiAYRaMCgAMABWIudHh0MAEAAw/HRF7hgPi1n9YrQzcIvFfN+jTfBqtdBdSTB3cPHY6AU+o1Zx1w8S1La5wanAoDAnJlmKaCfNoBY7pUstAQpUlftLrTwGmoOxu8uzgn14eTeHMvfJPi0ijEAN7+psA7fmxn1AVQANz4jXzxZh8CAwsSBBIgNT2alIAAAAVjLnR4dAoDAj5I6a6CfNoBVGhpcyBpcyBmcm9tIGMudHh07P3wXVICAzygAASSACDD32MSgAMABWQudHh0MAEAAw/HRF7hgPi1n9YrQzcIvFfN883Wsxy0W6d8rDZYjrMOaYNM1EjpFevWrHe3owoDAnvFKfSPfNoB22ly9PZWjqb9GWXplbM2hTQ39Sqg0yeHsvXBAjLYql4dd1ZRAwUEAA==", Base64.DEFAULT))
            check(runCatching { PaxCompressedArchive.convert(rar5, File(directory, "encrypted.zip")) }.isFailure)
            check(!File(directory, "encrypted.zip").exists())
            archive.writeBytes(rar4(mapOf("../outside" to app)))
            check(runCatching { PaxCompressedArchive.convert(archive, File(directory, "unsafe.zip")) }.isFailure)
            check(!File(directory, "unsafe.zip").exists())
            check(!File(directory.parentFile, "outside").exists())
            rar5.writeBytes(Base64.decode("UmFyIRoHAQDz4YLrCwEFBwAGAQGAgIAAicZf2iYCAwvpAgSwCaSDAs1wynyABQEIdGVzdC5iaW4KAxOLV6xb+BitHsr0ZQEnZWBUH1V2Xb+UknHJz2WWWQxw2WWTGMWSE4ZCSSThkJCSSSQk45JJIQhJJCEJJJCSSEISEs21b3t9Mw6AfR0Drj+eup/3Qf732vfdea86X+gAQAEAyCCfn49vX08vHv7uzr6uno5+Xk4+Lh4N/d3NrZ19bV1NPS0M/NzMrJyMfGxcTDwb+9vLu5uLe2tbSzsrGvrq2rqqmnpaSjoqGgn52cm5mXlpSTSyUjIR6FAfjoyKiYc6cNmoaEgoB+fHt6eHZ0c3Jxb21raWdlY2FfXVtZV1VTITf1/I/QJT6JTn+ESXRYowZ5YXajgppimUF+ZC0XadRjLDOMCoR/+glMZpCl9lUKxWVguLAZFoNy4HdeEGwA8xBIyCeZhWNAumoZbYN5uHg4Bgcg+uYROgpnYYDwNt6Hu9h1fQjfxYQI0YMecKHk0Jg2L44OY6HCIEqeGFFjtjQ/nxUIBsIQ0IhNIxnJAvJQAdd1ZRAwUEAA==", Base64.DEFAULT))
            val corrupt = rar5.readBytes().also { it[100] = (it[100].toInt() xor 127).toByte() }
            rar5.writeBytes(corrupt)
            check(runCatching { PaxCompressedArchive.convert(rar5, File(directory, "corrupt.zip")) }.isFailure)
            check(!File(directory, "corrupt.zip").exists())
            val volume = rar4(mapOf("file" to app)).also { it[10] = 1 }
            archive.writeBytes(volume)
            check(runCatching { PaxCompressedArchive.convert(archive, File(directory, "volume.zip")) }.exceptionOrNull()?.message?.contains("Многотомный") == true)
            archive.writeBytes(rar4(mapOf("same" to app, "SAME" to os)))
            check(runCatching { PaxCompressedArchive.convert(archive, File(directory, "duplicate.zip")) }.isFailure)
            finish(0, Bundle().apply { putString("stream", "7z direct and nested FTP import, 7z corruption and traversal rejection, RAR4 package/model/hash import, RAR5 stored/compressed content, password rejection, traversal, duplicate, multi-volume, corrupt and cleanup checks passed\n"); putBoolean("passed", true) })
        } catch (failure: Throwable) {
            finish(1, Bundle().apply { putString("stream", failure.stackTraceToString()); putBoolean("passed", false) })
        } finally { directory.deleteRecursively() }
    }

    private fun sevenZip(file: File, files: Map<String, ByteArray>) {
        val archive = Archive.writeNew()
        try {
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray(Charsets.UTF_8))
            Archive.writeSetFormat7zip(archive)
            Archive.writeOpenFileName(archive, file.absolutePath.toByteArray())
            files.forEach { (path, bytes) ->
                val entry = ArchiveEntry.new1()
                try {
                    ArchiveEntry.setPathnameUtf8(entry, path)
                    ArchiveEntry.setMode(entry, ArchiveEntry.AE_IFREG or 420)
                    ArchiveEntry.setSize(entry, bytes.size.toLong())
                    Archive.writeHeader(archive, entry)
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) Archive.writeData(archive, buffer)
                    Archive.writeFinishEntry(archive)
                } finally { ArchiveEntry.free(entry) }
            }
            Archive.writeClose(archive)
        } finally { Archive.writeFree(archive) }
    }

    private fun rar4(files: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        fun block(type: Int, flags: Int, body: ByteArray) {
            val header = ByteBuffer.allocate(5 + body.size).order(ByteOrder.LITTLE_ENDIAN)
                .put(type.toByte()).putShort(flags.toShort()).putShort((7 + body.size).toShort()).put(body).array()
            val crc = CRC32().apply { update(header) }.value.toInt()
            output.write(byteArrayOf(crc.toByte(), (crc ushr 8).toByte())); output.write(header)
        }
        output.write(byteArrayOf(82,97,114,33,26,7,0))
        block(0x73, 0, ByteArray(6))
        files.forEach { (name, bytes) ->
            val path = name.toByteArray(Charsets.UTF_8)
            val body = ByteBuffer.allocate(25 + path.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(bytes.size).putInt(bytes.size).put(3.toByte())
                .putInt(CRC32().apply { update(bytes) }.value.toInt()).putInt(0)
                .put(20.toByte()).put(0x30.toByte()).putShort(path.size.toShort()).putInt(0x81a4).put(path).array()
            block(0x74, 0x8000, body); output.write(bytes)
        }
        block(0x7b, 0, byteArrayOf())
        return output.toByteArray()
    }
}
