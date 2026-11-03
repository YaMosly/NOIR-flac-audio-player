package dev.noir.player

import android.content.ComponentName
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueAndPreferencesTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val library get()=(context.applicationContext as NoirApp).library
    private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun eventually(condition:()->Boolean) {
        val end=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<end) { var result=false;main{result=condition()};if(result)return;Thread.sleep(50) }
        fail("Queue condition not reached")
    }
    @Test fun manualQueueSurvivesPreferenceRefreshTransitionMoveRemoveAndPlayNext() {
        val tracks=runBlocking { withTimeout(10000) {library.tracks.first {it.size==3}} }
        lateinit var controller:MediaController
        lateinit var future:com.google.common.util.concurrent.ListenableFuture<MediaController>
        main { DirectUsbSettings.setEnabled(false);future=MediaController.Builder(context,SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync() }
        controller=future.get(10,TimeUnit.SECONDS)
        val original=library.preferences.value.lessTrack(tracks[0].id)
        try {
            main {controller.setMediaItems(tracks.map {it.toMediaItem()});controller.prepare()}
            eventually {controller.mediaItemCount>=3 && controller.getMediaItemAt(1).mediaId==tracks[1].id && controller.getMediaItemAt(2).mediaId==tracks[2].id}
            main { controller.moveMediaItem(2,1) }
            eventually {controller.getMediaItemAt(1).mediaId==tracks[2].id}
            main {library.setLessTrack(tracks[0].id,!original)}
            Thread.sleep(300)
            main {assertEquals(tracks[2].id,controller.getMediaItemAt(1).mediaId);assertEquals(tracks[1].id,controller.getMediaItemAt(2).mediaId)}
            eventually { library.savedQueue().take(3).map { it.id } == listOf(tracks[0].id,tracks[2].id,tracks[1].id) }
            assertEquals(listOf(tracks[0].id,tracks[2].id,tracks[1].id),LibraryStore(context).savedQueue().take(3).map {it.id})
            main {controller.seekToNextMediaItem()}
            eventually {controller.currentMediaItem?.mediaId==tracks[2].id && controller.getMediaItemAt(controller.currentMediaItemIndex+1).mediaId==tracks[1].id}
            var count=0
            main {count=controller.mediaItemCount;controller.removeMediaItem(controller.currentMediaItemIndex+1)}
            eventually {controller.mediaItemCount==count-1}
            main {controller.addMediaItem(controller.currentMediaItemIndex+1,tracks[0].toMediaItem())}
            eventually {controller.getMediaItemAt(controller.currentMediaItemIndex+1).mediaId==tracks[0].id}
        } finally {main {controller.pause();controller.stop();controller.release();library.setLessTrack(tracks[0].id,original)}}
    }
    @Test fun explicitChoicesPersistAndCanBeUndoneWithoutChangingAutomaticScores() {
        val id="synthetic-preference-test";val artist="Synthetic Preference Artist"
        val before=library.preferences.value
        val scores=library.scores.toMap()
        try {
            main {library.setLessTrack(id,true);library.setLessArtist(artist,true)}
            val recreated=LibraryStore(context)
            assertTrue(recreated.preferences.value.lessTrack(id));assertTrue(recreated.preferences.value.lessArtist(artist.uppercase()))
            main {library.setLessTrack(id,false);library.setLessArtist(artist,false)}
            assertFalse(LibraryStore(context).preferences.value.lessTrack(id))
            assertFalse(LibraryStore(context).preferences.value.lessArtist(artist))
            assertEquals(scores,library.scores)
        } finally {main {library.setLessTrack(id,id in before.tracks);library.setLessArtist(artist,before.lessArtist(artist))}}
    }
}
