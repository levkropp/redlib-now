package app.redlib.now.parse

import app.redlib.now.model.Post
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Parses Redlib subreddit/frontpage HTML into [Post]s. */
object PostParser {

    fun parseFeed(html: String, baseUrl: String): List<Post> {
        val doc: Document = Jsoup.parse(html, baseUrl)
        return doc.select("div.post").mapNotNull { el -> parsePost(el, baseUrl) }
    }

    fun parsePost(el: Element, baseUrl: String): Post? {
        val id = el.id().ifEmpty { null } ?: return null
        // The flair link is also an <a> inside h2.post_title and comes first,
        // so exclude it explicitly — otherwise flaired posts show the flair as
        // the title (e.g. "News" instead of the real headline).
        val titleEl = el.selectFirst("h2.post_title a:not(.post_flair)") ?: return null
        val title = titleEl.text().trim()
        val permalink = titleEl.attr("href")

        val subreddit = el.selectFirst("a.post_subreddit")?.text()?.trim()
            ?.removePrefix("r/")?.removePrefix("/r/") ?: ""
        val author = el.selectFirst("a.post_author")?.text()?.trim()
            ?.removePrefix("u/")?.removePrefix("/u/")?.ifEmpty { null }
        val timeAgo = el.selectFirst("span.created")?.text()?.trim()?.ifEmpty { null }

        val flair = el.selectFirst("a.post_flair")?.text()?.trim()?.ifEmpty { null }
        val selfText = el.selectFirst("div.post_body.post_preview")?.text()?.trim()
            ?.ifEmpty { null }

        // Media: image posts embed <svg><image href="/img/...">, videos use
        // <video poster="..."><source src="/hls/....m3u8">. All proxy paths.
        var imageUrl: String? = null
        var videoUrl: String? = null
        var isVideo = false
        el.selectFirst("div.post_media_content")?.let { media ->
            val video = media.selectFirst("video")
            if (video != null) {
                isVideo = true
                imageUrl = absolutize(video.attr("poster"), baseUrl)
                val src = video.selectFirst("source")?.attr("src")?.ifEmpty { null }
                    ?: video.attr("src").ifEmpty { null }
                videoUrl = src?.let { absolutize(it, baseUrl) }
            } else {
                val svgImg = media.selectFirst("svg image")?.attr("href")?.ifEmpty { null }
                    ?: media.selectFirst("svg image")?.attr("src")?.ifEmpty { null }
                if (svgImg != null) {
                    imageUrl = absolutize(svgImg, baseUrl)
                }
                // Some posts carry a plain <img> fallback too.
                if (imageUrl == null) {
                    media.selectFirst("img[src]")?.attr("src")?.let { imageUrl = absolutize(it, baseUrl) }
                }
            }
        }

        val scoreEl = el.selectFirst("div.post_score")
        val score = scoreEl?.attr("title")?.toLongOrNull()
            ?: scoreEl?.text()?.trim()?.split(" ")?.firstOrNull()?.parseKSuffix()

        val commentsEl = el.selectFirst("a.post_comments")
        val commentCount = commentsEl?.attr("title")?.substringBefore(" ")?.toLongOrNull()
            ?: commentsEl?.text()?.substringBefore(" ")?.parseKSuffix()

        val nsfw = el.classNames().any { it.equals("nsfw", true) } ||
            el.select(".post_flair").text().contains("NSFW", true)

        // Link posts carry the external URL in a.post_thumbnail (with the
        // domain in its <span>), optionally with a small preview image.
        var externalUrl: String? = null
        var linkDomain: String? = null
        var isGallery = false
        el.selectFirst("a.post_thumbnail")?.let { thumb ->
            val href = thumb.attr("href")
            val label = thumb.selectFirst("span")?.text()?.trim()?.lowercase()
            val svgImg = thumb.selectFirst("svg image")?.attr("href")?.takeIf { it.isNotBlank() }
                ?: thumb.selectFirst("img[src]")?.attr("src")?.takeIf { it.isNotBlank() }
            when {
                // External link post: href is the article URL.
                href.startsWith("http") -> {
                    externalUrl = href
                    linkDomain = thumb.selectFirst("span")?.text()?.trim()?.ifEmpty { null }
                    if (imageUrl == null && svgImg != null) imageUrl = absolutize(svgImg, baseUrl)
                }
                // Gallery post: relative permalink + a preview thumb.
                label == "gallery" -> {
                    isGallery = true
                    if (imageUrl == null && svgImg != null) imageUrl = absolutize(svgImg, baseUrl)
                }
            }
        }

        return Post(
            id = id,
            subreddit = subreddit,
            author = author,
            title = title,
            permalink = permalink,
            flair = flair,
            selfTextPreview = selfText,
            imageUrl = imageUrl,
            videoUrl = videoUrl,
            isVideo = isVideo,
            score = score,
            commentCount = commentCount,
            timeAgo = timeAgo,
            nsfw = nsfw,
            externalUrl = externalUrl,
            linkDomain = linkDomain,
            isGallery = isGallery,
        )
    }

    private fun absolutize(src: String, baseUrl: String): String = when {
        src.startsWith("http") -> src
        src.startsWith("/") -> baseUrl + src
        else -> "$baseUrl/$src"
    }

    private fun String.parseKSuffix(): Long? {
        val s = trim().lowercase()
        val n = s.removeSuffix("m").removeSuffix("k").toDoubleOrNull() ?: return null
        return when {
            s.endsWith("m") -> (n * 1_000_000).toLong()
            s.endsWith("k") -> (n * 1_000).toLong()
            else -> n.toLong()
        }
    }
}
