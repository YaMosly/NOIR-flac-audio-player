package dev.noir.player

import androidx.media3.common.*
import androidx.media3.exoplayer.audio.*
import java.nio.ByteBuffer

/** Bypasses DefaultAudioSink's unconditional 20ms PCM volume ramp in the direct path. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NoirPcmAudioSink(android: AudioSink, private val outputs: NoirAudioOutputProvider) : ForwardingAudioSink(android) {
    private var direct = false
    private var config: AudioSink.AudioSinkConfig? = null
    private var output: AudioOutput? = null
    private var period: Any? = null
    private var listener: AudioSink.Listener? = null
    private var playing = false
    private var eos = false
    private var written = 0L
    private var firstPts = C.TIME_UNSET
    private var volume = 1f
    private var discontinuity = false
    private var streamOffset = 0L
    private var hash = java.security.MessageDigest.getInstance("SHA-256")
    private var hashed = 0
    private var sourceTitle = ""
    private var profile = UsbPcmProfile()

    override fun setListener(listener: AudioSink.Listener) { this.listener = listener; super.setListener(listener) }
    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatSupport(format: Format): Int {
        if (!DirectUsbSettings.enabled.value) return super.getFormatSupport(format)
        // Route raw PCM to configure(), where unsupported formats get a visible error,
        // rather than letting ExoPlayer silently disable the audio track or transcode it.
        // In particular do not advertise float: MediaCodecAudioRenderer would then
        // request float from the decoder even for a 16-bit FLAC source.
        return if (format.sampleMimeType == MimeTypes.AUDIO_RAW && format.pcmEncoding in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED
    }
    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        if (DirectUsbSettings.enabled.value) AudioOffloadSupport.DEFAULT_UNSUPPORTED else super.getFormatOffloadSupport(format)

    override fun configure(config: AudioSink.AudioSinkConfig) {
        val nextDirect = DirectUsbSettings.enabled.value
        if (direct != nextDirect) { flush(); super.flush() }
        direct = nextDirect
        if (!direct) { super.configure(config); return }
        val index = config.mediaPeriodId?.let { config.timeline.getIndexOfPeriod(it.periodUid) } ?: C.INDEX_UNSET
        val source = if (index != C.INDEX_UNSET) {
            val period = config.timeline.getPeriod(index, Timeline.Period())
            config.timeline.getWindow(period.windowIndex, Timeline.Window()).mediaItem.localConfiguration?.tag as? Track
        } else null
        val decoded = UsbPcmProfile.from(config.format)
        val sourceSupported = source == null || decoded != null && source.bits == decoded.bits && source.sampleRate == decoded.rate && source.channels == 2
        if (!NoirAudioOutputProvider.accepts(config.format) || !sourceSupported || config.format.encoderDelay > 0 || config.format.encoderPadding > 0) {
            DirectUsbSettings.status.value = "Нужен FLAC 16–32 бит / 44,1–384 кГц / стерео без изменения формата"
            throw AudioSink.ConfigurationException("Прямой USB: неподдержанный исходный формат: PCM=${config.format.pcmEncoding}, delay=${config.format.encoderDelay}, padding=${config.format.encoderPadding}, source=${source?.format}", config.format)
        }
        if (output == null) profile = checkNotNull(decoded)
        this.config = config
        sourceTitle = source?.title ?: "неизвестный трек"
    }
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!direct) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val config = checkNotNull(config)
        val nextPeriod = config.mediaPeriodId?.periodUid
        val nextProfile = checkNotNull(UsbPcmProfile.from(config.format))
        if (output != null && (period != nextPeriod || profile != nextProfile)) {
            playToEndOfStream()
            if (hasPendingData()) return false
            flushDirect()
        }
        if (output == null) {
            profile = nextProfile
            try {
                DirectUsbSettings.decodedHash.value = ""
                val target = outputs.getOutputConfig(AudioOutputProvider.FormatConfig.Builder(config.format).build())
                output = outputs.getAudioOutput(target).also { it.setVolume(volume); if (playing) it.play() }
                period = nextPeriod
            } catch (failure: Exception) { throw AudioSink.InitializationException(failure.message ?: "USB", 0, config.format, false, failure) }
        }
        if (firstPts == C.TIME_UNSET) firstPts = presentationTimeUs
        if (discontinuity) {
            firstPts = presentationTimeUs - profile.durationUs(written)
            discontinuity = false
            listener?.onPositionDiscontinuity()
        }
        val before = buffer.remaining()
        val originalPosition = buffer.position()
        try {
            val handled = output!!.write(buffer, encodedAccessUnitCount, presentationTimeUs)
            val accepted = before - buffer.remaining()
            written += accepted
            val count = minOf(accepted, profile.rate * profile.frameBytes * 15 - hashed)
            if (count > 0) {
                val view = buffer.duplicate().apply { position(originalPosition); limit(originalPosition + count) }
                hash.update(view); hashed += count
                if (hashed == profile.rate * profile.frameBytes * 15) {
                    DirectUsbSettings.decodedHash.value = "PCM SHA-256 · ${profile.label} · $sourceTitle · 15 с с позиции ${(firstPts - streamOffset) / 1000} мс: " + hash.digest().joinToString("") { "%02x".format(it) }
                }
            }
            return handled
        } catch (failure: AudioOutput.WriteException) { throw AudioSink.WriteException(failure.errorCode, config.format, false) }
    }
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!direct) return super.getCurrentPositionUs(sourceEnded)
        if (firstPts == C.TIME_UNSET) return AudioSink.CURRENT_POSITION_NOT_SET
        return firstPts + minOf(output?.positionUs ?: 0, profile.durationUs(written))
    }
    override fun play() { playing = true; if (direct) output?.play() else super.play() }
    override fun pause() { playing = false; if (direct) output?.pause() else super.pause() }
    override fun playToEndOfStream() { if (!direct) super.playToEndOfStream() else if (!eos) { output?.stop(); eos = true } }
    override fun hasPendingData(): Boolean = if (!direct) super.hasPendingData() else output != null && (output!!.positionUs < profile.durationUs(written))
    override fun isEnded() = if (!direct) super.isEnded() else (eos && !hasPendingData())
    override fun handleDiscontinuity() { if (direct) discontinuity = true else super.handleDiscontinuity() }
    override fun setVolume(volume: Float) { this.volume = volume; output?.setVolume(volume); super.setVolume(volume) }
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) { if (!DirectUsbSettings.enabled.value) super.setPlaybackParameters(playbackParameters) }
    override fun getPlaybackParameters(): PlaybackParameters = if (DirectUsbSettings.enabled.value) PlaybackParameters.DEFAULT else super.getPlaybackParameters()
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) { if (!DirectUsbSettings.enabled.value) super.setSkipSilenceEnabled(skipSilenceEnabled) }
    override fun getSkipSilenceEnabled() = !DirectUsbSettings.enabled.value && super.getSkipSilenceEnabled()
    override fun getAudioTrackBufferSizeUs() = if (direct) UsbPcmProfile.BUFFER_SECONDS * 1_000_000L else super.getAudioTrackBufferSizeUs()
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) { streamOffset = outputStreamOffsetUs; super.setOutputStreamOffsetUs(outputStreamOffsetUs) }
    private fun flushDirect() {
        output?.release(); output = null; written = 0; firstPts = C.TIME_UNSET; eos = false; period = null; discontinuity = false
        hash = java.security.MessageDigest.getInstance("SHA-256"); hashed = 0
    }
    override fun flush() { flushDirect(); super.flush() }
    override fun reset() { flush(); playing = false; super.reset() }
    override fun release() { flush(); super.release() }
}
