package ru.lumastack.lumawork.data.tools

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal data class PaxTransferAsset(val name: String, val size: Long, val modifiedSeconds: Long, val open: () -> InputStream)

/** Fixed commands reconstructed from the supplied successful D270 and Q25 captures. No shell, retries or resume. */
internal class PaxInstallerSession(
    private val wire: PaxMessageWire,
    private val progress: (Long, Long) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()
    private var connected = false
    private var localId = 0
    private var maxPayload = 4096

    suspend fun connect() = mutex.withLock {
        connected = false
        withTimeout(15_000) {
            wire.send(PaxMessage("CNXN", 0x01000000, 4096, "host::\u0000".toByteArray()))
            val hello = wire.receive()
            paxRequire(hello.command != "AUTH", "PAX требует авторизацию XCB. Установка остановлена.")
            paxRequire(hello.command == "CNXN" && hello.arg0 == 0x01000000 && hello.arg1 in 24..PAX_MAX_PAYLOAD &&
                hello.payload.toString(Charsets.US_ASCII).startsWith("device::"), "Не получено ожидаемое рукопожатие XCB.")
            maxPayload = hello.arg1
            val channel = open("get-maxpayload:")
            val size = channel.readToClose(32).toString(Charsets.US_ASCII).trimEnd('\u0000', '\r', '\n')
            paxRequire(size.isNotEmpty() && size.all { it in '0'..'9' }, "PAX вернул неверный размер XCB-пакета.")
            val negotiated = size.toIntOrNull()
            paxRequire(negotiated != null && negotiated in 24..PAX_MAX_PAYLOAD, "Размер XCB-пакета PAX не поддерживается.")
            maxPayload = requireNotNull(negotiated)
            connected = true
        }
    }

    suspend fun passport(): PaxPassport = operation(15_000) {
        val channel = open("devinfo:")
        channel.write(byteArrayOf(32))
        parsePaxPassport(channel.readToClose(2 * PAX_MAX_PAYLOAD))
    }

    suspend fun upload(asset: PaxTransferAsset): String {
        val remote = remotePath(asset.name)
        paxRequire(asset.size in 1..0xffff_ffffL && asset.modifiedSeconds in 0..0xffff_ffffL, "Недопустимый размер или время файла PAX.")
        return operation(600_000) {
            // Open exactly once. A changed/truncated source must never cause DONE, retry or resume.
            asset.open().use { input ->
                val channel = open("installer:")
                channel.write(record("STAT", remote.toByteArray()))
                val stat = channel.read(16)
                paxRequire(stat.copyOfRange(0, 4).contentEquals("STAT".toByteArray()), "PAX вернул неверный ответ STAT.")
                // STAT contains mode, size, mtime, not a record payload length; always send the whole source.
                channel.write(record("ASK ", "size:${asset.size / 1024}".toByteArray()))
                channel.expectOkay()
                channel.write(record("SEND", "$remote,33206".toByteArray()), allowResponse = false)
                val buffer = ByteArray(PAX_MAX_PAYLOAD)
                var sent = 0L
                progress(0, asset.size)
                while (sent < asset.size) {
                    currentCoroutineContext().ensureActive()
                    val wanted = minOf(buffer.size.toLong(), asset.size - sent).toInt()
                    var count = 0
                    while (count < wanted) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer, count, wanted - count)
                        paxRequire(read > 0, "Файл PAX оборван или источник перестал отдавать данные.")
                        count += read
                    }
                    // Reject an overlong source before committing its last DATA/DONE.
                    if (sent + count == asset.size) paxRequire(input.read() == -1, "Размер файла PAX изменился.")
                    channel.write(record("DATA", buffer.copyOf(count)), allowResponse = false)
                    sent += count
                    progress(sent, asset.size)
                }
                channel.write(header("DONE", asset.modifiedSeconds.toInt()))
                channel.expectOkay()
                channel.finish()
            }
            remote
        }
    }

    suspend fun removeMainApp() = install("uaip", listOf("MAINAPP"), "installer uaip done.")
    suspend fun installBoot(remote: String) = install("boot", listOf(checkedRemote(remote)), "installer boot done.")
    suspend fun installOs(remote: String, model: PaxModel = PaxModel.D270) = install("firmware", listOf(checkedRemote(remote)),
        if (model == PaxModel.Q25) "installer firmware-base done." else "installer rootfs.img done.")
    suspend fun installMainApp(remote: String) = install("aip", listOf(checkedRemote(remote)), "installer aip done.")
    suspend fun installAppData(remote: String) = install("appdata", listOf("MAINAPP", checkedRemote(remote)), "installer appdata done.")
    suspend fun installOption(remote: String) = install("opt", listOf(checkedRemote(remote)), "installer opt done.")

    suspend fun reboot() = operation(15_000) {
        // Capture: OPEN reboot, OKAY, CLSE. No selector, shell command or extra write.
        open("reboot:").expectClose()
        connected = false
    }

    private suspend fun install(action: String, args: List<String>, marker: String) = operation(600_000) {
        val channel = open("installer:")
        val command = (listOf(action) + args).joinToString("\u0000", postfix = "\u0000\u0000").toByteArray()
        channel.write(record("CMD ", command))
        var trailingLine = ""
        var lastLine = ""
        var statusBytes = 0
        while (true) {
            val reply = channel.readRecord()
            when (reply.first) {
                "DATA" -> {
                    statusBytes += reply.second.size
                    paxRequire(statusBytes <= 4 * 1024 * 1024, "Превышен размер статуса установки PAX.")
                    val text = trailingLine + reply.second.toString(Charsets.UTF_8)
                    val lines = text.split('\n')
                    trailingLine = lines.last()
                    paxRequire(trailingLine.length <= 4096, "Некорректная строка статуса установки PAX.")
                    for (line in lines.dropLast(1)) {
                        val clean = line.trim()
                        paxRequire(!failureText.containsMatchIn(clean), "PAX сообщил об ошибке проверки или установки.")
                        if (clean.isNotEmpty()) lastLine = clean
                    }
                    paxRequire(!failureText.containsMatchIn(trailingLine), "PAX сообщил об ошибке проверки или установки.")
                }
                "OKAY" -> {
                    paxRequire(reply.second.isEmpty(), "Некорректный ответ завершения установки PAX.")
                    if (trailingLine.trim().isNotEmpty()) lastLine = trailingLine.trim()
                    paxRequire(lastLine == marker, "PAX не подтвердил завершение установки выбранного компонента.")
                    channel.finish()
                    return@operation
                }
                else -> throw PaxDiagnosticException("Неожиданный ответ установщика PAX.")
            }
        }
    }

    private suspend fun <T> operation(timeout: Long, block: suspend () -> T): T = mutex.withLock {
        paxRequire(connected, "Сеанс PAX не открыт. Требуется новое подключение.")
        try { withTimeout(timeout) { block() } }
        catch (failure: Throwable) {
            // Unknown device-side outcome: poison this session; the caller must stop the entire plan.
            connected = false
            throw failure
        }
    }

    private suspend fun open(service: String): Channel {
        currentCoroutineContext().ensureActive()
        paxRequire(localId < Int.MAX_VALUE, "Исчерпаны идентификаторы XCB.")
        val local = ++localId
        wire.send(PaxMessage("OPEN", local, 0, "$service\u0000".toByteArray()))
        val response = wire.receive()
        paxRequire(response.command == "OKAY" && response.arg0 > 0 && response.arg1 == local && response.payload.isEmpty(),
            "PAX не открыл ожидаемый сервис XCB.")
        return Channel(local, response.arg0)
    }

    private inner class Channel(private val local: Int, private val remote: Int) {
        private var pending = byteArrayOf()
        private var closed = false

        suspend fun write(bytes: ByteArray, allowResponse: Boolean = true) {
            paxRequire(allowResponse || pending.isEmpty(), "PAX прервал передачу файла.")
            for (offset in bytes.indices step maxPayload) {
                currentCoroutineContext().ensureActive()
                paxRequire(!closed, "Канал PAX закрыт до завершения записи.")
                wire.send(PaxMessage("WRTE", local, remote, bytes.copyOfRange(offset, minOf(bytes.size, offset + maxPayload))))
                while (true) {
                    val reply = receive()
                    paxRequire(allowResponse || pending.isEmpty(), "PAX прервал передачу файла.")
                    if (reply == "OKAY") break
                    paxRequire(!closed, "PAX закрыл канал до подтверждения записи.")
                }
            }
        }

        suspend fun read(size: Int): ByteArray {
            while (pending.size < size) {
                paxRequire(!closed, "Оборван ответ сервиса PAX.")
                paxRequire(receive() != "OKAY", "Неожиданное подтверждение записи PAX.")
            }
            return pending.copyOfRange(0, size).also { pending = pending.copyOfRange(size, pending.size) }
        }

        suspend fun readRecord(): Pair<String, ByteArray> {
            val head = read(8)
            val tag = head.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            val size = ByteBuffer.wrap(head, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            paxRequire(tag in setOf("DATA", "OKAY", "FAIL") && size in 0..PAX_MAX_PAYLOAD, "Некорректная запись установщика PAX.")
            val body = read(size)
            paxRequire(tag != "FAIL", "PAX отклонил операцию установщика.")
            return tag to body
        }

        suspend fun expectOkay() {
            val (tag, body) = readRecord()
            paxRequire(tag == "OKAY" && body.isEmpty(), "PAX не подтвердил передачу файла.")
        }

        suspend fun readToClose(limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                paxRequire(out.size() + pending.size <= limit, "Ответ сервиса PAX превышает допустимый размер.")
                out.write(pending)
                pending = byteArrayOf()
                if (closed) return out.toByteArray()
                paxRequire(receive() != "OKAY", "Неожиданное подтверждение сервиса PAX.")
            }
        }

        suspend fun finish() {
            paxRequire(pending.isEmpty(), "Лишние данные после результата установщика PAX.")
            write(record("QUIT"))
            expectClose()
        }

        suspend fun expectClose() {
            paxRequire(pending.isEmpty() && !closed, "Неожиданное завершение канала PAX.")
            paxRequire(receive() == "CLSE", "PAX не закрыл завершённый канал.")
        }

        private suspend fun receive(): String {
            currentCoroutineContext().ensureActive()
            val response = wire.receive()
            paxRequire(response.arg1 == local && (response.arg0 == remote || response.command == "CLSE" && response.arg0 == 0),
                "Ответ XCB относится к другому сеансу.")
            when (response.command) {
                "WRTE" -> {
                    paxRequire(!closed && response.payload.isNotEmpty() && pending.size + response.payload.size <= 2 * PAX_MAX_PAYLOAD,
                        "Недопустимый объём ответа XCB.")
                    pending += response.payload
                    wire.send(PaxMessage("OKAY", local, remote))
                }
                "OKAY" -> paxRequire(response.payload.isEmpty(), "Некорректное подтверждение XCB.")
                "CLSE" -> {
                    paxRequire(response.payload.isEmpty() && !closed, "Некорректное закрытие XCB.")
                    closed = true
                    wire.send(PaxMessage("CLSE", 0, remote))
                }
                else -> throw PaxDiagnosticException("Неожиданный ответ XCB.")
            }
            return response.command
        }
    }

    private fun remotePath(name: String): String {
        paxRequire(name.length in 1..180 && name != "." && name != ".." && name.all { it.isLetterOrDigit() || it in "._-" },
            "Недопустимое имя файла PAX.")
        return "/tmp/$name"
    }

    private fun checkedRemote(remote: String): String {
        paxRequire(remote.startsWith("/tmp/") && remote == remotePath(remote.removePrefix("/tmp/")), "Недопустимый путь установщика PAX.")
        return remote
    }

    private fun record(tag: String, body: ByteArray = byteArrayOf()) = header(tag, body.size) + body
    private fun header(tag: String, value: Int) = tag.toByteArray(Charsets.US_ASCII) +
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private companion object {
        val failureText = Regex("\\b(fail(?:ed|ure)?|error|invalid|denied|unsuccessful)\\b|not enough", RegexOption.IGNORE_CASE)
    }
}
