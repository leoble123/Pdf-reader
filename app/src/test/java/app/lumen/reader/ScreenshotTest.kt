package app.lumen.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.lumen.reader.pdf.drawLive
import app.lumen.reader.pdf.drawMarks
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.FileKind
import app.lumen.reader.core.Recents
import app.lumen.reader.ui.home.HomeScreen
import app.lumen.reader.ui.theme.LumenTheme
import app.lumen.reader.viewer.DocumentViewer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders key screens on the JVM (Robolectric native graphics) and writes PNGs to
 * app/build/screenshots so the UI can be eyeballed without a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val out = File("build/screenshots").apply { mkdirs() }

    private fun fixture(name: String): DocRef {
        val f = File(javaClass.classLoader!!.getResource("fixtures/$name")!!.toURI())
        val kind = FileKind.detect(name, null)
        return DocRef(Uri.fromFile(f), name, null, f.length(), kind)
    }

    private val pickers = app.lumen.reader.core.Pickers({ _, _ -> null }, { _, _ -> emptyList() })

    private fun shoot(name: String, dark: Boolean = true, settleMs: Long = 2500, studio: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            LumenTheme(dark = dark || studio, amoled = studio) {
                androidx.compose.runtime.CompositionLocalProvider(
                    app.lumen.reader.core.LocalPickers provides pickers,
                    app.lumen.reader.core.LocalSettings provides app.lumen.reader.core.Settings(ApplicationProvider.getApplicationContext())
                ) { content() }
            }
        }
        var waited = 0L
        while (waited < settleMs) {
            Thread.sleep(100)
            compose.mainClock.advanceTimeBy(100)
            waited += 100
        }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun viewer(name: String, dark: Boolean = true) {
        val ref = fixture(name)
        val recents = Recents(ApplicationProvider.getApplicationContext())
        shoot("viewer_" + name.replace('.', '_') + if (dark) "" else "_light", dark) {
            DocumentViewer(ref, recents, onBack = {}, onOpenNested = {})
        }
    }

    @Test fun homeEmpty() {
        val recents = Recents(ApplicationProvider.getApplicationContext())
        recents.clear()
        shoot("home_empty") { HomeScreen(recents, app.lumen.reader.core.Folders(ApplicationProvider.getApplicationContext()), {}, {}, {}, {}) }
    }

    @Test fun homeRecents() {
        val recents = Recents(ApplicationProvider.getApplicationContext())
        listOf("sample.pdf", "sample.docx", "sample.xlsx", "sample.epub", "sample.md", "sample.pptx").forEach { recents.record(fixture(it)) }
        recents.togglePin(fixture("sample.pdf"))
        shoot("home_recents") { HomeScreen(recents, app.lumen.reader.core.Folders(ApplicationProvider.getApplicationContext()), {}, {}, {}, {}) }
    }

    @Test fun homeLight() {
        val recents = Recents(ApplicationProvider.getApplicationContext())
        listOf("sample.docx", "sample.md", "sample.csv").forEach { recents.record(fixture(it)) }
        shoot("home_light", dark = false) { HomeScreen(recents, app.lumen.reader.core.Folders(ApplicationProvider.getApplicationContext()), {}, {}, {}, {}) }
    }

    @Test fun settings() = shoot("settings", dark = false) { app.lumen.reader.ui.settings.SettingsScreen {} }
    @Test fun markdown() = viewer("sample.md")
    @Test fun markdownLight() = viewer("sample.md", dark = false)
    @Test fun docx() = viewer("sample.docx")
    @Test fun pptx() = viewer("sample.pptx")
    @Test fun epub() = viewer("sample.epub", dark = false)
    @Test fun xlsx() = viewer("sample.xlsx")
    @Test fun csv() = viewer("sample.csv", dark = false)
    @Test fun code() {
        val src = File("src/main/java/app/lumen/reader/pdf/PdfViewerState.kt")
        val ref = DocRef(Uri.fromFile(src.absoluteFile), "PdfViewerState.kt", null, src.length(), FileKind.Code)
        val recents = Recents(ApplicationProvider.getApplicationContext())
        shoot("viewer_code") { DocumentViewer(ref, recents, {}, {}) }
    }
    // PDF isn't screenshot here: Robolectric has no native PdfRenderer (PDFium) backend.
    @Test fun unknown() {
        val f = File(javaClass.classLoader!!.getResource("fixtures/sample.odt")!!.toURI())
        val ref = DocRef(Uri.fromFile(f), "mystery.bin", null, f.length(), FileKind.Unknown)
        val recents = Recents(ApplicationProvider.getApplicationContext())
        shoot("viewer_unknown") { DocumentViewer(ref, recents, {}, {}) }
    }

    @Test fun textEditor() {
        val ref = fixture("sample.md").copy(kind = FileKind.Markdown)
        shoot("edit_markdown") { app.lumen.reader.text.TextScreen(ref, onBack = {}, startEditing = true) }
    }

    @Test fun codeEditor() {
        val src = File("src/main/java/app/lumen/reader/edit/video/VideoModel.kt")
        val ref = DocRef(Uri.fromFile(src.absoluteFile), "VideoModel.kt", null, src.length(), FileKind.Code)
        shoot("edit_code") { app.lumen.reader.text.TextScreen(ref, onBack = {}, startEditing = true) }
    }

    @Test fun docxEditor() {
        val ref = fixture("sample.docx")
        val doc = app.lumen.reader.rich.Archive(File(ref.uri.path!!)).let { app.lumen.reader.rich.DocxParser.parse(it) }
        shoot("edit_docx") { app.lumen.reader.rich.DocxEditScreen(ref, doc) {} }
    }

    @Test fun photoEditor() {
        shoot("edit_photo", settleMs = 4000, studio = true) { app.lumen.reader.edit.photo.PhotoEditorScreen(fixture("sample.jpg")) {} }
    }

    @Test fun photoEditorDraw() {
        shoot("edit_photo_draw", settleMs = 4000, studio = true) { app.lumen.reader.edit.photo.PhotoEditorScreen(fixture("sample.jpg"), drawFirst = true) {} }
    }

    /** A page mock-up with every kind of mark, drawn through the same layers as the PDF viewer. */
    private fun markup(name: String, tool: app.lumen.reader.pdf.InkTool, select: Boolean) {
        val ink = app.lumen.reader.pdf.InkController(File.createTempFile("ink", ".json").apply { delete() })
        val wave = FloatArray(60) { i -> if (i % 2 == 0) 0.08f + i / 2 * 0.012f else 0.1f + 0.03f * kotlin.math.sin(i / 2 * 0.4f) }
        val pressure = FloatArray(30) { 0.3f + 1.0f * kotlin.math.sin(it / 29f * Math.PI.toFloat()) }
        ink.pages[0] = listOf(
            app.lumen.reader.pdf.Mark.Ink(wave, 0xFF1F6FEB.toInt(), 0.006f, false, pressure),
            app.lumen.reader.pdf.Mark.Ink(floatArrayOf(0.08f, 0.31f, 0.7f, 0.31f), 0xFFFFD60A.toInt(), 0.022f, true),
            app.lumen.reader.pdf.Mark.Ink(app.lumen.reader.core.PenMath.rect(0.55f, 0.45f, 0.9f, 0.6f), 0xFFE5484D.toInt(), 0.004f, false),
            app.lumen.reader.pdf.Mark.Ink(app.lumen.reader.core.PenMath.ellipse(0.3f, 0.52f, 0.18f, 0.06f), 0xFF30A46C.toInt(), 0.004f, false),
            app.lumen.reader.pdf.Mark.Note(0.1f, 0.7f, "Check this figure", 0xFF8E4EC6.toInt(), 0.04f)
        )
        ink.selectTool(tool)
        if (select) {
            ink.begin(0, 0.5f, 0.42f, null, 0.75f)
            listOf(0.95f to 0.42f, 0.95f to 0.63f, 0.5f to 0.63f, 0.5f to 0.43f).forEach { (x, y) -> ink.extend(x, y, null) }
            ink.end()
        }
        shoot(name, dark = false) {
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer)
            ) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .aspectRatio(0.75f)
                        .background(androidx.compose.ui.graphics.Color.White)
                        .drawBehind {
                            for (i in 0 until 14) drawRect(
                                androidx.compose.ui.graphics.Color(0xFF444444),
                                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.08f, size.height * (0.29f + i * 0.05f)),
                                size = androidx.compose.ui.geometry.Size(size.width * (0.84f - (i % 3) * 0.1f), size.height * 0.012f)
                            )
                        }
                ) {
                    val marks = ink.pages[0]!!
                    val skip = ink.selection?.indices
                    androidx.compose.foundation.layout.Spacer(
                        androidx.compose.ui.Modifier.matchParentSize()
                            .graphicsLayer { blendMode = androidx.compose.ui.graphics.BlendMode.Multiply }
                            .drawBehind { drawMarks(marks, null, highlights = true, skip = skip) }
                    )
                    androidx.compose.foundation.layout.Spacer(
                        androidx.compose.ui.Modifier.matchParentSize().graphicsLayer { }
                            .drawBehind { drawMarks(marks, measurer, highlights = false, skip = skip) }
                    )
                    androidx.compose.foundation.layout.Spacer(
                        androidx.compose.ui.Modifier.matchParentSize().drawBehind { drawLive(ink, 0, measurer, accent) }
                    )
                }
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 24.dp)
                ) { app.lumen.reader.pdf.InkToolbar(ink) }
            }
        }
    }

    @Test fun markupPen() = markup("markup_pen", app.lumen.reader.pdf.InkTool.Pen, select = false)
    @Test fun markupLasso() = markup("markup_lasso", app.lumen.reader.pdf.InkTool.Lasso, select = true)
    @Test fun markupEraser() = markup("markup_eraser", app.lumen.reader.pdf.InkTool.Eraser, select = false)

    @Test fun videoEditor() {
        val img = fixture("sample.jpg")
        shoot("edit_video", settleMs = 4000, studio = true) { app.lumen.reader.edit.video.VideoEditorScreen(listOf(img, img)) {} }
    }

    @Test fun scannerEmpty() = shoot("scanner", dark = false) { app.lumen.reader.scan.ScannerScreen({}, {}) }

    @Test fun pdfTools() = shoot("pdf_tools", dark = false) {
        app.lumen.reader.pdf.tools.PdfToolsScreen(listOf(fixture("sample.pdf"), fixture("sample.jpg")), {}, {})
    }

    @Test fun mediaTool() {
        val clip = fixture("sample.jpg").copy(name = "holiday.mp4", size = 48_000_000)
        shoot("media_tool", dark = true) {
            app.lumen.reader.edit.video.MediaToolScreen(app.lumen.reader.core.Route.MediaTool(app.lumen.reader.core.Route.MediaTool.Tool.Compress, clip)) {}
        }
    }

    @Test fun homeDark() {
        val recents = Recents(ApplicationProvider.getApplicationContext())
        listOf("sample.pdf", "sample.docx", "sample.xlsx", "sample.epub").forEach { recents.record(fixture(it)) }
        shoot("home_dark") { HomeScreen(recents, app.lumen.reader.core.Folders(ApplicationProvider.getApplicationContext()), {}, {}, {}, {}) }
    }
}
