package app.redlib.now.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.redlib.now.data.MediaCache
import app.redlib.now.model.Post
import app.redlib.now.net.Logd
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import kotlinx.coroutines.launch

/**
 * Full-screen media viewer. Videos play from a local, remuxed copy in our
 * app-private cache (MediaCache): first view downloads with a progress bar,
 * afterwards it's instant and works offline. Images are pinch-zoomable and
 * also get a local copy for the 72h offline window.
 */
@Composable
fun MediaViewer(post: Post, onClose: () -> Unit) =
    MediaViewer(post.title, post.imageUrl, post.videoUrl, post.isVideo, onClose)

/** Fullscreen media viewer over primitives — reusable for posts and comment media. */
@Composable
fun MediaViewer(title: String, imageUrl: String?, videoUrl: String?, isVideo: Boolean, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val key = title + "|" + (videoUrl ?: imageUrl ?: "")

    var videoFile by remember(key) { mutableStateOf<File?>(null) }
    var downloadPct by remember(key) { mutableStateOf<Int?>(null) }
    var downloadFailed by remember(key) { mutableStateOf(false) }
    if (isVideo) {
        LaunchedEffect(key) {
            videoFile = MediaCache.videoReadyCopy(
                absoluteUrl(videoUrl ?: imageUrl)
            ) { pct -> downloadPct = pct }
            downloadPct = null
            if (videoFile == null) downloadFailed = true
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isVideo) {
            val vf = videoFile
            if (vf != null) {
                VideoPlayer(android.net.Uri.fromFile(vf).toString(), Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (downloadFailed) {
                            Text(
                                "Couldn't save this video.\nConnect to the internet and try again.",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            CircularProgressIndicator(color = Color.White)
                            downloadPct?.let {
                                Text(
                                    "saving for offline… $it%",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            ZoomableImage(absoluteUrl(imageUrl), onClose = if (app.redlib.now.data.Settings.tapToCloseImages) onClose else ({ }))
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
                title,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8AEA6),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // Viewer actions (parity with the classic image menu): share the
        // media file, copy its link, or save it to the public gallery.
        val context = LocalContext.current
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        val scope = rememberCoroutineScope()
        var statusMsg by remember { mutableStateOf<String?>(null) }
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(vertical = 6.dp),
        ) {
            TextButton(onClick = {
                val url = absoluteUrl(imageUrl)
                scope.launch {
                    MediaCache.getOrDownload(url)?.let { f ->
                        shareMedia(context, f, isVideo)
                    } ?: run { statusMsg = "Nothing to share yet" }
                }
            }) { Text("Share", color = Color.White) }
            TextButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(absoluteUrl(imageUrl)))
                statusMsg = "Link copied"
            }) { Text("Copy link", color = Color.White) }
            TextButton(onClick = {
                val url = absoluteUrl(imageUrl)
                scope.launch {
                    MediaCache.getOrDownload(url)?.let { f ->
                        statusMsg = if (saveToGallery(context, f, isVideo)) "Saved to gallery" else "Save failed"
                    } ?: run { statusMsg = "Nothing to save yet" }
                }
            }) { Text("Save", color = Color.White) }
        }
        statusMsg?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp),
            )
        }
    }
}

/** Share a cached media file through the FileProvider. */
private fun shareMedia(context: android.content.Context, file: File, isVideo: Boolean) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "app.redlib.now.fileprovider", file)
        val mime = if (isVideo || file.name.endsWith(".mp4")) "video/mp4" else "image/*"
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share media"))
    } catch (t: Throwable) {
        Logd.e("share failed", t)
    }
}

/** Copy a cached media file into the public gallery (MediaStore, no permission needed on API 29+). */
private fun saveToGallery(context: android.content.Context, file: File, isVideo: Boolean): Boolean {
    try {
        val resolver = context.contentResolver
        val mime = if (isVideo || file.name.endsWith(".mp4")) "video/mp4" else "image/jpeg"
        val collection = if (isVideo || file.name.endsWith(".mp4"))
            android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "redlib-now-${System.currentTimeMillis()}.${if (mime=="video/mp4") "mp4" else "jpg"}")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                if (mime == "video/mp4") "Movies/RedlibNow" else "Pictures/RedlibNow")
        }
        val uri = resolver.insert(collection, values) ?: return false
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: return false
        return true
    } catch (t: Throwable) {
        Logd.e("save to gallery failed", t)
        return false
    }
}

@Composable
private fun ZoomableImage(url: String, onClose: () -> Unit = {}) {
    // Serve the local copy when we have it; fetch one for offline otherwise.
    val model = MediaCache.localUri(url) ?: url
    LaunchedEffect(url) { if (MediaCache.localUri(url) == null) MediaCache.getOrDownload(url) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(model).crossfade(false).build(),
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
                    val start = android.os.SystemClock.uptimeMillis()
                    var moved = false
                    do {
                        val event = awaitPointerEvent()
                        val pinching = event.changes.size > 1
                        if (pinching || scale > 1f) {
                            moved = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    // Classic tap-to-close: quick, still tap on a non-zoomed image.
                    if (!moved && android.os.SystemClock.uptimeMillis() - start < 250 && scale == 1f) {
                        onClose()
                    }
                }
            },
    )
}

@Composable
private fun VideoPlayer(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(url) {
        // Default factory routes file:// to FileDataSource; everything we
        // play here is a local file served by our own cache.
        ExoPlayer.Builder(context, DefaultMediaSourceFactory(context))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        Logd.i("video: state=$playbackState (${STATE_NAMES.getOrElse(playbackState) {"?"}}) " +
                            "uri=$url pos=$currentPosition dur=$duration")
                        // Safety net: if the player ever reports ENDED well
                        // before the end of the media, restart once instead
                        // of leaving the user on a dead "replay" screen.
                        if (playbackState == Player.STATE_ENDED &&
                            duration > 0 && currentPosition < duration * 0.9
                        ) {
                            Logd.w("video: premature ENDED at ${currentPosition}ms/${duration}ms — restarting")
                            seekTo(0)
                            play()
                        }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Logd.i("video: isPlaying=$isPlaying")
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        Logd.e("video: error ${error.errorCodeName}", error)
                    }
                })
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
    val base = app.redlib.now.data.Repo.client.baseUrl() ?: ""
    return when {
        rel == null -> ""
        rel.startsWith("http") -> rel
        else -> base + rel
    }
}

private val STATE_NAMES = arrayOf("", "IDLE", "BUFFERING", "READY", "ENDED")
