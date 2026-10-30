package dev.noir.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class UsbDirectPreviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun tracks() = runBlocking { withTimeout(10_000) { (context.applicationContext as NoirApp).library.tracks.first { it.size == 3 } } }

    @Test fun actualFlacDecoderProducesExactOriginalIntegerPcm() {
        val track = tracks().single { it.bits == 16 }
        val pcm = FlacPcmPreview.decode(context, track, AtomicBoolean(false))
        assertEquals(44_100 * 4 * 15, pcm.bytes.size)
        // Reference: libsndfile PCM16 little-endian decode of the same generated FLAC, first 15s.
        assertEquals("637742ffa92d0ce6101196e8806a5938ec7af2874d4033681ef714689920eedc", pcm.sha256)
        assertTrue(pcm.decoder.isNotBlank())
        assertThrows(IllegalArgumentException::class.java) { FlacPcmPreview.decode(context, tracks().first { it.bits == 24 }, AtomicBoolean(false)) }
        assertThrows(java.util.concurrent.CancellationException::class.java) { FlacPcmPreview.decode(context, track, AtomicBoolean(true)) }
    }

    @Test fun nativePacketTimingPreservesEveryFrameAcrossFractional44100Rate() {
        for (intervals in listOf(1000, 2000, 4000, 8000)) {
            val plan = NativeUsb.packetPlan(44_100, intervals, intervals * 15)
            assertEquals(44_100 * 15, plan.sum())
            assertEquals(setOf(44_100 / intervals, 44_100 / intervals + 1), plan.toSet())
            var frames = 0L
            plan.forEachIndexed { index, size ->
                frames += size
                assertEquals((index + 1L) * 44_100 / intervals, frames)
            }
        }
        assertThrows(IllegalStateException::class.java) { NativeUsb.create(-1) }
        assertThrows(IllegalStateException::class.java) { NativeUsb.packetPlan(44_100, 0, 10) }
    }

    @Test fun noDacAndBackgroundDoNotAcquireInterlockOrStartPlayback() {
        val track = tracks().single { it.bits == 16 }
        instrumentation.runOnMainSync {
            val preview = UsbDirectPreview(context)
            try {
                preview.start(); preview.start()
                preview.request(track, -35)
                assertFalse(preview.state.value.busy)
                assertTrue(preview.state.value.message.contains("0E55:1110"))
                assertFalse(UsbPlaybackInterlock.active.value)
                preview.setForeground(false)
                preview.request(track, -35)
                assertFalse(preview.state.value.busy)
                assertFalse(UsbPlaybackInterlock.active.value)
                preview.stop()
            } finally { preview.close(); preview.close() }
        }
    }

    @Test fun nativeHandleOwnsDuplicateAndRejectsNonUsbFdBeforeTransmission() {
        val file = java.io.File.createTempFile("noir-fd-", ".tmp", context.cacheDir)
        val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val handle = NativeUsb.create(descriptor.fd)
        try {
            assertEquals(-1, NativeUsb.speed(handle))
            assertThrows(IllegalStateException::class.java) { NativeUsb.stream(handle, 4, 1, 196, ByteArray(400), AtomicBoolean(false)) }
            assertTrue(descriptor.fileDescriptor.valid())
        } finally {
            descriptor.close()
            NativeUsb.destroy(handle)
            file.delete()
        }
    }
}
