@file:OptIn(ExperimentalFoundationApi::class)

package dev.daniel.tvhub.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.daniel.tvhub.MainActivity
import dev.daniel.tvhub.core.TvHub
import dev.daniel.tvhub.core.TvState
import kotlinx.coroutines.launch

private enum class SheetKind { SEARCH, VOICE, REMOTE, TYPE, PHOTOS }

@Composable
fun HubScreen(hub: TvHub, activity: MainActivity) {
    val tvs by hub.tvs.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(tvs.isEmpty()) }
    var sheet by rememberSaveable { mutableStateOf<SheetKind?>(null) }
    var lastSheet by remember { mutableStateOf(SheetKind.REMOTE) }
    var remoteHint by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val startIndex = remember { tvs.indexOfFirst { it.config.id == hub.store.selectedTv }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIndex) { tvs.size }
    val selected: TvState? = tvs.getOrNull(pager.currentPage.coerceAtMost(tvs.size - 1).coerceAtLeast(0))
    LaunchedEffect(selected?.config?.id) { selected?.let { hub.store.selectedTv = it.config.id } }

    LaunchedEffect(Unit) {
        hub.messages.collect { msg ->
            snackbar.currentSnackbarData?.dismiss()
            scope.launch { snackbar.showSnackbar(msg) }
        }
    }

    fun open(k: SheetKind) { lastSheet = k; if (k != SheetKind.REMOTE) remoteHint = null; sheet = k }

    // This app may be the phone's home screen, so Back closes things but never leaves.
    BackHandler(enabled = true) {
        when {
            sheet != null -> sheet = null
            showSettings && tvs.isNotEmpty() -> showSettings = false
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        // Soft ambient glow behind the top of the screen.
        Box(
            Modifier.fillMaxWidth().height(320.dp)
                .background(Brush.radialGradient(listOf(Violet.copy(alpha = 0.22f), Color.Transparent), radius = 900f)),
        )

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(
                tvs = tvs,
                selectedIndex = pager.currentPage,
                onSelect = { i -> scope.launch { pager.animateScrollToPage(i) } },
                onSettingsTap = { Toast.makeText(activity, "Hold to open settings", Toast.LENGTH_SHORT).show() },
                onSettingsHold = { showSettings = true },
            )
            if (tvs.isEmpty()) {
                EmptyState { showSettings = true }
            } else {
                HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
                    tvs.getOrNull(page)?.let { tv ->
                        HomePage(hub, tv, onSearch = { open(SheetKind.SEARCH) }, onVoice = { open(SheetKind.VOICE) })
                    }
                }
            }
        }

        if (selected != null) {
            Dock(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
                onType = { open(SheetKind.TYPE) },
                onRemote = { open(SheetKind.REMOTE) },
                onPhotos = { open(SheetKind.PHOTOS) },
            )
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp)) { data ->
            Snackbar(data, containerColor = PanelHi, contentColor = TextHi, shape = RoundedCornerShape(18.dp))
        }

        if (selected != null) {
            HubSheet(visible = sheet != null, onDismiss = { sheet = null }) {
                when (sheet ?: lastSheet) {
                    SheetKind.SEARCH, SheetKind.VOICE -> SearchSheet(hub, selected, startWithVoice = lastSheet == SheetKind.VOICE) { searched ->
                        if (searched) {
                            remoteHint = "Your search is on the TV — swipe to pick a result."
                            lastSheet = SheetKind.REMOTE
                            sheet = SheetKind.REMOTE
                        } else sheet = null
                    }
                    SheetKind.REMOTE -> RemoteSheet(hub, selected, remoteHint)
                    SheetKind.TYPE -> TypeSheet(hub, selected)
                    SheetKind.PHOTOS -> PhotosSheet(hub, selected)
                }
            }
        }

        if (showSettings) SettingsScreen(hub, activity, onClose = { showSettings = false })
    }
}

@Composable
private fun TopBar(
    tvs: List<TvState>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onSettingsTap: () -> Unit,
    onSettingsHold: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tvs.forEachIndexed { i, tv ->
                val sel = i == selectedIndex
                Row(
                    Modifier.height(40.dp).clip(CircleShape)
                        .background(if (sel) Glow else Brush.linearGradient(listOf(Panel, Panel)))
                        .border(1.dp, if (sel) Color.Transparent else Line, CircleShape)
                        .clickable { onSelect(i) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(tv)
                    Spacer(Modifier.width(8.dp))
                    Text(tv.config.name, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, color = if (sel) Color.White else TextHi)
                }
            }
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).combinedClickable(onClick = onSettingsTap, onLongClick = onSettingsHold),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Settings, contentDescription = "Settings (hold)", tint = TextLo) }
    }
}

@Composable
private fun Dock(modifier: Modifier, onType: () -> Unit, onRemote: () -> Unit, onPhotos: () -> Unit) {
    Row(
        modifier.shadow(24.dp, CircleShape).clip(CircleShape).background(PanelHi).border(1.dp, Line, CircleShape).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DockItem(HubIcons.Keyboard, "Type", onClick = onType)
        Row(
            Modifier.height(56.dp).clip(CircleShape).background(Glow).clickable(onClick = onRemote).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HubIcons.Touch, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Text("Remote", color = Color.White, fontWeight = FontWeight.Bold)
        }
        DockItem(HubIcons.Photo, "Photos", onClick = onPhotos)
    }
}

@Composable
private fun DockItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.size(width = 72.dp, height = 56.dp).clip(CircleShape).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = TextHi, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextLo)
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(112.dp).clip(CircleShape).background(Glow), contentAlignment = Alignment.Center) {
            Icon(HubIcons.Tv, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Let's add your TVs", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("Turn on ADB debugging on each TV, then add it here.", color = TextLo)
        Spacer(Modifier.height(24.dp))
        GlowButton("Add a TV", onClick = onAdd)
    }
}
