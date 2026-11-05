package app.redlib.now.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.redlib.now.data.Repo
import app.redlib.now.data.Settings
import app.redlib.now.net.Http
import app.redlib.now.net.Logd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Subreddit finder only.
 *
 * Order while typing: Go to r/q → Browse (defaults ∪ live, deduped) → Recent.
 * Empty query: Recent first → Browse defaults.
 *
 * Live hits come from reddtastic public search API when the setting is on.
 */
@Composable
fun SearchScreen(
    onDismiss: () -> Unit,
    onOpenSubreddit: (String) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val scope = rememberCoroutineScope()
    // name -> subscriber count (0 if unknown / static only)
    var liveHits by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var liveJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun requestLiveSuggestions(raw: String) {
        liveJob?.cancel()
        val term = raw.trim().removePrefix("r/").removePrefix("/r/")
        if (!Settings.liveSubSuggestions || term.length < 2) {
            liveHits = emptyList()
            return
        }
        liveJob = scope.launch {
            delay(350)
            liveHits = withContext(Dispatchers.IO) {
                fetchReddtasticSubs(term)
            }
        }
    }

    fun open(sub: String) {
        val normalized = sub.trim().removePrefix("r/").removePrefix("/r/")
            .filter { it.isLetterOrDigit() || it == '_' }
            .lowercase()
        if (normalized.isEmpty()) return
        Repo.recordSearch(normalized)
        onOpenSubreddit(normalized)
    }

    val q = query.trim().removePrefix("r/").removePrefix("/r/")
    val qLower = q.lowercase()
    val typing = qLower.length >= 1
    val searchHistory = Repo.searchHistoryState

    // Browse: static matches ∪ live, no dups, prefer higher subscriber counts.
    val browseList: List<String> = remember(qLower, liveHits, searchHistory) {
        val liveMap = linkedMapOf<String, Long>()
        for ((name, subs) in liveHits) {
            val n = name.lowercase()
            if (!isValidSubName(n)) continue
            liveMap[n] = maxOf(liveMap[n] ?: 0L, subs)
        }
        val staticMatches = if (qLower.isEmpty()) {
            Repo.SUGGESTIONS
        } else {
            Repo.SUGGESTIONS.filter { it.contains(qLower, ignoreCase = true) }
        }
        val merged = linkedMapOf<String, Long>()
        for (s in staticMatches) {
            val n = s.lowercase()
            if (!isValidSubName(n)) continue
            merged[n] = liveMap[n] ?: 0L
        }
        for ((n, subs) in liveMap) {
            merged[n] = maxOf(merged[n] ?: 0L, subs)
        }
        // Drop exact query from browse if we show "Go to" separately — still ok to list.
        merged.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Long>> { it.value }
                    .thenBy { it.key },
            )
            .map { it.key }
            .filter { it !in Repo.pinnedState.map { p -> p.lowercase() } }
            .take(40)
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; requestLiveSuggestions(it) },
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
                if (typing) {
                    // 1) Go to r/{q}
                    item {
                        val target = qLower.filter { it.isLetterOrDigit() || it == '_' }
                        if (target.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { open(target) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    "Go to r/$target",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }

                    // 2) Browse (defaults + live, deduped, by subscribers)
                    item { SectionLabel("Browse") }
                    items(browseList, key = { "b:$it" }) { sub ->
                        BrowseRow(sub) { open(sub) }
                    }

                    // 3) Recent
                    if (searchHistory.isNotEmpty()) {
                        item { SectionLabel("Recent searches") }
                        items(searchHistory, key = { "h:$it" }) { sub ->
                            HistoryRow(sub, onOpen = { open(sub) }, onDelete = { Repo.removeFromSearchHistory(sub) })
                        }
                    }
                } else {
                    // Empty: Recent first, then Browse defaults
                    if (searchHistory.isNotEmpty()) {
                        item { SectionLabel("Recent searches") }
                        items(searchHistory, key = { "h:$it" }) { sub ->
                            HistoryRow(sub, onOpen = { open(sub) }, onDelete = { Repo.removeFromSearchHistory(sub) })
                        }
                    }
                    item {
                        SectionLabel("Browse (type to filter ${Repo.SUGGESTIONS.size} subreddits)")
                    }
                    item {
                        Text(
                            "${Repo.SUGGESTIONS.size} communities — start typing to narrow down, or browse the full grid from the drawer.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(browseList.take(24), key = { "b:$it" }) { sub ->
                        BrowseRow(sub) { open(sub) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseRow(sub: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "r/$sub",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun HistoryRow(sub: String, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Icon(
            Icons.Filled.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "r/$sub",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Remove r/$sub from search history",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
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

private val SUB_NAME = Regex("^[A-Za-z0-9_]{2,50}$")

private fun isValidSubName(name: String): Boolean =
    SUB_NAME.matches(name) && !name.contains('?') && '=' !in name

/**
 * Public reddtastic subreddit search — richer than Redlib type=sr.
 * Sorted by redditSubscribers descending.
 */
private fun fetchReddtasticSubs(query: String): List<Pair<String, Long>> {
    return try {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://reddtastic.com/api/reddit/subreddits/search?q=$encoded&page=1"
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logd.w("reddtastic search HTTP ${resp.code}")
                return emptyList()
            }
            val body = resp.body?.string().orEmpty()
            if (body.isEmpty()) return emptyList()
            val root = JSONObject(body)
            val items = root.optJSONArray("items") ?: return emptyList()
            val out = ArrayList<Pair<String, Long>>(items.length())
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val name = o.optString("nameDisplay", "").trim()
                if (!isValidSubName(name)) continue
                val subs = o.optLong("redditSubscribers", 0L)
                out += name.lowercase() to subs
            }
            out.sortedByDescending { it.second }.distinctBy { it.first }.take(40)
        }
    } catch (t: Throwable) {
        Logd.w("reddtastic search failed: ${t.message}")
        emptyList()
    }
}
