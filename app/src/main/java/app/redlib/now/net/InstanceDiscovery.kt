package app.redlib.now.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Discovers public Redlib instances at runtime.
 *
 * Primary source: the canonical list published by the redlib-instances repo
 * (updated daily by upstream). Whether an instance actually serves content is
 * decided by the health check in [RedlibClient], not here; we only exclude
 * non-HTTPS entries (Tor/I2P) statically.
 */
object InstanceDiscovery {

    const val DEFAULT_INSTANCE = "https://redlib.catsarch.com"

    private const val LIST_URL =
        "https://raw.githubusercontent.com/redlib-org/redlib-instances/main/instances.json"

    /** Used when the GitHub list itself is unreachable. */
    val FALLBACK = listOf(
        DEFAULT_INSTANCE,
        "https://safereddit.com",
        "https://redlib.privadency.com",
        "https://redlib.nadeko.net",
        "https://redlib.cow.rip",
    )

    suspend fun fetchInstanceUrls(): List<String> = withContext(Dispatchers.IO) {
        val urls = try {
            val body = URL(LIST_URL).readText()
            Regex(""""url"\s*:\s*"(https://[^"]+)"""")
                .findAll(body)
                .map { it.groupValues[1].removeSuffix("/") }
                .toList()
                .also { Logd.i("instance list from GitHub: $it") }
        } catch (t: Throwable) {
            Logd.w("instance list fetch failed (${t.message}); using fallback")
            emptyList()
        }
        // Preferred default first, then everything else de-duplicated.
        (listOf(DEFAULT_INSTANCE) + urls).distinct()
    }
}
