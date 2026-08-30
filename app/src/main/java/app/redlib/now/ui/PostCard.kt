package app.redlib.now.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.redlib.now.model.Post
import coil.compose.AsyncImage

/**
 * Compact dark card in the spirit of the original app: tight header row,
 * bold title, media preview, and a single comments action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostCard(
    post: Post,
    onClick: () -> Unit,
    onOpenComments: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenUser: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            // Header: subreddit • author • time • score, all in one tight line.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp),
            ) {
                Text(
                    "r/${post.subreddit}",
                    style = MaterialTheme.typography.labelMedium,
                    color = subredditColor(post.subreddit),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HeaderDot()
                post.author?.let {
                    Text(
                        "u/$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onOpenUser(it) },
                    )
                    HeaderDot()
                }
                post.timeAgo?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HeaderDot()
                }
                Text(
                    post.score?.formatScore() ?: "•",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (post.nsfw) StatusPill("NSFW", NowColors.Nsfw)
            }

            Text(
                post.title,
                style = if (app.redlib.now.data.Settings.cardSize == "compact") MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 17.sp,
                maxLines = if (app.redlib.now.data.Settings.cardSize == "large") 5 else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )

            // Media preview (tap = full screen). Videos get a centered play
            // button overlay so they're unmistakable.
            if (app.redlib.now.data.Settings.showMedia && post.imageUrl != null && post.externalUrl == null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onOpenMedia),
                ) {
                    AsyncImage(
                        model = app.redlib.now.data.MediaCache.localUri(post.imageUrl) ?: post.imageUrl,
                        contentDescription = post.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().heightIn(max = if (app.redlib.now.data.Settings.cardSize == "large") 340.dp else if (app.redlib.now.data.Settings.cardSize == "normal") 280.dp else 220.dp),
                    )
                    if (post.isVideo) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Play video",
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(10.dp),
                        )
                    }
                }
            }

            // External-link bar (reference card style): link icon, domain,
            // small thumbnail when the instance provides one.
            post.externalUrl?.let { url ->
                if (!app.redlib.now.data.Settings.linkPreviews) return@let
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Filled.Link,
                        contentDescription = "External link",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        post.linkDomain ?: url.removePrefix("https://").removePrefix("http://").take(40),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    if (post.imageUrl != null) {
                        AsyncImage(
                            model = app.redlib.now.data.MediaCache.localUri(post.imageUrl) ?: post.imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                        )
                    }
                }
            }

            post.selfTextPreview?.takeIf { app.redlib.now.data.Settings.showSelftext && post.imageUrl == null }?.let {
                Text(
                    linkify(it, MaterialTheme.colorScheme.secondary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }

            // Footer: flair pill + comments button.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 4.dp),
            ) {
                post.flair?.let {
                    StatusPill(it, MaterialTheme.colorScheme.surfaceVariant, textColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { app.redlib.now.data.Repo.toggleSave(post) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (app.redlib.now.data.Repo.isSaved(post.id)) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = "Save post",
                        tint = if (app.redlib.now.data.Repo.isSaved(post.id)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                TextButton(
                    onClick = onOpenComments,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${post.commentCount?.formatScore() ?: "?"} comments", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun HeaderDot() {
    Text(
        "·",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun StatusPill(text: String, bg: Color, textColor: Color = Color.White) {
    Text(
        text,
        color = textColor,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun Long.formatScore(): String = when {
    this >= 1_000_000 -> "%.1fm".format(this / 1_000_000f)
    this >= 1_000 -> "%.1fk".format(this / 1_000f)
    else -> toString()
}
