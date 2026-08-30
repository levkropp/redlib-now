package app.redlib.now.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.redlib.now.data.Repo
import app.redlib.now.model.Post
import app.redlib.now.parse.PostParser

/**
 * Post search — within a subreddit or globally (r/all), via the
 * instance's /search and /r/x/search endpoints.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostSearchScreen(
    subreddit: String?,          // null = global search
    onBack: () -> Unit,
    onOpenPost: (Post) -> Unit,
    onOpenComments: (Post) -> Unit,
    onOpenMedia: (Post) -> Unit,
) {
    BackHandler(onBack = onBack)
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Post>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        searching = true
        searched = true
        val path = (subreddit?.let { "/r/$it" } ?: "") + "/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
        scope.launch {
            try {
                val resp = Repo.client.fetch(path)
                results = PostParser.parseFeed(resp.html, resp.baseUrl)
                    .filter { app.redlib.now.data.Settings.postVisible(it) }
            } catch (t: Throwable) {
                results = emptyList()
            } finally {
                searching = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            title = { Text(if (subreddit != null) "Search r/$subreddit" else "Search Reddit", fontWeight = FontWeight.Bold) },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search posts…") },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { runSearch() }, enabled = query.isNotBlank()) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
        }
        when {
            searching -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            searched && results.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No results.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                items(results, key = { it.id }) { post ->
                    PostCard(
                        post = post,
                        onClick = { onOpenPost(post) },
                        onOpenComments = { onOpenComments(post) },
                        onOpenMedia = { onOpenMedia(post) },
                    )
                }
            }
        }
    }
}
