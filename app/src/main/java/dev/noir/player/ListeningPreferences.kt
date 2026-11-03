package dev.noir.player

import java.util.Locale

data class ListeningPreferences(val tracks: Set<String> = emptySet(), val artists: Set<String> = emptySet()) {
    fun lessTrack(id: String) = id in tracks
    fun lessArtist(artist: String) = artistKey(artist) in artists
    fun weight(track: Track): Double = (if (lessTrack(track.id)) .2 else 1.0) * (if (lessArtist(track.artist)) .2 else 1.0)
    companion object {
        fun artistKey(artist: String) = if (artist == SmartShuffle.UNKNOWN_ARTIST) "" else artist.trim().lowercase(Locale.ROOT)
    }
}

/** Tokenized case-insensitive search includes album names and ignores diacritics. */
object TrackSearch {
    private val marks = Regex("\\p{M}+")
    private val spaces = Regex("\\s+")
    private fun normalized(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(marks, "").lowercase(Locale.ROOT)
    fun filter(tracks: List<Track>, query: String): List<Track> {
        val words = normalized(query).split(spaces).filter(String::isNotEmpty)
        return if (words.isEmpty()) tracks else tracks.filter { track ->
            val text = normalized("${track.title} ${track.artist} ${track.album}")
            words.all(text::contains)
        }
    }
}
