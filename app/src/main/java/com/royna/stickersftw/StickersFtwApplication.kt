package com.royna.stickersftw

import android.app.Application
import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/** Supplies a custom default Coil [ImageLoader] whose network fetches are
 * capped to one in-flight request per host. Compose only requests images
 * for a LazyVerticalGrid's currently-composed (visible) items, in the order
 * they're laid out top-to-bottom -- capping concurrency to 1 makes actual
 * completion order follow that same top-to-bottom request order instead of
 * whichever request happens to finish first, which is what the sticker
 * picker's "load images in order of up towards down" relies on.
 *
 * [AnimatedImageDecoder] (API 28+, backed by [android.graphics.ImageDecoder])
 * is what makes every AsyncImage in the app -- pack cards, previews, the
 * full sticker grid -- play converted animated WebP stickers instead of
 * freezing on frame 0. Without it Coil falls back to BitmapFactory, which
 * only ever decodes a single frame. Below API 28 stickers still render, just
 * as a static first frame, since ImageDecoder itself doesn't exist there. */
class StickersFtwApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: Context): ImageLoader {
        val sequentialClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 1 })
            .build()

        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { sequentialClient }))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                }
            }
            .build()
    }
}
