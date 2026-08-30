package app.redlib.now

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.redlib.now.data.FeedCache
import app.redlib.now.data.MediaCache
import app.redlib.now.data.Repo
import app.redlib.now.model.Post
import app.redlib.now.parse.PostParser
import app.redlib.now.ui.FeedUiState
import kotlinx.coroutines.launch

class FeedViewModel : ViewModel() {

    private val client = Repo.client

    var state by mutableStateOf(FeedUiState())
        private set

    var currentPath by mutableStateOf("/")
        private set

    // Sort state (parity with the classic app's sort menus).
    var feedSort by mutableStateOf("hot")      // hot, new, rising, top, controversial
        private set
    var feedTime by mutableStateOf("all")      // hour, day, week, month, year, all
        private set

    private val loadedPaths = mutableSetOf<String>()

    init {
        load("/", initial = true)
    }

    fun setSort(sort: String, time: String = feedTime) {
        feedSort = sort
        feedTime = time
        load(currentPath)
    }

    /** Full fetch path for the current feed + sort. */
    private fun fetchPath(base: String): String {
        val sort = if (feedSort == "hot") "" else "/$feedSort"
        val time = if (feedSort == "top" || feedSort == "controversial") "?t=$feedTime" else ""
        val joined = if (base == "/") "/${feedSort}" else "$base$sort"
        return when {
            feedSort == "hot" -> base
            base == "/" -> joined + time   // e.g. /new, /top?t=week
            else -> base + sort + time     // e.g. /r/aww/new, /r/aww/top?t=week
        }
    }

    fun load(path: String, initial: Boolean = false) {
        currentPath = path

        // Serve the 72h offline copy instantly when we have one.
        val cached = FeedCache.loadFeed(path)
        if (cached != null) {
            state = FeedUiState(
                loading = true, // refreshing, but content is already visible
                posts = cached.posts,
                instanceStatus = "cached ${app.redlib.now.data.MediaCache.ageString(cached.savedAt)} — refreshing…",
            )
        } else {
            state = FeedUiState(loading = true)
        }

        viewModelScope.launch {
            try {
                val response = client.fetch(fetchPath(path))
                val posts = PostParser.parseFeed(response.html, response.baseUrl)
                loadedPaths += path
                state = FeedUiState(
                    loading = false,
                    posts = posts,
                    instanceStatus = "served by ${response.baseUrl.removePrefix("https://")}",
                )
                FeedCache.saveFeed(path, posts)
                MediaCache.prefetch(posts)
            } catch (e: Exception) {
                if (cached != null) {
                    // Offline: keep showing the cached feed.
                    state = state.copy(loading = false, error = null)
                } else {
                    state = state.copy(
                        loading = false,
                        posts = if (initial) emptyList() else state.posts,
                        error = "Failed to load: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun refresh() = load(currentPath)
}
