package ru.lumastack.lumawork.data.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlinx.coroutines.*
import ru.lumastack.lumawork.MainActivity
import ru.lumastack.lumawork.R

/** Started only by the explicit confirmation screen. No restart, resume or automatic destructive retries. */
class PaxFirmwareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val link = AtomicReference<PaxUsbConnection?>(null)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private lateinit var store: PaxFirmwareStore
    private var snapshot = PaxInstallState()
    private var lastPersist = 0L
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        synchronized(Companion) { serviceAlive = true }
        store = PaxFirmwareStore(this)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Установка ПО PAX", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_NOT_STICKY
        val owner = intent?.getStringExtra("owner")
        val path = intent?.getStringExtra("bundle")
        val key = intent?.getStringExtra("device")
        val fingerprint = intent?.getStringExtra("fingerprint")
        if (owner.isNullOrBlank() || path == null || key == null || fingerprint == null) { stopSelf(); return START_NOT_STICKY }
        snapshot = PaxInstallState(owner, PaxInstallPhase.Running, path, PaxFirmwareStep.Reading, "Проверка комплекта и терминала",
            operationId = intent.getStringExtra("operation") ?: UUID.randomUUID().toString())
        try {
            val notification = notification(snapshot.detail)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION, notification)
        } catch (_: Exception) {
            terminal(PaxInstallPhase.Failed, "Android не разрешил фоновую USB-службу. Вернитесь в приложение и проверьте USB-доступ.")
            stopSelf(); return START_NOT_STICKY
        }
        job = scope.launch {
            try {
                wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LumaWork:PaxInstall")
                    .apply { acquire(30 * 60_000L) }
                publish(snapshot)
                val directory = store.checkedBundleDirectory(owner, path)
                val bundle = PaxFirmwareBundleReader.read(directory)
                val host = AndroidPaxFirmwareHost(this@PaxFirmwareService, getSystemService(USB_SERVICE) as UsbManager,
                    onLink = { current -> link.set(current) },
                    onBytes = { bytes, total ->
                        publish(snapshot.copy(bytes = bytes, totalBytes = total), force = false)
                    }, onWaiting = { text -> publish(snapshot.copy(detail = text), force = false) })
                var stepNumber = 0
                val stepCount = 14 + bundle.appData.size + bundle.options.size
                val result = withTimeout(25 * 60_000L) {
                    PaxFirmwareWorkflow(host) { step, file ->
                        stepNumber++
                        publish(snapshot.copy(step = step, fileName = file, bytes = 0, totalBytes = 0,
                            stepNumber = stepNumber, stepCount = stepCount,
                            critical = snapshot.critical || step == PaxFirmwareStep.Removing,
                            detail = if (step == PaxFirmwareStep.Reconnect)
                                "Ожидаем PAX. После перезагрузки откройте main menu → General → XCB service → USB; при запросе разрешите USB-доступ."
                            else step.title))
                    }.execute(bundle, key, fingerprint)
                }
                publish(snapshot.copy(phase = PaxInstallPhase.Complete, step = PaxFirmwareStep.Complete, fileName = null,
                    detail = "Подтверждено с терминала: ${result.os}; MAINAPP ${result.mainApp}; загрузчик ${result.boot}. USB-сеанс закрыт."))
            } catch (_: TimeoutCancellationException) {
                terminal(PaxInstallPhase.Failed, "Время ожидания истекло. Результат этапа неизвестен. Проверьте терминал; автоматического повтора нет.")
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { terminal(PaxInstallPhase.Interrupted,
                    "USB-операция прервана. Результат неизвестен. Проверьте терминал; автоматического повтора нет.") }
                throw cancelled
            } catch (failure: Exception) {
                terminal(PaxInstallPhase.Failed,
                    if (failure is PaxDiagnosticException || failure is IllegalArgumentException)
                        failure.message ?: "Проверка или установка не завершена."
                    else "Установка остановлена из-за ошибки USB или локального хранилища. Проверьте терминал; автоматического повтора нет.")
            } finally {
                runCatching { link.getAndSet(null)?.close() }
                wake?.let { if (it.isHeld) it.release() }; wake = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun terminal(phase: PaxInstallPhase, detail: String) {
        val value = snapshot.copy(phase = phase, detail = detail, updatedAt = System.currentTimeMillis())
        // Journal errors must not recursively escape an error handler or leave Running in memory.
        runCatching { store.write(value) }
        snapshot = value
        PaxFirmwareRuntime.mutable.value = value
    }

    private fun publish(value: PaxInstallState, force: Boolean = true) {
        snapshot = value.copy(updatedAt = System.currentTimeMillis())
        // Persist before each critical command. Failure to journal must stop before sending that command.
        val now = SystemClock.elapsedRealtime()
        val notify = force || now - lastPersist > 1000
        if (notify) {
            try { store.write(snapshot); lastPersist = now }
            catch (_: Exception) {
                PaxFirmwareRuntime.mutable.value = snapshot.copy(phase = PaxInstallPhase.Failed,
                    detail = "Не удалось сохранить журнал установки. Операция остановлена; проверьте терминал.")
                throw PaxDiagnosticException("Не удалось сохранить журнал установки. Операция остановлена; проверьте терминал.")
            }
        }
        PaxFirmwareRuntime.mutable.value = snapshot
        if (notify) notifications.notify(NOTIFICATION, notification(snapshot.detail))
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_nav_folder_open)
            .setContentTitle("PAX D270 · ${snapshot.step?.title ?: "Установка ПО"}").setContentText(text)
            .setContentIntent(open).setOngoing(snapshot.active).setOnlyAlertOnce(true)
            .setProgress(if (snapshot.totalBytes > 0) 100 else snapshot.stepCount,
                if (snapshot.totalBytes > 0) (snapshot.bytes * 100 / snapshot.totalBytes).toInt() else (snapshot.stepNumber - 1).coerceAtLeast(0),
                snapshot.active && snapshot.totalBytes == 0L).build()
    }
    override fun onDestroy() {
        job?.cancel()
        runCatching { link.getAndSet(null)?.close() }
        // Allow the cancelled job's finally to persist its interrupted outcome.
        val finished: () -> Unit = { synchronized(Companion) { serviceAlive = false }; scope.cancel() }
        job?.invokeOnCompletion { finished() } ?: finished()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "pax-firmware"
        private const val NOTIFICATION = 0x504158
        private var serviceAlive = false
        fun start(context: Context, owner: String, bundle: String, device: String, fingerprint: String) = synchronized(this) {
            check(!serviceAlive && !PaxFirmwareRuntime.state.value.active) { "Служба PAX ещё занята. Дождитесь её завершения." }
            val previous = PaxFirmwareRuntime.state.value
            val operation = UUID.randomUUID().toString()
            PaxFirmwareRuntime.mutable.value = PaxInstallState(owner, PaxInstallPhase.Running, bundle, PaxFirmwareStep.Reading, "Подготовка установки", operationId = operation)
            try {
                ContextCompat.startForegroundService(context, Intent(context, PaxFirmwareService::class.java)
                    .putExtra("owner", owner).putExtra("bundle", bundle).putExtra("device", device).putExtra("fingerprint", fingerprint).putExtra("operation", operation))
            } catch (failure: Exception) { PaxFirmwareRuntime.mutable.value = previous; throw failure }
        }
    }
}

private class AndroidPaxFirmwareHost(
    private val context: Context, private val manager: UsbManager, private val onLink: (PaxUsbConnection?) -> Unit,
    private val onBytes: (Long, Long) -> Unit, private val onWaiting: (String) -> Unit,
) : PaxFirmwareHost {
    override suspend fun open(key: String): PaxFirmwarePort {
        val device = manager.deviceList[key] ?: throw PaxDiagnosticException("PAX отключён. Установка не начата.")
        val connection = PaxUsbConnection.open(manager, device)
        onLink(connection)
        try {
            val session = PaxInstallerSession(connection.wire, onBytes)
            session.connect()
            return Port(connection, session)
        } catch (failure: Throwable) { connection.close(); onLink(null); throw failure }
    }
    override suspend fun reconnect(previousKey: String, fingerprint: String): PaxFirmwarePort = withTimeout(240_000) {
        var sawDisconnect = false
        val requested = mutableSetOf<String>()
        while (true) {
            currentCoroutineContext().ensureActive()
            val devices = manager.deviceList
            if (previousKey !in devices) sawDisconnect = true
            val candidates = devices.values.filter { it.vendorId == 0x2FB8 && it.productId == 0x1101 }
            paxRequire(candidates.size <= 1, "После перезагрузки обнаружено несколько PAX. Продолжение заблокировано.")
            val candidate = candidates.singleOrNull()
            if (candidate != null && (sawDisconnect || candidate.deviceName != previousKey)) {
                if (!manager.hasPermission(candidate)) {
                    onWaiting("PAX появился после перезагрузки. Разрешите USB-доступ в диалоге Android.")
                    if (requested.add(candidate.deviceName)) {
                        val pending = PendingIntent.getBroadcast(context, candidate.deviceId,
                            Intent("${context.packageName}.PAX_INSTALL_PERMISSION").setPackage(context.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        manager.requestPermission(candidate, pending)
                    }
                } else {
                    val port = try { open(candidate.deviceName) } catch (failure: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        if (failure is TimeoutCancellationException) null else throw failure
                    }
                        catch (_: Exception) { null }
                    if (port != null) {
                        val passport = try { port.passport() } catch (failure: CancellationException) {
                            port.close(); currentCoroutineContext().ensureActive()
                            if (failure is TimeoutCancellationException) null else throw failure
                        }
                            catch (_: Exception) { port.close(); null }
                        if (passport != null) {
                            if (passport.fingerprint != fingerprint) { port.close(); throw PaxDiagnosticException("После перезагрузки подключён другой PAX. Продолжение заблокировано.") }
                            return@withTimeout port
                        }
                    }
                    onWaiting("Откройте main menu PAX → General → XCB service → USB. Ожидаем ответ того же терминала.")
                }
            }
            delay(1000)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    private class Port(private val connection: PaxUsbConnection, private val session: PaxInstallerSession) : PaxFirmwarePort {
        override suspend fun passport() = session.passport()
        override suspend fun upload(asset: PaxFirmwareAsset) = session.upload(PaxTransferAsset(asset.name, asset.size,
            asset.file.lastModified().coerceAtLeast(0) / 1000) { asset.file.inputStream() })
        override suspend fun removeMainApp() = session.removeMainApp()
        override suspend fun installBoot(remote: String) = session.installBoot(remote)
        override suspend fun installOs(remote: String) = session.installOs(remote)
        override suspend fun installMainApp(remote: String) = session.installMainApp(remote)
        override suspend fun installAppData(remote: String) = session.installAppData(remote)
        override suspend fun installOption(remote: String) = session.installOption(remote)
        override suspend fun reboot() = session.reboot()
        override fun close() = connection.close()
    }
}
