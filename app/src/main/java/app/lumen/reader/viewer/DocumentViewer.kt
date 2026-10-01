package app.lumen.reader.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.lumen.reader.archive.ZipScreen
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.FileKind
import app.lumen.reader.core.Recents
import app.lumen.reader.image.ImageScreen
import app.lumen.reader.media.MediaScreen
import app.lumen.reader.pdf.PdfScreen
import app.lumen.reader.rich.RichDocScreen
import app.lumen.reader.table.TableScreen
import app.lumen.reader.text.TextScreen

/** Routes a document to the viewer best suited for it. */
@Composable
fun DocumentViewer(ref: DocRef, recents: Recents, onBack: () -> Unit, onOpenNested: (DocRef) -> Unit) {
    // "Open as…" from the fallback screen swaps the kind in place.
    var override by remember(ref) { mutableStateOf<DocRef?>(null) }
    val doc = override ?: ref
    key(doc.kind) {
        when (doc.kind) {
            FileKind.Pdf -> PdfScreen(doc, recents, onBack)
            FileKind.Image -> ImageScreen(doc, onBack)
            FileKind.Video, FileKind.Audio -> MediaScreen(doc, onBack)
            FileKind.Text, FileKind.Code -> TextScreen(doc, onBack)
            FileKind.Markdown, FileKind.Docx, FileKind.Pptx, FileKind.Odt, FileKind.Epub -> RichDocScreen(doc, recents, onBack)
            FileKind.Csv, FileKind.Xlsx -> TableScreen(doc, onBack)
            FileKind.Html, FileKind.Svg -> WebScreen(doc, onBack)
            FileKind.Zip -> ZipScreen(doc, onBack, onOpenNested)
            FileKind.Unknown -> UnsupportedScreen(doc, onBack) { override = it }
        }
    }
}
