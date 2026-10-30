package dev.noir.player

import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

data class ListeningScore(val skips: Double = 0.0, val finishes: Double = 0.0, val updatedAt: Long = 0L) {
    fun decayed(now: Long): ListeningScore {
        val days = max(0L, now - updatedAt) / 86_400_000.0
        val factor = exp(-days / 21.0)
        return copy(skips = skips * factor, finishes = finishes * factor, updatedAt = now)
    }
    fun weight(now: Long): Double = decayed(now).let { (1.0 + it.finishes * .08) / (1.0 + it.skips * .65) }.coerceIn(.12, 1.8)
}

object SmartShuffle {
    fun isEarlySkip(positionMs: Long, durationMs: Long): Boolean = durationMs > 0 && positionMs >= 2_000 && positionMs < minOf(45_000L, (durationMs * .35).toLong())

    fun choose(
        tracks: List<Track>, recent: List<String>, scores: Map<String, ListeningScore>,
        artistScores: Map<String, ListeningScore>, smart: Boolean, now: Long, random: Random = Random.Default,
    ): Track? {
        if (tracks.isEmpty()) return null
        val avoid = recent.takeLast(minOf(30, max(0, tracks.size - 1))).toSet()
        val candidates = tracks.filterNot { it.id in avoid }.ifEmpty { tracks }
        val weights = candidates.map { track ->
            if (!smart) 1.0 else {
                val trackWeight = scores[track.id]?.weight(now) ?: 1.0
                // Require repeated skips before affecting an entire artist; unknown tags are never grouped.
                val artist = artistScores[track.artist.lowercase(java.util.Locale.ROOT)]?.decayed(now)
                val artistWeight = if (track.artist != UNKNOWN_ARTIST && artist != null && artist.skips >= 3) artist.weight(now) else 1.0
                (trackWeight * artistWeight).coerceAtLeast(.08)
            }
        }
        var pick = random.nextDouble() * weights.sum()
        candidates.forEachIndexed { i, track -> pick -= weights[i]; if (pick < 0) return track }
        return candidates.last()
    }
    const val UNKNOWN_ARTIST = "Неизвестный исполнитель"
}
