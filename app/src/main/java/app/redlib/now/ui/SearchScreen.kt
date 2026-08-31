package app.redlib.now.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.redlib.now.data.Repo

/**
 * Subreddit picker: history first (with per-row delete), then suggestions.
 * Confirming a query jumps straight to r/<query>.
 */
@Composable
fun SearchScreen(
    onDismiss: () -> Unit,
    onOpenSubreddit: (String) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var query by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(Repo.history()) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun open(sub: String) {
        Repo.add(sub)
        onOpenSubreddit(sub.trim().removePrefix("r/").removePrefix("/r/").lowercase())
    }

    val q = query.trim().removePrefix("r/").removePrefix("/r/")
    val filteredSuggestions = Repo.SUGGESTIONS.filter { it.contains(q, ignoreCase = true) && it !in history }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search subreddits…") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .focusRequester(focusRequester),
                )
                IconButton(onClick = { if (q.isNotBlank()) open(q) }, enabled = q.isNotBlank()) {
                    Icon(Icons.Filled.Search, contentDescription = "Go")
                }
            }

            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                if (history.isNotEmpty()) {
                    item {
                        SectionLabel("History")
                    }
                    items(history, key = { "h:$it" }) { sub ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { open(sub) }
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                        ) {
                            Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Text(
                                "r/$sub",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f).padding(start = 12.dp),
                            )
                            IconButton(onClick = {
                                Repo.remove(sub)
                                history = Repo.history()
                            }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Remove r/$sub from history",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item { SectionLabel(if (q.isEmpty()) "Browse (type to filter ${Repo.SUGGESTIONS.size} subreddits)" else "Browse") }
                if (q.isEmpty()) {
                    item { Text(
                        "${Repo.SUGGESTIONS.size} communities — start typing to narrow down, or browse the full grid from the drawer.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) }
                }
                items(filteredSuggestions.take(24), key = { "s:$it" }) { sub ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { open(sub) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Text("r/$sub", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                if (q.isNotBlank() && !filteredSuggestions.contains(q.lowercase()) && !history.contains(q.lowercase())) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { open(q) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(
                                "Go to r/${q.lowercase()}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
