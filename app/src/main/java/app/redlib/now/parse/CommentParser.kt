package app.redlib.now.parse

import app.redlib.now.model.Comment
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Parses Redlib comment threads (nested `div.comment` inside `blockquote.replies`). */
object CommentParser {

    fun parseComments(html: String, baseUrl: String): List<Comment> {
        val doc = Jsoup.parse(html, baseUrl)
        val threads = doc.select("div.thread > div.comment")
        if (threads.isEmpty()) return emptyList()
        return threads.mapNotNull { parseComment(it, null, baseUrl) }
    }

    private fun parseComment(el: Element, parentId: String?, baseUrl: String): Comment? {
        val id = el.id().ifEmpty { return null }
        val authorEl = el.selectFirst("a.comment_author")
        val author = authorEl?.text()?.trim()?.removePrefix("u/")?.removePrefix("/u/")?.ifEmpty { null }
        val isMod = authorEl?.classNames()?.contains("moderator") == true
        val isOp = authorEl?.classNames()?.contains("author") == true ||
            authorEl?.classNames()?.contains("op") == true
        val scoreEl = el.selectFirst("p.comment_score")
        val score = scoreEl?.attr("title")?.toLongOrNull()
            ?: scoreEl?.text()?.trim()?.takeIf { it != "•" }?.let { parseK(it) }
        val timeAgo = el.selectFirst("a.created")?.text()?.trim()?.ifEmpty { null }
        val bodyEl = el.selectFirst("div.comment_body")
        val body = bodyEl?.let { htmlToText(it) } ?: ""
        // Inline media: reddit-hosted images render as <figure><img>; bare
        // image-only comments would otherwise parse to an empty body.
        val imgSrc = bodyEl?.selectFirst("img[src]")?.attr("src")?.takeIf { it.isNotBlank() }
        val imageUrl = imgSrc?.let { absolutize(it, baseUrl) }
        val replies = el.selectFirst("blockquote.replies")
            ?.select("> div.comment")?.mapNotNull { parseComment(it, id, baseUrl) }.orEmpty()
        return Comment(id, author, score, timeAgo, body, isOp, isMod, parentId, imageUrl, replies)
    }

    /** Minimal HTML -> readable plain text: paragraphs and <br> become newlines. */
    private fun absolutize(src: String, baseUrl: String): String = when {
        src.startsWith("http") -> src
        src.startsWith("/") -> baseUrl + src
        else -> "$baseUrl/$src"
    }

    private fun htmlToText(el: Element): String = buildString {
        for (child in el.select("> div.md").firstOrNull()?.childNodes() ?: el.childNodes()) {
            when (child) {
                is Element -> when (child.tagName()) {
                    "p" -> { append(child.text()); append("\n\n") }
                    "br" -> append("\n")
                    else -> append(child.text())
                }
                else -> append(child.toString())
            }
        }
    }.trim()

    private fun parseK(s: String): Long? {
        val t = s.trim().lowercase()
        val n = t.removeSuffix("m").removeSuffix("k").toDoubleOrNull() ?: return null
        return when {
            t.endsWith("m") -> (n * 1_000_000).toLong()
            t.endsWith("k") -> (n * 1_000).toLong()
            else -> n.toLong()
        }
    }
}
