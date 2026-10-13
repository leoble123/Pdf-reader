package app.lumen.reader.table

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object CsvWriter {

    fun write(sheet: Sheet, delimiter: Char, out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        for (row in sheet.rows) {
            row.forEachIndexed { i, cell ->
                if (i > 0) w.write(delimiter.code)
                w.write(quote(cell, delimiter))
            }
            w.write("\n")
        }
        w.flush()
    }

    fun quote(cell: String, delimiter: Char): String {
        val needs = cell.any { it == delimiter || it == '"' || it == '\n' || it == '\r' } ||
            cell.startsWith(' ') || cell.endsWith(' ')
        return if (needs) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
    }
}

/**
 * Saves cell edits into an .xlsx without rebuilding it: only the touched `<c>` elements in the
 * touched sheets are rewritten, so styles, formulas elsewhere, charts and everything else survive.
 */
object XlsxWriter {

    fun write(source: File, book: Workbook, out: OutputStream) {
        val patched = HashMap<String, String>()
        var droppedFormula = false
        ZipFile(source).use { zip ->
            for (sheet in book.sheets) {
                val path = sheet.path ?: continue
                if (sheet.edits.isEmpty()) continue
                val xml = zip.getInputStream(zip.getEntry(path)).use { it.readBytes().toString(Charsets.UTF_8) }
                val (newXml, dropped) = patchSheet(xml, sheet.edits)
                patched[path] = newXml
                droppedFormula = droppedFormula || dropped
            }
            // A calcChain that points at a cell which no longer has a formula makes Excel "repair" the file.
            val dropCalcChain = droppedFormula && zip.getEntry("xl/calcChain.xml") != null
            ZipOutputStream(out).use { zos ->
                for (e in zip.entries()) {
                    if (dropCalcChain && e.name == "xl/calcChain.xml") continue
                    val bytes: ByteArray = when {
                        patched.containsKey(e.name) -> patched.getValue(e.name).toByteArray(Charsets.UTF_8)
                        dropCalcChain && e.name == "[Content_Types].xml" ->
                            zip.text(e).replace(Regex("<Override[^>]*calcChain[^>]*/>"), "").toByteArray(Charsets.UTF_8)
                        dropCalcChain && e.name == "xl/_rels/workbook.xml.rels" ->
                            zip.text(e).replace(Regex("<Relationship[^>]*calcChain[^>]*/>"), "").toByteArray(Charsets.UTF_8)
                        else -> zip.getInputStream(e).use { it.readBytes() }
                    }
                    zos.putNextEntry(ZipEntry(e.name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
    }

    private fun ZipFile.text(e: ZipEntry) = getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }

    fun columnName(col: Int): String {
        var n = col + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.append('A' + r)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** Returns the patched sheet XML and whether any formula cell was overwritten. */
    fun patchSheet(xml: String, edits: Map<Pair<Int, Int>, String>): Pair<String, Boolean> {
        var doc = xml
        var droppedFormula = false
        val byRow = edits.entries.groupBy({ it.key.first + 1 }, { it.key.second to it.value }).toSortedMap()
        for ((r, cells) in byRow) {
            // Locate (or create) the row.
            var rowMatch = Regex("<row\\b[^>]*\\br=\"$r\"[^>]*?(/?)>").find(doc)
            if (rowMatch == null) {
                val after = Regex("<row\\b[^>]*\\br=\"(\\d+)\"").findAll(doc).firstOrNull { it.groupValues[1].toInt() > r }
                val insertAt = after?.range?.first ?: run {
                    if (doc.contains("<sheetData/>")) {
                        doc = doc.replace("<sheetData/>", "<sheetData></sheetData>")
                    }
                    doc.indexOf("</sheetData>")
                }
                doc = doc.substring(0, insertAt) + "<row r=\"$r\"></row>" + doc.substring(insertAt)
                rowMatch = Regex("<row\\b[^>]*\\br=\"$r\"[^>]*?(/?)>").find(doc)!!
            }
            if (rowMatch.groupValues[1] == "/") {
                val open = rowMatch.value.removeSuffix("/>").trimEnd() + ">"
                doc = doc.replaceRange(rowMatch.range, "$open</row>")
                rowMatch = Regex("<row\\b[^>]*\\br=\"$r\"[^>]*?>").find(doc)!!
            }
            val bodyStart = rowMatch.range.last + 1
            val bodyEnd = doc.indexOf("</row>", bodyStart)
            var body = doc.substring(bodyStart, bodyEnd)
            for ((col, value) in cells.sortedBy { it.first }) {
                val ref = columnName(col) + r
                val cellRegex = Regex("<c\\b[^>]*\\br=\"$ref\"[^>]*?(?:/>|>.*?</c>)", RegexOption.DOT_MATCHES_ALL)
                val existing = cellRegex.find(body)
                val style = existing?.value?.let { Regex("\\bs=\"(\\d+)\"").find(it.substringBefore('>'))?.groupValues?.get(1) }
                val cell = cellXml(ref, value, style)
                if (existing != null) {
                    if (existing.value.contains("<f")) droppedFormula = true
                    body = body.replaceRange(existing.range, cell)
                } else {
                    val next = Regex("<c\\b[^>]*\\br=\"([A-Z]+)\\d+\"").findAll(body)
                        .firstOrNull { Xlsx.columnIndex(it.groupValues[1]) > col }
                    body = if (next != null) body.substring(0, next.range.first) + cell + body.substring(next.range.first)
                    else body + cell
                }
            }
            doc = doc.substring(0, bodyStart) + body + doc.substring(bodyEnd)
        }
        return doc to droppedFormula
    }

    private val number = Regex("^-?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?$")

    private fun cellXml(ref: String, value: String, style: String?): String {
        val s = style?.let { " s=\"$it\"" } ?: ""
        return when {
            value.isEmpty() -> "<c r=\"$ref\"$s/>"
            number.matches(value) -> "<c r=\"$ref\"$s><v>$value</v></c>"
            value.equals("TRUE", true) || value.equals("FALSE", true) ->
                "<c r=\"$ref\"$s t=\"b\"><v>${if (value.equals("TRUE", true)) 1 else 0}</v></c>"
            else -> "<c r=\"$ref\"$s t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(value)}</t></is></c>"
        }
    }

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
