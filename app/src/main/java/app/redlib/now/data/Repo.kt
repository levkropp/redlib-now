package app.redlib.now.data

import android.content.Context
import android.content.SharedPreferences
import app.redlib.now.net.RedlibClient
import org.json.JSONArray

/** App-wide singletons: one RedlibClient (its cookie cache survives screens) and subreddit history. */
object Repo {
    val client = RedlibClient()

    private const val PREFS = "sub_history"
    private const val KEY = "history"
    private const val MAX = 25

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun history(): List<String> {
        val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun add(sub: String) {
        val s = sub.trim().removePrefix("r/").removePrefix("/r/").lowercase()
        if (s.isEmpty()) return
        val next = (listOf(s) + history().filter { it != s }).take(MAX)
        save(next)
    }

    fun remove(sub: String) = save(history().filter { it != sub })

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
