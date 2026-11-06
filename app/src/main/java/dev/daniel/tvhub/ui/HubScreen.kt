@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.daniel.tvhub.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.daniel.tvhub.MainActivity
import dev.daniel.tvhub.core.Conn
import dev.daniel.tvhub.core.Power
import dev.daniel.tvhub.core.TvHub
import dev.daniel.tvhub.core.TvState
import kotlinx.coroutines.launch

private val tabNames = listOf("Remote", "Apps", "Links", "Cast")

@Composable
fun HubScreen(hub: TvHub, activity: MainActivity) {
    val tvs by hub.tvs.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf(hub.store.selectedTv) }
    val selected = tvs.firstOrNull { it.config.id == selectedId } ?: tvs.firstOrNull()
    LaunchedEffect(selected?.config?.id) { hub.store.selectedTv = selected?.config?.id }

    var showSettings by rememberSaveable { mutableStateOf(tvs.isEmpty()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        hub.messages.collect { msg ->
            snackbar.currentSnackbarData?.dismiss()
            scope.launch { snackbar.showSnackbar(msg) }
        }
    }

    // This app may be the phone's home screen: Back should never leave it.
    BackHandler(enabled = true) { if (showSettings && tvs.isNotEmpty()) showSettings = false }

    Box(Modifier.fillMaxSize()) {
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("TV Hub", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(48.dp).clip(CircleShape).combinedClickable(
                        onClick = { Toast.makeText(activity, "Hold to open settings", Toast.LENGTH_SHORT).show() },
                        onLongClick = { showSettings = true },
                    ),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Settings, contentDescription = "Settings (hold)") }
            }

            if (selected == null) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No TVs yet", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Add your three TVs in settings.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { showSettings = true }) { Text("Add a TV") }
                }
            } else {
                TvStrip(tvs, selected.config.id) { selectedId = it }
                NowCard(selected)
                TabRow(selectedTabIndex = tab) {
                    tabNames.forEachIndexed { i, name -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name) }) }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> RemoteTab(hub, selected)
                        1 -> AppsTab(hub, selected)
                        2 -> LinksTab(hub, selected)
                        else -> CastTab(hub, selected)
                    }
                }
            }
        }
    }

    if (showSettings) SettingsScreen(hub, activity, onClose = { showSettings = false })
    }
}

fun statusColor(s: TvState): Color = when {
    s.conn == Conn.OFFLINE -> OfflineRed
    s.conn == Conn.CONNECTING -> IdleGrey
    s.power == Power.OFF -> IdleGrey
    s.power == Power.SCREENSAVER -> DimAmber
    else -> OnlineGreen
}

fun statusText(s: TvState): String = when {
    s.busy != null -> s.busy
    s.conn == Conn.OFFLINE -> "Offline"
    s.conn == Conn.CONNECTING -> "Connecting…"
    s.power == Power.OFF -> "Off"
    s.power == Power.SCREENSAVER -> "Screensaver"
    else -> s.now?.appName ?: "On"
}

@Composable
private fun TvStrip(tvs: List<TvState>, selectedId: String, onSelect: (String) -> Unit) {
    val fits = tvs.size <= 3
    Row(
        Modifier.fillMaxWidth().then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState()))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tvs.forEach { s ->
            val sel = s.config.id == selectedId
            Card(
                onClick = { onSelect(s.config.id) },
                modifier = if (fits) Modifier.weight(1f) else Modifier.width(130.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(s)))
                        Spacer(Modifier.width(6.dp))
                        Text(s.config.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(statusText(s), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = s.now?.title ?: s.now?.playback
                    Text(
                        if (s.conn == Conn.ONLINE && s.power != Power.OFF) sub ?: " " else " ",
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NowCard(s: TvState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        when {
            s.busy != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp)); Text(s.busy)
            }
            s.conn == Conn.CONNECTING -> Text(
                "Connecting to ${s.config.name}… First time? Look at that TV and choose Allow (tick \"Always allow\").",
                style = MaterialTheme.typography.bodySmall,
            )
            s.conn == Conn.OFFLINE -> Text(s.error ?: "Offline", style = MaterialTheme.typography.bodySmall, color = OfflineRed)
            else -> {
                val now = s.now
                val line = buildString {
                    append(s.config.name).append(": ")
                    when {
                        s.power == Power.OFF -> append("off / asleep")
                        now?.appName != null -> {
                            append(now.appName)
                            now.title?.let { append(" — ").append(it) }
                            now.playback?.let { append(" (").append(it.lowercase()).append(")") }
                        }
                        else -> append("on")
                    }
                }
                Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
