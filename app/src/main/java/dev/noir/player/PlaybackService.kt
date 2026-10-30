package dev.noir.player

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var outputs: NoirAudioOutputProvider
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val library get() = (application as NoirApp).library
    private val recent = ArrayDeque<String>()
    private var changingQueue = false
    private var usbWasPresent = false
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val powerEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { BackgroundAudioDiagnostics.record(context, intent.action.orEmpty()) }
    }
    private val detached = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) { player.pause(); if (DirectUsbSettings.enabled.value) player.stop() }
        }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (!DirectUsbSettings.enabled.value && usbWasPresent && removedDevices.any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }) {
                player.pause()
                usbWasPresent = false
            }
        }
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            usbWasPresent = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        }
    }

    override fun onCreate() {
        super.onCreate()
        BackgroundAudioDiagnostics.record(this, "Playback service created")
        ContextCompat.registerReceiver(this, powerEvents, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Float output avoids the default 16-bit conversion for high-resolution PCM.
        // It does not establish integer bit-perfect USB playback.
        outputs = NoirAudioOutputProvider(this)
        val renderers = NoirRenderersFactory(this, outputs)
        player = ExoPlayer.Builder(this, renderers).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(!DirectUsbSettings.enabled.value)
            setWakeMode(C.WAKE_MODE_LOCAL)
            volume = 1f
        }
        UsbPlaybackInterlock.stopPlayback = { player.pause(); player.stop() }
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
                return if (UsbPlaybackInterlock.active.value) SessionError.ERROR_INVALID_STATE else SessionResult.RESULT_SUCCESS
            }
            override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                scope.launch {
                    runCatching {
                        val tracks = withTimeout(5_000) { library.tracks.first { it.isNotEmpty() } }
                        val start = tracks.firstOrNull { it.id == library.restoreId } ?: tracks.first()
                        MediaSession.MediaItemsWithStartPosition(buildQueue(start, tracks), 0, library.restorePosition)
                    }.onSuccess { result.set(it) }.onFailure { result.setException(it) }
                }
                return result
            }
            override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                val resolved = mediaItems.mapNotNull { item -> library.tracks.value.firstOrNull { it.id == item.mediaId }?.toMediaItem() }
                return com.google.common.util.concurrent.Futures.immediateFuture(resolved)
            }
        }).build()
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && UsbPlaybackInterlock.active.value) { player.pause(); player.stop() }
                if (!playWhenReady && DirectUsbSettings.enabled.value && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    val position = player.currentPosition
                    player.stop(); player.seekTo(position)
                }
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (changingQueue || old.mediaItem?.mediaId == new.mediaItem?.mediaId) return
                val track = library.tracks.value.firstOrNull { it.id == old.mediaItem?.mediaId } ?: return
                // A seek inside a song and a library replacement never count as dislike.
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    library.record(track, reason == Player.DISCONTINUITY_REASON_SEEK && SmartShuffle.isEarlySkip(old.positionMs, track.durationMs), track.durationMs > 0 && old.positionMs >= track.durationMs * .7)
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaId?.let { recent.addLast(it); while (recent.size > 60) recent.removeFirst() }
                // Refresh future choices after recording feedback; past items remain available for Back.
                if (!changingQueue) refreshFuture()
                if (mediaItem?.mediaMetadata?.artworkData == null) scope.launch {
                    val track = library.tracks.value.firstOrNull { it.id == mediaItem?.mediaId } ?: return@launch
                    val cover = library.cover(track) ?: return@launch
                    val bytes = withContext(Dispatchers.IO) { java.io.ByteArrayOutputStream().also { cover.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray() }
                    if (player.currentMediaItem?.mediaId == track.id) {
                        val item = player.currentMediaItem ?: return@launch
                        player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build())
                    }
                }
                saveAndUpdateWidget()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                BackgroundAudioDiagnostics.record(this@PlaybackService, "isPlaying=$isPlaying")
                saveAndUpdateWidget()
            }
            override fun onPlayerError(error: PlaybackException) {
                // Do not silently skip files forever. Keep the failed item visible for diagnosis.
                saveAndUpdateWidget()
            }
        })
        audioManager.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        ContextCompat.registerReceiver(this, detached, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            combine(library.tracks, UsbPlaybackInterlock.active) { tracks, locked -> tracks to locked }.collect { (tracks, locked) ->
                if (locked) return@collect
                if (tracks.isEmpty()) {
                    if (player.mediaItemCount > 0) { player.pause(); player.clearMediaItems() }
                } else if (player.mediaItemCount == 0 || tracks.none { it.id == player.currentMediaItem?.mediaId }) {
                    player.pause()
                    val start = tracks.firstOrNull { it.id == library.restoreId } ?: tracks.first()
                    replaceQueue(start, tracks, library.restorePosition)
                } else refreshFuture()
            }
        }
        scope.launch { while (isActive) { delay(5_000); if (player.mediaItemCount > 0) library.savePosition(player.currentMediaItem?.mediaId, player.currentPosition) } }
        scope.launch {
            DirectUsbSettings.enabled.drop(1).collect {
                player.setHandleAudioBecomingNoisy(!it)
                val position = player.currentPosition
                DirectUsbSettings.failure.value = null
                player.pause(); player.stop(); refreshFuture(); player.seekTo(position); player.prepare()
                if (!it) DirectUsbSettings.status.value = "Android · прямой USB выключен"
            }
        }
        scope.launch { DirectUsbSettings.db.drop(1).collect { withContext(Dispatchers.IO) { outputs.refreshVolume() } } }
        scope.launch { DirectUsbSettings.failure.collect { if (it != null && DirectUsbSettings.enabled.value) { player.pause(); player.stop(); saveAndUpdateWidget() } } }
    }

    private fun buildQueue(first: Track, tracks: List<Track>): List<MediaItem> {
        val items = mutableListOf(first.toMediaItem())
        val avoid = recent.toMutableList().apply { add(first.id) }
        repeat(3) {
            SmartShuffle.choose(playable(tracks), avoid, library.scores, library.artistScores, library.smart.value, System.currentTimeMillis())?.let { items.add(it.toMediaItem()); avoid.add(it.id) }
        }
        return items
    }

    private fun replaceQueue(start: Track, tracks: List<Track>, position: Long = 0L) {
        changingQueue = true
        player.setMediaItems(buildQueue(start, tracks), 0, position.coerceIn(0, (start.durationMs - 1).coerceAtLeast(0)))
        player.prepare()
        changingQueue = false
        saveAndUpdateWidget()
    }

    private fun refreshFuture() {
        if (changingQueue || library.tracks.value.isEmpty() || player.currentMediaItem == null) return
        changingQueue = true
        val index = player.currentMediaItemIndex
        if (index + 1 < player.mediaItemCount) player.removeMediaItems(index + 1, player.mediaItemCount)
        val avoid = recent.toMutableList()
        repeat(3) {
            SmartShuffle.choose(playable(library.tracks.value), avoid, library.scores, library.artistScores, library.smart.value, System.currentTimeMillis())?.let { player.addMediaItem(it.toMediaItem()); avoid.add(it.id) }
        }
        if (player.currentMediaItemIndex > 50) player.removeMediaItems(0, player.currentMediaItemIndex - 20)
        changingQueue = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if (UsbPlaybackInterlock.active.value) { stopSelf(startId); return result }
        val command = intent?.action
        if (command in setOf(ACTION_PLAY, ACTION_NEXT, ACTION_PREVIOUS, ACTION_RANDOM)) scope.launch {
            val tracks = withTimeoutOrNull(5_000) { library.tracks.first { it.isNotEmpty() } }
            if (tracks == null) { stopSelf(startId); return@launch }
            if (player.mediaItemCount == 0) replaceQueue(tracks.first(), tracks)
            when (command) {
                ACTION_PLAY -> if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
                ACTION_NEXT -> { player.seekToNextMediaItem(); player.play() }
                ACTION_PREVIOUS -> { player.seekToPrevious(); player.play() }
                ACTION_RANDOM -> {
                    val track = SmartShuffle.choose(playable(tracks), recent.toList(), library.scores, library.artistScores, library.smart.value, System.currentTimeMillis()) ?: tracks.first()
                    replaceQueue(track, tracks); player.play()
                }
            }
        }
        return result
    }

    private fun saveAndUpdateWidget() {
        library.savePosition(player.currentMediaItem?.mediaId, player.currentPosition)
        NoirWidget.update(this, player.currentMediaItem?.mediaMetadata, player.isPlaying)
    }
    private fun playable(tracks: List<Track>) = if (DirectUsbSettings.enabled.value) tracks.filter { UsbPcmProfile.supports(it) } else tracks

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session
    override fun onDestroy() {
        UsbPlaybackInterlock.stopPlayback = null
        library.savePosition(player.currentMediaItem?.mediaId, player.currentPosition)
        NoirWidget.update(this, player.currentMediaItem?.mediaMetadata, false)
        audioManager.unregisterAudioDeviceCallback(devices)
        unregisterReceiver(detached)
        unregisterReceiver(powerEvents)
        scope.cancel(); session.release(); player.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "dev.noir.PLAY"
        const val ACTION_NEXT = "dev.noir.NEXT"
        const val ACTION_PREVIOUS = "dev.noir.PREVIOUS"
        const val ACTION_RANDOM = "dev.noir.RANDOM"
    }
}

fun Track.toMediaItem(): MediaItem = MediaItem.Builder().setMediaId(id).setUri(id)
    .setTag(this)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).setDurationMs(durationMs).build()).build()
