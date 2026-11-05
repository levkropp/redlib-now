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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import app.redlib.now.net.Anubis
import app.redlib.now.data.Repo
import java.io.File
import java.security.MessageDigest

/**
 * App-private media cache. Everything the user sees gets a local copy that
 * OUR stack serves to the player/Coil (file:// URIs) — never exposed to
 * other apps or to the user's gallery/SD card.
 *
 * Videos from Redlib/v.redd.it:
 *  - HLS master (/hls/<id>/HLSPlaylist.m3u8) points at CMAF_<q>.mp4 (video)
 *    and CMAF_AUDIO_<br>.mp4 (AAC). We download both and mux into one
 *    progressive MP4 so play + save have sound.
 *  - Preview gif→mp4 and other single progressive files are remuxed alone.
 *
 * Retention: entries older than [RETENTION_MS] (72h) are purged on startup.
 */
object MediaCache {
    const val RETENTION_MS = 72L * 3600 * 1000
    private const val MAX_VIDEO_BYTES = 300L * 1024 * 1024
    private const val MIN_MEDIA_BYTES = 1024L
    /** Cap parallel media GETs so we do not trip instance rate limits. */
    private val downloadSemaphore = Semaphore(3)

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
        // Prefer the merged offline video if we built one for this URL.
        fileFor(url, ".r.mp4").takeIf { it.length() > 0 }?.let {
            return "file://${it.absolutePath}"
        }
        return fileFor(url, extOf(url)).takeIf { it.length() > 0 }
            ?.let { "file://${it.absolutePath}" }
    }

    /** Download (or reuse) a local copy. Progress callback 0..100, may be null. */
    suspend fun getOrDownload(url: String, onProgress: ((Int?) -> Unit)? = null): File? =
        withContext(Dispatchers.IO) {
            downloadSemaphore.withPermit {
                // Interceptor already retries Anubis; extra passes for HTML-as-body edge cases.
                var result: File? = null
                for (attempt in 0 until 4) {
                    result = downloadOnce(url, onProgress)
                    if (result != null) break
                    val origin = originOf(url) ?: break
                    Logd.i("media: retry ${attempt + 1}/4 after failure — refreshAuth($origin)")
                    Http.refreshAuth(origin)
                    delay(300L + attempt * 300L)
                }
                result
            }
        }

    /**
     * Single download attempt. Caller retries with ensureAuth on failure.
     * OkHttp [Http.client] already re-auths through Anubis walls several times.
     */
    private suspend fun downloadOnce(
        url: String,
        onProgress: ((Int?) -> Unit)?,
    ): File? {
        try {
            if (isPlaylistOrNonMediaUrl(url)) {
                Logd.w("refusing non-media url: $url")
                return null
            }
            val f = fileFor(url, extOf(url))
            if (f.length() > MIN_MEDIA_BYTES) {
                onProgress?.invoke(100)
                return f
            }
            val tmp = File(dir, md5(url) + ".part")
            tmp.delete()
            val req = okhttp3.Request.Builder().url(url).build()
            var code = 0
            Http.client.newCall(req).execute().use { resp ->
                code = resp.code
                if (!resp.isSuccessful) {
                    Logd.w("media download failed $url -> $code")
                    return null
                }
                val body = resp.body ?: return null
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
            if (code !in 200..299) {
                tmp.delete()
                return null
            }
            // Challenge HTML saved as "media" — reject and let caller re-auth + retry.
            if (!isLikelyMediaFile(tmp)) {
                Logd.w("media download not usable media, discarding: $url (${tmp.length()} bytes)")
                tmp.delete()
                return null
            }
            val ok = tmp.renameTo(f)
            return if (ok && f.length() > 0L) f else null
        } catch (t: Throwable) {
            Logd.e("media download error $url", t)
            return null
        }
    }

    private fun originOf(url: String): String? =
        Regex("""^(https?://[^/]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)

    /**
     * Full video pipeline for play + save:
     *  1. Resolve progressive video (+ optional audio) URLs from the Redlib source.
     *  2. Download them through the shared OkHttp client (Anubis cookies).
     *  3. Mux into a single progressive .r.mp4 with video and audio when available.
     */
    suspend fun videoReadyCopy(url0: String, onProgress: (Int?) -> Unit): File? =
        withContext(Dispatchers.IO) {
            if (url0.isBlank()) return@withContext null

            // Cache key is the original source URL (m3u8 or progressive).
            val merged = fileFor(url0, ".r.mp4")
            if (merged.length() > MIN_MEDIA_BYTES) {
                onProgress(100)
                return@withContext merged
            }
            // Stale partial from an interrupted run.
            if (merged.exists()) merged.delete()

            val plan = resolveVideoPlan(url0)
            Logd.i("video plan: kind=${plan.kind} videoCandidates=${plan.videoUrls.size} audioCandidates=${plan.audioUrls.size}")

            // --- download video track ---
            var videoFile: File? = null
            for ((i, vUrl) in plan.videoUrls.withIndex()) {
                Logd.i("video try [$i]: $vUrl")
                // HEAD only the first candidate — extra HEADs add up fast under Anubis.
                if (i == 0) {
                    val head = headContentLength(vUrl)
                    if (head != null && head > MAX_VIDEO_BYTES) {
                        Logd.w("video too large ($head bytes), skip plan")
                        return@withContext null
                    }
                }
                val f = getOrDownload(vUrl) { pct ->
                    if (pct != null) onProgress((pct * 0.7).toInt().coerceIn(0, 70))
                }
                if (f != null && f.length() > MIN_MEDIA_BYTES) {
                    videoFile = f
                    break
                }
            }
            if (videoFile == null) {
                Logd.w("video: no progressive video file for $url0")
                return@withContext null
            }

            // Video track is enough to play. Audio is optional — never leave the UI
            // stuck at 70% while audio candidates grind Anubis forever.
            onProgress(72)

            var audioFile: File? = null
            if (plan.audioUrls.isNotEmpty()) {
                val audioResult = withTimeoutOrNull(25_000L) {
                    for ((i, aUrl) in plan.audioUrls.take(3).withIndex()) {
                        Logd.i("audio try [$i]: $aUrl")
                        onProgress(72 + i * 3)
                        val f = getOrDownload(aUrl) { pct ->
                            if (pct != null) onProgress(72 + (pct * 0.15).toInt().coerceIn(0, 15))
                        }
                        if (f != null && f.length() > MIN_MEDIA_BYTES) return@withTimeoutOrNull f
                    }
                    null
                }
                audioFile = audioResult
                if (audioFile == null) {
                    Logd.w("video: audio track skipped (timeout or unavailable) — playing video-only")
                }
            }

            onProgress(90)
            val out = File(dir, md5(url0) + ".r.mp4")
            // Drop any previous partial mux from a crashed attempt.
            if (out.exists() && out.length() < MIN_MEDIA_BYTES) out.delete()
            else if (out.exists()) out.delete()

            val remuxed = withTimeoutOrNull(30_000L) {
                when {
                    audioFile != null -> remuxAv(videoFile!!, audioFile!!, out)
                    else -> remux(videoFile!!, out)
                }
            } ?: false

            if (remuxed && out.length() > MIN_MEDIA_BYTES) {
                if (videoFile!!.absolutePath != out.absolutePath && plan.kind == PlanKind.HLS_CMAF) {
                    videoFile!!.delete()
                    audioFile?.delete()
                }
                onProgress(100)
                Logd.i("video ready: ${out.name} (${out.length()} bytes, audio=${audioFile != null})")
                return@withContext out
            }

            // Last resort: play the raw video file even if remux failed/timed out.
            Logd.w("video remux failed or timed out; falling back to raw video")
            out.delete()
            onProgress(100)
            videoFile
        }

    private enum class PlanKind { HLS_CMAF, VID_PROXY, DIRECT }

    private data class VideoPlan(
        val kind: PlanKind,
        val videoUrls: List<String>,
        val audioUrls: List<String>,
    )

    /**
     * Build download plan from a Redlib media URL.
     *
     * Live Redlib/v.redd.it (2026): HLS master lists CMAF_<q>.mp4 + CMAF_AUDIO_<br>.mp4
     * under the same /hls/<id>/ prefix. DASH_* and /vid/ are legacy and often 403.
     */
    private fun resolveVideoPlan(url0: String): VideoPlan {
        val noQuery = url0.substringBefore('?')

        // /hls/<id>/HLSPlaylist.m3u8  or any /hls/<id>/...
        val hlsMatch = Regex("""^(https?://[^/]+)?(/hls/[^/]+/)""", RegexOption.IGNORE_CASE)
            .find(noQuery)
        if (hlsMatch != null || noQuery.contains("HLSPlaylist.m3u8", ignoreCase = true)) {
            val origin = Regex("""^(https?://[^/]+)""", RegexOption.IGNORE_CASE).find(url0)?.groupValues?.get(1).orEmpty()
            val basePath = when {
                hlsMatch != null -> hlsMatch.groupValues[2]
                else -> {
                    // strip filename
                    val idx = noQuery.indexOf("/hls/")
                    if (idx >= 0) {
                        val rest = noQuery.substring(idx)
                        rest.substringBeforeLast('/') + "/"
                    } else "/hls/"
                }
            }
            val base = origin + basePath
            val video = linkedSetOf<String>()
            // Prefer mid qualities first (faster, usually enough); then higher.
            // Prefer mid quality first, then higher/lower — full ladder, no quality tradeoff.
            for (q in listOf("480", "720", "360", "270", "220", "1080")) {
                video += base + "CMAF_$q.mp4"
            }
            for (q in listOf("480", "720", "360", "1080")) {
                video += base + "DASH_$q.mp4"
            }
            val audio = listOf(
                base + "CMAF_AUDIO_128.mp4",
                base + "CMAF_AUDIO_64.mp4",
                base + "DASH_AUDIO_128.mp4",
                base + "DASH_AUDIO_64.mp4",
            )
            return VideoPlan(PlanKind.HLS_CMAF, video.toList(), audio)
        }

        // /vid/<id>/<size> → Redlib rewrites to DASH_<size>; also try sibling CMAF via /hls/
        val vidMatch = Regex("""^(https?://[^/]+)?(/vid/([^/]+)/)""", RegexOption.IGNORE_CASE)
            .find(noQuery)
        if (vidMatch != null) {
            val origin = vidMatch.groupValues[1]
            val id = vidMatch.groupValues[3]
            val video = linkedSetOf<String>()
            // Prefer constructing /hls/ CMAF which works on modern posts.
            val hlsBase = "$origin/hls/$id/"
            for (q in listOf("480", "720", "360", "1080")) {
                video += hlsBase + "CMAF_$q.mp4"
            }
            for (q in listOf("480", "720", "360", "1080")) {
                video += "$origin/vid/$id/$q"
                video += "$origin/vid/$id/$q.mp4"
            }
            video += url0
            val audio = listOf(
                hlsBase + "CMAF_AUDIO_128.mp4",
                hlsBase + "CMAF_AUDIO_64.mp4",
            )
            return VideoPlan(PlanKind.VID_PROXY, video.toList(), audio)
        }

        // Direct progressive (preview gif?format=mp4, redgifs proxy, plain mp4).
        return VideoPlan(PlanKind.DIRECT, listOf(url0), emptyList())
    }

    private fun isPlaylistOrNonMediaUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("hlsplaylist.m3u8") ||
            u.endsWith(".m3u8") ||
            u.contains(".m3u8?") ||
            u.contains("dashplaylist.mpd") ||
            u.endsWith(".mpd")
    }

    /** Reject HTML bot-walls and m3u8/playlist bodies mistaken for media. */
    private fun isLikelyMediaFile(f: File): Boolean {
        if (f.length() < MIN_MEDIA_BYTES) return false
        val head = f.inputStream().use { it.readNBytes(512) }
        if (head.isEmpty()) return false
        val asText = head.toString(Charsets.ISO_8859_1).lowercase()
        if (asText.contains("<html") || asText.contains("<!doctype") || asText.contains("anubis")) {
            return false
        }
        if (asText.startsWith("#extm3u") || asText.contains("#ext-x-")) {
            return false
        }
        // ISO BMFF / MP4 typically starts with size + 'ftyp'
        if (head.size >= 8) {
            val box = head.copyOfRange(4, 8).toString(Charsets.ISO_8859_1)
            if (box == "ftyp" || box == "moof" || box == "mdat" || box == "moov") return true
        }
        // JPEG/PNG/GIF/WebP for image path
        if (head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) return true
        if (head.size >= 4 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte()) return true
        if (asText.startsWith("gif8")) return true
        if (asText.startsWith("riff") && asText.contains("webp")) return true
        // Allow other binaries that passed HTML checks (legacy containers).
        return !asText.trimStart().startsWith("<")
    }

    private fun headContentLength(url: String): Long? = try {
        val req = okhttp3.Request.Builder().url(url).head().build()
        Http.client.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull() else null
        }
    } catch (_: Exception) { null }

    /** Copy all A/V samples from a single source into a fresh progressive MP4. */
    private fun remux(src: File, dst: File): Boolean {
        try {
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
            if (indexMap.isEmpty()) {
                mux.release(); ex.release()
                Logd.w("remux: no video/audio tracks in source")
                return false
            }
            val buf = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            mux.start()
            var samplesWritten = 0
            while (true) {
                val trackIdx = ex.sampleTrackIndex
                if (trackIdx < 0) break
                buf.clear()
                info.size = ex.readSampleData(buf, 0)
                if (info.size < 0) break
                info.offset = 0
                info.presentationTimeUs = ex.sampleTime
                info.flags = ex.sampleFlags
                buf.flip()
                indexMap[trackIdx]?.let { mux.writeSampleData(it, buf, info) }
                samplesWritten++
                if (!ex.advance()) break
            }
            mux.stop(); mux.release(); ex.release()
            if (samplesWritten == 0 || dst.length() < MIN_MEDIA_BYTES) {
                Logd.w("remux: empty/too-small (samples=$samplesWritten, size=${dst.length()})")
                dst.delete()
                return false
            }
            Logd.i("remux ok: ${dst.name} (${dst.length()} bytes, $samplesWritten samples)")
            return true
        } catch (t: Throwable) {
            Logd.e("remux failed", t)
            dst.delete()
            return false
        }
    }

    /**
     * Mux video-only + audio-only CMAF/fMP4 files into one progressive MP4.
     * Writes all video samples then all audio samples (players accept this).
     */
    private fun remuxAv(videoSrc: File, audioSrc: File, dst: File): Boolean {
        var vEx: MediaExtractor? = null
        var aEx: MediaExtractor? = null
        var mux: MediaMuxer? = null
        try {
            vEx = MediaExtractor().also { it.setDataSource(videoSrc.absolutePath) }
            aEx = MediaExtractor().also { it.setDataSource(audioSrc.absolutePath) }

            var vTrack = -1
            var aTrack = -1
            var vFmt: MediaFormat? = null
            var aFmt: MediaFormat? = null
            for (i in 0 until vEx.trackCount) {
                val fmt = vEx.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    vTrack = i; vFmt = fmt; break
                }
            }
            for (i in 0 until aEx.trackCount) {
                val fmt = aEx.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    aTrack = i; aFmt = fmt; break
                }
            }
            if (vTrack < 0 || vFmt == null) {
                Logd.w("remuxAv: no video track")
                return remux(videoSrc, dst)
            }

            mux = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            vEx.selectTrack(vTrack)
            val outV = mux.addTrack(vFmt)
            val outA = if (aTrack >= 0 && aFmt != null) {
                aEx.selectTrack(aTrack)
                mux.addTrack(aFmt)
            } else -1

            mux.start()
            val buf = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var samples = 0

            fun drain(ex: MediaExtractor, outIdx: Int) {
                while (true) {
                    buf.clear()
                    val sz = ex.readSampleData(buf, 0)
                    if (sz < 0) break
                    info.offset = 0
                    info.size = sz
                    info.presentationTimeUs = ex.sampleTime.coerceAtLeast(0)
                    info.flags = ex.sampleFlags
                    buf.flip()
                    mux.writeSampleData(outIdx, buf, info)
                    samples++
                    if (!ex.advance()) break
                }
            }

            drain(vEx, outV)
            if (outA >= 0) drain(aEx, outA)

            mux.stop()
            if (samples == 0 || dst.length() < MIN_MEDIA_BYTES) {
                Logd.w("remuxAv: empty output samples=$samples size=${dst.length()}")
                dst.delete()
                return false
            }
            Logd.i("remuxAv ok: ${dst.name} (${dst.length()} bytes, $samples samples, hasAudio=${outA >= 0})")
            return true
        } catch (t: Throwable) {
            Logd.e("remuxAv failed", t)
            dst.delete()
            // Fall back to video-only remux.
            return remux(videoSrc, dst)
        } finally {
            try { vEx?.release() } catch (_: Throwable) {}
            try { aEx?.release() } catch (_: Throwable) {}
            try { mux?.release() } catch (_: Throwable) {}
        }
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
        if (Settings.dataSaver) return@withContext
        posts.take(12).forEach { post ->
            val url = post.imageUrl ?: return@forEach
            if (post.isVideo) return@forEach
            if (localUri(url) == null) getOrDownload(url)
        }
    }

    private fun purgeOld() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /** Total bytes used by the on-disk media cache (0 if not initialized). */
    fun totalBytes(): Long {
        if (!this::dir.isInitialized) return 0L
        return dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** Delete all cached media files. Safe to call from any thread. */
    fun clearAll() {
        if (!this::dir.isInitialized) return
        dir.listFiles()?.forEach { it.delete() }
    }
}
