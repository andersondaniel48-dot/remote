@file:OptIn(ExperimentalMaterial3Api::class)

package dev.daniel.tvhub.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.daniel.tvhub.BuildConfig
import dev.daniel.tvhub.core.Conn
import dev.daniel.tvhub.core.PhoneMedia
import dev.daniel.tvhub.core.PhoneMediaItem
import dev.daniel.tvhub.core.TvHub
import dev.daniel.tvhub.core.TvState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CastTab(hub: TvHub, tv: TvState) {
    val ctx = LocalContext.current
    val id = tv.config.id
    var granted by remember { mutableStateOf(PhoneMedia.hasPermission(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = PhoneMedia.hasPermission(ctx)
    }
    var items by remember { mutableStateOf<List<PhoneMediaItem>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(granted, reload) {
        if (granted) items = withContext(Dispatchers.IO) { PhoneMedia.recent(ctx) }
    }
    val ready = (tv.receiverVersion ?: -1) >= BuildConfig.VERSION_CODE
    val casting = tv.now?.packageName == BuildConfig.APPLICATION_ID

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when {
                    tv.busy != null -> Text(tv.busy, style = MaterialTheme.typography.bodyMedium)
                    tv.conn != Conn.ONLINE -> Text("${tv.config.name} isn't connected", style = MaterialTheme.typography.bodyMedium)
                    !ready -> Text(
                        if (tv.receiverVersion == null || tv.receiverVersion < 0) "First cast installs TV Hub's small player on ${tv.config.name} (one time)."
                        else "TV Hub's player on ${tv.config.name} needs an update.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Text("Tap a photo or video to show it on ${tv.config.name}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            when {
                tv.busy != null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                casting -> OutlinedButton(onClick = { hub.stopCast(id) }) { Text("Stop") }
                tv.conn == Conn.ONLINE && !ready -> OutlinedButton(onClick = { hub.installReceiver(id) }) { Text("Set up") }
                granted -> IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, contentDescription = "Reload gallery") }
            }
        }
        if (casting) {
            Text(
                "Use Play / Pause, « Rew and FF » (or ◀ ▶ on the D‑pad) in the Remote tab to control the video.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        val list = items
        when {
            !granted -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TV Hub needs to see this phone's photos and videos to cast them.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = { ask.launch(PhoneMedia.permissions()) }) { Text("Allow photos & videos") }
            }
            list == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Centered("No photos or videos on this phone yet. Copy some over, then tap reload.")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(list, key = { it.uri.toString() }) { item ->
                    MediaTile(item, enabled = tv.conn == Conn.ONLINE && tv.busy == null) { hub.cast(id, item) }
                }
            }
        }
    }
}

@Composable
private fun MediaTile(item: PhoneMediaItem, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        AsyncImage(model = item.uri, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (item.isVideo) {
            val secs = item.durationMs / 1000
            Text(
                "▶ %d:%02d".format(secs / 60, secs % 60),
                color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
