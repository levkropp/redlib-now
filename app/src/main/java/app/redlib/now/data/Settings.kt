package app.redlib.now.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App settings, parity with the classic Now for Reddit preference screens:
 * Appearance / Behaviour / Filters / Gestures. Backed by SharedPreferences,
 * exposed as Compose state so UI reacts instantly.
 */
object Settings {
    private lateinit var prefs: SharedPreferences

    // ---- Appearance ----
    var cardSize by mutableStateOf("compact")        // compact, normal, large
    var showSelftext by mutableStateOf(true)
    var showMedia by mutableStateOf(true)
    var linkPreviews by mutableStateOf(true)
    var dataSaver by mutableStateOf(false)           // "image quality": skip prefetch, cap previews
    var textScale by mutableStateOf(1.0f)            // 0.85 / 1.0 / 1.15 / 1.3

    // ---- Behaviour ----
    var collapseThreads by mutableStateOf(false)     // collapse comment replies by default
    var collapseAutoMod by mutableStateOf(true)      // AutoModerator starts collapsed
    var suggestedCommentSort by mutableStateOf("best")
    var hideReadPosts by mutableStateOf(false)
    var rememberSubredditPosition by mutableStateOf(true)

    // ---- Filters ----
    var hideNsfw by mutableStateOf(false)
    var hideNsfwPreviews by mutableStateOf(true)
    var subredditFilters by mutableStateOf<List<String>>(emptyList())
    var userFilters by mutableStateOf<List<String>>(emptyList())
    var keywordFilters by mutableStateOf<List<String>>(emptyList())

    // ---- Gestures ----
    var swipeBack by mutableStateOf(true)
    var tapToCloseImages by mutableStateOf(true)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        cardSize = prefs.getString("card_size", "compact") ?: "compact"
        showSelftext = prefs.getBoolean("show_selftext", true)
        showMedia = prefs.getBoolean("show_media", true)
        linkPreviews = prefs.getBoolean("link_previews", true)
        dataSaver = prefs.getBoolean("data_saver", false)
        textScale = prefs.getFloat("text_scale", 1.0f)
        collapseThreads = prefs.getBoolean("collapse_threads", false)
        collapseAutoMod = prefs.getBoolean("collapse_automod", true)
        suggestedCommentSort = prefs.getString("suggested_comment_sort", "best") ?: "best"
        hideReadPosts = prefs.getBoolean("hide_read_posts", false)
        rememberSubredditPosition = prefs.getBoolean("remember_position", true)
        hideNsfw = prefs.getBoolean("hide_nsfw", false)
        hideNsfwPreviews = prefs.getBoolean("hide_nsfw_previews", true)
        subredditFilters = prefs.getString("filter_subs", "")!!.split(",").filter { it.isNotBlank() }
        userFilters = prefs.getString("filter_users", "")!!.split(",").filter { it.isNotBlank() }
        keywordFilters = prefs.getString("filter_keywords", "")!!.split(",").filter { it.isNotBlank() }
        swipeBack = prefs.getBoolean("swipe_back", true)
        tapToCloseImages = prefs.getBoolean("tap_to_close", true)
    }

    private fun put(key: String, value: Any?) {
        val e = prefs.edit()
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is Float -> e.putFloat(key, value)
            is String -> e.putString(key, value)
            is List<*> -> e.putString(key, (value as List<String>).joinToString(","))
            null -> {}
        }
        e.apply()
    }

    fun updateCardSize(v: String) { cardSize = v; put("card_size", v) }
    fun updateShowSelftext(v: Boolean) { showSelftext = v; put("show_selftext", v) }
    fun updateShowMedia(v: Boolean) { showMedia = v; put("show_media", v) }
    fun updateLinkPreviews(v: Boolean) { linkPreviews = v; put("link_previews", v) }
    fun updateDataSaver(v: Boolean) { dataSaver = v; put("data_saver", v) }
    fun updateTextScale(v: Float) { textScale = v; put("text_scale", v) }
    fun updateCollapseThreads(v: Boolean) { collapseThreads = v; put("collapse_threads", v) }
    fun updateCollapseAutoMod(v: Boolean) { collapseAutoMod = v; put("collapse_automod", v) }
    fun updateSuggestedCommentSort(v: String) { suggestedCommentSort = v; put("suggested_comment_sort", v) }
    fun updateHideReadPosts(v: Boolean) { hideReadPosts = v; put("hide_read_posts", v) }
    fun updateRememberPosition(v: Boolean) { rememberSubredditPosition = v; put("remember_position", v) }
    fun updateHideNsfw(v: Boolean) { hideNsfw = v; put("hide_nsfw", v) }
    fun updateHideNsfwPreviews(v: Boolean) { hideNsfwPreviews = v; put("hide_nsfw_previews", v) }
    fun updateSwipeBack(v: Boolean) { swipeBack = v; put("swipe_back", v) }
    fun updateTapToClose(v: Boolean) { tapToCloseImages = v; put("tap_to_close", v) }

    fun updateSubredditFilters(list: List<String>) { subredditFilters = list; put("filter_subs", list) }
    fun updateUserFilters(list: List<String>) { userFilters = list; put("filter_users", list) }
    fun updateKeywordFilters(list: List<String>) { keywordFilters = list; put("filter_keywords", list) }

    /** Whether a feed post passes the active filters. */
    fun postVisible(p: app.redlib.now.model.Post): Boolean {
        if (hideNsfw && p.nsfw) return false
        if (subredditFilters.any { it.isNotBlank() && p.subreddit.equals(it, true) }) return false
        if (userFilters.any { it.isNotBlank() && p.author.equals(it, true) }) return false
        if (keywordFilters.any { it.isNotBlank() && (p.title + " " + (p.selfTextPreview ?: "")).contains(it, true) }) return false
        return true
    }
}
