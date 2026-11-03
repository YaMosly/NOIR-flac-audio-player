package dev.noir.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController

@Composable
fun AlbumPanel(matches: List<Track>, library: List<Track>, controller: MediaController?, enabled: Boolean, modifier: Modifier = Modifier, onPlay: () -> Unit) {
    val albums = remember(matches) { matches.filter { AlbumOrder.key(it) != null }.distinctBy { AlbumOrder.key(it) }.sortedBy { it.album.lowercase(java.util.Locale.ROOT) } }
    LazyColumn(modifier) {
        if (albums.isEmpty()) item { Text("Нет альбомов с заполненными тегами", modifier = Modifier.padding(vertical = 16.dp)) }
        items(albums, key = { it.id }) { first ->
            val tracks = remember(first, library) { AlbumOrder.tracks(first, library) }
            Column(Modifier.fillMaxWidth().clickable(enabled = enabled) {
                controller?.let { it.setMediaItems(tracks.map { track -> track.toQueueItem(false, album = true) }); it.prepare(); it.play(); onPlay() }
            }.padding(vertical = 16.dp)) {
                Text(first.album, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${first.albumArtist.ifBlank { first.artist }} · ${tracks.size} треков", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
