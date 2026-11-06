package dev.daniel.tvhub.tv

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Runs on the TV. Being an enabled notification listener is what lets the helper read the TV's
 * media sessions (the phone turns it on over adb). It never reads or touches notifications.
 */
class MediaListener : NotificationListenerService()

/** Triggered by the phone; writes what each app reports as playing to nowplaying.json. */
class NowPlayingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { NowPlaying.write(context.applicationContext) } catch (_: Throwable) {} finally { pending.finish() }
        }.start()
    }
}

object NowPlaying {
    const val FILE = "nowplaying.json"

    fun write(ctx: Context) {
        val out = JSONObject()
        val arr = JSONArray()
        try {
            val msm = ctx.getSystemService(MediaSessionManager::class.java)
            val sessions = msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java))
            for (mc in sessions) {
                val md = mc.metadata
                val d = md?.description
                val o = JSONObject()
                    .put("pkg", mc.packageName)
                    .put("state", mc.playbackState?.state ?: 0)
                    .put("title", md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: d?.title?.toString() ?: JSONObject.NULL)
                    .put("subtitle", md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: d?.subtitle?.toString() ?: JSONObject.NULL)
                    .put("mediaId", md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: d?.mediaId ?: JSONObject.NULL)
                    .put("mediaUri", md?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI) ?: d?.mediaUri?.toString() ?: JSONObject.NULL)
                    .put("artUri", md?.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                        ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI) ?: d?.iconUri?.toString() ?: JSONObject.NULL)
                val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) ?: d?.iconBitmap
                if (art != null) runCatching { o.put("art", encode(art)) }
                arr.put(o)
            }
            out.put("ok", true)
        } catch (t: SecurityException) {
            out.put("ok", false).put("error", "no-access")
        }
        out.put("sessions", arr)
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(out.toString())
        tmp.renameTo(File(dir, FILE))
    }

    private fun encode(src: Bitmap): String {
        val w = minOf(480, src.width)
        val h = (src.height * (w.toFloat() / src.width)).toInt().coerceAtLeast(1)
        val bmp = if (w != src.width) Bitmap.createScaledBitmap(src, w, h, true) else src
        val bytes = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, bytes)
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }
}
