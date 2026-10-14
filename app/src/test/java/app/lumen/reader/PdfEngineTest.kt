package app.lumen.reader

import app.lumen.reader.pdf.Mark
import app.lumen.reader.pdf.engine.PdfAnnotator
import app.lumen.reader.pdf.engine.PdfAssembler
import app.lumen.reader.pdf.engine.PdfName
import app.lumen.reader.pdf.engine.PdfNum
import app.lumen.reader.pdf.engine.PdfReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Plain JVM tests for the PDF engine. Outputs land in build/pdf-out for external validation. */
class PdfEngineTest {

    private fun bytes(name: String) = File(javaClass.classLoader!!.getResource("fixtures/$name")!!.toURI()).readBytes()
    private val out = File("build/pdf-out").apply { mkdirs() }

    @Test
    fun readsEveryVariant() {
        for (name in listOf("sample.pdf", "objstm.pdf", "rotated.pdf", "broken.pdf")) {
            val r = PdfReader(bytes(name))
            assertEquals(name, 12, r.pages.size)
            r.pages.forEach { p -> assertTrue(name, (p.dict["MediaBox"] ?: p.inherited["MediaBox"]) != null) }
        }
        assertTrue(PdfReader(bytes("broken.pdf")).isRebuilt)
        assertEquals(90, (PdfReader(bytes("rotated.pdf")).pages[1].dict["Rotate"] as PdfNum).int)
    }

    @Test
    fun mergesReordersRotates() {
        val a = PdfReader(bytes("sample.pdf"))
        val b = PdfReader(bytes("objstm.pdf"))
        val asm = PdfAssembler()
        asm.addPage(a, a.pages[2])
        asm.addPage(b, b.pages[0], extraRotation = 90)
        asm.addPage(a, a.pages[11])
        asm.addPage(b, b.pages[5], extraRotation = 270)
        val file = File(out, "merged.pdf")
        file.outputStream().use { asm.write(it, "Merged") }

        val r = PdfReader(file.readBytes())
        assertEquals(4, r.pages.size)
        assertEquals(90, (r.pages[1].dict["Rotate"] as PdfNum).int)
        assertEquals(270, (r.pages[3].dict["Rotate"] as PdfNum).int)
        // Fonts came along with the pages.
        val res = r.dict(r.pages[0].dict["Resources"])!!
        assertTrue(r.dict(res["Font"]) != null)
        // Output is much smaller than both inputs combined: only referenced objects are copied.
        assertTrue(file.length() < bytes("sample.pdf").size)
    }

    @Test
    fun annotatesIncrementally() {
        val src = bytes("rotated.pdf")
        val reader = PdfReader(src)
        val marks = mapOf(
            0 to listOf(
                Mark.Ink(floatArrayOf(0.1f, 0.1f, 0.5f, 0.2f, 0.9f, 0.1f), 0xFFE5484D.toInt(), 0.006f, false),
                Mark.Ink(floatArrayOf(0.1f, 0.35f, 0.8f, 0.35f), 0xFFFFD60A.toInt(), 0.02f, true),
                Mark.Note(0.15f, 0.5f, "Hello from Lumen", 0xFF1F6FEB.toInt(), 0.04f)
            ),
            1 to listOf(Mark.Ink(floatArrayOf(0.05f, 0.05f, 0.95f, 0.95f), 0xFF30A46C.toInt(), 0.01f, false)),
            2 to listOf(Mark.Note(0.5f, 0.5f, "Cropped page", 0xFF8E4EC6.toInt(), 0.05f))
        )
        val file = File(out, "annotated.pdf")
        file.outputStream().use { PdfAnnotator.save(reader, marks, it) }
        val bytes = file.readBytes()
        // Original bytes are untouched at the start of the file.
        assertTrue(bytes.copyOf(src.size).contentEquals(src))
        val r = PdfReader(bytes)
        assertEquals(12, r.pages.size)
        val annots = r.pages.take(3).map { p -> (r.resolve(p.dict["Annots"]) as app.lumen.reader.pdf.engine.PdfArray).size }
        assertEquals(listOf(3, 1, 1), annots)
        val first = r.dict((r.resolve(r.pages[0].dict["Annots"]) as app.lumen.reader.pdf.engine.PdfArray)[0])!!
        assertEquals("Ink", (first["Subtype"] as PdfName).name)
        // The outline (bookmarks) from the original is still reachable.
        assertTrue(r.dict(r.dict(r.trailer["Root"])!!["Outlines"]) != null)
    }

    @Test
    fun annotatesDamagedFileByRewriting() {
        val reader = PdfReader(bytes("broken.pdf"))
        val file = File(out, "annotated-broken.pdf")
        file.outputStream().use {
            PdfAnnotator.save(reader, mapOf(0 to listOf(Mark.Ink(floatArrayOf(0.2f, 0.2f, 0.8f, 0.8f), 0xFF000000.toInt(), 0.01f, false))), it)
        }
        val r = PdfReader(file.readBytes())
        assertEquals(12, r.pages.size)
        assertTrue(!r.isRebuilt)
    }

    @Test
    fun buildsImagePdf() {
        val jpeg = bytes("sample.jpg")
        val asm = PdfAssembler()
        asm.addJpegPage(jpeg, 1200, 800, 612f, 408f)
        asm.addJpegPage(jpeg, 1200, 800, 595f, 397f)
        val file = File(out, "images.pdf")
        file.outputStream().use { asm.write(it) }
        val r = PdfReader(file.readBytes())
        assertEquals(2, r.pages.size)
        // Embedded as JPEG, not re-encoded: file is about two copies of the image.
        assertTrue(file.length() in (jpeg.size * 2).toLong()..(jpeg.size * 2 + 4000).toLong())
    }
}
