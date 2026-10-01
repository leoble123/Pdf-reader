package app.lumen.reader.rich

import android.graphics.Bitmap
import androidx.compose.ui.text.AnnotatedString

/**
 * Lenient HTML/XHTML → [Block] converter used for EPUB chapters.
 * It's a tokenizer + style stack rather than a DOM, so malformed markup never throws.
 */
class HtmlBlocks(
    private val resolveImage: (src: String) -> (suspend (Int) -> Bitmap?)?
) {
    private val blocks = ArrayList<Block>()
    private var text = StyledText()
    private var headingLevel = 0
    private var inPre = 0
    private var inQuote = 0
    private val pre = StringBuilder()
    private val listStack = ArrayList<IntArray>() // [ordered(0/1), counter]
    private var inLi = false
    private var skipDepth = 0
    private var table: ArrayList<List<AnnotatedString>>? = null
    private var row: ArrayList<AnnotatedString>? = null
    private var cell: StyledText? = null
    private var headerRow = false
    val headings = ArrayList<Pair<String, Int>>()

    fun parse(html: String): List<Block> {
        var i = 0
        val n = html.length
        while (i < n) {
            val lt = html.indexOf('<', i)
            val textEnd = if (lt < 0) n else lt
            if (textEnd > i) onText(html.substring(i, textEnd))
            if (lt < 0) break
            if (html.startsWith("<!--", lt)) {
                val e = html.indexOf("-->", lt + 4)
                i = if (e < 0) n else e + 3; continue
            }
            if (html.startsWith("<![CDATA[", lt)) {
                val e = html.indexOf("]]>", lt)
                if (e > 0) onText(html.substring(lt + 9, e))
                i = if (e < 0) n else e + 3; continue
            }
            val gt = findTagEnd(html, lt)
            if (gt < 0) {
                onText(html.substring(lt)); break
            }
            val raw = html.substring(lt + 1, gt)
            i = gt + 1
            if (raw.startsWith("!") || raw.startsWith("?")) continue
            val closing = raw.startsWith("/")
            val selfClosing = raw.endsWith("/")
            val body = raw.trim('/', ' ', '\n', '\t', '\r')
            val name = body.takeWhile { !it.isWhitespace() }.substringAfter(':').lowercase()
            if (closing) close(name) else {
                open(name, body)
                if (selfClosing || name in voidTags) close(name)
            }
            if (!closing && (name == "script" || name == "style")) {
                val end = html.indexOf("</$name", i, ignoreCase = true)
                i = if (end < 0) n else html.indexOf('>', end).let { if (it < 0) n else it + 1 }
                skipDepth = 0
            }
        }
        flush()
        flushTable()
        return blocks
    }

    private fun findTagEnd(s: String, from: Int): Int {
        var q: Char? = null
        var i = from + 1
        while (i < s.length) {
            val c = s[i]
            if (q != null) {
                if (c == q) q = null
            } else if (c == '"' || c == '\'') q = c
            else if (c == '>') return i
            i++
        }
        return -1
    }

    private fun attr(body: String, key: String): String? {
        val m = Regex("(?:^|\\s)(?:[\\w-]+:)?$key\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(body)
            ?: return null
        return decodeEntities(m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } })
    }

    private fun onText(raw: String) {
        if (skipDepth > 0) return
        val t = decodeEntities(raw)
        when {
            inPre > 0 -> pre.append(t)
            cell != null -> cell!!.appendCollapsed(t)
            else -> text.appendCollapsed(t)
        }
    }

    private fun open(name: String, body: String) {
        when (name) {
            "head", "title", "nav" -> skipDepth++
            "p", "div", "section", "article", "header", "footer", "aside", "figure", "figcaption", "dd", "dt", "center" -> flush()
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                flush(); headingLevel = name[1].digitToInt()
            }
            "br" -> if (inPre > 0) pre.append('\n') else (cell ?: text).lineBreak()
            "hr" -> {
                flush(); blocks += Block.Rule
            }
            "pre" -> {
                flush(); inPre++
            }
            "blockquote" -> {
                flush(); inQuote++
            }
            "ul", "ol" -> {
                flush(); listStack += intArrayOf(if (name == "ol") 1 else 0, 0)
            }
            "li" -> {
                flush(); inLi = true
                listStack.lastOrNull()?.let { it[1]++ }
            }
            "img", "image" -> {
                val src = attr(body, "src") ?: attr(body, "href")
                val loader = src?.let(resolveImage)
                if (loader != null) {
                    flush()
                    blocks += Block.Image(src, attr(body, "alt"), loader)
                }
            }
            "table" -> {
                flush(); table = ArrayList()
            }
            "tr" -> {
                row = ArrayList(); headerRow = false
            }
            "td", "th" -> {
                if (name == "th") headerRow = true
                cell = StyledText()
            }
            "a" -> (cell ?: text).push("a", attr(body, "href"))
            else -> (cell ?: text).push(name)
        }
    }

    private fun close(name: String) {
        when (name) {
            "head", "title", "nav" -> skipDepth = (skipDepth - 1).coerceAtLeast(0)
            "p", "div", "section", "article", "header", "footer", "aside", "figure", "figcaption", "dd", "dt", "center" -> flush()
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                flush(); headingLevel = 0
            }
            "pre" -> {
                if (inPre > 0) inPre--
                if (inPre == 0) {
                    blocks += Block.Code(pre.toString().trim('\n'), null); pre.setLength(0)
                }
            }
            "blockquote" -> {
                flush(); inQuote = (inQuote - 1).coerceAtLeast(0)
            }
            "ul", "ol" -> {
                flush(); if (listStack.isNotEmpty()) listStack.removeAt(listStack.lastIndex)
            }
            "li" -> {
                flush(); inLi = false
            }
            "td", "th" -> {
                cell?.let { row?.add(it.build()) }
                cell = null
            }
            "tr" -> {
                row?.let { r -> if (r.isNotEmpty()) table?.add(r) }
                row = null
            }
            "table" -> flushTable()
            "body", "html" -> flush()
            else -> (cell ?: text).pop(name)
        }
    }

    private fun flushTable() {
        val t = table ?: return
        if (t.isNotEmpty()) blocks += Block.Table(t, header = true)
        table = null
    }

    private fun flush() {
        if (cell != null) return
        if (text.isBlank) return
        val built = text.build()
        text = StyledText()
        if (built.isBlank()) return
        val list = listStack.lastOrNull()
        blocks += when {
            headingLevel > 0 -> {
                headings += built.text to blocks.size
                Block.Heading(headingLevel, built)
            }
            inLi && list != null -> Block.ListItem(
                (listStack.size - 1).coerceAtMost(4),
                if (list[0] == 1) "${list[1]}." else "•",
                built
            )
            inQuote > 0 -> Block.Quote(built)
            else -> Block.Paragraph(built)
        }
    }

    private companion object {
        val voidTags = setOf("br", "hr", "img", "meta", "link", "input", "col", "source", "wbr", "area", "base", "image")
    }
}
