package app.lumen.reader.rich

import androidx.compose.ui.text.AnnotatedString
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParser.END_DOCUMENT
import org.xmlpull.v1.XmlPullParser.END_TAG
import org.xmlpull.v1.XmlPullParser.START_TAG
import org.xmlpull.v1.XmlPullParser.TEXT

private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

private fun onOff(p: XmlPullParser): Boolean = p.attr("w:val")?.let { it != "0" && it != "false" && it != "none" } ?: true

// ---------------------------------------------------------------- DOCX

object DocxParser {

    fun parse(zip: Archive): RichDoc {
        val rels = zip.rels("word/_rels/document.xml.rels", "word")
        val styleNumbering = HashMap<String, Pair<String, Int>>()
        val headingStyles = headingStyles(zip, styleNumbering)
        val numbering = numbering(zip)
        val counters = HashMap<String, Int>()
        val blocks = ArrayList<Block>()
        val toc = ArrayList<Pair<String, Int>>()
        val p = zip.parser("word/document.xml") ?: error("Not a Word document")

        var para: StyledText? = null
        var style: String? = null
        var numId: String? = null
        var ilvl = 0
        var align = Align.Start
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var inRunProps = false
        var inParaProps = false
        val pendingImages = ArrayList<Block.Image>()
        var tableDepth = 0
        var rows: ArrayList<List<AnnotatedString>>? = null
        var row: ArrayList<AnnotatedString>? = null
        var cell: StyledText? = null
        var inText = false
        var pOrdinal = -1
        var paraOrd = -1
        val sourceMap = HashMap<Int, Int>()

        fun current(): StyledText = cell ?: para ?: StyledText().also { para = it }

        while (p.next() != END_DOCUMENT) {
            when (p.eventType) {
                START_TAG -> when (p.name) {
                    "w:tbl" -> {
                        tableDepth++
                        if (tableDepth == 1) rows = ArrayList()
                    }
                    "w:tr" -> if (tableDepth == 1) row = ArrayList()
                    "w:tc" -> if (tableDepth == 1) cell = StyledText()
                    "w:p" -> {
                        pOrdinal++
                        if (cell == null) {
                            para = StyledText(); paraOrd = pOrdinal
                        }
                        else cell.let { if (!it.isBlank) it.lineBreak() }
                        style = null; numId = null; ilvl = 0; align = Align.Start
                    }
                    "w:pPr" -> inParaProps = true
                    "w:pStyle" -> style = p.attr("w:val")
                    "w:numId" -> numId = p.attr("w:val")
                    "w:ilvl" -> ilvl = p.attr("w:val")?.toIntOrNull() ?: 0
                    "w:jc" -> if (inParaProps && !inRunProps) align = when (p.attr("w:val")) {
                        "center" -> Align.Center
                        "right", "end" -> Align.End
                        "both", "distribute" -> Align.Justify
                        else -> Align.Start
                    }
                    "w:r" -> {
                        bold = false; italic = false; underline = false; strike = false
                    }
                    "w:rPr" -> inRunProps = true
                    "w:b" -> if (inRunProps) bold = onOff(p)
                    "w:i" -> if (inRunProps) italic = onOff(p)
                    "w:u" -> if (inRunProps) underline = onOff(p)
                    "w:strike", "w:dstrike" -> if (inRunProps) strike = onOff(p)
                    "w:t" -> {
                        inText = true
                        current().setStyle(bold, italic, underline, strike)
                    }
                    "w:tab" -> if (!inParaProps) current().appendRaw("    ")
                    "w:br", "w:cr" -> if (p.attr("w:type") != "page") current().lineBreak()
                    "w:hyperlink" -> current().setLink(p.attr("r:id")?.let { rels[it]?.first })
                    "a:blip" -> {
                        val id = p.attr("r:embed") ?: p.attr("r:link")
                        val target = id?.let { rels[it] }
                        if (target != null && !target.second) {
                            zip.imageLoader(target.first)?.let { pendingImages += Block.Image(target.first, null, it) }
                        }
                    }
                    "v:imagedata" -> {
                        val target = p.attr("r:id")?.let { rels[it] }
                        if (target != null) zip.imageLoader(target.first)?.let { pendingImages += Block.Image(target.first, null, it) }
                    }
                }
                TEXT -> if (inText) current().appendRaw(p.text)
                END_TAG -> when (p.name) {
                    "w:t" -> inText = false
                    "w:rPr" -> inRunProps = false
                    "w:pPr" -> inParaProps = false
                    "w:hyperlink" -> current().setLink(null)
                    "w:p" -> if (cell == null) {
                        val text = para?.build() ?: AnnotatedString("")
                        para = null
                        if (text.isNotBlank()) sourceMap[blocks.size] = paraOrd
                        val level = style?.let { headingStyles[it] }
                        if (numId == null) style?.let { styleNumbering[it] }?.let { (n, l) ->
                            numId = n; ilvl = l
                        }
                        when {
                            text.isBlank() -> Unit
                            level != null -> {
                                if (level <= 3) toc += text.text to blocks.size
                                blocks += Block.Heading(level, text)
                            }
                            numId != null && numId != "0" -> {
                                val fmt = numbering["$numId:$ilvl"]
                                val key = "$numId:$ilvl"
                                val n = (counters[key] ?: 0) + 1
                                counters[key] = n
                                // deeper levels restart when a shallower item appears
                                counters.keys.filter { it.startsWith("$numId:") && (it.substringAfter(':').toIntOrNull() ?: 0) > ilvl }
                                    .forEach { counters.remove(it) }
                                val marker = when (fmt) {
                                    null, "bullet" -> if (ilvl % 2 == 0) "•" else "◦"
                                    "lowerLetter" -> "${'a' + (n - 1) % 26}."
                                    "upperLetter" -> "${'A' + (n - 1) % 26}."
                                    "lowerRoman" -> roman(n).lowercase() + "."
                                    "upperRoman" -> roman(n) + "."
                                    else -> "$n."
                                }
                                blocks += Block.ListItem(ilvl.coerceAtMost(4), marker, text)
                            }
                            style?.contains("Quote", ignoreCase = true) == true -> blocks += Block.Quote(text)
                            else -> blocks += Block.Paragraph(text, align)
                        }
                        blocks += pendingImages
                        pendingImages.clear()
                    }
                    "w:tc" -> if (tableDepth == 1) {
                        row?.add(cell?.build() ?: AnnotatedString(""))
                        cell = null
                    }
                    "w:tr" -> if (tableDepth == 1) {
                        row?.let { rows?.add(it) }
                        row = null
                    }
                    "w:tbl" -> {
                        if (tableDepth == 1) {
                            rows?.takeIf { it.isNotEmpty() }?.let { blocks += Block.Table(it, header = true) }
                            rows = null
                            blocks += pendingImages
                            pendingImages.clear()
                        }
                        tableDepth--
                    }
                }
            }
        }
        val title = zip.parser("docProps/core.xml")?.let { cp ->
            var t: String? = null
            var inTitle = false
            while (cp.next() != END_DOCUMENT) {
                if (cp.eventType == START_TAG && cp.name == "dc:title") inTitle = true
                else if (cp.eventType == TEXT && inTitle) t = cp.text
                else if (cp.eventType == END_TAG) inTitle = false
            }
            t?.takeIf { it.isNotBlank() }
        }
        return RichDoc(blocks, title, toc, "Word document", zip, sourceMap)
    }

    private fun headingStyles(zip: Archive, numbered: MutableMap<String, Pair<String, Int>>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (i in 1..6) out["Heading$i"] = i
        out["Title"] = 1
        out["Subtitle"] = 2
        val p = zip.parser("word/styles.xml") ?: return out
        var id: String? = null
        var styleIlvl = 0
        while (p.next() != END_DOCUMENT) {
            if (p.eventType != START_TAG) continue
            when (p.name) {
                "w:style" -> {
                    id = p.attr("w:styleId"); styleIlvl = 0
                }
                "w:ilvl" -> styleIlvl = p.attr("w:val")?.toIntOrNull() ?: 0
                "w:numId" -> id?.let { sid -> p.attr("w:val")?.let { numbered[sid] = it to styleIlvl } }
                "w:name" -> {
                    val name = p.attr("w:val")?.lowercase() ?: continue
                    val sid = id ?: continue
                    when {
                        name == "title" -> out[sid] = 1
                        name == "subtitle" -> out[sid] = 2
                        name.startsWith("heading ") -> name.removePrefix("heading ").trim().toIntOrNull()?.let { out[sid] = it.coerceIn(1, 6) }
                    }
                }
            }
        }
        return out
    }

    /** "numId:ilvl" → numFmt */
    private fun numbering(zip: Archive): Map<String, String> {
        val p = zip.parser("word/numbering.xml") ?: return emptyMap()
        val abstract = HashMap<String, HashMap<Int, String>>()
        val nums = HashMap<String, String>()
        var absId: String? = null
        var lvl = 0
        var numId: String? = null
        while (p.next() != END_DOCUMENT) {
            if (p.eventType != START_TAG) continue
            when (p.name) {
                "w:abstractNum" -> absId = p.attr("w:abstractNumId")
                "w:lvl" -> lvl = p.attr("w:ilvl")?.toIntOrNull() ?: 0
                "w:numFmt" -> absId?.let { abstract.getOrPut(it) { HashMap() }[lvl] = p.attr("w:val") ?: "decimal" }
                "w:num" -> {
                    numId = p.attr("w:numId"); absId = null
                }
                "w:abstractNumId" -> numId?.let { nums[it] = p.attr("w:val") ?: "" }
            }
        }
        val out = HashMap<String, String>()
        nums.forEach { (n, a) -> abstract[a]?.forEach { (l, f) -> out["$n:$l"] = f } }
        return out
    }

    private fun roman(n: Int): String {
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n
        val sb = StringBuilder()
        for (i in v.indices) while (x >= v[i]) {
            sb.append(s[i]); x -= v[i]
        }
        return sb.toString()
    }
}

// ---------------------------------------------------------------- PPTX

object PptxParser {

    fun parse(zip: Archive): RichDoc {
        val presRels = zip.rels("ppt/_rels/presentation.xml.rels", "ppt")
        val order = ArrayList<String>()
        zip.parser("ppt/presentation.xml")?.let { p ->
            while (p.next() != END_DOCUMENT) {
                if (p.eventType == START_TAG && p.name == "p:sldId") {
                    p.attr("r:id")?.let { presRels[it]?.first }?.let { order += it }
                }
            }
        }
        if (order.isEmpty()) {
            var i = 1
            while (zip.exists("ppt/slides/slide$i.xml")) order += "ppt/slides/slide${i++}.xml"
        }
        val blocks = ArrayList<Block>()
        val toc = ArrayList<Pair<String, Int>>()
        order.forEachIndexed { index, path ->
            val slide = parseSlide(zip, path, index + 1)
            toc += ("${index + 1}. " + (slide.title?.text?.take(60) ?: "Slide ${index + 1}")) to blocks.size
            blocks += slide
        }
        return RichDoc(blocks, null, toc, "Presentation", zip)
    }

    private fun parseSlide(zip: Archive, path: String, index: Int): Block.Slide {
        val dir = path.substringBeforeLast('/')
        val rels = zip.rels("$dir/_rels/${path.substringAfterLast('/')}.rels", dir)
        val p = zip.parser(path) ?: return Block.Slide(index, null, emptyList())
        val body = ArrayList<Block>()
        var title: AnnotatedString? = null
        var phType: String? = null
        var para: StyledText? = null
        var lvl = 0
        var bullet = false
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var inRPr = false
        var inText = false
        var rows: ArrayList<List<AnnotatedString>>? = null
        var row: ArrayList<AnnotatedString>? = null
        var cell: StyledText? = null

        while (p.next() != END_DOCUMENT) {
            when (p.eventType) {
                START_TAG -> when (p.name) {
                    "p:sp" -> phType = null
                    "p:ph" -> phType = p.attr("type") ?: "body"
                    "a:tbl" -> rows = ArrayList()
                    "a:tr" -> row = ArrayList()
                    "a:tc" -> cell = StyledText()
                    "a:p" -> {
                        if (cell != null) {
                            cell.let { if (!it.isBlank) it.lineBreak() }
                        } else para = StyledText()
                        lvl = 0; bullet = phType == "body" || phType == "obj"
                    }
                    "a:pPr" -> {
                        lvl = p.attr("lvl")?.toIntOrNull() ?: 0
                    }
                    "a:buNone" -> bullet = false
                    "a:buChar", "a:buAutoNum" -> bullet = true
                    "a:r" -> {
                        bold = false; italic = false; underline = false; strike = false
                    }
                    "a:rPr" -> {
                        inRPr = true
                        bold = p.attr("b") == "1"
                        italic = p.attr("i") == "1"
                        underline = p.attr("u")?.let { it != "none" } ?: false
                        strike = p.attr("strike")?.let { it != "noStrike" } ?: false
                    }
                    "a:hlinkClick" -> if (inRPr) {
                        (cell ?: para)?.setLink(p.attr("r:id")?.let { rels[it]?.first })
                    }
                    "a:t" -> {
                        inText = true
                        (cell ?: para)?.setStyle(bold, italic, underline, strike)
                    }
                    "a:br" -> (cell ?: para)?.lineBreak()
                    "a:blip" -> {
                        val target = p.attr("r:embed")?.let { rels[it] }
                        if (target != null && !target.second) zip.imageLoader(target.first)?.let {
                            body += Block.Image(target.first, null, it)
                        }
                    }
                }
                TEXT -> if (inText) (cell ?: para)?.appendRaw(p.text)
                END_TAG -> when (p.name) {
                    "a:t" -> inText = false
                    "a:rPr" -> inRPr = false
                    "a:r" -> (cell ?: para)?.setLink(null)
                    "a:p" -> if (cell == null) {
                        val text = para?.build()
                        para = null
                        if (text != null && text.isNotBlank()) {
                            if ((phType == "title" || phType == "ctrTitle") && title == null) title = text
                            else if (bullet) body += Block.ListItem(lvl.coerceAtMost(4), if (lvl % 2 == 0) "•" else "◦", text)
                            else body += Block.Paragraph(text, if (phType == "subTitle") Align.Center else Align.Start)
                        }
                    }
                    "a:tc" -> {
                        row?.add(cell?.build() ?: AnnotatedString("")); cell = null
                    }
                    "a:tr" -> {
                        row?.let { rows?.add(it) }; row = null
                    }
                    "a:tbl" -> {
                        rows?.takeIf { it.isNotEmpty() }?.let { body += Block.Table(it, header = true) }
                        rows = null
                    }
                }
            }
        }
        return Block.Slide(index, title, body)
    }
}

// ---------------------------------------------------------------- ODT

object OdtParser {

    fun parse(zip: Archive): RichDoc {
        val p = zip.parser("content.xml") ?: error("Not an OpenDocument file")
        val styles = HashMap<String, BooleanArray>() // bold, italic, underline, strike
        val blocks = ArrayList<Block>()
        val toc = ArrayList<Pair<String, Int>>()
        var styleName: String? = null
        var text: StyledText? = null
        var headingLevel = 0
        var listDepth = 0
        var inItem = false
        val spanStack = ArrayList<BooleanArray?>()
        var rows: ArrayList<List<AnnotatedString>>? = null
        var row: ArrayList<AnnotatedString>? = null
        var cell: StyledText? = null

        fun applySpan(t: StyledText) {
            val s = spanStack.lastOrNull { it != null }
            t.setStyle(s?.get(0) == true, s?.get(1) == true, s?.get(2) == true, s?.get(3) == true)
        }

        while (p.next() != END_DOCUMENT) {
            when (p.eventType) {
                START_TAG -> when (p.name) {
                    "style:style" -> styleName = p.attr("style:name")
                    "style:text-properties" -> styleName?.let { n ->
                        styles[n] = booleanArrayOf(
                            p.attr("fo:font-weight") == "bold",
                            p.attr("fo:font-style") == "italic",
                            p.attr("style:text-underline-style")?.let { it != "none" } ?: false,
                            p.attr("style:text-line-through-style")?.let { it != "none" } ?: false
                        )
                    }
                    "text:list" -> listDepth++
                    "text:list-item" -> inItem = true
                    "table:table" -> rows = ArrayList()
                    "table:table-row" -> row = ArrayList()
                    "table:table-cell" -> cell = StyledText()
                    "text:h", "text:p" -> {
                        if (cell != null) {
                            cell.let { if (!it.isBlank) it.lineBreak() }
                        } else {
                            text = StyledText()
                            headingLevel = if (p.name == "text:h") p.attr("text:outline-level")?.toIntOrNull() ?: 1 else 0
                        }
                        spanStack.clear()
                        spanStack += styles[p.attr("text:style-name")]
                    }
                    "text:span" -> spanStack += styles[p.attr("text:style-name")]
                    "text:a" -> (cell ?: text)?.setLink(p.attr("xlink:href"))
                    "text:line-break" -> (cell ?: text)?.lineBreak()
                    "text:tab" -> (cell ?: text)?.appendRaw("    ")
                    "text:s" -> (cell ?: text)?.appendRaw(" ".repeat(p.attr("text:c")?.toIntOrNull() ?: 1))
                    "draw:image" -> p.attr("xlink:href")?.let { href ->
                        zip.imageLoader(Archive.resolvePath("", href))?.let { blocks += Block.Image(href, null, it) }
                    }
                }
                TEXT -> (cell ?: text)?.let { t ->
                    applySpan(t); t.appendRaw(p.text)
                }
                END_TAG -> when (p.name) {
                    "style:style" -> styleName = null
                    "text:span" -> if (spanStack.isNotEmpty()) spanStack.removeAt(spanStack.lastIndex)
                    "text:a" -> (cell ?: text)?.setLink(null)
                    "text:list" -> listDepth = (listDepth - 1).coerceAtLeast(0)
                    "text:list-item" -> inItem = false
                    "text:h", "text:p" -> if (cell == null) {
                        val built = text?.build()
                        text = null
                        if (built != null && built.isNotBlank()) {
                            blocks += when {
                                headingLevel > 0 -> {
                                    if (headingLevel <= 3) toc += built.text to blocks.size
                                    Block.Heading(headingLevel.coerceIn(1, 6), built)
                                }
                                inItem && listDepth > 0 -> Block.ListItem((listDepth - 1).coerceAtMost(4), "•", built)
                                else -> Block.Paragraph(built)
                            }
                        }
                    }
                    "table:table-cell" -> {
                        row?.add(cell?.build() ?: AnnotatedString("")); cell = null
                    }
                    "table:table-row" -> {
                        row?.let { rows?.add(it) }; row = null
                    }
                    "table:table" -> {
                        rows?.takeIf { it.isNotEmpty() }?.let { blocks += Block.Table(it, header = false) }
                        rows = null
                    }
                }
            }
        }
        return RichDoc(blocks, null, toc, "OpenDocument", zip)
    }
}

// ---------------------------------------------------------------- EPUB

object EpubParser {

    fun parse(zip: Archive): RichDoc {
        val opfPath = zip.parser("META-INF/container.xml")?.let { p ->
            var path: String? = null
            while (p.next() != END_DOCUMENT && path == null) {
                if (p.eventType == START_TAG && p.name.endsWith("rootfile")) path = p.attr("full-path")
            }
            path
        } ?: error("Not a valid EPUB")
        val base = opfPath.substringBeforeLast('/', "")
        val manifest = HashMap<String, Pair<String, String>>() // id → (href, media-type)
        val spine = ArrayList<String>()
        var title: String? = null
        zip.parser(opfPath)?.let { p ->
            var inTitle = false
            while (p.next() != END_DOCUMENT) {
                when (p.eventType) {
                    START_TAG -> {
                        val name = p.name.substringAfter(':')
                        when (name) {
                            "item" -> manifest[p.attr("id") ?: ""] = (p.attr("href") ?: "") to (p.attr("media-type") ?: "")
                            "itemref" -> p.attr("idref")?.let { spine += it }
                            "title" -> inTitle = title == null
                        }
                    }
                    TEXT -> if (inTitle) title = p.text.trim()
                    END_TAG -> inTitle = false
                }
            }
        }
        val blocks = ArrayList<Block>()
        val toc = ArrayList<Pair<String, Int>>()
        var chapter = 0
        for (id in spine) {
            val (href, type) = manifest[id] ?: continue
            if (!type.contains("html") && !href.endsWith("html") && !href.endsWith("htm")) continue
            val path = Archive.resolvePath(base, href)
            val html = zip.text(path) ?: continue
            val dir = path.substringBeforeLast('/', "")
            val parser = HtmlBlocks { src ->
                if (src.startsWith("data:")) null else zip.imageLoader(Archive.resolvePath(dir, src))
            }
            val chapterBlocks = parser.parse(html)
            if (chapterBlocks.isEmpty()) continue
            chapter++
            val label = parser.headings.firstOrNull()?.first?.take(70) ?: "Chapter $chapter"
            if (chapter > 1) blocks += Block.Section(label, chapter)
            toc += label to blocks.size
            blocks += chapterBlocks
        }
        return RichDoc(blocks, title, toc, "eBook", zip)
    }
}
