package dev.noir.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

data class OutputState(val device: String = "USB-ЦАП не подключён", val usb: Boolean = false, val rates: List<Int> = emptyList(), val mixerFormats: List<String> = emptyList(), val bitPerfectAvailable: Boolean = false)

class AudioDiagnostics(context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    val state = MutableStateFlow(OutputState())
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }
    fun start() { manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())); refresh() }
    fun close() { manager.unregisterAudioDeviceCallback(callback) }
    fun refresh() {
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val usb = outputs.lastOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        if (usb == null) { state.value = OutputState(); return }
        val formats = runCatching { manager.getSupportedMixerAttributes(usb) }.getOrDefault(emptyList())
        state.value = OutputState(usb.productName.toString().ifBlank { "USB-ЦАП" }, true, usb.sampleRates.sorted(), formats.map { "${it.format.sampleRate} Гц · ${encoding(it.format.encoding)}${if (it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) " · bit-perfect" else ""}" }.distinct(), formats.any { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT })
    }
    private fun encoding(value: Int): String = when (value) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM 16"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM 32"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
        else -> "формат $value"
    }
}
