package dev.noir.player

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class UsbPreviewState(val busy: Boolean = false, val message: String = "Прямая передача ещё не проверялась", val report: String? = null)

/** Foreground-only, bounded 15-second experiment. The normal player remains stopped afterwards. */
class UsbDirectPreview(private val context: Context) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(UsbPreviewState())
    val state = mutable.asStateFlow()
    private val action = "${context.packageName}.USB_PREVIEW_PERMISSION"
    private var pending: Triple<String, Track, Int>? = null
    private var running: Job? = null
    private var stopped = AtomicBoolean(false)
    private var registered = false
    private var foreground = true
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) stop()
            if (intent.action != action) return
            val request = pending ?: return
            pending = null
            val device = manager.deviceList[request.first]
            if (foreground && device != null && manager.hasPermission(device)) launch(device, request.second, request.third)
            else mutable.value = UsbPreviewState(message = "USB-доступ не получен или проба отменена", report = mutable.value.report)
        }
    }

    fun start() {
        if (!registered) {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
    }
    fun setForeground(value: Boolean) { foreground = value; if (!value) stop() }
    fun stop() {
        stopped.set(true)
        pending = null
        if (running?.isActive != true) mutable.value = mutable.value.copy(busy = false, message = "Проба остановлена")
    }
    fun close() {
        stop()
        if (registered) { context.unregisterReceiver(receiver); registered = false }
        scope.cancel()
    }
    fun request(track: Track, db: Int) {
        if (mutable.value.busy || UsbPlaybackInterlock.active.value || DirectUsbSettings.owned.value || !foreground) return
        if (track.bits != 16 || track.sampleRate != 44_100 || track.channels != 2) {
            mutable.value = UsbPreviewState(message = "Выбери FLAC 16 бит / 44,1 кГц / стерео"); return
        }
        val devices = manager.deviceList.values.filter { it.vendorId == 0x0e55 && it.productId == 0x1110 }
        if (devices.size != 1) { mutable.value = UsbPreviewState(message = "Для пробы нужен один ЦАП 0E55:1110"); return }
        require(db in -45..-15 && db % 5 == 0)
        mutable.value = UsbPreviewState(true, "Подготовка USB-пробы…", mutable.value.report)
        val device = devices.single()
        if (manager.hasPermission(device)) launch(device, track, db) else {
            pending = Triple(device.deviceName, track, db)
            runCatching {
                manager.requestPermission(device, PendingIntent.getBroadcast(context, 205, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }.onFailure { pending = null; mutable.value = UsbPreviewState(message = "Не удалось запросить USB-доступ") }
        }
    }

    private fun launch(device: UsbDevice, track: Track, db: Int) {
        stopped = AtomicBoolean(false)
        val stop = stopped
        running = scope.launch {
            val token = Any()
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener({ change -> if (change < 0) stop.set(true) }, Handler(Looper.getMainLooper())).build()
            try {
                check(UsbPlaybackInterlock.acquire(token)) { "USB уже занят" }
                check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Android не предоставил аудиофокус" }
                mutable.value = mutable.value.copy(busy = true, message = "Готовлю первые 15 секунд выбранного FLAC…")
                val pcm = withContext(Dispatchers.IO) { FlacPcmPreview.decode(context, track, stop) }
                if (stop.get()) return@launch
                mutable.value = mutable.value.copy(message = "Прямая USB-проба · до 15 секунд · $db дБ")
                val report = withContext(Dispatchers.IO) { UsbAudioResources.run(context) { resources -> play(device, track, db, pcm, stop, resources) } }
                val outcome = when {
                    "Native USB: complete" in report -> "USB-передача завершена"
                    "Native USB: stopped" in report -> "USB-передача остановлена"
                    else -> "USB-проба не завершилась успешно"
                }
                mutable.value = UsbPreviewState(message = "$outcome. Скопируй диагностику. Если обычный звук не вернулся — переподключи ЦАП.", report = report)
            } catch (cancel: CancellationException) {
                if (currentCoroutineContext().isActive) mutable.value = UsbPreviewState(message = "Проба остановлена", report = mutable.value.report)
                else throw cancel
            } catch (failure: Exception) {
                mutable.value = UsbPreviewState(message = "Проба не запущена: ${failure.message}", report = "NOIR ${BuildConfig.VERSION_NAME} — прямая USB-проба\nВремя UTC: ${java.time.Instant.now()}\nПодготовка не завершена: ${failure.message}")
            }
            finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    runCatching { audio.abandonAudioFocusRequest(focus) }
                    UsbPlaybackInterlock.release(token)
                    mutable.value = mutable.value.copy(busy = false)
                }
            }
        }
    }

    private fun play(device: UsbDevice, track: Track, db: Int, pcm: PcmPreview, stop: AtomicBoolean, resources: String): String {
        val began = android.os.SystemClock.elapsedRealtime()
        val lines = mutableListOf("NOIR ${BuildConfig.VERSION_NAME} — прямая USB-проба", "Время UTC: ${java.time.Instant.now()}", "VID:PID 0E55:1110", "Трек: ${track.title}", "PCM: ${pcm.bytes.size} байт; decoder=${pcm.decoder}", "PCM SHA-256: ${pcm.sha256}", resources)
        var handle = 0L
        val gate = Any()
        check(UsbHardwareGate.acquire(gate)) { "ЦАП занят основным плеером" }
        val connection = try { manager.openDevice(device) ?: error("Не удалось открыть ЦАП") } catch (failure: Exception) { UsbHardwareGate.release(gate); throw failure }
        try {
            // Load the library and obtain a usable native fd before touching hardware controls.
            handle = runCatching { NativeUsb.create(connection.fileDescriptor) }.getOrElse { error("Не загружен модуль прямого USB: ${it.javaClass.simpleName}") }
            check(NativeUsb.speed(handle) in setOf(2, 3)) { "Не удалось определить поддерживаемую скорость USB" }
            val description = UsbAudioDescriptors.parse(connection.rawDescriptors)
            require(description.warnings.isEmpty() && description.clocks.singleOrNull()?.let { it.id == 6 && it.controlInterface == 0 && it.controls and 3 == 3 } == true) { "Clock отличается от проверенного профиля ЦАПа" }
            require(description.volumes.filter { it.unit == 7 && it.controlInterface == 0 && it.version == 0x200 && it.writable }.map { it.channel }.toSet() == setOf(1, 2)) { "Регуляторы отличаются от проверенного профиля ЦАПа" }
            val format = description.formats.single { it.iface == 1 && it.alternate == 1 && it.version == 0x200 && it.channels == 2 && it.bits == 16 && it.bytesPerSample == 2 }
            val interfaces = (0 until device.interfaceCount).map(device::getInterface)
            val ac = interfaces.single { it.id == 0 && it.alternateSetting == 0 && it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1 }
            val inactive = interfaces.single { it.id == format.iface && it.alternateSetting == 0 && it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 2 }
            val active = interfaces.single { it.id == format.iface && it.alternateSetting == format.alternate }
            require(active.endpointCount == 1)
            val endpoint = active.getEndpoint(0)
            require(endpoint.address == 4 && endpoint.attributes == 9 && endpoint.interval in 1..4 && endpoint.maxPacketSize in 24..1024) { "Endpoint отличается от проверенного адаптивного USB-профиля" }
            lines += "AS 1 alt 1; EP04 adaptive OUT, interval=${endpoint.interval}, maxPacket=${endpoint.maxPacketSize}"
            val statusReader = UsbStatusRead { type, request, value, index, length ->
                val buffer = ByteArray(length)
                val count = connection.controlTransfer(type, request, value, index, buffer, length, 200)
                if (count == length) buffer else null
            }
            val controls = UsbPreviewControls(
                read = { selector, entity, channel, request, length ->
                    val buffer = ByteArray(length)
                    val received = connection.controlTransfer(0xa1, request, (selector shl 8) or channel, entity shl 8, buffer, length, 300)
                    check(received == length) { "GET entity=$entity ch=$channel request=$request: $received / $length" }; buffer
                },
                write = { selector, entity, channel, bytes ->
                    val sent = connection.controlTransfer(0x21, 1, (selector shl 8) or channel, entity shl 8, bytes, bytes.size, 300)
                    check(sent == bytes.size) { "SET entity=$entity ch=$channel: $sent" }
                }, note = lines::add,
            )
            UsbInterfaceLease(
                acquire = { id -> connection.claimInterface(if (id == 0) ac else inactive, true) },
                release = { id -> connection.releaseInterface(if (id == 0) ac else inactive) }, note = lines::add,
            ).use { lease ->
                lease.claim(listOf(0, 1))
                try {
                    check(!stop.get()) { "Проба остановлена" }
                    check(connection.setInterface(inactive)) { "ЦАП не принял начальный alt 0" }
                    controls.prepare(db)
                    check(!stop.get()) { "Проба остановлена" }
                    check(connection.setInterface(active)) { "ЦАП не принял AS 1 alt 1" }
                    val before = UsbStreamSnapshot.capture(statusReader)
                    lines += before.report("До передачи")
                    check(before.alternate == null || before.alternate == 1) { "ЦАП уже вышел из alt 1 до запуска передачи" }
                    lines += "Подготовка USB: ${android.os.SystemClock.elapsedRealtime() - began} мс"
                    lines += NativeUsb.stream(handle, endpoint.address, endpoint.interval, endpoint.maxPacketSize, pcm.bytes, stop)
                    lines += UsbStreamSnapshot.capture(statusReader).report("После Native, до SET alt 0")
                    val actualClock = ByteArray(4)
                    val count = connection.controlTransfer(0xa1, 1, 0x100, 6 shl 8, actualClock, 4, 300)
                    lines += "Clock 6 сразу после передачи: ${if (count == 4) "${UsbControlValues.unsigned(actualClock)} Гц" else "GET не получен ($count)"}"
                } finally {
                    val inactiveConfirmed = runCatching { connection.setInterface(inactive) }.getOrDefault(false)
                    lines += "AS 1 alt 0 после пробы: $inactiveConfirmed"
                    controls.restore(inactiveConfirmed)
                }
            }
        } catch (failure: Exception) { lines += "Проба прервана: ${failure.message ?: failure.javaClass.simpleName}" }
        finally {
            try { connection.close() } finally { try { if (handle != 0L) NativeUsb.destroy(handle) } finally { UsbHardwareGate.release(gate) } }
        }
        lines += "Передача без микшера Android; результат USB не измеряет аналоговый выход и не подтверждает весь тракт как bit-perfect."
        lines += "Общее время пробы с восстановлением: ${android.os.SystemClock.elapsedRealtime() - began} мс"
        lines += "Частота и громкость восстанавливаются по GET, результат выше. Воспроизведение автоматически не возобновляется."
        return lines.joinToString("\n")
    }
}
