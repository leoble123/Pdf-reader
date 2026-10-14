package app.lumen.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
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
