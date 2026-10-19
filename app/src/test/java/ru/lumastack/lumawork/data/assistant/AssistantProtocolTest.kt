package ru.lumastack.lumawork.data.assistant

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import okio.Buffer

class AssistantProtocolTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    @Test fun `SSE assembles deltas and accepts final event without trailing blank line`() {
        val source = Buffer().writeUtf8(": keepalive\n\nevent: delta\ndata: {\"text\":\"Привет\"}\n\nevent: delta\ndata: {\"text\":\"!\"}\n\nevent: done\ndata: {\"kind\":\"answer\",\"text\":\"Привет!\"}")
        val deltas = StringBuilder()
        assertEquals("Привет!", AssistantSseReader.read(source, json, deltas::append).text)
        assertEquals("Привет!", deltas.toString())
    }
    @Test fun `SSE EOF after delta is failure not a completed answer`() {
        assertTrue(runCatching { AssistantSseReader.read(Buffer().writeUtf8("event: delta\ndata: {\"text\":\"partial\"}\n\n"), json) {} }.isFailure)
    }
    @Test fun `SSE token limit becomes conversation full state`() {
        val source = Buffer().writeUtf8("event: error\ndata: {\"error\":\"ASSISTANT_CONVERSATION_TOKEN_LIMIT\",\"conversation\":{\"isFull\":true}}\n\n")
        assertEquals("conversation_full", AssistantSseReader.read(source, json) {}.kind)
    }
    @Test fun `wire body keeps exact existing app API fields and never includes tokens`() {
        val encoded = json.encodeToString(AssistantRunRequest("request-id", "conversation-id", "Hello"))
        assertTrue(encoded.contains("\"requestId\"")); assertTrue(encoded.contains("\"conversationId\""))
        assertFalse(encoded.contains("requestID")); assertFalse(encoded.contains("token"))
    }
    @Test fun `text document bounds content and names and uses supported extraction enum`() {
        val attachment = AssistantDocuments.prepare("я".repeat(13_000).toByteArray(), "../note.txt")
        val document = attachment.document!!
        assertEquals("note.txt", document.fileName); assertEquals(12_000, document.text.length)
        assertTrue(document.wasTruncated); assertEquals("ios_text", document.extraction)
    }
    @Test fun `docx extracts paragraphs but does not fetch external entities`() {
        val attachment = AssistantDocuments.prepare(zip("word/document.xml", """<w:document xmlns:w="urn:w"><w:p><w:r><w:t>Hello</w:t></w:r></w:p><w:p><w:r><w:t>World</w:t></w:r></w:p></w:document>"""), "test.docx")
        assertEquals("Hello\nWorld", attachment.document!!.text)
        val attack = """<!DOCTYPE x [<!ENTITY ext SYSTEM "file:///etc/passwd">]><w:document xmlns:w="urn:w"><w:t>&ext;</w:t></w:document>"""
        assertTrue(runCatching { AssistantDocuments.prepare(zip("word/document.xml", attack), "bad.docx") }.isFailure)
    }
    @Test fun `xlsx single first-row cell is included without assuming request-table headers`() {
        val xml = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Only cell</t></is></c></row></sheetData></worksheet>"""
        val attachment = AssistantDocuments.prepare(zip("xl/worksheets/sheet1.xml", xml), "one.xlsx")
        assertTrue(attachment.document!!.text.contains("Only cell"))
        assertFalse(attachment.document.wasTruncated)
    }
    @Test fun `RTF excludes font metadata and decodes Unicode fallback`() {
        val data = "{\\rtf1\\ansi{\\fonttbl{\\f0 Arial;}}\\uc1\\u1040?\\par Work}".toByteArray()
        assertEquals("А\nWork", AssistantDocuments.prepare(data, "test.rtf").document!!.text)
    }
    @Test fun `legacy binary files validate OLE signature before upload`() {
        assertTrue(runCatching { AssistantDocuments.prepare("not a DOC".toByteArray(), "bad.doc") }.isFailure)
        val ole = byteArrayOf(208.toByte(), 207.toByte(),17,224.toByte(),161.toByte(),177.toByte(),26,225.toByte(),0)
        val result = AssistantDocuments.prepare(ole, "legacy.doc").document!!
        assertEquals("server_legacy", result.extraction); assertNotNull(result.rawBase64)
    }
    @Test fun `empty unsupported and oversized documents are rejected`() {
        assertTrue(runCatching { AssistantDocuments.prepare(byteArrayOf(), "empty.txt") }.isFailure)
        assertTrue(runCatching { AssistantDocuments.prepare(byteArrayOf(1), "app.apk") }.isFailure)
        assertTrue(runCatching { AssistantDocuments.prepare(ByteArray(AssistantDocuments.MAX_BYTES + 1), "large.txt") }.isFailure)
    }
    private fun zip(name: String, value: String): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { it.putNextEntry(ZipEntry(name)); it.write(value.toByteArray()); it.closeEntry() }
    }.toByteArray()
}
