package dev.noir.player

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbAudioTest {
    @Test fun parsesUac2ClocksFormatsAndPerChannelVolume() {
        val parsed = UsbAudioDescriptors.parse(uac2())
        assertEquals(setOf(0x200), parsed.versions)
        assertEquals(UsbClock(2, 10, 3), parsed.clocks.single())
        assertEquals(listOf(UsbVolume(2, 0x200, 20, 0, true), UsbVolume(2, 0x200, 20, 1, false)), parsed.volumes)
        assertEquals(UsbStreamFormat(3, 1, 0x200, 2, 24, 3, emptyList()), parsed.formats.single())
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test fun uac1DiscreteRatesInclude44100WithoutGuessingFromAndroidList() {
        val parsed = UsbAudioDescriptors.parse(bytes(9, 4, 1, 1, 1, 1, 2, 0, 0) + bytes(14, 0x24, 2, 1, 2, 3, 24, 2, 0x44, 0xac, 0, 0x80, 0xbb, 0))
        assertEquals(listOf(44_100L, 48_000L), parsed.formats.single().rates)
        assertTrue(parsed.clocks.isEmpty())
    }

    @Test fun handlesContinuousRatesAndMalformedDescriptors() {
        val continuous = UsbAudioDescriptors.parse(bytes(9, 4, 1, 1, 1, 1, 2, 0, 0) + bytes(14, 0x24, 2, 1, 2, 2, 16, 0, 0x44, 0xac, 0, 0x00, 0xee, 2))
        assertEquals(44_100L to 192_000L, continuous.formats.single().continuous)
        assertTrue(UsbAudioDescriptors.parse(bytes(0, 4)).warnings.isNotEmpty())
        assertTrue(UsbAudioDescriptors.parse(bytes(20, 4, 0)).warnings.isNotEmpty())
        assertTrue(UsbAudioDescriptors.parse(bytes(1)).warnings.isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) { UsbAudioDescriptors.parse(ByteArray(300_000)) }
    }

    @Test fun uac1VolumeUsesTheCorrectGetRequestsAndSignedDecibels() {
        val description = UsbAudioDescriptors.parse(bytes(9, 4, 0, 0, 0, 1, 1, 0, 0) + bytes(9, 0x24, 6, 7, 1, 1, 2, 0, 0))
        assertEquals(listOf(UsbVolume(0, 0x100, 7, 0, true)), description.volumes)
        val calls = mutableListOf<Int>()
        val report = UsbReadProbe.report(description, UsbReadTransport { request, selector, entity, iface, channel, length ->
            calls += request
            assertEquals(2, selector); assertEquals(7, entity); assertEquals(0, iface); assertEquals(0, channel); assertEquals(2, length)
            when (request) { 0x81 -> shorts(-6 * 256); 0x82 -> shorts(-64 * 256); 0x83 -> shorts(0); 0x84 -> shorts(256); else -> null }
        })
        assertEquals(listOf(0x81, 0x82, 0x83, 0x84), calls)
        assertTrue(report.contains("CUR: -6.0 дБ")); assertTrue(report.contains("MAX: 0.0 дБ"))
    }

    @Test fun ignoresVendorInterfacesAndUnknownUac3Layouts() {
        val parsed = UsbAudioDescriptors.parse(bytes(9, 4, 0, 0, 0, 255, 1, 0x20, 0) + bytes(8, 0x24, 10, 9, 1, 3, 0, 0))
        assertTrue(parsed.clocks.isEmpty()); assertTrue(parsed.versions.isEmpty())
        val uac3 = UsbAudioDescriptors.parse(bytes(9, 4, 0, 0, 0, 1, 1, 0x30, 0) + bytes(8, 0x24, 10, 9, 1, 3, 0, 0))
        assertTrue(uac3.clocks.isEmpty()); assertEquals(setOf(0x300), uac3.versions)
    }

    @Test fun clockRangesUseLittleEndian32BitAndVolumeUsesSigned256ths() {
        val ranges = UsbControlValues.ranges(bytes(2, 0) + ints(44_100, 44_100, 0, 48_000, 384_000, 48_000), 4, false)
        assertTrue(ranges.any { it.contains(44_100) }); assertFalse(ranges[1].contains(44_100))
        assertTrue(ranges[1].contains(96_000)); assertFalse(ranges[1].contains(88_200))
        val volume = UsbControlValues.ranges(bytes(1, 0) + shorts(-96 * 256, 0, 128), 2, true).single()
        assertEquals(-24_576L, volume.min); assertEquals(128L, volume.step)
        assertEquals(-32768, UsbControlValues.signed16(bytes(0, 0x80)))
    }

    @Test fun truncatedOrInvalidControlRepliesAreNotAcceptedAsCapabilities() {
        assertThrows(IllegalArgumentException::class.java) { UsbControlValues.ranges(bytes(65, 0), 4, false) }
        assertThrows(IllegalArgumentException::class.java) { UsbControlValues.ranges(bytes(2, 0) + ints(48_000, 48_000, 0), 4, false) }
        assertThrows(IllegalArgumentException::class.java) { UsbControlValues.ranges(bytes(1, 0) + ints(96_000, 44_100, 0), 4, false) }
        val report = UsbReadProbe.report(UsbAudioDescriptors.parse(uac2()), UsbReadTransport { _, _, _, _, _, _ -> null })
        assertTrue(report.contains("44100 Гц в RANGE: неизвестно"))
        assertFalse(report.contains("44100 Гц в RANGE: false"))
    }

    @Test fun readProbeSeparatesDeclaredFormatsClockRangesAndVolume() {
        val calls = mutableListOf<List<Int>>()
        val report = UsbReadProbe.report(UsbAudioDescriptors.parse(uac2()), UsbReadTransport { request, selector, entity, iface, channel, length ->
            calls += listOf(request, selector, entity, iface, channel, length)
            if (entity == 10 && selector == 1) {
                if (request == 1) ints(48_000) else if (length == 2) bytes(2, 0) else bytes(2, 0) + ints(44_100, 44_100, 0, 48_000, 48_000, 0)
            } else if (entity == 20 && selector == 2) {
                if (request == 1) shorts(-12 * 256) else if (length == 2) bytes(1, 0) else bytes(1, 0) + shorts(-96 * 256, 0, 128)
            } else null
        })
        assertTrue(report.contains("CUR: 48000 Гц")); assertTrue(report.contains("44100 Гц в RANGE: true"))
        assertTrue(report.contains("CUR: -12.0 дБ")); assertTrue(report.contains("шаг 0.5"))
        assertTrue(calls.all { it[0] in 1..2 && it[3] == 2 })
        assertTrue(report.contains("не подтверждает активный путь или bit-perfect"))
    }

    private fun uac2() = bytes(9, 4, 2, 0, 0, 1, 1, 0x20, 0) +
        bytes(9, 0x24, 1, 0, 2, 1, 0, 0, 0) +
        bytes(8, 0x24, 0x0a, 10, 1, 3, 0, 0) +
        bytes(14, 0x24, 6, 20, 1, 0x0c, 0, 0, 0, 0x04, 0, 0, 0, 0) +
        bytes(9, 4, 3, 1, 1, 1, 2, 0x20, 0) +
        bytes(16, 0x24, 1, 1, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0) +
        bytes(6, 0x24, 2, 1, 3, 24)
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private fun ints(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putInt) }.array()
    private fun shorts(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putShort(it.toShort()) } }.array()
}
