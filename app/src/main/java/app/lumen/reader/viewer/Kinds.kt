package app.lumen.reader.viewer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Polyline
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.lumen.reader.core.FileKind

fun kindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.Pdf -> Icons.Rounded.PictureAsPdf
    FileKind.Image -> Icons.Rounded.Image
    FileKind.Svg -> Icons.Rounded.Polyline
    FileKind.Video -> Icons.Rounded.Movie
    FileKind.Audio -> Icons.Rounded.Audiotrack
    FileKind.Markdown -> Icons.AutoMirrored.Rounded.Article
    FileKind.Code -> Icons.Rounded.Code
    FileKind.Text -> Icons.AutoMirrored.Rounded.TextSnippet
    FileKind.Csv, FileKind.Xlsx -> Icons.Rounded.TableChart
    FileKind.Html -> Icons.Rounded.Language
    FileKind.Docx, FileKind.Odt -> Icons.Rounded.Description
    FileKind.Pptx -> Icons.Rounded.Slideshow
    FileKind.Epub -> Icons.AutoMirrored.Rounded.MenuBook
    FileKind.Zip -> Icons.Rounded.FolderZip
    FileKind.Unknown -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

fun kindAccent(kind: FileKind): Color = when (kind) {
    FileKind.Pdf -> Color(0xFFFF5A5F)
    FileKind.Image, FileKind.Svg -> Color(0xFF34C77B)
    FileKind.Video -> Color(0xFFFF8A3D)
    FileKind.Audio -> Color(0xFFFF4FA3)
    FileKind.Markdown, FileKind.Text -> Color(0xFF9AA3B8)
    FileKind.Code -> Color(0xFF5AC8FA)
    FileKind.Csv, FileKind.Xlsx -> Color(0xFF30C060)
    FileKind.Html -> Color(0xFF64A0FF)
    FileKind.Docx, FileKind.Odt -> Color(0xFF4A86FF)
    FileKind.Pptx -> Color(0xFFFF7A45)
    FileKind.Epub -> Color(0xFFB07CFF)
    FileKind.Zip -> Color(0xFFE0B040)
    FileKind.Unknown -> Color(0xFF8E93A6)
}
