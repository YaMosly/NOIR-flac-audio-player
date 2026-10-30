package dev.noir.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.PowerManager
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

interface LiveUsbTransport : AutoCloseable {
    fun setStopped(stopped: Boolean) {}
    fun feed(buffer: ByteBuffer): Int
    fun position(): Long
    fun buffered(): Long
    fun finish()
    fun reset()
    fun transmit(stop: AtomicBoolean): String
    fun setVolume(factor: Float)
}

/** ExoPlayer supplies decoded PCM; this output never creates an AudioTrack. */
// Keep this entry point identifiable for installed-APK verification.
@androidx.annotation.Keep
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class UsbAudioOutput @JvmOverloads constructor(private val context: Context, private val profile: UsbPcmProfile = UsbPcmProfile(), factory: () -> LiveUsbTransport = { UsbLiveHardware(context, profile) }) : AudioOutput {
    private val transport = factory()
    private val lock = Object()
    private val interrupted = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    private var playing = false
    private var released = false
    private var eos = false
    private var flushing = false
    private var error: String? = null
    private var finalPosition = 0L
    private var factor = 1f
    private val power = context.getSystemService(PowerManager::class.java)
    private val worker = thread(name = "NOIR-USB", isDaemon = true) { consume() }

    // Do not return from consume() while its outer finally and a monitor overlap.
    // R8 produced invalid catch-all coverage for that shape, even with @Keep.
    private fun awaitPlayable(): Boolean = synchronized(lock) {
        while (!released) {
            if (error != null) throw IllegalStateException(error)
            if (flushing) { transport.reset(); eos = false; flushing = false; finalPosition = 0; lock.notifyAll() }
            val buffered = transport.buffered()
            if (playing && (buffered >= profile.prebufferBytes || eos && buffered > 0)) {
                interrupted.set(false)
                transport.setStopped(false)
                return@synchronized true
            }
            lock.wait(20)
        }
        false
    }

    private fun consume() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NOIR:UsbPlayer")
        try {
            while (awaitPlayable()) {
                wake.acquire()
                try {
                    BackgroundAudioDiagnostics.record(context, "USB start; nice=${Process.getThreadPriority(Process.myTid())}; wake=${wake.isHeld}")
                    val result = transport.transmit(interrupted)
                    BackgroundAudioDiagnostics.record(context, "USB returned; wake=${wake.isHeld}")
                    DirectUsbSettings.report.value = "NOIR ${BuildConfig.VERSION_NAME} — основной плеер, прямой USB\nВремя UTC: ${java.time.Instant.now()}\n" + result
                    if ("Native USB: failed" in result || "Live error:" in result) error(result)
                } finally { if (wake.isHeld) wake.release() }
                synchronized(lock) {
                    finalPosition = transport.position()
                    if (!playing && !released) DirectUsbSettings.status.value = "Прямой USB · пауза"
                }
            }
        } catch (failure: Exception) {
            synchronized(lock) { error = failure.message ?: failure.javaClass.simpleName }
            DirectUsbSettings.status.value = "Ошибка USB: ${failure.message}"
            DirectUsbSettings.failure.value = failure.message ?: "Передача USB прервана"
        } finally {
            if (wake.isHeld) wake.release()
            synchronized(lock) {
                runCatching { finalPosition = transport.position() }
                runCatching { transport.close() }.onFailure { error = it.message }
                released = true
                lock.notifyAll()
            }
            listeners.forEach { it.onReleased() }
        }
    }
    override fun play() = synchronized(lock) { if (!released) { playing = true; lock.notifyAll() }; Unit }
    override fun pause() = synchronized(lock) { playing = false; interrupted.set(true); transport.setStopped(true); Unit }
    override fun stop() = synchronized(lock) { if (!released && !eos) { eos = true; transport.finish(); lock.notifyAll() }; Unit }
    override fun flush() = synchronized(lock) { if (!released) { flushing = true; interrupted.set(true); transport.setStopped(true); lock.notifyAll() }; Unit }
    override fun release() {
        synchronized(lock) { released = true; interrupted.set(true); transport.setStopped(true); lock.notifyAll() }
        if (Thread.currentThread() !== worker) worker.join(8_000)
        // If a vendor syscall exceeds the join, the worker retains every native resource.
        // A subsequent hardware acquire fails until its finally block finishes.
    }
    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean = synchronized(lock) {
        if (released || error != null) throw AudioOutput.WriteException(-1, false)
        if (flushing) return@synchronized false
        if (buffer.remaining() % profile.frameBytes != 0 || !buffer.isDirect) throw AudioOutput.WriteException(-2, false)
        try {
            val accepted = transport.feed(buffer)
            buffer.position(buffer.position() + accepted)
            lock.notifyAll()
            !buffer.hasRemaining()
        } catch (failure: Exception) {
            error = failure.message; interrupted.set(true); transport.setStopped(true)
            throw AudioOutput.WriteException(-3, false)
        }
    }
    override fun getPositionUs(): Long = synchronized(lock) {
        if (!released && !flushing) finalPosition = transport.position()
        profile.durationUs(finalPosition)
    }
    override fun setVolume(volume: Float) = synchronized(lock) {
        factor = volume
        if (!released) try { transport.setVolume(volume) } catch (failure: Exception) {
            error = failure.message; interrupted.set(true); transport.setStopped(true); DirectUsbSettings.failure.value = failure.message
        }
    }
    fun refreshVolume() = setVolume(synchronized(lock) { factor })
    override fun getSampleRate() = profile.rate
    override fun getBufferSizeInFrames() = profile.rate * UsbPcmProfile.BUFFER_SECONDS.toLong()
    override fun getAudioSessionId() = C.AUDIO_SESSION_ID_UNSET
    override fun isOffloadedPlayback() = false
    override fun isStalled() = synchronized(lock) { error != null }
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun setPlaybackParameters(playbackParams: PlaybackParameters) { require(playbackParams == PlaybackParameters.DEFAULT) }
    override fun addListener(listener: AudioOutput.Listener) { listeners.add(listener) }
    override fun removeListener(listener: AudioOutput.Listener) { listeners.remove(listener) }
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {}
    override fun setOffloadEndOfStream() {}
    override fun attachAuxEffect(effectId: Int) {}
    override fun setAuxEffectSendLevel(level: Float) {}
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {}
    override fun canReuseAudioOutput(currentConfig: AudioOutputProvider.OutputConfig, newFormat: AudioOutputProvider.FormatConfig, newConfig: AudioOutputProvider.OutputConfig) = false
}
