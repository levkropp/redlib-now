package app.redlib.now.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.redlib.now.model.Post
import kotlinx.coroutines.launch

/**
 * Main feed: compact top bar (drawer, sort label, search, refresh) and the
 * post card list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    state: FeedUiState,
    currentFeed: String,
    onOpenSearch: () -> Unit,
    onRefresh: () -> Unit,
    onOpenPost: (Post) -> Unit,
    onOpenComments: (Post) -> Unit,
    onOpenMedia: (Post) -> Unit,
    onOpenFeed: (String) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Now for Redlib",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(20.dp),
                )
                listOf("/" to "Frontpage", "/r/popular" to "Popular", "/r/all" to "All").forEach { (path, label) ->
                    NavigationDrawerItem(
                        label = { Text(label) },
                        selected = currentFeed == path,
                        onClick = { onOpenFeed(path) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                listOf(
                    "askreddit", "funny", "pics", "gaming", "movies", "music",
                    "technology", "worldnews", "interestingasfuck", "mademesmile",
                ).forEach { sub ->
                    NavigationDrawerItem(
                        label = { Text("r/$sub") },
                        selected = currentFeed == "/r/$sub",
                        onClick = { onOpenFeed("/r/$sub") },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    state.instanceStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
        },
    ) {
        Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu")
                    }
                },
                title = {
                    Text(
                        when {
                            currentFeed == "/" -> "Frontpage"
                            currentFeed.startsWith("/r/") ->
                                "r/" + currentFeed.removePrefix("/r/").removeSuffix("/")
                            else -> "Now for Redlib"
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search subreddits")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        snackbarHost = {
            val hostState = remember { SnackbarHostState() }
            LaunchedEffect(state.error) {
                state.error?.let { hostState.showSnackbar(it) }
            }
            SnackbarHost(hostState)
        },
    ) { padding ->
        when {
            state.loading && state.posts.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    if (state.instanceStatus.isNotBlank()) {
                        Text(
                            state.instanceStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
            state.posts.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text(if (state.error != null) state.error else "Nothing to show yet.") }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(state.posts, key = { it.id }) { post ->
                    PostCard(
                        post = post,
                        onClick = { onOpenPost(post) },
                        onOpenComments = { onOpenComments(post) },
                        onOpenMedia = { onOpenMedia(post) },
                    )
                }
                if (state.loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
        }
    }
}

data class FeedUiState(
    val loading: Boolean = false,
    val posts: List<Post> = emptyList(),
    val error: String? = null,
    val instanceStatus: String = "",
)
