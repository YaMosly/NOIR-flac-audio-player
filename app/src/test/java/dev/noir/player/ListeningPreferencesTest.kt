package dev.noir.player

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ListeningPreferencesTest {
    @Test fun explicitReductionsAreIndependentReversibleAndNeverExcludeTracks() {
        val a = Track("a", "Char", "Crystal Castles")
        val b = Track("b", "Other", "Other")
        val prefs = ListeningPreferences(setOf("a"), setOf("crystal castles"))
        assertEquals(.04, prefs.weight(a), .00001)
        val rng = Random(13)
        val count = (0 until 10000).count {
            SmartShuffle.choose(listOf(a,b), emptyList(), emptyMap(), emptyMap(), true, 1000, rng, prefs)?.id == "a"
        }
        assertTrue("Reduced track must still be chosen: $count", count in 200..600)
        assertEquals(1.0, ListeningPreferences().weight(a), .0)
        assertEquals("crystal castles", ListeningPreferences.artistKey(" Crystal Castles "))
        assertEquals("", ListeningPreferences.artistKey(SmartShuffle.UNKNOWN_ARTIST))
    }
    @Test fun uniformShuffleIgnoresLearningAndExplicitReductions() {
        val tracks = listOf(Track("a","a","a"),Track("b","b","b"))
        val a=Random(3);val b=Random(3)
        repeat(100) { assertEquals(
            SmartShuffle.choose(tracks,emptyList(),emptyMap(),emptyMap(),false,1000,a),
            SmartShuffle.choose(tracks,emptyList(),emptyMap(),emptyMap(),false,1000,b,ListeningPreferences(setOf("a"))),
        ) }
    }
    @Test fun searchMatchesMultipleWordsAcrossTagsWithoutRequiringDiacritics() {
        val a=Track("a","Déjà vu","Björk","Live 2026")
        val b=Track("b","Other","Elsewhere","Studio")
        assertEquals(listOf(a),TrackSearch.filter(listOf(a,b),"  bjork deja live "))
        assertEquals(listOf(a,b),TrackSearch.filter(listOf(a,b)," "))
        assertTrue(TrackSearch.filter(listOf(a,b),"missing").isEmpty())
    }
}
