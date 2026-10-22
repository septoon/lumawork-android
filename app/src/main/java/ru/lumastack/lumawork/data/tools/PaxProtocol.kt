package ru.lumastack.lumawork.data.tools

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.w3c.dom.Element
import org.xml.sax.EntityResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException

internal const val PAX_MAX_PAYLOAD = 65_536
internal open class PaxDiagnosticException(message: String) : Exception(message)
internal class PaxConnectionLostException(message: String) : PaxDiagnosticException(message)
internal fun paxRequire(value: Boolean, message: String) {
    if (!value) throw PaxDiagnosticException(message)
}

internal data class PaxFrame(val sequence: Int, val type: Int, val payload: ByteArray)
internal class PaxFrameDecoder {
    private var pending = byteArrayOf()
    fun feed(bytes: ByteArray): List<PaxFrame> {
        paxRequire(pending.size + bytes.size <= 2 * PAX_MAX_PAYLOAD, "Превышен размер USB-кадра PAX.")
        pending += bytes
        val frames = mutableListOf<PaxFrame>()
        var offset = 0
        while (pending.size - offset >= 4) {
            val sequence = pending[offset].toInt() and 255
            val type = pending[offset + 1].toInt() and 255
            val length = (pending[offset + 2].toInt() and 255) or ((pending[offset + 3].toInt() and 255) shl 8)
            paxRequire(sequence < 16 && type <= 4 && length > 0, "Некорректный заголовок USB-кадра PAX.")
            val end = offset + length + 4
            if (end > pending.size) break
            var xor = 0
            for (i in offset until end) xor = xor xor (pending[i].toInt() and 255)
            paxRequire(xor == 0, "Неверная контрольная сумма USB-кадра PAX.")
            frames += PaxFrame(sequence, type, pending.copyOfRange(offset + 4, end - 1))
            offset = end
        }
        pending = pending.copyOfRange(offset, pending.size)
        return frames
    }
}

internal fun encodePaxFrame(sequence: Int, type: Int, payload: ByteArray): ByteArray {
    require(sequence in 0..15 && type in 0..4 && payload.size <= 65_534)
    val bytes = ByteBuffer.allocate(payload.size + 5).order(ByteOrder.LITTLE_ENDIAN)
        .put(sequence.toByte()).put(type.toByte()).putShort((payload.size + 1).toShort()).put(payload).array()
    bytes[bytes.lastIndex] = bytes.dropLast(1).fold(0) { xor, byte -> xor xor (byte.toInt() and 255) }.toByte()
    return bytes
}

internal data class PaxMessage(val command: String, val arg0: Int, val arg1: Int, val payload: ByteArray = byteArrayOf())
internal class PaxMessageDecoder {
    private var pending = byteArrayOf()
    fun feed(bytes: ByteArray): List<PaxMessage> {
        paxRequire(pending.size + bytes.size <= 2 * (PAX_MAX_PAYLOAD + 24), "Превышен размер сообщения XCB.")
        pending += bytes
        val messages = mutableListOf<PaxMessage>()
        var offset = 0
        while (pending.size - offset >= 24) {
            val header = ByteBuffer.wrap(pending, offset, 24).order(ByteOrder.LITTLE_ENDIAN)
            val command = header.int
            val arg0 = header.int
            val arg1 = header.int
            val length = header.int
            val checksum = header.int
            val magic = header.int
            paxRequire(magic == command.inv() && length in 0..PAX_MAX_PAYLOAD, "Некорректный заголовок XCB.")
            val name = pending.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
            paxRequire(name in setOf("CNXN", "OPEN", "WRTE", "OKAY", "CLSE", "AUTH"), "Неизвестное сообщение XCB.")
            val end = offset + 24 + length
            if (end > pending.size) break
            val payload = pending.copyOfRange(offset + 24, end)
            paxRequire(payload.sumOf { it.toInt() and 255 } == checksum, "Неверная контрольная сумма XCB.")
            messages += PaxMessage(name, arg0, arg1, payload)
            offset = end
        }
        pending = pending.copyOfRange(offset, pending.size)
        return messages
    }
}

internal fun encodePaxMessage(message: PaxMessage): ByteArray {
    require(message.command.length == 4 && message.payload.size <= PAX_MAX_PAYLOAD)
    val command = ByteBuffer.wrap(message.command.toByteArray(Charsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).int
    return ByteBuffer.allocate(24 + message.payload.size).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(command).putInt(message.arg0).putInt(message.arg1).putInt(message.payload.size)
        .putInt(message.payload.sumOf { it.toInt() and 255 }).putInt(command.inv()).put(message.payload).array()
}

/** Deliberately excludes USB serial, terminal IDs, MAC/IMEI and every security/key field. */
data class PaxPassport(val model: String, val os: String?, val boot: String?, val mainApp: String?,
    internal val fingerprint: String? = null, internal val target: String? = null)
internal fun parsePaxPassport(bytes: ByteArray): PaxPassport {
    paxRequire(bytes.size in 1..(2 * PAX_MAX_PAYLOAD), "Размер паспорта PAX недопустим.")
    val text = bytes.toString(Charsets.UTF_8).trim('\u0000', '\r', '\n', ' ')
    // Android SAX/DOM providers differ in supported features. Reject declarations independently.
    paxRequire(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true), "Внешние сущности в паспорте PAX запрещены.")
    try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            listOf("http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false).forEach { (feature, value) ->
                runCatching { setFeature(feature, value) }
            }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver(EntityResolver { _, _ -> throw PaxDiagnosticException("Внешние сущности XML запрещены.") })
            setErrorHandler(object : ErrorHandler {
                override fun warning(exception: SAXParseException) = Unit
                override fun error(exception: SAXParseException) { throw exception }
                override fun fatalError(exception: SAXParseException) { throw exception }
            })
        }
        val root = builder.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))).documentElement
        paxRequire(root.tagName == "devinfo", "Устройство вернуло неизвестный формат паспорта.")
        val terminal = root.child("terminal")
        val model = terminal?.value("mach")
        paxRequire(model in setOf("D230", "D270", "Q25"), "Модель из ответа не поддерживается: нужен D230, D270 или Q25.")
        val apps = root.child("application")
        val main = apps?.childNodes?.let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
            .singleOrNull { it.tagName == "app" && it.getAttribute("id") == "MAINAPP" } }
        val serial = terminal?.child("sn")?.textContent?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 }
        val fingerprint = serial?.let { value -> java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) } }
        return PaxPassport(requireNotNull(model), terminal.value("os"), terminal.value("boot"), main?.value("version"),
            fingerprint, terminal.value("target"))
    } catch (failure: PaxDiagnosticException) { throw failure }
    catch (_: Exception) { throw PaxDiagnosticException("Не удалось прочитать паспорт PAX. Данные не сохранены.") }
}

private fun Element.child(name: String): Element? = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    .filter { it.tagName == name }.let { found ->
        paxRequire(found.size <= 1, "Неоднозначный паспорт PAX.")
        found.singleOrNull()
    }
private fun Element.value(name: String): String? = child(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.also {
    paxRequire(it.length <= 96 && it.all { c -> c.isLetterOrDigit() || c in " ._()/+-" }, "Недопустимое поле паспорта PAX.")
}

internal interface PaxMessageWire {
    suspend fun send(message: PaxMessage)
    suspend fun receive(): PaxMessage
}

/** No arbitrary service API: the only OPEN is devinfo, and its only input is the observed space selector. */
internal class PaxReadOnlySession(private val wire: PaxMessageWire, private val onReading: () -> Unit = {}) {
    suspend fun inspect(): PaxPassport = withTimeout(12_000) {
        wire.send(PaxMessage("CNXN", 0x01000000, 4096, "host::\u0000".toByteArray()))
        val hello = wire.receive()
        paxRequire(hello.command != "AUTH", "PAX требует авторизацию XCB. Нужен официальный SDK или сервисный инструмент.")
        paxRequire(hello.command == "CNXN" && hello.arg0 == 0x01000000 && hello.arg1 in 24..PAX_MAX_PAYLOAD &&
            hello.payload.toString(Charsets.US_ASCII).startsWith("device::"), "Не получено ожидаемое рукопожатие XCB.")
        val local = 11
        wire.send(PaxMessage("OPEN", local, 0, "devinfo:\u0000".toByteArray()))
        val ready = wire.receive()
        paxRequire(ready.command == "OKAY" && ready.arg0 > 0 && ready.arg1 == local, "Сервис devinfo недоступен или вернул неверный идентификатор сеанса.")
        val remote = ready.arg0
        onReading()
        wire.send(PaxMessage("WRTE", local, remote, byteArrayOf(0x20)))
        val xml = ByteArrayOutputStream()
        var chunks = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val reply = wire.receive()
            paxRequire(reply.arg1 == local && (reply.arg0 == remote || reply.command == "CLSE" && reply.arg0 == 0), "Ответ XCB относится к другому сеансу.")
            when (reply.command) {
                "OKAY" -> Unit
                "WRTE" -> {
                    paxRequire(++chunks <= 512 && xml.size() + reply.payload.size <= 2 * PAX_MAX_PAYLOAD, "Паспорт PAX превышает допустимый размер.")
                    xml.write(reply.payload)
                    wire.send(PaxMessage("OKAY", local, remote))
                }
                "CLSE" -> {
                    wire.send(PaxMessage("CLSE", 0, remote))
                    return@withTimeout parsePaxPassport(xml.toByteArray())
                }
                else -> throw PaxDiagnosticException("Неожиданный ответ сервиса devinfo. Диагностика остановлена.")
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
}
