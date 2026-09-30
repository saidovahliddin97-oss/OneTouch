package app.onetouch

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF0B0D12)
private val CardBg = Color(0xFF151923)
private val Accent = Color(0xFF4F8CFF)
private val Ok = Color(0xFF2FBF71)

/** Fingers must close to 60% of their starting distance to count as a pinch. */
private const val PINCH_THRESHOLD = 0.6f

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        OneTouchService.start(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = CardBg)) {
                Screen(resumeTick.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        if (Bus.desktop.value == null) OneTouchService.refresh(this)
    }
}

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS,
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun hasMedia(ctx: Context): Boolean {
    fun g(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 34 -> g(Manifest.permission.READ_MEDIA_IMAGES) || g(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> g(Manifest.permission.READ_MEDIA_IMAGES)
        else -> g(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun sendItem(ctx: Context, item: MediaItem) {
    OneTouchService.send(ctx, listOf(Media.job(ctx, item.uri)))
}

@Composable
private fun Screen(tick: Int) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasMedia(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasMedia(ctx)
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(mediaPermissions()) }

    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(granted, tick) {
        if (granted) items = withContext(Dispatchers.IO) { Media.recent(ctx) }
    }
    LaunchedEffect(Unit) {
        Bus.toasts.collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    }

    val desktop by Bus.desktop.collectAsState()
    val searching by Bus.searching.collectAsState()
    val sent by Bus.sent.collectAsState()
    val sending by Bus.sending.collectAsState()
    var viewer by remember { mutableStateOf<MediaItem?>(null) }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Header(desktop, searching) { OneTouchService.refresh(ctx) }
            BatteryCard(tick)
            Text(
                "🤏 Сведите два пальца на фото — и оно на Mac.\nИли в любой галерее: Поделиться → «На Mac».",
                color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (!granted) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = {
                        launcher.launch(mediaPermissions())
                        if (!hasMedia(ctx)) ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                    }) { Text("Разрешить доступ к фото") }
                }
            } else {
                PinchGrid(items, sent, sending, onOpen = { viewer = it }, onPinch = { sendItem(ctx, it) })
            }
        }
        viewer?.let { item ->
            Viewer(item, onClose = { viewer = null }, onSend = { sendItem(ctx, item); viewer = null })
        }
    }
    BackHandler(enabled = viewer != null) { viewer = null }
}

@Composable
private fun Header(desktop: Peer?, searching: Boolean, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("OneTouch", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.weight(1f))
        val (dot, label) = when {
            desktop != null -> Ok to desktop.name
            searching -> Color.Gray to "Ищу Mac…"
            else -> Color(0xFFFF9F43) to "Mac не найден ↻"
        }
        Row(
            Modifier.clip(RoundedCornerShape(99.dp)).background(CardBg).clickable(onClick = onRefresh)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, fontSize = 14.sp)
        }
    }
}

/** Asks once to exempt us from battery optimization so offers arrive with the screen off. */
@SuppressLint("BatteryLife")
@Composable
private fun BatteryCard(tick: Int) {
    val ctx = LocalContext.current
    val ignoring = remember(tick) {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    if (ignoring) return
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Чтобы файлы с Mac приходили и при выключенном экране, разрешите OneTouch работать в фоне. В простое он не тратит батарею.", color = Color.White, fontSize = 13.sp)
            TextButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Разрешить") }
        }
    }
}

private object Thumbs {
    val cache = LruCache<Uri, ImageBitmap>(400)
}

@Composable
private fun Thumb(uri: Uri, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState(Thumbs.cache.get(uri), uri) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                Media.thumbnail(ctx, uri, 320)?.asImageBitmap()?.also { Thumbs.cache.put(uri, it) }
            }
        }
    }
    Box(modifier.background(CardBg)) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/**
 * Tracks a two-finger pinch without disturbing one-finger scrolling/taps.
 * [onStart] gets the centroid, [onScale] the current distance ratio, and
 * [onEnd] whether the fingers closed past [PINCH_THRESHOLD].
 */
private suspend fun PointerInputScope.detectPinchIn(
    onStart: (Offset) -> Unit,
    onScale: (Float, Boolean) -> Unit,
    onEnd: (Boolean) -> Unit,
) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    var d0 = 0f
    var armed = false
    var pinched = false
    do {
        val ev = awaitPointerEvent(PointerEventPass.Initial)
        val down = ev.changes.filter { it.pressed }
        if (down.size >= 2) {
            val a = down[0].position
            val b = down[1].position
            val d = (a - b).getDistance()
            if (d0 == 0f) {
                d0 = d.coerceAtLeast(1f)
                pinched = true
                onStart((a + b) / 2f)
            }
            val s = (d / d0).coerceIn(0.3f, 1.1f)
            armed = s < PINCH_THRESHOLD
            onScale(s, armed)
            ev.changes.forEach { it.consume() } // stop the grid from scrolling / tiles from clicking
        }
    } while (ev.changes.any { it.pressed })
    if (pinched) onEnd(armed)
}

@Composable
private fun PinchGrid(
    items: List<MediaItem>, sent: Set<Uri>, sending: Uri?,
    onOpen: (MediaItem) -> Unit, onPinch: (MediaItem) -> Unit,
) {
    val state = rememberLazyGridState()
    val haptic = LocalHapticFeedback.current
    var pinchIndex by remember { mutableIntStateOf(-1) }
    var pinchScale by remember { mutableFloatStateOf(1f) }
    var wasArmed by remember { mutableStateOf(false) }
    val currentItems by rememberUpdatedState(items)
    val currentOnPinch by rememberUpdatedState(onPinch)

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectPinchIn(
                onStart = { c -> pinchIndex = itemAt(state, c) },
                onScale = { s, armed ->
                    if (armed && !wasArmed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    wasArmed = armed
                    pinchScale = s
                },
                onEnd = { armed ->
                    val i = pinchIndex
                    if (armed && i in currentItems.indices) currentOnPinch(currentItems[i])
                    pinchIndex = -1
                    pinchScale = 1f
                    wasArmed = false
                },
            )
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(items, key = { _, it -> it.uri.toString() }) { i, item ->
                val target = if (i == pinchIndex) pinchScale else 1f
                val s by animateFloatAsState(target, label = "pinch")
                Box(
                    Modifier.aspectRatio(1f)
                        .graphicsLayer { scaleX = s; scaleY = s }
                        .clip(RoundedCornerShape(if (s < 0.98f) 18.dp else 2.dp))
                        .clickable { onOpen(item) },
                ) {
                    Thumb(item.uri, Modifier.fillMaxSize())
                    if (item.isVideo) Text("▶", color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
                    when {
                        item.uri == sending -> Badge("↑", Accent)
                        item.uri in sent -> Badge("✓", Ok)
                    }
                    if (i == pinchIndex && pinchScale < PINCH_THRESHOLD) {
                        Box(Modifier.fillMaxSize().background(Accent.copy(alpha = 0.35f)))
                    }
                }
            }
        }
    }
}

private fun itemAt(state: LazyGridState, c: Offset): Int =
    state.layoutInfo.visibleItemsInfo.firstOrNull {
        c.x >= it.offset.x && c.x < it.offset.x + it.size.width &&
            c.y >= it.offset.y && c.y < it.offset.y + it.size.height
    }?.index ?: -1

@Composable
private fun Badge(text: String, color: Color) {
    Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.TopEnd) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Viewer(item: MediaItem, onClose: () -> Unit, onSend: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val bmp by produceState<ImageBitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) { Media.large(ctx, item)?.asImageBitmap() }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var wasArmed by remember { mutableStateOf(false) }
    val currentOnSend by rememberUpdatedState(onSend)
    val shown by animateFloatAsState(scale, label = "viewer")

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectPinchIn(
                onStart = {},
                onScale = { s, armed ->
                    if (armed && !wasArmed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    wasArmed = armed
                    scale = s
                },
                onEnd = { armed ->
                    scale = 1f
                    wasArmed = false
                    if (armed) currentOnSend()
                },
            )
        },
    ) {
        bmp?.let {
            Image(it, null, Modifier.fillMaxSize().graphicsLayer { scaleX = shown; scaleY = shown }, contentScale = ContentScale.Fit)
        }
        Text(
            if (scale < PINCH_THRESHOLD) "Отпустите — отправлю на Mac" else "Сведите два пальца — отправить на Mac",
            color = Color.White, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(top = 16.dp)
                .clip(RoundedCornerShape(99.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 14.dp, vertical = 6.dp),
        )
        Row(
            Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onClose) { Text("Закрыть") }
            Button(onClick = onSend) { Text("На Mac") }
        }
    }
}
