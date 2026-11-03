package dev.noir.player

import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DailyPlaybackIntegrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val library get()=(context.applicationContext as NoirApp).library
    private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
    private fun waitFor(condition:()->Boolean) {
        val until=System.currentTimeMillis()+12000
        while(System.currentTimeMillis()<until) {var ready=false;main{ready=condition()};if(ready)return;Thread.sleep(50)}
        fail("Expected daily playback state was not reached")
    }
    @Test fun dacLevelsAndHistoryPersistIndependently() {
        val enabled=DirectUsbSettings.enabled.value
        val old=library.history.toList()
        try {
            main {
                DirectUsbSettings.bindDevice(65000,1,"fixture-a","fixture-a");DirectUsbSettings.setDb(-47)
                DirectUsbSettings.bindDevice(65000,1,"fixture-b","fixture-b");DirectUsbSettings.setDb(-42)
                DirectUsbSettings.bindDevice(65000,1,"fixture-a","fixture-a");assertEquals(-47,DirectUsbSettings.db.value)
                library.rememberPlayed("history-a");library.rememberPlayed("history-b")
            }
            val recreated=LibraryStore(context)
            try {assertEquals(listOf("history-a","history-b"),recreated.history.takeLast(2))}finally{main{recreated.close()}}
            main {
                DirectUsbSettings.initialize(context)
                DirectUsbSettings.bindDevice(65000,1,"fixture-b","fixture-b");assertEquals(-42,DirectUsbSettings.db.value)
                DirectUsbSettings.bindDevice(65000,2,null,"fixture-new");assertEquals(-35,DirectUsbSettings.db.value)
            }
        } finally {main{
            library.history.clear();library.history.addAll(old)
            context.getSharedPreferences("noir",0).edit().putString("history",org.json.JSONArray(old).toString()).apply()
            DirectUsbSettings.initialize(context);DirectUsbSettings.setEnabled(enabled)
        }}
    }
    @Test fun widgetLessActionTargetsDisplayedTrackAndCanUndo() {
        val tracks=runBlocking {withTimeout(10000){library.tracks.first{it.size==3}}}
        val track=tracks[1];val before=library.preferences.value.lessTrack(track.id)
        try {
            main {library.setLessTrack(track.id,false)}
            fun send(reduce:Boolean)=context.sendBroadcast(Intent(context,NoirWidget::class.java).setAction("dev.noir.WIDGET_LESS").putExtra("track",track.id).putExtra("reduce",reduce))
            send(true);waitFor{library.preferences.value.lessTrack(track.id)}
            send(false);waitFor{!library.preferences.value.lessTrack(track.id)}
        } finally {main{library.setLessTrack(track.id,before)}}
    }
    @Test fun safRefreshReusesMetadataFindsChangesAndRetainsCacheOnPartialFailure() {
        val tracks=runBlocking {withTimeout(10000){library.tracks.first{it.size==3}}}
        val cache=File(context.filesDir,"library.json");val oldCache=cache.readBytes()
        val prefs=context.getSharedPreferences("noir",0)
        val oldTree=prefs.getString("tree",null);val oldName=prefs.getString("folder_name",null)
        val folder=File(context.filesDir,"scan-fixture").apply{mkdirs()}
        var store:LibraryStore?=null
        try {
            folder.listFiles().orEmpty().forEach{it.delete()}
            tracks.take(2).forEachIndexed {index,track -> context.contentResolver.openInputStream(android.net.Uri.parse(track.id))!!.use{input->File(folder,"$index.flac").outputStream().use{output->input.copyTo(output)}} }
            main {store=LibraryStore(context)}
            val target=store!!
            runBlocking {withTimeout(10000){target.tracks.first{it.isNotEmpty()}}}
            val uri=DocumentsContract.buildTreeDocumentUri(ScanFixtureProvider.AUTHORITY,"root")
            fun scan() {main{target.scanFolder(uri)};waitFor{!target.scan.value.scanning && target.tree==uri}}
            ScanFixtureProvider.reads.set(0)
            scan();assertEquals(2,target.tracks.value.size);assertEquals(2,ScanFixtureProvider.reads.get())
            scan();assertEquals("Unchanged metadata was decoded again",2,ScanFixtureProvider.reads.get())
            File(folder,"1.flac").delete();File(folder,"0.flac").copyTo(File(folder,"2.flac"))
            scan();assertEquals(2,target.tracks.value.size);assertTrue(target.tracks.value.any{it.id.endsWith("2.flac")});assertFalse(target.tracks.value.any{it.id.endsWith("1.flac")})
            assertEquals(3,ScanFixtureProvider.reads.get())
            val snapshot=target.tracks.value
            ScanFixtureProvider.incomplete=true
            main{target.scanFolder(uri)};waitFor{!target.scan.value.scanning && target.scan.value.message!=null}
            assertEquals(snapshot,target.tracks.value)
            ScanFixtureProvider.incomplete=false
            main {target.setForeground(true)}
            waitFor{!target.scan.value.scanning}
            Thread.sleep(300)
            File(folder,"2.flac").delete()
            context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(ScanFixtureProvider.AUTHORITY,"root"),null)
            waitFor{target.tracks.value.size==1}
        } finally {
            ScanFixtureProvider.incomplete=false
            main{store?.close()}
            cache.writeBytes(oldCache)
            prefs.edit().putString("tree",oldTree).putString("folder_name",oldName).commit()
            folder.listFiles().orEmpty().forEach{it.delete()}
        }
    }
    @Test fun usbLossReleaseRetainsQueueAndPositionUntilExplicitPlay() {
        val tracks=runBlocking {withTimeout(10000){library.tracks.first{it.size==3}}}
        lateinit var player:androidx.media3.exoplayer.ExoPlayer
        main {player=androidx.media3.exoplayer.ExoPlayer.Builder(context).build()}
        try {
            main {player.setMediaItems(tracks.map{it.toMediaItem()});player.prepare();player.seekTo(1,4321)}
            waitFor{player.playbackState==androidx.media3.common.Player.STATE_READY}
            main {pauseForUsbLoss(player);assertFalse(player.playWhenReady);assertEquals(3,player.mediaItemCount);assertEquals(1,player.currentMediaItemIndex);assertEquals(4321L,player.currentPosition);player.prepare()}
            waitFor{player.playbackState==androidx.media3.common.Player.STATE_READY}
            main {assertFalse(player.isPlaying);assertEquals(4321L,player.currentPosition);player.play()}
            waitFor{player.isPlaying && player.currentPosition>4400}
        } finally {main{player.release()}}
    }
}
