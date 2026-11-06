@file:OptIn(ExperimentalMaterial3Api::class)

package dev.daniel.tvhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.daniel.tvhub.core.Keys
import dev.daniel.tvhub.core.Power
import dev.daniel.tvhub.core.TvHub
import dev.daniel.tvhub.core.TvKind
import dev.daniel.tvhub.core.TvState

@Composable
fun RemoteTab(hub: TvHub, tv: TvState) {
    val id = tv.config.id
    val haptic = LocalHapticFeedback.current
    val press: (Int) -> Unit = { code ->
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        hub.key(id, code)
    }
    var typed by remember(id) { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteButton(if (tv.power == Power.OFF) "Wake" else "Sleep", Modifier.weight(1f)) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress); hub.power(id)
            }
            RemoteButton("Home", Modifier.weight(1f)) { press(Keys.HOME) }
            RemoteButton("Back", Modifier.weight(1f)) { press(Keys.BACK) }
            RemoteButton("Menu", Modifier.weight(1f)) { press(Keys.MENU) }
        }

        DPad(press)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteButton("« Rew", Modifier.weight(1f)) { press(Keys.REWIND) }
            RemoteButton("Play / Pause", Modifier.weight(1.4f)) { press(Keys.PLAY_PAUSE) }
            RemoteButton("FF »", Modifier.weight(1f)) { press(Keys.FAST_FORWARD) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteButton("Vol −", Modifier.weight(1f)) { press(Keys.VOL_DOWN) }
            RemoteButton("Mute", Modifier.weight(1f)) { press(Keys.MUTE) }
            RemoteButton("Vol +", Modifier.weight(1f)) { press(Keys.VOL_UP) }
        }

        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Type on the TV (search boxes, logins)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { hub.text(id, typed); typed = "" }),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { hub.text(id, typed); typed = "" }, modifier = Modifier.weight(1.4f)) { Text("Send text") }
            RemoteButton("⌫", Modifier.weight(1f)) { press(Keys.DEL) }
            RemoteButton("Enter", Modifier.weight(1f)) { press(Keys.ENTER) }
        }
        if (tv.config.kind == TvKind.FIRE_TV) {
            Text(
                "Volume and Wake go through HDMI‑CEC. If they do nothing, turn on HDMI CEC Device Control in the Fire TV's settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RemoteButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(52.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
        Text(label, maxLines = 1)
    }
}

@Composable
private fun DPad(press: (Int) -> Unit) {
    Box(
        Modifier.size(250.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Arrow(Icons.Default.KeyboardArrowUp, "Up", Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) { press(Keys.UP) }
        Arrow(Icons.Default.KeyboardArrowDown, "Down", Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) { press(Keys.DOWN) }
        Arrow(Icons.Default.KeyboardArrowLeft, "Left", Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) { press(Keys.LEFT) }
        Arrow(Icons.Default.KeyboardArrowRight, "Right", Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) { press(Keys.RIGHT) }
        Button(
            onClick = { press(Keys.CENTER) },
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
        ) { Text("OK", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun Arrow(icon: ImageVector, desc: String, modifier: Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.size(68.dp)) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(48.dp))
    }
}
