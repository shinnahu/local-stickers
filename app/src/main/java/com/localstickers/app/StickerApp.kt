package com.localstickers.app

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.localstickers.app.data.Backup
import com.localstickers.app.data.PackRepository

class StickerApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        PackRepository.get(this)
        Backup.clearLeftovers(this)
    }

    /** Coil image loader able to play animated WebP stickers and GIFs. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .crossfade(false)
            .build()
}
