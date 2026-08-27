package app.redlib.now.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.redlib.now.data.Repo
import app.redlib.now.model.Post
import app.redlib.now.net.Http
import app.redlib.now.net.Logd
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Full-screen media viewer: pinch-zoomable image, or HLS video via ExoPlayer
 * on the app's cookie-carrying OkHttp client.
 */
@Composable
fun MediaViewer(post: Post, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (post.isVideo) {
            VideoPlayer(absoluteUrl(post.videoUrl ?: post.imageUrl), Modifier.fillMaxSize())
        } else {
            ZoomableImage(absoluteUrl(post.imageUrl))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 8.dp, start = 4.dp, end = 8.dp),
        ) {
            IconButton(onClick = {
                Logd.i("MediaViewer: X clicked")
                onClose()
            }) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
            Text(
                post.title,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8AEA6),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun ZoomableImage(url: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(false).build(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(Unit) {
                // Non-greedy transform handling: consume events only while a
                // pinch is in progress or the image is zoomed in. A plain tap
                // stays unconsumed so the close button on top stays clickable.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    Logd.d("viewer gesture: down at ${down.position}")
                    do {
                        val event = awaitPointerEvent()
                        val pinching = event.changes.size > 1
                        if (pinching || scale > 1f) {
                            Logd.d("viewer gesture: consuming (pinch=$pinching scale=$scale)")
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    )
}

@Composable
private fun VideoPlayer(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(url) {
        val dataSourceFactory = OkHttpDataSource.Factory(Http.client)
            .setUserAgent(Http.USER_AGENT)
        val mimeType = if (url.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4
        ExoPlayer.Builder(context, DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMimeType(mimeType)
                        .build()
                )
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(url) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
        modifier = modifier,
    )
}

private fun absoluteUrl(rel: String?): String {
    val base = Repo.client.baseUrl() ?: ""
    return when {
        rel == null -> ""
        rel.startsWith("http") -> rel
        else -> base + rel
    }
}
