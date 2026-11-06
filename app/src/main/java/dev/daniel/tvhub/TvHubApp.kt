package dev.daniel.tvhub

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import dev.daniel.tvhub.core.TvHub

class TvHubApp : Application(), ImageLoaderFactory {
    val hub: TvHub by lazy { TvHub(this) }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
}
