package app.redlib.now.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.redlib.now.data.MediaCache
import app.redlib.now.data.Repo
import app.redlib.now.parse.PostParser
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Fullscreen gallery viewer: swipe through every image of a gallery post,
 * each page pinch-zoomable, with a page counter and a jump-to-comments
 * action.
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
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    val url = urls!![page]
                    var scale by remember(url) { mutableFloatStateOf(1f) }
                    var offset by remember(url) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(MediaCache.localUri(url) ?: url).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale; scaleY = scale
                                translationX = offset.x; translationY = offset.y
                            }
                            .pointerInput(url) {
                                // Same non-greedy zoom as the image viewer;
                                // horizontal pans also drive the pager when
                                // not zoomed.
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
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
                )
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
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
