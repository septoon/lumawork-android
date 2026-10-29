package ru.lumastack.lumawork.data.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlinx.coroutines.*
import ru.lumastack.lumawork.MainActivity
import ru.lumastack.lumawork.R

/** User-confirmed operations. Checkpoints survive a process stop; writes never restart automatically. */
class PaxFirmwareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val link = AtomicReference<AutoCloseable?>(null)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private lateinit var store: PaxFirmwareStore
    private var snapshot = PaxInstallState()
    private var lastPersist = 0L
    private val usbEpoch = AtomicLong()
    private var observingUsb = false
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device != null && isPaxXcbUsbId(device.vendorId, device.productId)) usbEpoch.incrementAndGet()
        }
    }
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private val manager get() = getSystemService(USB_SERVICE) as UsbManager

    override fun onCreate() {
        super.onCreate()
        synchronized(Companion) { serviceAlive = true }
        store = PaxFirmwareStore(this)
        ContextCompat.registerReceiver(this, usbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        observingUsb = true
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Установка ПО PAX", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_NOT_STICKY
        val reserved = PaxFirmwareRuntime.state.value
        val key = intent?.getStringExtra("device")
        if (key == null || !reserved.active || reserved.operationId != intent.getStringExtra("operation")) { stopSelf(); return START_NOT_STICKY }
        snapshot = reserved
        try {
            val notification = notification(snapshot.detail)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION, notification)
        } catch (_: Exception) {
            terminal(PaxInstallPhase.Failed, "Android не разрешил USB-службу. Вернитесь в приложение и продолжите.")
            stopSelf(); return START_NOT_STICKY
        }
        job = scope.launch {
            try {
                wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LumaWork:PaxInstall")
                    .apply { acquire(30 * 60_000L) }
                publish(snapshot)
                val bundle = snapshot.bundleDirectory?.let { readPaxPackage(store.checkedBundleDirectory(snapshot.owner, it), snapshot.model) }
                val identity = bundle?.identity()
                paxRequire(snapshot.packageIdentity == null || snapshot.packageIdentity == identity, "Комплект изменился после остановки. Продолжение заблокировано.")
                val count = if (snapshot.model == PaxModel.S210) if (snapshot.mode == PaxInstallMode.Full) 3 else if (snapshot.mode == PaxInstallMode.Prolin) 2 else 1 else
                    (if (snapshot.mode in listOf(PaxInstallMode.Full, PaxInstallMode.RemoveMainApp)) 1 else 0) +
                    (if (snapshot.mode in listOf(PaxInstallMode.Full, PaxInstallMode.Prolin)) 3 + (if (bundle?.boot != null) 1 else 0) else 0) +
                    (if (snapshot.mode in listOf(PaxInstallMode.Full, PaxInstallMode.MainApp)) 1 + (if (snapshot.mode == PaxInstallMode.Full) 1 else 0) + (bundle?.appData?.size ?: 0) + (bundle?.options?.size ?: 0) else 0) + 1
                publish(snapshot.copy(packageIdentity = identity, stepCount = count))
                withTimeout(25 * 60_000L) {
                    if (snapshot.model == PaxModel.S210) runPinpad(requireNotNull(bundle))
                    else runTerminal(bundle, key)
                }
            } catch (_: TimeoutCancellationException) {
                terminal(PaxInstallPhase.Interrupted, "Время ожидания истекло. Подключите тот же PAX и нажмите «Продолжить».")
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { terminal(PaxInstallPhase.Interrupted, "Операция остановлена. Подключите тот же PAX и нажмите «Продолжить».") }
                throw cancelled
            } catch (failure: Exception) {
                terminal(PaxInstallPhase.Failed, if (failure is PaxDiagnosticException || failure is IllegalArgumentException)
                    failure.message ?: "Операция не завершена." else "Операция остановлена. Проверьте подключение и продолжите с сохранённого этапа.")
            } finally {
                runCatching { link.getAndSet(null)?.close() }
                wake?.let { if (it.isHeld) it.release() }; wake = null
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runTerminal(bundle: PaxFirmwarePackage?, key: String) {
        val host = AndroidPaxFirmwareHost(this, manager, onLink = { link.set(it) },
            onBytes = { bytes, total -> publish(snapshot.copy(bytes = bytes, totalBytes = total), force = false) },
            onWaiting = { text -> publish(snapshot.copy(detail = text), force = false) },
            epoch = { usbEpoch.get() }, allowCurrent = snapshot.pendingAction == "reconnect" || ("reboot" in snapshot.completedActions && "reconnect" !in snapshot.completedActions),
            model = snapshot.model)
        // Migration of the old stage-7 journal: the operator explicitly confirms a fresh MAINAPP operation.
        // Old journals have no identity checkpoint, so only the captured D270 package and expected OS/boot permit this gate.
        if (snapshot.deviceFingerprint == null) throw PaxDiagnosticException("Прочитайте паспорт PAX перед запуском операции.")
        if (snapshot.legacyRecovery) {
            val value = host.open(key).use { it.passport() }
            paxRequire(bundle != null && value.model == "D270" && value.target == "Inpas" &&
                paxVersionMatches(value.os, bundle.expectedOs) && paxVersionMatches(value.boot, bundle.expectedBoot),
                "Перед продолжением MAINAPP нужно подтвердить ОС и загрузчик из прежнего комплекта.")
        }
        val result = PaxFirmwareWorkflow(host, event = { step, file ->
            publish(snapshot.copy(step = step, fileName = file, bytes = 0, totalBytes = 0,
                stepNumber = if (step == PaxFirmwareStep.Complete) snapshot.stepCount else snapshot.completedActions.size + 1,
                critical = snapshot.critical || step in listOf(PaxFirmwareStep.Removing, PaxFirmwareStep.BootInstall, PaxFirmwareStep.OsInstall, PaxFirmwareStep.AppInstall),
                detail = if (step == PaxFirmwareStep.Reconnect) "После перезапуска: ${snapshot.model.xcbUsbMenuPath}. Разрешите USB-доступ; продолжение автоматическое."
                    else step.title))
        }, checkpoint = { done, pending ->
            publish(snapshot.copy(completedActions = done, pendingAction = pending,
                recoveryRequired = pending != null && pending !in listOf("reconnect", "reboot", "verify")))
        }).execute(bundle, snapshot.mode, key, requireNotNull(snapshot.deviceFingerprint), snapshot.model, snapshot.completedActions, snapshot.pendingAction)
        publish(snapshot.copy(phase = PaxInstallPhase.Complete, step = PaxFirmwareStep.Complete, stepNumber = snapshot.stepCount, fileName = null, pendingAction = null,
            recoveryRequired = false, detail = "Подтверждено: ${result.model} · ОС ${result.os ?: "—"} · MAINAPP ${result.mainApp ?: "отсутствует"} · загрузчик ${result.boot ?: "—"}."))
    }

    private suspend fun runPinpad(bundle: PaxFirmwarePackage) {
        paxRequire(snapshot.mode != PaxInstallMode.RemoveMainApp, "Удаление MAINAPP для S210 не поддерживается.")
        paxRequire(snapshot.mode != PaxInstallMode.MainApp || snapshot.deviceFingerprint != null, "Перед отдельной записью APP прочитайте данные S210 в системном меню.")
        val expectedOs = snapshot.expectedPinpadOs
        paxRequire(!expectedOs.isNullOrBlank(), "Укажите ожидаемую версию OS S210 из документации комплекта.")
        val ids = buildList { if (snapshot.mode != PaxInstallMode.MainApp) addAll(listOf("pinpad-os", "pinpad-os-check")); if (snapshot.mode != PaxInstallMode.Prolin) add("pinpad-app") }
        paxRequire(snapshot.completedActions == ids.take(snapshot.completedActions.size), "Журнал S210 не соответствует операции.")
        paxRequire(snapshot.pendingAction == null || snapshot.pendingAction == ids.getOrNull(snapshot.completedActions.size), "Неподтверждённый этап S210 не соответствует журналу.")
        if ("pinpad-os" in ids) paxRequire(bundle.os != null, "В комплекте S210 нет OS.")
        if ("pinpad-app" in ids) paxRequire(bundle.application != null, "В комплекте S210 нет APP.")
        if (snapshot.completedActions == ids) {
            waitPinpad(PaxPinpadMode.SYSTEM_MENU).use { checkPaxPinpadGate(it.readInfo(), snapshot.deviceFingerprint, expectedOs) }
            link.set(null)
        }
        while (snapshot.completedActions.size < ids.size) {
            var id = ids[snapshot.completedActions.size]
            val os = id == "pinpad-os"
            val checkOnly = id == "pinpad-os-check"
            publish(snapshot.copy(pendingAction = id, stepNumber = snapshot.completedActions.size + 1, bytes = 0, totalBytes = 0, fileName = if (os) bundle.os?.name else bundle.application?.name,
                step = PaxFirmwareStep.Reconnect,
                detail = if (os) "S210: при подключении удерживайте жёлтую кнопку (белый экран)." else "S210: после перезагрузки откройте системное меню. Ожидаем подключение."))
            val session = if (os) waitPinpad(PaxPinpadMode.OS_BOOT) else waitPinpad(PaxPinpadMode.SYSTEM_MENU)
            session.use {
                if (!os) {
                    val info = session.readInfo()
                    checkPaxPinpadGate(info, snapshot.deviceFingerprint, expectedOs,
                        bindAfterOs = checkOnly && "pinpad-os" in snapshot.completedActions)
                    publish(snapshot.copy(deviceFingerprint = info.fingerprint))
                }
                val progress: (PaxPinpadStage, Long, Long) -> Unit = { stage, bytes, total ->
                    val step = when (stage) {
                        PaxPinpadStage.DownloadingOs -> PaxFirmwareStep.OsUpload
                        PaxPinpadStage.DownloadingApp -> PaxFirmwareStep.AppUpload
                        PaxPinpadStage.ErasingOs, PaxPinpadStage.ErasingFlash, PaxPinpadStage.WritingOs -> PaxFirmwareStep.OsInstall
                        else -> PaxFirmwareStep.AppInstall
                    }
                    val detail = when (stage) {
                        PaxPinpadStage.ErasingOs -> "Очистка области ОС"
                        PaxPinpadStage.ErasingFlash -> "Подготовка flash"
                        PaxPinpadStage.WritingOs -> "Запись ОС во flash"
                        PaxPinpadStage.ErasingApp -> "Очистка области APP"
                        PaxPinpadStage.FinalizingApp -> "Подготовка APP"
                        PaxPinpadStage.WritingApp -> "Запись APP во flash"
                        else -> step.titleFor(snapshot.model)
                    }
                    publish(snapshot.copy(step = step, detail = detail, bytes = bytes, totalBytes = total, critical = true), force = false)
                }
                if (checkOnly && snapshot.mode == PaxInstallMode.Full) {
                    // POSInfo and APP belong to one menu/CDC session. Closing the handle here
                    // avoids another CDC reopen/handshake between OS verification and APP.
                    publish(snapshot.copy(completedActions = snapshot.completedActions + id, pendingAction = null, recoveryRequired = false))
                    id = "pinpad-app"
                    publish(snapshot.copy(pendingAction = id, stepNumber = snapshot.completedActions.size + 1,
                        step = PaxFirmwareStep.AppUpload, fileName = bundle.application?.name,
                        bytes = 0, totalBytes = 0, detail = "OS подтверждена. Начинается загрузка APP."))
                }
                if (os || !checkOnly || id == "pinpad-app") {
                    // Mark uncertainty immediately before the first mutating command, not during read-only reconnect.
                    publish(snapshot.copy(recoveryRequired = true))
                    if (os) session.installOs(requireNotNull(bundle.os), progress)
                    else session.installApp(requireNotNull(bundle.application), progress)
                }
            }
            link.set(null)
            publish(snapshot.copy(completedActions = snapshot.completedActions + id, pendingAction = null, recoveryRequired = false))
        }
        publish(snapshot.copy(phase = PaxInstallPhase.Complete, step = PaxFirmwareStep.Complete, stepNumber = snapshot.stepCount, recoveryRequired = false,
            detail = if (snapshot.mode == PaxInstallMode.Prolin) "После перезагрузки S210 подтвердил OS $expectedOs. Теперь можно загрузить APP."
                else "S210 подтвердил Writing 100%. Нажмите красную Cancel на пинпаде, дождитесь загрузки и проверьте версию APP. Версия после перезагрузки приложением не проверена."))
    }
    private suspend fun openPinpad(key: String, mode: PaxPinpadMode): PaxPinpadSession {
        val device = manager.deviceList[key] ?: throw PaxConnectionLostException("S210 отключён. Подключите его в нужном режиме и продолжите.")
        val connection = PaxPinpadUsbConnection.open(manager, device)
        link.set(connection)
        try { return PaxPinpadSession(connection).also { it.connect(mode) } }
        catch (failure: Throwable) { connection.close(); link.set(null); throw failure }
    }
    private suspend fun waitPinpad(mode: PaxPinpadMode): PaxPinpadSession = withTimeout(240_000) {
        delay(1500)
        val requested = mutableSetOf<String>()
        var revision = PaxFirmwareRuntime.reconnectRevision
        while (true) {
            if (revision != PaxFirmwareRuntime.reconnectRevision) { requested.clear(); revision = PaxFirmwareRuntime.reconnectRevision }
            val devices = manager.deviceList.values.filter { it.vendorId == 0x2fb8 && it.productId == 0x3101 }
            paxRequire(devices.size <= 1, "Оставьте подключённым один S210.")
            devices.singleOrNull()?.let { device ->
                if (!manager.hasPermission(device)) {
                    if (requested.add(device.deviceName)) requestFirmwarePermission(this@PaxFirmwareService, manager, device)
                } else {
                    try { return@withTimeout openPinpad(device.deviceName, mode) }
                    catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        if (cancelled !is TimeoutCancellationException) throw cancelled
                    } catch (_: Exception) { /* Read-only handshake only. No writes are retried here. */ }
                }
            }
            delay(1000)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    private fun terminal(phase: PaxInstallPhase, detail: String) {
        val value = snapshot.copy(phase = phase, detail = detail, updatedAt = System.currentTimeMillis())
        runCatching { store.write(value) }; snapshot = value; PaxFirmwareRuntime.mutable.value = value
    }
    private fun publish(value: PaxInstallState, force: Boolean = true) {
        snapshot = value.copy(updatedAt = System.currentTimeMillis())
        val now = SystemClock.elapsedRealtime()
        val notify = force || now - lastPersist > 1000
        if (notify) {
            try { store.write(snapshot); lastPersist = now }
            catch (_: Exception) { throw PaxDiagnosticException("Не удалось сохранить журнал. Операция остановлена до следующей команды.") }
        }
        PaxFirmwareRuntime.mutable.value = snapshot
        if (notify) notifications.notify(NOTIFICATION, notification(snapshot.detail))
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_nav_folder_open)
            .setContentTitle("${snapshot.model.title} · ${snapshot.step?.titleFor(snapshot.model) ?: snapshot.mode.titleFor(snapshot.model)}").setContentText(text)
            .setContentIntent(open).setOngoing(snapshot.active).setOnlyAlertOnce(true)
            .setProgress(100, if (snapshot.totalBytes > 0) (snapshot.bytes * 100 / snapshot.totalBytes).toInt() else 0,
                snapshot.active && snapshot.totalBytes == 0L).build()
    }
    override fun onDestroy() {
        job?.cancel(); runCatching { link.getAndSet(null)?.close() }
        if (observingUsb) { unregisterReceiver(usbReceiver); observingUsb = false }
        val operation = snapshot.operationId
        val finished: () -> Unit = {
            terminalOperationGate.release(operation)
            synchronized(Companion) { serviceAlive = false }; scope.cancel()
        }
        job?.invokeOnCompletion { finished() } ?: finished()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "pax-firmware"
        private const val NOTIFICATION = 0x504158
        private var serviceAlive = false
        fun start(context: Context, owner: String, bundle: String?, device: String, fingerprint: String?,
            model: PaxModel = PaxModel.D270, mode: PaxInstallMode = PaxInstallMode.Full, resume: Boolean = false, expectedPinpadOs: String? = null,
        ) = synchronized(this) {
            check(!serviceAlive && !PaxFirmwareRuntime.state.value.active) { "USB-служба ещё занята. Дождитесь её завершения." }
            val store = PaxFirmwareStore(context)
            val saved = if (resume) store.read(owner) else null
            val operation = UUID.randomUUID().toString()
            var start = if (saved != null) {
                check(saved.canResume && saved.model == model) { "Нет сохранённой операции этой модели." }
                check(saved.deviceFingerprint == null || saved.deviceFingerprint == fingerprint || model == PaxModel.S210 && fingerprint == null) { "Продолжение возможно только на прежнем PAX." }
                val legacy = saved.deviceFingerprint == null && model != PaxModel.S210
                if (legacy) check(saved.step in listOf(PaxFirmwareStep.Reboot, PaxFirmwareStep.Reconnect, PaxFirmwareStep.CheckingOs)) {
                    "Старый журнал не содержит подтверждённых этапов. Выберите отдельную операцию после проверки PAX."
                }
                saved.copy(phase = PaxInstallPhase.Running, mode = if (legacy) PaxInstallMode.MainApp else saved.mode,
                    legacyRecovery = legacy || saved.legacyRecovery, deviceFingerprint = saved.deviceFingerprint ?: fingerprint,
                    operationId = operation, detail = "Проверка сохранённого этапа")
            } else PaxInstallState(owner = owner, phase = PaxInstallPhase.Running, bundleDirectory = bundle, model = model, mode = mode,
                deviceFingerprint = fingerprint, operationId = operation, expectedPinpadOs = expectedPinpadOs, detail = "Подготовка операции")
            check(model != PaxModel.S210 || start.mode != PaxInstallMode.MainApp || start.deviceFingerprint != null) { "Перед отдельной записью APP прочитайте данные S210 в системном меню." }
            start = start.copy(updatedAt = System.currentTimeMillis())
            check(terminalOperationGate.tryAcquire(operation)) { "Другая USB-операция ещё выполняется. Дождитесь её завершения." }
            try {
                store.write(start)
                PaxFirmwareRuntime.mutable.value = start
                ContextCompat.startForegroundService(context, Intent(context, PaxFirmwareService::class.java).putExtra("device", device).putExtra("operation", operation))
            }
            catch (failure: Exception) {
                terminalOperationGate.release(operation)
                val interrupted = start.copy(phase = PaxInstallPhase.Interrupted, detail = "USB-служба не запущена. Нажмите «Продолжить».")
                PaxFirmwareRuntime.mutable.value = interrupted
                runCatching { store.write(interrupted) }
                throw failure
            }
        }
    }
}

private fun PaxFirmwarePackage.identity(): String {
    val value = (listOfNotNull(boot, os, application) + appData + options).joinToString("\n") { "${it.name}:${it.size}:${it.sha256}" }
    return java.security.MessageDigest.getInstance("SHA-256").digest("${model.name}\n$value".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
private fun requestFirmwarePermission(context: Context, manager: UsbManager, device: android.hardware.usb.UsbDevice) {
    val pending = PendingIntent.getBroadcast(context, device.deviceId,
        Intent("${context.packageName}.PAX_INSTALL_PERMISSION").setPackage(context.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    manager.requestPermission(device, pending)
}
private class AndroidPaxFirmwareHost(
    private val context: Context, private val manager: UsbManager, private val onLink: (PaxUsbConnection?) -> Unit,
    private val onBytes: (Long, Long) -> Unit, private val onWaiting: (String) -> Unit,
    private val epoch: () -> Long, private val allowCurrent: Boolean,
    private val model: PaxModel,
) : PaxFirmwareHost {
    private var initialEpoch = epoch()
    private var allowCurrentDevice = allowCurrent
    private var retryRevision = PaxFirmwareRuntime.reconnectRevision
    override fun armReboot() {
        initialEpoch = epoch()
        allowCurrentDevice = false
        retryRevision = PaxFirmwareRuntime.reconnectRevision
    }
    override suspend fun open(key: String): PaxFirmwarePort {
        val device = manager.deviceList[key] ?: throw PaxDiagnosticException("PAX отключён. Установка не начата.")
        val connection = PaxUsbConnection.open(manager, device)
        onLink(connection)
        try {
            val session = PaxInstallerSession(connection.wire, onBytes)
            session.connect()
            return Port(connection, session, model)
        } catch (failure: Throwable) { connection.close(); onLink(null); throw failure }
    }
    override suspend fun reconnect(previousKey: String, fingerprint: String): PaxFirmwarePort = withTimeout(240_000) {
        delay(1500) // Close the old handle; Android may reuse its device path before polling sees detach.
        val requested = mutableSetOf<String>()
        var revision = retryRevision
        var manual = allowCurrentDevice
        var sawDisconnect = false
        while (true) {
            if (revision != PaxFirmwareRuntime.reconnectRevision) { requested.clear(); revision = PaxFirmwareRuntime.reconnectRevision; manual = true }
            currentCoroutineContext().ensureActive()
            val devices = manager.deviceList
            if (previousKey !in devices) sawDisconnect = true
            val candidates = devices.values.filter { isPaxXcbUsbId(it.vendorId, it.productId) }
            paxRequire(candidates.size <= 1, "После перезагрузки обнаружено несколько PAX. Продолжение заблокировано.")
            val candidate = candidates.singleOrNull()
            if (candidate != null && (manual || sawDisconnect || epoch() != initialEpoch || candidate.deviceName != previousKey)) {
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
                    onWaiting("Откройте ${model.xcbUsbMenuPath}. Ожидаем ответ того же терминала.")
                }
            }
            delay(1000)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    private class Port(private val connection: PaxUsbConnection, private val session: PaxInstallerSession, private val model: PaxModel) : PaxFirmwarePort {
        override suspend fun passport() = session.passport()
        override suspend fun upload(asset: PaxFirmwareAsset) = session.upload(PaxTransferAsset(asset.name, asset.size,
            asset.file.lastModified().coerceAtLeast(0) / 1000) { asset.file.inputStream() })
        override suspend fun removeMainApp() = session.removeMainApp()
        override suspend fun installBoot(remote: String) = session.installBoot(remote)
        override suspend fun installOs(remote: String) = session.installOs(remote, model)
        override suspend fun installMainApp(remote: String) = session.installMainApp(remote)
        override suspend fun installAppData(remote: String) = session.installAppData(remote)
        override suspend fun installOption(remote: String) = session.installOption(remote)
        override suspend fun reboot() = session.reboot()
        override fun close() = connection.close()
    }
}
