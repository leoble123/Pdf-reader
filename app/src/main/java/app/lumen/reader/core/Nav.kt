package app.lumen.reader.core

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
sealed interface Route {
    val key: String

    /** [siblings] lets viewers page through neighbouring files (e.g. swiping photos in a folder). */
    data class Doc(val ref: DocRef, val siblings: List<DocRef> = emptyList()) : Route {
        override val key get() = "doc:" + ref.key
    }

    data class PhotoEdit(val ref: DocRef?) : Route {
        override val key get() = "photo:" + ref?.key
    }

    data class VideoEdit(val refs: List<DocRef>) : Route {
        override val key get() = "video:" + refs.joinToString { it.key }
    }

    data object Scanner : Route {
        override val key get() = "scanner"
    }

    /** Page organiser. Null opens a picker; several refs are merged in order. */
    data class PdfTools(val refs: List<DocRef>) : Route {
        override val key get() = "pdftools:" + refs.joinToString { it.key }
    }

    data class Folder(val tree: android.net.Uri, val documentId: String, val name: String) : Route {
        override val key get() = "folder:$tree#$documentId"
    }

    data class MediaTool(val tool: Tool, val ref: DocRef?) : Route {
        enum class Tool(val label: String) { Compress("Compress video"), ExtractAudio("Extract audio") }
        override val key get() = "tool:${tool.name}:${ref?.key}"
    }

    data object Settings : Route {
        override val key get() = "settings"
    }
}

/** Push a new screen from anywhere (e.g. "Edit" in the image viewer). */
val LocalNavigator = staticCompositionLocalOf<(Route) -> Unit> { {} }
