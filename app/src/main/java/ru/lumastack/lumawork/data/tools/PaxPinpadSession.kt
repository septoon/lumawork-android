package ru.lumastack.lumawork.data.tools

import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/** write must send the entire buffer; read returns a nonempty bounded USB chunk or throws. */
internal interface PaxPinpadWire {
    suspend fun write(bytes: ByteArray)
    suspend fun read(): ByteArray
    fun close()
}

internal enum class PaxPinpadMode { OS_BOOT, SYSTEM_MENU }
internal enum class PaxPinpadStage { ErasingOs, DownloadingOs, ErasingFlash, WritingOs, ErasingApp, DownloadingApp, FinalizingApp, WritingApp }
internal enum class PaxPinpadInstallResult { OsRebootRequested, AppWritingCompleted }

/** Null means the OS loader answered D/X; it exposes no model, version or serial. Both probes are read-only. */
internal suspend fun inspectPaxPinpad(open: () -> PaxPinpadWire): PaxPinpadInfo? {
    for (mode in listOf(PaxPinpadMode.OS_BOOT, PaxPinpadMode.SYSTEM_MENU)) {
        // Open errors (permission, topology, occupied interface) must not be hidden by a second probe.
        PaxPinpadSession(open()).use { session ->
            try {
                session.connect(mode)
                return if (mode == PaxPinpadMode.OS_BOOT) null else session.readInfo()
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (mode == PaxPinpadMode.SYSTEM_MENU) throw timeout
            } catch (failure: PaxDiagnosticException) {
                if (mode == PaxPinpadMode.SYSTEM_MENU) throw failure
            }
        }
    }
    error("unreachable")
}

/** Fixed successful S210 exchanges only. Any error consumes the session; no destructive retries/resume. */
internal class PaxPinpadSession(private val wire: PaxPinpadWire) : AutoCloseable {
    private val mutex = Mutex()
    private val decoder = PaxPinpadDecoder()
    private val events = ArrayDeque<PaxPinpadEvent>()
    private var mode: PaxPinpadMode? = null
    private var attempted = false
    @Volatile private var broken = false
    private val closed = AtomicBoolean(false)
    private var info: PaxPinpadInfo? = null

    suspend fun connect(mode: PaxPinpadMode) = mutex.withLock {
        try {
            paxRequire(!attempted && !broken, "Для PAX S210 требуется новый сеанс.")
            attempted = true
            withTimeout(15_000) { handshake(mode) }
            this.mode = mode
        } catch (failure: Throwable) { broken = true; this.mode = null; throw failure }
    }

    /** Unlike the Windows Refresh sequence, this never synchronizes the terminal clock (A2). */
    suspend fun readInfo(): PaxPinpadInfo = operation(PaxPinpadMode.SYSTEM_MENU) {
        command(0xa1, byteArrayOf(0x2f, 0))
        parsePaxPinpadInfo(reply(0xa1)).also { info = it }
    }

    suspend fun installOs(
        asset: PaxFirmwareAsset,
        progress: (PaxPinpadStage, Long, Long) -> Unit = { _, _, _ -> },
    ): PaxPinpadInstallResult = operation(PaxPinpadMode.OS_BOOT) {
        val size = verifyAsset(asset)
        begin(0xe2, size)
        command(0xe3, byteArrayOf(0))
        phase(0xe3, PaxPinpadStage.ErasingOs, progress)
        upload(asset, size, 0xe4, PaxPinpadStage.DownloadingOs, progress)
        command(0xe5)
        expectSuccess(0xe5)
        command(0xe3, byteArrayOf(1))
        phase(0xe3, PaxPinpadStage.ErasingFlash, progress)
        command(0xe6)
        phase(0xe6, PaxPinpadStage.WritingOs, progress)
        handshake(PaxPinpadMode.OS_BOOT)
        try {
            command(0xff, byteArrayOf(0))
            expectSuccess(0xff)
        } catch (_: PaxConnectionLostException) {
            // Writing already reached 100%. A reboot can remove USB before FF's ACK/reply.
            // This is only a request result; the parent must reconnect and read fresh OS info.
        }
        mode = null
        PaxPinpadInstallResult.OsRebootRequested
    }

    suspend fun installApp(
        asset: PaxFirmwareAsset,
        progress: (PaxPinpadStage, Long, Long) -> Unit = { _, _, _ -> },
    ): PaxPinpadInstallResult = operation(PaxPinpadMode.SYSTEM_MENU) {
        paxRequire(info?.model == "S210" && info?.application == "PAX_PINPAD", "Сначала проверьте S210 и единственное приложение PAX_PINPAD.")
        val size = verifyAsset(asset)
        handshake(PaxPinpadMode.SYSTEM_MENU)
        begin(0xa4, size)
        command(0xa5, byteArrayOf(0))
        phase(0xa5, PaxPinpadStage.ErasingApp, progress)
        upload(asset, size, 0xa6, PaxPinpadStage.DownloadingApp, progress)
        progress(PaxPinpadStage.FinalizingApp, 0, 100)
        command(0xa7)
        expectSuccess(0xa7)
        command(0xa5, byteArrayOf(1))
        phase(0xa5, PaxPinpadStage.FinalizingApp, progress)
        command(0xa8)
        phase(0xa8, PaxPinpadStage.WritingApp, progress)
        mode = null
        PaxPinpadInstallResult.AppWritingCompleted
    }

    override fun close() {
        broken = true
        mode = null
        if (closed.compareAndSet(false, true)) wire.close()
    }

    private suspend fun <T> operation(requiredMode: PaxPinpadMode, block: suspend () -> T): T = mutex.withLock {
        try {
            paxRequire(!broken && mode == requiredMode, "PAX S210 не подключён в нужном режиме. Требуется новый сеанс.")
            withTimeout(600_000) { block() }
        } catch (failure: Throwable) { broken = true; mode = null; throw failure }
    }

    private suspend fun handshake(mode: PaxPinpadMode) {
        repeat(2) {
            beforeWrite()
            writeBytes(byteArrayOf(if (mode == PaxPinpadMode.OS_BOOT) 0x44 else 0x51))
            val expected = if (mode == PaxPinpadMode.OS_BOOT) 0x58 else 0x5a
            paxRequire(next() == PaxPinpadEvent.Control(expected), "PAX S210 не подтвердил выбранный режим загрузчика.")
        }
    }

    private suspend fun command(command: Int, payload: ByteArray = byteArrayOf()) {
        beforeWrite()
        writeBytes(encodePaxPinpadFrame(command, payload))
        paxRequire(next() == PaxPinpadEvent.Control(6), "PAX S210 не подтвердил команду ACK.")
    }

    private suspend fun reply(command: Int): ByteArray {
        val event = next()
        paxRequire(event is PaxPinpadEvent.Frame && event.command == command, "Неожиданный ответ PAX S210.")
        val payload = (event as PaxPinpadEvent.Frame).payload
        // ACK only a valid frame for the expected command; malformed replies fail closed.
        writeBytes(byteArrayOf(6))
        return payload
    }

    private suspend fun writeBytes(bytes: ByteArray) = withTimeout(5_000) {
        currentCoroutineContext().ensureActive()
        paxRequire(!broken, "Сеанс PAX S210 закрыт.")
        wire.write(bytes)
    }

    private suspend fun beforeWrite() {
        currentCoroutineContext().ensureActive()
        paxRequire(!broken && events.isEmpty() && decoder.isIdle, "Неподтверждённые данные в сеансе PAX S210.")
    }

    private suspend fun next(): PaxPinpadEvent = withTimeout(5_000) {
        currentCoroutineContext().ensureActive()
        paxRequire(!broken, "Сеанс PAX S210 закрыт.")
        while (events.isEmpty()) {
            currentCoroutineContext().ensureActive()
            paxRequire(!broken, "Сеанс PAX S210 закрыт.")
            events.addAll(decoder.feed(wire.read()))
        }
        events.removeFirst()
    }

    private suspend fun begin(command: Int, size: Long) {
        command(command, byteArrayOf(2) + byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
        paxRequire(reply(command).contentEquals(byteArrayOf(0, 2, 0, 0, 0x10, 0, 0x13, 0x88.toByte())),
            "PAX S210 вернул неподдерживаемые параметры передачи.")
    }

    private suspend fun expectSuccess(command: Int) {
        paxRequire(reply(command).contentEquals(byteArrayOf(0)), "PAX S210 не подтвердил завершение операции.")
    }

    private suspend fun phase(command: Int, stage: PaxPinpadStage, progress: (PaxPinpadStage, Long, Long) -> Unit) = withTimeout(90_000) {
        var previous = -1
        repeat(1024) {
            val body = reply(command)
            paxRequire(body.size == 2 && body[0] == 0.toByte(), "PAX S210 сообщил об ошибке записи.")
            val percent = body[1].toInt() and 255
            paxRequire(percent in 0..100 && percent >= previous, "PAX S210 вернул неверный прогресс записи.")
            previous = percent
            progress(stage, percent.toLong(), 100)
            if (percent == 100) return@withTimeout
        }
        throw PaxDiagnosticException("PAX S210 не завершил этап записи.")
    }

    private suspend fun verifyAsset(asset: PaxFirmwareAsset): Long {
        val size = asset.size
        paxRequire(size in 1..(PAX_PINPAD_CHUNK.toLong() * 65535) && asset.sha256.matches(Regex("[0-9a-fA-F]{64}")), "Недопустимый файл PAX S210.")
        val digest = MessageDigest.getInstance("SHA-256")
        asset.file.inputStream().use { input ->
            var remaining = size
            val buffer = ByteArray(PAX_PINPAD_CHUNK)
            while (remaining > 0) {
                currentCoroutineContext().ensureActive()
                val count = readChunk(input, buffer, minOf(remaining, buffer.size.toLong()).toInt())
                digest.update(buffer, 0, count)
                remaining -= count
            }
            paxRequire(input.read() == -1, "Размер файла PAX S210 изменился.")
        }
        paxRequire(hash(digest).equals(asset.sha256, true), "Контрольная сумма файла PAX S210 не совпадает.")
        return size
    }

    private suspend fun upload(asset: PaxFirmwareAsset, size: Long, command: Int, stage: PaxPinpadStage,
        progress: (PaxPinpadStage, Long, Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        asset.file.inputStream().use { input ->
            val buffer = ByteArray(PAX_PINPAD_CHUNK)
            var sent = 0L
            var sequence = 1
            progress(stage, 0, size)
            while (sent < size) {
                currentCoroutineContext().ensureActive()
                val count = readChunk(input, buffer, minOf(size - sent, buffer.size.toLong()).toInt())
                digest.update(buffer, 0, count)
                if (sent + count == size) {
                    paxRequire(input.read() == -1 && hash(digest).equals(asset.sha256, true), "Файл PAX S210 изменился при передаче.")
                }
                command(command, byteArrayOf((sequence ushr 8).toByte(), sequence.toByte()) + buffer.copyOf(count))
                val response = reply(command)
                paxRequire(response.contentEquals(byteArrayOf(0, (sequence ushr 8).toByte(), sequence.toByte())),
                    "PAX S210 не подтвердил номер блока.")
                sent += count
                progress(stage, sent, size)
                sequence++
            }
        }
    }

    private suspend fun readChunk(input: InputStream, buffer: ByteArray, count: Int): Int {
        var offset = 0
        while (offset < count) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer, offset, count - offset)
            paxRequire(read > 0, "Файл PAX S210 оборван.")
            offset += read
        }
        return count
    }

    private fun hash(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
