package dev.noir.player

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat

/** USB consent is requested from the visible activity, never from the background player. */
class UsbPlaybackAccess(private val context: Context) : AutoCloseable {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val action = "${context.packageName}.USB_PLAYBACK_PERMISSION"
    private var pending: String? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != action) return
            val id = pending ?: return
            pending = null
            val device = manager.deviceList[id]
            if (device != null && manager.hasPermission(device) && !UsbPlaybackInterlock.active.value) {
                DirectUsbSettings.bindDevice(device.vendorId, device.productId, runCatching { device.serialNumber }.getOrNull(), device.deviceName)
                DirectUsbSettings.setEnabled(true)
                DirectUsbSettings.status.value = "Прямой USB включён · нажми Play"
            } else DirectUsbSettings.status.value = "USB-доступ не получен · повтори включение"
        }
    }
    init { ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED) }
    fun enable() {
        if (UsbPlaybackInterlock.active.value) return
        val device = runCatching { UsbPlaybackDevices.select(manager) }.getOrElse {
            DirectUsbSettings.status.value = it.message.orEmpty(); return
        }
        DirectUsbSettings.failure.value = null
        if (manager.hasPermission(device)) {
            DirectUsbSettings.bindDevice(device.vendorId, device.productId, runCatching { device.serialNumber }.getOrNull(), device.deviceName)
            DirectUsbSettings.setEnabled(true)
            DirectUsbSettings.status.value = "Прямой USB включён · нажми Play"
        } else {
            pending = device.deviceName
            runCatching {
                manager.requestPermission(device, PendingIntent.getBroadcast(context, 206, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }.onFailure { pending = null; DirectUsbSettings.status.value = "Не удалось запросить USB-доступ" }
        }
    }
    override fun close() { pending = null; context.unregisterReceiver(receiver) }
}
