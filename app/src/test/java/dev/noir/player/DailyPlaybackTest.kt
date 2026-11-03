package dev.noir.player

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class DailyPlaybackTest {
    @Test fun shuffleUsesMoreThanThirtyRecentTracksAndSpreadsArtists() {
        val tracks=(0..79).map { Track("$it","$it",if(it%2==0) "A" else "B") }
        val recent=(0..75).map {"$it"}
        repeat(100) {
            val selected=SmartShuffle.choose(tracks,recent,emptyMap(),emptyMap(),true,0,Random(it))!!
            assertTrue(selected.id.toInt()>=76)
        }
        val artistTracks=listOf(Track("a","a","A"),Track("b","b","A"),Track("c","c","B"))
        repeat(100) {assertEquals("c",SmartShuffle.choose(artistTracks,listOf("a"),emptyMap(),emptyMap(),true,0,Random(it))!!.id)}
        assertNotNull(SmartShuffle.choose(artistTracks.take(2),listOf("a","a","b"),emptyMap(),emptyMap(),true,0))
    }
    @Test fun albumSortsNumericallyAndSeparatesAlbumArtists() {
        val t=Track("a","Z","Guest","Album",albumArtist="Various",discNumber=2,trackNumber=1)
        val tracks=listOf(t,t.copy(id="b",title="B",discNumber=1,trackNumber=10),t.copy(id="c",title="C",discNumber=1,trackNumber=2),t.copy(id="d",albumArtist="Other"))
        assertEquals(listOf("c","b","a"),AlbumOrder.tracks(t,tracks).map{it.id})
        assertEquals(12,AlbumOrder.number("12/20"));assertEquals(0,AlbumOrder.number("bad"))
        assertTrue(AlbumOrder.tracks(t.copy(album=""),tracks).isEmpty())
    }
    @Test fun dacProfilesSeparateModelsAndSerialsWithoutStoringSerialText() {
        assertNotEquals(DacVolumeProfiles.key(1,2,null),DacVolumeProfiles.key(1,3,null))
        assertNotEquals(DacVolumeProfiles.key(1,2,"unit-a"),DacVolumeProfiles.key(1,2,"unit-b"))
        assertFalse(DacVolumeProfiles.key(1,2,"sensitive-unit").contains("sensitive-unit"))
        assertEquals(DacVolumeProfiles.key(1,2,null),DacVolumeProfiles.key(1,2,""))
    }
    @Test fun deletedCurrentItemRestoresTheNextSurvivingManualChoice() {
        val a=Track("a","a","A");val b=Track("b","b","B")
        val saved=listOf(SavedQueueEntry("deleted",false),SavedQueueEntry("b",false),SavedQueueEntry("a",false))
        assertEquals(b,restoredStart(listOf(a,b),saved,"deleted"))
        assertEquals(a,restoredStart(listOf(a,b),saved,"a"))
        assertEquals(a,restoredStart(listOf(a,b),emptyList(),"deleted"))
    }
}
