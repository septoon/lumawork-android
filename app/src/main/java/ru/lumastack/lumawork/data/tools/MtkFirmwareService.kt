package ru.lumastack.lumawork.data.tools

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.*
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.lumastack.lumawork.MainActivity
import ru.lumastack.lumawork.R

internal data class MtkFirmwareRequest(val initial: MtkInstallState,val pkg: MtkFirmwarePackage,val device: String,val deviceId: Int)
internal object MtkFirmwareRuntime {
    val mutable=MutableStateFlow<MtkInstallState?>(null)
    val state=mutable.asStateFlow()
    @Volatile var request: MtkFirmwareRequest?=null
}

/** Started from the visible waiting screen after USB permission. Never restarts or resumes commands. */
class MtkFirmwareService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val link=AtomicReference<MtkWire?>()
    private val epoch=AtomicLong()
    private var job: Job?=null
    private var wake: PowerManager.WakeLock?=null
    private var snapshot=MtkInstallState()
    private lateinit var store: MtkFirmwareStore
    private var lastPersist=0L
    private var registered=false
    private val manager get()=getSystemService(USB_SERVICE) as UsbManager
    private val notifications get()=getSystemService(NotificationManager::class.java)
    private val receiver=object : BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            epoch.incrementAndGet()
            if(intent.action==UsbManager.ACTION_USB_DEVICE_DETACHED) runCatching { link.getAndSet(null)?.close() }
        }
    }
    override fun onCreate() {
        super.onCreate();synchronized(Companion) { instance=this }
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,"Прошивка UniTodi P8",NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(this,receiver,IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        },ContextCompat.RECEIVER_NOT_EXPORTED)
        registered=true
    }
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(job?.isActive==true) return START_NOT_STICKY
        val request=MtkFirmwareRuntime.request
        if(request==null || intent?.getStringExtra("operation")!=request.initial.operationId ||
            terminalOperationGate.owner.value!=request.initial.operationId || MtkFirmwareRuntime.state.value?.active!=true) {
            stopSelf();return START_NOT_STICKY
        }
        snapshot=request.initial;store=MtkFirmwareStore(this,snapshot.owner)
        try {
            if(Build.VERSION.SDK_INT>=29) startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION,notification())
        } catch(failure: Exception) { terminal(failure);stopSelf();return START_NOT_STICKY }
        job=scope.launch {
            try {
                wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"LumaWork:UniTodiP8").apply { acquire(30*60_000L) }
                withTimeout(25*60_000L) {
                    val openedEpoch=epoch.get()
                    val runContext=currentCoroutineContext()
                    val checkpoint={ runContext.ensureActive();check(epoch.get()==openedEpoch) { "USB-состав изменился. Сеанс P8 остановлен." }
                        check(stopRequested!=snapshot.operationId) { "Операция прервана инженером." } }
                    checkpoint()
                    val candidates=manager.deviceList.values.filter { isMtkPreloader(it.vendorId,it.productId) }
                    require(candidates.size==1) { "Нужен ровно один UniTodi P8 в Preloader." }
                    val device=candidates.single()
                    require(device.deviceName==request.device && device.deviceId==request.deviceId && manager.hasPermission(device)) { "USB-разрешение или устройство изменилось до запуска службы." }
                    val expected=MtkUsbCandidate(device.deviceName,device.deviceId,openedEpoch,true)
                    val selection=MtkUsbSelection()
                    val current={ manager.deviceList[request.device]?.let { MtkUsbCandidate(it.deviceName,it.deviceId,epoch.get(),manager.hasPermission(it)) } }
                    val wire=MtkUsbConnection.open(manager,device,{selection.canOpen(expected,current())},checkpoint)
                    link.set(wire)
                    wire.use {
                        checkpoint()
                        publish(snapshot.copy(phase=MtkInstallPhase.Handshake,detail="Чтение идентификаторов Preloader…"))
                        val preloader=MtkPreloaderSession(it)
                        val info=preloader.inspect()
                        publish(snapshot.copy(phase=MtkInstallPhase.LoadingAgent,info=info,agentStarted=true,detail="Проверка и запуск исходного агента в RAM…"))
                        val agent=preloader.loadUpgradeAgent(request.pkg)
                        val target=agent.inspectLayout(agent.inspectTarget(info))
                        require(target.compatible) { "BOM или GPT не соответствуют проверенному P8KC. FORMAT не отправлен." }
                        publish(snapshot.copy(target=target,detail="BOM и разметка P8KC подтверждены. Подготовка Firmware Upgrade…"))
                        MtkFirmwareWorkflow(agent.upgradeAgent(target,checkpoint),checkpoint=checkpoint,event={ event ->
                            val next=snapshot.afterWriteEvent(event)
                            val force=next.phase!=snapshot.phase || next.pendingAction!=snapshot.pendingAction || next.lastAcknowledgedAction!=snapshot.lastAcknowledgedAction ||
                                event.bytes==0L || event.bytes==event.totalBytes
                            publish(next,force)
                        }).execute(request.pkg,target)
                    }
                }
            } catch(failure: Exception) { terminal(failure) }
            finally {
                runCatching { link.getAndSet(null)?.close() }
                wake?.let { if(it.isHeld) it.release() };wake=null
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun publish(value: MtkInstallState,force: Boolean=true) {
        snapshot=value.copy(updatedAt=System.currentTimeMillis())
        val now=SystemClock.elapsedRealtime()
        if(force || now-lastPersist>=500) {
            try { store.write(snapshot) }
            catch(failure: Exception) {
                if(snapshot.phase==MtkInstallPhase.WriteComplete) snapshot=snapshot.copy(detail="$MTK_WRITE_SUCCESS. Не удалось сохранить итоговый журнал.")
                throw failure
            }
            lastPersist=now;notifications.notify(NOTIFICATION,notification())
        }
        MtkFirmwareRuntime.mutable.value=snapshot
    }
    private fun terminal(failure: Exception) {
        android.util.Log.w("MtkFirmware","${snapshot.operationId}: firmware service stopped",failure)
        if(snapshot.phase==MtkInstallPhase.WriteComplete) {
            MtkFirmwareRuntime.mutable.value=snapshot
            return
        }
        val phase=if(snapshot.destructiveStarted) MtkInstallPhase.RecoveryRequired else if(failure is CancellationException) MtkInstallPhase.Interrupted else MtkInstallPhase.Failed
        val detail=(failure.message ?: "Сеанс P8 остановлен.")+if(snapshot.destructiveStarted)
            " Требуется проверка и восстановление штатным Agenew. Команды не повторяются автоматически."
            else " Команды изменения eMMC не отправлялись. Отсоедините USB и перезапустите терминал."
        snapshot=snapshot.copy(phase=phase,detail=detail,usbPermission=false,updatedAt=System.currentTimeMillis())
        runCatching { store.write(snapshot) };MtkFirmwareRuntime.mutable.value=snapshot
    }
    private fun notification(): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_nav_folder_open).setContentTitle("UniTodi P8 · Firmware Upgrade")
            .setContentText(snapshot.detail).setContentIntent(open).setOngoing(snapshot.active).setOnlyAlertOnce(true)
            .setProgress(100,if(snapshot.totalBytes>0) (snapshot.bytes*100/snapshot.totalBytes).toInt() else 0,snapshot.totalBytes==0L).build()
    }
    private fun interrupt() { job?.cancel();runCatching { link.getAndSet(null)?.close() } }
    override fun onDestroy() {
        interrupt()
        if(registered) { unregisterReceiver(receiver);registered=false }
        val operation=snapshot.operationId
        val cleanup={
            if(snapshot.active && this::store.isInitialized) terminal(CancellationException("Служба P8 остановлена."))
            terminalOperationGate.release(operation);synchronized(Companion) { if(instance===this) instance=null };scope.cancel()
        }
        job?.invokeOnCompletion { cleanup() } ?: cleanup()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL="mtk-firmware"
        private const val NOTIFICATION=0x4d544b
        @Volatile private var instance: MtkFirmwareService?=null
        @Volatile private var stopRequested: String?=null
        internal fun start(context: Context,state: MtkInstallState,pkg: MtkFirmwarePackage,device: String,deviceId: Int)=synchronized(this) {
            check(instance==null && MtkFirmwareRuntime.state.value?.active!=true) { "Служба P8 ещё занята." }
            check(terminalOperationGate.owner.value==state.operationId && state.usbPermission && !state.destructiveStarted)
            MtkFirmwareRuntime.request=MtkFirmwareRequest(state,pkg,device,deviceId)
            MtkFirmwareRuntime.mutable.value=state;stopRequested=null
            try { ContextCompat.startForegroundService(context,Intent(context,MtkFirmwareService::class.java).putExtra("operation",state.operationId));Unit }
            catch(failure: Exception) { MtkFirmwareRuntime.mutable.value=null;MtkFirmwareRuntime.request=null;throw failure }
        }
        fun interrupt(operation: String) { stopRequested=operation;instance?.takeIf { it.snapshot.operationId==operation }?.interrupt() }
    }
}
