package dev.noir.player

import android.content.ComponentName
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Run only on the test emulator, after granting its Music/NOIR-Test folder. */
@RunWith(AndroidJUnit4::class)
class PlaybackSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
    private fun eventually(timeout: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) { var passed = false; main { passed = condition() }; if (passed) return; Thread.sleep(80) }
        fail("Condition did not become true in ${timeout}ms")
    }

    @Test fun realFlacPlaybackSkipLearningAndScreenOff() {
        main { DirectUsbSettings.setEnabled(false); DirectUsbSettings.failure.value = null }
        val library = (context.applicationContext as NoirApp).library
        val tracks = runBlocking { withTimeout(10_000) { library.tracks.first { it.size == 3 } } }
        assertEquals(setOf(44_100, 96_000, 192_000), tracks.map { it.sampleRate }.toSet())
        assertEquals(setOf(16, 24), tracks.map { it.bits }.toSet())
        assertTrue(tracks.all { it.durationMs == 20_000L })
        context.contentResolver.openInputStream(android.net.Uri.parse(tracks.first().id))!!.use {
            assertNotNull("The prepared synthetic FLAC must contain its embedded cover", FlacMetadata.read(it, true).picture)
        }
        assertNotNull(runBlocking { library.cover(tracks.first()) })
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var future: ListenableFuture<MediaController>
        main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val controller = future.get(10, TimeUnit.SECONDS)
        try {
            for (track in tracks) {
                main { controller.setMediaItem(track.toMediaItem()); controller.prepare(); controller.play() }
                eventually { controller.currentMediaItem?.mediaId == track.id && controller.isPlaying && controller.currentPosition > 600 }
                main { assertNull(controller.playerError); assertEquals(Player.STATE_READY, controller.playbackState) }
            }
            val probeToken = Any()
            try {
                main { assertTrue(UsbPlaybackInterlock.acquire(probeToken)) }
                eventually { controller.playbackState == Player.STATE_IDLE && !controller.playWhenReady }
                main { controller.play() }
                eventually { !controller.playWhenReady }
            } finally { main { UsbPlaybackInterlock.release(probeToken) } }
            main { assertFalse(controller.playWhenReady); controller.prepare(); controller.play() }
            eventually { controller.isPlaying }
            val track = tracks.first()
            main { library.resetLearning(); controller.setMediaItem(track.toMediaItem()); controller.prepare(); controller.play() }
            eventually { controller.currentMediaItem?.mediaId == track.id && controller.currentPosition > 2_500 }
            main { controller.seekToNextMediaItem() }
            eventually { controller.currentMediaItem?.mediaId != track.id }
            main { assertTrue("An early manual skip must be recorded", (library.scores[track.id]?.skips ?: 0.0) > .9) }
            var finishingId: String? = null
            main { finishingId = controller.currentMediaItem?.mediaId; controller.seekTo(18_800); controller.play() }
            eventually { controller.currentMediaItem?.mediaId != finishingId }
            main { assertTrue("A finished track must be recorded", (library.scores[finishingId]?.finishes ?: 0.0) > .9) }
            // Leaving the activity and turning off the display must not stop the foreground player.
            shell("input keyevent 3")
            shell("input keyevent 223")
            var before = 0L; var beforeId: String? = null
            main { before = controller.currentPosition; beforeId = controller.currentMediaItem?.mediaId }
            Thread.sleep(3_000)
            main { assertTrue(controller.isPlaying); assertNull(controller.playerError); assertTrue(controller.currentPosition > before + 1_000 || controller.currentMediaItem?.mediaId != beforeId) }
            shell("input keyevent 224")
            main { controller.pause() }
            eventually { !controller.isPlaying }
            // Changing mode must replace an already-built mixed-format future queue.
            val usbTrack = tracks.single { it.bits == 16 }
            main { controller.setMediaItem(usbTrack.toMediaItem()); controller.prepare() }
            eventually { controller.currentMediaItem?.mediaId == usbTrack.id && controller.mediaItemCount > 1 }
            main { DirectUsbSettings.setEnabled(true) }
            eventually {
                controller.mediaItemCount > 1 && (controller.currentMediaItemIndex + 1 until controller.mediaItemCount).all { i -> tracks.any { it.id == controller.getMediaItemAt(i).mediaId && UsbPcmProfile.supports(it) } }
            }
            main { assertFalse(controller.playWhenReady); DirectUsbSettings.setEnabled(false); DirectUsbSettings.failure.value = null }
        } finally {
            shell("input keyevent 224")
            main { DirectUsbSettings.setEnabled(false); DirectUsbSettings.failure.value = null; controller.pause(); controller.release() }
            scenario.close()
        }
    }
}
