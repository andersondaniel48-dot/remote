package dev.daniel.tvhub.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.daniel.tvhub.core.Conn
import dev.daniel.tvhub.core.Power
import dev.daniel.tvhub.core.TvState

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

/** A round icon button. Pass [brush] for the glowing primary style. */
@Composable
fun RoundIcon(
    icon: ImageVector,
    desc: String,
    size: Dp = 48.dp,
    brush: Brush? = null,
    tint: Color = TextHi,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(brush ?: SolidColor(PanelHi))
            .clickable(enabled = enabled) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
fun StatusDot(s: TvState, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(statusColor(s)))
}

/**
 * A bottom sheet drawn in the main window (not a dialog), so the phone's volume buttons
 * keep controlling the TV while it's open. Swipe down or tap outside to close.
 */
@Composable
fun BoxScope.HubSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.matchParentSize()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(Panel)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .imePadding(),
        ) {
            var dragged = 0f
            Box(
                Modifier.fillMaxWidth().height(28.dp).pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragged = 0f },
                        onVerticalDrag = { _, dy -> dragged += dy; if (dragged > 90f) { dragged = -10_000f; onDismiss() } },
                    )
                },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(width = 40.dp, height = 4.dp).clip(CircleShape).background(Line)) }
            Column(
                Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                content = content,
            )
        }
    }
}
