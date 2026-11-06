package dev.daniel.tvhub.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Runs on the TV. The phone triggers it with `am broadcast`; it writes every launchable app's
 * real name, TV banner and icon to catalog.json so the phone can show proper app tiles.
 */
class AppCatalogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { AppCatalog.write(context.applicationContext) } catch (_: Throwable) {} finally { pending.finish() }
        }.start()
    }
}

object AppCatalog {
    const val FILE = "catalog.json"

    fun write(ctx: Context) {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0).forEach { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg != ctx.packageName && pkg !in seen) seen[pkg] = ri
            }
        }
        val arr = JSONArray()
        for ((pkg, ri) in seen) {
            val ai = ri.activityInfo
            val banner = runCatching { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) }.getOrNull()
            val icon = runCatching { ri.loadIcon(pm) }.getOrNull()
            arr.put(
                JSONObject()
                    .put("pkg", pkg)
                    .put("component", "$pkg/${ai.name}")
                    .put("label", runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg))
                    .put("banner", banner?.let { encode(it, 320, 180) } ?: JSONObject.NULL)
                    .put("icon", icon?.let { encode(it, 144, 144) } ?: JSONObject.NULL),
            )
        }
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(File(dir, FILE))
    }

    private fun encode(d: Drawable, w: Int, h: Int): String {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        bmp.compress(fmt, 82, out)
        bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
