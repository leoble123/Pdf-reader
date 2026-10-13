package app.lumen.reader.core

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
sealed interface Route {
    val key: String

    data class Doc(val ref: DocRef) : Route {
        override val key get() = "doc:" + ref.key
    }

    data class PhotoEdit(val ref: DocRef?) : Route {
        override val key get() = "photo:" + ref?.key
    }

    data class VideoEdit(val refs: List<DocRef>) : Route {
        override val key get() = "video:" + refs.joinToString { it.key }
    }
}

/** Push a new screen from anywhere (e.g. "Edit" in the image viewer). */
val LocalNavigator = staticCompositionLocalOf<(Route) -> Unit> { {} }
