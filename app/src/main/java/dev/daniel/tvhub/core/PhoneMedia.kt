package dev.daniel.tvhub.core

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.InputStream

data class PhoneMediaItem(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val isVideo: Boolean,
    val dateAdded: Long,
    val durationMs: Long,
)

object PhoneMedia {
    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasPermission(context: Context): Boolean {
        val granted = permissions().any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        // Android 14 "Allow limited access" still lets us read the photos you picked.
        val partial = Build.VERSION.SDK_INT >= 34 &&
            context.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED
        return granted || partial
    }

    fun recent(context: Context, limit: Int = 300): List<PhoneMediaItem> {
        val images = query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, limit)
        val videos = query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, limit)
        return (images + videos).sortedByDescending { it.dateAdded }.take(limit)
    }

    private fun query(context: Context, base: Uri, video: Boolean, limit: Int): List<PhoneMediaItem> {
        val cols = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED,
        )
        if (video) cols += MediaStore.Video.VideoColumns.DURATION
        val out = mutableListOf<PhoneMediaItem>()
        runCatching {
            context.contentResolver.query(base, cols.toTypedArray(), null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val iDur = if (video) c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(iId)
                    out += PhoneMediaItem(
                        uri = ContentUris.withAppendedId(base, id),
                        name = c.getString(iName) ?: "media",
                        mime = c.getString(iMime) ?: if (video) "video/mp4" else "image/jpeg",
                        size = c.getLong(iSize),
                        isVideo = video,
                        dateAdded = c.getLong(iDate),
                        durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                    )
                }
            }
        }
        return out
    }

    /** Wraps a gallery item so the [MediaServer] can stream it (with seeking). */
    fun asSource(context: Context, item: PhoneMediaItem): MediaSource {
        val size = if (item.size > 0) item.size else runCatching {
            context.contentResolver.openFileDescriptor(item.uri, "r")?.use { it.statSize }
        }.getOrNull() ?: 0L
        return object : MediaSource {
            override val mime = item.mime
            override val size = size
            override fun open(offset: Long): InputStream {
                val pfd = context.contentResolver.openFileDescriptor(item.uri, "r")
                    ?: error("Can't open ${item.name}")
                val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                runCatching { stream.channel.position(offset) }.onFailure { stream.skipFully(offset) }
                return stream
            }
        }
    }
}
