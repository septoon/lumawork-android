package ru.lumastack.lumawork.data.tools

import org.junit.Assert.*
import org.junit.Test

class PaxPinpadProtocolTest {
    @org.junit.Test fun `post reboot gate requires same model identity application and exact OS`() {
        val good = PaxPinpadInfo("S210", "1.0.08.308e43ddR", "PAX_PINPAD", "same")
        checkPaxPinpadGate(good, "same", "1.0.08.308e43ddR")
        for (bad in listOf(good.copy(model = "S200"), good.copy(fingerprint = "other"), good.copy(application = "OTHER"), good.copy(os = "old"))) {
            org.junit.Assert.assertTrue(runCatching { checkPaxPinpadGate(bad, "same", "1.0.08.308e43ddR") }.isFailure)
        }
        org.junit.Assert.assertTrue(runCatching { checkPaxPinpadGate(good, null, "1.0.08.308e43ddR") }.isFailure)
        org.junit.Assert.assertTrue(runCatching { checkPaxPinpadGate(good, "same", null) }.isFailure)
    }

    @Test fun `white screen start binds first identity only at completed OS checkpoint`() {
        val info = PaxPinpadInfo("S210", "target", "PAX_PINPAD", "first")
        checkPaxPinpadGate(info, null, "target", bindAfterOs = true)
        assertTrue(runCatching { checkPaxPinpadGate(info.copy(os = "old"), null, "target", bindAfterOs = true) }.isFailure)
        assertTrue(runCatching { checkPaxPinpadGate(info.copy(model = "S200"), null, "target", bindAfterOs = true) }.isFailure)
        assertTrue(runCatching { checkPaxPinpadGate(info.copy(application = "OTHER"), null, "target", bindAfterOs = true) }.isFailure)
        assertTrue(runCatching { checkPaxPinpadGate(info.copy(fingerprint = null), null, "target", bindAfterOs = true) }.isFailure)
        assertTrue(runCatching { checkPaxPinpadGate(info, "previous", "target", bindAfterOs = true) }.isFailure)
    }

    @Test fun `uses captured big endian length and XOR vectors`() {
        assertArrayEquals(hex("02e30000000100e2"), encodePaxPinpadFrame(0xe3, byteArrayOf(0)))
        assertArrayEquals(hex("02a1000000022f008c"), encodePaxPinpadFrame(0xa1, byteArrayOf(0x2f, 0)))
        assertArrayEquals(hex("02a700000000a7"), encodePaxPinpadFrame(0xa7))
    }

    @Test fun `decodes ACK and multiple frames at every fragmentation boundary`() {
        val bytes = hex("0602e400000003000001e602e300000002006485")
        for (cut in 1 until bytes.size) {
            val decoder = PaxPinpadDecoder()
            val events = decoder.feed(bytes.copyOfRange(0, cut)) + decoder.feed(bytes.copyOfRange(cut, bytes.size))
            assertEquals(3, events.size)
            assertEquals(PaxPinpadEvent.Control(6), events[0])
            assertArrayEquals(byteArrayOf(0, 0, 1), (events[1] as PaxPinpadEvent.Frame).payload)
            assertArrayEquals(byteArrayOf(0, 100), (events[2] as PaxPinpadEvent.Frame).payload)
        }
        val decoder = PaxPinpadDecoder()
        assertEquals(3, bytes.flatMap { decoder.feed(byteArrayOf(it)) }.size)
    }

    @Test fun `rejects oversized unsigned lengths invalid checksum and unknown controls`() {
        for (bytes in listOf(hex("02a1ffffffff"), hex("02a700000000a6"), byteArrayOf(0x15))) {
            failure { PaxPinpadDecoder().feed(bytes) }
        }
    }

    @Test fun `allowlists menu info and hashes identity without retaining raw fields`() {
        val payload = info("""{"terminal":{"mach":"S210","os":"1.0.08.syntheticR","sn":"synthetic-terminal","secret":"discard"},"application":[{"appname":"PAX_PINPAD","version":"1"}],"keyinfo":{"discard":"value"}}""")
        val parsed = parsePaxPinpadInfo(payload)
        assertEquals("S210", parsed.model)
        assertEquals("1.0.08.syntheticR", parsed.os)
        assertEquals("PAX_PINPAD", parsed.application)
        assertEquals(64, parsed.fingerprint?.length)
        assertFalse(parsed.toString().contains("synthetic-terminal"))
        assertFalse(parsed.toString().contains("discard"))
    }

    @Test fun `rejects wrong model malformed response and does not bless multiple apps`() {
        for (payload in listOf(info("{}"), info("""{"terminal":{"mach":"S200"},"application":[]}"""),
            byteArrayOf(1, 0), byteArrayOf(0, 123, 125))) {
            failure { parsePaxPinpadInfo(payload) }
        }
        assertNull(parsePaxPinpadInfo(info("""{"terminal":{"mach":"S210"},"application":[{"appname":"PAX_PINPAD"},{"appname":"OTHER"}]}""")).application)
    }

    private fun info(json: String) = byteArrayOf(0) + json.toByteArray() + byteArrayOf(0)
    private fun failure(block: () -> Unit) {
        try { block(); fail("Expected protocol failure") } catch (_: PaxDiagnosticException) { }
    }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
