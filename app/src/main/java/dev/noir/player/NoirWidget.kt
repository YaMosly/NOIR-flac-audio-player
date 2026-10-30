package dev.noir.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.common.MediaMetadata

class NoirWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) = update(context)
    companion object {
        fun update(context: Context, metadata: MediaMetadata? = null, playing: Boolean = false) {
            val prefs = context.getSharedPreferences("widget", Context.MODE_PRIVATE)
            if (metadata != null) prefs.edit().putString("title", metadata.title?.toString()).putString("artist", metadata.artist?.toString()).apply()
            val title = metadata?.title?.toString() ?: prefs.getString("title", null)
            val open = PendingIntent.getActivity(context, 10, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val views = RemoteViews(context.packageName, R.layout.player_widget).apply {
                setTextViewText(R.id.widget_title, title ?: "Выбери свою музыку")
                setTextViewText(R.id.widget_artist, metadata?.artist?.toString() ?: prefs.getString("artist", "Открыть NOIR"))
                setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                setOnClickPendingIntent(R.id.widget_root, open)
                listOf(R.id.widget_previous to PlaybackService.ACTION_PREVIOUS, R.id.widget_play to PlaybackService.ACTION_PLAY, R.id.widget_next to PlaybackService.ACTION_NEXT).forEach { (id, action) ->
                    val intent = Intent(context, PlaybackService::class.java).setAction(action)
                    setOnClickPendingIntent(id, if (title == null) open else PendingIntent.getForegroundService(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
            }
            val manager = AppWidgetManager.getInstance(context)
            manager.updateAppWidget(ComponentName(context, NoirWidget::class.java), views)
        }
    }
}
