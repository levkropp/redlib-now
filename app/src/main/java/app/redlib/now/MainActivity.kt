package app.redlib.now

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

                when {
                    showSettings -> SettingsScreen(onBack = { showSettings = false })
                    showSaved -> SavedScreen(
                        onBack = { showSaved = false },
                        onOpenPost = { post ->
                            showSaved = false
                            if (post.imageUrl != null && post.externalUrl == null) viewerPost = post else commentsPost = post
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
                            if (post.imageUrl != null && post.externalUrl == null) viewerPost = post else commentsPost = post
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
                            if (post.imageUrl != null) viewerPost = post else commentsPost = post
                        },
                        onOpenComments = { commentsPost = it },
                        onOpenMedia = { viewerPost = it },
                    )
                    commentsPost != null -> CommentsScreen(
                        post = commentsPost!!,
                        onBack = { commentsPost = null },
                        onOpenMedia = { viewerPost = it },
                        onOpenUser = { userProfile = it },
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
                            if (post.imageUrl != null) viewerPost = post else commentsPost = post
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
                    )
                }
              }
            }
        }
    }
}
