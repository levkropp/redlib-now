package app.redlib.now.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.redlib.now.data.FeedCache
import app.redlib.now.data.Repo
import app.redlib.now.model.Comment
import app.redlib.now.model.Post
import app.redlib.now.parse.CommentParser
import kotlinx.coroutines.launch

/** Post header + comment thread, loaded from the instance on open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsScreen(
    post: Post,
    onBack: () -> Unit,
    onOpenMedia: (Post) -> Unit,
    onOpenUser: (String) -> Unit = {},
) {
    BackHandler(onBack = onBack)
    var comments by remember { mutableStateOf<List<Comment>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf("best") }
    var menuComment by remember { mutableStateOf<Comment?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // id -> top-level root id, for jump-to-parent scrolling.
    val rootIdById = remember(comments) {
        val map = HashMap<String, String>()
        fun walk(c: Comment, root: String) {
            map[c.id] = root
            c.replies.forEach { walk(it, root) }
        }
        comments.orEmpty().forEach { walk(it, it.id) }
        map
    }

    LaunchedEffect(post.id, sort) {
        // Cache-first: show the offline thread immediately, refresh from network.
        val key = post.permalink + "?sort=" + sort
        val cached = FeedCache.loadComments(key)
        if (cached != null) comments = cached
        try {
            val resp = Repo.client.fetch(post.permalink + if (sort == "best") "" else "?sort=$sort")
            val parsed = CommentParser.parseComments(resp.html, resp.baseUrl)
            comments = parsed
            FeedCache.saveComments(key, parsed)
        } catch (t: Throwable) {
            if (comments == null) error = "Failed to load comments: ${t.message}"
        }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) {
        TopAppBar(
            actions = {
                var sortMenuOpen by remember { mutableStateOf(false) }
                TextButton(onClick = { sortMenuOpen = true }) {
                    Text(
                        sort.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Sort comments",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                        listOf("best", "top", "new", "old", "controversial", "qa").forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.replaceFirstChar { it.uppercase() }) },
                                onClick = { sort = s; sortMenuOpen = false },
                            )
                        }
                    }
                }
            },
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.background,
            ),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            title = {
                Column {
                    Text(
                        "r/${post.subreddit}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        post.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            },
        )

        when {
            comments == null && error == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    CommentPostHeader(post, onOpenMedia, onOpenUser)
                }
                items(comments!!, key = { it.id }) { c ->
                    CommentNode(c, depth = 0, onLongPress = { menuComment = it })
                }
                if (comments!!.isEmpty()) {
                    item { Text("No comments yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }

    // Long-press comment actions (parity with the classic comment overflow).
    menuComment?.let { mc ->
        AlertDialog(
            onDismissRequest = { menuComment = null },
            title = { Text(mc.author?.let { "u/$it" } ?: "Comment", style = MaterialTheme.typography.titleSmall) },
            text = {
                Column {
                    Text(
                        mc.body.take(120),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(mc.body))
                        menuComment = null
                    }) { Text("Copy text") }
                    if (mc.parentId != null) {
                        TextButton(onClick = {
                            val root = rootIdById[mc.parentId] ?: mc.parentId
                            val idx = comments.orEmpty().indexOfFirst { it.id == root }
                            if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                            menuComment = null
                        }) { Text("Jump to parent") }
                    }
                    if (mc.author != null) {
                        TextButton(onClick = {
                            menuComment = null
                            onOpenUser(mc.author)
                        }) { Text("View u/${mc.author} profile") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuComment = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun CommentPostHeader(post: Post, onOpenMedia: (Post) -> Unit, onOpenUser: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${post.score ?: "•"} points · ${post.commentCount ?: "?"} comments · ${post.timeAgo ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (post.imageUrl != null) {
            // Tap the preview to open the full-screen viewer without losing place.
            app.redlib.now.ui.PostCard(
                post = post,
                onClick = {},
                onOpenComments = {},
                onOpenMedia = { onOpenMedia(post) },
                onOpenUser = onOpenUser,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CommentNode(comment: Comment, depth: Int, onLongPress: (Comment) -> Unit = {}) {
    var expanded by remember(comment.id) { mutableStateOf(true) }
    val replyCount = comment.replies.recursiveCount()

    Column(
        Modifier
            .padding(start = (depth * 8).dp)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .background(
                if (depth > 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .combinedClickable(
                onClick = { if (!expanded) expanded = true },
                onLongClick = { onLongPress(comment) },
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Collapse/expand toggle — read-only client, so this is the
            // left-side interaction instead of a vote arrow.
            Text(
                if (expanded) "−" else "+",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (expanded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 4.dp)
                    .widthIn(min = 20.dp),
            )
            Text(
                comment.author ?: "[deleted]",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    comment.isOp -> MaterialTheme.colorScheme.primary
                    comment.isMod -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (comment.isMod) {
                Text("  MOD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.weight(1f))
            comment.score?.let {
                Text(it.formatScore(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            comment.timeAgo?.let {
                Text("  $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded) {
            if (comment.body.isNotBlank()) {
                Text(
                    comment.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            comment.replies.forEach { CommentNode(it, depth + 1, onLongPress) }
        } else if (replyCount > 0) {
            Text(
                "$replyCount ${if (replyCount == 1L) "reply" else "replies"} hidden",
                style = MaterialTheme.typography.labelSmall,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

private fun List<Comment>.recursiveCount(): Long = sumOf { 1L + it.replies.recursiveCount() }

private fun Long.formatScore(): String = when {
    this >= 1_000_000 -> "%.1fm".format(this / 1_000_000f)
    this >= 1_000 -> "%.1fk".format(this / 1_000f)
    else -> toString()
}
