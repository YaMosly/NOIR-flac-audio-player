package dev.noir.player

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/** Ignore HID/storage devices and never arbitrarily pick one of several DACs. */
object UsbPlaybackDevices {
    fun select(manager: UsbManager): UsbDevice {
        val candidates = manager.deviceList.values.filter { device ->
            (0 until device.interfaceCount).any { index ->
                val iface = device.getInterface(index)
                iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 2 &&
                    (0 until iface.endpointCount).any { endpoint ->
                        val ep = iface.getEndpoint(endpoint)
                        ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
                    }
            }
        }
        check(candidates.isNotEmpty()) { "Подключи USB-аудио ЦАП. Для остальных устройств используй вывод Android." }
        check(candidates.size == 1) { "Подключено несколько USB-ЦАПов. Оставь один для прямого режима." }
        return candidates.single()
    }
}
