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
) {
    val format: String get() = if (sampleRate > 0) "FLAC · $bits бит · ${"%.1f".format(java.util.Locale.US, sampleRate / 1000.0)} кГц" else "FLAC"
}
