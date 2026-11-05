package app.redlib.now.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches Redlib pages, transparently handling:
 *  - Anubis proof-of-work challenges (solved on-device, cookie cached),
 *  - load-balancer meta-refresh redirects between instance backends,
 *  - instance rotation when an instance is unreachable or refuses us (403),
 *  - optional user-pinned instance from [app.redlib.now.data.Settings.preferredInstance].
 *
 * Every step is logged under the "NowRedlib" logcat tag.
 */
class RedlibClient(
    private val instancesProvider: suspend () -> List<String> = { InstanceDiscovery.fetchInstanceUrls() }
) {
    data class Response(val html: String, val baseUrl: String, val switchedInstance: Boolean)

    private val currentBase = AtomicReference<String?>(null)
    private var allInstances: List<String> = emptyList()

    /** Currently selected base URL, if any (after warm-up / last successful fetch). */
    fun activeBase(): String? = currentBase.get()

    /**
     * Apply a Settings change immediately: clear sticky base so the next
     * fetch re-warms against Auto or the pinned URL.
     */
    fun applyPreferredFromSettings() {
        currentBase.set(null)
    }

    private fun pinnedFromSettings(): String? {
        val p = app.redlib.now.data.Settings.preferredInstance.trim().removeSuffix("/")
        return if (p.isBlank() || p.equals("auto", ignoreCase = true)) null else p
    }

    /** Health-check instances and pick the first one that serves real posts. */
    suspend fun warmUp(): String? = withContext(Dispatchers.IO) {
        allInstances = instancesProvider()
        val pinned = pinnedFromSettings()
        val order = if (pinned != null) {
            // User pinned a host: try it first, then the rest as emergency fallbacks.
            (listOf(pinned) + allInstances).distinct()
        } else {
            allInstances
        }
        Logd.i("warmUp: ${order.size} candidates (pinned=$pinned): $order")
        val failures = mutableListOf<String>()
        for (candidate in order) {
            try {
                Logd.i("warmUp: trying $candidate ...")
                val resp = fetchInternal(candidate, "/r/popular")
                if (resp != null) {
                    Logd.i("warmUp: OK -> ${resp.baseUrl}")
                    currentBase.set(resp.baseUrl)
                    return@withContext resp.baseUrl
                }
                failures += "$candidate: no usable content"
                Logd.w("warmUp: $candidate returned no usable content")
            } catch (t: Throwable) {
                Logd.e("warmUp: $candidate failed: ${t.message}", t)
                failures += "$candidate: ${t.message}"
            }
            // Strict pin: do not silently roam if the user forced this host.
            if (pinned != null && candidate == pinned) {
                Logd.e("warmUp: pinned instance $pinned failed; not auto-rotating")
                break
            }
        }
        Logd.e("warmUp: ALL instances failed:\n${failures.joinToString("\n")}")
        null
    }

    suspend fun fetch(path: String): Response = withContext(Dispatchers.IO) {
        val pinned = pinnedFromSettings()
        val tried = mutableListOf<String>()
        var base: String = currentBase.get()
            ?: pinned
            ?: warmUp()
            ?: throw Exception("No working Redlib instance found")
        // If pinned and current base drifted (e.g. old session), snap back.
        if (pinned != null && base != pinned && !base.startsWith(pinned)) {
            base = pinned
            currentBase.set(pinned)
        }
        while (true) {
            try {
                Logd.i("fetch: GET $base$path")
                val resp = fetchInternal(base, path)
                    ?: throw Exception("Instance returned no usable content")
                currentBase.set(resp.baseUrl)
                if (resp.switchedInstance) {
                    Logd.i("fetch: load balancer moved us to ${resp.baseUrl}")
                }
                return@withContext Response(resp.html, resp.baseUrl, tried.isNotEmpty())
            } catch (e: InstanceDeadException) {
                Logd.w("fetch: $base is dead (${e.cause?.message}), rotating...")
                tried += base
                if (pinned != null) {
                    // User asked for a specific host — surface the failure instead of hopping away.
                    throw Exception("Pinned instance $pinned is unreachable", e)
                }
                allInstances = (allInstances + InstanceDiscovery.FALLBACK).distinct()
                val next = allInstances.firstOrNull { it !in tried }
                    ?: throw Exception("All Redlib instances failed", e)
                base = next
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private class InstanceDeadException(cause: Throwable?) : Exception(cause)

    /**
     * GET base+path, solving Anubis challenges and following meta-refreshes.
     * Returns null if the final page does not look like Redlib content.
     */
    private fun fetchInternal(base: String, path: String): Response? {
        var currentBase = base
        var currentPath = path
        var hops = 0
        while (hops < 8) {
            hops++
            val url = currentBase + currentPath
            Logd.d("hop#$hops GET $url")
            val call = Http.rawClient.newCall(Request.Builder().url(url).build())
            val resp = call.execute()
            val code = resp.code
            val body = resp.use { it.body?.string() }
            Logd.d("hop#$hops status=$code len=${body?.length ?: 0} cookies=${run { val h = okhttp3.HttpUrl.Companion.run { url.toHttpUrl() }; Http.cookieJar.loadForRequest(h).map { it.name } }}")
            if (body == null) return null

            // 1. Anubis challenge?
            if (Anubis.isChallenge(body)) {
                val challenge = Anubis.extractChallenge(body)
                if (challenge == null) {
                    Logd.w("hop#$hops: Anubis page but challenge unreadable; first 300 chars: ${body.take(300)}")
                    throw InstanceDeadException(IllegalStateException("Challenge unreadable"))
                }
                val t0 = System.currentTimeMillis()
                val solution = kotlinx.coroutines.runBlocking { Anubis.solveOffMain(challenge) }
                Logd.i("hop#$hops: Anubis challenge id=${challenge.id} algo=${challenge.algorithm} diff=${challenge.difficulty} " +
                    "solved nonce=${solution.nonce} in ${System.currentTimeMillis() - t0}ms")
                val passUrl = Anubis.passChallengeUrl(currentBase, currentPath, challenge, solution, 500)
                Http.rawClient.newCall(Request.Builder().url(passUrl).build()).execute().use { pass ->
                    val passBody = pass.body?.string().orEmpty()
                    Logd.i("hop#$hops: pass-challenge status=${pass.code} len=${passBody.length} " +
                        "cookiesNow=${run { val h = okhttp3.HttpUrl.Companion.run { url.toHttpUrl() }; Http.cookieJar.loadForRequest(h).map { it.name } }}")
                }
                continue // retry the original page, now with the clearance cookie
            }

            // 2. Load-balancer meta refresh?
            val refresh = Regex("""http-equiv="refresh"\s+content="\d+;url=([^"]+)"""")
                .find(body)?.groupValues?.get(1)
            if (refresh != null) {
                Logd.i("hop#$hops: meta-refresh -> $refresh")
                val newUrl = if (refresh.startsWith("http")) refresh else currentBase + refresh
                val newBase = Regex("""^(https?://[^/]+)""").find(newUrl)?.groupValues?.get(1)
                val newPath = newUrl.removePrefix(newBase ?: "")
                if (newBase != null) {
                    currentBase = newBase
                    currentPath = newPath
                    continue
                }
                Logd.w("hop#$hops: could not parse meta-refresh target '$newUrl'")
            }

            // 3. Real content?
            val hasTitle = body.contains("post_title")
            val hasPosts = body.contains("id=\"posts\"")
            if (hasTitle || hasPosts) {
                Logd.i("hop#$hops: content OK (post_title=$hasTitle posts=$hasPosts) from $currentBase")
                return Response(body, currentBase, currentBase != base)
            }

            Logd.w("hop#$hops: no Redlib content at $url (status=$code). First 300 chars: ${body.take(300).replace('\n', ' ')}")
            throw InstanceDeadException(IllegalStateException("No Redlib content (HTTP $code)"))
        }
        Logd.w("gave up after $hops hops starting at $base$path")
        return null
    }

    fun baseUrl(): String? = currentBase.get()

    /**
     * Ensure we hold a valid Anubis clearance cookie for [base].
     * Used by media downloads when the host returns challenge HTML / 401-429
     * instead of media bytes. Does not require a full feed parse.
     *
     * @return true if a media retry is worth attempting.
     */
    fun ensureAuth(base: String?): Boolean {
        val b = base?.trim()?.removeSuffix("/") ?: return false
        if (b.isEmpty()) return false
        // A couple of passes — challenge pages sometimes race with cookie settle.
        repeat(2) { pass ->
            try {
                val url = "$b/"
                val body = Http.rawClient.newCall(Request.Builder().url(url).build())
                    .execute().use { it.body?.string().orEmpty() }
                if (body.isEmpty()) return@repeat
                if (!Anubis.isChallenge(body) && "Access Denied" !in body) {
                    return true
                }
                val challenge = Anubis.extractChallenge(body)
                if (challenge == null) {
                    Logd.w("ensureAuth: challenge unreadable / access denied on $b (pass ${pass + 1})")
                    Thread.sleep(300)
                    return@repeat
                }
                val t0 = System.currentTimeMillis()
                val solution = kotlinx.coroutines.runBlocking { Anubis.solveOffMain(challenge) }
                Logd.i(
                    "ensureAuth: solved ${challenge.id} algo=${challenge.algorithm} " +
                        "in ${System.currentTimeMillis() - t0}ms (pass ${pass + 1})",
                )
                val passUrl = Anubis.passChallengeUrl(b, "/", challenge, solution, 500)
                Http.rawClient.newCall(Request.Builder().url(passUrl).build()).execute().use { resp ->
                    Logd.i("ensureAuth: pass-challenge status=${resp.code}")
                }
                Thread.sleep(200)
                // Verify we can fetch without a wall.
                val check = Http.rawClient.newCall(Request.Builder().url(url).build())
                    .execute().use { it.body?.string().orEmpty() }
                if (check.isNotEmpty() && !Anubis.isChallenge(check) && "Access Denied" !in check) {
                    return true
                }
            } catch (t: Throwable) {
                Logd.w("ensureAuth failed for $b: ${t.message}")
                Thread.sleep(300)
            }
        }
        return false
    }
}

