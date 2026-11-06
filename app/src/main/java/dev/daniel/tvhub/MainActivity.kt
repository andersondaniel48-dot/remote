package dev.daniel.tvhub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
