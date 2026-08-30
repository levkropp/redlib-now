package app.redlib.now

import android.app.Application
import app.redlib.now.net.Http
import coil.ImageLoader
import coil.ImageLoaderFactory

/**
 * Boots a shared Coil image loader on the same OkHttpClient that owns the
 * Anubis clearance cookies, so instance-proxied images (/img/..., /preview/...)
 * load without triggering new bot checks.
 */
class NowApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(Http.client)
            .crossfade(true)
            // Animated GIFs (giphy embeds in comments) decode & auto-play.
            .components {
                add(coil.decode.GifDecoder.Factory())
            }
            .build()
}
