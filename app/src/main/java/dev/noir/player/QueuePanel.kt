package dev.noir.player

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController

private const val AUTOMATIC = "dev.noir.queue.automatic"
fun MediaItem.isAutomaticQueueItem() = mediaMetadata.extras?.getBoolean(AUTOMATIC) == true
fun Track.toQueueItem(automatic: Boolean) = toMediaItem().buildUpon().setMediaMetadata(
    toMediaItem().mediaMetadata.buildUpon().setExtras(Bundle().apply { putBoolean(AUTOMATIC, automatic) }).build(),
).build()

@Composable
fun QueuePanel(controller: MediaController?, tracks: List<Track>, enabled: Boolean) {
    var revision by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    DisposableEffect(controller) {
        val listener = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { revision++ } }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }
    val entries = remember(controller, revision) {
        if (controller == null) emptyList() else (controller.currentMediaItemIndex.coerceAtLeast(0) until controller.mediaItemCount).map { it to controller.getMediaItemAt(it) }
    }
    val currentIndex = controller?.currentMediaItemIndex ?: -1
    val words = query.trim()
    val matchingIds = remember(tracks, query) { TrackSearch.filter(tracks, query).map { it.id }.toHashSet() }
    val visible = entries.filter { (_, item) -> words.isEmpty() || item.mediaId in matchingIds }
    // An explicit reorder turns the displayed future into a manual queue. Preference
    // changes may regenerate automatic suggestions, never the user's chosen order.
    fun freeze() {
        val player = controller ?: return
        for (index in player.currentMediaItemIndex + 1 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            if (item.isAutomaticQueueItem()) tracks.firstOrNull { it.id == item.mediaId }?.let { player.replaceMediaItem(index, it.toMediaItem()) }
        }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .75f).dp).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        Text("Очередь", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(query, { query = it }, label = { Text("Поиск в очереди") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Ручной порядок сохраняется. Случайные предложения добавляются в конец.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
        if (visible.isEmpty()) Text("В очереди ничего не найдено")
        LazyColumn(Modifier.weight(1f, fill = false)) {
            itemsIndexed(visible, key = { _, entry -> entry.first }) { _, (index, item) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(item.mediaMetadata.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (index == currentIndex) "Сейчас играет" else item.mediaMetadata.artist?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall)
                    if (index > currentIndex) Row {
                        TextButton(onClick = { controller?.seekTo(index, 0); controller?.play() }, enabled = enabled) { Text("Слушать") }
                        IconButton(onClick = { freeze(); controller?.moveMediaItem(index, index - 1) }, enabled = enabled && index > currentIndex + 1) { Icon(Icons.Outlined.ArrowUpward, "Выше: ${item.mediaMetadata.title}") }
                        IconButton(onClick = { freeze(); controller?.moveMediaItem(index, index + 1) }, enabled = enabled && index < (controller?.mediaItemCount ?: 0) - 1) { Icon(Icons.Outlined.ArrowDownward, "Ниже: ${item.mediaMetadata.title}") }
                        IconButton(onClick = { controller?.removeMediaItem(index) }, enabled = enabled) { Icon(Icons.Outlined.Close, "Убрать из очереди: ${item.mediaMetadata.title}") }
                    }
                }
            }
        }
    }
}

@Composable
fun TrackActions(track: Track, controller: MediaController?, library: LibraryStore, enabled: Boolean, feedback: (Boolean, () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val preferences by library.preferences.collectAsState()
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) { Icon(Icons.Outlined.MoreVert, "Действия: ${track.title}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Слушать следующим") }, onClick = {
                controller?.let { it.addMediaItem((it.currentMediaItemIndex + 1).coerceIn(0, it.mediaItemCount), track.toMediaItem()) }; expanded = false
            })
            DropdownMenuItem(text = { Text("В конец очереди") }, onClick = { controller?.addMediaItem(track.toMediaItem()); expanded = false })
            val lessTrack = preferences.lessTrack(track.id)
            DropdownMenuItem(text = { Text(if (lessTrack) "Вернуть обычный шанс трека" else "Реже этот трек") }, onClick = {
                library.setLessTrack(track.id, !lessTrack); expanded = false; feedback(!lessTrack) { library.setLessTrack(track.id, lessTrack) }
            })
            if (ListeningPreferences.artistKey(track.artist).isNotBlank()) {
                val lessArtist = preferences.lessArtist(track.artist)
                DropdownMenuItem(text = { Text(if (lessArtist) "Вернуть обычный шанс исполнителя" else "Реже этого исполнителя") }, onClick = {
                    library.setLessArtist(track.artist, !lessArtist); expanded = false; feedback(!lessArtist) { library.setLessArtist(track.artist, lessArtist) }
                })
            }
        }
    }
}
