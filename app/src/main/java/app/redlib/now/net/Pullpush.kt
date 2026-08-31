package app.redlib.now.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Recovers moderation-removed comment bodies from the pullpush archive
 * (the same service Redlib's "view removed comment" link targets), so the
 * original text can be shown inline instead of linking out.
 */
object Pullpush {

    suspend fun fetchRemovedBody(commentId: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.pullpush.io/reddit/search/comment/?ids=$commentId"
            val req = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
            Logd.d("pullpush: fetching archive for $commentId")
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Logd.w("pullpush: ${resp.code} for $commentId")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                val data = JSONObject(body).optJSONArray("data") ?: return@withContext null
                if (data.length() == 0) {
                    Logd.d("pullpush: no archive for $commentId")
                    return@withContext null
                }
                data.getJSONObject(0).optString("body").ifBlank { null }
            }
        } catch (t: Throwable) {
            Logd.e("pullpush: fetch failed for $commentId", t)
            null
        }
    }
}
