package app.redlib.now.data

import app.redlib.now.model.Comment
import app.redlib.now.model.Post
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 72-hour offline cache for "stuff we have seen": feed listings, comment
 * threads, and (via [MediaCache]) their media. App-private JSON in filesDir.
 *
 * Offline = last seen feeds and their cards render fully; viewing a video
 * works whenever its file is still inside the retention window.
 */
object FeedCache {
    const val RETENTION_MS = 72L * 3600 * 1000

    private lateinit var feedDir: File
    private lateinit var commentDir: File

    fun init(context: android.content.Context) {
        feedDir = File(context.filesDir, "feeds").apply { mkdirs() }
        commentDir = File(context.filesDir, "comments").apply { mkdirs() }
        purgeOld()
    }

    // ---- feeds ---------------------------------------------------------

    data class CachedFeed(val posts: List<Post>, val savedAt: Long)

    fun loadFeed(path: String): CachedFeed? = try {
        val f = fileFor(feedDir, path)
        if (!f.exists()) null else {
            val obj = JSONObject(f.readText())
            CachedFeed(parsePosts(obj.getJSONArray("posts")), obj.getLong("savedAt"))
        }
    } catch (t: Throwable) {
        android.util.Log.w("NowRedlib", "feed cache read failed: ${t.message}")
        null
    }

    fun saveFeed(path: String, posts: List<Post>) = try {
        val obj = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("path", path)
            .put("posts", JSONArray().apply { posts.forEach { put(postJson(it)) } })
        fileFor(feedDir, path).writeText(obj.toString())
    } catch (_: Throwable) { }

    // ---- comments ------------------------------------------------------

    fun loadComments(permalink: String): List<Comment>? = try {
        val f = fileFor(commentDir, permalink)
        if (!f.exists()) null else parseComments(JSONArray(f.readText()))
    } catch (t: Throwable) {
        android.util.Log.w("NowRedlib", "comment cache read failed: ${t.message}")
        null
    }

    fun saveComments(permalink: String, comments: List<Comment>) = try {
        fileFor(commentDir, permalink).writeText(
            JSONArray().apply { comments.forEach { put(commentJson(it)) } }.toString()
        )
    } catch (_: Throwable) { }

    // ---- internals -----------------------------------------------------

    private fun fileFor(dir: File, key: String): File =
        File(dir, MessageDigest.getInstance("MD5")
            .digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")

    private fun postJson(p: Post): JSONObject = JSONObject()
        .put("id", p.id)
        .put("subreddit", p.subreddit)
        .put("author", p.author ?: "")
        .put("title", p.title)
        .put("permalink", p.permalink)
        .put("flair", p.flair ?: "")
        .put("selfText", p.selfTextPreview ?: "")
        .put("imageUrl", p.imageUrl ?: "")
        .put("videoUrl", p.videoUrl ?: "")
        .put("isVideo", p.isVideo)
        .put("score", p.score ?: -1)
        .put("commentCount", p.commentCount ?: -1)
        .put("timeAgo", p.timeAgo ?: "")
        .put("nsfw", p.nsfw)

    private fun parsePosts(arr: JSONArray): List<Post> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        Post(
            id = o.getString("id"),
            subreddit = o.getString("subreddit"),
            author = o.optString("author").ifEmpty { null },
            title = o.getString("title"),
            permalink = o.getString("permalink"),
            flair = o.optString("flair").ifEmpty { null },
            selfTextPreview = o.optString("selfText").ifEmpty { null },
            imageUrl = o.optString("imageUrl").ifEmpty { null },
            videoUrl = o.optString("videoUrl").ifEmpty { null },
            isVideo = o.getBoolean("isVideo"),
            score = o.getLong("score").takeIf { it >= 0 },
            commentCount = o.getLong("commentCount").takeIf { it >= 0 },
            timeAgo = o.optString("timeAgo").ifEmpty { null },
            nsfw = o.getBoolean("nsfw"),
        )
    }

    private fun commentJson(c: Comment): JSONObject {
        val replies = JSONArray()
        for (r in c.replies) replies.put(commentJson(r))
        return JSONObject()
            .put("id", c.id)
            .put("author", c.author ?: "")
            .put("score", c.score ?: -1)
            .put("timeAgo", c.timeAgo ?: "")
            .put("body", c.body)
            .put("isOp", c.isOp)
            .put("isMod", c.isMod)
            .put("parent", c.parentId ?: "")
            .put("replies", replies)
    }

    private fun parseComments(arr: JSONArray): List<Comment> {
        val out = ArrayList<Comment>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Comment(
                id = o.getString("id"),
                author = o.optString("author").ifEmpty { null },
                score = o.getLong("score").takeIf { it >= 0 },
                timeAgo = o.optString("timeAgo").ifEmpty { null },
                body = o.getString("body"),
                isOp = o.getBoolean("isOp"),
                isMod = o.getBoolean("isMod"),
                parentId = o.optString("parent").ifEmpty { null },
                replies = parseComments(o.getJSONArray("replies")),
            ))
        }
        return out
    }

    private fun purgeOld() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        (feedDir.listFiles() + commentDir.listFiles()).forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}
