package app.lumen.reader.table

import app.lumen.reader.rich.Archive
import org.xmlpull.v1.XmlPullParser
import java.util.Calendar
import java.util.TimeZone

class Sheet(val name: String, val rows: List<Array<String>>, val columns: Int)

class Workbook(val sheets: List<Sheet>, val truncated: Boolean)

object Csv {

    fun parse(text: String, maxRows: Int = 1_000_000): Workbook {
        val sample = text.take(20_000)
        val delimiter = listOf(',', ';', '\t', '|').maxBy { d -> countOutsideQuotes(sample, d) }
        val rows = ArrayList<Array<String>>()
        val row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        val n = text.length
        var cols = 0
        var truncated = false
        while (i < n) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text[i + 1] == '"') {
                        field.append('"'); i++
                    } else quoted = false
                } else field.append(c)
            } else when (c) {
                '"' -> if (field.isEmpty()) quoted = true else field.append(c)
                delimiter -> {
                    row += field.toString(); field.setLength(0)
                }
                '\r' -> Unit
                '\n' -> {
                    row += field.toString(); field.setLength(0)
                    cols = maxOf(cols, row.size)
                    rows += row.toTypedArray(); row.clear()
                    if (rows.size >= maxRows) {
                        truncated = true; break
                    }
                }
                else -> field.append(c)
            }
            i++
        }
        if (!truncated && (field.isNotEmpty() || row.isNotEmpty())) {
            row += field.toString()
            cols = maxOf(cols, row.size)
            rows += row.toTypedArray()
        }
        return Workbook(listOf(Sheet("Sheet", rows, cols)), truncated)
    }

    private fun countOutsideQuotes(s: String, d: Char): Int {
        var q = false
        var count = 0
        for (c in s) {
            if (c == '"') q = !q else if (c == d && !q) count++
        }
        return count
    }
}

object Xlsx {

    private val builtinDateFormats = (14..22).toSet() + (45..47).toSet()

    fun parse(zip: Archive, maxCells: Int = 4_000_000): Workbook {
        val rels = zip.rels("xl/_rels/workbook.xml.rels", "xl")
        val shared = sharedStrings(zip)
        val dateStyles = dateStyles(zip)
        val sheets = ArrayList<Sheet>()
        var truncated = false
        var cellBudget = maxCells
        zip.parser("xl/workbook.xml")?.let { p ->
            val list = ArrayList<Pair<String, String>>()
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name.substringAfter(':') == "sheet") {
                    val name = p.getAttributeValue(null, "name") ?: "Sheet"
                    val id = p.getAttributeValue(null, "r:id")
                    val path = id?.let { rels[it]?.first }
                    if (path != null) list += name to path
                }
            }
            for ((name, path) in list) {
                val (sheet, cut) = sheet(zip, name, path, shared, dateStyles, cellBudget)
                cellBudget -= sheet.rows.size * maxOf(1, sheet.columns)
                truncated = truncated || cut
                sheets += sheet
            }
        }
        return Workbook(sheets, truncated)
    }

    private fun sharedStrings(zip: Archive): List<String> {
        val p = zip.parser("xl/sharedStrings.xml") ?: return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inT = false
        var inRph = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> sb.setLength(0)
                    "t" -> inT = true
                    "rPh" -> inRph = true
                }
                XmlPullParser.TEXT -> if (inT && !inRph) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "rPh" -> inRph = false
                    "si" -> out += sb.toString()
                }
            }
        }
        return out
    }

    /** Indices into cellXfs whose number format is a date. */
    private fun dateStyles(zip: Archive): Set<Int> {
        val p = zip.parser("xl/styles.xml") ?: return emptySet()
        val customDate = HashSet<Int>()
        val out = HashSet<Int>()
        var inXfs = false
        var xfIndex = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                "numFmt" -> {
                    val id = p.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                    val code = p.getAttributeValue(null, "formatCode")?.lowercase()?.replace(Regex("\\[[^]]*]|\"[^\"]*\""), "")
                    if (id != null && code != null && (code.contains('y') || code.contains('d') || code.contains("mm") && code.contains('h'))) customDate += id
                }
                "cellXfs" -> inXfs = true
                "xf" -> if (inXfs) {
                    val fmt = p.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0
                    if (fmt in builtinDateFormats || fmt in customDate) out += xfIndex
                    xfIndex++
                }
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "cellXfs") inXfs = false
        }
        return out
    }

    private fun sheet(
        zip: Archive,
        name: String,
        path: String,
        shared: List<String>,
        dateStyles: Set<Int>,
        budget: Int
    ): Pair<Sheet, Boolean> {
        val p = zip.parser(path) ?: return Sheet(name, emptyList(), 0) to false
        val rows = ArrayList<Array<String>>()
        var current = HashMap<Int, String>()
        var rowIndex = -1
        var col = 0
        var type: String? = null
        var style = 0
        var inV = false
        var inIs = false
        val value = StringBuilder()
        var maxCol = 0
        var cells = 0
        var truncated = false

        fun commitRow() {
            if (rowIndex < 0) return
            while (rows.size < rowIndex) rows.add(emptyArray())
            val width = (current.keys.maxOrNull() ?: -1) + 1
            rows.add(Array(width) { current[it] ?: "" })
            maxCol = maxOf(maxCol, width)
            current = HashMap()
        }

        loop@ while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        commitRow()
                        rowIndex = (p.getAttributeValue(null, "r")?.toIntOrNull() ?: (rowIndex + 2)) - 1
                        col = 0
                        if (rowIndex > 1_048_576) break@loop
                    }
                    "c" -> {
                        col = p.getAttributeValue(null, "r")?.let(::columnIndex) ?: col
                        type = p.getAttributeValue(null, "t")
                        style = p.getAttributeValue(null, "s")?.toIntOrNull() ?: 0
                        value.setLength(0)
                    }
                    "v" -> inV = true
                    "is" -> inIs = true
                    "t" -> if (inIs) inV = true
                }
                XmlPullParser.TEXT -> if (inV) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inV = false
                    "is" -> inIs = false
                    "c" -> {
                        val raw = value.toString()
                        val shown = when (type) {
                            "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "b" -> if (raw == "1") "TRUE" else "FALSE"
                            "str", "inlineStr", "e" -> raw
                            else -> if (style in dateStyles) excelDate(raw) else prettyNumber(raw)
                        }
                        if (shown.isNotEmpty()) current[col] = shown
                        col++
                        if (++cells > budget) {
                            truncated = true; break@loop
                        }
                    }
                }
            }
        }
        commitRow()
        // Trim trailing empty rows
        while (rows.isNotEmpty() && rows.last().all { it.isEmpty() }) rows.removeAt(rows.lastIndex)
        return Sheet(name, rows, maxCol) to truncated
    }

    fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (!ch.isLetter()) break
            n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return n - 1
    }

    private fun prettyNumber(raw: String): String {
        val d = raw.toDoubleOrNull() ?: return raw
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
        val s = "%.10g".format(d).trimEnd('0').trimEnd('.')
        return if (s.contains('e')) raw else s
    }

    private fun excelDate(raw: String): String {
        val serial = raw.toDoubleOrNull() ?: return raw
        val days = serial.toLong()
        val millis = ((serial - days) * 86_400_000L).toLong()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(1899, Calendar.DECEMBER, 30)
            add(Calendar.DAY_OF_MONTH, days.toInt())
            add(Calendar.MILLISECOND, millis.toInt())
        }
        val date = "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        return if (millis > 0) date + " %02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)) else date
    }
}
