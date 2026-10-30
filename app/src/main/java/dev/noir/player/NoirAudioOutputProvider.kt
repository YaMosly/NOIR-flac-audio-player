package dev.noir.player

import android.content.Context
import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NoirAudioOutputProvider(
    private val context: Context,
    private val usbFactory: (UsbPcmProfile) -> AudioOutput = { UsbAudioOutput(context, it) },
) : AudioOutputProvider {
    private val android = AudioTrackAudioOutputProvider.Builder(context).build()
    @Volatile private var usb: UsbAudioOutput? = null
    private fun direct() = DirectUsbSettings.enabled.value
    override fun getFormatSupport(config: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        if (!direct()) return android.getFormatSupport(config)
        return AudioOutputProvider.FormatSupport.Builder().setFormatSupportLevel(
            if (accepts(config.format)) AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY else AudioOutputProvider.FORMAT_UNSUPPORTED,
        ).build()
    }
    override fun getOutputConfig(config: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        if (!direct()) return android.getOutputConfig(config)
        val profile = UsbPcmProfile.from(config.format) ?: throw AudioOutputProvider.ConfigurationException("Нужен PCM 16–32 бит / 44,1–384 кГц / стерео")
        return AudioOutputProvider.OutputConfig.Builder().setEncoding(profile.encoding).setSampleRate(profile.rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setBufferSize(profile.bufferBytes).setAudioAttributes(config.audioAttributes).build()
    }
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        if (!direct()) {
            DirectUsbSettings.status.value = "Android · системный вывод"
            return android.getAudioOutput(config)
        }
        try {
            DirectUsbSettings.failure.value = null
            val bits = when (config.encoding) { C.ENCODING_PCM_24BIT -> 24; C.ENCODING_PCM_32BIT -> 32; else -> 16 }
            return usbFactory(UsbPcmProfile(config.sampleRate, bits)).also { usb = it as? UsbAudioOutput }
        } catch (failure: Exception) {
            DirectUsbSettings.status.value = "USB не запущен: ${failure.message}"
            DirectUsbSettings.failure.value = failure.message
            throw AudioOutputProvider.InitializationException(failure)
        } catch (failure: LinkageError) {
            CrashReports.usbLinkage(failure)
            DirectUsbSettings.status.value = "Не удалось загрузить USB-движок: ${failure.javaClass.simpleName}"
            DirectUsbSettings.failure.value = DirectUsbSettings.status.value
            throw AudioOutputProvider.InitializationException(failure)
        }
    }
    fun refreshVolume() { usb?.refreshVolume() }
    override fun addListener(listener: AudioOutputProvider.Listener) = android.addListener(listener)
    override fun removeListener(listener: AudioOutputProvider.Listener) = android.removeListener(listener)
    override fun hasPendingReleases() = android.hasPendingReleases()
    override fun release() { usb?.release(); usb = null; android.release() }
    companion object {
        fun accepts(format: Format) = UsbPcmProfile.from(format) != null
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NoirRenderersFactory(context: Context, private val outputs: NoirAudioOutputProvider) : DefaultRenderersFactory(context) {
    init { setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER) }
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
        val sink = DefaultAudioSink.Builder(context).setEnableFloatOutput(true).setAudioOutputProvider(outputs).build()
        return NoirPcmAudioSink(sink, outputs)
    }
}
