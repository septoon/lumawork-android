package ru.lumastack.lumawork.data.tools

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

const val USB_UNCONFIRMED_UPDATE = "Не отсоединяйте кабель: автономное продолжение обновления не подтверждено"
data class UsbToolsState(
    val hostSupported: Boolean = true, val devices: List<UsbEquipment> = emptyList(),
    val permissionPending: String? = null, val probingKey: String? = null,
    val diagnostics: Map<String, String> = emptyMap(), val error: String? = null,
)

/** Enumerates real hardware. No model VID/PID allowlist is invented from the Wiki's device names. */
class UsbToolsMonitor(context: Context, private val scope: CoroutineScope,
    private val onEvent: (ToolsEventKind, String, String?) -> Unit,
) : AutoCloseable {
    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${context.packageName}.tools.USB_PERMISSION"
    private val mutable = MutableStateFlow(UsbToolsState(hostSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)))
    val state = mutable.asStateFlow()
    private var registered = false
    private var probeJob: Job? = null
    private val adapters = listOf(MtpDiagnosticsAdapter(manager), CdcAcmDiagnosticsAdapter())
    private var revision = 0L
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == permissionAction) {
                // Re-read Android's permission, rather than trusting broadcast extras (also works with immutable PendingIntent).
                val key = mutable.value.permissionPending
                val granted = key?.let { manager.deviceList[it]?.let(manager::hasPermission) } == true
                mutable.update { it.copy(permissionPending = null, error = if (granted) null else "Доступ USB не разрешён или устройство отключено. Разрешение можно запросить повторно.") }
            }
            scan()
        }
    }
    fun start() {
        if (registered) return
        try {
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(permissionAction)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true; scan()
        } catch (_: Exception) { mutable.update { it.copy(error = "Не удалось включить наблюдение USB.") } }
    }
    fun scan() {
        try {
            val current = manager.deviceList.values.map(::describe).sortedBy { it.key }
            val before = mutable.value.devices.associateBy { it.key }
            val after = current.associateBy { it.key }
            (before.keys - after.keys).forEach { key ->
                if (mutable.value.probingKey == key) cancelProbe()
                onEvent(ToolsEventKind.UsbDetached, "Кабель отсоединён. Установка ОС не подтверждена.", key)
            }
            (after.keys - before.keys).forEach { key ->
                val device = after.getValue(key)
                onEvent(ToolsEventKind.UsbAttached, "VID/PID %04X:%04X; модель не подтверждена".format(device.vendorId, device.productId), key)
            }
            mutable.update { it.copy(devices = current, diagnostics = it.diagnostics.filterKeys(after::containsKey),
                permissionPending = it.permissionPending?.takeIf(after::containsKey)) }
        } catch (_: Exception) { mutable.update { it.copy(error = "Не удалось прочитать USB-устройства. Проверьте кабель и USB Host на телефоне.") } }
    }
    fun requestPermission(key: String) {
        if (mutable.value.permissionPending != null) return
        try {
            val device = manager.deviceList[key] ?: error("detached")
            if (manager.hasPermission(device)) { scan(); return }
            mutable.update { it.copy(permissionPending = key, error = null) }
            val pending = PendingIntent.getBroadcast(context, device.deviceId,
                Intent(permissionAction).setPackage(context.packageName).putExtra("device-key", key),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.requestPermission(device, pending)
        } catch (_: Exception) { mutable.update { it.copy(permissionPending = null, error = "Не удалось запросить USB-доступ. Переподключите терминал.") } }
    }
    fun probe(key: String) {
        if (probeJob?.isActive == true) return
        val device = manager.deviceList[key] ?: return
        if (!manager.hasPermission(device)) { requestPermission(key); return }
        val equipment = describe(device)
        val adapter = adapters.firstOrNull { it.supports(equipment) }
        if (adapter == null) {
            val message = "MTP/PTP-интерфейс не объявлен. CDC ACM и прочие интерфейсы не подтверждают поддержку логов или прошивки. Сервисные команды не отправлялись."
            mutable.update { it.copy(diagnostics = it.diagnostics + (key to message)) }
            onEvent(ToolsEventKind.Diagnostic, message, key)
            return
        }
        val generation = ++revision
        mutable.update { it.copy(probingKey = key, error = null) }
        probeJob = scope.launch {
            try {
                val message = withContext(Dispatchers.IO) { adapter.inspect(device) }
                if (generation == revision && manager.deviceList[key] == device) {
                    mutable.update { it.copy(diagnostics = it.diagnostics + (key to message)) }
                    onEvent(ToolsEventKind.Diagnostic, message, key)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == revision) {
                    val message = "Протокол не подтверждён: устройство не ответило, заблокировано или USB-доступ потерян. Результат диагностики не подтверждён."
                    mutable.update { it.copy(diagnostics = it.diagnostics + (key to message)) }
                    onEvent(ToolsEventKind.Diagnostic, message, key)
                }
            } finally { if (generation == revision) mutable.update { it.copy(probingKey = null) } }
        }
    }
    private fun describe(device: UsbDevice) = UsbEquipment(device.deviceName, device.vendorId, device.productId,
        runCatching { device.manufacturerName }.getOrNull(), runCatching { device.productName }.getOrNull(), manager.hasPermission(device),
        (0 until device.interfaceCount).map { index ->
            val iface = device.getInterface(index)
            UsbInterfaceDetails(iface.id, iface.alternateSetting, iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol,
                (0 until iface.endpointCount).map { e -> iface.getEndpoint(e).let { ep ->
                    "0x%02X · %s · тип %d · пакет %d Б".format(ep.address, if (ep.direction == 128) "IN" else "OUT", ep.type, ep.maxPacketSize)
                } })
        })
    fun cancelProbe() {
        revision++; probeJob?.cancel(); probeJob = null
        adapters.forEach { it.cancel() }
        mutable.update { it.copy(probingKey = null) }
    }
    override fun close() {
        cancelProbe()
        if (registered) { context.unregisterReceiver(receiver); registered = false }
        mutable.update { it.copy(permissionPending = null) }
    }
}
