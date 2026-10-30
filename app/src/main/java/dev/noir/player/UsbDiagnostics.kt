package dev.noir.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UsbProbeState(val busy: Boolean = false, val message: String = "Проверка USB ещё не выполнена", val report: String? = null)

class UsbDiagnostics(private val context: Context) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(UsbProbeState())
    val state = mutable.asStateFlow()
    private val permissionAction = "${context.packageName}.USB_READ_PERMISSION"
    private var pendingDevice: String? = null
    private var pendingExclusive = false
    private var running: Job? = null
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == permissionAction) {
                val id = pendingDevice ?: return
                val device = manager.deviceList[id]
                pendingDevice = null
                // Never trust broadcast extras as authorization: re-check UsbManager.
                if (device != null && manager.hasPermission(device)) read(device, pendingExclusive)
                else mutable.value = UsbProbeState(message = "Доступ к USB не получен. Можно повторить проверку.")
            } else if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                running?.cancel(); pendingDevice = null
                val previous = mutable.value.report
                mutable.value = UsbProbeState(message = if (previous != null) "USB-устройство отключено. Последний отчёт сохранён; подключи ЦАП для новой проверки." else "USB-устройство отключено. Подключи ЦАП и повтори проверку.", report = previous)
            }
        }
    }

    fun start() {
        if (!registered) {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
    }

    fun close() {
        if (registered) { context.unregisterReceiver(receiver); registered = false }
        scope.cancel()
    }

    fun request(exclusive: Boolean = false) {
        if (mutable.value.busy) return
        if (DirectUsbSettings.owned.value) { mutable.value = UsbProbeState(message = "Сначала выключи прямой USB основного плеера"); return }
        if (UsbPlaybackInterlock.active.value) {
            mutable.value = UsbProbeState(message = "Эксклюзивная проверка уже выполняется. Дождись её завершения.")
            return
        }
        val devices = manager.deviceList.values.filter { device ->
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
        }
        if (devices.size != 1) {
            mutable.value = UsbProbeState(message = if (devices.isEmpty()) "UsbManager не видит аудиоустройство. Переподключи ЦАП и повтори." else "Подключено несколько USB-аудиоустройств. Оставь только нужный ЦАП для этой проверки.")
            return
        }
        val device = devices.single()
        mutable.value = UsbProbeState(busy = true, message = "Ожидаю разрешение на чтение USB…")
        if (manager.hasPermission(device)) read(device, exclusive) else {
            pendingDevice = device.deviceName
            pendingExclusive = exclusive
            runCatching {
                val intent = Intent(permissionAction).setPackage(context.packageName)
                val result = PendingIntent.getBroadcast(context, 204, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                manager.requestPermission(device, result)
            }.onFailure { pendingDevice = null; mutable.value = UsbProbeState(message = "Android не разрешил запрос USB-доступа. Воспроизведение через Android остаётся доступным.") }
        }
    }

    private fun read(device: UsbDevice, exclusive: Boolean) {
        running?.cancel()
        running = scope.launch {
            val token = Any()
            try {
                if (exclusive && !UsbPlaybackInterlock.acquire(token)) {
                    mutable.value = UsbProbeState(message = "Другая проверка уже использует USB. Повтори после её завершения.")
                    return@launch
                }
                mutable.value = UsbProbeState(busy = true, message = if (exclusive) "Музыка остановлена. Проверяю USB с эксклюзивным доступом…" else "Читаю описание и доступные регуляторы ЦАПа…")
                val result = withContext(Dispatchers.IO) { inspect(device, exclusive) }
                if (manager.deviceList.containsKey(device.deviceName)) mutable.value = UsbProbeState(message = if (exclusive) "Проверка завершена. Результат освобождения ЦАПа есть в отчёте. Если звука нет — переподключи его. Скопируй диагностику." else "USB-отчёт готов. Скопируй диагностику и пришли в чат.", report = result)
                else mutable.value = UsbProbeState(message = "ЦАП отключён. Подключи его и повтори проверку.", report = result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.value = UsbProbeState(message = "Проверка не завершена. Переподключи ЦАП перед воспроизведением.", report = failure.message) }
            finally { if (exclusive) withContext(NonCancellable + Dispatchers.Main.immediate) { UsbPlaybackInterlock.release(token) } }
        }
    }

    private suspend fun inspect(device: UsbDevice, exclusive: Boolean): String {
        val notes = mutableListOf<String>()
        var body = ""
        val gate = Any()
        try {
            check(UsbHardwareGate.acquire(gate)) { "ЦАП занят основным плеером" }
            val connection = manager.openDevice(device) ?: error("Не удалось открыть USB-устройство")
            try {
                val raw = connection.rawDescriptors ?: error("Android не вернул USB-описание")
                val description = UsbAudioDescriptors.parse(raw)
                val taskContext = currentCoroutineContext()
                var calls = 0
                var succeeded = 0
                var failed = 0
                var exhausted = false
                val failedRequests = mutableListOf<String>()
                val transport = UsbReadTransport { request, selector, entity, iface, channel, length ->
                    taskContext.ensureActive()
                    // Control payloads are always IN. Exclusive ownership is handled separately.
                    require(request in setOf(1, 2, 0x81, 0x82, 0x83, 0x84))
                    require(selector in 1..2 && entity in 0..255 && iface in 0..255 && channel in 0..255 && length in 1..770)
                    if (calls++ >= 40) { exhausted = true; null } else {
                        val buffer = ByteArray(length)
                        val count = connection.controlTransfer(0xA1, request, (selector shl 8) or channel, (entity shl 8) or iface, buffer, length, 250)
                        if (count > 0 && count <= buffer.size) { succeeded++; buffer.copyOf(count) } else {
                            failed++
                            failedRequests += "GET %02X sel=$selector entity=$entity AC=$iface ch=$channel len=$length: $count".format(request)
                            null
                        }
                    }
                }
                fun query() = buildString {
                    appendLine(UsbReadProbe.report(description, transport, exclusive))
                    appendLine("USB GET: ответов $succeeded, без ответа $failed")
                    if (failed > 0) appendLine("Недоступный GET не означает отсутствие функции: системный USB-драйвер может не разрешать её чтение приложению.")
                    failedRequests.forEach { appendLine(it) }
                    if (exhausted) appendLine("Достигнут лимит 40 запросов; часть полей не прочитана.")
                }
                if (exclusive) {
                    val ids = (description.clocks.map { it.controlInterface } + description.volumes.map { it.controlInterface }).distinct()
                    val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
                        .filter { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1 && it.alternateSetting == 0 }
                        .associateBy { it.id }
                    require(ids.isNotEmpty() && ids.all { it in interfaces }) { "Нет распознанного AudioControl-интерфейса для проверки" }
                    UsbInterfaceLease(
                        acquire = { id -> connection.claimInterface(interfaces.getValue(id), true) },
                        release = { id -> connection.releaseInterface(interfaces.getValue(id)) },
                        note = notes::add,
                    ).use { lease -> lease.claim(ids); body = query() }
                } else body = query()
            } finally { connection.close() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { body = "USB-проверка не завершена: ${e.message ?: e.javaClass.simpleName}. Команды изменения частоты и громкости не отправлялись." }
        finally { UsbHardwareGate.release(gate) }
        return buildString {
            appendLine("Прямое чтение USB, NOIR ${BuildConfig.VERSION_NAME}")
            appendLine("Время отчёта (UTC): ${java.time.Instant.now()}")
            appendLine("Режим: ${if (exclusive) "эксклюзивная проверка AudioControl" else "обычная проверка"}")
            appendLine("VID:PID %04X:%04X".format(device.vendorId, device.productId))
            appendLine("Модель: ${device.productName ?: "не сообщается"}; версия устройства: ${device.version}")
            appendLine("Серийный номер не считывается.")
            for (index in 0 until device.interfaceCount) {
                val iface = device.getInterface(index)
                if (iface.interfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
                appendLine("Interface ${iface.id}, alt ${iface.alternateSetting}, subclass ${iface.interfaceSubclass}:")
                for (epIndex in 0 until iface.endpointCount) {
                    val endpoint = iface.getEndpoint(epIndex)
                    appendLine("  EP %02X attr=%02X maxPacket=${endpoint.maxPacketSize} interval=${endpoint.interval}".format(endpoint.address, endpoint.attributes))
                }
            }
            appendLine(body)
            notes.forEach { appendLine(it) }
            if (exclusive) appendLine("release=true подтверждает освобождение интерфейса, но не восстановление маршрута Android. Если звука нет — переподключи ЦАП. Воспроизведение автоматически не возобновляется.")
        }
    }
}
