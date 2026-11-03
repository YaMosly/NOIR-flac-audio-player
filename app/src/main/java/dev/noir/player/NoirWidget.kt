package dev.noir.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import androidx.media3.common.MediaMetadata

class NoirWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) = update(context)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != LESS) return
        val id = intent.getStringExtra("track") ?: return
        val reduce = intent.getBooleanExtra("reduce", true)
        val pending = goAsync()
        scope.launch {
            try {
                val library = (context.applicationContext as NoirApp).library
                val tracks = withTimeoutOrNull(5_000) { library.tracks.first { it.isNotEmpty() } }.orEmpty()
                if (tracks.any { it.id == id }) {
                    library.setLessTrack(id, reduce)
                    update(context)
                }
            } finally { pending.finish() }
        }
    }
    companion object {
        private const val LESS = "dev.noir.WIDGET_LESS"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var artworkJob: Job? = null
        private val artworkLock = kotlinx.coroutines.sync.Mutex()
        fun update(context: Context, metadata: MediaMetadata? = null, playing: Boolean? = null, trackId: String? = null) {
            val prefs = context.getSharedPreferences("widget", Context.MODE_PRIVATE)
            if (metadata != null) prefs.edit().putString("title", metadata.title?.toString()).putString("artist", metadata.artist?.toString()).apply()
            if (trackId != null) prefs.edit().putString("track", trackId).apply()
            if (playing != null) prefs.edit().putBoolean("playing", playing).apply()
            val id = trackId ?: prefs.getString("track", null)
            val isPlaying = playing ?: prefs.getBoolean("playing", false)
            val title = metadata?.title?.toString() ?: prefs.getString("title", null)
            val open = PendingIntent.getActivity(context, 10, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val views = RemoteViews(context.packageName, R.layout.player_widget).apply {
                setTextViewText(R.id.widget_title, title ?: "Выбери свою музыку")
                setTextViewText(R.id.widget_artist, metadata?.artist?.toString() ?: prefs.getString("artist", "Открыть NOIR"))
                setImageViewResource(R.id.widget_play, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                setOnClickPendingIntent(R.id.widget_root, open)
                listOf(R.id.widget_previous to PlaybackService.ACTION_PREVIOUS, R.id.widget_play to PlaybackService.ACTION_PLAY, R.id.widget_next to PlaybackService.ACTION_NEXT).forEach { (id, action) ->
                    val intent = Intent(context, PlaybackService::class.java).setAction(action)
                    setOnClickPendingIntent(id, if (title == null) open else PendingIntent.getForegroundService(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
            }
            val library = (context.applicationContext as NoirApp).library
            val reduced = id != null && library.preferences.value.lessTrack(id)
            views.setTextViewText(R.id.widget_less, if (reduced) "Отменить «реже»" else "Реже этот трек")
            if (id != null) {
                val action = Intent(context, NoirWidget::class.java).setAction(LESS)
                    .setData(Uri.parse("noir://widget/preference/${Uri.encode(id)}/$reduced"))
                    .putExtra("track", id).putExtra("reduce", !reduced)
                views.setOnClickPendingIntent(R.id.widget_less, PendingIntent.getBroadcast(context, 11, action, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else views.setOnClickPendingIntent(R.id.widget_less, open)
            val manager = AppWidgetManager.getInstance(context)
            artworkJob?.cancel()
            val component = ComponentName(context, NoirWidget::class.java)
            views.setImageViewResource(R.id.widget_cover, R.drawable.ic_noir)
            manager.updateAppWidget(component, views)
            artworkJob = scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    artworkLock.lock()
                    try {
                    val file = File(context.filesDir, "widget-cover.jpg")
                    if (metadata != null) {
                        val bytes = metadata.artworkData
                        if (bytes == null) file.delete() else runCatching { file.writeBytes(bytes) }
                    }
                    runCatching {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, options)
                        var sample = 1
                        while (options.outWidth / sample > 256 || options.outHeight / sample > 256) sample *= 2
                        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    }.getOrNull()
                    } finally { artworkLock.unlock() }
                }
                ensureActive()
                if (bitmap != null) { views.setImageViewBitmap(R.id.widget_cover, bitmap); manager.updateAppWidget(component, views) }
            }
        }
    }
}
