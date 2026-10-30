package dev.noir.player

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import java.util.Locale

private val Ink = Color(0xFF0A0B0E)
private val Paper = Color(0xFFECEFED)
private val Muted = Color(0xFF9BA4A2)
private val Accent = Color(0xFFC8DDD2)
private val Surface = Color(0xFF181C20)

class MainActivity : ComponentActivity() {
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (DirectUsbSettings.enabled.value && !UsbPlaybackInterlock.active.value && keyCode in setOf(android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            DirectUsbSettings.setDb(DirectUsbSettings.db.value + if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Ink, background = Ink, surface = Surface, onSurface = Paper, onBackground = Paper, secondary = Accent)) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Ink, contentColor = Paper) {
                    NoirScreen((application as NoirApp).library)
                }
            }
        }
    }
}

@Composable
private fun rememberController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        var disposed = false
        future.addListener({ if (!disposed) controller = runCatching { future.get() }.getOrNull() }, ContextCompat.getMainExecutor(context))
        onDispose { disposed = true; controller = null; MediaController.releaseFuture(future) }
    }
    return controller
}

private data class PlaybackUi(val id: String? = null, val playing: Boolean = false, val position: Long = 0, val duration: Long = 0, val error: String? = null, val buffering: Boolean = false)

@Composable
private fun playbackUi(controller: MediaController?): PlaybackUi {
    var state by remember { mutableStateOf(PlaybackUi()) }
    DisposableEffect(controller) {
        fun update() { state = PlaybackUi(controller?.currentMediaItem?.mediaId, controller?.isPlaying == true, controller?.currentPosition ?: 0, controller?.duration?.coerceAtLeast(0) ?: 0, controller?.playerError?.let { if (DirectUsbSettings.enabled.value) "Прямой USB: ${DirectUsbSettings.failure.value ?: "проверь доступ к ЦАПу и формат трека"}. Подробности — в звуковом тракте." else "Не удалось воспроизвести трек. Проверь доступ к папке." }, controller?.playbackState == Player.STATE_BUFFERING) }
        val listener = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) = update() }
        controller?.addListener(listener); update()
        onDispose { controller?.removeListener(listener) }
    }
    LaunchedEffect(controller) { while (true) { delay(400); state = state.copy(position = controller?.currentPosition ?: 0, duration = controller?.duration?.coerceAtLeast(0) ?: 0) } }
    return state
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NoirScreen(library: LibraryStore) {
    val context = LocalContext.current
    val tracks by library.tracks.collectAsStateWithLifecycle()
    val scan by library.scan.collectAsStateWithLifecycle()
    val smart by library.smart.collectAsStateWithLifecycle()
    val folder by library.folder.collectAsStateWithLifecycle()
    val controller = rememberController()
    val playback = playbackUi(controller)
    val usbFailure by DirectUsbSettings.failure.collectAsStateWithLifecycle()
    val directMode by DirectUsbSettings.enabled.collectAsStateWithLifecycle()
    val current = tracks.firstOrNull { it.id == playback.id }
    var showLibrary by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var pickerError by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    val diagnostics = remember { AudioDiagnostics(context) }
    val systemOutput by diagnostics.state.collectAsStateWithLifecycle()
    val directOwned by DirectUsbSettings.owned.collectAsStateWithLifecycle()
    val output = if (directOwned) systemOutput.copy(device = "ddHiFi · прямой USB", usb = true) else systemOutput
    val usbDiagnostics = remember { UsbDiagnostics(context) }
    val usbProbe by usbDiagnostics.state.collectAsStateWithLifecycle()
    val usbPreview = remember { UsbDirectPreview(context) }
    val preview by usbPreview.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val usbLocked by UsbPlaybackInterlock.active.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { diagnostics.start(); onDispose { diagnostics.close() } }
    DisposableEffect(Unit) { usbDiagnostics.start(); onDispose { usbDiagnostics.close() } }
    DisposableEffect(lifecycle) {
        usbPreview.start()
        usbPreview.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) usbPreview.setForeground(true)
            if (event == Lifecycle.Event.ON_STOP) usbPreview.setForeground(false)
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_STOP) BackgroundAudioDiagnostics.record(context, "UI $event")
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); usbPreview.close() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                library.scanFolder(uri)
            }.onFailure { pickerError = "Не удалось сохранить доступ. Попробуй другую папку." }
        }
    }
    val cover by produceState<Bitmap?>(null, current?.id) { value = null; value = library.cover(current) }
    val artwork = remember(cover) { cover?.asImageBitmap() }
    val rawTint = remember(cover) { cover?.let(::coverColor) ?: Color(0xFF71978F) }
    val tint by animateColorAsState(rawTint, tween(1400), label = "coverTint")
    fun randomPlay() { if (!usbLocked) context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_RANDOM)) }
    fun toggle() {
        if (usbLocked) return
        controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    }
    Box(Modifier.fillMaxSize().background(Ink)) {
        AmbientGlow(tint, playback.playing)
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("N O I R", color = Paper, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
                Row {
                    ToolButton(if (showLibrary) Icons.Outlined.Close else Icons.Outlined.LibraryMusic, if (showLibrary) "Закрыть библиотеку" else "Библиотека") { showLibrary = !showLibrary }
                    ToolButton(Icons.Outlined.Tune, "Настройки") { sheet = "settings" }
                }
            }
            if (tracks.isEmpty()) {
                EmptyLibrary(scan, pickerError) { picker.launch(library.tree) }
            } else if (showLibrary) {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Твоя музыка", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Paper); Text("${tracks.size} треков · ${folder ?: "Папка"}", color = Muted, fontSize = 13.sp) }
                    ToolButton(Icons.Outlined.FolderOpen, "Выбрать папку") { picker.launch(library.tree) }
                }
                OutlinedTextField(search, { search = it }, placeholder = { Text("Найти трек или исполнителя") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { randomPlay(); showLibrary = false }, enabled = controller != null && !usbLocked) { Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("Перемешать всё") }
                LazyColumn(Modifier.weight(1f)) {
                    items(tracks.filter { search.isBlank() || it.title.contains(search, true) || it.artist.contains(search, true) }, key = { it.id }) { track ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = controller != null && !usbLocked) {
                            controller?.setMediaItem(track.toMediaItem()); controller?.prepare(); controller?.play(); showLibrary = false
                        }.padding(vertical = 13.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (track.id == current?.id) Icons.Rounded.GraphicEq else Icons.Outlined.MusicNote, null, Modifier.size(24.dp), tint = if (track.id == current?.id) Accent else Muted)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Paper, fontSize = 15.sp); Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 12.sp) }
                            Text(timeLabel(track.durationMs), color = Muted, fontSize = 12.sp)
                        }
                    }
                }
                if (current != null) Row(Modifier.fillMaxWidth().clickable { showLibrary = false }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(artwork, Modifier.size(48.dp), tint)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(current.title, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(current.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    ToolButton(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "Пауза" else "Слушать", enabled = !usbLocked) { toggle() }
                }
            } else {
                NowPlaying(current, artwork, tint, if (directMode && usbFailure != null) playback.copy(error = usbFailure) else playback, smart, output, controller != null && !usbLocked,
                    onPlay = { toggle() }, onNext = { controller?.seekToNextMediaItem() }, onPrevious = { controller?.seekToPrevious() },
                    onShuffle = { randomPlay() }, onSeek = { controller?.seekTo(it) }, onOutput = { sheet = "audio" }, onVolume = { sheet = "volume" }, onSmart = { library.setSmart(!smart) })
            }
            if (scan.scanning && tracks.isNotEmpty()) Text("Обновляю папку · ${scan.count} треков", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(8.dp))
        }
        if (sheet != null) ModalBottomSheet(onDismissRequest = { usbPreview.stop(); sheet = null }, containerColor = Surface, contentColor = Paper) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                when (sheet) {
                    "settings" -> {
                        Text("Под тебя", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Умное перемешивание"); Text("Реже предлагает то, что ты часто пропускаешь", color = Muted, fontSize = 13.sp) }; Switch(smart, library::setSmart) }
                        Text("Учитываем пропуски после 2 секунд в начале трека. Несколько пропусков снижают частоту исполнителя. Предпочтения постепенно ослабевают; музыка не блокируется навсегда.", color = Muted, fontSize = 13.sp)
                        TextButton(onClick = { library.resetLearning(); sheet = "reset" }) { Text("Сбросить историю предпочтений") }
                        HorizontalDivider(color = Muted.copy(alpha = .15f))
                        Text("Папка · ${folder ?: "Не выбрана"}", color = Muted)
                        TextButton(onClick = { sheet = null; picker.launch(library.tree) }, enabled = !scan.scanning) { Icon(Icons.Outlined.FolderOpen, null); Spacer(Modifier.width(10.dp)); Text("Выбрать папку") }
                        TextButton(onClick = { library.tree?.let(library::scanFolder) }, enabled = library.tree != null && !scan.scanning) { Text("Обновить библиотеку") }
                        scan.message?.let { Text(it, color = Muted, fontSize = 13.sp) }
                        TextButton(onClick = { sheet = "background" }) { Text("Работа при выключенном экране") }
                        TextButton(onClick = { diagnostics.refresh(); sheet = "audio" }) { Text("Звуковой тракт и USB-ЦАП") }
                        Text("NOIR ${BuildConfig.VERSION_NAME} · Всё хранится на телефоне", color = Muted, fontSize = 12.sp)
                    }
                    "reset" -> { Text("Начинаем с чистого листа", fontSize = 23.sp); Text("История пропусков сброшена. У всех треков снова одинаковый шанс.", color = Muted) }
                    "volume" -> VolumePanel()
                    "background" -> BackgroundPowerPanel()
                    "audio" -> AudioPanel(current, output, usbProbe, preview, usbLocked,
                        onProbe = { exclusive -> usbDiagnostics.request(exclusive) },
                        onPreview = { db -> current?.let { usbPreview.request(it, db) } }, onStopPreview = usbPreview::stop)
                }
            }
        }
    }
}

@Composable
private fun AmbientGlow(color: Color, moving: Boolean) {
    val context = LocalContext.current
    val motionAllowed = remember { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    val phase = if (moving && motionAllowed) {
        val infinite = rememberInfiniteTransition(label = "ambient")
        val shift by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "glow")
        shift
    } else 0f
    Canvas(Modifier.fillMaxSize()) {
        val x = if (moving && motionAllowed) .45f + phase * .12f else .5f
        drawRect(Brush.radialGradient(listOf(color.copy(alpha = .17f), Color.Transparent), Offset(size.width * x, size.height * .34f), size.width * .9f))
    }
}

@Composable
private fun ColumnScope.EmptyLibrary(scan: ScanState, error: String?, choose: () -> Unit) {
    Spacer(Modifier.weight(.7f))
    Cover(null, Modifier.fillMaxWidth(.67f).aspectRatio(1f).align(Alignment.CenterHorizontally), Color(0xFF6C9387))
    Spacer(Modifier.height(38.dp))
    Text("Твоя музыка.\nБез лишнего.", color = Paper, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, lineHeight = 45.sp, letterSpacing = (-1.5).sp)
    Spacer(Modifier.height(16.dp))
    Text("Выбери папку с FLAC.\nОстальное — нажать Play.", color = Muted, fontSize = 16.sp, lineHeight = 24.sp)
    Spacer(Modifier.height(28.dp))
    Button(onClick = choose, enabled = !scan.scanning, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
        if (scan.scanning) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Читаю треки · ${scan.count}") }
        else { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Выбрать папку", fontWeight = FontWeight.SemiBold) }
    }
    (error ?: scan.message)?.let { Text(it, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
    Spacer(Modifier.weight(1f))
    Text("Локально. Только музыка.", color = Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp), textAlign = TextAlign.Center)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ColumnScope.NowPlaying(track: Track?, art: ImageBitmap?, tint: Color, state: PlaybackUi, smart: Boolean, output: OutputState, ready: Boolean,
    onPlay: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onShuffle: () -> Unit, onSeek: (Long) -> Unit, onOutput: () -> Unit, onVolume: () -> Unit, onSmart: () -> Unit) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val artSize = minOf(maxWidth - 18.dp, maxHeight * .52f, 350.dp)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(.45f))
            Text(if (state.playing) "СЕЙЧАС ЗВУЧИТ" else "ТВОЯ МУЗЫКА", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(22.dp))
            Cover(art, Modifier.size(artSize), tint)
            Spacer(Modifier.height(28.dp))
            Text(track?.title ?: "Музыка готова", color = Paper, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 32.sp, letterSpacing = (-.7).sp)
            Spacer(Modifier.height(8.dp))
            Text(track?.artist ?: "Нажми Play", color = Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Text(track?.format ?: "FLAC", color = Muted.copy(alpha = .85f), fontSize = 11.sp, letterSpacing = .4.sp)
            Spacer(Modifier.weight(.7f))
            var scrubbing by remember { mutableStateOf<Float?>(null) }
            val duration = state.duration.takeIf { it > 0 } ?: track?.durationMs ?: 0L
            Slider(value = scrubbing ?: (if (duration > 0) state.position.toFloat() / duration else 0f).coerceIn(0f, 1f), onValueChange = { scrubbing = it }, onValueChangeFinished = { scrubbing?.let { onSeek((duration * it).toLong()) }; scrubbing = null }, enabled = duration > 0 && ready, modifier = Modifier.fillMaxWidth().height(28.dp),
                colors = SliderDefaults.colors(activeTrackColor = Accent, inactiveTrackColor = Muted.copy(alpha = .22f)),
                thumb = { Box(Modifier.size(10.dp).background(Accent, CircleShape)) },
                track = { sliderState -> SliderDefaults.Track(sliderState, modifier = Modifier.height(3.dp), colors = SliderDefaults.colors(activeTrackColor = Accent, inactiveTrackColor = Muted.copy(alpha = .22f)), thumbTrackGapSize = 0.dp, trackInsideCornerSize = 1.dp, drawStopIndicator = null) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(timeLabel(scrubbing?.let { (it * duration).toLong() } ?: state.position), color = Muted, fontSize = 11.sp); Text(timeLabel(duration), color = Muted, fontSize = 11.sp) }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                ToolButton(Icons.Rounded.Shuffle, "Перемешать заново", enabled = ready, onClick = onShuffle)
                IconButton(onClick = onPrevious, enabled = ready, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipPrevious, "Предыдущий трек", Modifier.size(34.dp)) }
                FilledIconButton(onClick = onPlay, enabled = ready, modifier = Modifier.size(76.dp), shape = CircleShape) {
                    if (state.buffering) CircularProgressIndicator(Modifier.size(28.dp), color = Ink, strokeWidth = 2.dp)
                    else Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Пауза" else "Слушать", Modifier.size(38.dp))
                }
                IconButton(onClick = onNext, enabled = ready, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.SkipNext, "Следующий трек", Modifier.size(34.dp)) }
                ToolButton(Icons.Outlined.VolumeUp, "Громкость", onClick = onVolume)
            }
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onSmart) { Icon(if (smart) Icons.Outlined.AutoAwesome else Icons.Rounded.Shuffle, null, Modifier.size(15.dp)); Spacer(Modifier.width(8.dp)); Text(if (smart) "Умный рандом" else "Обычный рандом", fontSize = 12.sp) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, textAlign = TextAlign.Center) }
            Spacer(Modifier.weight(.4f))
        }
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOutput).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(if (output.usb) Icons.Outlined.Usb else Icons.Outlined.Speaker, null, Modifier.size(15.dp), tint = Muted)
        Spacer(Modifier.width(8.dp))
        Text(if (output.usb) output.device else "Звуковой тракт", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 250.dp))
        Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.ChevronRight, null, Modifier.size(15.dp), tint = Muted)
    }
}

@Composable
private fun Cover(art: ImageBitmap?, modifier: Modifier, tint: Color) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF12191B)), contentAlignment = Alignment.Center) {
        if (art != null) Image(art, "Обложка альбома", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.linearGradient(listOf(Color(0xFF10181D), tint.copy(alpha = .3f), Color(0xFF14151E)), Offset.Zero, Offset(size.width, size.height)))
                val c = Offset(size.width * .5f, size.height * .5f)
                repeat(8) { i -> drawCircle(tint.copy(alpha = .09f + i * .012f), size.width * (.15f + i * .04f), c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.width * .0025f)) }
                drawLine(Paper.copy(alpha = .7f), Offset(size.width * .38f, size.height * .64f), Offset(size.width * .38f, size.height * .36f), size.width * .014f)
                drawLine(Paper.copy(alpha = .7f), Offset(size.width * .38f, size.height * .36f), Offset(size.width * .62f, size.height * .64f), size.width * .014f)
                drawLine(Paper.copy(alpha = .7f), Offset(size.width * .62f, size.height * .64f), Offset(size.width * .62f, size.height * .36f), size.width * .014f)
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label, Modifier.size(23.dp), tint = if (enabled) Muted else Muted.copy(alpha = .3f)) }
}

@Composable
private fun AudioPanel(track: Track?, output: OutputState, probe: UsbProbeState, preview: UsbPreviewState, usbLocked: Boolean, onProbe: (Boolean) -> Unit, onPreview: (Int) -> Unit, onStopPreview: () -> Unit) {
    val context = LocalContext.current
    val direct by DirectUsbSettings.enabled.collectAsStateWithLifecycle()
    val normalOwns by DirectUsbSettings.owned.collectAsStateWithLifecycle()
    val directStatus by DirectUsbSettings.status.collectAsStateWithLifecycle()
    val directReport by DirectUsbSettings.report.collectAsStateWithLifecycle()
    val access = remember { UsbPlaybackAccess(context) }
    DisposableEffect(access) { onDispose { access.close() } }
    var copied by remember { mutableStateOf(false) }
    var previewDb by remember { mutableIntStateOf(-35) }
    LaunchedEffect(track?.id, output, probe.report, preview.report) { copied = false }
    Text("Звуковой тракт", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    InfoRow("Исходный файл", track?.format ?: "Трек не выбран")
    InfoRow("Подключённый USB-ЦАП", if (output.usb) output.device else "Не подключён")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Прямой USB в плеере", fontSize = 19.sp); Text("Полные треки · 16–32 бит / 44,1–384 кГц / стерео", color = Muted, fontSize = 12.sp) }
        Switch(direct, { if (it) access.enable() else DirectUsbSettings.setEnabled(false) }, enabled = !usbLocked && !probe.busy && !preview.busy)
    }
    Text(directStatus, color = Accent, fontSize = 13.sp)
    Text("После включения нажми обычную кнопку Play. Работают очередь, пауза, перемотка и экран блокировки. Смена режима ставит музыку на паузу. Частота и разрядность следуют за треком; ЦАП должен подтвердить формат.", color = Muted, fontSize = 13.sp)
    if (direct && !normalOwns) TextButton(onClick = access::enable, enabled = !usbLocked) { Text("Разрешить доступ к ЦАПу") }
    if (normalOwns) Text("Для отдельных проверок ниже сначала выключи прямой USB основного плеера.", color = Muted, fontSize = 12.sp)
    HorizontalDivider(color = Muted.copy(alpha = .15f))
    BackgroundPowerPanel()
    HorizontalDivider(color = Muted.copy(alpha = .15f))
    Text("Прямой USB · эксперимент", fontSize = 19.sp, fontWeight = FontWeight.Medium)
    Text("Первые 15 секунд выбранного трека через ЦАП, в обход микшера Android. Пока только FLAC 16 бит / 44,1 кГц / стерео и ЦАП 0E55:1110. При уходе из приложения проба останавливается.", color = Muted, fontSize = 13.sp)
    Text("Аппаратная громкость пробы · $previewDb дБ", color = Paper, fontSize = 14.sp)
    Slider(previewDb.toFloat(), { previewDb = (kotlin.math.round(it / 5) * 5).toInt() }, valueRange = -45f..-15f, steps = 5, enabled = !normalOwns && !preview.busy && !usbLocked && !probe.busy)
    Text("Начни с −35 дБ. Системная громкость Android на эту пробу не действует. После остановки NOIR отключит поток и попытается вернуть исходные частоту и громкость ЦАПа; результат будет в отчёте.", color = Muted, fontSize = 12.sp)
    if (preview.busy) {
        Button(onClick = onStopPreview, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text("Остановить USB-пробу") }
    } else {
        Button(onClick = { onPreview(previewDb) }, enabled = !normalOwns && !probe.busy && !usbLocked && track?.let { it.bits == 16 && it.sampleRate == 44_100 && it.channels == 2 } == true, modifier = Modifier.fillMaxWidth()) { Text("Слушать 15 секунд через USB") }
    }
    Text(preview.message, color = Muted, fontSize = 13.sp)
    HorizontalDivider(color = Muted.copy(alpha = .15f))
    if (output.usb || probe.busy || probe.report != null) {
        Text("Эксклюзивная проверка остановит музыку и временно отключит системный доступ к ЦАПу. После чтения NOIR освободит его. Если Android не вернул звук, переподключи ЦАП.", color = Muted, fontSize = 13.sp)
        Button(onClick = { onProbe(true) }, enabled = !normalOwns && !probe.busy && !preview.busy && !usbLocked, modifier = Modifier.fillMaxWidth()) {
            if (probe.busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
            Text(if (probe.busy) "Проверка USB…" else "Эксклюзивная проверка USB")
        }
        TextButton(onClick = { onProbe(false) }, enabled = !normalOwns && !probe.busy && !preview.busy && !usbLocked) { Text("Обычная проверка без отключения драйвера") }
        Text(probe.message, color = Muted, fontSize = 13.sp)
        Text("Читаем описание, частоты и регуляторы. Громкость и частота не меняются. Android может запросить USB-доступ.", color = Muted, fontSize = 12.sp)
    }
    InfoRow("Основной плеер", if (direct) directStatus else "Android · без эквалайзера и предусиления")
    InfoRow("Bit-perfect всего тракта", "Не измерен")
    Text(if (direct) "PCM передаётся напрямую USB-ЦАПу. Громкость регулируется в самом ЦАПе; системный уровень Android не управляет этим потоком." else "Для Android разрешён PCM float. Формат на физическом входе ЦАПа отдельно не измеряется.", color = Muted, fontSize = 13.sp)
    if (output.usb) {
        InfoRow("Bit-perfect по данным системы", if (output.bitPerfectAvailable) "Доступен для части форматов" else "Система не сообщает поддержку")
        if (output.rates.isNotEmpty()) InfoRow("Частоты, которые сообщает Android", output.rates.joinToString { "${it / 1000.0} кГц" })
        if (track != null && track.sampleRate > 0 && output.rates.isNotEmpty() && track.sampleRate !in output.rates) {
            Text("Частота файла ${track.sampleRate} Гц отсутствует в списке Android. Исходную частоту на выходе не подтверждаем. Это не доказывает ограничение самого ЦАПа.", color = Accent, fontSize = 13.sp)
        }
        Text("Режимы USB, которые сообщает Android", fontWeight = FontWeight.Medium)
        Text(output.mixerFormats.ifEmpty { listOf("Система не вернула список режимов") }.joinToString("\n"), fontSize = 12.sp, color = Muted)
    }
    Text("При отключении USB-ЦАПа воспроизведение ставится на паузу.", color = Muted, fontSize = 13.sp)
    TextButton(onClick = {
        val report = buildString {
            appendLine("NOIR ${BuildConfig.VERSION_NAME} — диагностика")
            appendLine("Телефон: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Файл: ${track?.format ?: "не выбран"}")
            appendLine("USB подключён: ${output.usb}; устройство: ${output.device}")
            appendLine("Доступен bit-perfect по данным Android: ${output.bitPerfectAvailable}")
            appendLine("Прямой USB основного плеера включён: $direct; владеет ЦАПом: $normalOwns")
            appendLine("Основной плеер: $directStatus")
            appendLine("Аппаратная громкость прямого USB: ${DirectUsbSettings.db.value} дБ")
            appendLine(DirectUsbSettings.decodedHash.value.ifBlank { "Хеш PCM основного USB-потока ещё не рассчитан" })
            appendLine("Bit-perfect всего тракта: не измерен")
            appendLine("Реальный формат на входе ЦАПа: не подтверждён")
            appendLine("Частоты по данным Android: ${output.rates}")
            appendLine("Режимы микшера:"); output.mixerFormats.forEach { appendLine(it) }
            appendLine(); appendLine(probe.report ?: "USB-проверка: ${probe.message}")
            appendLine(); appendLine(preview.report ?: "Прямая USB-проба: ${preview.message}")
            appendLine(); appendLine(directReport.ifBlank { "Основной прямой поток ещё не завершён; после паузы отчёт будет здесь." })
            appendLine(); appendLine(BackgroundAudioDiagnostics.report(context))
            appendLine(); appendLine(CrashReports.report())
        }
        context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("NOIR", report))
        copied = true
    }, enabled = !probe.busy && !preview.busy) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text(if (copied) "Скопировано" else "Скопировать диагностику") }
    preview.report?.let { Text(it, color = Muted, fontSize = 12.sp) }
    probe.report?.let { Text(it, color = Muted, fontSize = 12.sp) }
}

@Composable
private fun BackgroundPowerPanel() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var exempt by remember { mutableStateOf(PlaybackPowerSettings.isExempt(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exempt = PlaybackPowerSettings.isExempt(context)
                BackgroundAudioDiagnostics.record(context, "Power setting refreshed; batteryExempt=$exempt")
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Text("Работа при выключенном экране", fontSize = 21.sp, fontWeight = FontWeight.Medium)
    Text(if (exempt) "Android: NOIR в списке исключений из оптимизации батареи" else "Android: исключение из оптимизации батареи не разрешено", color = if (exempt) Accent else Muted, fontSize = 13.sp)
    Text("Если звук замирает при выключении экрана, разреши работу без оптимизации батареи. На vivo может также потребоваться разрешить фоновое энергопотребление в настройках NOIR.", color = Muted, fontSize = 13.sp)
    if (!exempt) Button(onClick = {
        error = if (PlaybackPowerSettings.requestExemption(context)) null else "Открой настройки телефона → NOIR → Батарея или фоновая работа"
    }, modifier = Modifier.fillMaxWidth()) { Text("Разрешить работу без оптимизации") }
    TextButton(onClick = {
        error = if (PlaybackPowerSettings.openAppSettings(context)) null else "Открой карточку NOIR в системных настройках телефона"
    }) { Text("Настройки NOIR на телефоне") }
    Text("Системный запрос нужно подтвердить. Этот статус относится к Android; отдельные ограничения vivo могут сохраняться.", color = Muted, fontSize = 12.sp)
    error?.let { Text(it, color = Accent, fontSize = 13.sp) }
}

@Composable
private fun InfoRow(label: String, value: String) { Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(label, color = Muted, fontSize = 12.sp); Text(value, color = Paper, fontSize = 15.sp) } }

@Composable
private fun VolumePanel() {
    val usbLocked by UsbPlaybackInterlock.active.collectAsStateWithLifecycle()
    val direct by DirectUsbSettings.enabled.collectAsStateWithLifecycle()
    val db by DirectUsbSettings.db.collectAsStateWithLifecycle()
    val manager = LocalContext.current.getSystemService(AudioManager::class.java)
    var volume by remember { mutableIntStateOf(manager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    LaunchedEffect(Unit) { while (true) { delay(300); volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC) } }
    Text("Громкость", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    if (direct) {
        Text("Аппаратная громкость ЦАПа · $db дБ", color = Muted)
        Slider(db.toFloat(), { DirectUsbSettings.setDb(kotlin.math.round(it).toInt()) }, valueRange = -50f..0f, steps = 49, enabled = !usbLocked)
        Text("Стартовый уровень −35 дБ. 0 дБ — максимальный уровень ЦАПа. В открытом приложении кнопки громкости телефона меняют этот уровень; с погашенным экраном используй ползунок после разблокировки.", color = Muted, fontSize = 13.sp)
    } else {
        Text("Уровень Android · $volume / $max", color = Muted)
        Slider(volume.toFloat(), { volume = it.toInt(); manager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0) }, valueRange = 0f..max.toFloat(), steps = (max - 1).coerceAtLeast(0), enabled = !usbLocked)
        Text("Без цифрового усиления. Для прямого USB используется отдельная аппаратная громкость ЦАПа.", color = Muted, fontSize = 13.sp)
    }
}

private fun timeLabel(ms: Long): String { val seconds = ms.coerceAtLeast(0) / 1000; return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60) }
private fun coverColor(bitmap: Bitmap): Color {
    val tiny = Bitmap.createScaledBitmap(bitmap, 12, 12, true)
    var r = 0L; var g = 0L; var b = 0L; var count = 0
    for (x in 0 until tiny.width) for (y in 0 until tiny.height) {
        val p = tiny.getPixel(x, y); val pr = android.graphics.Color.red(p); val pg = android.graphics.Color.green(p); val pb = android.graphics.Color.blue(p)
        if (maxOf(pr, pg, pb) - minOf(pr, pg, pb) > 28) { r += pr; g += pg; b += pb; count++ }
    }
    if (tiny !== bitmap) tiny.recycle()
    return if (count > 0) Color((r / count).toInt(), (g / count).toInt(), (b / count).toInt()) else Color(0xFF71978F)
}
