@file:OptIn(ExperimentalFoundationApi::class)

package dev.daniel.tvhub.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.daniel.tvhub.BuildConfig
import dev.daniel.tvhub.core.AppNames
import dev.daniel.tvhub.core.Conn
import dev.daniel.tvhub.core.GuideItem
import dev.daniel.tvhub.core.RecentItem
import dev.daniel.tvhub.core.Keys
import dev.daniel.tvhub.core.Power
import dev.daniel.tvhub.core.TvApp
import dev.daniel.tvhub.core.TvHub
import dev.daniel.tvhub.core.TvState
import java.io.File
import kotlin.math.abs

/** One TV's home: what's on, search, favorites, your guide, then every app. */
@Composable
fun HomePage(hub: TvHub, tv: TvState, onSearch: () -> Unit, onVoice: () -> Unit, onAddGuide: () -> Unit) {
    val id = tv.config.id
    val appsMap by hub.apps.collectAsStateWithLifecycle()
    val pinsMap by hub.pins.collectAsStateWithLifecycle()
    val usageMap by hub.usageByTv.collectAsStateWithLifecycle()
    val guide by hub.guide.collectAsStateWithLifecycle()
    val recents by hub.recents.collectAsStateWithLifecycle()
    var removeRecent by remember { mutableStateOf<RecentItem?>(null) }
    val online = tv.conn == Conn.ONLINE
    LaunchedEffect(id, online, tv.receiverVersion) { if (online) hub.ensureApps(id) }

    var editing by rememberSaveable(id) { mutableStateOf(false) }
    var removeGuide by remember { mutableStateOf<GuideItem?>(null) }

    val st = appsMap[id]
    val pins = pinsMap[id] ?: emptyList()
    val byPkg = remember(st?.apps) { st?.apps?.associateBy { it.packageName } ?: emptyMap() }
    val favorites = remember(pins, byPkg) { pins.mapNotNull { byPkg[it] } }
    val ordered = remember(st?.apps, pins, usageMap[id], editing) {
        hub.ordered(id, st?.apps ?: emptyList(), if (editing) emptyList() else pins, usageMap[id] ?: emptyMap())
            .filter { editing || it.packageName !in pins }
    }
    val current = st?.apps?.firstOrNull { it.packageName == tv.now?.packageName }
    val helperMissing = online && (tv.receiverVersion ?: 0) < BuildConfig.VERSION_CODE && tv.receiverVersion != null
    val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 128.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "hero", span = full) { NowPlayingHero(hub, tv, current) }
        item(key = "search", span = full) { SearchPill(tv.config.name, onSearch, onVoice) }
        if (helperMissing) item(key = "setup", span = full) { SetupCard(hub, tv) }

        // ----- Favorites
        item(key = "favHead", span = full) {
            SectionHead(
                "Favorites",
                if (editing) "Tap ☆ on any app below to add or remove it" else "Your go-to apps on ${tv.config.name}",
            ) {
                TextButton(onClick = { editing = !editing }) {
                    Text(if (editing) "Done" else "Edit", color = if (editing) Pink else TextLo, fontWeight = FontWeight.Bold)
                }
            }
        }
        item(key = "favRow", span = full) {
            if (favorites.isEmpty()) {
                DashedCard(
                    if (editing) "Tap the ☆ on apps below to make them favorites" else "Pick your favorite apps",
                    "They'll sit right here, one tap away.",
                ) { editing = true }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 8.dp)) {
                    itemsIndexed(favorites, key = { _, a -> a.packageName }) { i, app ->
                        Box(Modifier.width(250.dp)) {
                            AppTile(
                                app, isOpen = tv.now?.packageName == app.packageName && tv.power != Power.OFF,
                                pinned = false, editing = false, enabled = online,
                                onClick = { hub.launchApp(id, app) }, onLongClick = { editing = true },
                            )
                            if (editing) {
                                Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (i > 0) MiniButton("◀") { hub.movePin(id, app.packageName, -1) }
                                    if (i < favorites.size - 1) MiniButton("▶") { hub.movePin(id, app.packageName, 1) }
                                    MiniButton("✕") { hub.togglePin(id, app.packageName) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ----- Guide
        if (recents.isNotEmpty()) {
            item(key = "recentHead", span = full) {
                SectionHead("Continue watching", "Picked up automatically from your TVs · ☆ to save") {}
            }
            item(key = "recentRow", span = full) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 8.dp)) {
                    items(recents, key = { it.id }) { r ->
                        val saved = guide.any { it.id == "r" + r.id }
                        MediaCard(
                            title = r.title, subtitle = r.subtitle, image = r.image, badge = r.appName,
                            saved = saved, enabled = online,
                            onClick = { hub.playRecent(id, r) },
                            onLongClick = { removeRecent = r },
                            onStar = { hub.toggleSaveRecent(r) },
                        )
                    }
                }
            }
        }
        item(key = "guideHead", span = full) {
            SectionHead("Guide", "Your saved shows and videos · tap to play") {
                TextButton(onClick = onAddGuide) { Text("+ Add", color = Pink, fontWeight = FontWeight.Bold) }
            }
        }
        item(key = "guideRow", span = full) {
            if (guide.isEmpty()) {
                DashedCard(
                    "Save shows, movies and videos",
                    if (recents.isNotEmpty()) "Tap ☆ on anything in Continue watching, or paste a link." else "Watch something and it shows up in Continue watching — or paste a link.",
                    onAddGuide,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 8.dp)) {
                    items(guide, key = { it.id }) { g ->
                        MediaCard(
                            title = g.title, subtitle = null, image = g.image, badge = g.service,
                            saved = null, enabled = online,
                            onClick = { hub.playGuide(id, g) }, onLongClick = { removeGuide = g }, onStar = {},
                        )
                    }
                    item(key = "add") {
                        Box(
                            Modifier.width(120.dp).aspectRatio(16f / 13f).clip(RoundedCornerShape(18.dp)).background(PanelHi)
                                .border(1.dp, Line, RoundedCornerShape(18.dp)).clickable(onClick = onAddGuide),
                            contentAlignment = Alignment.Center,
                        ) { Text("+ Add", color = TextLo, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        // ----- All apps
        item(key = "appsHead", span = full) {
            SectionHead("All apps", if (editing) "Tap to add to favorites" else "Tap to open · hold to favorite") {
                if (st?.loading == true) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Pink)
                else IconButton(onClick = { hub.loadApps(id) }, enabled = online) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh apps", tint = TextLo)
                }
            }
        }
        when {
            st == null && !online -> item(span = full) {
                Text("Apps show up here once ${tv.config.name} is connected.", color = TextLo, modifier = Modifier.padding(vertical = 24.dp))
            }
            st == null || (st.loading && st.apps.isEmpty()) -> items(6) { PlaceholderTile() }
            st.apps.isEmpty() -> item(span = full) {
                Text(st.error ?: "No apps found.", color = TextLo, modifier = Modifier.padding(vertical = 24.dp))
            }
            else -> items(ordered, key = { it.packageName }) { app ->
                AppTile(
                    app = app,
                    isOpen = tv.now?.packageName == app.packageName && tv.power != Power.OFF,
                    pinned = app.packageName in pins,
                    editing = editing,
                    enabled = online || editing,
                    onClick = { if (editing) hub.togglePin(id, app.packageName) else hub.launchApp(id, app) },
                    onLongClick = { hub.togglePin(id, app.packageName) },
                )
            }
        }
    }

    removeRecent?.let { r ->
        AlertDialog(
            onDismissRequest = { removeRecent = null },
            containerColor = PanelHi,
            title = { Text(r.title) },
            text = { Text("${r.appName}${r.subtitle?.let { " · $it" } ?: ""}", color = TextLo) },
            confirmButton = { TextButton(onClick = { hub.removeRecent(r.id); removeRecent = null }) { Text("Remove", color = OfflineRed) } },
            dismissButton = { TextButton(onClick = { removeRecent = null }) { Text("Cancel") } },
        )
    }

    removeGuide?.let { g ->
        AlertDialog(
            onDismissRequest = { removeGuide = null },
            containerColor = PanelHi,
            title = { Text(g.title) },
            text = { Text(g.url, color = TextLo, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton(onClick = { hub.removeGuide(g.id); removeGuide = null }) { Text("Remove", color = OfflineRed) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { hub.moveGuideToFront(g.id); removeGuide = null }) { Text("Move to front") }
                    TextButton(onClick = { removeGuide = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun SectionHead(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextLo)
        }
        trailing()
    }
}

@Composable
private fun DashedCard(title: String, body: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel)
            .border(1.5.dp, Brush.linearGradient(listOf(Violet.copy(alpha = 0.6f), Pink.copy(alpha = 0.6f))), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(18.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodySmall, color = TextLo)
    }
}

@Composable
private fun MiniButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(Ink.copy(alpha = 0.8f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

/** A 16:9 picture card for Continue watching and Guide items. [saved] null = no star button. */
@Composable
private fun MediaCard(
    title: String, subtitle: String?, image: String?, badge: String?, saved: Boolean?, enabled: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onStar: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier.width(220.dp).combinedClickable(
            enabled = enabled,
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
        ),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(PanelHi, Panel)))) {
            if (image != null) {
                AsyncImage(
                    model = if (image.startsWith("/")) File(image) else image,
                    contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center).padding(12.dp), maxLines = 2)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Ink.copy(alpha = 0.75f)))))
            Box(
                Modifier.align(Alignment.Center).size(46.dp).clip(CircleShape).background(Ink.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) { Icon(HubIcons.Play, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(26.dp)) }
            badge?.let {
                Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
            if (saved != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(34.dp).clip(CircleShape)
                        .background(if (saved) Glow else Brush.linearGradient(listOf(Ink.copy(alpha = 0.7f), Ink.copy(alpha = 0.7f))))
                        .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onStar() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (saved) HubIcons.Star else HubIcons.StarOutline, contentDescription = if (saved) "Saved" else "Save to Guide", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextLo, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun NowPlayingHero(hub: TvHub, tv: TvState, current: TvApp?) {
    val id = tv.config.id
    val name = tv.config.name
    val on = tv.conn == Conn.ONLINE && tv.power != Power.OFF
    val now = tv.now
    val isHome = AppNames.isHome(now?.packageName)
    val title: String
    val subtitle: String
    when {
        tv.busy != null -> { title = tv.busy; subtitle = "This only happens once" }
        tv.conn == Conn.CONNECTING -> { title = "Connecting…"; subtitle = "First time? On $name, choose Allow (tick “Always allow”)." }
        tv.conn == Conn.OFFLINE -> { title = "Can't reach $name"; subtitle = tv.error ?: "Check it's on and on Wi‑Fi." }
        tv.power == Power.OFF -> { title = "$name is off"; subtitle = "Wake it up to start watching" }
        tv.power == Power.SCREENSAVER -> { title = "Screensaver"; subtitle = "Open an app or search to start" }
        now == null || isHome -> { title = "Home screen"; subtitle = "Pick an app below or search" }
        else -> {
            title = now?.title ?: now?.appName ?: "Watching"
            subtitle = listOfNotNull(if (now?.title != null) now.appName else null, now?.playback).joinToString(" · ").ifEmpty { "Now open" }
        }
    }

    Box(
        Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(28.dp)).background(Panel),
    ) {
        val art = current?.banner ?: current?.icon
        if (art != null && on && !isHome) {
            AsyncImage(
                model = File(art), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.55f),
            )
        } else {
            Box(Modifier.fillMaxSize().background(if (on) GlowSoft else Brush.linearGradient(listOf(PanelHi, Panel))))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.55f), Ink.copy(alpha = 0.95f)))))

        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(tv)
                Spacer(Modifier.width(8.dp))
                Text("NOW ON ${name.uppercase()}", style = MaterialTheme.typography.labelSmall, color = TextHi.copy(alpha = 0.75f), modifier = Modifier.weight(1f))
                if (tv.conn == Conn.ONLINE) {
                    RoundIcon(HubIcons.Power, if (on) "Sleep" else "Wake", size = 38.dp, tint = if (on) TextHi else OnlineGreen) { hub.power(id) }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextHi.copy(alpha = 0.7f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            when {
                tv.busy != null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = Pink, trackColor = PanelHi)
                on -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundIcon(HubIcons.Rewind, "Rewind", size = 44.dp) { hub.key(id, Keys.REWIND) }
                    RoundIcon(if (now?.playback == "Playing") HubIcons.Pause else HubIcons.Play, "Play or pause", size = 56.dp, brush = Glow) { hub.key(id, Keys.PLAY_PAUSE) }
                    RoundIcon(HubIcons.Forward, "Fast forward", size = 44.dp) { hub.key(id, Keys.FAST_FORWARD) }
                    Spacer(Modifier.weight(1f))
                    RoundIcon(HubIcons.VolDown, "Volume down", size = 44.dp) { hub.key(id, Keys.VOL_DOWN) }
                    RoundIcon(HubIcons.VolUp, "Volume up", size = 44.dp) { hub.key(id, Keys.VOL_UP) }
                }
                tv.conn == Conn.ONLINE -> GlowButton("Wake $name") { hub.power(id) }
                tv.conn == Conn.OFFLINE -> OutlinedButton(onClick = { hub.refresh(id, 0) }) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun SearchPill(tvName: String, onSearch: () -> Unit, onVoice: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(CircleShape).background(PanelHi)
            .border(1.dp, Line, CircleShape).clickable(onClick = onSearch).padding(start = 20.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = TextLo)
        Spacer(Modifier.width(12.dp))
        Text("Search $tvName", color = TextLo, fontSize = 17.sp, modifier = Modifier.weight(1f))
        RoundIcon(HubIcons.Mic, "Voice search", size = 48.dp, brush = Glow, onClick = onVoice)
    }
}

@Composable
private fun SetupCard(hub: TvHub, tv: TvState) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(GlowSoft).border(1.dp, Violet.copy(alpha = 0.5f), RoundedCornerShape(24.dp)).padding(18.dp),
    ) {
        val updating = (tv.receiverVersion ?: -1) >= 0
        Text(if (updating) "Update ${tv.config.name}'s helper" else "Make ${tv.config.name} look great", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Installs TV Hub's small helper on this TV (one time, about 30 seconds). You get the TV's real app tiles and photo casting.",
            style = MaterialTheme.typography.bodySmall, color = TextHi.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(12.dp))
        GlowButton(if (updating) "Update" else "Set up", enabled = tv.busy == null) { hub.installReceiver(tv.config.id) }
    }
}

@Composable
fun GlowButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(48.dp).clip(CircleShape).background(if (enabled) Glow else Brush.linearGradient(listOf(PanelHi, PanelHi)))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold) }
}

private fun tileColor(pkg: String): Color = Color.hsv((abs(pkg.hashCode()) % 360).toFloat(), 0.55f, 0.5f)

@Composable
private fun AppTile(app: TvApp, isOpen: Boolean, pinned: Boolean, editing: Boolean, enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.combinedClickable(
            enabled = enabled,
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
        ),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape)
                .then(if (isOpen) Modifier.border(2.5.dp, Glow, shape) else Modifier.border(1.dp, Line, shape)),
        ) {
            when {
                app.banner != null -> AsyncImage(
                    model = File(app.banner), contentDescription = app.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                app.icon != null -> Box(
                    Modifier.fillMaxSize().background(Brush.linearGradient(listOf(tileColor(app.packageName), PanelHi))),
                    contentAlignment = Alignment.Center,
                ) { AsyncImage(model = File(app.icon), contentDescription = app.name, modifier = Modifier.size(56.dp)) }
                else -> Box(
                    Modifier.fillMaxSize().background(Brush.linearGradient(listOf(tileColor(app.packageName), PanelHi))),
                    contentAlignment = Alignment.Center,
                ) { Text(app.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp)) }
            }
            if (isOpen) {
                Text(
                    "NOW ON", style = MaterialTheme.typography.labelSmall, color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(CircleShape).background(Glow).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            if (editing || pinned) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(if (editing) 34.dp else 24.dp).clip(CircleShape)
                        .background(if (pinned) Glow else Brush.linearGradient(listOf(Ink.copy(alpha = 0.75f), Ink.copy(alpha = 0.75f)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (pinned) HubIcons.Star else HubIcons.StarOutline, contentDescription = if (pinned) "Favorite" else "Not a favorite",
                        tint = Color.White, modifier = Modifier.size(if (editing) 20.dp else 14.dp),
                    )
                }
            }
        }
        Text(app.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
    }
}

@Composable
private fun PlaceholderTile() {
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(PanelHi))
        Box(Modifier.padding(top = 8.dp).size(width = 80.dp, height = 12.dp).clip(CircleShape).background(PanelHi))
    }
}
