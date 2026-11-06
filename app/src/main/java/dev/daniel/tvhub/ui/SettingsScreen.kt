@file:OptIn(ExperimentalMaterial3Api::class)

package dev.daniel.tvhub.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.daniel.tvhub.BuildConfig
import dev.daniel.tvhub.MainActivity
import dev.daniel.tvhub.core.Kiosk
import dev.daniel.tvhub.core.NetUtil
import dev.daniel.tvhub.core.TvConfig
import dev.daniel.tvhub.core.TvHub
import dev.daniel.tvhub.core.TvKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class Draft(val id: String?, val name: String, val host: String, val port: String, val kind: TvKind)

@Composable
fun SettingsScreen(hub: TvHub, activity: MainActivity, onClose: () -> Unit) {
    val tvs by hub.tvs.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf<Draft?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<String>?>(null) }
    var confirmDelete by remember { mutableStateOf<TvConfig?>(null) }
    val scope = rememberCoroutineScope()

    // Lock/home state can change outside the app, so re-read it every second while this screen is open.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val homeOn = remember(tick) { Kiosk.isHomeEnabled(activity) }
    val locked = remember(tick) { Kiosk.isLocked(activity) }
    val owner = remember(tick) { Kiosk.isDeviceOwner(activity) }
    var keepOn by remember { mutableStateOf(hub.store.keepScreenOnWhileCharging) }
    var volKeys by remember { mutableStateOf(hub.store.volumeKeysControlTv) }

    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tvs.isNotEmpty()) IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
            }

            Section("TVs")
            if (tvs.isEmpty()) Text("Add each TV with its Wi‑Fi address. The README explains how to find it and turn on ADB debugging.")
            tvs.forEachIndexed { i, s ->
                Card {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.config.name, fontWeight = FontWeight.SemiBold)
                            Text("${s.config.kind.label} · ${s.config.host}:${s.config.port}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                listOfNotNull(statusText(s), s.model).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = statusColor(s),
                            )
                        }
                        IconButton(onClick = { hub.moveTv(s.config.id, -1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                        IconButton(onClick = { hub.moveTv(s.config.id, 1) }, enabled = i < tvs.size - 1) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                        IconButton(onClick = {
                            draft = Draft(s.config.id, s.config.name, s.config.host, s.config.port.toString(), s.config.kind)
                        }) { Icon(Icons.Default.Edit, "Edit") }
                        IconButton(onClick = { confirmDelete = s.config }) { Icon(Icons.Default.Delete, "Remove") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { draft = Draft(null, "", "", "5555", TvKind.FIRE_TV) }) { Text("Add a TV") }
                OutlinedButton(enabled = !scanning, onClick = {
                    scope.launch { scanning = true; found = hub.scan(); scanning = false }
                }) { Text(if (scanning) "Scanning…" else "Scan Wi‑Fi") }
            }
            found?.let { list ->
                val known = tvs.map { it.config.host }.toSet()
                val fresh = list.filter { it !in known }
                Text(
                    if (fresh.isEmpty()) "No new devices with ADB debugging found. Check it's turned on and the TV is awake."
                    else "Found with ADB debugging on — tap to add:",
                    style = MaterialTheme.typography.bodySmall,
                )
                fresh.forEach { host ->
                    OutlinedButton(onClick = { draft = Draft(null, "", host, "5555", TvKind.FIRE_TV) }) { Text(host) }
                }
            }

            HorizontalDivider()
            Section("Make TV Hub the only app")
            SwitchRow(
                "Use TV Hub as the home screen",
                "Home and Back always come back here. Android will ask you to choose TV Hub as the default home app.",
                homeOn,
            ) { on ->
                Kiosk.setHomeEnabled(activity, on)
                Kiosk.openHomePicker(activity)
                tick++
                activity.applyChrome()
            }
            if (owner) {
                SwitchRow(
                    "Full lock (device owner)",
                    "Locks the phone to TV Hub. Notifications, recent apps and other apps are blocked.",
                    locked,
                ) { on ->
                    hub.store.autoLock = on
                    if (on) Kiosk.lock(activity) else Kiosk.unlock(activity)
                    tick++
                    activity.applyChrome()
                }
                OutlinedButton(onClick = {
                    hub.store.autoLock = false
                    Kiosk.unlock(activity)
                    Kiosk.removeDeviceOwner(activity)
                    tick++
                }) { Text("Remove device-owner mode") }
            } else {
                SwitchRow(
                    "Pin TV Hub",
                    "Uses Android screen pinning. To unpin, hold Back + Overview (or swipe up and hold). For a lock kids can't undo, see README → Full lock.",
                    locked,
                ) { on ->
                    if (on) Kiosk.lock(activity) else Kiosk.unlock(activity)
                    tick++
                    activity.applyChrome()
                }
            }

            HorizontalDivider()
            Section("Screen")
            SwitchRow("Keep screen on while charging", "Recommended: leave the phone on a charger like a dock.", keepOn) { on ->
                keepOn = on
                hub.store.keepScreenOnWhileCharging = on
                activity.refreshKeepOn()
            }

            SwitchRow("Phone volume buttons control the TV", "Turns the selected TV up and down.", volKeys) { on ->
                volKeys = on
                hub.store.volumeKeysControlTv = on
            }

            HorizontalDivider()
            Section("Phone")
            OutlinedButton(onClick = {
                if (locked) Kiosk.unlock(activity)
                runCatching { activity.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
            }) { Text("Open Android settings") }
            val ip = remember(tick / 10) { NetUtil.phoneIpv4(activity)?.first ?: "not on Wi‑Fi" }
            Text("Phone Wi‑Fi address: $ip · TV Hub ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }

    draft?.let { d ->
        TvDialog(d, onDismiss = { draft = null }) { saved ->
            val port = saved.port.toIntOrNull() ?: 5555
            if (saved.id == null) hub.addTv(saved.name, saved.host, port, saved.kind)
            else hub.updateTv(TvConfig(saved.id, saved.name.trim().ifEmpty { saved.kind.label }, saved.host.trim(), port, saved.kind))
            draft = null
            found = found?.filter { it != saved.host.trim() }
        }
    }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove ${c.name}?") },
            confirmButton = { TextButton(onClick = { hub.removeTv(c.id); confirmDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TvDialog(initial: Draft, onDismiss: () -> Unit, onSave: (Draft) -> Unit) {
    var d by remember { mutableStateOf(initial) }
    val hostOk = Regex("^[A-Za-z0-9.-]+$").matches(d.host.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == null) "Add a TV" else "Edit ${initial.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(d.name, { d = d.copy(name = it) }, label = { Text("Name (e.g. Living Room)") }, singleLine = true)
                OutlinedTextField(
                    d.host, { d = d.copy(host = it) }, label = { Text("TV's Wi‑Fi address (e.g. 192.168.1.50)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    d.port, { d = d.copy(port = it.filter(Char::isDigit).take(5)) }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvKind.entries.forEach { k ->
                        FilterChip(selected = d.kind == k, onClick = { d = d.copy(kind = k) }, label = { Text(k.label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = hostOk, onClick = { onSave(d) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
