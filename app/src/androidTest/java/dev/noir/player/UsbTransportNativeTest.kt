package dev.noir.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** These exercise the real C++ queue, replacing only USB syscalls and monotonic time. */
@RunWith(AndroidJUnit4::class)
class UsbTransportNativeTest {
    init { System.loadLibrary("noir_usb") }
    private external fun scenario(mode: Int): String
    private external fun liveScenario(mode: Int): String
    private external fun hiResScenario(rate: Int, frameBytes: Int): String
    private external fun backgroundScenario(): String
    private external fun waitForNativeStop(handle: Long): Long
    @Test fun deepQueueAndPcmReserveSurviveSchedulingAndDecoderStalls() {
        val result = backgroundScenario()
        assertTrue(result, result.contains("Queue: 1024 x 1 packets; planned 128 ms"))
        assertTrue(result.contains("no modeled USB schedule gaps"))
    }
    @Test fun nativeStopFlagWorksAcrossGcAndScreenOffWithoutJavaCallbacks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = java.io.File(context.cacheDir, "native-stop-test")
        val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_WRITE or android.os.ParcelFileDescriptor.MODE_CREATE)
        val handle = NativeUsb.create(descriptor.fd)
        val elapsed = java.util.concurrent.atomic.AtomicLong(-2)
        NativeUsb.setLiveStopped(handle, false)
        val worker = kotlin.concurrent.thread { elapsed.set(waitForNativeStop(handle)) }
        try {
            Thread.sleep(200)
            instrumentation.uiAutomation.executeShellCommand("input keyevent 223").close()
            repeat(12) { val pressure = ByteArray(512_000); pressure[0] = it.toByte(); System.gc() }
            NativeUsb.setLiveStopped(handle, true)
            worker.join(3000)
            assertFalse("Native stop did not interrupt the worker", worker.isAlive)
            assertTrue("Native loop did not actually wait: ${elapsed.get()}", elapsed.get() >= 100)
            assertTrue(elapsed.get() < 5000)
        } finally {
            NativeUsb.setLiveStopped(handle, true); worker.join(6000)
            instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
            descriptor.close(); NativeUsb.destroy(handle); file.delete()
        }
    }
    @Test fun everyHiResNativeProfilePreservesFramesAcrossPauseAndRingWrap() {
        for (rate in UsbPcmProfile.rates) for (bits in listOf(16, 24, 32)) {
            val report = hiResScenario(rate, bits / 4)
            assertTrue("$rate/$bits: $report", report.contains("Native USB: complete"))
            assertTrue(report.contains("stereo S${bits}LE / $rate Hz"))
        }
    }
    @Test fun packedTwentyFourBitJniUsesSixByteFramesAndDynamicCapacity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "hires-jni-test")
        val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_WRITE or android.os.ParcelFileDescriptor.MODE_CREATE)
        val handle = NativeUsb.create(descriptor.fd)
        try {
            NativeUsb.configureLive(handle, 192_000, 6)
            val capacity = 192_000 * 6 * UsbPcmProfile.BUFFER_SECONDS
            val buffer = java.nio.ByteBuffer.allocateDirect(capacity + 6)
            assertEquals(capacity, NativeUsb.feedLive(handle, buffer, 0, capacity + 6))
            assertEquals(capacity.toLong(), NativeUsb.liveBuffered(handle))
            assertThrows(IllegalStateException::class.java) { NativeUsb.feedLive(handle, buffer, 0, 4) }
            assertThrows(IllegalStateException::class.java) { NativeUsb.configureLive(handle, 96_000, 6) }
            NativeUsb.resetLive(handle)
            NativeUsb.configureLive(handle, 384_000, 8)
            assertEquals(8, NativeUsb.feedLive(handle, buffer, 0, 8))
        } finally { descriptor.close(); NativeUsb.destroy(handle); file.delete() }
    }
    @Test fun liveJniChecksBufferBoundsAndEnforcesTwoSecondCapacity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "live-jni-test")
        val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_WRITE or android.os.ParcelFileDescriptor.MODE_CREATE)
        val handle = NativeUsb.create(descriptor.fd)
        try {
            val buffer = java.nio.ByteBuffer.allocateDirect(352_804)
            assertEquals(352_800, NativeUsb.feedLive(handle, buffer, 0, 352_804))
            assertEquals(0, NativeUsb.feedLive(handle, buffer, 0, 4))
            assertEquals(352_800L, NativeUsb.liveBuffered(handle))
            assertEquals(0L, NativeUsb.livePosition(handle))
            assertThrows(IllegalStateException::class.java) { NativeUsb.feedLive(handle, buffer, 352_800, 8) }
            assertThrows(IllegalStateException::class.java) { NativeUsb.feedLive(handle, buffer, 0, 3) }
            NativeUsb.finishLive(handle)
            assertThrows(IllegalStateException::class.java) { NativeUsb.feedLive(handle, buffer, 0, 4) }
            NativeUsb.resetLive(handle)
            assertEquals(0L, NativeUsb.liveBuffered(handle))
            assertEquals(4, NativeUsb.feedLive(handle, buffer, 0, 4))
        } finally { descriptor.close(); NativeUsb.destroy(handle); file.delete() }
    }
    @Test fun liveFortyFiveSecondsUsesBoundedRingAndPreservesAllBytes() { assertTrue(liveScenario(0).contains("Native USB: complete")) }
    @Test fun livePauseAndResumeReplaysOnlyUncompletedFrames() { assertTrue(liveScenario(1).contains("Native USB: complete")) }
    @Test fun liveStarvationStopsInsteadOfAddingSilence() { assertTrue(liveScenario(2).contains("PCM underrun")) }
    @Test fun liveFlushResetsRingAndPosition() { assertTrue(liveScenario(3).contains("Native USB: complete")) }
    @Test fun completeFifteenSecondsWithoutSkippingOrRepeatingPcm() { assertTrue(scenario(0).contains("Native USB: complete")) }
    @Test fun stallAfterTheReported1784PacketsCancelsEveryTransfer() { assertTrue(scenario(1).contains("USB idle timeout")) }
    @Test fun slowProgressReachesOverallDeadlineWithDifferentDiagnostic() { assertTrue(scenario(2).contains("USB overall deadline")) }
    @Test fun manualStopCancelsAndDrainsWithoutFailure() { assertTrue(scenario(3).contains("Native USB: stopped")) }
    @Test fun packetErrorRejectsStreamAndDrainsRemainingQueue() { assertTrue(scenario(4).contains("packet errors: 1")) }
    @Test fun disconnectIsReportedImmediately() { assertTrue(scenario(5).contains("terminal events")) }
    @Test fun interruptedWaitCanContinueToCompletion() { assertTrue(scenario(6).contains("Native USB: complete")) }
    @Test fun submitFailureCleansUpEarlierRequests() { assertTrue(scenario(7).contains("SUBMITURB errno=12")) }
    @Test fun queueTolerates12msWorkerDelayWithoutChangingPayload() { assertTrue(scenario(8).contains("Native USB: complete")) }
    @Test fun readyCompletionsAfterLongWorkerPauseAreNotMistakenForIdleUsb() { assertTrue(scenario(9).contains("Native USB: complete")) }
    @Test fun stopBeforeQueueStartupSubmitsNoAudio() { assertTrue(scenario(10).contains("URB submitted: 0")) }
    @Test fun finalPartialPacketPreservesEveryRemainingFrame() { assertTrue(scenario(11).contains("2645988 / 2645988")) }
    @Test fun audioWorkerPriorityRestoresAfterSuccessAndException() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withContext(Dispatchers.IO) {
            val tid = android.os.Process.myTid()
            val previous = android.os.Process.getThreadPriority(tid)
            val result = UsbAudioResources.run(context) { assertTrue(it.contains("CPU wake lock=true")); 42 }
            assertEquals(42, result)
            assertEquals(previous, android.os.Process.getThreadPriority(tid))
            assertThrows(IllegalArgumentException::class.java) { UsbAudioResources.run(context) { throw IllegalArgumentException("injected failure") } }
            assertEquals(previous, android.os.Process.getThreadPriority(tid))
        }
    }
}
