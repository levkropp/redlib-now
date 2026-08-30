package app.redlib.now.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.redlib.now.data.Repo
import app.redlib.now.model.Post

/** Locally saved (bookmarked) posts — classic "Saved" screen, no account needed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
    onBack: () -> Unit,
    onOpenPost: (Post) -> Unit,
    onOpenComments: (Post) -> Unit,
    onOpenMedia: (Post) -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            title = { Text("Saved posts", fontWeight = FontWeight.Bold) },
        )
        val saved = Repo.savedState
        if (saved.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Nothing saved yet — tap the bookmark on any card.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                items(saved, key = { it.id }) { post ->
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
