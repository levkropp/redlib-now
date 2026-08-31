package app.redlib.now

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewmodel.compose.viewModel
import app.redlib.now.data.Settings
import app.redlib.now.data.Repo
import app.redlib.now.model.Post
import app.redlib.now.ui.CommentsScreen
import app.redlib.now.ui.FeedScreen
import app.redlib.now.ui.MediaViewer
import app.redlib.now.ui.NowRedlibTheme
import app.redlib.now.ui.SearchScreen
import app.redlib.now.ui.SettingsScreen
import app.redlib.now.ui.SavedScreen
import app.redlib.now.ui.SubredditBrowseScreen
import app.redlib.now.ui.PostSearchScreen
import app.redlib.now.ui.UserScreen

class MainActivity : ComponentActivity() {

    /** Reddit URL pending routing (VIEW intents and shared text). */
    private val incomingLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingLink.value = extractLink(intent)
        Repo.init(applicationContext)
        enableEdgeToEdge()
        // Immersive: status bar stays hidden while browsing, swipe from the
        // top edge to bring it back transiently.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setContent {
            val baseDensity = LocalDensity.current
            NowRedlibTheme {
              CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, Settings.textScale)) {
                val vm: FeedViewModel = viewModel()
                var viewerPost by remember { mutableStateOf<Post?>(null) }
                var commentsPost by remember { mutableStateOf<Post?>(null) }
                var userProfile by remember { mutableStateOf<String?>(null) }
                var showSearch by remember { mutableStateOf(false) }
                var showSettings by remember { mutableStateOf(false) }
                var showSaved by remember { mutableStateOf(false) }
                var showBrowse by remember { mutableStateOf(false) }
                var postSearchSub by remember { mutableStateOf<String?>(null) }
                var postSearchOpen by remember { mutableStateOf(false) }
                var commentMedia by remember { mutableStateOf<Triple<String, String?, Boolean>?>(null) }

                when {
                    commentMedia != null -> MediaViewer(
                        title = commentMedia!!.first,
                        imageUrl = commentMedia!!.second,
                        videoUrl = if (commentMedia!!.third) commentMedia!!.second else null,
                        isVideo = commentMedia!!.third,
                        onClose = { commentMedia = null },
                    )
                    showSettings -> SettingsScreen(onBack = { showSettings = false })
                    showSaved -> SavedScreen(
                        onBack = { showSaved = false },
                        onOpenPost = { post ->
                            showSaved = false
                            if (post.isGallery || post.imageUrl == null || post.externalUrl != null) commentsPost = post else viewerPost = post
                        },
                        onOpenComments = { commentsPost = it },
                        onOpenMedia = { viewerPost = it },
                    )
                    showBrowse -> SubredditBrowseScreen(
                        onBack = { showBrowse = false },
                        onOpenSubreddit = {
                            showBrowse = false
                            vm.load("/r/$it")
                        },
                    )
                    postSearchOpen -> PostSearchScreen(
                        subreddit = postSearchSub,
                        onBack = { postSearchOpen = false },
                        onOpenPost = { post ->
                            postSearchOpen = false
                            if (post.isGallery || post.imageUrl == null || post.externalUrl != null) commentsPost = post else viewerPost = post
                        },
                        onOpenComments = { commentsPost = it },
                        onOpenMedia = { viewerPost = it },
                    )
                    viewerPost != null -> MediaViewer(
                        post = viewerPost!!,
                        onClose = { viewerPost = null },
                    )
                    userProfile != null -> UserScreen(
                        username = userProfile!!,
                        onBack = { userProfile = null },
                        onOpenPost = { post ->
                            userProfile = null
                            if (post.isGallery || post.imageUrl == null) commentsPost = post else viewerPost = post
                        },
                        onOpenComments = { commentsPost = it },
                        onOpenMedia = { viewerPost = it },
                    )
                    commentsPost != null -> CommentsScreen(
                        post = commentsPost!!,
                        onBack = { commentsPost = null },
                        onOpenMedia = { viewerPost = it },
                        onOpenUser = { userProfile = it },
                        onOpenCommentMedia = { url, isVideo -> commentMedia = Triple("Comment media", url, isVideo) },
                    )
                    showSearch -> SearchScreen(
                        onDismiss = { showSearch = false },
                        onOpenSubreddit = {
                            showSearch = false
                            vm.load("/r/$it")
                        },
                    )
                    else -> FeedScreen(
                        state = vm.state,
                        currentFeed = vm.currentPath,
                        feedSort = vm.feedSort,
                        feedTime = vm.feedTime,
                        onSort = { s, t -> vm.setSort(s, t) },
                        onOpenSearch = { showSearch = true },
                        onRefresh = { vm.refresh() },
                        onOpenPost = { post ->
                            if (post.isGallery || post.imageUrl == null) commentsPost = post else viewerPost = post
                        },
                        onOpenComments = { commentsPost = it },
                        onOpenMedia = { viewerPost = it },
                        onOpenUser = { userProfile = it },
                        onOpenSettings = { showSettings = true },
                        onOpenSaved = { showSaved = true },
                        onOpenBrowse = { showBrowse = true },
                        onOpenPostSearch = { sub ->
                            postSearchSub = sub
                            postSearchOpen = true
                        },
                        onOpenFeed = { vm.load(it) },
                        statePositions = vm.positions,
                    )
                }

            // Route pending reddit links: /r/x feeds, comment threads, user pages.
            val pending = incomingLink.value
            LaunchedEffect(pending) {
                if (pending == null) return@LaunchedEffect
                incomingLink.value = null
                routeRedditLink(pending, openFeed = { path -> vm.load(path) },
                    openComments = { commentsPost = it }, openUser = { userProfile = it })
            }
              }
            }

        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        incomingLink.value = extractLink(intent)
    }

    /** Pull a URL out of a VIEW intent's data or a shared-text SEND intent. */
    private fun extractLink(intent: android.content.Intent?): String? {
        if (intent == null) return null
        intent.data?.let { return it.toString() }
        if (intent.action == android.content.Intent.ACTION_SEND) {
            val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: return null
            return Regex("(https?://\\S+)").find(text)?.groupValues?.get(1)
        }
        return null
    }

    private fun routeRedditLink(
        url: String,
        openFeed: (String) -> Unit,
        openComments: (Post) -> Unit,
        openUser: (String) -> Unit,
    ) {
        val uri = android.net.Uri.parse(url) ?: return
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("old.")?.removePrefix("m.") ?: return
        if (host != "reddit.com") return
        val segs = uri.pathSegments.filter { it.isNotBlank() }
        when {
            segs.size >= 4 && segs[0] == "r" && segs[2] == "comments" -> {
                val sub = segs[1]; val id = segs[3]
                val slug = if (segs.size >= 5) segs[4].replace('_', ' ') else "Post"
                openComments(Post(
                    id = id, subreddit = sub, author = null, title = slug,
                    permalink = "/r/$sub/comments/$id/" + if (segs.size >= 5) segs[4] + "/" else "",
                    flair = null, selfTextPreview = null, imageUrl = null, videoUrl = null,
                    isVideo = false, score = null, commentCount = null, timeAgo = null, nsfw = false,
                ))
            }
            segs.size >= 2 && segs[0] == "r" -> openFeed("/r/${segs[1]}")
            segs.size >= 2 && (segs[0] == "u" || segs[0] == "user") -> openUser(segs[1])
            else -> openFeed("/")
        }
    }
}
