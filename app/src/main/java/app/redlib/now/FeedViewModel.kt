package app.redlib.now

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    private val loadedPaths = mutableSetOf<String>()

    init {
        load("/", initial = true)
    }

    fun load(path: String, initial: Boolean = false) {
        currentPath = path
        state = state.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val response = client.fetch(pathWithSort(path))
                val posts = PostParser.parseFeed(response.html, response.baseUrl)
                loadedPaths += path
                state = FeedUiState(
                    loading = false,
                    posts = posts,
                    instanceStatus = "served by ${response.baseUrl.removePrefix("https://")}",
                )
            } catch (e: Exception) {
                state = state.copy(
                    loading = false,
                    posts = if (initial) emptyList() else state.posts,
                    error = "Failed to load: ${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    fun refresh() = load(currentPath)

    private fun pathWithSort(path: String): String = path
}
