package app.redlib.now.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * On-device solver for Anubis bot checks used by public Redlib instances.
 *
 * Algorithms (from TecharoHQ/anubis sources):
 *
 *  **fast** — SHA-256(randomData + decimalNonce) must have [difficulty] leading
 *  zero *hex digits* (nibbles). Pass via pass-challenge?id&response&nonce&redir.
 *
 *  **preact** — SHA-256(randomData) as hex is the result; client must wait at
 *  least difficulty×80ms (browser uses ×125ms). Pass via
 *  pass-challenge?id&result&redir. Proven against privacyredirect (v1.27).
 */
object Anubis {

    data class Challenge(
        val id: String,
        val randomData: String,
        val difficulty: Int,
        val algorithm: String = "fast",
    )

    data class Solution(
        val nonce: Long = 0,
        val response: String,
        val algorithm: String,
    )

    /** Returns null if the document is not a solvable Anubis challenge page. */
    fun extractChallenge(html: String): Challenge? {
        if (!html.contains("anubis_challenge")) return null
        val json = Regex(
            """<script id="anubis_challenge"[^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(html)?.groupValues?.get(1)?.trim() ?: return null

        val randomData = Regex(""""randomData"\s*:\s*"([0-9a-fA-F]+)"""")
            .find(json)?.groupValues?.get(1) ?: return null
        val difficulty = Regex(""""difficulty"\s*:\s*(\d+)""")
            .findAll(json)
            .map { it.groupValues[1].toInt() }
            .maxOrNull() ?: return null
        val id = Regex(""""id"\s*:\s*"([0-9a-fA-F-]{36})"""")
            .find(json)?.groupValues?.get(1) ?: return null
        val algorithm = Regex(""""algorithm"\s*:\s*"([a-zA-Z0-9_-]+)"""")
            .find(json)?.groupValues?.get(1)
            ?: Regex(""""method"\s*:\s*"([a-zA-Z0-9_-]+)"""")
                .find(json)?.groupValues?.get(1)
            ?: "fast"
        return Challenge(id, randomData, difficulty, algorithm.lowercase())
    }

    fun solve(challenge: Challenge): Solution = when (challenge.algorithm) {
        "preact" -> solvePreact(challenge)
        else -> solveFast(challenge) // fast, slow, unknown → try fast PoW
    }

    /** preact: result = hex(SHA256(randomData)); no nonce grinding. */
    private fun solvePreact(challenge: Challenge): Solution {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(challenge.randomData.toByteArray(Charsets.UTF_8))
        return Solution(nonce = 0, response = digest.toHex(), algorithm = "preact")
    }

    /**
     * fast: find nonce where SHA256(randomData || decimal(nonce)) has
     * [difficulty] leading zero hex nibbles (same as Anubis JS worker).
     */
    private fun solveFast(challenge: Challenge): Solution {
        val full = challenge.difficulty / 2
        val odd = challenge.difficulty % 2 == 1
        val md = MessageDigest.getInstance("SHA-256")
        val data = challenge.randomData.toByteArray(Charsets.UTF_8)
        var nonce = 0L
        while (true) {
            md.update(data)
            val digest = md.digest(nonce.toString().toByteArray(Charsets.UTF_8))
            var ok = true
            var i = 0
            while (i < full) {
                if (digest[i].toInt() != 0) { ok = false; break }
                i++
            }
            if (ok && odd && (digest[full].toInt() and 0xF0) != 0) ok = false
            if (ok) return Solution(nonce, digest.toHex(), algorithm = challenge.algorithm)
            nonce++
        }
    }

    /**
     * Build pass-challenge URL. For preact uses [result]; for fast uses
     * response+nonce. [elapsedMs] is informational for fast; preact relies on
     * wall-clock wait before the request is made.
     */
    fun passChallengeUrl(
        base: String,
        path: String,
        challenge: Challenge,
        solution: Solution,
        elapsedMs: Long,
    ): String {
        val root = base.trimEnd('/')
        val redir = urlEncode(root + path)
        return when (solution.algorithm) {
            "preact" ->
                "$root/.within.website/x/cmd/anubis/api/pass-challenge" +
                    "?id=${challenge.id}" +
                    "&result=${solution.response}" +
                    "&redir=$redir"
            else ->
                "$root/.within.website/x/cmd/anubis/api/pass-challenge" +
                    "?id=${challenge.id}" +
                    "&response=${solution.response}" +
                    "&nonce=${solution.nonce}" +
                    "&redir=$redir" +
                    "&elapsedTime=${elapsedMs.coerceAtLeast(500)}"
        }
    }

    /**
     * Solve including mandatory preact wait (difficulty × 125ms, matches browser;
     * server requires difficulty × 80ms from IssuedAt).
     */
    suspend fun solveOffMain(challenge: Challenge): Solution =
        withContext(Dispatchers.Default) {
            val solution = solve(challenge)
            if (challenge.algorithm == "preact") {
                // Browser app.tsx: setTimeout(..., difficulty * 125)
                delay((challenge.difficulty * 125L).coerceAtLeast(100L))
            }
            solution
        }

    fun isChallenge(html: String): Boolean =
        html.contains("anubis_challenge") ||
            html.contains("Making sure you&#39;re not a bot") ||
            html.contains("Verifying your browser")

    /** True if body looks like bot-wall / challenge HTML rather than media bytes. */
    fun looksLikeBotWall(bodySample: String): Boolean {
        val s = bodySample.lowercase()
        return isChallenge(bodySample) ||
            s.contains("access denied") ||
            (s.contains("<html") && (s.contains("anubis") || s.contains("bot")))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}
