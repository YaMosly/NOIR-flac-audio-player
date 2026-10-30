package dev.noir.player

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class UsbInterfaceLeaseTest {
    @Test fun successReleasesInReverseOrder() {
        val events = mutableListOf<String>()
        UsbInterfaceLease({ events += "claim $it"; true }, { events += "release $it"; true }, {}).use {
            it.claim(listOf(0, 2)); events += "read"
        }
        assertEquals(listOf("claim 0", "claim 2", "read", "release 2", "release 0"), events)
    }
    @Test fun failedAcquisitionStillAttemptsRecoveryOfBothInterfaces() {
        val events = mutableListOf<String>()
        val notes = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            UsbInterfaceLease({ events += "claim $it"; it == 0 }, { events += "release $it"; it == 0 }, notes::add).use { it.claim(listOf(0, 2)) }
        }
        assertEquals(listOf("claim 0", "claim 2", "release 2", "release 0"), events)
        assertTrue(notes.any { it.contains("Переподключи") })
    }
    @Test fun cancellationAndReadFailureCannotSkipCleanup() {
        for (failure in listOf(IllegalArgumentException("bad data"), CancellationException("cancelled"))) {
            var released = false
            assertThrows(failure.javaClass) {
                UsbInterfaceLease({ true }, { released = true; true }, {}).use { it.claim(listOf(0)); throw failure }
            }
            assertTrue(released)
        }
    }
    @Test fun releaseFailureDoesNotPreventOtherReleasesAndCloseIsIdempotent() {
        val releases = mutableListOf<Int>(); val notes = mutableListOf<String>()
        val lease = UsbInterfaceLease({ true }, { releases += it; if (it == 2) error("removed") else true }, notes::add)
        lease.claim(listOf(0, 2)); lease.close(); lease.close()
        assertEquals(listOf(2, 0), releases)
        assertTrue(notes.any { it.contains("release = false") })
    }
    @Test fun rejectsInvalidTargetsBeforeChangingOwnership() {
        var called = false
        for (ids in listOf(emptyList(), listOf(0, 0), listOf(-1), listOf(256), listOf(0, 1, 2, 3, 4))) {
            UsbInterfaceLease({ called = true; true }, { true }, {}).use { lease ->
                assertThrows(IllegalArgumentException::class.java) { lease.claim(ids) }
            }
        }
        assertFalse(called)
    }
}
