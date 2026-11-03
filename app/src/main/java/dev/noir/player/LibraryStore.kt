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
data class SavedQueueEntry(val id: String, val automatic: Boolean, val album: Boolean = false)

class LibraryStore(private val context: Context) : AutoCloseable {
    private val prefs = context.getSharedPreferences("noir", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cacheFile = AtomicFile(File(context.filesDir, "library.json"))
    private val cacheLock = kotlinx.coroutines.sync.Mutex()
    private val mutableTracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks = mutableTracks.asStateFlow()
    val scan = MutableStateFlow(ScanState())
    val smart = MutableStateFlow(prefs.getBoolean("smart", true))
    val folder = MutableStateFlow(prefs.getString("folder_name", null))
    var tree: Uri? = prefs.getString("tree", null)?.let(Uri::parse)
        private set
    val scores = readScores("scores")
    val artistScores = readScores("artists")
    val preferences = MutableStateFlow(ListeningPreferences(
        prefs.getStringSet("less_tracks", emptySet()).orEmpty().toSet(),
        prefs.getStringSet("less_artists", emptySet()).orEmpty().toSet(),
    ))
    val recommendationRevision = MutableStateFlow(0L)
    val history = runCatching { JSONArray(prefs.getString("history", "[]")).let { a -> (0 until minOf(a.length(), 1000)).map { a.getString(it) } } }.getOrDefault(emptyList()).toMutableList()
    fun rememberPlayed(id: String) {
        if (history.lastOrNull() == id) return
        history.add(id); while (history.size > 1000) history.removeAt(0)
        prefs.edit().putString("history", JSONArray(history).toString()).apply()
    }
    private val artwork = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    val restoreId: String? get() = prefs.getString("last_track", null)
    val restorePosition: Long get() = prefs.getLong("last_position", 0L)

    private val loaded = CompletableDeferred<Unit>()
    private var scanJob: Job? = null
    private var scanUri: Uri? = null
    private var queuedRefresh = false
    private var visible = false
    private var foregroundJob: Job? = null
    private var observerJob: Job? = null
    private val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (!visible) return
            observerJob?.cancel()
            observerJob = scope.launch { delay(1_000); tree?.let(::scanFolder) }
        }
    }
    fun setForeground(value: Boolean) {
        if (visible == value) return
        visible = value; foregroundJob?.cancel()
        if (value) foregroundJob = scope.launch {
            loaded.await()
            while (isActive) { tree?.let(::scanFolder); delay(60_000) }
        }
    }
    private fun watch(uris: Set<Uri>) {
        context.contentResolver.unregisterContentObserver(observer)
        uris.take(256).forEach { uri -> runCatching { context.contentResolver.registerContentObserver(uri, true, observer) } }
    }

    init {
        scope.launch {
            mutableTracks.value = withContext(Dispatchers.IO) {
                runCatching {
                    val cached = cacheFile.openRead().bufferedReader().use { org.json.JSONTokener(it.readText()).nextValue() }
                    val json = if (cached is JSONArray) cached else (cached as JSONObject).let {
                        require(it.optString("tree") == tree?.toString().orEmpty())
                        it.getJSONArray("tracks")
                    }
                    (0 until json.length()).map { trackFromJson(json.getJSONObject(it)) }
                }.getOrDefault(emptyList())
            }
            loaded.complete(Unit)
        }
    }

    fun setSmart(value: Boolean) { smart.value = value; prefs.edit().putBoolean("smart", value).apply(); recommendationRevision.value++ }
    fun setLessTrack(id: String, less: Boolean) {
        val current = preferences.value
        preferences.value = current.copy(tracks = if (less) current.tracks + id else current.tracks - id)
        savePreferences()
    }
    fun setLessArtist(artist: String, less: Boolean) {
        val key = ListeningPreferences.artistKey(artist)
        if (key.isBlank()) return
        val current = preferences.value
        preferences.value = current.copy(artists = if (less) current.artists + key else current.artists - key)
        savePreferences()
    }
    private fun savePreferences() {
        prefs.edit().putStringSet("less_tracks", preferences.value.tracks).putStringSet("less_artists", preferences.value.artists).apply()
        recommendationRevision.value++
    }

    fun savePosition(id: String?, position: Long) {
        prefs.edit().putString("last_track", id).putLong("last_position", position).apply()
    }
    fun saveQueue(entries: List<SavedQueueEntry>, position: Long) {
        val queue = JSONArray().apply { entries.take(1000).forEach { put(JSONObject().put("id", it.id).put("auto", it.automatic).put("album", it.album)) } }.toString()
        val editor = prefs.edit().putString("last_track", entries.firstOrNull()?.id).putLong("last_position", position)
        if (queue != prefs.getString("queue", null)) editor.putString("queue", queue)
        editor.apply()
    }
    fun savedQueue(): List<SavedQueueEntry> = runCatching {
        val value = prefs.getString("queue", "[]").orEmpty()
        require(value.length <= 1024 * 1024)
        val queue = JSONArray(value)
        (0 until minOf(queue.length(), 1000)).map { queue.getJSONObject(it).let { entry -> SavedQueueEntry(entry.getString("id"), entry.optBoolean("auto"), entry.optBoolean("album")) } }
    }.getOrDefault(emptyList())

    fun resetLearning() {
        scores.clear(); artistScores.clear(); history.clear()
        prefs.edit().remove("scores").remove("artists").remove("history").apply()
        preferences.value = ListeningPreferences(); savePreferences()
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
        if (earlySkip) recommendationRevision.value++
    }

    fun scanFolder(uri: Uri) {
        if (scanJob?.isActive == true) {
            if (scanUri == uri) { queuedRefresh = true; return }
            scanJob?.cancel()
        }
        scanUri = uri; queuedRefresh = false
        scanJob = scope.launch {
            loaded.await()
            val previous = if (tree == uri) mutableTracks.value.associateBy { it.id } else emptyMap()
            val watched = linkedSetOf<Uri>()
            scan.value = ScanState(scanning = true)
            val outcome = withContext(Dispatchers.IO) { runCatching {
                val result = mutableListOf<Track>()
                val pending = ArrayDeque<String>()
                val visited = mutableSetOf<String>()
                val root = DocumentsContract.getTreeDocumentId(uri)
                val rootUri = DocumentsContract.buildDocumentUriUsingTree(uri, root)
                val name = context.contentResolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "Музыка" } ?: "Музыка"
                var failed = 0
                watched.add(rootUri)
                pending.add(root)
                while (pending.isNotEmpty()) {
                    ensureActive()
                    val id = pending.removeFirst()
                    if (!visited.add(id)) continue
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, id)
                    val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val cursor = context.contentResolver.query(children, projection, null, null, null)
                        ?: error("Не удалось прочитать папку")
                    watched.add(children)
                    cursor.notificationUri?.let(watched::add)
                    cursor.use {
                        check(!it.extras.getBoolean(DocumentsContract.EXTRA_LOADING) && it.extras.getString(DocumentsContract.EXTRA_ERROR) == null) { "Неполный ответ папки" }
                        while (it.moveToNext()) {
                            ensureActive()
                            val docId = it.getString(0); val filename = it.getString(1) ?: continue
                            if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { pending.add(docId); continue }
                            if (!filename.endsWith(".flac", true)) continue
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                            val size = if (it.isNull(3)) 0L else it.getLong(3)
                            val modified = if (it.isNull(4)) 0L else it.getLong(4)
                            val old = previous[fileUri.toString()]
                            if (old != null && size > 0 && modified > 0 && old.fileSize == size && old.modified == modified) {
                                result += old
                            } else {
                                val info = runCatching { context.contentResolver.openInputStream(fileUri)?.buffered()?.use { stream -> FlacMetadata.read(stream) } }.getOrNull()
                                if (info == null) { failed++; old?.let(result::add); continue }
                                result += Track(fileUri.toString(), info.tags["TITLE"]?.takeIf(String::isNotBlank) ?: filename.substringBeforeLast('.'), info.tags["ARTIST"]?.takeIf(String::isNotBlank) ?: SmartShuffle.UNKNOWN_ARTIST, info.tags["ALBUM"] ?: "", info.durationMs, info.rate, info.bits, info.channels,
                                    info.tags["ALBUMARTIST"] ?: info.tags["ALBUM ARTIST"] ?: "", AlbumOrder.number(info.tags["DISCNUMBER"]), AlbumOrder.number(info.tags["TRACKNUMBER"]), size, modified)
                            }
                            scan.value = ScanState(true, result.size)
                        }
                    }
                }
                val sorted = result.sortedBy { it.title.lowercase(Locale.ROOT) }
                val bytes = JSONObject().put("tree", uri.toString()).put("tracks", JSONArray().apply { sorted.forEach { put(trackJson(it)) } }).toString().toByteArray()
                cacheLock.lock()
                try {
                    ensureActive()
                    val output = cacheFile.startWrite()
                    try { output.write(bytes); cacheFile.finishWrite(output) } catch (e: Exception) { cacheFile.failWrite(output); throw e }
                } finally { cacheLock.unlock() }
                Triple(sorted, name, failed)
            } }
            outcome.onSuccess { (found, name, failed) ->
                val changed = mutableTracks.value != found
                mutableTracks.value = found
                watch(watched)
                tree = uri; folder.value = name
                prefs.edit().putString("tree", uri.toString()).putString("folder_name", name).apply()
                if (changed) artwork.evictAll()
                scan.value = ScanState(count = found.size, message = when { found.isEmpty() -> "В папке нет доступных FLAC-треков"; failed > 0 -> "Найдено ${found.size}, не прочитано: $failed"; else -> null })
            }.onFailure {
                if (it is CancellationException) throw it
                scan.value = ScanState(count = mutableTracks.value.size, message = "Не удалось полностью прочитать папку. Сохранённая библиотека оставлена; проверь доступ и обнови её.")
            }
            scanJob = null
            if (queuedRefresh) { queuedRefresh = false; scanFolder(uri) }
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
    private fun trackJson(t: Track) = JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album).put("duration", t.durationMs).put("rate", t.sampleRate).put("bits", t.bits).put("channels", t.channels).put("albumArtist", t.albumArtist).put("disc", t.discNumber).put("number", t.trackNumber).put("size", t.fileSize).put("modified", t.modified)
    private fun trackFromJson(t: JSONObject) = Track(t.getString("id"), t.getString("title"), t.getString("artist"), t.optString("album"), t.optLong("duration"), t.optInt("rate"), t.optInt("bits"), t.optInt("channels"), t.optString("albumArtist"), t.optInt("disc"), t.optInt("number"), t.optLong("size"), t.optLong("modified"))
    override fun close() { context.contentResolver.unregisterContentObserver(observer); scope.cancel() }
}
