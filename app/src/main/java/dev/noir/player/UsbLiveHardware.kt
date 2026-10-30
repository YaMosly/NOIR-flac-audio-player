package dev.noir.player

import android.content.Context
import android.hardware.usb.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.roundToInt

/** Exclusive connection for the physically checked profile. Construct/release on the worker. */
class UsbLiveHardware @JvmOverloads constructor(private val context: Context, private val profile: UsbPcmProfile = UsbPcmProfile()) : LiveUsbTransport {
    private val token = Any()
    private var connection: UsbDeviceConnection? = null
    private var lease: UsbInterfaceLease? = null
    private var controls: UsbPreviewControls? = null
    private lateinit var inactive: UsbInterface
    private lateinit var active: UsbInterface
    private var maxPacket = 196
    private val notes = mutableListOf<String>()
    var handle = 0L
        private set
    private var lastVolume: Int? = null

    init {
        check(!UsbPlaybackInterlock.active.value && UsbHardwareGate.acquire(token)) { "USB занят диагностикой" }
        DirectUsbSettings.owned.value = true
        try {
            val manager = context.getSystemService(UsbManager::class.java)
            val device = manager.deviceList.values.singleOrNull { it.vendorId == 0x0e55 && it.productId == 0x1110 }
                ?: error("Подключи ЦАП 0E55:1110")
            check(manager.hasPermission(device)) { "Разреши прямой USB в разделе «Звуковой тракт»" }
            val conn = manager.openDevice(device) ?: error("Не удалось открыть ЦАП")
            connection = conn
            handle = NativeUsb.create(conn.fileDescriptor)
            NativeUsb.configureLive(handle, profile.rate, profile.frameBytes)
            check(NativeUsb.speed(handle) == 3) { "Прямой режим пока проверен только для USB high-speed" }
            val description = UsbAudioDescriptors.parse(conn.rawDescriptors)
            require(description.warnings.isEmpty() && description.clocks.singleOrNull()?.let { it.id == 6 && it.controlInterface == 0 && it.controls and 3 == 3 } == true)
            require(description.volumes.filter { it.unit == 7 && it.controlInterface == 0 && it.version == 0x200 && it.writable }.map { it.channel }.toSet() == setOf(1, 2))
            require(description.formats.any { it.iface == 1 && it.alternate == profile.alternate && it.version == 0x200 && it.channels == 2 && it.bits == profile.bits && it.bytesPerSample == profile.sampleBytes })
            val interfaces = (0 until device.interfaceCount).map(device::getInterface)
            val ac = interfaces.single { it.id == 0 && it.alternateSetting == 0 && it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1 }
            inactive = interfaces.single { it.id == 1 && it.alternateSetting == 0 }
            active = interfaces.single { it.id == 1 && it.alternateSetting == profile.alternate }
            require(active.endpointCount == 1)
            val ep = active.getEndpoint(0)
            require(ep.address == 4 && ep.attributes == 9 && ep.interval == 1 && ep.maxPacketSize in 24..1024)
            maxPacket = ep.maxPacketSize
            require(((profile.rate + 7999) / 8000) * profile.frameBytes <= maxPacket) { "Пакет USB недостаточен для ${profile.label}" }
            val control = UsbPreviewControls(
                read = { selector, entity, channel, request, length ->
                    val data = ByteArray(length)
                    check(conn.controlTransfer(0xa1, request, selector shl 8 or channel, entity shl 8, data, length, 300) == length) { "USB GET не получен" }; data
                },
                write = { selector, entity, channel, bytes ->
                    check(conn.controlTransfer(0x21, 1, selector shl 8 or channel, entity shl 8, bytes, bytes.size, 300) == bytes.size) { "USB SET не подтверждён" }
                }, note = notes::add,
            )
            controls = control
            lease = UsbInterfaceLease(
                acquire = { conn.claimInterface(if (it == 0) ac else inactive, true) },
                release = { conn.releaseInterface(if (it == 0) ac else inactive) }, note = notes::add,
            )
            lease!!.claim(listOf(0, 1))
            check(conn.setInterface(inactive))
            control.prepare(-35, profile.rate)
            DirectUsbSettings.status.value = "Прямой USB · готов · ${profile.label}"
        } catch (failure: Exception) { close(); throw failure }
        catch (failure: LinkageError) {
            CrashReports.usbLinkage(failure)
            runCatching { close() }
            throw failure
        }
    }
    @Synchronized override fun setVolume(factor: Float) {
        val conn = connection ?: return
        val halfDb = if (factor <= 0f) -32768 else ((DirectUsbSettings.db.value + 20.0 * log10(factor.toDouble())).coerceIn(-50.0, 0.0) * 2).roundToInt() * 128
        if (lastVolume == halfDb) return
        val bytes = byteArrayOf(halfDb.toByte(), (halfDb shr 8).toByte())
        try {
            for (ch in 1..2) {
                check(conn.controlTransfer(0x21, 1, 0x200 or ch, 7 shl 8, bytes, 2, 300) == 2)
                val actual = ByteArray(2)
                check(conn.controlTransfer(0xa1, 1, 0x200 or ch, 7 shl 8, actual, 2, 300) == 2 && actual.contentEquals(bytes))
            }
            lastVolume = halfDb
        } catch (failure: Exception) {
            // Do not leave asymmetric channel levels after a partially successful write.
            val mute = byteArrayOf(0, 0x80.toByte())
            for (ch in 1..2) runCatching { conn.controlTransfer(0x21, 1, 0x200 or ch, 7 shl 8, mute, 2, 300) }
            throw IllegalStateException("ЦАП не подтвердил громкость; поток остановлен", failure)
        }
    }
    @Synchronized override fun setStopped(stopped: Boolean) { if (handle != 0L) NativeUsb.setLiveStopped(handle, stopped) }
    override fun feed(buffer: java.nio.ByteBuffer): Int = NativeUsb.feedLive(handle, buffer, buffer.position(), buffer.remaining())
    override fun position(): Long = NativeUsb.livePosition(handle)
    override fun buffered(): Long = NativeUsb.liveBuffered(handle)
    override fun finish() = NativeUsb.finishLive(handle)
    override fun reset() = NativeUsb.resetLive(handle)
    override fun transmit(stop: AtomicBoolean): String {
        check(connection!!.setInterface(active)) { "ЦАП не принял alt ${profile.alternate}" }
        try {
            DirectUsbSettings.status.value = "Прямой USB · ${profile.label}"
            return NativeUsb.streamLive(handle, 4, 1, maxPacket)
        } finally { check(connection!!.setInterface(inactive)) { "Не подтверждён alt 0" } }
    }
    @Synchronized override fun close() {
        val conn = connection
        if (conn != null) {
            try {
                val stopped = if (::inactive.isInitialized) runCatching { conn.setInterface(inactive) }.getOrDefault(false) else false
                notes += "AS 1 alt 0 при закрытии: $stopped"
                controls?.restore(stopped)
                lease?.close()
            } finally {
                try {
                    try { conn.close() } finally { if (handle != 0L) NativeUsb.destroy(handle); handle = 0; connection = null }
                } finally {
                    DirectUsbSettings.report.value += "\n" + notes.joinToString("\n")
                    DirectUsbSettings.owned.value = false
                    UsbHardwareGate.release(token)
                }
            }
        } else { DirectUsbSettings.owned.value = false; UsbHardwareGate.release(token) }
    }
}
