@file:OptIn(ExperimentalFoundationApi::class)

package dev.daniel.tvhub.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import dev.daniel.tvhub.core.Keys
import dev.daniel.tvhub.core.Power
import dev.daniel.tvhub.core.TvApp
import dev.daniel.tvhub.core.TvHub
import dev.daniel.tvhub.core.TvState
import java.io.File
import kotlin.math.abs

/** One TV's home: what's on, a big search box, and its apps as tiles. */
@Composable
fun HomePage(hub: TvHub, tv: TvState, onSearch: () -> Unit, onVoice: () -> Unit) {
    val id = tv.config.id
    val appsMap by hub.apps.collectAsStateWithLifecycle()
    val pinsMap by hub.pins.collectAsStateWithLifecycle()
    val usageMap by hub.usageByTv.collectAsStateWithLifecycle()
    val online = tv.conn == Conn.ONLINE
    LaunchedEffect(id, online, tv.receiverVersion) { if (online) hub.ensureApps(id) }

    val st = appsMap[id]
    val pins = pinsMap[id] ?: emptySet()
    val ordered = remember(st?.apps, pins, usageMap[id]) {
        hub.ordered(id, st?.apps ?: emptyList(), pins, usageMap[id] ?: emptyMap())
    }
    val current = st?.apps?.firstOrNull { it.packageName == tv.now?.packageName }
    val helperMissing = online && (tv.receiverVersion ?: 0) < BuildConfig.VERSION_CODE && tv.receiverVersion != null

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 128.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { NowPlayingHero(hub, tv, current) }
        item(span = { GridItemSpan(maxLineSpan) }) { SearchPill(tv.config.name, onSearch, onVoice) }
        if (helperMissing) {
            item(span = { GridItemSpan(maxLineSpan) }) { SetupCard(hub, tv) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Apps", style = MaterialTheme.typography.titleLarge)
                    Text("Tap to open · hold to pin", style = MaterialTheme.typography.bodySmall, color = TextLo)
                }
                if (st?.loading == true) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Pink)
                else IconButton(onClick = { hub.loadApps(id) }, enabled = online) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh apps", tint = TextLo)
                }
            }
        }
        when {
            st == null && !online -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Apps show up here once ${tv.config.name} is connected.", color = TextLo, modifier = Modifier.padding(vertical = 24.dp))
            }
            st == null || (st.loading && st.apps.isEmpty()) -> items(6) { PlaceholderTile() }
            st.apps.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(st.error ?: "No apps found.", color = TextLo, modifier = Modifier.padding(vertical = 24.dp))
            }
            else -> items(ordered, key = { it.packageName }) { app ->
                AppTile(
                    app = app,
                    isOpen = tv.now?.packageName == app.packageName && tv.power != Power.OFF,
                    pinned = app.packageName in pins,
                    enabled = online,
                    onClick = { hub.launchApp(id, app) },
                    onLongClick = { hub.togglePin(id, app.packageName) },
                )
            }
        }
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
private fun AppTile(app: TvApp, isOpen: Boolean, pinned: Boolean, enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
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
            if (pinned) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(Ink.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(HubIcons.Pin, contentDescription = "Pinned", tint = Peach, modifier = Modifier.size(14.dp)) }
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
