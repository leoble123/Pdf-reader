package app.lumen.reader.rich

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/** A pragmatic CommonMark/GFM subset: everything people actually put in READMEs and notes. */
object Markdown {

    private val heading = Regex("^ {0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val fence = Regex("^ {0,3}(```+|~~~+)\\s*([\\w+#.-]*).*$")
    private val rule = Regex("^ {0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val bullet = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val ordered = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(src: String): RichDoc {
        val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<Block>()
        val toc = ArrayList<Pair<String, Int>>()
        val para = StringBuilder()

        fun flushPara() {
            if (para.isNotBlank()) blocks += Block.Paragraph(inline(para.toString().trim()))
            para.setLength(0)
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            // Front matter
            if (i == 0 && trimmed == "---") {
                val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
                if (end != null) {
                    blocks += Block.Code(lines.subList(1, end).joinToString("\n"), "yaml")
                    i = end + 1; continue
                }
            }
            if (trimmed.isEmpty()) {
                flushPara(); i++; continue
            }
            fence.matchEntire(line)?.let { m ->
                flushPara()
                val marker = m.groupValues[1]
                val lang = m.groupValues[2].ifEmpty { null }
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                blocks += Block.Code(code.toString(), lang)
                i++
                return@let
            }?.let { continue }
            heading.matchEntire(line)?.let { m ->
                flushPara()
                val level = m.groupValues[1].length
                val text = m.groupValues[2]
                if (level <= 3) toc += text to blocks.size
                blocks += Block.Heading(level, inline(text))
                i++
            }?.let { continue }
            // Setext headings
            if (para.isNotEmpty() && i > 0 && (trimmed.matches(Regex("=+")) || trimmed.matches(Regex("-+")) && para.isNotBlank())) {
                val level = if (trimmed.startsWith("=")) 1 else 2
                val text = para.toString().trim()
                para.setLength(0)
                toc += text to blocks.size
                blocks += Block.Heading(level, inline(text))
                i++; continue
            }
            if (rule.matches(line)) {
                flushPara(); blocks += Block.Rule; i++; continue
            }
            if (trimmed.startsWith(">")) {
                flushPara()
                val q = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    if (q.isNotEmpty()) q.append(' ')
                    q.append(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks += Block.Quote(inline(q.toString()))
                continue
            }
            // Tables
            if (trimmed.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1])) {
                flushPara()
                val rows = ArrayList<List<AnnotatedString>>()
                rows += splitRow(line).map { inline(it) }
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += splitRow(lines[i]).map { inline(it) }
                    i++
                }
                blocks += Block.Table(rows, header = true)
                continue
            }
            val b = bullet.matchEntire(line)
            val o = if (b == null) ordered.matchEntire(line) else null
            if (b != null || o != null) {
                flushPara()
                val m = (b ?: o)!!
                val depth = m.groupValues[1].replace("\t", "    ").length / 2
                var body = m.groupValues[3]
                var marker = if (b != null) "•" else m.groupValues[2] + "."
                if (body.startsWith("[ ] ")) {
                    marker = "☐"; body = body.substring(4)
                } else if (body.startsWith("[x] ") || body.startsWith("[X] ")) {
                    marker = "☑"; body = body.substring(4)
                }
                // lazy continuation lines
                i++
                val sb = StringBuilder(body)
                while (i < lines.size && lines[i].isNotBlank() && lines[i].startsWith("  ") &&
                    bullet.matchEntire(lines[i]) == null && ordered.matchEntire(lines[i]) == null
                ) {
                    sb.append(' ').append(lines[i].trim()); i++
                }
                blocks += Block.ListItem(depth.coerceAtMost(4), marker, inline(sb.toString()))
                continue
            }
            if (line.startsWith("    ") && para.isEmpty()) {
                val code = StringBuilder()
                while (i < lines.size && (lines[i].startsWith("    ") || lines[i].isBlank())) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i].removePrefix("    "))
                    i++
                }
                blocks += Block.Code(code.toString().trimEnd(), null)
                continue
            }
            if (para.isNotEmpty()) para.append(if (line.endsWith("  ")) "\n" else " ")
            para.append(trimmed)
            i++
        }
        flushPara()
        return RichDoc(blocks, toc = toc, kindLabel = "Markdown")
    }

    private fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        return s.split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }
    }

    private val urlRegex = Regex("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")

    fun inline(src: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        val n = src.length
        val plain = StringBuilder()
        fun flushPlain() {
            if (plain.isEmpty()) return
            val text = plain.toString()
            var last = 0
            for (m in urlRegex.findAll(text)) {
                append(text.substring(last, m.range.first))
                val url = m.value.trimEnd('.', ',', ')', ';')
                val start = length
                append(url)
                addLink(LinkAnnotation.Url(url, LinkStyles), start, length)
                last = m.range.first + url.length
            }
            append(text.substring(last))
            plain.setLength(0)
        }
        while (i < n) {
            val c = src[i]
            when {
                c == '\\' && i + 1 < n && src[i + 1] in "\\`*_{}[]()#+-.!|~<>" -> {
                    plain.append(src[i + 1]); i += 2
                }
                c == '`' -> {
                    var ticks = 0
                    while (i + ticks < n && src[i + ticks] == '`') ticks++
                    val close = src.indexOf("`".repeat(ticks), i + ticks)
                    if (close > 0) {
                        flushPlain()
                        val start = length
                        append(src.substring(i + ticks, close).trim())
                        addStyle(CodeSpan, start, length)
                        i = close + ticks
                    } else {
                        plain.append(c); i++
                    }
                }
                c == '!' && i + 1 < n && src[i + 1] == '[' -> {
                    val closeB = src.indexOf(']', i + 2)
                    if (closeB > 0 && closeB + 1 < n && src[closeB + 1] == '(') {
                        val closeP = src.indexOf(')', closeB + 2)
                        if (closeP > 0) {
                            flushPlain()
                            val alt = src.substring(i + 2, closeB).ifBlank { "image" }
                            val start = length
                            append("🖼 $alt")
                            addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                            i = closeP + 1
                            continue
                        }
                    }
                    plain.append(c); i++
                }
                c == '[' -> {
                    val closeB = findClosing(src, i, '[', ']')
                    if (closeB > 0 && closeB + 1 < n && src[closeB + 1] == '(') {
                        val closeP = findClosing(src, closeB + 1, '(', ')')
                        if (closeP > 0) {
                            flushPlain()
                            val label = inline(src.substring(i + 1, closeB))
                            val url = src.substring(closeB + 2, closeP).trim().substringBefore(' ').trim('<', '>')
                            val start = length
                            append(label)
                            if (url.startsWith("http") || url.startsWith("mailto:")) {
                                addLink(LinkAnnotation.Url(url, LinkStyles), start, length)
                            } else {
                                addStyle(SpanStyle(color = LinkColor), start, length)
                            }
                            i = closeP + 1
                            continue
                        }
                    }
                    plain.append(c); i++
                }
                c == '<' && src.startsWith("<http", i) -> {
                    val close = src.indexOf('>', i)
                    if (close > 0) {
                        flushPlain()
                        val url = src.substring(i + 1, close)
                        val start = length
                        append(url)
                        addLink(LinkAnnotation.Url(url, LinkStyles), start, length)
                        i = close + 1
                    } else {
                        plain.append(c); i++
                    }
                }
                (c == '*' || c == '_' || c == '~') -> {
                    var run = 0
                    while (i + run < n && src[i + run] == c) run++
                    val r = minOf(run, if (c == '~') 2 else 3)
                    val marker = c.toString().repeat(r)
                    // Intraword underscores are literal (snake_case).
                    val intraword = c == '_' && i > 0 && src[i - 1].isLetterOrDigit()
                    val close = if (intraword || (c == '~' && r < 2)) -1 else src.indexOf(marker, i + r)
                    if (close > i + r && !src[i + r].isWhitespace()) {
                        flushPlain()
                        val inner = inline(src.substring(i + r, close))
                        val start = length
                        append(inner)
                        val style = when {
                            c == '~' -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                            r == 3 -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                            r == 2 -> SpanStyle(fontWeight = FontWeight.Bold)
                            else -> SpanStyle(fontStyle = FontStyle.Italic)
                        }
                        addStyle(style, start, length)
                        i = close + r
                    } else {
                        plain.append(src, i, i + run); i += run
                    }
                }
                c == '<' -> {
                    // Inline HTML: drop tags like <br>, <sub>, keep text.
                    val close = src.indexOf('>', i)
                    if (close > 0 && close - i < 40 && src.getOrNull(i + 1)?.let { it.isLetter() || it == '/' } == true) {
                        val tag = src.substring(i + 1, close).trim('/', ' ').lowercase()
                        if (tag == "br") plain.append('\n')
                        i = close + 1
                    } else {
                        plain.append(c); i++
                    }
                }
                c == '&' -> {
                    val semi = src.indexOf(';', i)
                    if (semi in (i + 2)..(i + 10)) {
                        plain.append(decodeEntities(src.substring(i, semi + 1))); i = semi + 1
                    } else {
                        plain.append(c); i++
                    }
                }
                else -> {
                    plain.append(c); i++
                }
            }
        }
        flushPlain()
    }

    private fun findClosing(s: String, open: Int, o: Char, c: Char): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                o -> depth++
                c -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }
}
