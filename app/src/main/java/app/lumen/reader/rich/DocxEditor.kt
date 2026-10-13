package app.lumen.reader.rich

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Paragraph-level editing for .docx. Paragraphs are addressed by their ordinal among all
 * `<w:p>` start tags (the same order the parser sees). Only edited paragraphs are rewritten;
 * every other byte of the package is copied through untouched.
 */
class DocxEditSession(private val source: File) {

    private val xml: String = ZipFile(source).use { z ->
        z.getInputStream(z.getEntry("word/document.xml")).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Start/end (exclusive) offsets of every paragraph, by ordinal. */
    private val ranges: List<IntRange> = paragraphRanges(xml)

    val replacements = HashMap<Int, String>()
    val deletions = HashSet<Int>()
    val inserts = HashMap<Int, MutableList<String>>()

    val isDirty get() = replacements.isNotEmpty() || deletions.isNotEmpty() || inserts.values.any { it.isNotEmpty() }

    /** Paragraphs holding images, text boxes, embedded objects or content controls are left alone. */
    fun canEdit(ordinal: Int): Boolean {
        val r = ranges.getOrNull(ordinal) ?: return false
        val seg = xml.substring(r.first, r.last + 1)
        val inner = seg.substring(1)
        return !Regex("<w:p(?=[\\s>/])").containsMatchIn(inner) &&
            listOf("<w:drawing", "<w:pict", "<w:object", "<w:sdt", "<mc:AlternateContent", "<w:fldChar").none { seg.contains(it) }
    }

    /** Deleting the only paragraph of a table cell or text box would corrupt the document. */
    fun canDelete(ordinal: Int): Boolean {
        val r = ranges.getOrNull(ordinal) ?: return false
        val before = xml.substring(0, r.first)
        fun depth(open: String, close: String) =
            Regex(Regex.escape(open) + "(?=[\\s>])").findAll(before).count() - Regex(Regex.escape(close)).findAll(before).count()
        return depth("<w:tc", "</w:tc>") <= 0 && depth("<w:txbxContent", "</w:txbxContent>") <= 0
    }

    fun write(out: OutputStream) {
        val sb = StringBuilder(xml)
        // Apply from the end so earlier offsets stay valid.
        val touched = (replacements.keys + deletions + inserts.keys).toSortedSet().reversed()
        for (ord in touched) {
            val r = ranges[ord]
            val original = xml.substring(r.first, r.last + 1)
            val added = inserts[ord].orEmpty().joinToString("") { rebuild(original, it, forInsert = true) }
            val self = when {
                ord in deletions -> ""
                ord in replacements -> rebuild(original, replacements.getValue(ord), forInsert = false)
                else -> original
            }
            sb.replace(r.first, r.last + 1, self + added)
        }
        ZipFile(source).use { zip ->
            ZipOutputStream(out).use { zos ->
                for (e in zip.entries()) {
                    zos.putNextEntry(ZipEntry(e.name))
                    if (e.name == "word/document.xml") zos.write(sb.toString().toByteArray(Charsets.UTF_8))
                    else zip.getInputStream(e).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    companion object {

        fun paragraphRanges(xml: String): List<IntRange> {
            val result = ArrayList<IntRange?>()
            val stack = ArrayList<Int>() // indices into result
            val token = Regex("<w:p(?=[\\s>/])[^>]*?(/?)>|</w:p>")
            for (m in token.findAll(xml)) {
                if (m.value == "</w:p>") {
                    val idx = stack.removeLastOrNull() ?: continue
                    result[idx] = result[idx]!!.first..m.range.last
                } else if (m.groupValues[1] == "/") {
                    result += m.range
                } else {
                    result += m.range.first..m.range.last
                    stack += result.lastIndex
                }
            }
            return result.map { it!! }
        }

        /** New paragraph XML: same paragraph properties and first-run formatting, new text. */
        fun rebuild(original: String, text: String, forInsert: Boolean): String {
            val open = Regex("^<w:p(?=[\\s>/])[^>]*?/?>").find(original)!!.value.removeSuffix("/>").removeSuffix(">") + ">"
            var pPr = Regex("<w:pPr>.*?</w:pPr>|<w:pPr/>", RegexOption.DOT_MATCHES_ALL).find(original)?.value ?: ""
            if (forInsert) pPr = pPr.replace(Regex("<w:sectPr\\b.*?</w:sectPr>", RegexOption.DOT_MATCHES_ALL), "")
            val afterPPr = if (pPr.isNotEmpty()) original.substringAfter(pPr, original) else original
            val rPr = Regex("<w:r[\\s>].*?(<w:rPr>.*?</w:rPr>)", RegexOption.DOT_MATCHES_ALL).find(afterPPr)?.groupValues?.get(1) ?: ""
            val runs = text.split('\n').mapIndexed { i, line ->
                (if (i > 0) "<w:r>$rPr<w:br/></w:r>" else "") +
                    "<w:r>$rPr<w:t xml:space=\"preserve\">${escape(line)}</w:t></w:r>"
            }.joinToString("")
            return "$open$pPr$runs</w:p>"
        }

        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
