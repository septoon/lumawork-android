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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

const val USB_UNCONFIRMED_UPDATE = "Не отсоединяйте кабель: автономное продолжение обновления не подтверждено"
enum class UsbProbeStage(val title: String) {
    Opening("Открытие USB-интерфейса…"), Handshake("Установка сеанса PAX XCB…"), Reading("Чтение ответа устройства…"),
}
internal data class UsbDiagnosticResult(val message: String, val passport: PaxPassport? = null)
data class UsbToolsState(
    val hostSupported: Boolean = true, val devices: List<UsbEquipment> = emptyList(),
    val permissionPending: String? = null, val probingKey: String? = null,
    val diagnostics: Map<String, String> = emptyMap(), val error: String? = null,
    val passports: Map<String, PaxPassport> = emptyMap(), val failures: Map<String, String> = emptyMap(),
    val probeStage: UsbProbeStage? = null,
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
    private val adapters = listOf(PaxUsbDiagnosticsAdapter(manager), MtpDiagnosticsAdapter(manager), CdcAcmDiagnosticsAdapter())
    @Volatile private var revision = 0L
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                // Invalidate even if the path is reused before the next enumeration.
                cancelProbe()
                mutable.update { it.copy(passports = emptyMap(), diagnostics = emptyMap(), failures = emptyMap(),
                    devices = it.devices.map { device -> device.copy(confirmedModel = null) }) }
            }
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
            if (mutable.value.probingKey?.let { after[it]?.hasPermission != true } == true) cancelProbe()
            (before.keys - after.keys).forEach { key ->
                if (mutable.value.probingKey == key) cancelProbe()
                onEvent(ToolsEventKind.UsbDetached, "USB-устройство отсоединено.", key)
            }
            (after.keys - before.keys).forEach { key ->
                val device = after.getValue(key)
                onEvent(ToolsEventKind.UsbAttached, "VID/PID %04X:%04X; модель не подтверждена".format(device.vendorId, device.productId), key)
            }
            mutable.update { it.copy(devices = current, diagnostics = it.diagnostics.filterKeys { key -> after[key]?.hasPermission == true },
                passports = it.passports.filterKeys { key -> after[key]?.hasPermission == true }, failures = it.failures.filterKeys(after::containsKey),
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
        if (probeJob?.isActive == true || PaxFirmwareRuntime.state.value.active) return
        val device = manager.deviceList[key] ?: return
        if (!manager.hasPermission(device)) { requestPermission(key); return }
        val equipment = describe(device)
        val adapter = adapters.firstOrNull { it.supports(equipment) }
        if (adapter == null) {
            val message = "Сервисный протокол этого USB-устройства пока не поддерживается. Дескрипторы доступны в технических деталях."
            mutable.update { it.copy(diagnostics = it.diagnostics + (key to message)) }
            onEvent(ToolsEventKind.Diagnostic, message, key)
            return
        }
        val generation = ++revision
        mutable.update { it.copy(probingKey = key, probeStage = UsbProbeStage.Opening, error = null,
            passports = it.passports - key, failures = it.failures - key, diagnostics = it.diagnostics - key,
            devices = it.devices.map { device -> if (device.key == key) device.copy(confirmedModel = null) else device }) }
        probeJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { adapter.inspect(device) { stage ->
                    if (generation == revision) mutable.update { it.copy(probeStage = stage) }
                } }
                if (generation == revision && manager.deviceList[key] == device && manager.hasPermission(device)) {
                    mutable.update { it.copy(diagnostics = it.diagnostics + (key to result.message),
                        passports = result.passport?.let { passport -> it.passports + (key to passport) } ?: (it.passports - key),
                        devices = it.devices.map { current -> if (current.key == key) current.copy(confirmedModel = result.passport?.model) else current }) }
                    onEvent(ToolsEventKind.Diagnostic, result.message, key)
                }
            } catch (_: TimeoutCancellationException) {
                if (generation == revision) failProbe(key, "Время ожидания ответа истекло. Проверьте XCB service → USB и оставайтесь в main menu терминала.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == revision) {
                    val message = if (failure is PaxDiagnosticException) requireNotNull(failure.message)
                        else "Ответ устройства не подтверждён. Проверьте USB-разрешение, подключение и режим терминала."
                    failProbe(key, message)
                }
            } finally { if (generation == revision) mutable.update { it.copy(probingKey = null, probeStage = null) } }
        }
    }
    private fun failProbe(key: String, message: String) {
        mutable.update { it.copy(failures = it.failures + (key to message), passports = it.passports - key) }
        onEvent(ToolsEventKind.Diagnostic, message, key)
    }
    private fun describe(device: UsbDevice) = UsbEquipment(device.deviceName, device.vendorId, device.productId,
        runCatching { device.manufacturerName }.getOrNull(), runCatching { device.productName }.getOrNull(), manager.hasPermission(device),
        (0 until device.interfaceCount).map { index ->
            val iface = device.getInterface(index)
            UsbInterfaceDetails(iface.id, iface.alternateSetting, iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol,
                (0 until iface.endpointCount).map { e -> iface.getEndpoint(e).let { ep ->
                    "0x%02X · %s · тип %d · пакет %d Б".format(ep.address, if (ep.direction == 128) "IN" else "OUT", ep.type, ep.maxPacketSize)
                } })
        }, confirmedModel = mutable.value.passports[device.deviceName]?.model?.takeIf { manager.hasPermission(device) })
    fun cancelProbe() {
        val key = mutable.value.probingKey
        revision++; probeJob?.cancel(); probeJob = null
        adapters.forEach { it.cancel() }
        mutable.update { it.copy(probingKey = null, probeStage = null) }
        if (key != null) onEvent(ToolsEventKind.Diagnostic, "Диагностика USB остановлена. Паспорт не подтверждён.", key)
    }
    override fun close() {
        cancelProbe()
        if (registered) { context.unregisterReceiver(receiver); registered = false }
        mutable.update { it.copy(permissionPending = null, passports = emptyMap(), diagnostics = emptyMap(), failures = emptyMap(),
            devices = it.devices.map { device -> device.copy(confirmedModel = null) }) }
    }
}
