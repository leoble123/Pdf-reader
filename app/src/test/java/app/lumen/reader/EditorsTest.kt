package app.lumen.reader

import app.lumen.reader.rich.Archive
import app.lumen.reader.table.Csv
import app.lumen.reader.table.CsvWriter
import app.lumen.reader.table.Xlsx
import app.lumen.reader.table.XlsxWriter
import app.lumen.reader.text.TextLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorsTest {

    private fun fixture(name: String) = File(javaClass.classLoader!!.getResource("fixtures/$name")!!.toURI())

    @Test
    fun csvRoundTrip() {
        val book = Csv.parse(fixture("sample.csv").readText())
        val sheet = book.sheets[0]
        sheet.set(2, 2, "now, with \"quotes\"")
        sheet.addRow()
        sheet.set(3, 0, "New")
        val out = ByteArrayOutputStream().also { CsvWriter.write(sheet, book.delimiter, it) }.toString("UTF-8")
        val again = Csv.parse(out).sheets[0].rows.map { it.toList() }
        assertEquals(listOf("Smith, J", "3", "said \"hi\""), again[1])
        assertEquals(listOf("Lee", "10", "now, with \"quotes\""), again[2])
        assertEquals("New", again[3][0])
        assertEquals(4, again.size)
    }

    @Test
    fun xlsxPatchRoundTrip() {
        val src = fixture("sample.xlsx")
        val book = Archive(src).use { Xlsx.parse(it) }
        val sales = book.sheets[0]
        sales.set(1, 0, "Widget <Pro> & co")   // replace a shared string cell
        sales.set(2, 1, "9")                    // replace a number
        sales.set(3, 1, "42")                   // overwrite the SUM formula cell
        sales.set(9, 5, "far away")             // brand new row + column
        sales.set(0, 6, "Notes")                // new cell in an existing row
        val out = File.createTempFile("patched", ".xlsx")
        out.outputStream().use { XlsxWriter.write(src, book, it) }

        val again = Archive(out).use { Xlsx.parse(it) }
        val rows = again.sheets[0].rows
        assertEquals("Widget <Pro> & co", rows[1][0])
        assertEquals("9", rows[2][1])
        assertEquals("42", rows[3][1])
        assertEquals("far away", rows[9][5])
        assertEquals("Notes", rows[0][6])
        // Untouched cells, dates and the other sheet survive.
        assertEquals("2026-03-14", rows[1][3])
        assertEquals("12.25", rows[2][2])
        assertEquals("Hello", again.sheets[1].rows[0][0])
        ZipFile(out).use { z ->
            assertTrue(z.getEntry("xl/styles.xml") != null)
            val sheetXml = z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).readBytes().toString(Charsets.UTF_8)
            assertFalse("formula should be replaced", sheetXml.contains("SUM("))
            // rows stay in ascending order
            val order = Regex("<row\\b[^>]*\\br=\"(\\d+)\"").findAll(sheetXml).map { it.groupValues[1].toInt() }.toList()
            assertEquals(order.sorted(), order)
        }
    }

    @Test
    fun docxEditRoundTrip() {
        val src = fixture("sample.docx")
        val doc = Archive(src).use { app.lumen.reader.rich.DocxParser.parse(it) }
        fun ordinalOf(text: String) = doc.sourceMap.getValue(doc.blocks.indexOfFirst {
            (it as? app.lumen.reader.rich.Block.Paragraph)?.text?.text == text ||
                (it as? app.lumen.reader.rich.Block.ListItem)?.text?.text == text ||
                (it as? app.lumen.reader.rich.Block.Heading)?.text?.text == text
        })
        val session = app.lumen.reader.rich.DocxEditSession(src)
        val revenue = ordinalOf("Revenue grew 23% while costs stayed flat.")
        assertTrue(session.canEdit(revenue))
        session.replacements[revenue] = "Revenue grew <a lot> & more\nSecond line"
        session.deletions += ordinalOf("Second bullet")
        session.inserts[ordinalOf("Overview")] = mutableListOf("Inserted intro")
        val out = File.createTempFile("edited", ".docx")
        out.outputStream().use { session.write(it) }

        val again = Archive(out).use { app.lumen.reader.rich.DocxParser.parse(it) }
        val texts = again.blocks.map {
            when (it) {
                is app.lumen.reader.rich.Block.Paragraph -> "P:" + it.text.text
                is app.lumen.reader.rich.Block.ListItem -> "L:" + it.text.text
                is app.lumen.reader.rich.Block.Heading -> "H:" + it.text.text
                else -> it::class.simpleName
            }
        }
        println(texts.joinToString("\n"))
        assertTrue(texts.contains("P:Revenue grew <a lot> & more\nSecond line"))
        assertFalse(texts.contains("L:Second bullet"))
        assertTrue(texts.contains("L:First bullet"))
        // Inserted paragraph copies the heading's style, so it lands right after "Overview".
        assertEquals(texts.indexOf("H:Overview") + 1, texts.indexOfFirst { it!!.endsWith("Inserted intro") })
        // Untouched content (table, image, quote) survives.
        assertTrue(texts.contains("Table"))
        assertTrue(texts.contains("Image"))
        assertTrue(texts.contains("Quote"))
    }

    @Test
    fun textEncodingPreserved() {
        val text = "héllo\nworld"
        val crlf = TextLoader.encode(text, "UTF-8", crlf = true)
        assertEquals("héllo\r\nworld", String(crlf, Charsets.UTF_8))
        val bom = TextLoader.encode(text, "UTF-8 BOM", crlf = false)
        assertEquals(TextLoader.decode(bom), text to "UTF-8 BOM")
        val utf16 = TextLoader.encode(text, "UTF-16LE", crlf = false)
        assertEquals(text, TextLoader.decode(utf16).first)
    }
}
