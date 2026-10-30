package dev.noir.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

data class PcmPreview(val bytes: ByteArray, val decoder: String) {
    val sha256: String get() = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Bounded decode to original stereo PCM16/44100. No AudioTrack, DSP, resampling or float conversion. */
object FlacPcmPreview {
    const val MAX_SECONDS = 15
    fun decode(context: Context, track: Track, stop: AtomicBoolean): PcmPreview {
        require(track.bits == 16 && track.channels == 2 && track.sampleRate == 44_100) { "Проба поддерживает FLAC 16 бит / 44,1 кГц / стерео" }
        context.contentResolver.openInputStream(Uri.parse(track.id))!!.use {
            val metadata = FlacMetadata.read(it)
            require(metadata.bits == 16 && metadata.channels == 2 && metadata.rate == 44_100) { "Формат FLAC не совпадает с библиотекой. Обнови папку." }
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(context, Uri.parse(track.id), null)
            val index = (0 until extractor.trackCount).single { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) in setOf("audio/flac", "audio/raw") }
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            require(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 44_100 && format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2)
            val limit = 44_100 * 4 * MAX_SECONDS
            val output = ByteArrayOutputStream(limit)
            val deadline = SystemClock.elapsedRealtime() + 25_000
            fun checkOutput(actual: MediaFormat) {
                require(actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 44_100 && actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2 && actual.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_16BIT) { "Декодер изменил формат PCM; передача отменена" }
            }
            fun checkRunning() {
                if (stop.get()) throw CancellationException("Проба остановлена")
                check(SystemClock.elapsedRealtime() < deadline) { "Истекло время декодирования FLAC" }
            }
            // AOSP FLACExtractor itself uses libFLAC and exposes decoded audio/raw.
            // Some vendor extractors instead expose compressed audio/flac for MediaCodec.
            if (format.getString(MediaFormat.KEY_MIME) == "audio/raw") {
                checkOutput(format)
                val buffer = ByteBuffer.allocate(1024 * 1024)
                while (output.size() < limit) {
                    checkRunning()
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    require(size in 1..buffer.capacity() && size % 4 == 0) { "Некорректный блок PCM от FLACExtractor" }
                    val count = minOf(size, limit - output.size())
                    output.write(buffer.array(), 0, count)
                    if (!extractor.advance()) break
                }
                check(output.size() > 0)
                return PcmPreview(output.toByteArray(), "Android FLACExtractor / audio/raw PCM16")
            }
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType("audio/flac")
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start(); started = true
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            while (!outputEnded && output.size() < limit) {
                checkRunning()
                if (!inputEnded) {
                    val slot = decoder.dequeueInputBuffer(10_000)
                    if (slot >= 0) {
                        val buffer = decoder.getInputBuffer(slot)!!
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) { decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { decoder.queueInputBuffer(slot, 0, count, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                when (val slot = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> checkOutput(decoder.outputFormat)
                    in 0..Int.MAX_VALUE -> {
                        try {
                            checkOutput(decoder.outputFormat)
                            if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                require(info.size % 4 == 0) { "Неполный кадр PCM" }
                                val count = minOf(info.size, limit - output.size())
                                val data = ByteArray(count)
                                decoder.getOutputBuffer(slot)!!.apply { position(info.offset); limit(info.offset + count); get(data) }
                                output.write(data)
                            }
                            outputEnded = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        } finally { decoder.releaseOutputBuffer(slot, false) }
                    }
                }
            }
            check(output.size() > 0 && output.size() % 4 == 0) { "FLAC не дал PCM-данных" }
            return PcmPreview(output.toByteArray(), decoder.name)
        } finally {
            try { if (started) runCatching { codec?.stop() }; codec?.release() } finally { extractor.release() }
        }
    }
}
