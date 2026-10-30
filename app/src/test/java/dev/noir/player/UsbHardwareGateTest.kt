package dev.noir.player

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class UsbHardwareGateTest {
    @Test fun concurrentOpensHaveOneOwnerAndOldCleanupCannotReleaseANewerOwner() {
        val start = CountDownLatch(1)
        val winners = CopyOnWriteArrayList<Any>()
        val workers = List(12) { thread { val token = Any(); start.await(); if (UsbHardwareGate.acquire(token)) winners.add(token) } }
        start.countDown(); workers.forEach { it.join() }
        val next = Any()
        try {
            assertEquals(1, winners.size)
            UsbHardwareGate.release(Any())
            assertFalse(UsbHardwareGate.acquire(next))
            UsbHardwareGate.release(winners.single())
            assertTrue(UsbHardwareGate.acquire(next))
            UsbHardwareGate.release(winners.single())
            assertFalse(UsbHardwareGate.acquire(Any()))
        } finally { winners.forEach(UsbHardwareGate::release); UsbHardwareGate.release(next) }
    }
}
