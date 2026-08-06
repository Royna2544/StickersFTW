package com.royna.stickersftw

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/** Supplies a custom default Coil [ImageLoader] whose network fetches are
 * capped to one in-flight request per host. Compose only requests images
 * for a LazyVerticalGrid's currently-composed (visible) items, in the order
 * they're laid out top-to-bottom -- capping concurrency to 1 makes actual
 * completion order follow that same top-to-bottom request order instead of
 * whichever request happens to finish first, which is what the sticker
 * picker's "load images in order of up towards down" relies on. */
class StickersFtwApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: Context): ImageLoader {
        val sequentialClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 1 })
            .build()

        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { sequentialClient }))
            }
            .build()
    }
}
