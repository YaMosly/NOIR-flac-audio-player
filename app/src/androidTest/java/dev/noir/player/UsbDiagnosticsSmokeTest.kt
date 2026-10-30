package dev.noir.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsbDiagnosticsSmokeTest {
    @Test fun noDeviceDoesNotStartPermissionRequestOrCrash() {
        val test = InstrumentationRegistry.getInstrumentation()
        val context = test.targetContext
        test.runOnMainSync {
            val manager = context.getSystemService(android.hardware.usb.UsbManager::class.java)
            assertTrue("Run on the isolated emulator without USB passthrough", manager.deviceList.isEmpty())
            val probe = UsbDiagnostics(context)
            try {
                probe.start()
                probe.start() // Registering twice must be harmless.
                probe.request()
                assertFalse(probe.state.value.busy)
                assertTrue(probe.state.value.message.contains("не видит аудиоустройство"))
                assertNull(probe.state.value.report)
                probe.request(exclusive = true)
                assertFalse(probe.state.value.busy)
                assertFalse(UsbPlaybackInterlock.active.value)
            } finally {
                probe.close()
                probe.close()
            }
        }
    }

    @Test fun playbackInterlockStopsOnceDoesNotUnlockForAnotherOwnerOrAutoResume() {
        val test = InstrumentationRegistry.getInstrumentation()
        test.runOnMainSync {
            val token = Any(); val other = Any()
            var stopped = 0
            val previousStop = UsbPlaybackInterlock.stopPlayback
            UsbPlaybackInterlock.stopPlayback = { stopped++ }
            try {
                assertTrue(UsbPlaybackInterlock.acquire(token))
                assertEquals(1, stopped)
                assertFalse(UsbPlaybackInterlock.acquire(other))
                UsbPlaybackInterlock.release(other)
                assertTrue(UsbPlaybackInterlock.active.value)
                UsbPlaybackInterlock.release(token)
                assertFalse(UsbPlaybackInterlock.active.value)
                assertEquals(1, stopped)
            } finally { UsbPlaybackInterlock.release(token); UsbPlaybackInterlock.stopPlayback = previousStop }
        }
    }
}
