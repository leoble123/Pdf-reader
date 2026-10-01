package app.lumen.reader.rich

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration

internal val LinkColor = Color(0xFF7C8CFF)
internal val CodeSpan = SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x2A8890A8))
internal val LinkStyles = TextLinkStyles(SpanStyle(color = LinkColor, textDecoration = TextDecoration.Underline))

/** Incremental styled-text builder shared by the XML/HTML based parsers. */
internal class StyledText {
    private val b = AnnotatedString.Builder()
    private var bold = 0
    private var italic = 0
    private var underline = 0
    private var strike = 0
    private var mono = 0
    private var sup = 0
    private var sub = 0
    private var link: String? = null
    private var pendingSpace = false

    val isBlank: Boolean get() = b.length == 0

    fun push(tag: String, href: String? = null) {
        when (tag) {
            "b", "strong" -> bold++
            "i", "em", "cite", "dfn" -> italic++
            "u", "ins" -> underline++
            "s", "strike", "del" -> strike++
            "code", "tt", "kbd", "samp" -> mono++
            "sup" -> sup++
            "sub" -> sub++
            "a" -> link = href
        }
    }

    fun pop(tag: String) {
        when (tag) {
            "b", "strong" -> bold = (bold - 1).coerceAtLeast(0)
            "i", "em", "cite", "dfn" -> italic = (italic - 1).coerceAtLeast(0)
            "u", "ins" -> underline = (underline - 1).coerceAtLeast(0)
            "s", "strike", "del" -> strike = (strike - 1).coerceAtLeast(0)
            "code", "tt", "kbd", "samp" -> mono = (mono - 1).coerceAtLeast(0)
            "sup" -> sup = (sup - 1).coerceAtLeast(0)
            "sub" -> sub = (sub - 1).coerceAtLeast(0)
            "a" -> link = null
        }
    }

    fun setStyle(bold: Boolean, italic: Boolean, underline: Boolean, strike: Boolean) {
        this.bold = if (bold) 1 else 0
        this.italic = if (italic) 1 else 0
        this.underline = if (underline) 1 else 0
        this.strike = if (strike) 1 else 0
    }

    fun setLink(url: String?) {
        link = url
    }

    /** Appends text collapsing whitespace like HTML does. */
    fun appendCollapsed(raw: String) {
        for (c in raw) {
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000C') {
                if (b.length > 0) pendingSpace = true
            } else {
                if (pendingSpace) {
                    appendStyled(" "); pendingSpace = false
                }
                appendStyled(c.toString())
            }
        }
    }

    fun appendRaw(text: String) {
        if (pendingSpace && text.isNotEmpty()) {
            appendStyled(" "); pendingSpace = false
        }
        appendStyled(text)
    }

    fun lineBreak() {
        pendingSpace = false
        b.append('\n')
    }

    private fun appendStyled(text: String) {
        val start = b.length
        b.append(text)
        val end = b.length
        if (bold > 0 || italic > 0 || underline > 0 || strike > 0 || sup > 0 || sub > 0) {
            val deco = when {
                underline > 0 && strike > 0 -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                underline > 0 -> TextDecoration.Underline
                strike > 0 -> TextDecoration.LineThrough
                else -> null
            }
            b.addStyle(
                SpanStyle(
                    fontWeight = if (bold > 0) FontWeight.Bold else null,
                    fontStyle = if (italic > 0) FontStyle.Italic else null,
                    textDecoration = deco,
                    baselineShift = when {
                        sup > 0 -> BaselineShift.Superscript
                        sub > 0 -> BaselineShift.Subscript
                        else -> null
                    }
                ),
                start, end
            )
        }
        if (mono > 0) b.addStyle(CodeSpan, start, end)
        link?.let { url ->
            if (url.startsWith("http") || url.startsWith("mailto:")) {
                b.addLink(LinkAnnotation.Url(url, LinkStyles), start, end)
            } else {
                b.addStyle(SpanStyle(color = LinkColor), start, end)
            }
        }
    }

    fun build(): AnnotatedString {
        val s = b.toAnnotatedString()
        // trim trailing newlines/spaces
        var end = s.length
        while (end > 0 && s[end - 1].isWhitespace()) end--
        return if (end == s.length) s else s.subSequence(0, end)
    }

    fun reset() {
        // Builder can't be cleared; callers create a new StyledText per block.
    }
}

internal val namedEntities = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "ndash" to "–", "mdash" to "—", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“",
    "rdquo" to "”", "copy" to "©", "reg" to "®", "trade" to "™", "bull" to "•", "middot" to "·",
    "times" to "×", "divide" to "÷", "deg" to "°", "plusmn" to "±", "laquo" to "«", "raquo" to "»",
    "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "sect" to "§", "para" to "¶",
    "shy" to "­", "zwnj" to "‌", "zwj" to "‍", "thinsp" to " ", "ensp" to " ",
    "emsp" to " ", "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "hearts" to "♥",
    "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "eacute" to "é", "egrave" to "è", "agrave" to "à",
    "aacute" to "á", "ccedil" to "ç", "ouml" to "ö", "uuml" to "ü", "auml" to "ä", "szlig" to "ß",
    "ntilde" to "ñ", "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "ecirc" to "ê", "acirc" to "â"
)

internal fun decodeEntities(s: String): String {
    if (s.indexOf('&') < 0) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '&') {
            val semi = s.indexOf(';', i + 1)
            if (semi in (i + 2)..(i + 12)) {
                val name = s.substring(i + 1, semi)
                val rep = when {
                    name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                    name.startsWith("#") -> name.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                    else -> namedEntities[name]
                }
                if (rep != null) {
                    out.append(rep); i = semi + 1; continue
                }
            }
        }
        out.append(c); i++
    }
    return out.toString()
}
