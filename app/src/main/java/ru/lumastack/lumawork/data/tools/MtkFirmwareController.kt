package ru.lumastack.lumawork.data.tools

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val mtkPreparationMutex = Mutex()

data class MtkFirmwareUiState(val installation: MtkInstallState = MtkInstallState(), val firmware: MtkFirmwarePackage? = null,
    val agentVerified: Boolean = false, val loading: Boolean = true, val error: String? = null,val serviceRunning: Boolean=false) {
    val canStartDownload: Boolean get() = !loading && !serviceRunning && agentVerified && firmware!=null && installation.canWrite
}

/** Visible-screen preparation/permission and diagnostics; long Firmware Upgrade transfers to the foreground service. */
class MtkFirmwareController(context: Context, private val owner: String, private val scope: CoroutineScope,
    private val onResult: suspend (MtkInstallState) -> Unit,
) : AutoCloseable {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val store = MtkFirmwareStore(this.context, owner)
    private val mutable = MutableStateFlow(MtkFirmwareUiState(installation = MtkInstallState(owner = owner)))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val link = AtomicReference<MtkWire?>()
    private val epoch = AtomicLong()
    private var visible = false
    private var registered = false
    private val permissionAction = "${context.packageName}.MTK_INSPECT_PERMISSION"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED || intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                // A path can be reused between polls. Any bus topology change invalidates an open lease.
                epoch.incrementAndGet()
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) link.getAndSet(null)?.close()
            }
        }
    }
    init {
        scope.launch {
            try { mtkPreparationMutex.withLock {
                val running=MtkFirmwareRuntime.state.value?.takeIf { it.owner==owner && it.active }
                val recovered = running ?: withContext(Dispatchers.IO) { store.read() }
                if(running==null) withContext(Dispatchers.IO) {
                    cleanupMtkImports(store.directory, recovered.packageDirectory?.let(store::packageDirectory))
                }
                mutable.update { it.copy(installation = recovered,serviceRunning=running!=null) }
                val livePackage=MtkFirmwareRuntime.request?.takeIf { running!=null && it.initial.owner==owner }?.pkg
                if (running == null) withContext(Dispatchers.IO) {
                    MtkFirmwarePackageReader(checkpoint = { ensureActive() }).ensureBundledAgent(store.agent) {
                        context.assets.open(MTK_P8_AGENT_ASSET)
                    }
                }
                mutable.update { it.copy(agentVerified = true) }
                if(livePackage!=null) mutable.update { it.copy(firmware=livePackage,agentVerified=true) }
                else recovered.packageDirectory?.let { path ->
                    val pkg = withContext(Dispatchers.IO) { MtkFirmwarePackageReader(checkpoint = { ensureActive() }).inspect(store.packageDirectory(path)) }
                    require(pkg.sha256 == recovered.packageHash) { "Подготовленный комплект изменился. Выберите его заново." }
                    mutable.update { it.copy(firmware = pkg, agentVerified = true) }
                }
                if (!recovered.active) onResult(recovered)
            }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(error = failure.message ?: "Не удалось восстановить комплект P8. Выберите его заново.") } }
            finally { mutable.update { it.copy(loading = false) } }
            MtkFirmwareRuntime.state.collect { current ->
                if(current!=null && current.owner==owner && (current.active || current.operationId==state.value.installation.operationId)) {
                    mutable.update { it.copy(installation=current,serviceRunning=current.active) }
                    if(!current.active) onResult(current)
                }
            }
        }
    }
    fun setVisible(value: Boolean) {
        visible = value
        if (value && !registered) {
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        } else if (!value) {
            cancelInspection()
            if (registered) { context.unregisterReceiver(receiver); registered = false }
        }
    }
    fun preparePackage(open: suspend () -> InputStream) {
        if (!state.value.agentVerified) { mutable.update { it.copy(error = "Встроенный Download Agent не подготовлен. Откройте вкладку P8 заново.") }; return }
        prepare {
            val before = state.value.firmware
            val target = File(store.directory, "package-${UUID.randomUUID()}")
            var last = 0L
            val reader = MtkFirmwarePackageReader(checkpoint = { ensureActive() }, progress = { name, bytes, total ->
                val now = System.currentTimeMillis()
                if (now - last >= 200 || bytes == total) { last = now; mutable.update { it.copy(installation = it.installation.copy(fileName = name, bytes = bytes, totalBytes = total)) } }
            })
            val pkg = open().use { reader.prepare(it, target, store.agent) }
            try {
                persist(state.value.installation.copy(phase = MtkInstallPhase.Prepared, packageDirectory = target.path, packageHash = pkg.sha256,
                    detail = "Проверены scatter, 18 образов и обе ступени агента. Нажмите Download и подключите выключенный P8 после приглашения.", fileName = null, bytes = 0, totalBytes = 0))
                mutable.update { it.copy(firmware = pkg) }
                before?.directory?.takeIf { it != target }?.let { store.packageDirectory(it.path).deleteRecursively() }
            } catch (failure: Throwable) { if (state.value.firmware?.directory != target) target.deleteRecursively(); throw failure }
        }
    }
    private fun prepare(block: suspend CoroutineScope.() -> Unit) {
        if (state.value.loading || state.value.installation.active || job?.isActive == true || terminalOperationGate.owner.value != null) {
            mutable.update { it.copy(error = "Дождитесь завершения текущей операции, затем выберите комплект повторно. Скачанный ZIP остаётся в FTP-файлах.") }
            return
        }
        mutable.update { it.copy(loading = true, error = null) }
        job = scope.launch {
            try { withContext(Dispatchers.IO) { mtkPreparationMutex.withLock {
                persist(state.value.installation.freshOperation(UUID.randomUUID().toString(),MtkInstallPhase.Preparing,false,"Проверка локальных файлов…")); block()
            } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                withContext(Dispatchers.IO) { persist(state.value.installation.copy(phase = MtkInstallPhase.Failed,
                    detail = failure.message ?: "Комплект не подготовлен.")) }
                mutable.update { it.copy(error = failure.message ?: "Не удалось подготовить комплект.") }
            } finally { mutable.update { it.copy(loading = false) } }
        }
    }
    fun inspect() = startInspection(loadAgent = false)
    fun inspectAgent() = startInspection(loadAgent = true)
    fun download() = startInspection(loadAgent=true,upgrade=true)
    private fun startInspection(loadAgent: Boolean,upgrade: Boolean=false) {
        if (!visible || state.value.loading || job?.isActive == true) return
        if(upgrade && (state.value.firmware==null || !state.value.installation.canWrite)) return
        if (loadAgent && !state.value.agentVerified) { mutable.update { it.copy(error = "Встроенный Download Agent не подготовлен. Откройте вкладку P8 заново.") }; return }
        val operation = UUID.randomUUID().toString()
        if (!terminalOperationGate.tryAcquire(operation)) { mutable.update { it.copy(error = "USB занят другой операцией.") }; return }
        mutable.update { it.copy(error = null,serviceRunning=false, installation = it.installation.freshOperation(operation,
            if(upgrade) MtkInstallPhase.VerifyingFiles else MtkInstallPhase.WaitingForUsb,upgrade,
            if(upgrade) "Проверка исходных файлов перед Download. Пока не подключайте терминал."
                else if (loadAgent) "Подключите выключенный терминал по USB. Агент будет запущен только в RAM, без записи eMMC."
                else "Подключите выключенный терминал по USB. Проверка без записи eMMC.")) }
        val transferred=AtomicBoolean(false)
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    persist(state.value.installation)
                    if(upgrade) {
                        val before=requireNotNull(state.value.firmware)
                        var last=0L
                        val checked=MtkFirmwarePackageReader(checkpoint={ensureActive()},progress={name,bytes,total ->
                            val now=System.nanoTime()
                            if(bytes==total || now-last>200_000_000) {
                                last=now;mutable.update { it.copy(installation=it.installation.copy(fileName=name,bytes=bytes,totalBytes=total)) }
                            }
                        }).inspect(before.directory)
                        require(checked.sha256==before.sha256) { "Комплект изменился после подготовки." }
                        mutable.update { it.copy(firmware=checked) }
                        persist(state.value.installation.copy(phase=MtkInstallPhase.WaitingForUsb,fileName=null,bytes=0,totalBytes=0,
                            detail="Подключите выключенный P8 по USB для прошивки."))
                    }
                }
                val selection = MtkUsbSelection()
                val requested = mutableSetOf<Pair<String, Long>>()
                withTimeout(120_000) {
                    while (true) {
                        ensureActive()
                        val devices = manager.deviceList.values.filter { isMtkPreloader(it.vendorId, it.productId) }
                        val candidates = devices.map { MtkUsbCandidate(it.deviceName, it.deviceId, epoch.get(), manager.hasPermission(it)) }
                        val chosen = selection.select(candidates)
                        if (chosen != null) {
                            val device = devices.single()
                            if (!chosen.permission) {
                                mutable.update { it.copy(installation = it.installation.copy(phase = MtkInstallPhase.Permission,
                                    usbPermission = false, detail = "Разрешите USB-доступ. Если терминал исчезнет, переподключите выключенный P8. Для повторного запроса отмените ожидание и начните проверку заново.")) }
                                if (requested.add(chosen.key to chosen.epoch)) {
                                    val pending = PendingIntent.getBroadcast(context, device.deviceId, Intent(permissionAction).setPackage(context.packageName),
                                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                                    manager.requestPermission(device, pending)
                                }
                            } else {
                                withContext(Dispatchers.IO) {
                                    persist(state.value.installation.copy(phase = MtkInstallPhase.Handshake, usbPermission = true, detail = "Чтение идентификаторов Preloader…"))
                                    if(upgrade) {
                                        MtkFirmwareService.start(context,state.value.installation,requireNotNull(state.value.firmware),device.deviceName,device.deviceId)
                                        transferred.set(true)
                                    } else {
                                    val current = {
                                        manager.deviceList[chosen.key]?.let { MtkUsbCandidate(it.deviceName, it.deviceId, epoch.get(), manager.hasPermission(it)) }
                                    }
                                    check(selection.canOpen(chosen, current())) { "Терминал изменился после разрешения USB." }
                                    val wire = MtkUsbConnection.open(manager, device, { selection.canOpen(chosen, current()) }, {
                                        ensureActive()
                                        check(epoch.get() == chosen.epoch) { "Терминал изменился во время обмена USB." }
                                    })
                                    link.set(wire)
                                    try { wire.use {
                                        val preloader = MtkPreloaderSession(it)
                                        val info = preloader.inspect()
                                        if (loadAgent) {
                                            // Journal the possibility of RAM execution before SEND_DA/JUMP_DA.
                                            persist(state.value.installation.copy(phase = MtkInstallPhase.LoadingAgent, info = info, agentStarted = true,
                                                detail = "Проверка и запуск исходного агента в RAM. eMMC не записывается."))
                                            val source = state.value.firmware?.agent ?: store.agent
                                            val agent = preloader.loadAgent(source)
                                            val inspected = agent.inspectTarget(info)
                                            persist(state.value.installation.copy(target = inspected, detail = "Параметры агента прочитаны. Проверка PGPT/SGPT, CRC и границ разделов…"))
                                            val target = agent.inspectLayout(inspected)
                                            persist(state.value.installation.copy(phase = MtkInstallPhase.AgentInspected, target = target,
                                                detail = "Агент в RAM ответил: MT6761; eMMC USER ${target.emmc.userSize} байт; DRAM ${target.ram.dramSize} байт; тип таблицы ${target.partitionTableKind}. " +
                                                    "PGPT/SGPT и CRC проверены; ${target.gpt!!.partitions.size} разделов; " +
                                                    (if (target.gpt.matchesP8Profile) "разметка совпала с профилем. " else "разметка отличается от профиля, запись заблокирована. ") +
                                                    (target.boardName?.let { "BOM boot_a: $it. " } ?: "BOM не подтверждён. ") +
                                                    (if (target.compatible) "BOM и параметры совпали с проверенным профилем P8KC. " else "Совместимость с P8KC не подтверждена. ") +
                                                    "Команды записи eMMC не отправлялись. Отсоедините USB и перезапустите терминал."))
                                        } else persist(state.value.installation.copy(phase = MtkInstallPhase.Inspected, info = info,
                                            detail = "Preloader ответил: SoC 0x%04X, HW 0x%04X/0x%04X, SW 0x%04X. Модель P8 не подтверждена. eMMC не изменялась.".format(info.hwCode, info.hwSubCode, info.hwVersion, info.swVersion)))
                                    } } finally { link.compareAndSet(wire, null) }
                                    }
                                }
                                break
                            }
                        } else mutable.update { it.copy(installation = it.installation.copy(phase = MtkInstallPhase.WaitingForUsb, usbPermission = false,
                            detail = if(upgrade) "Подключите выключенный P8 по USB для прошивки."
                                else if (loadAgent) "Подключите выключенный терминал по USB. Агент будет запущен только в RAM, без записи eMMC."
                                else "Подключите выключенный терминал по USB. Проверка без записи eMMC.")) }
                        delay(100)
                    }
                }
                if(!transferred.get()) onResult(state.value.installation)
            } catch (failure: Exception) {
                if(transferred.get()) return@launch
                if (failure !is CancellationException) android.util.Log.w("MtkFirmware", "$operation: diagnostic exchange failed", failure)
                withContext(NonCancellable + Dispatchers.IO) {
                    val interrupted = failure is CancellationException
                    val result = state.value.installation.copy(phase = if (interrupted) MtkInstallPhase.Interrupted else MtkInstallPhase.Failed,
                        usbPermission = false, detail = (if (interrupted) "Ожидание или диагностика остановлены. eMMC не изменялась." else
                            (failure.message ?: "Не удалось прочитать Preloader. Переподключите выключенный P8 и повторите проверку.")) +
                            if (state.value.installation.agentStarted) " Агент мог остаться в RAM. Отсоедините USB и перезапустите терминал." else "")
                    persist(result); onResult(result)
                }
                if (failure is CancellationException) throw failure
            } finally { runCatching { link.getAndSet(null)?.close() }; if(!transferred.get()) terminalOperationGate.release(operation) }
        }
        // Even cancellation before the first coroutine instruction must release the lease.
        job?.invokeOnCompletion { if(!transferred.get()) terminalOperationGate.release(operation) }
    }
    fun cancelInspection() {
        if(MtkFirmwareRuntime.state.value?.let { it.active && it.operationId==state.value.installation.operationId }==true) return
        if (state.value.installation.phase in listOf(MtkInstallPhase.WaitingForUsb, MtkInstallPhase.Permission, MtkInstallPhase.Handshake, MtkInstallPhase.LoadingAgent,MtkInstallPhase.VerifyingFiles)) {
            job?.cancel(); runCatching { link.getAndSet(null)?.close() }
        }
    }
    fun emergencyStop()=MtkFirmwareService.interrupt(state.value.installation.operationId)
    fun confirmBoot() {
        val before=state.value.installation
        if(before.phase!=MtkInstallPhase.WriteComplete || before.bootConfirmedAt!=null) return
        scope.launch { try { withContext(Dispatchers.IO) {
                persist(before.copy(bootConfirmedAt=System.currentTimeMillis()))
                if(MtkFirmwareRuntime.state.value?.operationId==before.operationId) MtkFirmwareRuntime.mutable.value=state.value.installation
            };onResult(state.value.installation)
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { mutable.update { it.copy(error="Не удалось сохранить подтверждение загрузки.") } } }
    }
    private fun persist(value: MtkInstallState) {
        val updated = value.copy(updatedAt = System.currentTimeMillis())
        store.write(updated)
        mutable.update { it.copy(installation = updated) }
        android.util.Log.i("MtkFirmware", "${updated.operationId} ${updated.phase}: ${updated.detail}")
    }
    override fun close() { setVisible(false) }
}
