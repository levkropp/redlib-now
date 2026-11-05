package app.redlib.now.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.redlib.now.data.Repo

/**
 * Discovery grid/list of suggested communities.
 * Pinning is not done here — open a sub and use the feed top-bar bookmark.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubredditBrowseScreen(
    onBack: () -> Unit,
    onOpenSubreddit: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    var grid by remember { mutableStateOf(true) }
    val subs = Repo.SUGGESTIONS

    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Browse subreddits", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { grid = !grid }) {
                        Icon(
                            if (grid) Icons.Filled.List else Icons.Filled.GridOn,
                            contentDescription = "Toggle layout",
                        )
                    }
                },
            )
            if (grid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(subs, key = { it }) { sub ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .padding(5.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(subredditColor(sub).copy(alpha = 0.18f))
                                .clickable { onOpenSubreddit(sub) }
                                .padding(vertical = 16.dp, horizontal = 6.dp),
                        ) {
                            Text(
                                "r/$sub",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = subredditColor(sub),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(subs, key = { it }) { sub ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(5.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(subredditColor(sub).copy(alpha = 0.12f))
                                .clickable { onOpenSubreddit(sub) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(
                                "r/$sub",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = subredditColor(sub),
                                textAlign = TextAlign.Start,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
