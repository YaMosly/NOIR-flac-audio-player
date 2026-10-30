package dev.noir.player

import android.content.Context
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real FLAC decoder, DefaultAudioSink, USB AudioOutput and MediaSession; only hardware is fake. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class UsbPlayerIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun eventually(timeout: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(30) }
        fail("Condition did not become true")
    }
    private fun onPlayer(condition: () -> Boolean): Boolean { var result = false; main { result = condition() }; return result }
    private fun tracks() = runBlocking { withTimeout(10_000) { (context.applicationContext as NoirApp).library.tracks.first { it.size == 3 } } }

    private class FakeHardware(val profile: UsbPcmProfile) : LiveUsbTransport {
        val received = ByteArrayOutputStream()
        @Volatile var completed = 0L
        @Volatile var closed = false
        private var queued = 0L
        private var ended = false
        private val nativeStopped = AtomicBoolean(true)
        override fun setStopped(stopped: Boolean) { nativeStopped.set(stopped) }
        @Synchronized override fun feed(buffer: ByteBuffer): Int {
            check(!closed && !ended)
            val count = minOf(buffer.remaining(), (profile.bufferBytes - queued).toInt())
            val data = ByteArray(count); buffer.duplicate().get(data); received.write(data)
            queued += count; return count
        }
        @Synchronized override fun position() = completed
        @Synchronized override fun buffered() = queued
        @Synchronized override fun finish() { ended = true }
        @Synchronized override fun reset() { queued = 0; completed = 0; ended = false; received.reset() }
        override fun transmit(stop: AtomicBoolean): String {
            while (!nativeStopped.get()) {
                val count = synchronized(this) {
                    check(!closed)
                    if (queued == 0L && ended) return "Native USB: complete"
                    minOf(profile.rate / 50 * profile.frameBytes, queued.toInt())
                }
                Thread.sleep(20)
                if (!nativeStopped.get()) synchronized(this) { completed += count; queued -= count }
            }
            return "Native USB: stopped"
        }
        override fun setVolume(factor: Float) {}
        @Synchronized override fun close() { closed = true }
        @Synchronized fun bytes() = received.toByteArray()
    }

    private fun withPlayer(block: (ExoPlayer, List<FakeHardware>) -> Unit) {
        main { context.stopService(android.content.Intent(context, PlaybackService::class.java)) }
        eventually { onPlayer { UsbPlaybackInterlock.stopPlayback == null } }
        val transports = CopyOnWriteArrayList<FakeHardware>()
        lateinit var player: ExoPlayer
        main {
            DirectUsbSettings.setEnabled(true)
            DirectUsbSettings.failure.value = null
            val outputs = NoirAudioOutputProvider(context) { profile -> UsbAudioOutput(context, profile) { FakeHardware(profile).also(transports::add) } }
            player = ExoPlayer.Builder(context, NoirRenderersFactory(context, outputs)).build()
        }
        try { block(player, transports) } finally { main { player.release(); DirectUsbSettings.setEnabled(false) } }
        assertTrue("Every output must release its transport", transports.all { it.closed })
    }

    @Test fun highResolutionContinuesInBackgroundAndScreenOffDuringGcPressure() {
        val track = tracks().single { it.sampleRate == 192_000 }
        withPlayer { player, transports ->
            main { player.setMediaItem(track.toMediaItem()); player.prepare(); player.play() }
            eventually { onPlayer { player.currentPosition > 300 && player.isPlaying } }
            instrumentation.uiAutomation.executeShellCommand("input keyevent 3").close()
            instrumentation.uiAutomation.executeShellCommand("input keyevent 223").close()
            val start = System.currentTimeMillis()
            try {
                while (System.currentTimeMillis() - start < 5500) {
                    val pressure = ByteArray(256_000); pressure[0] = 1; System.gc(); Thread.sleep(150)
                }
                main { assertTrue(player.isPlaying); assertNull(player.playerError); assertTrue(player.currentPosition > 4000) }
                assertTrue(transports.single().completed > track.sampleRate * 6L * 4)
            } finally { instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close() }
        }
    }

    @Test fun fullTrackBeyondFifteenSecondsHasExactPcmAndFinishes() {
        val track = tracks().single { it.bits == 16 }
        val reference = FlacPcmPreview.decode(context, track, AtomicBoolean(false)).bytes
        withPlayer { player, transports ->
            main { player.setMediaItem(track.toMediaItem()); player.prepare(); player.play() }
            eventually(30_000) { onPlayer { player.playbackState == Player.STATE_ENDED || player.playerError != null } }
            main { assertNull(player.playerError); assertEquals(Player.STATE_ENDED, player.playbackState) }
            val hardware = transports.single()
            assertEquals(3_528_000L, hardware.completed)
            assertEquals(3_528_000, hardware.bytes().size)
            assertArrayEquals(reference, hardware.bytes().copyOf(reference.size))
            assertTrue(DirectUsbSettings.decodedHash.value.contains("637742ffa92d0ce6101196e8806a5938ec7af2874d4033681ef714689920eedc"))
            assertTrue(DirectUsbSettings.decodedHash.value.contains("с позиции 0 мс"))
        }
    }

    @Test fun mediaControllerPauseResumeSeekNextAndScreenOffUseUsbOutput() {
        val track = tracks().single { it.bits == 16 }
        withPlayer { player, transports ->
            lateinit var session: MediaSession
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { session = MediaSession.Builder(context, player).setId("usb-integration").build(); future = MediaController.Builder(context, session.token).buildAsync() }
            val controller = future.get(10, TimeUnit.SECONDS)
            try {
                main {
                    controller.setMediaItems(listOf(track.toMediaItem(), track.toMediaItem().buildUpon().setMediaId("repeat").build()))
                    controller.prepare(); controller.play()
                }
                eventually { onPlayer { controller.isPlaying && controller.currentPosition > 800 } }
                main { controller.pause() }
                Thread.sleep(100)
                var paused = 0L; main { paused = controller.currentPosition }
                Thread.sleep(400)
                main { assertFalse(controller.isPlaying); assertTrue(kotlin.math.abs(controller.currentPosition - paused) < 50); controller.play() }
                eventually { onPlayer { controller.currentPosition > paused + 500 } }
                main { controller.seekTo(8_000) }
                eventually { onPlayer { controller.currentPosition > 8_300 && controller.isPlaying } }
                assertTrue(transports.first().closed)
                main { controller.seekToNextMediaItem() }
                eventually { onPlayer { controller.currentMediaItem?.mediaId == "repeat" && controller.currentPosition > 300 } }
                instrumentation.uiAutomation.executeShellCommand("input keyevent 223").close()
                var before = 0L; main { before = controller.currentPosition }
                Thread.sleep(1_500)
                main { assertTrue(controller.isPlaying); assertNull(controller.playerError); assertTrue(controller.currentPosition > before + 500) }
            } finally {
                instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
                main { controller.release(); session.release() }
            }
        }
    }

    private fun manifest(): List<org.json.JSONObject> {
        val json = org.json.JSONArray(instrumentation.context.assets.open("hires/manifest.json").bufferedReader().use { it.readText() })
        return (0 until json.length()).map(json::getJSONObject)
    }
    private fun assertReference(expected: org.json.JSONObject, hardware: FakeHardware) {
        val data = hardware.bytes()
        assertEquals(expected.getInt("rate"), hardware.profile.rate)
        assertEquals(expected.getInt("bits"), hardware.profile.bits)
        assertEquals(expected.getInt("bytes"), data.size)
        assertEquals(data.size.toLong(), hardware.completed)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        assertEquals(expected.getString("file"), expected.getString("sha256"), hash)
    }
    @Test fun everyUsbRateAndIntegerDepthPreservesAllPcmIncludingLowBits() {
        for (expected in manifest().filter { !it.optBoolean("library") }) {
            val name = expected.getString("file")
            val file = java.io.File(context.cacheDir, name)
            instrumentation.context.assets.open("hires/$name").use { input -> file.outputStream().use(input::copyTo) }
            try {
                val track = Track(android.net.Uri.fromFile(file).toString(), name, "test", durationMs = 1000,
                    sampleRate = expected.getInt("rate"), bits = expected.getInt("bits"), channels = 2)
                withPlayer { player, transports ->
                    main { player.setMediaItem(track.toMediaItem()); player.prepare(); player.play() }
                    eventually { onPlayer { player.playbackState == Player.STATE_ENDED || player.playerError != null } }
                    main { assertNull("Profile $name: ${player.playerError}", player.playerError); assertEquals(Player.STATE_ENDED, player.playbackState) }
                    assertReference(expected, transports.single())
                }
            } finally { file.delete() }
        }
    }
    @Test fun fullTwentyFourBit96And192TracksMatchIndependentReference() {
        for (expected in manifest().filter { it.optBoolean("library") }) {
            val track = tracks().single { it.sampleRate == expected.getInt("rate") }
            withPlayer { player, transports ->
                main { player.setMediaItem(track.toMediaItem()); player.prepare(); player.play() }
                eventually(30_000) { onPlayer { player.playbackState == Player.STATE_ENDED || player.playerError != null } }
                main { assertNull(player.playerError); assertEquals(Player.STATE_ENDED, player.playbackState) }
                assertReference(expected, transports.single())
            }
        }
    }

    @Test fun automaticEndOfTrackStartsTheNextUsbTrackWithCorrectPosition() {
        val track = tracks().single { it.bits == 16 }
        withPlayer { player, transports ->
            val next = tracks().single { it.sampleRate == 192_000 }
            main {
                player.setMediaItems(listOf(track.toMediaItem(), next.toMediaItem().buildUpon().setMediaId("automatic-next").build()))
                player.prepare(); player.play()
            }
            eventually { onPlayer { player.currentPosition > 100 && player.isPlaying } }
            main { player.seekTo(18_800) }
            eventually { onPlayer { player.currentMediaItem?.mediaId == "automatic-next" && player.currentPosition > 300 && player.isPlaying } }
            main { assertNull(player.playerError); assertTrue(player.currentPosition < 5_000) }
            assertTrue(transports.first().closed)
            assertEquals(UsbPcmProfile(192_000, 24), transports.last().profile)
        }
    }

    @Test fun sourceMetadataPreventsSilentTwentyFourToSixteenBitConversion() {
        val track = tracks().single { it.bits == 16 }.copy(bits = 24)
        withPlayer { player, transports ->
            main { player.setMediaItem(track.toMediaItem()); player.prepare(); player.play() }
            eventually { onPlayer { player.playerError != null } }
            assertTrue(transports.isEmpty())
        }
    }

    @Test fun startupVerifierErrorBecomesAReportedInitializationError() {
        val log = java.io.File(context.filesDir, "last-usb-linkage.txt")
        val previous = if (log.exists()) log.readBytes() else null
        try {
            main {
                DirectUsbSettings.setEnabled(true)
                val outputs = NoirAudioOutputProvider(context) { throw VerifyError("injected-startup-verifier-regression") }
                try {
                    val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).setSampleRate(44_100).setChannelCount(2).build()
                    val config = outputs.getOutputConfig(androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig.Builder(format).build())
                    val error = assertThrows(androidx.media3.exoplayer.audio.AudioOutputProvider.InitializationException::class.java) { outputs.getAudioOutput(config) }
                    assertTrue(error.cause is VerifyError)
                    assertTrue(DirectUsbSettings.failure.value.orEmpty().contains("VerifyError"))
                    assertTrue(CrashReports.report().contains("injected-startup-verifier-regression"))
                } finally { outputs.release(); DirectUsbSettings.setEnabled(false); DirectUsbSettings.failure.value = null }
            }
        } finally { if (previous == null) log.delete() else log.writeBytes(previous) }
    }
}
