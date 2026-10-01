package app.lumen.reader.core

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

@Immutable
data class DocRef(
    val uri: Uri,
    val name: String,
    val mime: String?,
    val size: Long,
    val kind: FileKind
) {
    val key: String get() = uri.toString()
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

object Docs {

    suspend fun resolve(context: Context, uri: Uri, mimeHint: String? = null): DocRef =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var name: String? = null
            var size = -1L
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                        ?.use { c ->
                            if (c.moveToFirst()) {
                                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val si = c.getColumnIndex(OpenableColumns.SIZE)
                                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                            }
                        }
                }
            } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
                uri.path?.let { File(it) }?.let {
                    name = it.name
                    size = it.length()
                }
            }
            val finalName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled"
            val mime = runCatching { resolver.getType(uri) }.getOrNull()?.takeIf { it != "application/octet-stream" }
                ?: mimeHint
            var kind = FileKind.detect(finalName, mime)
            if (kind == FileKind.Unknown || kind == FileKind.Zip && finalName.indexOf('.') < 0) {
                val head = runCatching { resolver.openInputStream(uri)?.use { it.readHead(4096) } }.getOrNull()
                if (head != null) kind = FileKind.sniff(head) ?: kind
            }
            DocRef(uri, finalName, mime, size, kind)
        }

    fun open(context: Context, ref: DocRef): InputStream =
        context.contentResolver.openInputStream(ref.uri) ?: error("Unable to open ${ref.name}")

    /**
     * Zip-based formats need random access, so they're mirrored into the cache once.
     * The mirror is keyed by uri + size so re-opening is instant.
     */
    suspend fun localFile(context: Context, ref: DocRef): File = withContext(Dispatchers.IO) {
        if (ref.uri.scheme == ContentResolver.SCHEME_FILE) {
            val f = File(ref.uri.path!!)
            if (f.canRead()) return@withContext f
        }
        val dir = File(context.cacheDir, "mirror").apply { mkdirs() }
        val target = File(dir, hash("${ref.uri}#${ref.size}") + "." + ref.extension.ifEmpty { "bin" })
        if (!target.exists() || (ref.size > 0 && target.length() != ref.size)) {
            val tmp = File(dir, target.name + ".part")
            open(context, ref).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            tmp.renameTo(target)
        }
        trimMirror(dir, keep = target)
        target
    }

    /** Peeks inside a zip to recognise office files and ebooks that arrived without a name. */
    fun refineZipKind(file: File): FileKind = runCatching {
        ZipFile(file).use { zip ->
            when {
                zip.getEntry("word/document.xml") != null -> FileKind.Docx
                zip.getEntry("xl/workbook.xml") != null -> FileKind.Xlsx
                zip.getEntry("ppt/presentation.xml") != null -> FileKind.Pptx
                zip.getEntry("META-INF/container.xml") != null -> FileKind.Epub
                zip.getEntry("content.xml") != null -> FileKind.Odt
                else -> FileKind.Zip
            }
        }
    }.getOrDefault(FileKind.Zip)

    fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    private fun trimMirror(dir: File, keep: File, maxBytes: Long = 512L shl 20) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > maxBytes && f != keep) f.delete()
        }
    }

    fun shareIntent(ref: DocRef): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = ref.mime ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, ref.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun openWithIntent(ref: DocRef): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(ref.uri, ref.mime ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024; i++
    }
    return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}

fun InputStream.readHead(max: Int): ByteArray {
    val buf = ByteArray(max)
    var n = 0
    while (n < max) {
        val r = read(buf, n, max - n)
        if (r < 0) break
        n += r
    }
    return if (n == max) buf else buf.copyOf(n)
}
