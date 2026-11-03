package dev.noir.player

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbPreviewControlsTest {
    @Test fun nonDefaultEntitiesAndMasterVolumeUseDeclaredStepsAndRestoreTheirOwnValues() {
        fun bytes(width: Int, vararg values: Int) = ByteBuffer.allocate(width * values.size).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { if (width == 2) putShort(it.toShort()) else putInt(it) } }.array()
        var rate = 48000; var volume = -256; val written = mutableListOf<Pair<Int,Int>>()
        val controls = UsbPreviewControls(
            read = { selector, entity, channel, request, length ->
                assertEquals(0,channel)
                if (entity == 19) {
                    assertEquals(1,selector)
                    if(request==1) bytes(4,rate) else (bytes(2,1)+bytes(4,44100,48000,3900)).copyOf(length)
                } else {
                    assertEquals(27,entity);assertEquals(2,selector)
                    if(request==1) bytes(2,volume) else (bytes(2,1)+bytes(2,-50*256,0,256)).copyOf(length)
                }
            },
            write = { _,entity,channel,data -> written += entity to channel; if(entity==19) rate=UsbControlValues.unsigned(data).toInt() else volume=UsbControlValues.signed16(data) },
            note = {}, clockId = 19, volumeUnit = 27, channels = listOf(0),
        )
        controls.prepare(-35)
        assertEquals(44100,rate);assertEquals(-35*256,volume)
        assertEquals(-36*256,controls.volumeValue(-35*256-1))
        controls.restore(true)
        assertEquals(48000,rate);assertEquals(-256,volume)
        assertEquals(listOf(27 to 0,19 to 0,19 to 0,27 to 0),written)
    }
    private class Hardware {
        var clock = 48_000
        val volume = mutableMapOf(1 to 0, 2 to -256)
        val writes = mutableListOf<Pair<Int, Int>>()
        val notes = mutableListOf<String>()
        var failWriteChannel: Int? = null
        var ignoreVolume = false
        var badRange = false
        fun bytes(width: Int, vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * width).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { if (width == 2) putShort(it.toShort()) else putInt(it) } }.array()
        fun controls() = UsbPreviewControls(
            read = { _, entity, channel, request, length ->
                if (request == 1) { if (entity == 6) bytes(4, clock) else bytes(2, volume.getValue(channel)) }
                else {
                    val ranges = if (entity == 6) bytes(2, 2) + bytes(4, 44_100, 44_100, 0, 48_000, 48_000, 0)
                    else bytes(2, 1) + bytes(2, if (badRange) -256 else -50 * 256, 0, 128)
                    ranges.copyOf(length)
                }
            },
            write = { _, entity, channel, data ->
                writes += entity to channel
                if (entity == 6) clock = UsbControlValues.unsigned(data).toInt()
                else if (!ignoreVolume) volume[channel] = UsbControlValues.signed16(data)
                if (failWriteChannel == channel && entity == 7) { failWriteChannel = null; error("SET completed but reply was lost") }
            }, note = notes::add,
        )
    }
    @Test fun selectedRateIsConfirmedAndUnsupportedRateMakesNoWrites() {
        val hardware = Hardware(); val controls = hardware.controls()
        controls.prepare(-35, 48_000)
        assertEquals(48_000, hardware.clock)
        controls.restore(true)
        val unsupported = Hardware()
        assertThrows(IllegalArgumentException::class.java) { unsupported.controls().prepare(-35, 192_000) }
        assertTrue(unsupported.writes.isEmpty())
    }
    @Test fun confirmsQuietBothChannelsAndClockBeforeStreamAndRestoresOriginals() {
        val hardware = Hardware(); val controls = hardware.controls()
        controls.prepare(-35)
        assertEquals(44_100, hardware.clock)
        assertEquals(mapOf(1 to -35 * 256, 2 to -35 * 256), hardware.volume)
        assertEquals(listOf(7 to 1, 7 to 2, 6 to 0), hardware.writes)
        controls.restore(true)
        assertEquals(48_000, hardware.clock)
        assertEquals(mapOf(1 to 0, 2 to -256), hardware.volume)
    }
    @Test fun failedSetStillRestoresTheChannelThatMayHaveChanged() {
        val hardware = Hardware().apply { failWriteChannel = 2 }; val controls = hardware.controls()
        assertThrows(IllegalStateException::class.java) { controls.prepare(-35) }
        controls.restore(true)
        assertEquals(mapOf(1 to 0, 2 to -256), hardware.volume)
        assertEquals(48_000, hardware.clock)
    }
    @Test fun unconfirmedAttenuationCannotContinueToClockOrStreaming() {
        val hardware = Hardware().apply { ignoreVolume = true }; val controls = hardware.controls()
        assertThrows(IllegalStateException::class.java) { controls.prepare(-35) }
        assertEquals(listOf(7 to 1), hardware.writes)
        assertEquals(48_000, hardware.clock)
        controls.restore(true)
    }
    @Test fun noWritesWhenRangeDoesNotSupportQuietTarget() {
        val hardware = Hardware().apply { badRange = true }
        assertThrows(IllegalArgumentException::class.java) { hardware.controls().prepare(-35) }
        assertTrue(hardware.writes.isEmpty())
    }
    @Test fun failedAltZeroLeavesAttenuationInsteadOfRaisingVolume() {
        val hardware = Hardware(); val controls = hardware.controls()
        controls.prepare(-35)
        controls.restore(false)
        assertEquals(mapOf(1 to -35 * 256, 2 to -35 * 256), hardware.volume)
        assertEquals(3, hardware.writes.size)
        assertTrue(hardware.notes.last().contains("alt 0"))
    }
    @Test fun oneRestoreFailureDoesNotPreventTheOtherChannel() {
        val hardware = Hardware(); val controls = hardware.controls()
        controls.prepare(-35)
        hardware.failWriteChannel = 2
        controls.restore(true)
        assertEquals(7 to 1, hardware.writes.last())
        assertTrue(hardware.notes.any { it.contains("ошибка") })
    }
    @Test fun refusesFullScaleBoostAndOutOfStepTargetsBeforeAnyIo() {
        for (db in listOf(0, 6, -50, -34, -14)) {
            val hardware = Hardware()
            assertThrows(IllegalArgumentException::class.java) { hardware.controls().prepare(db) }
            assertTrue(hardware.writes.isEmpty())
        }
    }
}
