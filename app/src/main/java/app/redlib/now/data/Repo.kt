package app.redlib.now.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import app.redlib.now.net.RedlibClient
import org.json.JSONArray
import app.redlib.now.data.MediaCache
import app.redlib.now.data.FeedCache

/** App-wide singletons: one RedlibClient (its cookie cache survives screens) and subreddit history. */
object Repo {
    val client = RedlibClient()

    private const val PREFS = "sub_history"
    private const val KEY = "history"
    private const val MAX = 25

    private lateinit var prefs: SharedPreferences

    /** Recent subreddits, most recent first. Backed by prefs, observable by Compose. */
    var historyState by androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        MediaCache.init(context)
        FeedCache.init(context)
        Settings.init(context)
        readPosts = loadReadPosts()
    }

    // ---- read-post tracking (for "hide read posts") ----
    private val READ_KEY = "read_posts"
    private val MAX_READ = 500
    private var readPosts: LinkedHashSet<String> = LinkedHashSet()

    private fun loadReadPosts(): LinkedHashSet<String> =
        LinkedHashSet(prefs.getString(READ_KEY, "")!!.split(",").filter { it.isNotBlank() })

    fun isRead(id: String): Boolean = id in readPosts

    fun markRead(id: String) {
        if (id in readPosts) return
        readPosts.add(id)
        while (readPosts.size > MAX_READ) readPosts.remove(readPosts.first())
        prefs.edit().putString(READ_KEY, readPosts.joinToString(",")).apply()
    }

    fun history(): List<String> = historyState

    fun add(sub: String) {
        val s = sub.trim().removePrefix("r/").removePrefix("/r/").lowercase()
        if (s.isEmpty()) return
        val next = (listOf(s) + historyState.filter { it != s }).take(MAX)
        save(next)
        historyState = next
    }

    fun remove(sub: String) {
        val next = historyState.filter { it != sub }
        save(next)
        historyState = next
    }

    private fun load(): List<String> {
        val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun save(list: List<String>) {
        prefs.edit().putString(KEY, JSONArray(list).toString()).apply()
    }

    /** Static suggestions shown under the history in the search screen. */
    val SUGGESTIONS = listOf(
        "popular", "all", "askreddit", "funny", "pics", "gaming", "movies",
        "music", "technology", "worldnews", "aww", "mildlyinteresting",
        "todayilearned", "interestingasfuck", "mademesmile", "unexpected",
    )
}
