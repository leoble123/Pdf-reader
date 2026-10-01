package app.lumen.reader

import app.lumen.reader.core.FileKind
import app.lumen.reader.rich.Archive
import app.lumen.reader.rich.Block
import app.lumen.reader.rich.DocxParser
import app.lumen.reader.rich.EpubParser
import app.lumen.reader.rich.Markdown
import app.lumen.reader.rich.OdtParser
import app.lumen.reader.rich.PptxParser
import app.lumen.reader.table.Csv
import app.lumen.reader.table.Xlsx
import app.lumen.reader.text.Highlighter
import app.lumen.reader.text.Lang
import app.lumen.reader.text.TextLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ParsersTest {

    private fun fixture(name: String) = File(javaClass.classLoader!!.getResource("fixtures/$name")!!.toURI())

    private fun List<Block>.texts(): List<String> = flatMap { b ->
        when (b) {
            is Block.Heading -> listOf("H${b.level}:" + b.text.text)
            is Block.Paragraph -> listOf("P:" + b.text.text)
            is Block.ListItem -> listOf("L${b.depth}${b.marker}" + b.text.text)
            is Block.Quote -> listOf("Q:" + b.text.text)
            is Block.Code -> listOf("C:" + b.text)
            is Block.Table -> listOf("T:" + b.rows.joinToString("|") { r -> r.joinToString(",") { it.text } })
            is Block.Image -> listOf("IMG:" + b.key)
            is Block.Slide -> listOf("S${b.index}:" + b.title?.text) + b.body.texts()
            is Block.Section -> listOf("SEC:" + b.title)
            Block.Rule -> listOf("---")
        }
    }

    @Test
    fun docx() {
        Archive(fixture("sample.docx")).use { zip ->
            val doc = DocxParser.parse(zip)
            val t = doc.blocks.texts()
            println(t.joinToString("\n"))
            assertEquals("Quarterly Report", doc.title)
            assertTrue(t.contains("H1:Quarterly Report"))
            assertTrue(t.contains("H1:Overview"))
            assertTrue(t.contains("P:Revenue grew 23% while costs stayed flat."))
            assertTrue(t.contains("L0•First bullet"))
            assertTrue(t.contains("L01.Step one"))
            assertTrue(t.contains("L02.Step two"))
            assertTrue(t.any { it.startsWith("T:Region,Q1,Q2|North,120,150") })
            assertTrue(t.any { it.startsWith("IMG:word/media/") })
            assertTrue(t.contains("Q:Closing thoughts."))
            val img = doc.blocks.filterIsInstance<Block.Image>().first()
            val bmp = kotlinx.coroutines.runBlocking { img.load(400) }
            assertEquals(240, bmp!!.width)
        }
    }

    @Test
    fun xlsx() {
        Archive(fixture("sample.xlsx")).use { zip ->
            val book = Xlsx.parse(zip)
            assertEquals(listOf("Sales", "Notes"), book.sheets.map { it.name })
            val sales = book.sheets[0]
            assertEquals(listOf("Item", "Units", "Price", "Date"), sales.rows[0].toList())
            assertEquals(listOf("Widget", "12", "3.5", "2026-03-14"), sales.rows[1].toList())
            assertEquals(4, sales.columns)
            val notes = book.sheets[1]
            assertEquals("Hello", notes.rows[0][0])
            assertEquals("TRUE", notes.rows[2][2])
        }
    }

    @Test
    fun pptx() {
        Archive(fixture("sample.pptx")).use { zip ->
            val doc = PptxParser.parse(zip)
            val t = doc.blocks.texts()
            println(t.joinToString("\n"))
            assertEquals(3, doc.blocks.size)
            assertTrue(t.contains("S1:Lumen Launch"))
            assertTrue(t.contains("P:Offline. Gorgeous. Fast."))
            assertTrue(t.contains("L0•Liquid glass UI"))
            assertTrue(t.contains("L1◦Zero lag zoom"))
            assertTrue(t.any { it.startsWith("IMG:ppt/media/") })
        }
    }

    @Test
    fun epub() {
        Archive(fixture("sample.epub")).use { zip ->
            val doc = EpubParser.parse(zip)
            val t = doc.blocks.texts()
            println(t.joinToString("\n"))
            assertEquals("The Glass Garden", doc.title)
            assertTrue(t.contains("H1:Morning"))
            assertTrue(t.contains("P:The light came in sideways, & everything glowed."))
            assertTrue(t.contains("IMG:../images/fx.png"))
            assertTrue(t.contains("L0•Moss"))
            assertTrue(t.contains("SEC:Evening"))
            assertTrue(t.contains("P:By dusk the glass held the warmth.\nThen night."))
            assertTrue(t.contains("Q:Quiet."))
            assertEquals(listOf("Morning", "Evening"), doc.toc.map { it.first })
        }
    }

    @Test
    fun odt() {
        Archive(fixture("sample.odt")).use { zip ->
            val t = OdtParser.parse(zip).blocks.texts()
            assertEquals(listOf("H1:Open Doc", "P:Plain and bold text.", "L0•Item A"), t)
        }
    }

    @Test
    fun markdown() {
        val doc = Markdown.parse(fixture("sample.md").readText())
        val t = doc.blocks.texts()
        println(t.joinToString("\n"))
        assertEquals("H1:Lumen notes", t[0])
        assertTrue(t[1], t[1] == "P:Some bold, some italic, code, gone and a link.")
        assertTrue(t.contains("L0•one"))
        assertTrue(t.contains("L1•nested"))
        assertTrue(t.contains("L01.first"))
        assertTrue(t.contains("L0☑done"))
        assertTrue(t.contains("L0☐todo"))
        assertTrue(t.contains("Q:quoted wisdom"))
        assertTrue(t.contains("C:fun main() = println(\"hi\")"))
        assertTrue(t.contains("T:Name,Value|a,1|b,2"))
        assertTrue(t.contains("---"))
        assertTrue(t.last().contains("snake_case_word"))
    }

    @Test
    fun csv() {
        val book = Csv.parse(fixture("sample.csv").readText())
        val rows = book.sheets[0].rows.map { it.toList() }
        assertEquals(listOf("name", "qty", "note"), rows[0])
        assertEquals(listOf("Smith, J", "3", "said \"hi\""), rows[1])
        assertEquals(3, rows.size)
        val semi = Csv.parse("a;b;c\n1;2;3\n").sheets[0].rows
        assertEquals(listOf("1", "2", "3"), semi[1].toList())
    }

    @Test
    fun textDecoding() {
        assertEquals("héllo", TextLoader.decode("héllo".toByteArray(Charsets.UTF_8)).first)
        assertEquals("héllo", TextLoader.decode(byteArrayOf(0x68, 0xE9.toByte(), 0x6C, 0x6C, 0x6F)).first)
        assertEquals(listOf("a", "b", "", "c"), TextLoader.splitLines("a\r\nb\n\rc".replace("\n\r", "\n\n")))
        // A truncated multi-byte character at the end must not demote the whole file to Windows-1252.
        val cut = "naïve ✓".toByteArray().let { it.copyOf(it.size - 1) }
        assertEquals("UTF-8", TextLoader.decode(cut).second)
    }

    @Test
    fun highlighting() {
        val line = "val x = \"hi\" // note"
        val hl = Highlighter.highlight(line, Lang.CLike, app.lumen.reader.text.DarkCode, false)
        assertEquals(line, hl.text)
        assertTrue(hl.spanStyles.any { line.substring(it.start, it.end) == "val" })
        assertTrue(hl.spanStyles.any { line.substring(it.start, it.end) == "\"hi\"" })
        assertTrue(hl.spanStyles.any { line.substring(it.start, it.end) == "// note" })
        val starts = Highlighter.blockCommentStarts(listOf("a /* b", "c", "d */ e", "f"), Lang.CLike)
        assertEquals(listOf(false, true, true, false), starts.toList())
    }

    @Test
    fun kinds() {
        assertEquals(FileKind.Pdf, FileKind.detect("a.PDF", null))
        assertEquals(FileKind.Code, FileKind.detect("main.ts", null))
        assertEquals(FileKind.Docx, FileKind.detect("x", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(FileKind.Pdf, FileKind.sniff("%PDF-1.7".toByteArray()))
        assertEquals(FileKind.Docx, app.lumen.reader.core.Docs.refineZipKind(fixture("sample.docx")))
        assertEquals(FileKind.Epub, app.lumen.reader.core.Docs.refineZipKind(fixture("sample.epub")))
    }
}
