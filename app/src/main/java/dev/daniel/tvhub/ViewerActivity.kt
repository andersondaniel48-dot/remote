package dev.daniel.tvhub

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs on the TV. The phone starts it with a link to a photo or video that the phone is serving,
 * and it shows it full screen. The TV remote (or the phone) controls playback.
 */
class ViewerActivity : Activity() {
    private lateinit var image: ImageView
    private lateinit var video: VideoView
    private lateinit var caption: TextView
    private lateinit var spinner: ProgressBar
    private val ui = Handler(Looper.getMainLooper())
    private var loadId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        video = VideoView(this).apply { visibility = View.GONE }
        spinner = ProgressBar(this)
        caption = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(48, 24, 48, 36)
        }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        root.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(spinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(caption, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        setContentView(root)
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent?) {
        val uri = intent?.data ?: run { finish(); return }
        val mime = intent.type ?: ""
        val title = intent.getStringExtra("title") ?: ""
        val id = ++loadId
        runCatching { video.stopPlayback() }
        image.setImageDrawable(null)
        spinner.visibility = View.VISIBLE
        flashCaption(title)

        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
            image.visibility = View.GONE
            video.visibility = View.VISIBLE
            video.setOnPreparedListener { spinner.visibility = View.GONE; video.start() }
            video.setOnErrorListener { _, _, _ ->
                spinner.visibility = View.GONE
                caption.text = "This TV can't play that video format."
                caption.alpha = 1f
                true
            }
            video.setOnCompletionListener { flashCaption("Finished — $title") }
            video.setVideoURI(uri)
        } else {
            video.visibility = View.GONE
            image.visibility = View.VISIBLE
            Thread {
                val bmp = runCatching { loadImage(uri) }.getOrNull()
                ui.post {
                    if (id != loadId) return@post
                    spinner.visibility = View.GONE
                    if (bmp != null) image.setImageBitmap(bmp) else { caption.text = "Couldn't load that photo."; caption.alpha = 1f }
                }
            }.start()
        }
    }

    private fun flashCaption(text: String) {
        caption.text = text
        caption.alpha = 1f
        caption.animate().cancel()
        caption.animate().alpha(0f).setStartDelay(4000).setDuration(800).start()
    }

    private fun loadImage(uri: Uri): Bitmap? {
        val conn = URL(uri.toString()).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 30000
        val bytes = conn.inputStream.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxW = maxOf(resources.displayMetrics.widthPixels, 1280)
        val maxH = maxOf(resources.displayMetrics.heightPixels, 720)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW || bounds.outHeight / (sample * 2) >= maxH) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val rotation = runCatching {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (video.visibility != View.VISIBLE) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (video.isPlaying) { video.pause(); flashCaption("Paused") } else { video.start(); flashCaption("Playing") }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> video.start()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> video.pause()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> video.seekTo(maxOf(0, video.currentPosition - 10_000))
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> video.seekTo(video.currentPosition + 10_000)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onStop() {
        super.onStop()
        runCatching { video.stopPlayback() }
        finish()
    }
}
