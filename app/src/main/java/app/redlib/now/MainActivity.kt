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
import app.redlib.now.ui.GalleryScreen
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
        // Bug #5: keep the status bar visible by default so the user keeps
        // clock / battery / signal awareness. Only the navigation bar is
        // left to the system; immersive auto-hide kicks in for fullscreen
        // media playback from inside MediaViewer.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            show(WindowInsetsCompat.Type.statusBars())
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
                // In-feed sub search survives opening a post (hoisted out of FeedScreen).
                var feedSearchExpanded by remember { mutableStateOf(false) }
                var feedSearchQuery by remember { mutableStateOf("") }
                var feedSearchResults by remember { mutableStateOf<List<app.redlib.now.model.Post>?>(null) }
                var feedSearchError by remember { mutableStateOf<String?>(null) }
                var commentMedia by remember { mutableStateOf<Triple<String, String?, Boolean>?>(null) }
                var galleryViewer by remember { mutableStateOf<Pair<String, String>?>(null) }

                when {
                    // Overlay screens first so underlays (Saved / search / feed) stay alive.
                    galleryViewer != null -> app.redlib.now.ui.GalleryScreen(
                        permalink = galleryViewer!!.first,
                        title = galleryViewer!!.second,
                        onBack = { galleryViewer = null },
                        onOpenComments = {
                            val perm = galleryViewer!!.first
                            val title = galleryViewer!!.second
                            galleryViewer = null
                            commentsPost = Post(
                                id = "gallery-" + perm,
                                subreddit = "", author = null,
                                title = title, permalink = perm,
                                flair = null, selfTextPreview = null, imageUrl = null, videoUrl = null,
                                isVideo = false, score = null, commentCount = null, timeAgo = null, nsfw = false,
                            )
                        },
                    )
                    commentMedia != null -> MediaViewer(
                        title = commentMedia!!.first,
                        imageUrl = commentMedia!!.second,
                        videoUrl = if (commentMedia!!.third) commentMedia!!.second else null,
                        isVideo = commentMedia!!.third,
                        onClose = { commentMedia = null },
                    )
                    viewerPost != null -> MediaViewer(
                        post = viewerPost!!,
                        onClose = { viewerPost = null },
                    )
                    commentsPost != null -> CommentsScreen(
                        post = commentsPost!!,
                        onBack = { commentsPost = null },
                        onOpenMedia = { viewerPost = it },
                        onOpenUser = { userProfile = it },
                        onOpenCommentMedia = { url, isVideo -> commentMedia = Triple("Comment media", url, isVideo) },
                    )
                    userProfile != null -> UserScreen(
                        username = userProfile!!,
                        onBack = { userProfile = null },
                        onOpenPost = { post ->
                            if (post.isGallery || post.imageUrl == null) commentsPost = post else viewerPost = post
                        },
                        onOpenComments = { commentsPost = it },
                        onOpenMedia = { viewerPost = it },
                        onOpenSubreddit = { sub ->
                            userProfile = null
                            vm.load("/r/$sub")
                        },
                    )
                    showSettings -> SettingsScreen(onBack = { showSettings = false })
                    showSaved -> SavedScreen(
                        onBack = { showSaved = false },
                        onOpenPost = { post ->
                            if (post.isGallery || post.imageUrl == null || post.externalUrl != null) commentsPost = post else viewerPost = post
                        },
                        onOpenComments = { commentsPost = it },
                        onOpenMedia = { viewerPost = it },
                        onOpenSubreddit = { sub ->
                            showSaved = false
                            vm.load("/r/$sub")
                        },
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
                            if (post.isGallery || post.imageUrl == null || post.externalUrl != null) commentsPost = post else viewerPost = post
                        },
                        onOpenComments = { commentsPost = it },
                        onOpenMedia = { viewerPost = it },
                        onOpenSubreddit = { sub ->
                            postSearchOpen = false
                            vm.load("/r/$sub")
                        },
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
                        positionKey = vm.positionKey(),
                        onSort = { s, t -> vm.setSort(s, t) },
                        onOpenSearch = { showSearch = true },
                        onRefresh = { vm.refresh() },
                        onLoadMore = { vm.loadMore() },
                        onRetryLoadMore = { vm.retryLoadMore() },
                        onOpenPost = { post ->
                            if (post.isGallery) galleryViewer = Pair(post.permalink, post.title)
                            else if (post.imageUrl == null) commentsPost = post else viewerPost = post
                        },
                        onOpenComments = {
                            app.redlib.now.net.Logd.i("nav: commentsPost set " + it.id)
                            commentsPost = it
                        },
                        onOpenMedia = { viewerPost = it },
                        onOpenUser = { userProfile = it },
                        onOpenGallery = { post ->
                            app.redlib.now.net.Logd.i("nav: galleryViewer set " + post.id)
                            galleryViewer = Pair(post.permalink, post.title)
                        },
                        onOpenSettings = { showSettings = true },
                        onOpenSaved = { showSaved = true },
                        onOpenBrowse = { showBrowse = true },
                        onOpenPostSearch = { sub ->
                            postSearchSub = sub
                            postSearchOpen = true
                        },
                        onOpenFeed = { path ->
                            // Leaving this sub (or going home) clears in-feed search.
                            if (path != vm.currentPath) {
                                feedSearchExpanded = false
                                feedSearchQuery = ""
                                feedSearchResults = null
                                feedSearchError = null
                            }
                            vm.load(path)
                        },
                        statePositions = vm.positions,
                        onExitApp = { finish() },
                        searchExpanded = feedSearchExpanded,
                        onSearchExpandedChange = { feedSearchExpanded = it },
                        searchQuery = feedSearchQuery,
                        onSearchQueryChange = { feedSearchQuery = it },
                        searchResults = feedSearchResults,
                        onSearchResultsChange = { feedSearchResults = it },
                        searchError = feedSearchError,
                        onSearchErrorChange = { feedSearchError = it },
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
