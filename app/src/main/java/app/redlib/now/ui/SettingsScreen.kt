package app.redlib.now.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.redlib.now.data.Repo
import app.redlib.now.data.MediaCache
import app.redlib.now.data.Settings
import app.redlib.now.net.Http
import app.redlib.now.net.InstanceDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Settings screen: Appearance / Behaviour / Filters / Gestures / Instance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
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
            title = { Text("Settings", fontWeight = FontWeight.Bold) },
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Section("Search") }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { Settings.updateLiveSubSuggestions(!Settings.liveSubSuggestions) },
                    ) {
                        Text(
                            "Live subreddit suggestions",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Switch(
                            checked = Settings.liveSubSuggestions,
                            onCheckedChange = { Settings.updateLiveSubSuggestions(it) },
                        )
                    }
                    Text(
                        "Uses reddtastic.com public search API for richer matches",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.padding(end = 48.dp, bottom = 4.dp),
                    )
                }
            }
            item { Section("Storage") }
            item { CacheRow() }
            item { Section("Instance") }
            item { InstancePicker() }

            item { Section("Appearance") }
            item {
                ChoiceRow("Card size", Settings.cardSize, listOf("compact" to "Compact", "normal" to "Normal", "large" to "Large")) {
                    Settings.updateCardSize(it)
                }
            }
            item { SwitchRow("Self-text previews", Settings.showSelftext) { Settings.updateShowSelftext(it) } }
            item { SwitchRow("Media previews", Settings.showMedia) { Settings.updateShowMedia(it) } }
            item { SwitchRow("Link previews", Settings.linkPreviews) { Settings.updateLinkPreviews(it) } }
            item { SwitchRow("Data saver (image quality)", Settings.dataSaver) { Settings.updateDataSaver(it) } }
            item {
                ChoiceRow("Text size", "%.0f%%".format(Settings.textScale * 100),
                    listOf("0.85" to "Small", "1.0" to "Normal", "1.15" to "Large", "1.3" to "Largest")) {
                    Settings.updateTextScale(it.toFloat())
                }
            }

            item { Section("Behaviour") }
            item { SwitchRow("Collapse comment replies by default", Settings.collapseThreads) { Settings.updateCollapseThreads(it) } }
            item { SwitchRow("Collapse AutoModerator", Settings.collapseAutoMod) { Settings.updateCollapseAutoMod(it) } }
            item {
                ChoiceRow("Suggested comment sort", Settings.suggestedCommentSort,
                    listOf("best" to "Best", "top" to "Top", "new" to "New", "old" to "Old", "controversial" to "Controversial", "qa" to "Q&A")) {
                    Settings.updateSuggestedCommentSort(it)
                }
            }
            item { SwitchRow("Hide read posts", Settings.hideReadPosts) { Settings.updateHideReadPosts(it) } }
            item { SwitchRow("Remember subreddit position", Settings.rememberSubredditPosition) { Settings.updateRememberPosition(it) } }

            item { Section("Filters") }
            item { SwitchRow("Hide NSFW content", Settings.hideNsfw) { Settings.updateHideNsfw(it) } }
            item { SwitchRow("Hide NSFW previews", Settings.hideNsfwPreviews) { Settings.updateHideNsfwPreviews(it) } }
            item { FilterListRow("Subreddit filter", Settings.subredditFilters, hint = "subreddit name") { Settings.updateSubredditFilters(it) } }
            item { FilterListRow("User filter", Settings.userFilters, hint = "username") { Settings.updateUserFilters(it) } }
            item { FilterListRow("Keyword filter", Settings.keywordFilters, hint = "keyword") { Settings.updateKeywordFilters(it) } }

            item { Section("Gestures") }
            item { SwitchRow("Swipe back", Settings.swipeBack) { Settings.updateSwipeBack(it) } }
            item { SwitchRow("Tap to close images & videos", Settings.tapToCloseImages) { Settings.updateTapToClose(it) } }
        }
    }
    }
}

private enum class InstanceStatus { Checking, Up, Challenge, Down }

@Composable
private fun InstancePicker() {
    val scope = rememberCoroutineScope()
    var dialogOpen by remember { mutableStateOf(false) }
    var instances by remember { mutableStateOf<List<String>>(emptyList()) }
    var statuses by remember { mutableStateOf<Map<String, InstanceStatus>>(emptyMap()) }
    var loadingList by remember { mutableStateOf(false) }
    val preferred = Settings.preferredInstance
    val auto = Settings.isAutoInstance()
    val active = Repo.client.activeBase()

    fun refreshListAndProbe() {
        scope.launch {
            loadingList = true
            val urls = withContext(Dispatchers.IO) {
                try {
                    InstanceDiscovery.fetchInstanceUrls()
                } catch (_: Throwable) {
                    InstanceDiscovery.FALLBACK
                }
            }
            instances = urls
            loadingList = false
            val map = statuses.toMutableMap()
            urls.forEach { map[it] = InstanceStatus.Checking }
            statuses = map.toMap()
            for (url in urls) {
                val st = withContext(Dispatchers.IO) { probeInstance(url) }
                map[url] = st
                statuses = map.toMap()
            }
        }
    }

    val summary = when {
        auto -> "Auto" + (active?.removePrefix("https://")?.let { " · $it" } ?: "")
        preferred.isNotBlank() -> preferred.removePrefix("https://").removePrefix("http://")
        else -> "Auto"
    }

    // Compact summary row — full list lives in the dialog only.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                dialogOpen = true
                if (instances.isEmpty()) refreshListAndProbe()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Redlib instance", style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "Change",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("Choose instance") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "Auto rotates across healthy hosts. Pinning sticks to one host.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (loadingList) "Loading…" else "${instances.size} instances",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { refreshListAndProbe() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh status")
                        }
                    }
                    // Scrollable list inside dialog
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                    ) {
                        item {
                            InstanceRow(
                                label = "Auto",
                                subtitle = active?.removePrefix("https://")?.let { "currently $it" }
                                    ?: "rotate across healthy hosts",
                                selected = auto,
                                status = if (auto) InstanceStatus.Up else null,
                                onClick = {
                                    Settings.updatePreferredInstance("")
                                    Repo.client.applyPreferredFromSettings()
                                    dialogOpen = false
                                },
                            )
                        }
                        items(instances.size) { idx ->
                            val url = instances[idx]
                            val host = url.removePrefix("https://").removePrefix("http://")
                            InstanceRow(
                                label = host,
                                subtitle = when {
                                    !auto && preferred == url -> "selected"
                                    active == url -> "in use"
                                    else -> null
                                },
                                selected = !auto && preferred == url,
                                status = statuses[url] ?: if (loadingList) InstanceStatus.Checking else null,
                                onClick = {
                                    Settings.updatePreferredInstance(url)
                                    Repo.client.applyPreferredFromSettings()
                                    dialogOpen = false
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun InstanceRow(
    label: String,
    subtitle: String?,
    selected: Boolean,
    status: InstanceStatus?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (status != null) {
            StatusDot(status)
        }
    }
}

@Composable
private fun StatusDot(status: InstanceStatus) {
    val color = when (status) {
        InstanceStatus.Checking -> Color(0xFF9E9E9E)
        InstanceStatus.Up -> Color(0xFF4CAF50)
        InstanceStatus.Challenge -> Color(0xFFFFC107) // reachable, needs Anubis
        InstanceStatus.Down -> Color(0xFFF44336)
    }
    val desc = when (status) {
        InstanceStatus.Checking -> "checking"
        InstanceStatus.Up -> "up"
        InstanceStatus.Challenge -> "reachable"
        InstanceStatus.Down -> "down"
    }
    Box(
        Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color),
    )
    Spacer(Modifier.width(6.dp))
    Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Lightweight reachability probe — does not fully solve Anubis.
 * Up = got real Redlib content; Challenge = Anubis interstitial (host is alive);
 * Down = network/HTTP failure.
 */
private fun probeInstance(base: String): InstanceStatus {
    return try {
        val url = base.trimEnd('/') + "/"
        val req = Request.Builder().url(url).get().header("User-Agent", "NowRedlib/1.0").build()
        Http.client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            when {
                body.contains("post_title") || body.contains("id=\"posts\"") -> InstanceStatus.Up
                body.contains("anubis_challenge") ||
                    body.contains("Making sure you") ||
                    body.contains("Verifying your browser") -> InstanceStatus.Challenge
                resp.isSuccessful -> InstanceStatus.Up
                else -> InstanceStatus.Down
            }
        }
    } catch (_: Throwable) {
        InstanceStatus.Down
    }
}

@Composable
private fun CacheRow() {
    val scope = rememberCoroutineScope()
    var sizeText by remember { mutableStateOf("…") }
    var clearing by remember { mutableStateOf(false) }
    fun refresh() {
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { MediaCache.totalBytes() }
            sizeText = formatBytes(bytes)
        }
    }
    LaunchedEffect(Unit) { refresh() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Media cache", style = MaterialTheme.typography.bodyLarge)
            Text(
                sizeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
        TextButton(
            enabled = !clearing,
            onClick = {
                clearing = true
                scope.launch {
                    withContext(Dispatchers.IO) { MediaCache.clearAll() }
                    refresh()
                    clearing = false
                }
            },
        ) {
            Text(if (clearing) "Clearing…" else "Clear cache")
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(title: String, current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            options.firstOrNull { it.first == current }?.second ?: current,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onPick(id); open = false })
            }
        }
    }
}

@Composable
private fun FilterListRow(
    title: String,
    items: List<String>,
    hint: String,
    onChange: (List<String>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            if (items.isEmpty()) "None" else "${items.size} blocked",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) {
        var draft by remember { mutableStateOf(items.joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Comma-separated $hint") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onChange(draft.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() })
                    open = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}
