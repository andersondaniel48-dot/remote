@file:OptIn(ExperimentalMaterial3Api::class)

package dev.daniel.tvhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.daniel.tvhub.core.Conn
import dev.daniel.tvhub.core.TvApp
import dev.daniel.tvhub.core.TvHub
import dev.daniel.tvhub.core.TvState
import kotlin.math.abs

private val tileColors = listOf(
    Color(0xFFE57373), Color(0xFFBA68C8), Color(0xFF64B5F6), Color(0xFF4DB6AC),
    Color(0xFFFFB74D), Color(0xFFF06292), Color(0xFF9575CD), Color(0xFF81C784),
)

@Composable
fun AppsTab(hub: TvHub, tv: TvState) {
    val id = tv.config.id
    val all by hub.apps.collectAsStateWithLifecycle()
    val st = all[id]
    val online = tv.conn == Conn.ONLINE
    LaunchedEffect(id, online) { if (online && hub.apps.value[id] == null) hub.loadApps(id) }
    var filter by remember(id) { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedTextField(
                value = filter, onValueChange = { filter = it }, singleLine = true,
                placeholder = { Text("Find an app on ${tv.config.name}") }, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { hub.loadApps(id) }) { Icon(Icons.Default.Refresh, contentDescription = "Reload apps") }
        }
        when {
            !online && st == null -> Centered("Apps appear once ${tv.config.name} is connected.")
            st == null || (st.loading && st.apps.isEmpty()) -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            st.error != null && st.apps.isEmpty() -> Centered(st.error)
            else -> {
                val shown = st.apps.filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(100.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.packageName }) { app ->
                        AppTile(app, isOpen = tv.now?.packageName == app.packageName) { hub.launchApp(id, app) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppTile(app: TvApp, isOpen: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isOpen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(tileColors[abs(app.packageName.hashCode()) % tileColors.size]),
                contentAlignment = Alignment.Center,
            ) { Text(app.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = Color.White) }
            Spacer(Modifier.height(6.dp))
            Text(app.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
