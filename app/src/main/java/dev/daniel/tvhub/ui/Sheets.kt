package dev.daniel.tvhub.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.daniel.tvhub.BuildConfig
import dev.daniel.tvhub.core.AppNames
import dev.daniel.tvhub.core.Conn
import dev.daniel.tvhub.core.Keys
import dev.daniel.tvhub.core.PhoneMedia
import dev.daniel.tvhub.core.PhoneMediaItem
import dev.daniel.tvhub.core.TvApp
import dev.daniel.tvhub.core.TvHub
import dev.daniel.tvhub.core.TvKind
import dev.daniel.tvhub.core.TvState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

// ---------------------------------------------------------------- Search

@Composable
fun ColumnScope.SearchSheet(hub: TvHub, tv: TvState, startWithVoice: Boolean, onDone: (searched: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val id = tv.config.id
    val appsMap by hub.apps.collectAsStateWithLifecycle()
    val pinsMap by hub.pins.collectAsStateWithLifecycle()
    val usageMap by hub.usageByTv.collectAsStateWithLifecycle()
    val recents by hub.recentSearches.collectAsStateWithLifecycle()
    val apps = remember(appsMap[id], pinsMap[id], usageMap[id]) {
        hub.ordered(id, appsMap[id]?.apps ?: emptyList(), pinsMap[id] ?: emptySet(), usageMap[id] ?: emptyMap())
            .filter { !AppNames.isUtility(it.packageName) }.take(14)
    }
    // If an app is open on the TV, searching inside it is usually what you want.
    val openApp = apps.firstOrNull { it.packageName == tv.now?.packageName }
    var scope by remember(id) { mutableStateOf(openApp) }
    var q by remember { mutableStateOf("") }
    var voiceNote by remember { mutableStateOf<String?>(null) }
    val voice = rememberVoice()
    val focus = remember { FocusRequester() }
    val isLink = hub.looksLikeLink(q)

    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        voice.stop()
        if (hub.looksLikeLink(t)) { hub.openLink(id, t); onDone(false) } else { hub.search(id, t, scope); onDone(true) }
    }

    fun listen() {
        voiceNote = null
        voice.start(
            onPartial = { q = it },
            onFinal = { q = it; submit(it) },
            onError = { voiceNote = it },
        )
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listen() else voiceNote = "Allow the microphone to search by voice"
    }
    fun mic() {
        if (voice.listening) { voice.stop(); return }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(Unit) {
        delay(200)
        if (startWithVoice) mic() else runCatching { focus.requestFocus() }
    }

    Text("Search ${tv.config.name}", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(14.dp))

    val pulse by animateFloatAsState(if (voice.listening) 1f + voice.level * 0.35f else 1f, label = "mic")
    Row(
        Modifier.fillMaxWidth().height(62.dp).clip(CircleShape).background(PanelHi)
            .border(1.5.dp, if (voice.listening) Brush.linearGradient(listOf(Violet, Pink)) else Brush.linearGradient(listOf(Line, Line)), CircleShape)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = TextLo)
        TextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            placeholder = { Text(if (voice.listening) "Listening…" else "Shows, movies, YouTube…", color = TextLo) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Pink,
            ),
            textStyle = LocalTextStyle.current.copy(fontSize = 18.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit(q) }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        Box(Modifier.scale(pulse)) { RoundIcon(HubIcons.Mic, if (voice.listening) "Stop listening" else "Voice search", size = 50.dp, brush = Glow) { mic() } }
    }
    voiceNote?.let { Text(it, color = DimAmber, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 18.dp, top = 6.dp)) }

    Spacer(Modifier.height(18.dp))
    if (isLink) {
        GlowButton("Open this link on ${tv.config.name}", Modifier.fillMaxWidth()) { submit(q) }
    } else {
        Text("SEARCH IN", style = MaterialTheme.typography.labelSmall, color = TextLo)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ScopeChip("Everywhere", null, scope == null) { scope = null } }
            items(apps, key = { it.packageName }) { app -> ScopeChip(app.name, app.icon, scope?.packageName == app.packageName) { scope = app } }
        }
        Text(
            when {
                scope != null -> "Opens ${scope?.name}, then types your search into it."
                tv.config.kind == TvKind.GOOGLE_TV -> "Uses Google TV's search across all your apps."
                else -> "Uses the Fire TV's search. For best results, pick an app."
            },
            style = MaterialTheme.typography.bodySmall, color = TextLo, modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        GlowButton(if (scope == null) "Search" else "Search ${scope?.name}", Modifier.fillMaxWidth(), enabled = q.isNotBlank() && tv.conn == Conn.ONLINE) { submit(q) }
    }

    if (recents.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("RECENT", style = MaterialTheme.typography.labelSmall, color = TextLo, modifier = Modifier.weight(1f))
            TextButton(onClick = { hub.clearSearches() }) { Text("Clear", color = TextLo) }
        }
        recents.take(6).forEach { r ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { q = r; submit(r) }.padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HubIcons.History, contentDescription = null, tint = TextLo, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(r, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ScopeChip(label: String, icon: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(CircleShape)
            .background(if (selected) Glow else Brush.linearGradient(listOf(PanelHi, PanelHi)))
            .clickable(onClick = onClick).padding(start = if (icon != null) 6.dp else 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AsyncImage(model = File(icon), contentDescription = null, modifier = Modifier.size(28.dp).clip(CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = if (selected) Color.White else TextHi, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

// ---------------------------------------------------------------- Remote

@Composable
fun ColumnScope.RemoteSheet(hub: TvHub, tv: TvState, hint: String?) {
    val id = tv.config.id
    var buttons by rememberSaveable { mutableStateOf(false) }
    val press: (Int) -> Unit = { hub.key(id, it) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Remote", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(tv); Spacer(Modifier.width(6.dp))
                Text(tv.config.name, color = TextLo, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Segmented(listOf("Touchpad", "Buttons"), if (buttons) 1 else 0) { buttons = it == 1 }
    }
    hint?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = Peach)
    }
    Spacer(Modifier.height(14.dp))
    if (buttons) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DPad(press) }
    } else {
        Touchpad(press, Modifier.fillMaxWidth().height(280.dp))
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        RoundIcon(HubIcons.Back, "Back", size = 52.dp) { press(Keys.BACK) }
        RoundIcon(HubIcons.Home, "Home", size = 52.dp) { press(Keys.HOME) }
        RoundIcon(if (tv.now?.playback == "Playing") HubIcons.Pause else HubIcons.Play, "Play or pause", size = 52.dp, brush = Glow) { press(Keys.PLAY_PAUSE) }
        RoundIcon(HubIcons.VolDown, "Volume down", size = 52.dp) { press(Keys.VOL_DOWN) }
        RoundIcon(HubIcons.VolUp, "Volume up", size = 52.dp) { press(Keys.VOL_UP) }
        RoundIcon(HubIcons.Mute, "Mute", size = 52.dp) { press(Keys.MUTE) }
    }
    if (hub.store.volumeKeysControlTv) {
        Text(
            "Tip: the phone's own volume buttons control ${tv.config.name}.",
            style = MaterialTheme.typography.bodySmall, color = TextLo, modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(CircleShape).background(PanelHi).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            Box(
                Modifier.clip(CircleShape).background(if (i == selected) Glow else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = if (i == selected) Color.White else TextLo) }
        }
    }
}

/** Swipe to move, tap to select, hold for the menu. Long swipes move several steps. */
@Composable
private fun Touchpad(press: (Int) -> Unit, modifier: Modifier) {
    val haptic = LocalHapticFeedback.current
    val step = with(LocalDensity.current) { 52.dp.toPx() }
    var lastDir by remember { mutableIntStateOf(0) }
    var flashes by remember { mutableIntStateOf(0) }
    val glow by animateFloatAsState(if (flashes % 2 == 1) 1f else 0f, label = "pad")
    val send: (Int) -> Unit = { code ->
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        lastDir = code
        flashes++
        press(code)
    }
    Box(
        modifier.clip(RoundedCornerShape(28.dp))
            .background(Brush.radialGradient(listOf(Violet.copy(alpha = 0.18f + glow * 0.12f), PanelHi)))
            .border(1.dp, Line, RoundedCornerShape(28.dp))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { send(Keys.CENTER) }, onLongPress = { send(Keys.MENU) })
            }
            .pointerInput(Unit) {
                var acc = Offset.Zero
                detectDragGestures(onDragStart = { acc = Offset.Zero }) { change, drag ->
                    change.consume()
                    acc += drag
                    while (abs(acc.x) > step || abs(acc.y) > step) {
                        if (abs(acc.x) >= abs(acc.y)) {
                            val right = acc.x > 0
                            send(if (right) Keys.RIGHT else Keys.LEFT)
                            acc = acc.copy(x = acc.x - if (right) step else -step)
                        } else {
                            val down = acc.y > 0
                            send(if (down) Keys.DOWN else Keys.UP)
                            acc = acc.copy(y = acc.y - if (down) step else -step)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val arrow = when (lastDir) {
            Keys.UP -> "↑"; Keys.DOWN -> "↓"; Keys.LEFT -> "←"; Keys.RIGHT -> "→"; Keys.CENTER -> "OK"; Keys.MENU -> "Menu"; else -> ""
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Glow).scale(0.9f + glow * 0.1f), contentAlignment = Alignment.Center) {
                Text(arrow.ifEmpty { "OK" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (arrow.length > 2) 15.sp else 26.sp)
            }
            Spacer(Modifier.height(14.dp))
            Text("Swipe to move · tap to select · hold for menu", color = TextLo, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DPad(press: (Int) -> Unit) {
    Box(Modifier.size(260.dp).clip(CircleShape).background(PanelHi).border(1.dp, Line, CircleShape), contentAlignment = Alignment.Center) {
        DArrow(Icons.Default.KeyboardArrowUp, "Up", Modifier.align(Alignment.TopCenter).padding(top = 10.dp)) { press(Keys.UP) }
        DArrow(Icons.Default.KeyboardArrowDown, "Down", Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) { press(Keys.DOWN) }
        DArrow(Icons.Default.KeyboardArrowLeft, "Left", Modifier.align(Alignment.CenterStart).padding(start = 10.dp)) { press(Keys.LEFT) }
        DArrow(Icons.Default.KeyboardArrowRight, "Right", Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)) { press(Keys.RIGHT) }
        Box(Modifier.size(100.dp).clip(CircleShape).background(Glow).clickable { press(Keys.CENTER) }, contentAlignment = Alignment.Center) {
            Text("OK", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)
        }
    }
}

@Composable
private fun DArrow(icon: ImageVector, desc: String, modifier: Modifier, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.size(64.dp).clip(CircleShape).clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = desc, tint = TextHi, modifier = Modifier.size(44.dp)) }
}

// ---------------------------------------------------------------- Type

@Composable
fun ColumnScope.TypeSheet(hub: TvHub, tv: TvState) {
    val id = tv.config.id
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); runCatching { focus.requestFocus() } }

    Text("Type on ${tv.config.name}", style = MaterialTheme.typography.headlineSmall)
    Text("Goes into whatever text box is selected on the TV — logins, search boxes, codes.", color = TextLo, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        value = text, onValueChange = { text = it }, singleLine = true, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { hub.text(id, text); text = "" }),
        placeholder = { Text("Type here", color = TextLo) },
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        GlowButton("Send to TV", Modifier.weight(1f), enabled = text.isNotEmpty()) { hub.text(id, text); text = "" }
        OutlinedButton(onClick = { hub.key(id, Keys.DEL) }, modifier = Modifier.height(48.dp)) { Text("⌫  Delete") }
        OutlinedButton(onClick = { hub.key(id, Keys.ENTER) }, modifier = Modifier.height(48.dp)) { Text("Enter") }
    }
}

// ---------------------------------------------------------------- Photos

@Composable
fun ColumnScope.PhotosSheet(hub: TvHub, tv: TvState) {
    val ctx = LocalContext.current
    val id = tv.config.id
    var granted by remember { mutableStateOf(PhoneMedia.hasPermission(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = PhoneMedia.hasPermission(ctx) }
    var items by remember { mutableStateOf<List<PhoneMediaItem>?>(null) }
    LaunchedEffect(granted) { if (granted) items = withContext(Dispatchers.IO) { PhoneMedia.recent(ctx) } }
    val ready = (tv.receiverVersion ?: -1) >= BuildConfig.VERSION_CODE
    val casting = tv.now?.packageName == BuildConfig.APPLICATION_ID

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Photos & videos", style = MaterialTheme.typography.headlineSmall)
            Text(
                when {
                    tv.busy != null -> tv.busy
                    tv.conn != Conn.ONLINE -> "${tv.config.name} isn't connected"
                    !ready -> "The first one installs TV Hub's helper on ${tv.config.name} (one time)"
                    else -> "Tap one to show it on ${tv.config.name}"
                },
                color = TextLo, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (casting) OutlinedButton(onClick = { hub.stopCast(id) }) { Text("Stop") }
    }
    Spacer(Modifier.height(12.dp))
    val list = items
    Box(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 460.dp)) {
        when {
            !granted -> Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Let TV Hub see this phone's photos and videos.", color = TextLo)
                Spacer(Modifier.height(12.dp))
                GlowButton("Allow photos & videos") { ask.launch(PhoneMedia.permissions()) }
            }
            list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Pink)
            list.isEmpty() -> Text("No photos or videos on this phone yet.", color = TextLo, modifier = Modifier.align(Alignment.Center))
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(list, key = { it.uri.toString() }) { item ->
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(PanelHi)
                            .clickable(enabled = tv.conn == Conn.ONLINE && tv.busy == null) { hub.cast(id, item) },
                    ) {
                        AsyncImage(model = item.uri, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (item.isVideo) {
                            val secs = item.durationMs / 1000
                            Text(
                                "▶ %d:%02d".format(secs / 60, secs % 60), color = Color.White, style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
