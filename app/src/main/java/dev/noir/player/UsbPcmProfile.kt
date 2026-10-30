package dev.noir.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/** Exact stereo PCM format shared by the decoder, position clock and USB transport. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
data class UsbPcmProfile(val rate: Int = 44_100, val bits: Int = 16) {
    init { require(rate in rates && bits in setOf(16, 24, 32)) }
    val sampleBytes get() = bits / 8
    val frameBytes get() = sampleBytes * 2
    val bufferBytes get() = rate * frameBytes * BUFFER_SECONDS
    val prebufferBytes get() = rate / 2 * frameBytes
    val encoding get() = when (bits) { 16 -> C.ENCODING_PCM_16BIT; 24 -> C.ENCODING_PCM_24BIT; else -> C.ENCODING_PCM_32BIT }
    val alternate get() = when (bits) { 16 -> 1; 24 -> 2; else -> 3 }
    val label get() = "PCM $bits / ${rate / 1000.0} кГц"
    fun durationUs(bytes: Long) = bytes / frameBytes * 1_000_000L / rate
    companion object {
        const val BUFFER_SECONDS = 8
        val rates = setOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000)
        fun supports(track: Track) = track.channels == 2 && track.bits in setOf(16, 24, 32) && track.sampleRate in rates
        fun from(format: Format): UsbPcmProfile? {
            if (format.sampleMimeType != MimeTypes.AUDIO_RAW || format.channelCount != 2 || format.sampleRate !in rates) return null
            val bits = when (format.pcmEncoding) { C.ENCODING_PCM_16BIT -> 16; C.ENCODING_PCM_24BIT -> 24; C.ENCODING_PCM_32BIT -> 32; else -> return null }
            return UsbPcmProfile(format.sampleRate, bits)
        }
    }
}
