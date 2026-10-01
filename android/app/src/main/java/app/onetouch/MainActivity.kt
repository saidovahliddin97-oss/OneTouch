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
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopMac
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import android.app.Activity
import android.media.projection.MediaProjectionManager
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


/** Fingers must close to 60% of their starting distance to count as a pinch. */
private const val PINCH_THRESHOLD = 0.6f

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        OneTouchService.start(this)
        setContent {
            MaterialTheme(colorScheme = oneTouchColors()) {
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
    val mirroringTo by Bus.mirroringTo.collectAsState()
    var viewer by remember { mutableStateOf<MediaItem?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Scaffold(
            containerColor = Bg,
            topBar = { Header(desktop, searching) { OneTouchService.refresh(ctx) } },
            bottomBar = {
                NavigationBar(containerColor = CardBg) {
                    NavigationBarItem(
                        selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(Icons.Outlined.PhotoLibrary, null) }, label = { Text("Файлы") },
                    )
                    NavigationBarItem(
                        selected = tab == 1, onClick = { tab = 1 },
                        icon = { Icon(Icons.Outlined.ScreenShare, null) }, label = { Text("Экран") },
                    )
                }
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                if (tab == 0) {
                    BatteryCard(tick)
                    Hint(Icons.Outlined.Gesture, "Сведите два пальца на фото — и оно на ${desktop?.name ?: "Mac"}. Из любого приложения: Поделиться → «На Mac» или плавающая кнопка OneTouch.")
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
                } else {
                    ScreenTab(desktop, mirroringTo)
                }
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
    val ctx = LocalContext.current
    val desktops by Bus.desktops.collectAsState()
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF5C94FF), Color(0xFF335CF2)))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.SyncAlt, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(10.dp))
        Text("OneTouch", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Spacer(Modifier.weight(1f))
        val (dot, label) = when {
            desktop != null -> Ok to desktop.name
            searching -> Muted to "Ищу…"
            else -> Warn to "Компьютер не найден"
        }
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(99.dp)).background(CardBg)
                    .clickable { menu = true; OneTouchService.scan(ctx) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Laptop, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.widthIn(max = 150.dp))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                Icon(Icons.Outlined.ArrowDropDown, null, tint = Muted, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = CardBg2) {
                Text("Куда отправлять", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                val list = (desktops + listOfNotNull(desktop)).distinctBy { it.id }
                list.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        leadingIcon = { Icon(if (p.os == "windows") Icons.Outlined.DesktopWindows else Icons.Outlined.Laptop, null) },
                        trailingIcon = { if (p.id == desktop?.id) Icon(Icons.Outlined.Check, null, tint = Accent) },
                        onClick = { OneTouchService.select(ctx, p); menu = false },
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (searching) "Ищу компьютеры…" else "Обновить список") },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                    onClick = { OneTouchService.scan(ctx); onRefresh() },
                )
            }
        }
    }
}

@Composable
private fun Hint(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Muted, fontSize = 13.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun ScreenTab(desktop: Peer?, mirroringTo: String?) {
    val ctx = LocalContext.current
    val name = desktop?.name ?: "Mac"
    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) MirrorService.start(ctx, res.resultCode, data)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FeatureCard(
            icon = Icons.Outlined.DesktopMac,
            title = "Экран $name на телефоне",
            text = "Смотрите и управляйте: тап — клик, перетаскивание — выделение, долгое нажатие — правый клик, " +
                "два пальца — прокрутка, щипок — зум. Выделили файл в Finder — «Забрать», и он уже здесь.",
            button = "Открыть экран",
            enabled = desktop != null,
        ) { ctx.startActivity(Intent(ctx, ScreenActivity::class.java)) }

        FeatureCard(
            icon = Icons.Outlined.PhoneAndroid,
            title = if (mirroringTo != null) "Экран транслируется на $mirroringTo" else "Экран телефона на $name",
            text = "Ваш экран появится в окне на Mac. Перетащите файл в это окно — он сразу окажется в Галерее телефона.",
            button = if (mirroringTo != null) "Остановить" else "Показать на $name",
            enabled = desktop != null || mirroringTo != null,
            active = mirroringTo != null,
        ) {
            if (mirroringTo != null) {
                MirrorService.stop(ctx)
            } else {
                projection.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            }
        }

        ControlCard()

        Hint(Icons.Outlined.Info, "В первый раз Mac спросит разрешение. Для управления включите OneTouch на Mac в «Запись экрана» и «Универсальный доступ» (значок ⇄ в строке меню подскажет).")
        if (desktop == null) Hint(Icons.Outlined.WifiOff, "Mac не найден: запустите OneTouch на Mac в этой же Wi‑Fi сети.")
    }
}

/** Accessibility service: control from the Mac + floating button over any app. */
@Composable
private fun ControlCard() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("onetouch", Context.MODE_PRIVATE) }
    var tick by remember { mutableIntStateOf(0) }
    val enabled = remember(tick) { ControlService.isEnabled(ctx) }
    var bubble by remember { mutableStateOf(prefs.getBoolean("bubble", true)) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); tick++ } }
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x334F8CFF)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.TouchApp, null, tint = Accent, modifier = Modifier.size(26.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Управление и плавающая кнопка", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (enabled) "Включено" else "Выключено", color = if (enabled) Ok else Warn, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.size(10.dp))
            Text(
                "• Управляйте телефоном мышью и клавиатурой Mac во время трансляции.\n" +
                    "• Кнопка OneTouch поверх любых приложений: нажали — снимок экрана на компьютер, удержали — последнее фото.",
                color = Muted, fontSize = 13.sp, lineHeight = 18.sp,
            )
            Spacer(Modifier.size(12.dp))
            if (!enabled) {
                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Включить: Спец. возможности → OneTouch — управление")
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    "Если переключатель серый: Настройки → Приложения → OneTouch → ⋮ (вверху справа) → «Разрешить ограниченные настройки», затем снова сюда.",
                    color = Muted, fontSize = 12.sp, lineHeight = 16.sp,
                )
                TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                }) { Text("Открыть настройки OneTouch") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Плавающая кнопка", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = bubble, onCheckedChange = {
                        bubble = it
                        prefs.edit().putBoolean("bubble", it).apply()
                        ControlService.instance?.showBubble(it)
                    })
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(
    icon: ImageVector, title: String, text: String, button: String,
    enabled: Boolean, active: Boolean = false, onClick: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (active) Color(0x33FF5D5D) else Color(0x334F8CFF)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = if (active) Color(0xFFFF5D5D) else Accent, modifier = Modifier.size(26.dp)) }
            Spacer(Modifier.size(14.dp))
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(6.dp))
            Text(text, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.size(14.dp))
            if (active) {
                OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(button) }
            } else {
                Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(button) }
            }
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2214)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
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
