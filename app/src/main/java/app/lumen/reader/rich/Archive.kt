package app.lumen.reader.rich

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Thread-safe random access to a zip-based document (OOXML, ODF, EPUB). */
class Archive(file: File) : Closeable {
    private val zip = ZipFile(file)
    private val lock = Mutex()

    fun exists(path: String) = zip.getEntry(path) != null

    fun text(path: String): String? = zip.getEntry(path)?.let { e ->
        zip.getInputStream(e).use { it.readBytes() }.let { bytes ->
            app.lumen.reader.text.TextLoader.decode(bytes).first
        }
    }

    fun <T> stream(path: String, block: (InputStream) -> T): T? =
        zip.getEntry(path)?.let { e -> zip.getInputStream(e).use(block) }

    fun parser(path: String): XmlPullParser? {
        val e = zip.getEntry(path) ?: return null
        val bytes = zip.getInputStream(e).use { it.readBytes() }
        return Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(bytes.inputStream(), null)
        }
    }

    /** Lazily decodes an embedded image, downsampled to roughly [maxWidth] pixels. */
    fun imageLoader(path: String): (suspend (Int) -> Bitmap?)? {
        if (zip.getEntry(path) == null) return null
        return { maxWidth ->
            withContext(Dispatchers.IO) {
                lock.withLock {
                    runCatching {
                        val bytes = zip.getInputStream(zip.getEntry(path)).use { it.readBytes() }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    }.getOrNull()
                }
            }
        }
    }

    /** OOXML relationship map: Id → resolved target path (or the URL for external targets). */
    fun rels(relsPath: String, baseDir: String): Map<String, Pair<String, Boolean>> {
        val out = HashMap<String, Pair<String, Boolean>>()
        val p = parser(relsPath) ?: return out
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.getAttributeValue(null, "Id") ?: continue
                val target = p.getAttributeValue(null, "Target") ?: continue
                val external = p.getAttributeValue(null, "TargetMode") == "External"
                out[id] = (if (external) target else resolvePath(baseDir, target)) to external
            }
        }
        return out
    }

    override fun close() = zip.close()

    companion object {
        fun resolvePath(baseDir: String, target: String): String {
            if (target.startsWith("/")) return target.removePrefix("/")
            val parts = ArrayList<String>()
            if (baseDir.isNotEmpty()) parts += baseDir.trim('/').split('/')
            for (seg in target.substringBefore('#').split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts += java.net.URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8")
                }
            }
            return parts.joinToString("/")
        }
    }
}
