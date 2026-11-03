package dev.noir.player

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val sampleRate: Int = 0,
    val bits: Int = 0,
    val channels: Int = 0,
    val albumArtist: String = "",
    val discNumber: Int = 0,
    val trackNumber: Int = 0,
    val fileSize: Long = 0,
    val modified: Long = 0,
) {
    val format: String get() = if (sampleRate > 0) "FLAC · $bits бит · ${"%.1f".format(java.util.Locale.US, sampleRate / 1000.0)} кГц" else "FLAC"
}

object AlbumOrder {
    fun number(tag: String?): Int = tag?.substringBefore('/')?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    fun key(track: Track): Pair<String, String>? = track.album.trim().takeIf { it.isNotEmpty() }?.let {
        it.lowercase(java.util.Locale.ROOT) to (track.albumArtist.ifBlank { track.artist }).trim().lowercase(java.util.Locale.ROOT)
    }
    fun tracks(track: Track, library: List<Track>): List<Track> {
        val key = key(track) ?: return emptyList()
        return library.filter { key(it) == key }.sortedWith(compareBy<Track>(
            { if (it.discNumber > 0) it.discNumber else 1 },
            { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE },
            { it.title.lowercase(java.util.Locale.ROOT) }, { it.id },
        ))
    }
}
