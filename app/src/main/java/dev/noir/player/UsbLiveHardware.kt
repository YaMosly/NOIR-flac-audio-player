package dev.noir.player

import android.content.Context
import android.hardware.usb.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.roundToInt

/** Exclusive descriptor-selected UAC2 connection. Construct/release on the worker. */
class UsbLiveHardware @JvmOverloads constructor(private val context: Context, private val profile: UsbPcmProfile = UsbPcmProfile()) : LiveUsbTransport {
    private val token = Any()
    private var connection: UsbDeviceConnection? = null
    private var lease: UsbInterfaceLease? = null
    private var controls: UsbPreviewControls? = null
    private lateinit var inactive: UsbInterface
    private lateinit var active: UsbInterface
    private var maxPacket = 196
    private lateinit var plan: UsbPlaybackPlan
    private val notes = mutableListOf<String>()
    var handle = 0L
        private set
    private var lastVolume: Int? = null

    init {
        check(!UsbPlaybackInterlock.active.value && UsbHardwareGate.acquire(token)) { "USB занят диагностикой" }
        DirectUsbSettings.owned.value = true
        try {
            val manager = context.getSystemService(UsbManager::class.java)
            val device = UsbPlaybackDevices.select(manager)
            check(manager.hasPermission(device)) { "Разреши прямой USB в разделе «Звуковой тракт»" }
            val conn = manager.openDevice(device) ?: error("Не удалось открыть ЦАП")
            connection = conn
            handle = NativeUsb.create(conn.fileDescriptor)
            val description = UsbAudioDescriptors.parse(conn.rawDescriptors)
            val interfaces = (0 until device.interfaceCount).map(device::getInterface)
            val endpoints = interfaces.filter { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 2 }.flatMap { iface ->
                (0 until iface.endpointCount).map { index -> iface.getEndpoint(index).let { ep ->
                    UsbPlaybackEndpoint(iface.id, iface.alternateSetting, ep.address, ep.attributes, ep.interval, ep.maxPacketSize)
                } }
            }
            plan = UsbPlaybackPlans.select(description, endpoints, profile.rate, profile.bits, NativeUsb.speed(handle))
            val ac = interfaces.single { it.id == plan.clock.controlInterface && it.alternateSetting == 0 && it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1 }
            inactive = interfaces.single { it.id == plan.format.iface && it.alternateSetting == 0 && it.endpointCount == 0 }
            active = interfaces.single { it.id == plan.format.iface && it.alternateSetting == plan.format.alternate }
            maxPacket = plan.endpoint.maxPacket
            NativeUsb.configureLive(handle, profile.rate, profile.frameBytes)
            DirectUsbSettings.device.value = device.productName ?: "USB ${device.vendorId.toString(16)}:${device.productId.toString(16)}"
            notes += "UAC2: AC ${ac.id}, AS ${active.id}, alt ${active.alternateSetting}, EP ${plan.endpoint.address}, interval ${plan.endpoint.interval}"
            val control = UsbPreviewControls(
                read = { selector, entity, channel, request, length ->
                    val data = ByteArray(length)
                    check(conn.controlTransfer(0xa1, request, selector shl 8 or channel, entity shl 8 or ac.id, data, length, 300) == length) { "USB GET не получен" }; data
                },
                write = { selector, entity, channel, bytes ->
                    check(conn.controlTransfer(0x21, 1, selector shl 8 or channel, entity shl 8 or ac.id, bytes, bytes.size, 300) == bytes.size) { "USB SET не подтверждён" }
                }, note = notes::add, clockId = plan.clock.id, volumeUnit = plan.volumeUnit, channels = plan.volumeChannels,
            )
            controls = control
            lease = UsbInterfaceLease(
                acquire = { conn.claimInterface(if (it == ac.id) ac else inactive, true) },
                release = { conn.releaseInterface(if (it == ac.id) ac else inactive) }, note = notes::add,
            )
            lease!!.claim(listOf(ac.id, inactive.id))
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
        require(factor.isFinite() && factor in 0f..1f)
        val halfDb = if (factor <= 0f) -32768 else controls!!.volumeValue(((DirectUsbSettings.db.value + 20.0 * log10(factor.toDouble())).coerceIn(-50.0, 0.0) * 256).roundToInt())
        if (lastVolume == halfDb) return
        val bytes = byteArrayOf(halfDb.toByte(), (halfDb shr 8).toByte())
        try {
            for (ch in plan.volumeChannels) {
                check(conn.controlTransfer(0x21, 1, 0x200 or ch, plan.volumeUnit shl 8 or plan.clock.controlInterface, bytes, 2, 300) == 2)
                val actual = ByteArray(2)
                check(conn.controlTransfer(0xa1, 1, 0x200 or ch, plan.volumeUnit shl 8 or plan.clock.controlInterface, actual, 2, 300) == 2 && actual.contentEquals(bytes))
            }
            lastVolume = halfDb
        } catch (failure: Exception) {
            // Do not leave asymmetric channel levels after a partially successful write.
            val mute = byteArrayOf(0, 0x80.toByte())
            for (ch in plan.volumeChannels) runCatching { conn.controlTransfer(0x21, 1, 0x200 or ch, plan.volumeUnit shl 8 or plan.clock.controlInterface, mute, 2, 300) }
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
        check(connection!!.setInterface(active)) { "ЦАП не принял alt ${active.alternateSetting}" }
        try {
            DirectUsbSettings.status.value = "Прямой USB · ${profile.label}"
            return NativeUsb.streamLive(handle, plan.endpoint.address, plan.endpoint.interval, maxPacket)
        } finally { check(connection!!.setInterface(inactive)) { "Не подтверждён alt 0" } }
    }
    @Synchronized override fun close() {
        val conn = connection
        if (conn != null) {
            try {
                val stopped = if (::inactive.isInitialized && lease != null) runCatching { conn.setInterface(inactive) }.getOrDefault(false) else false
                notes += "AS ${if (::inactive.isInitialized) inactive.id else "?"} alt 0 при закрытии: $stopped"
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
