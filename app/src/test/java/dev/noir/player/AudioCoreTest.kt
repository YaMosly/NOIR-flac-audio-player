package dev.noir.player

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class AudioCoreTest {
    @Test fun streamInfoPreserves24Bit96kHzAndTags() {
        val info = FlacMetadata.read(ByteArrayInputStream(fixture(96_000, 24, 2, 960_000)))
        assertEquals(96_000, info.rate); assertEquals(24, info.bits); assertEquals(2, info.channels)
        assertEquals(10_000L, info.durationMs); assertEquals("Тестовый трек", info.tags["TITLE"])
        assertEquals("Crystal Castles", info.tags["ARTIST"])
    }
    @Test fun sourceFormatsAreNotAssumedToBeCD() {
        for ((rate, bits) in listOf(44_100 to 16, 192_000 to 24, 384_000 to 32)) {
            val info = FlacMetadata.read(ByteArrayInputStream(fixture(rate, bits, 1, rate.toLong() * 9)))
            assertEquals(rate, info.rate); assertEquals(bits, info.bits); assertEquals(1, info.channels); assertEquals(9000L, info.durationMs)
        }
    }
    @Test fun corruptAndTruncatedFilesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { FlacMetadata.read(ByteArrayInputStream(ByteArray(80))) }
        val bytes = fixture(44_100, 16, 2, 44_100)
        assertThrows(Exception::class.java) { FlacMetadata.read(ByteArrayInputStream(bytes.copyOf(18))) }
        val badComments = bytes.copyOf()
        // Vendor length at offset 46 must never escape the bounded metadata block.
        for (i in 46..49) badComments[i] = 0x7f
        assertThrows(IllegalArgumentException::class.java) { FlacMetadata.read(ByteArrayInputStream(badComments)) }
    }
    @Test fun skipsIgnoreAccidentalTapsAndLateTransitions() {
        assertFalse(SmartShuffle.isEarlySkip(100, 200_000))
        assertTrue(SmartShuffle.isEarlySkip(3_000, 200_000))
        assertFalse(SmartShuffle.isEarlySkip(80_000, 200_000))
        assertFalse(SmartShuffle.isEarlySkip(4_000, 0))
        assertFalse(SmartShuffle.isEarlySkip(5_000, 10_000))
    }
    @Test fun recentTracksAreAvoidedAndTinyLibrariesRemainPlayable() {
        val a = track("a"); val b = track("b")
        repeat(100) { assertEquals("b", choose(listOf(a, b), listOf("a"))?.id) }
        assertEquals(a, choose(listOf(a), listOf("a")))
        assertNull(choose(emptyList(), emptyList()))
    }
    @Test fun repeatedArtistSkipsReduceFrequencyWithoutBlockingArtist() {
        val tracks = listOf(track("a", "Crystal Castles"), track("b", "Other"))
        val rng = Random(14)
        val now = 1_800_000_000_000L
        val scores = mapOf("crystal castles" to ListeningScore(10.0, 0.0, now))
        val picks = (0 until 6000).count { SmartShuffle.choose(tracks, emptyList(), emptyMap(), scores, true, now, rng)?.id == "a" }
        assertTrue("Unliked artist still has a nonzero chance: $picks", picks in 300..1400)
        val normalRng = Random(14)
        val normal = (0 until 6000).count { SmartShuffle.choose(tracks, emptyList(), emptyMap(), scores, false, now, normalRng)?.id == "a" }
        assertTrue(normal in 2700..3300)
    }
    @Test fun oneArtistSkipDoesNotPenalizeTheirWholeCatalogue() {
        val now = 1_800_000_000_000L
        val tracks = listOf(track("a", "Artist"), track("b", "Other"))
        val scores = mapOf("artist" to ListeningScore(1.0, 0.0, now))
        val with = Random(4); val without = Random(4)
        repeat(300) { assertEquals(SmartShuffle.choose(tracks, emptyList(), emptyMap(), emptyMap(), true, now, without), SmartShuffle.choose(tracks, emptyList(), emptyMap(), scores, true, now, with)) }
    }
    @Test fun dislikeDecaysOverTime() {
        val now = 1_800_000_000_000L
        val score = ListeningScore(10.0, 0.0, now)
        assertTrue(score.weight(now + 120L * 86_400_000) > score.weight(now) * 4)
        assertTrue(score.weight(now + 365L * 86_400_000) > .99)
    }
    private fun track(id: String, artist: String = "Artist") = Track(id, id, artist)
    private fun choose(tracks: List<Track>, recent: List<String>) = SmartShuffle.choose(tracks, recent, emptyMap(), emptyMap(), true, 1000, Random(3))
    private fun fixture(rate: Int, bits: Int, channels: Int, samples: Long): ByteArray {
        val info = ByteArray(34)
        ByteBuffer.wrap(info, 10, 8).putLong((rate.toLong() shl 44) or ((channels - 1L) shl 41) or ((bits - 1L) shl 36) or samples)
        val comments = ByteArrayOutputStream()
        fun little(value: Int) { comments.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        val vendor = "NOIR test".toByteArray(); little(vendor.size); comments.write(vendor)
        val tags = listOf("TITLE=Тестовый трек", "ARTIST=Crystal Castles")
        little(tags.size); tags.forEach { val bytes = it.toByteArray(); little(bytes.size); comments.write(bytes) }
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(0x664C6143); out.writeByte(0); out.write(byteArrayOf(0, 0, 34)); out.write(info)
            out.writeByte(0x84); val size = comments.size(); out.writeByte(size ushr 16); out.writeByte(size ushr 8); out.writeByte(size); out.write(comments.toByteArray())
        } }.toByteArray()
    }
}
