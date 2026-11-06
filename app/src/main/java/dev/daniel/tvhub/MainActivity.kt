package dev.daniel.tvhub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.daniel.tvhub.core.Keys
import dev.daniel.tvhub.core.Kiosk
import dev.daniel.tvhub.ui.HubScreen
import dev.daniel.tvhub.ui.TvHubTheme

class MainActivity : ComponentActivity() {
    private val hub get() = (application as TvHubApp).hub
    private var plugged = false

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { updatePlugged(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Kiosk.applyOwnerPolicies(this)
        setContent {
            TvHubTheme { HubScreen(hub = hub, activity = this) }
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Share → TV Hub" from a browser or streaming app saves the link to the Guide. */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (!hub.addGuide(text, i.getStringExtra(Intent.EXTRA_SUBJECT))) {
            android.widget.Toast.makeText(this, "No link found to save", android.widget.Toast.LENGTH_SHORT).show()
        }
        i.action = null
    }

    override fun onResume() {
        super.onResume()
        hub.start()
        ContextCompat.registerReceiver(this, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { updatePlugged(it) }
        applyChrome()
        if (Kiosk.isDeviceOwner(this) && hub.store.autoLock && !Kiosk.isLocked(this)) Kiosk.lock(this)
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(battery) }
        hub.stop()
    }

    /** The phone's volume buttons turn the selected TV up and down. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && hub.store.volumeKeysControlTv) {
            val tvs = hub.tvs.value
            val id = tvs.firstOrNull { it.config.id == hub.store.selectedTv }?.config?.id ?: tvs.firstOrNull()?.config?.id
            if (id != null) {
                hub.key(id, if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) Keys.VOL_UP else Keys.VOL_DOWN)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && hub.store.volumeKeysControlTv && hub.tvs.value.isNotEmpty()) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun updatePlugged(i: Intent) {
        plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        refreshKeepOn()
    }

    fun refreshKeepOn() {
        if (plugged && hub.store.keepScreenOnWhileCharging) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** In kiosk mode, hide the status and navigation bars (swipe from the edge to peek). */
    fun applyChrome() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (Kiosk.isHomeEnabled(this) || Kiosk.isLocked(this)) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
