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

/**
 * App-wide singletons: RedlibClient, pinned subs, and search history.
 *
 * Two separate lists on purpose:
 * - [pinnedState] — explicit subscriptions (pin/unpin only on the subreddit feed top bar)
 * - [searchHistoryState] — recent lookups from the search screen (delete = forget search only)
 */
object Repo {
    val client = RedlibClient()

    private const val PREFS = "sub_history"
    private const val KEY_PINNED = "pinned"
    /** Legacy key — migrated into pinned once. */
    private const val KEY_LEGACY_HISTORY = "history"
    private const val KEY_SEARCH_HISTORY = "search_history"
    private const val MAX_PINNED = 50
    private const val MAX_SEARCH_HISTORY = 25

    private lateinit var prefs: SharedPreferences

    /** Explicitly pinned subreddits (sidebar). Observable by Compose. */
    var pinnedState by androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * Recent search opens, most recent first. Does **not** include pinned
     * subs (those live in the drawer). Observable by Compose.
     */
    var searchHistoryState by androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        pinnedState = loadPinned()
        searchHistoryState = loadSearchHistory()
        MediaCache.init(context)
        FeedCache.init(context)
        Settings.init(context)
        readPosts = loadReadPosts()
        loadSaved()
    }

    // ---- read-post tracking (for "hide read posts") ----
    private val READ_KEY = "read_posts"
    private val MAX_READ = 500
    private var readPosts: LinkedHashSet<String> = LinkedHashSet()

    private fun loadReadPosts(): LinkedHashSet<String> =
        LinkedHashSet(prefs.getString(READ_KEY, "")!!.split(",").filter { it.isNotBlank() })

    fun isRead(id: String): Boolean = id in readPosts

    // ---- saved posts (local bookmarks) ----
    private val SAVED_KEY = "saved_posts"
    private var savedPostsInternal: List<app.redlib.now.model.Post> = emptyList()
    // Observable set of saved post IDs so isSaved() is a Compose state read.
    // Without this, PostCard's bookmark icon never recomposes on toggle.
    private val savedIdsState = androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())
    var savedState by androidx.compose.runtime.mutableStateOf<List<app.redlib.now.model.Post>>(emptyList())
        private set

    fun loadSaved() {
        savedPostsInternal = FeedCache.loadFeed("_saved_")?.posts ?: emptyList()
        savedState = savedPostsInternal
        savedIdsState.value = savedPostsInternal.mapTo(LinkedHashSet<String>()) { it.id }
    }

    fun isSaved(id: String): Boolean = id in savedIdsState.value

    fun toggleSave(post: app.redlib.now.model.Post) {
        if (isSaved(post.id)) {
            savedPostsInternal = savedPostsInternal.filter { it.id != post.id }
            savedIdsState.value = savedIdsState.value - post.id
        } else {
            savedPostsInternal = listOf(post) + savedPostsInternal
            savedIdsState.value = savedIdsState.value + post.id
        }
        savedState = savedPostsInternal
        FeedCache.saveFeed("_saved_", savedPostsInternal)
    }

    fun markRead(id: String) {
        if (id in readPosts) return
        readPosts.add(id)
        while (readPosts.size > MAX_READ) readPosts.remove(readPosts.first())
        prefs.edit().putString(READ_KEY, readPosts.joinToString(",")).apply()
    }

    fun history(): List<String> = pinnedState

    fun isPinned(sub: String): Boolean {
        val s = normalize(sub)
        return s.isNotEmpty() && s in pinnedState
    }

    /** Pin a subreddit (feed top-bar only). Also drops it from search history. */
    fun pin(sub: String) {
        val s = normalize(sub)
        if (s.isEmpty()) return
        val next = (listOf(s) + pinnedState.filter { it != s }).take(MAX_PINNED)
        saveJson(KEY_PINNED, next)
        pinnedState = next
        // Pinned items don't belong in search history.
        if (s in searchHistoryState) removeFromSearchHistory(s)
    }

    /** Unpin a subreddit (feed top-bar only). Does not touch search history. */
    fun unpin(sub: String) {
        val s = normalize(sub)
        if (s.isEmpty()) return
        val next = pinnedState.filter { it != s }
        saveJson(KEY_PINNED, next)
        pinnedState = next
    }

    /** @deprecated Prefer [pin] / [unpin]. */
    fun add(sub: String) = pin(sub)

    /** @deprecated Prefer [unpin]. */
    fun remove(sub: String) = unpin(sub)

    /**
     * Record that the user opened [sub] from search.
     * Skips if already pinned (sidebar covers those).
     */
    fun recordSearch(sub: String) {
        val s = normalize(sub)
        if (s.isEmpty() || s in pinnedState) return
        val next = (listOf(s) + searchHistoryState.filter { it != s }).take(MAX_SEARCH_HISTORY)
        saveJson(KEY_SEARCH_HISTORY, next)
        searchHistoryState = next
    }

    /** Forget a search-history entry only — never unpins. */
    fun removeFromSearchHistory(sub: String) {
        val s = normalize(sub)
        val next = searchHistoryState.filter { it != s }
        saveJson(KEY_SEARCH_HISTORY, next)
        searchHistoryState = next
    }

    private fun normalize(sub: String): String =
        sub.trim().removePrefix("r/").removePrefix("/r/").lowercase()

    private fun loadPinned(): List<String> {
        val raw = prefs.getString(KEY_PINNED, null)
        if (raw != null) return parseJsonList(raw)
        // One-time migration from the old single "history" list.
        val legacy = prefs.getString(KEY_LEGACY_HISTORY, null) ?: return emptyList()
        val migrated = parseJsonList(legacy)
        saveJson(KEY_PINNED, migrated)
        return migrated
    }

    private fun loadSearchHistory(): List<String> =
        parseJsonList(prefs.getString(KEY_SEARCH_HISTORY, "[]") ?: "[]")
            .filter { it !in pinnedState }

    private fun parseJsonList(raw: String): List<String> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun saveJson(key: String, list: List<String>) {
        prefs.edit().putString(key, JSONArray(list).toString()).apply()
    }

    /** Static suggestions shown under the history in the search screen. */
    val SUGGESTIONS = listOf(
        "AnimalsBeingBros",
        "AnimalsBeingDerps",
        "Anime",
        "Architecture",
        "Art",
        "AskCulinary",
        "AskHistorians",
        "AskReddit",
        "AskScience",
        "AskWomen",
        "BBQ",
        "BeAmazed",
        "Beer",
        "BikiniBottomTwitter",
        "Boxing",
        "Breadit",
        "CampingandHiking",
        "Cartalk",
        "Coffee",
        "Cooking",
        "Cricket",
        "CrossStitch",
        "DIY",
        "DataArt",
        "Design",
        "DesignPorn",
        "Drawing",
        "EarthPorn",
        "EatCheapAndHealthy",
        "Eldenring",
        "Europe",
        "F1",
        "Fishing",
        "Fitness",
        "FoodPorn",
        "FutureWhatIf",
        "Futurology",
        "GameDeals",
        "Geography",
        "GlobalOffensive",
        "Golf",
        "Guitar",
        "HistoryAnimals",
        "HistoryMemes",
        "HistoryPorn",
        "HomeImprovement",
        "Icecream",
        "India",
        "Japan",
        "Jazz",
        "LifeProTips",
        "MMA",
        "MachineLearning",
        "MagicArena",
        "Manga",
        "MapPorn",
        "Marvel",
        "Meditation",
        "Metal",
        "Monitors",
        "Music",
        "NatureIsFuckingLit",
        "NintendoSwitch",
        "NoStupidQuestions",
        "OSHA",
        "OldSchoolCool",
        "Physics",
        "PowerMetal",
        "PublicFreakout",
        "Robotics",
        "RoomPorn",
        "Rustlang",
        "Skateboarding",
        "SkincareAddiction",
        "SpacePorn",
        "Spanish",
        "StarWars",
        "Tennis",
        "Whisky",
        "Wholesomememes",
        "Wildlands",
        "Woodworking",
        "astronomy",
        "aww",
        "baking",
        "baseball",
        "birding",
        "boardgames",
        "bodyweightfitness",
        "books",
        "buildapc",
        "calligraphy",
        "cars",
        "castiron",
        "cats",
        "chess",
        "climbing",
        "coins",
        "comics",
        "coolguides",
        "crafts",
        "crochet",
        "css",
        "cycling",
        "dataisbeautiful",
        "dogs",
        "explainlikeimfive",
        "fantasy",
        "femalefashionadvice",
        "fermentation",
        "food",
        "formula1",
        "fountainpens",
        "frugal",
        "funny",
        "gaming",
        "gardening",
        "gifs",
        "golang",
        "hiking",
        "hiphopheads",
        "homebrewing",
        "houseplants",
        "html",
        "knives",
        "languagelearning",
        "law",
        "lego",
        "listentothis",
        "literature",
        "malefashionadvice",
        "math",
        "mildlyinteresting",
        "minimalism",
        "movies",
        "nature",
        "pcgaming",
        "philosophy",
        "photography",
        "pics",
        "pokemon",
        "politics",
        "popular",
        "printSF",
        "productivity",
        "psychology",
        "puzzles",
        "recipes",
        "rickandmorty",
        "rocketlaunches",
        "rpg",
        "science",
        "selfimprovement",
        "sewing",
        "skiing",
        "skydiving",
        "soccer",
        "specializedtools",
        "sports",
        "standupshots",
        "streetart",
        "streetwear",
        "succulents",
        "surfing",
        "swimming",
        "technology",
        "television",
        "theater",
        "todayilearned",
        "travel",
        "typescript",
        "unexpected",
        "upliftingnews",
        "videos",
        "vinyl",
        "wallstreetbets",
        "watches",
        "webcomics",
        "webdev",
        "worldbuilding",
        "worldnews",
        "writing",
        "xboxone",
    )
}
