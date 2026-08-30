package app.redlib.now.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.graphics.Color

private val URL_RE = Regex("""(https?://[^\s)]+)""")

/**
 * Turns raw text (comment bodies, selftext) into an [AnnotatedString] with
 * clickable links. Reddit links resolve back into this app via its intent
 * filters; other links go to the browser.
 */
fun linkify(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in URL_RE.findAll(text)) {
        append(text.substring(last, m.range.first))
        withLink(
            LinkAnnotation.Url(
                m.value,
                TextLinkStyles(SpanStyle(color = linkColor)),
            )
        ) {
            append(m.value)
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}
