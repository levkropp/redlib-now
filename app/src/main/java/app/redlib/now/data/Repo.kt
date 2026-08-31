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
    var savedState by androidx.compose.runtime.mutableStateOf<List<app.redlib.now.model.Post>>(emptyList())
        private set

    fun loadSaved() {
        savedPostsInternal = FeedCache.loadFeed("_saved_")?.posts ?: emptyList()
        savedState = savedPostsInternal
    }

    fun isSaved(id: String): Boolean = savedPostsInternal.any { it.id == id }

    fun toggleSave(post: app.redlib.now.model.Post) {
        savedPostsInternal = if (isSaved(post.id)) {
            savedPostsInternal.filter { it.id != post.id }
        } else {
            listOf(post) + savedPostsInternal
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
