package app.redlib.now.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.redlib.now.data.MediaCache
import app.redlib.now.data.Repo
import app.redlib.now.parse.PostParser
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

/**
 * Fullscreen gallery viewer: swipe through every image of a gallery post,
 * each page pinch-zoomable, with page counter, comments, and save/share for
 * the currently displayed image.
 */
@Composable
fun GalleryScreen(
    permalink: String,
    title: String,
    onBack: () -> Unit,
    onOpenComments: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var urls by remember(permalink) { mutableStateOf<List<String>?>(null) }
    var error by remember(permalink) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(permalink) {
        try {
            val resp = Repo.client.fetch(permalink)
            val parsed = PostParser.parseGallery(resp.html, resp.baseUrl)
            urls = if (parsed.isEmpty()) null else parsed
            if (parsed.isEmpty()) error = "No gallery images found"
        } catch (t: Throwable) {
            error = "Failed to load gallery: ${t.message}"
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            urls == null && error == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
            error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(error!!, color = Color(0xFFB8AEA6))
            }
            else -> {
                val pagerState = rememberPagerState(pageCount = { urls!!.size })
                // Prefetch current + neighbors into MediaCache (Anubis-aware).
                LaunchedEffect(pagerState.currentPage, urls) {
                    val list = urls ?: return@LaunchedEffect
                    val idx = pagerState.currentPage
                    for (i in listOf(idx, idx - 1, idx + 1)) {
                        if (i in list.indices) {
                            val u = list[i]
                            if (MediaCache.localUri(u) == null) {
                                MediaCache.getOrDownload(u)
                            }
                        }
                    }
                }
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    val url = urls!![page]
                    var scale by remember(url) { mutableFloatStateOf(1f) }
                    var offset by remember(url) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                    var local by remember(url) { mutableStateOf(MediaCache.localUri(url)) }
                    // Ensure this page has a local (or network) copy; refresh model when ready.
                    LaunchedEffect(url) {
                        if (local == null) {
                            val f = MediaCache.getOrDownload(url)
                            local = f?.let { "file://${it.absolutePath}" } ?: url
                        }
                    }
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(local ?: url)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale; scaleY = scale
                                translationX = offset.x; translationY = offset.y
                            }
                            .pointerInput(url) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        val pinching = event.changes.size > 1
                                        if (pinching || scale > 1f) {
                                            scale = (scale * event.calculateZoom()).coerceIn(1f, 8f)
                                            if (scale > 1f) offset += event.calculatePan() else offset = androidx.compose.ui.geometry.Offset.Zero
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            },
                    )
                }
                Text(
                    "${pagerState.currentPage + 1} / ${urls!!.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
                )

                // Actions for the currently visible image.
                val currentUrl = urls!![pagerState.currentPage]
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(vertical = 6.dp),
                ) {
                    statusMsg?.let {
                        Text(
                            it,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = {
                            scope.launch {
                                val f = MediaCache.getOrDownload(currentUrl)
                                f?.let { shareGalleryMedia(context, it) }
                                    ?: run { statusMsg = "Nothing to share yet" }
                            }
                        }) { Text("Share", color = Color.White) }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(currentUrl))
                            statusMsg = "Link copied"
                        }) { Text("Copy link", color = Color.White) }
                        TextButton(
                            enabled = !saving,
                            onClick = {
                                saving = true
                                scope.launch {
                                    val f = MediaCache.getOrDownload(currentUrl)
                                    statusMsg = if (f != null && saveMediaToGallery(context, f, isVideo = false))
                                        "Saved to gallery" else "Save failed"
                                    saving = false
                                }
                            },
                        ) {
                            Text(if (saving) "Saving…" else "Save", color = Color.White)
                        }
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 8.dp, start = 4.dp, end = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8AEA6),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (urls != null) {
                IconButton(onClick = onOpenComments) {
                    Icon(Icons.Filled.ChatBubble, contentDescription = "Comments", tint = Color.White)
                }
            }
        }
    }
}

private fun shareGalleryMedia(context: android.content.Context, file: java.io.File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file,
        )
        val mime = mimeFromFile(file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share"))
    } catch (_: Throwable) {}
}
