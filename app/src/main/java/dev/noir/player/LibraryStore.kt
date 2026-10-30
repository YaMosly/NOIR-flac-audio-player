package dev.noir.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class ScanState(val scanning: Boolean = false, val count: Int = 0, val message: String? = null)

class LibraryStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("noir", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cacheFile = AtomicFile(File(context.filesDir, "library.json"))
    private val mutableTracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks = mutableTracks.asStateFlow()
    val scan = MutableStateFlow(ScanState())
    val smart = MutableStateFlow(prefs.getBoolean("smart", true))
    val folder = MutableStateFlow(prefs.getString("folder_name", null))
    var tree: Uri? = prefs.getString("tree", null)?.let(Uri::parse)
        private set
    val scores = readScores("scores")
    val artistScores = readScores("artists")
    private val artwork = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    val restoreId: String? get() = prefs.getString("last_track", null)
    val restorePosition: Long get() = prefs.getLong("last_position", 0L)

    init {
        scope.launch {
            mutableTracks.value = withContext(Dispatchers.IO) {
                runCatching {
                    val json = cacheFile.openRead().bufferedReader().use { JSONArray(it.readText()) }
                    (0 until json.length()).map { trackFromJson(json.getJSONObject(it)) }
                }.getOrDefault(emptyList())
            }
        }
    }

    fun setSmart(value: Boolean) { smart.value = value; prefs.edit().putBoolean("smart", value).apply() }

    fun savePosition(id: String?, position: Long) {
        prefs.edit().putString("last_track", id).putLong("last_position", position).apply()
    }

    fun resetLearning() {
        scores.clear(); artistScores.clear()
        prefs.edit().remove("scores").remove("artists").apply()
    }

    fun record(track: Track, earlySkip: Boolean, completed: Boolean) {
        if (!earlySkip && !completed) return
        val now = System.currentTimeMillis()
        fun update(map: MutableMap<String, ListeningScore>, key: String) {
            val old = (map[key] ?: ListeningScore(updatedAt = now)).decayed(now)
            map[key] = old.copy(skips = (old.skips + if (earlySkip) 1 else 0).coerceAtMost(20.0), finishes = (old.finishes + if (completed) 1 else 0).coerceAtMost(20.0))
        }
        update(scores, track.id)
        if (track.artist != SmartShuffle.UNKNOWN_ARTIST) update(artistScores, track.artist.lowercase(Locale.ROOT))
        prefs.edit().putString("scores", scoresJson(scores).toString()).putString("artists", scoresJson(artistScores).toString()).apply()
    }

    fun scanFolder(uri: Uri) {
        if (scan.value.scanning) return
        scope.launch {
            scan.value = ScanState(scanning = true)
            val outcome = withContext(Dispatchers.IO) { runCatching {
                val result = mutableListOf<Track>()
                val pending = ArrayDeque<String>()
                val visited = mutableSetOf<String>()
                val root = DocumentsContract.getTreeDocumentId(uri)
                val rootUri = DocumentsContract.buildDocumentUriUsingTree(uri, root)
                val name = context.contentResolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "Музыка" } ?: "Музыка"
                var failed = 0
                pending.add(root)
                while (pending.isNotEmpty()) {
                    ensureActive()
                    val id = pending.removeFirst()
                    if (!visited.add(id)) continue
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, id)
                    val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val cursor = context.contentResolver.query(children, projection, null, null, null)
                        ?: error("Не удалось прочитать папку")
                    cursor.use {
                        while (it.moveToNext()) {
                            ensureActive()
                            val docId = it.getString(0); val filename = it.getString(1) ?: continue
                            if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { pending.add(docId); continue }
                            if (!filename.endsWith(".flac", true)) continue
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                            val info = runCatching { context.contentResolver.openInputStream(fileUri)?.buffered()?.use { stream -> FlacMetadata.read(stream) } }.getOrNull()
                            if (info == null) { failed++; continue }
                            result += Track(fileUri.toString(), info.tags["TITLE"]?.takeIf(String::isNotBlank) ?: filename.substringBeforeLast('.'), info.tags["ARTIST"]?.takeIf(String::isNotBlank) ?: SmartShuffle.UNKNOWN_ARTIST, info.tags["ALBUM"] ?: "", info.durationMs, info.rate, info.bits, info.channels)
                            scan.value = ScanState(true, result.size)
                        }
                    }
                }
                val sorted = result.sortedBy { it.title.lowercase(Locale.ROOT) }
                val bytes = JSONArray().apply { sorted.forEach { put(trackJson(it)) } }.toString().toByteArray()
                val output = cacheFile.startWrite()
                try { output.write(bytes); cacheFile.finishWrite(output) } catch (e: Exception) { cacheFile.failWrite(output); throw e }
                Triple(sorted, name, failed)
            } }
            outcome.onSuccess { (found, name, failed) ->
                mutableTracks.value = found
                tree = uri; folder.value = name
                prefs.edit().putString("tree", uri.toString()).putString("folder_name", name).apply()
                artwork.evictAll()
                scan.value = ScanState(count = found.size, message = when { found.isEmpty() -> "В папке нет доступных FLAC-треков"; failed > 0 -> "Найдено ${found.size}, не прочитано: $failed"; else -> null })
            }.onFailure { scan.value = ScanState(message = "Не удалось прочитать папку. Выбери её заново.") }
        }
    }

    suspend fun cover(track: Track?): Bitmap? = withContext(Dispatchers.IO) {
        if (track == null) return@withContext null
        artwork.get(track.id)?.let { return@withContext it }
        runCatching {
            val bytes = context.contentResolver.openInputStream(Uri.parse(track.id))?.buffered()?.use { FlacMetadata.read(it, true).picture } ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1000 || bounds.outHeight / sample > 1000) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.also { artwork.put(track.id, it) }
        }.getOrNull()
    }

    private fun readScores(key: String): MutableMap<String, ListeningScore> = runCatching {
        val json = JSONObject(prefs.getString(key, "{}")!!)
        json.keys().asSequence().associateWith { id -> json.getJSONObject(id).let { ListeningScore(it.optDouble("s"), it.optDouble("f"), it.optLong("t")) } }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun scoresJson(scores: Map<String, ListeningScore>) = JSONObject().apply { scores.forEach { (id, s) -> put(id, JSONObject().put("s", s.skips).put("f", s.finishes).put("t", s.updatedAt)) } }
    private fun trackJson(t: Track) = JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album).put("duration", t.durationMs).put("rate", t.sampleRate).put("bits", t.bits).put("channels", t.channels)
    private fun trackFromJson(t: JSONObject) = Track(t.getString("id"), t.getString("title"), t.getString("artist"), t.optString("album"), t.optLong("duration"), t.optInt("rate"), t.optInt("bits"), t.optInt("channels"))
}
