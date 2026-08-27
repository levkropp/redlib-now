package app.redlib.now

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import app.redlib.now.data.Repo
import app.redlib.now.model.Post
import app.redlib.now.ui.CommentsScreen
import app.redlib.now.ui.FeedScreen
import app.redlib.now.ui.MediaViewer
import app.redlib.now.ui.NowRedlibTheme
import app.redlib.now.ui.SearchScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            NowRedlibTheme {
                val vm: FeedViewModel = viewModel()
                var viewerPost by remember { mutableStateOf<Post?>(null) }
                var commentsPost by remember { mutableStateOf<Post?>(null) }
                var showSearch by remember { mutableStateOf(false) }

                when {
                    viewerPost != null -> MediaViewer(
                        post = viewerPost!!,
                        onClose = { viewerPost = null },
                    )
                    commentsPost != null -> CommentsScreen(
                        post = commentsPost!!,
                        onBack = { commentsPost = null },
                        onOpenMedia = { viewerPost = it },
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
                        onOpenSearch = { showSearch = true },
                        onRefresh = { vm.refresh() },
                        onOpenPost = { post ->
                            if (post.imageUrl != null) viewerPost = post else commentsPost = post
                        },
                        onOpenComments = { commentsPost = it },
                        onOpenMedia = { viewerPost = it },
                        onOpenFeed = { vm.load(it) },
                    )
                }
            }
        }
    }
}
