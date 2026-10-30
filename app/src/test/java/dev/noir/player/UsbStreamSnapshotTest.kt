package dev.noir.player

import org.junit.Assert.*
import org.junit.Test

class UsbStreamSnapshotTest {
    @Test fun queriesOnlyInAndUsesStandardInterfaceEndpointAndClockValiditySelectors() {
        val calls = mutableListOf<List<Int>>()
        val state = UsbStreamSnapshot.capture { type, request, value, index, length ->
            calls += listOf(type, request, value, index, length)
            assertTrue(type and 0x80 != 0)
            when (type) { 0x81 -> byteArrayOf(1); 0x82 -> byteArrayOf(0, 0); else -> byteArrayOf(1) }
        }
        assertEquals(listOf(listOf(0x81, 0x0a, 0, 1, 1), listOf(0x82, 0, 0, 4, 2), listOf(0xa1, 1, 0x200, 0x600, 1)), calls)
        assertEquals(UsbStreamSnapshot(1, 0, 1), state)
        assertTrue(state.report("before").contains("valid=true"))
    }
    @Test fun detectsInactiveAlternateAndInvalidClockWithoutTreatingThemAsUnavailable() {
        val state = UsbStreamSnapshot.capture { type, _, _, _, _ -> if (type == 0x82) byteArrayOf(1, 0) else byteArrayOf(0) }
        assertEquals(UsbStreamSnapshot(0, 1, 0), state)
        assertTrue(state.report("after").contains("GET_INTERFACE=0"))
        assertTrue(state.report("after").contains("valid=false"))
    }
    @Test fun shortUnsupportedAndFailedReadsRemainUnknownAndDoNotSkipLaterQueries() {
        var calls = 0
        val state = UsbStreamSnapshot.capture { type, _, _, _, _ ->
            calls++
            when (type) { 0x81 -> byteArrayOf(); 0x82 -> throw IllegalStateException("read failed"); else -> null }
        }
        assertEquals(3, calls)
        assertEquals(UsbStreamSnapshot(null, null, null), state)
        assertTrue(state.report("after").contains("нет ответа"))
    }
    @Test fun rejectsWrongLengthAndPreservesUnknownValidityValue() {
        val state = UsbStreamSnapshot.capture { type, _, _, _, _ -> when (type) { 0x81 -> byteArrayOf(1, 0); 0x82 -> byteArrayOf(0); else -> byteArrayOf(2) } }
        assertNull(state.alternate); assertNull(state.endpointStatus)
        assertEquals(2, state.clockValid)
        assertTrue(state.report("after").contains("неизвестно (2)"))
    }
}
