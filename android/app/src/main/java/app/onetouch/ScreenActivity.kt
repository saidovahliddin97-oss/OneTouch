package app.onetouch

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Watch and control the Mac. Video: H.264 from VideoToolbox decoded by
 * MediaCodec straight onto a TextureView. Gestures:
 *  tap = click · double tap = double click · drag = drag / select ·
 *  long press = right click · two fingers = scroll · pinch = zoom the picture.
 */
class ScreenActivity : ComponentActivity() {
    private val ui = ScreenUi()
    private var client: ScreenClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val peer = currentDesktop(this)
        setContent {
            MaterialTheme(colorScheme = oneTouchColors()) {
                ScreenView(
                    ui = ui,
                    peerName = peer?.name ?: "Mac",
                    onSurface = { surface ->
                        if (peer == null) ui.error = "Mac не найден. Откройте OneTouch на Mac."
                        else client = ScreenClient(this, peer, surface, ui).also { it.start() }
                    },
                    onSurfaceGone = { client?.stop(); client = null },
                    send = { t, o -> client?.send(t, o) },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        client?.stop()
        super.onDestroy()
    }
}

/** Observable state shared between the network thread and Compose. */
class ScreenUi {
    var connected by mutableStateOf(false)
    var videoW by mutableStateOf(16)
    var videoH by mutableStateOf(10)
    var control by mutableStateOf(true)
    var status by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
}

/** Network + decoder thread for one screen session. */
class ScreenClient(
    private val ctx: android.content.Context,
    private val peer: Peer,
    private val surface: Surface,
    private val ui: ScreenUi,
) {
    @Volatile private var session: Session? = null
    @Volatile private var stopped = false
    private var codec: MediaCodec? = null
    private var lastConfig: ByteArray? = null
    private var w = 0
    private var h = 0
    private val info = MediaCodec.BufferInfo()

    fun start() = thread(name = "screen-session") {
        try {
            val s = Session.open(ctx, peer, "screen")
            session = s
            ui.connected = true
            while (!stopped) {
                val (type, p) = s.read()
                when (type) {
                    Session.HELLO -> {
                        val o = JSONObject(String(p))
                        w = o.getInt("w"); h = o.getInt("h")
                        ui.videoW = w; ui.videoH = h
                        ui.control = o.optBoolean("control", true)
                    }
                    Session.CONFIG -> if (!p.contentEquals(lastConfig)) {
                        releaseCodec()
                        codec = createDecoder(p)
                        lastConfig = p
                    }
                    Session.FRAME -> codec?.let { feed(it, p) }
                    Session.STATUS -> ui.status = JSONObject(String(p)).optString("text")
                    Session.OFFER -> {
                        val offer = Offer.parse(String(p), peer.host)
                        ui.status = "Получаю ${offer.files.first().name}…"
                        thread {
                            ui.status = try {
                                Receiver.receive(ctx, offer)
                                "✓ ${offer.files.first().name} — в Галерее (альбом OneTouch)"
                            } catch (e: Exception) {
                                "Не удалось получить: ${e.message}"
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!stopped) ui.error = e.message ?: "Соединение потеряно"
        } finally {
            ui.connected = false
            releaseCodec()
            session?.close()
        }
    }

    fun send(type: Int, obj: JSONObject) {
        val s = session ?: return
        thread { runCatching { s.json(type, obj) } }
    }

    fun stop() {
        stopped = true
        session?.close()
    }

    private fun createDecoder(config: ByteArray): MediaCodec? {
        val nals = splitNals(config)
        val sps = nals.firstOrNull { it.isNotEmpty() && it[0].toInt() and 0x1f == 7 } ?: return null
        val pps = nals.firstOrNull { it.isNotEmpty() && it[0].toInt() and 0x1f == 8 } ?: return null
        val start = byteArrayOf(0, 0, 0, 1)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w.coerceAtLeast(16), h.coerceAtLeast(16)).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(start + sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(start + pps))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (w * h).coerceAtLeast(1 shl 20))
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        return runCatching {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(fmt, surface, null, 0)
                start()
            }
        }.onFailure { ui.error = "Декодер видео недоступен: ${it.message}" }.getOrNull()
    }

    private fun feed(c: MediaCodec, p: ByteArray) {
        if (p.size < 2) return
        val key = p[0].toInt() == 1
        try {
            val idx = c.dequeueInputBuffer(20_000)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                if (buf.capacity() < p.size - 1) {
                    c.queueInputBuffer(idx, 0, 0, 0, 0)
                    send(Session.COMMAND, JSONObject().put("cmd", "keyframe"))
                    return
                }
                buf.put(p, 1, p.size - 1)
                c.queueInputBuffer(idx, 0, p.size - 1, System.nanoTime() / 1000, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            }
            while (true) {
                val o = c.dequeueOutputBuffer(info, 0)
                if (o >= 0) c.releaseOutputBuffer(o, true)
                else if (o != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) break
            }
        } catch (e: IllegalStateException) {
            send(Session.COMMAND, JSONObject().put("cmd", "keyframe"))
        }
    }

    private fun releaseCodec() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        lastConfig = null
    }
}

@Composable
private fun ScreenView(
    ui: ScreenUi,
    peerName: String,
    onSurface: (Surface) -> Unit,
    onSurfaceGone: () -> Unit,
    send: (Int, JSONObject) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var keyboard by remember { mutableStateOf(false) }
    val send by rememberUpdatedState(send)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) {
            OneTouchService.send(ctx, uris.map { Media.job(ctx, it) })
            ui.status = "Отправляю на $peerName… (появится на рабочем столе и в буфере ⌘V)"
        }
    }

    LaunchedEffect(ui.status) {
        if (ui.status != null) { delay(4500); ui.status = null }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val aspect = ui.videoW.toFloat() / ui.videoH
        val (vw, vh) = if (boxW / boxH > aspect) boxH * aspect to boxH else boxW to boxW / aspect
        val left = (boxW - vw) / 2
        val top = (boxH - vh) / 2

        // Screen point → normalized Mac coordinates, undoing the local zoom/pan.
        fun toMac(p: Offset): Pair<Double, Double> {
            val cx = boxW / 2; val cy = boxH / 2
            val qx = (p.x - cx - pan.x) / zoom + cx
            val qy = (p.y - cy - pan.y) / zoom + cy
            return ((qx - left) / vw).toDouble().coerceIn(0.0, 1.0) to ((qy - top) / vh).toDouble().coerceIn(0.0, 1.0)
        }
        fun input(t: String, p: Offset) {
            val (x, y) = toMac(p)
            send(Session.INPUT, JSONObject().put("t", t).put("x", x).put("y", y))
        }
        fun clampPan(o: Offset): Offset {
            val mx = vw * (zoom - 1) / 2; val my = vh * (zoom - 1) / 2
            return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }

        AndroidView(
            factory = { c ->
                TextureView(c).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = onSurface(Surface(st))
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { onSurfaceGone(); return true }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { vw.toDp() }, with(density) { vh.toDp() })
                .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y },
        )

        // Gesture layer over the whole screen.
        Box(
            Modifier.fillMaxSize().pointerInput(vw, vh) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = down.position
                    var dragging = false
                    var longPressed = false
                    var multi = false
                    var prevCentroid = Offset.Unspecified
                    var prevDist = 0f
                    var lastMove = 0L
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val ev = if (!dragging && !multi && !longPressed) {
                            withTimeoutOrNull(550) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (ev == null) { // long press → right click
                            longPressed = true
                            input("right", start)
                            continue
                        }
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            if (dragging) { input("up", pressed[0].position); dragging = false }
                            multi = true
                            val a = pressed[0].position; val b = pressed[1].position
                            val centroid = (a + b) / 2f
                            val dist = (a - b).getDistance()
                            if (prevCentroid != Offset.Unspecified && prevDist > 0f) {
                                val ratio = dist / prevDist
                                if (abs(ratio - 1f) > 0.004f) zoom = (zoom * ratio).coerceIn(1f, 5f)
                                val d = centroid - prevCentroid
                                if (zoom > 1.01f) {
                                    pan = clampPan(pan + d)
                                } else {
                                    pan = Offset.Zero
                                    if (abs(ratio - 1f) < 0.02f) {
                                        send(Session.INPUT, JSONObject().put("t", "scroll").put("dx", (d.x * 1.5).toInt()).put("dy", (d.y * 1.5).toInt()))
                                    }
                                }
                            }
                            prevCentroid = centroid
                            prevDist = dist
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        if (multi) {
                            if (pressed.isEmpty()) break
                            prevCentroid = Offset.Unspecified
                            continue
                        }
                        val c = ev.changes.first()
                        if (!c.pressed) {
                            when {
                                longPressed -> {}
                                dragging -> input("up", c.position)
                                else -> { input("move", start); input("down", start); input("up", start) }
                            }
                            break
                        }
                        if (!dragging && !longPressed && (c.position - start).getDistance() > slop) {
                            dragging = true
                            input("move", start)
                            input("down", start)
                        }
                        if (dragging) {
                            val now = System.currentTimeMillis()
                            if (now - lastMove >= 16) { lastMove = now; input("move", c.position) }
                        }
                        c.consume()
                    }
                }
            },
        )

        // Top toolbar.
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                .clip(RoundedCornerShape(99.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolButton(Icons.Outlined.Close, "Закрыть", onClose)
            Text(peerName, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
            ToolButton(Icons.Outlined.Keyboard, "Клавиатура") { keyboard = !keyboard }
            ToolButton(Icons.Outlined.CloudDownload, "Забрать") {
                send(Session.COMMAND, JSONObject().put("cmd", "grab"))
                ui.status = "Забираю файлы, выделенные в Finder…"
            }
            ToolButton(Icons.Outlined.PhotoLibrary, "На Mac") {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
            if (zoom > 1.01f) ToolButton(Icons.Outlined.ZoomOutMap, "1:1") { zoom = 1f; pan = Offset.Zero }
        }

        if (keyboard) {
            KeyboardBar(
                Modifier.align(Alignment.BottomCenter),
                onKey = { k -> send(Session.INPUT, JSONObject().put("t", "key").put("k", k)) },
                onText = { s -> send(Session.INPUT, JSONObject().put("t", "text").put("s", s)) },
            )
        }

        // Connection state / messages.
        when {
            ui.error != null -> Message(Modifier.align(Alignment.Center), ui.error!!, action = "Закрыть", onAction = onClose)
            !ui.connected || ui.videoW == 16 -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.size(12.dp))
                Text("Подключаюсь к $peerName…\nНа Mac может появиться запрос — нажмите «Разрешить».",
                    color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        AnimatedVisibility(ui.status != null, Modifier.align(Alignment.BottomCenter).padding(bottom = if (keyboard) 70.dp else 20.dp),
            enter = fadeIn(), exit = fadeOut()) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xEE151923)) {
                Text(ui.status.orEmpty(), color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        if (!ui.control && ui.connected) {
            Text("Только просмотр — разрешите управление на Mac", color = Color(0xFFFFB86B), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun Message(modifier: Modifier, text: String, action: String, onAction: () -> Unit) {
    Surface(modifier.padding(32.dp), shape = RoundedCornerShape(16.dp), color = Color(0xFF151923)) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.size(12.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

/** Mac keys plus a hidden text field that forwards what you type on the phone keyboard. */
@Composable
private fun KeyboardBar(modifier: Modifier, onKey: (String) -> Unit, onText: (String) -> Unit) {
    val baseline = "  "
    var value by remember { mutableStateOf(TextFieldValue(baseline, TextRange(baseline.length))) }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus(); kb?.show() }
    Column(modifier.background(Color(0xF0151923))) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("esc" to "Esc", "tab" to "Tab", "cmd+c" to "⌘C", "cmd+v" to "⌘V", "cmd+z" to "⌘Z", "cmd+a" to "⌘A",
                "cmd+space" to "⌘␣", "left" to "←", "up" to "↑", "down" to "↓", "right" to "→", "backspace" to "⌫", "return" to "↵")
                .forEach { (k, label) ->
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF232A38), onClick = { onKey(k) }) {
                        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
        }
        BasicTextField(
            value = value,
            onValueChange = { v ->
                val old = value.text
                val new = v.text
                when {
                    new.length > old.length && new.startsWith(old) -> onText(new.substring(old.length))
                    new.length < old.length -> repeat(old.length - new.length) { onKey("backspace") }
                    new != old -> onText(new.trim())
                }
                value = TextFieldValue(baseline, TextRange(baseline.length)) // keep a stable baseline for the next diff
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onKey("return") }),
            modifier = Modifier.size(1.dp).focusRequester(focus),
        )
    }
}
