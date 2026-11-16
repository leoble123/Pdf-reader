package app.lumen.reader.viewer.web

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.Docs
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale

/**
 * Serves an HTML file and the files beside it from a private, made-up origin
 * (`https://lumen.local/site/…`), so relative images, CSS, scripts, fetch() calls, other
 * pages and localStorage all work, while anything on the real internet stays unreachable.
 *
 * Siblings are reachable when the page came from somewhere Lumen can browse: a folder added
 * in Lumen (a document tree) or a plain file path. A page shared from another app on its own
 * still works; only its relative files are missing. MHTML archives carry their own resources.
 */
class LocalSite private constructor(
    val pageUrl: String,
    val html: String,
    private val siblings: Siblings?,
    private val archive: MhtmlArchive?
) {
    /** Document title from the archive, when the file is a saved web page. */
    val isArchive get() = archive != null

    /** A response for [url], or null when it isn't part of this site (the caller blocks it). */
    fun open(url: Uri): Resource? {
        archive?.find(url.toString())?.let { return it }
        if (url.host != HOST) return null
        val path = url.path ?: return null
        if (!path.startsWith(SITE_PREFIX)) return null
        val rel = path.removePrefix(SITE_PREFIX)
        if (rel.isEmpty() || rel.split('/').any { it == ".." }) return null
        val stream = siblings?.open(rel) ?: return null
        return Resource(mimeOf(rel), null, stream)
    }

    /** Reads another HTML page of the same site (for link navigation). */
    fun page(url: Uri): String? {
        val r = open(url) ?: return null
        if (r.mime != "text/html" && r.mime != "application/xhtml+xml") return null
        return r.stream.use { decodeText(it.readBytes(), null) }
    }

    class Resource(val mime: String, val charset: String?, val stream: InputStream)

    interface Siblings {
        fun open(rel: String): InputStream?
    }

    private class FileSiblings(private val dir: File) : Siblings {
        override fun open(rel: String): InputStream? {
            val f = File(dir, rel).canonicalFile
            // Never outside the page's own folder.
            if (!f.path.startsWith(dir.canonicalPath + File.separator) || !f.isFile) return null
            return f.inputStream()
        }
    }

    /** Document-tree folders: works for providers whose IDs are paths ("primary:Download/site/x.html"). */
    private class TreeSiblings(private val context: Context, private val tree: Uri, private val dirPrefix: String) : Siblings {
        override fun open(rel: String): InputStream? = runCatching {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, dirPrefix + rel)
            context.contentResolver.openInputStream(uri)
        }.getOrNull()
    }

    companion object {
        const val HOST = "lumen.local"
        const val ORIGIN = "https://$HOST"
        const val SITE_PREFIX = "/site/"

        fun build(context: Context, ref: DocRef, bytes: ByteArray): LocalSite {
            val name = Uri.encode(ref.name.ifBlank { "index.html" })
            val pageUrl = ORIGIN + SITE_PREFIX + name
            if (ref.extension == "mhtml" || ref.extension == "mht" || MhtmlArchive.looksLike(bytes)) {
                MhtmlArchive.parse(bytes)?.let { a ->
                    val base = a.mainUrl?.takeIf { it.startsWith("http") } ?: pageUrl
                    return LocalSite(base, a.mainHtml, null, a)
                }
            }
            return LocalSite(pageUrl, decodeText(bytes, null), siblingsOf(context, ref), null)
        }

        /** For tests and callers that already know the folder. */
        fun forFolder(dir: File, name: String, html: String) =
            LocalSite(ORIGIN + SITE_PREFIX + Uri.encode(name), html, FileSiblings(dir), null)

        private fun siblingsOf(context: Context, ref: DocRef): Siblings? {
            val uri = ref.uri
            if (uri.scheme == "file") return uri.path?.let { File(it).parentFile }?.let { FileSiblings(it) }
            if (uri.scheme != "content") return null
            return runCatching {
                if (!DocumentsContract.isTreeUri(uri)) return null
                val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
                val docId = DocumentsContract.getDocumentId(uri)
                val prefix = when {
                    '/' in docId -> docId.substringBeforeLast('/') + "/"
                    ':' in docId -> docId.substringBefore(':') + ":"
                    else -> return null
                }
                TreeSiblings(context, tree, prefix)
            }.getOrNull()
        }

        fun mimeOf(path: String): String {
            val ext = path.substringAfterLast('.', "").substringBefore('?').lowercase(Locale.ROOT)
            return when (ext) {
                "js", "mjs", "cjs" -> "text/javascript"
                "css" -> "text/css"
                "html", "htm" -> "text/html"
                "xhtml" -> "application/xhtml+xml"
                "json", "map" -> "application/json"
                "svg" -> "image/svg+xml"
                "wasm" -> "application/wasm"
                "woff2" -> "font/woff2"
                "woff" -> "font/woff"
                "ttf" -> "font/ttf"
                "otf" -> "font/otf"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            }
        }

        /** UTF-8 (with or without BOM) when it decodes cleanly, else the declared charset, else Windows-1252. */
        fun decodeText(bytes: ByteArray, declared: String?): String {
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            }
            runCatching {
                return Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            }
            val meta = declared ?: Regex("""charset\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE)
                .find(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
            val cs = meta?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charset.forName("windows-1252")
            return String(bytes, cs)
        }

        suspend fun load(context: Context, ref: DocRef, maxBytes: Int = 24 shl 20): LocalSite =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val bytes = Docs.open(context, ref).use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(1 shl 16)
                    while (out.size() < maxBytes) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                build(context, ref, bytes)
            }
    }
}

/**
 * A saved web page (`.mhtml` / `.mht`): one MIME multipart file holding the page and every
 * image, stylesheet and font it used, each tagged with its original URL.
 */
class MhtmlArchive private constructor(
    val mainHtml: String,
    val mainUrl: String?,
    private val parts: Map<String, Part>
) {
    class Part(val mime: String, val charset: String?, val data: ByteArray)

    fun find(url: String): LocalSite.Resource? {
        val key = url.substringBefore('#')
        val p = parts[key] ?: parts[key.removeSuffix("/")] ?: return null
        return LocalSite.Resource(p.mime, p.charset, ByteArrayInputStream(p.data))
    }

    companion object {
        fun looksLike(bytes: ByteArray): Boolean {
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1).lowercase(Locale.ROOT)
            return head.contains("mime-version:") && head.contains("multipart/related")
        }

        fun parse(bytes: ByteArray): MhtmlArchive? {
            val s = String(bytes, Charsets.ISO_8859_1)
            val headerEnd = blankLine(s, 0) ?: return null
            val top = headers(s.substring(0, headerEnd.first))
            val boundary = Regex("""boundary\s*=\s*"?([^";\r\n]+)"?""", RegexOption.IGNORE_CASE)
                .find(top["content-type"].orEmpty())?.groupValues?.get(1) ?: return null
            val delim = "--$boundary"
            val parts = LinkedHashMap<String, Part>()
            var main: Pair<String, String?>? = null
            var pos = s.indexOf(delim, headerEnd.second)
            while (pos >= 0) {
                val start = pos + delim.length
                if (s.startsWith("--", start)) break
                val next = s.indexOf(delim, start)
                val chunk = s.substring(start, if (next < 0) s.length else next).trimStart('\r', '\n')
                pos = next
                val split = blankLine(chunk, 0) ?: continue
                val h = headers(chunk.substring(0, split.first))
                val body = chunk.substring(split.second).removeSuffix("\n").removeSuffix("\r")
                val type = h["content-type"].orEmpty()
                val mime = type.substringBefore(';').trim().lowercase(Locale.ROOT).ifEmpty { "application/octet-stream" }
                val charset = Regex("""charset\s*=\s*"?([\w-]+)""", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
                val data = when (h["content-transfer-encoding"]?.trim()?.lowercase(Locale.ROOT)) {
                    "base64" -> runCatching { Base64.getMimeDecoder().decode(body) }.getOrNull() ?: continue
                    "quoted-printable" -> quotedPrintable(body)
                    else -> body.toByteArray(Charsets.ISO_8859_1)
                }
                val location = h["content-location"]?.trim()
                val cid = h["content-id"]?.trim()?.removePrefix("<")?.removeSuffix(">")
                val part = Part(mime, charset, data)
                location?.let { parts[it] = part }
                cid?.let { parts["cid:$it"] = part }
                if (main == null && mime == "text/html") main = LocalSite.decodeText(data, charset) to location
            }
            val (html, url) = main ?: return null
            return MhtmlArchive(html, url, parts)
        }

        /** Returns (end of headers, start of body) for the first blank line at or after [from]. */
        private fun blankLine(s: String, from: Int): Pair<Int, Int>? {
            val crlf = s.indexOf("\r\n\r\n", from)
            val lf = s.indexOf("\n\n", from)
            return when {
                crlf >= 0 && (lf < 0 || crlf <= lf) -> crlf to crlf + 4
                lf >= 0 -> lf to lf + 2
                else -> null
            }
        }

        private fun headers(block: String): Map<String, String> {
            val out = HashMap<String, String>()
            var key: String? = null
            for (line in block.split('\n').map { it.trimEnd('\r') }) {
                if ((line.startsWith(" ") || line.startsWith("\t")) && key != null) {
                    out[key] = out[key] + " " + line.trim(); continue
                }
                val c = line.indexOf(':')
                if (c <= 0) continue
                key = line.substring(0, c).trim().lowercase(Locale.ROOT)
                out[key] = line.substring(c + 1).trim()
            }
            return out
        }

        private fun quotedPrintable(s: String): ByteArray {
            val out = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '=') {
                    if (s.startsWith("\r\n", i + 1)) { i += 3; continue }
                    if (i + 1 < s.length && s[i + 1] == '\n') { i += 2; continue }
                    val hex = s.substring(i + 1, minOf(i + 3, s.length)).toIntOrNull(16)
                    if (hex != null) { out.write(hex); i += 3; continue }
                }
                out.write(c.code and 0xFF)
                i++
            }
            return out.toByteArray()
        }
    }
}
