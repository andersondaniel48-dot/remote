@file:OptIn(ExperimentalMaterial3Api::class)

package dev.daniel.tvhub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.daniel.tvhub.core.TvHub
import dev.daniel.tvhub.core.TvKind
import dev.daniel.tvhub.core.TvState

@Composable
fun LinksTab(hub: TvHub, tv: TvState) {
    val id = tv.config.id
    var url by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val recents by hub.recentLinks.collectAsStateWithLifecycle()
    val send = { if (url.isNotBlank()) { hub.openLink(id, url); url = "" } }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true,
            label = { Text("Link — YouTube video, web page…") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { send() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.getText()?.text?.let { url = it.trim() } }) { Text("Paste") }
            Button(onClick = send, modifier = Modifier.weight(1f), enabled = url.isNotBlank()) { Text("Open on ${tv.config.name}") }
        }
        Text(
            if (tv.config.kind == TvKind.FIRE_TV) "YouTube links open in the YouTube app. Other links open in Silk if it's installed."
            else "YouTube links open in the YouTube app. Google TV has no web browser built in, so other web links need one installed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (recents.isNotEmpty()) {
            Text("Recent", style = MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.weight(1f)) {
                items(recents, key = { it }) { link ->
                    ListItem(
                        headlineContent = { Text(link, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("Tap to open on ${tv.config.name}") },
                        trailingContent = {
                            IconButton(onClick = { hub.forgetLink(link) }) { Icon(Icons.Default.Close, contentDescription = "Remove") }
                        },
                        modifier = Modifier.clickable { hub.openLink(id, link) },
                    )
                }
            }
        }
    }
}
