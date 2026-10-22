package ru.lumastack.lumawork.data.tools

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val PAX_PINPAD_MAX_PAYLOAD = 65_536
internal const val PAX_PINPAD_CHUNK = 4096

/** Contains no raw terminal identity or factory/security fields. */
data class PaxPinpadInfo(
    val model: String,
    val os: String?,
    val application: String?,
    internal val fingerprint: String? = null,
)

internal sealed interface PaxPinpadEvent {
    data class Control(val value: Int) : PaxPinpadEvent
    data class Frame(val command: Int, val payload: ByteArray) : PaxPinpadEvent
}

/** S210 RunthOS protocol, independent of the Prolin XCB framing. Lengths are big endian. */
internal fun encodePaxPinpadFrame(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
    require(command in 0..255 && payload.size <= PAX_PINPAD_MAX_PAYLOAD)
    val bytes = ByteArray(payload.size + 7)
    bytes[0] = 2
    bytes[1] = command.toByte()
    for (i in 0..3) bytes[2 + i] = (payload.size ushr (24 - 8 * i)).toByte()
    payload.copyInto(bytes, 6)
    bytes[bytes.lastIndex] = (1 until bytes.lastIndex).fold(0) { xor, i -> xor xor (bytes[i].toInt() and 255) }.toByte()
    return bytes
}

internal class PaxPinpadDecoder {
    private var pending = byteArrayOf()
    val isIdle: Boolean get() = pending.isEmpty()

    fun feed(bytes: ByteArray): List<PaxPinpadEvent> {
        paxRequire(bytes.isNotEmpty() && pending.size + bytes.size <= 2 * (PAX_PINPAD_MAX_PAYLOAD + 7),
            "Недопустимый объём ответа PAX S210.")
        pending += bytes
        val events = mutableListOf<PaxPinpadEvent>()
        var offset = 0
        while (offset < pending.size) {
            val first = pending[offset].toInt() and 255
            if (first != 2) {
                paxRequire(first in setOf(6, 0x58, 0x5a), "Неожиданный управляющий байт PAX S210.")
                events += PaxPinpadEvent.Control(first)
                offset++
                continue
            }
            if (pending.size - offset < 6) break
            var length = 0L
            for (i in 2..5) length = (length shl 8) or (pending[offset + i].toLong() and 255)
            paxRequire(length <= PAX_PINPAD_MAX_PAYLOAD, "Размер кадра PAX S210 превышен.")
            val end = offset + 7 + length.toInt()
            if (pending.size < end) break
            val checksum = (offset + 1 until end).fold(0) { xor, i -> xor xor (pending[i].toInt() and 255) }
            paxRequire(checksum == 0, "Неверная контрольная сумма PAX S210.")
            events += PaxPinpadEvent.Frame(pending[offset + 1].toInt() and 255, pending.copyOfRange(offset + 6, end - 1))
            offset = end
        }
        pending = pending.copyOfRange(offset, pending.size)
        return events
    }
}

internal fun parsePaxPinpadInfo(payload: ByteArray): PaxPinpadInfo {
    paxRequire(payload.size in 3..PAX_PINPAD_MAX_PAYLOAD && payload[0] == 0.toByte() && payload.last() == 0.toByte(),
        "PAX S210 не подтвердил чтение параметров.")
    try {
        val root = Json.parseToJsonElement(payload.copyOfRange(1, payload.lastIndex).toString(Charsets.UTF_8)) as? JsonObject
            ?: throw PaxDiagnosticException("Неизвестный формат параметров PAX S210.")
        val terminal = root["terminal"] as? JsonObject
            ?: throw PaxDiagnosticException("В ответе PAX S210 нет модели.")
        fun field(objectValue: JsonObject, key: String): String? {
            val value = objectValue[key] ?: return null
            val primitive = value as? JsonPrimitive
            paxRequire(primitive != null && primitive.isString, "Некорректное поле параметров PAX S210.")
            return requireNotNull(primitive).content.trim().takeIf { it.isNotEmpty() }?.also {
                paxRequire(it.length <= 96 && it.all { c -> c.isLetterOrDigit() || c in " ._()/+-" },
                    "Недопустимое поле параметров PAX S210.")
            }
        }
        val model = field(terminal, "mach")
        paxRequire(model == "S210", "Модель из ответа не подтверждена как S210.")
        val apps = root["application"] as? JsonArray
            ?: throw PaxDiagnosticException("PAX S210 не вернул список приложений.")
        paxRequire(apps.size <= 32, "Слишком много приложений в ответе PAX S210.")
        val application = apps.singleOrNull()?.let { it as? JsonObject }?.let { field(it, "appname") }
        val serial = (terminal["sn"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotEmpty() && it.length <= 128 }
        val fingerprint = serial?.let { value -> MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) } }
        return PaxPinpadInfo(requireNotNull(model), field(terminal, "os"), application, fingerprint)
    } catch (failure: PaxDiagnosticException) { throw failure }
    catch (_: Exception) { throw PaxDiagnosticException("Не удалось прочитать параметры PAX S210. Данные не сохранены.") }
}

/** White-screen OS start has no serial. Bind it only after a completed OS write and fresh menu/OS validation. */
internal fun checkPaxPinpadGate(info: PaxPinpadInfo, fingerprint: String?, expectedOs: String?, bindAfterOs: Boolean = false) {
    paxRequire(info.model == "S210" && info.application == "PAX_PINPAD" && info.fingerprint != null,
        "Не подтверждён S210 / PAX_PINPAD.")
    paxRequire((fingerprint == null && bindAfterOs) || fingerprint == info.fingerprint, "Подключён другой S210. Продолжение заблокировано.")
    paxRequire(!expectedOs.isNullOrBlank() && info.os == expectedOs,
        "OS S210 после перезагрузки не совпала: ожидается $expectedOs, получено ${info.os ?: "неизвестно"}. Запись APP заблокирована.")
}
