package app.redlib.now.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import app.redlib.now.model.Post
import java.nio.ByteBuffer
import app.redlib.now.net.Http
import app.redlib.now.net.Logd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * App-private media cache. Everything the user sees gets a local copy that
 * OUR stack serves to the player/Coil (file:// URIs) — never exposed to
 * other apps or to the user's gallery/SD card.
 *
 * Videos are downloaded fully and remuxed (MediaExtractor -> MediaMuxer)
 * into a vanilla progressive MP4, sidestepping ExoPlayer's trouble with
 * reddit's DASH-branded fMP4 streams (spurious STATE_ENDED).
 *
 * Retention: entries older than [RETENTION_MS] (72h) are purged on startup.
 */
object MediaCache {
    const val RETENTION_MS = 72L * 3600 * 1000
    private const val MAX_VIDEO_BYTES = 300L * 1024 * 1024

    private lateinit var dir: File

    fun init(context: android.content.Context) {
        dir = File(context.filesDir, "media").apply { mkdirs() }
        purgeOld()
    }

    private fun fileFor(url: String, ext: String): File =
        File(dir, md5(url) + ext)

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun extOf(url: String): String =
        Regex("""\.(jpe?g|png|gif|webp|mp4)(\?|$)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.lowercase()?.let { ".$it" } ?: ".bin"

    /** file:// URI if we already have this media locally, else null. */
    fun localUri(url: String?): String? {
        if (url == null || !this::dir.isInitialized) return null
        return fileFor(url, extOf(url)).takeIf { it.length() > 0 }
            ?.let { "file://${it.absolutePath}" }
    }

    /** Download (or reuse) a local copy. Progress callback 0..100, may be null. */
    suspend fun getOrDownload(url: String, onProgress: ((Int?) -> Unit)? = null): File? =
        withContext(Dispatchers.IO) {
            try {
                val f = fileFor(url, extOf(url))
                if (f.length() > 0L) {
                    onProgress?.invoke(100)
                    return@withContext f
                }
                val tmp = File(dir, md5(url) + ".part")
                val req = okhttp3.Request.Builder().url(url).build()
                Http.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Logd.w("media download failed $url -> ${resp.code}")
                        return@withContext null
                    }
                    val body = resp.body ?: return@withContext null
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var read: Int
                            var done = 0L
                            var lastPct = -1
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                                done += read
                                if (total > 0 && onProgress != null) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) { onProgress(pct); lastPct = pct }
                                }
                            }
                        }
                    }
                }
                val ok = tmp.renameTo(f)
                return@withContext if (ok && f.length() > 0L) f else null
            } catch (t: Throwable) {
                Logd.e("media download error $url", t)
                null
            }
        }

    /**
     * Full video pipeline: download -> remux to clean MP4 -> local file.
     * Falls back to the raw download if remuxing fails; returns null only
     * if we could not get the video at all.
     */
    suspend fun videoReadyCopy(url0: String, onProgress: (Int?) -> Unit): File? =
        withContext(Dispatchers.IO) {
            // Reddit's HLS master is unusable for us; the byterange segments
            // it references are ranges of one real progressive MP4 per
            // quality, served by the instance. Fetch that directly.
            val url = if (url0.contains("/HLSPlaylist.m3u8")) {
                url0.substringBefore("HLSPlaylist.m3u8") + "CMAF_480.mp4"
            } else url0
            val existing = fileFor(url, ".r.mp4")
            if (existing.length() > 0L) { onProgress(100); return@withContext existing }

            val raw = fileFor(url, extOf(url))
            if (raw.length() == 0L) {
                val head = headContentLength(url)
                if (head != null && head > MAX_VIDEO_BYTES) {
                    Logd.w("video too large ($head bytes), streaming instead: $url")
                    return@withContext null
                }
                getOrDownload(url, onProgress) ?: return@withContext null
            }
            // Distinct name from raw — muxer and extractor cannot share a path.
            val remuxed = File(dir, md5(url) + ".r.mp4")
            if (remux(raw, remuxed)) {
                raw.delete()
                onProgress(100)
                remuxed
            } else {
                Logd.w("remux failed; playing raw download")
                raw
            }
        }

    private fun headContentLength(url: String): Long? = try {
        val req = okhttp3.Request.Builder().url(url).head().build()
        Http.client.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull() else null
        }
    } catch (_: Exception) { null }

    /** Copy all A/V samples into a fresh, boring, maximally-compatible MP4. */
    private fun remux(src: File, dst: File): Boolean = try {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        val mux = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val indexMap = HashMap<Int, Int>()
        for (i in 0 until ex.trackCount) {
            val fmt: MediaFormat = ex.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                ex.selectTrack(i)
                indexMap[i] = mux.addTrack(fmt)
            }
        }
        val buf = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        mux.start()
        while (true) {
            val trackIdx = ex.sampleTrackIndex
            if (trackIdx < 0) break
            info.offset = 0
            info.size = ex.readSampleData(buf, 0)
            if (info.size < 0) break
            info.presentationTimeUs = ex.sampleTime
            info.flags = ex.sampleFlags
            indexMap[trackIdx]?.let { mux.writeSampleData(it, buf, info) }
            if (!ex.advance()) break
        }
        mux.stop(); mux.release(); ex.release()
        Logd.i("remux ok: ${dst.name} (${dst.length()} bytes)")
        true
    } catch (t: Throwable) {
        Logd.e("remux failed", t)
        dst.delete()
        false
    }

    /** Human-readable age for the drawer status line. */
    fun ageString(savedAt: Long): String {
        val mins = (System.currentTimeMillis() - savedAt) / 60000
        return when {
            mins < 60 -> "${mins}m ago"
            mins < 1440 -> "${mins / 60}h ago"
            else -> "${mins / 1440}d ago"
        }
    }

    /** Warm the cache with card images for a feed's posts (videos on view). */
    suspend fun prefetch(posts: List<Post>) = withContext(Dispatchers.IO) {
        posts.take(30).forEach { post ->
            val url = post.imageUrl ?: return@forEach
            if (post.isVideo) return@forEach // full videos on demand only
            if (localUri(url) == null) getOrDownload(url)
        }
    }

    private fun purgeOld() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}

