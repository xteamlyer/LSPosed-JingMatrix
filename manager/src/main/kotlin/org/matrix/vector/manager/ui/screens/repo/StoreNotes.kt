package org.matrix.vector.manager.ui.screens.repo

import android.graphics.Typeface
import android.text.style.BulletSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.text.HtmlCompat

/**
 * Release notes as text, not as a web page.
 *
 * The notes used to be a `WebView` with a bounded height inside the releases list, which put a
 * scrolling view inside a scrolling view: the inner one swallowed the drag whenever a finger
 * happened to land on it, and no amount of tuning makes that read as anything but broken. Nothing
 * should ship that pattern.
 *
 * So the HTML is flattened to an [AnnotatedString] and laid out as ordinary text of whatever height
 * it needs, and the list — the one scrolling thing on the screen — scrolls it. That also removes a
 * renderer process per expanded release, which is why only one set of notes could be open at a time.
 *
 * The full page keeps its WebView: a README is a document with its own layout, images and tables,
 * and it is the whole content of its tab rather than a paragraph inside a list. Release notes are
 * a few lines of markdown, and this covers what they actually use — emphasis, code, links, lists
 * and strikethrough. Links are marked but not individually tappable; every release already carries
 * an "open this release" action, which is where a link-following reader is headed anyway.
 */
internal fun releaseNotes(html: String, linkColor: Color, codeColor: Color): AnnotatedString {
    val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
    val text = spanned.toString().trim().ifEmpty { return AnnotatedString("") }

    return buildAnnotatedString {
        append(text)
        val limit = text.length

        spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            // fromHtml's trailing newlines are trimmed above, so a span can now run past the end.
            if (start < 0 || end <= start || start >= limit) return@forEach
            val to = end.coerceAtMost(limit)

            val style =
                when (span) {
                    is StyleSpan ->
                        when (span.style) {
                            Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                            Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                            Typeface.BOLD_ITALIC ->
                                SpanStyle(
                                    fontWeight = FontWeight.Bold,
                                    fontStyle = FontStyle.Italic,
                                )
                            else -> null
                        }
                    is TypefaceSpan ->
                        if (span.family == "monospace") SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor)
                        else null
                    is URLSpan ->
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                    is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
                    is StrikethroughSpan ->
                        SpanStyle(textDecoration = TextDecoration.LineThrough)
                    is BulletSpan -> null
                    else -> null
                }
            if (style != null) addStyle(style, start, to)
        }
    }
}
