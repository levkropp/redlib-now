package app.redlib.now

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.redlib.now.data.FeedCache
import app.redlib.now.data.MediaCache
import app.redlib.now.data.Repo
import app.redlib.now.data.Settings
import app.redlib.now.parse.PostParser
import app.redlib.now.ui.FeedUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class FeedViewModel : ViewModel() {

    private val client = Repo.client

    var state by mutableStateOf(FeedUiState())
        private set

    var currentPath by mutableStateOf("/")
        private set

    var feedSort by mutableStateOf("hot")
        private set
    var feedTime by mutableStateOf("all")
        private set

    private val loadedPaths = mutableSetOf<String>()
    private var fetchJob: Job? = null
    private var loadMoreJob: Job? = null

    /** Per-feed scroll positions keyed by path|sort|time. */
    val positions = mutableMapOf<String, Pair<Int, Int>>()

    init {
        load("/", initial = true)
    }

    fun positionKey(path: String = currentPath, sort: String = feedSort, time: String = feedTime): String =
        "$path|$sort|$time"

    fun setSort(sort: String, time: String = feedTime) {
        feedSort = sort
        feedTime = time
        positions.remove(positionKey())
        load(currentPath)
    }

    private fun fetchPath(base: String): String {
        val sort = if (feedSort == "hot") "" else "/$feedSort"
        val time = if (feedSort == "top" || feedSort == "controversial") "?t=$feedTime" else ""
        val joined = if (base == "/") "/${feedSort}" else "$base$sort"
        return when {
            feedSort == "hot" -> base
            base == "/" -> joined + time
            else -> base + sort + time
        }
    }

    private fun pathWithAfter(basePath: String, after: String): String {
        val sep = if (basePath.contains('?')) '&' else '?'
        return basePath + sep + "after=" + java.net.URLEncoder.encode(after, "UTF-8")
    }

    fun load(path: String, initial: Boolean = false) {
        currentPath = path
        loadMoreJob?.cancel()

        val cached = FeedCache.loadFeed(path)
        if (cached != null) {
            state = FeedUiState(
                loading = true,
                posts = cached.posts,
                instanceStatus = "cached ${MediaCache.ageString(cached.savedAt)} — refreshing…",
                after = null,
                loadingMore = false,
                endReached = false,
                loadMoreFailed = false,
            )
        } else {
            state = FeedUiState(loading = true)
        }

        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            try {
                val response = client.fetch(fetchPath(path))
                val page = PostParser.parseFeedPage(response.html, response.baseUrl)
                val posts = page.posts
                    .filter { Settings.postVisible(it) }
                    .filter { !Settings.hideReadPosts || !Repo.isRead(it.id) }
                loadedPaths += path
                state = FeedUiState(
                    loading = false,
                    posts = posts,
                    instanceStatus = "served by ${response.baseUrl.removePrefix("https://")}",
                    after = page.after,
                    loadingMore = false,
                    endReached = page.after == null,
                    loadMoreFailed = false,
                )
                FeedCache.saveFeed(path, posts)
                if (cached == null) MediaCache.prefetch(posts)
            } catch (e: CancellationException) {
                // Don't treat cancel as an error; clear loading flags.
                state = state.copy(loading = false, loadingMore = false)
                throw e
            } catch (e: Exception) {
                if (cached != null) {
                    state = state.copy(loading = false, loadingMore = false, error = null)
                } else {
                    state = state.copy(
                        loading = false,
                        loadingMore = false,
                        posts = if (initial) emptyList() else state.posts,
                        error = "Failed to load: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun refresh() = load(currentPath)

    fun loadMore() {
        val after = state.after ?: return
        if (state.loading || state.loadingMore || state.endReached) return
        if (loadMoreJob?.isActive == true) return

        loadMoreJob = viewModelScope.launch {
            state = state.copy(loadingMore = true, loadMoreFailed = false, error = null)
            try {
                val base = fetchPath(currentPath)
                val response = client.fetch(pathWithAfter(base, after))
                val page = PostParser.parseFeedPage(response.html, response.baseUrl)
                val more = page.posts
                    .filter { Settings.postVisible(it) }
                    .filter { !Settings.hideReadPosts || !Repo.isRead(it.id) }
                val existing = state.posts.map { it.id }.toHashSet()
                val merged = state.posts + more.filter { it.id !in existing }
                state = state.copy(
                    loadingMore = false,
                    loadMoreFailed = false,
                    posts = merged,
                    after = page.after,
                    endReached = page.after == null || more.isEmpty(),
                    instanceStatus = "served by ${response.baseUrl.removePrefix("https://")}",
                )
                FeedCache.saveFeed(currentPath, merged)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(
                    loadingMore = false,
                    loadMoreFailed = true,
                    error = "Failed to load more: ${e.message ?: e.javaClass.simpleName}",
                )
            } finally {
                // Always clear spinner — including cancel races.
                if (state.loadingMore) {
                    state = state.copy(loadingMore = false)
                }
            }
        }
    }

    /** User tapped "retry" on the footer after a failed load-more. */
    fun retryLoadMore() {
        state = state.copy(loadMoreFailed = false, error = null)
        loadMore()
    }

    fun markRead(id: String) {
        Repo.markRead(id)
        if (Settings.hideReadPosts) {
            state = state.copy(posts = state.posts.filter { it.id != id })
        }
    }
}
