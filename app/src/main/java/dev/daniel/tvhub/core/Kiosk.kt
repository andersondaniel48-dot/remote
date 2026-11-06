package dev.daniel.tvhub.core

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import dev.daniel.tvhub.AdminReceiver

/** Makes TV Hub the only thing on the phone. */
object Kiosk {
    private fun alias(ctx: Context) = ComponentName(ctx, "${ctx.packageName}.HomeAlias")
    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun isHomeEnabled(ctx: Context) =
        ctx.packageManager.getComponentEnabledSetting(alias(ctx)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setHomeEnabled(ctx: Context, on: Boolean) {
        ctx.packageManager.setComponentEnabledSetting(
            alias(ctx),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /** Opens the system "Default home app" picker. */
    fun openHomePicker(activity: Activity) {
        val tries = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        )
        for (i in tries) {
            if (runCatching { activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    fun isDeviceOwner(ctx: Context) = dpm(ctx)?.isDeviceOwnerApp(ctx.packageName) == true

    /** With device-owner set up, lock mode is silent and can't be exited without the app. */
    fun applyOwnerPolicies(ctx: Context) {
        val dpm = dpm(ctx) ?: return
        if (!isDeviceOwner(ctx)) return
        runCatching { dpm.setLockTaskPackages(admin(ctx), arrayOf(ctx.packageName)) }
        PhoneMedia.permissions().forEach { p ->
            runCatching {
                dpm.setPermissionGrantState(admin(ctx), ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            // Hide the status bar and notifications in lock mode, but keep the power menu so you can restart.
            runCatching { dpm.setLockTaskFeatures(admin(ctx), DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS) }
        }
    }

    fun removeDeviceOwner(ctx: Context) {
        @Suppress("DEPRECATION")
        runCatching { dpm(ctx)?.clearDeviceOwnerApp(ctx.packageName) }
    }

    fun isLocked(ctx: Context): Boolean {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return false
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    fun lock(activity: Activity) { runCatching { activity.startLockTask() } }
    fun unlock(activity: Activity) { runCatching { activity.stopLockTask() } }
}
