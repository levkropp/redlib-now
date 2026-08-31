package app.redlib.now.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.redlib.now.data.FeedCache
import app.redlib.now.data.Repo
import app.redlib.now.model.Post
import app.redlib.now.parse.PostParser

/** Read-only user profile: the author's submitted posts, via /user/<name>. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScreen(
    username: String,
    onBack: () -> Unit,
    onOpenPost: (Post) -> Unit,
    onOpenComments: (Post) -> Unit,
    onOpenMedia: (Post) -> Unit,
) {
    BackHandler(onBack = onBack)

    val path = "/user/$username/submitted"
    var posts by remember { mutableStateOf<List<Post>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(username) {
        FeedCache.loadFeed(path)?.let { posts = it.posts }
        try {
            val resp = Repo.client.fetch(path)
            val parsed = PostParser.parseFeed(resp.html, resp.baseUrl)
            posts = parsed
            FeedCache.saveFeed(path, parsed)
        } catch (t: Throwable) {
            if (posts == null) error = "Failed to load profile: ${t.message}"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(
                if (app.redlib.now.data.Settings.swipeBack)
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var accX = 0f
                            var accY = 0f
                            do {
                                val event = awaitPointerEvent()
                                val ch = event.changes.firstOrNull()
                                if (ch != null) {
                                    accX += ch.position.x - ch.previousPosition.x
                                    accY += ch.position.y - ch.previousPosition.y
                                }
                            } while (event.changes.any { it.pressed })
                            if (accX > 120f && kotlin.math.abs(accX) > 2 * kotlin.math.abs(accY)) onBack()
                        }
                    }
                else Modifier
            )
    ) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            title = {
                Text("u/$username", fontWeight = FontWeight.Bold, maxLines = 1)
            },
        )
        when {
            posts == null && error == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            posts == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            posts!!.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No posts found for u/$username", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                items(posts!!, key = { it.id }) { post ->
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
