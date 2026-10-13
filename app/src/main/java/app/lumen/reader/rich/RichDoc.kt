package app.lumen.reader.rich

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString

/** A format-neutral document model: every rich format is parsed into this and rendered by one view. */
@Immutable
sealed interface Block {
    data class Heading(val level: Int, val text: AnnotatedString) : Block
    data class Paragraph(val text: AnnotatedString, val align: Align = Align.Start) : Block
    data class ListItem(val depth: Int, val marker: String, val text: AnnotatedString) : Block
    data class Quote(val text: AnnotatedString) : Block
    data class Code(val text: String, val lang: String?) : Block
    data object Rule : Block
    class Image(val key: String, val alt: String?, val load: suspend (maxWidth: Int) -> Bitmap?) : Block
    data class Table(val rows: List<List<AnnotatedString>>, val header: Boolean) : Block
    data class Section(val title: String, val index: Int) : Block
    data class Slide(val index: Int, val title: AnnotatedString?, val body: List<Block>) : Block
}

enum class Align { Start, Center, End, Justify }

class RichDoc(
    val blocks: List<Block>,
    val title: String? = null,
    /** Table of contents: label → block index. */
    val toc: List<Pair<String, Int>> = emptyList(),
    val kindLabel: String,
    /** Keeps e.g. the backing zip open for lazy image loading; closed when the viewer leaves. */
    val resources: java.io.Closeable? = null,
    /** DOCX only: block index → ordinal of the `<w:p>` it came from, so edits can be written back. */
    val sourceMap: Map<Int, Int> = emptyMap()
)
