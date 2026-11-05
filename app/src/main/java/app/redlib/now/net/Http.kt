package app.redlib.now.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * OkHttp client with a persistent-per-process cookie jar. Anubis clearance
 * cookies issued by [RedlibClient] live here, so challenges are solved once
 * per instance until they expire server-side.
 *
 * [rawClient] has no Anubis-retry interceptor — used by challenge solve itself
 * to avoid recursion. [client] is what the rest of the app (and Coil) use.
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

    /** Last time we finished an ensureAuth attempt (success or fail). */
    val lastAuthMs = AtomicLong(0L)

    /** Serialize ensureAuth so a burst of Coil thumbs shares one solve. */
    private val authLock = Any()

    /**
     * Resolve Anubis for [origin]. Concurrent callers block on the same lock
     * so we don't stampede; we still always try — viewing is the point.
     */
    fun refreshAuth(origin: String): Boolean {
        synchronized(authLock) {
            return try {
                Logd.i("Http.refreshAuth: $origin")
                val ok = app.redlib.now.data.Repo.client.ensureAuth(origin)
                lastAuthMs.set(System.currentTimeMillis())
                ok
            } catch (t: Throwable) {
                Logd.w("Http.refreshAuth failed: ${t.message}")
                lastAuthMs.set(System.currentTimeMillis())
                false
            }
        }
    }

    private fun uaInterceptor() = Interceptor { chain ->
        val resp = chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
        )
        Logd.d("HTTP ${resp.code} <- ${chain.request().url}")
        resp
    }

    private fun baseBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor(uaInterceptor())

    /** No Anubis auto-retry — for pass-challenge and ensureAuth only. */
    val rawClient: OkHttpClient = baseBuilder().build()

    /**
     * App + Coil client. On challenge HTML / 401-403-429, re-auth and retry
     * several times with backoff. Never "give up after one challenge."
     */
    val client: OkHttpClient = baseBuilder().build()
}
