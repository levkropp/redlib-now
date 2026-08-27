package app.redlib.now.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * On-device solver for the Anubis proof-of-work bot check used by most public
 * Redlib instances.
 *
 * Protocol (Anubis v1.2x, e.g. privadency v1.27.0):
 *  1. The challenged page embeds JSON in `<script id="anubis_challenge">`:
 *     `{"rules":{"algorithm":"fast","difficulty":N,...},"challenge":{"id":...,"randomData":...}}`
 *  2. The client finds `nonce` such that SHA-256(randomData + nonce) has
 *     `difficulty` leading zero *hex digits*, then calls
 *     `/.within.website/x/cmd/anubis/api/pass-challenge?id=&response=&nonce=&redir=&elapsedTime=`.
 *  3. That endpoint sets an auth cookie and redirects back to the target page.
 */
object Anubis {

    data class Challenge(val id: String, val randomData: String, val difficulty: Int)
    data class Solution(val nonce: Long, val response: String)

    /** Returns null if the document is not an Anubis challenge page. */
    fun extractChallenge(html: String): Challenge? {
        val marker = "anubis_challenge"
        if (!html.contains(marker)) return null
        val json = Regex("""<script id="anubis_challenge"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim() ?: return null
        // Minimal field extraction: avoid a JSON dependency, the shape is stable.
        val randomData = Regex(""""randomData"\s*:\s*"([0-9a-f]+)"""").find(json)?.groupValues?.get(1)
            ?: return null
        val difficulty = Regex(""""difficulty"\s*:\s*(\d+)""").findAll(json)
            .map { it.groupValues[1].toInt() }
            .maxOrNull() ?: return null
        val id = Regex(""""id"\s*:\s*"([0-9a-f-]{36})"""").find(json)?.groupValues?.get(1)
            ?: return null
        return Challenge(id, randomData, difficulty)
    }

    fun solve(challenge: Challenge): Solution {
        val full = challenge.difficulty / 2
        val odd = challenge.difficulty % 2 == 1
        val md = MessageDigest.getInstance("SHA-256")
        val data = challenge.randomData.toByteArray(Charsets.UTF_8)
        var nonce: Long = 0
        while (true) {
            md.update(data)
            // Append decimal nonce without allocating a new array per try where possible.
            val n = nonce.toString().toByteArray(Charsets.UTF_8)
            val digest = md.digest(n)
            var ok = true
            var i = 0
            while (i < full) {
                if (digest[i].toInt() != 0) { ok = false; break }
                i++
            }
            if (ok && odd && (digest[full].toInt() and 0xF0) != 0) ok = false
            if (ok) return Solution(nonce, digest.toHex())
            nonce++
        }
    }

    fun passChallengeUrl(base: String, path: String, challenge: Challenge, solution: Solution, elapsedMs: Long): String {
        val sep = if (base.endsWith("/")) "" else ""
        return "${base}$sep/.within.website/x/cmd/anubis/api/pass-challenge" +
            "?id=${challenge.id}" +
            "&response=${solution.response}" +
            "&nonce=${solution.nonce}" +
            "&redir=${urlEncode(base + path)}" +
            "&elapsedTime=${elapsedMs.coerceAtLeast(500)}"
    }

    /** True if this HTML is an Anubis interstitial (fresh challenge or stale page). */
    fun isChallenge(html: String): Boolean =
        html.contains("anubis_challenge") || html.contains("Making sure you&#39;re not a bot")

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    suspend fun solveOffMain(challenge: Challenge): Solution =
        withContext(Dispatchers.Default) { solve(challenge) }
}
