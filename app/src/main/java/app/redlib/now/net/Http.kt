package app.redlib.now.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * OkHttp client with a persistent-per-process cookie jar. Anubis clearance
 * cookies issued by [RedlibClient] live here, so challenges are solved once
 * per instance until they expire server-side.
 *
 * Cookies are keyed by (name, domain, path): re-issue REPLACES the old value.
 * Appending duplicates would corrupt the Cookie header and make Anubis
 * re-challenge every request forever.
 */
class MemoryCookieJar : CookieJar {
    private val store = LinkedHashMap<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (c in cookies) {
            val key = "${c.name}|${c.domain}|${c.path}"
            if (c.expiresAt in 1..now) {
                store.remove(key)
            } else {
                store[key] = c
            }
        }
        store.entries.removeAll { it.value.expiresAt in 1..now }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store.values.filter { it.expiresAt > now && it.matches(url) }
    }

    fun clear() = store.clear()
}

object Http {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    val cookieJar = MemoryCookieJar()

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request()
            Logd.d("HTTP -> ${req.url}")
            val resp = chain.proceed(
                req.newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
            )
            Logd.d("HTTP ${resp.code} <- ${req.url}")
            resp
        }
        .build()
}
