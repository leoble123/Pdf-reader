package app.lumen.reader

import androidx.test.core.app.ApplicationProvider
import app.lumen.reader.core.OneEuroFilter
import app.lumen.reader.notebook.Notebooks
import app.lumen.reader.notebook.Paper
import app.lumen.reader.pdf.InkController
import app.lumen.reader.pdf.engine.PdfReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotebookTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun createsGrowsRenamesDeletes() = runBlocking {
        val nb = Notebooks.create(context, Paper.Lined, pages = 2)
        assertEquals(2, nb.pages)
        val ref = nb.ref()
        assertTrue(Notebooks.isNotebook(context, ref))
        assertEquals(2, PdfReader(nb.file.readBytes()).pages.size)

        // Ink survives adding pages: it lives beside the paper, keyed by page index.
        val ink = InkController.forDoc(context, ref)
        ink.begin(1, 0.2f, 0.2f, 0.6f, 0.7f); ink.extend(0.4f, 0.3f, 0.8f); ink.end()
        val grown = Notebooks.addPage(context, ref)!!
        assertEquals(3, grown.pages)
        assertEquals(3, PdfReader(grown.file.readBytes()).pages.size)
        assertEquals(1, InkController.forDoc(context, ref).pages[1]!!.size)
        assertTrue(InkController.forDoc(context, ref) === ink)

        Notebooks.rename(grown, "  Physics  ")
        val listed = Notebooks.list(context).single { it.file == nb.file }
        assertEquals("Physics", listed.name)
        assertEquals(Paper.Lined, listed.paper)

        File("build/pdf-out").mkdirs()
        nb.file.copyTo(File("build/pdf-out/notebook.pdf"), overwrite = true)
        InkController.flush()
        Notebooks.delete(context, listed)
        assertFalse(nb.file.exists())
        assertFalse(InkController.fileFor(context, ref).exists())
    }

    @Test
    fun everyPaperIsAValidPdf() {
        for (p in Paper.entries) {
            val f = File.createTempFile("paper", ".pdf")
            Notebooks.write(f, p, 1, p.label)
            val r = PdfReader(f.readBytes())
            assertEquals(1, r.pages.size)
            assertFalse(r.isRebuilt)
            f.delete()
        }
        // A PDF without notebook metadata is not a notebook.
        org.junit.Assert.assertNull(Notebooks.info(File.createTempFile("plain", ".pdf")))
    }

    @Test
    fun oneEuroSmoothsJitterButKeepsUpWithFastStrokes() {
        // Slow pen with ±1.5 px noise: the filtered path wobbles far less.
        val f = OneEuroFilter()
        val rnd = java.util.Random(3)
        var rawDev = 0f; var outDev = 0f
        for (i in 0 until 240) {
            val t = i * 4L
            val trueY = 100f
            val y = trueY + (rnd.nextFloat() - 0.5f) * 3f
            val o = f.filter(10f + i * 0.2f, y, t)
            if (i > 20) { rawDev += abs(y - trueY); outDev += abs(o.y - trueY) }
        }
        assertTrue("filtered $outDev vs raw $rawDev", outDev < rawDev * 0.5f)

        // Fast straight stroke (2000 px/s): lag stays within a few pixels.
        val g = OneEuroFilter()
        var last = androidx.compose.ui.geometry.Offset.Zero
        for (i in 0 until 60) last = g.filter(i * 8f, 0f, i * 4L)
        assertTrue("lag ${59 * 8f - last.x}", 59 * 8f - last.x < 12f)
    }
}
