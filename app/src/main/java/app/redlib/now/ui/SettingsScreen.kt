package app.redlib.now.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.redlib.now.data.Settings

/** Settings screen: Appearance / Behaviour / Filters / Gestures. */
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
