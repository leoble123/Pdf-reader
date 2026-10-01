package app.lumen.reader.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Recent-file thumbnails: memory LRU in front of a small disk cache. */
object Thumbs {

    private const val WIDTH = 360
    private val memory = object : LruCache<String, Bitmap>(24 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val gate = Semaphore(2)

    fun cached(ref: DocRef): Bitmap? = memory.get(ref.key)

    suspend fun load(context: Context, ref: DocRef): Bitmap? {
        memory.get(ref.key)?.let { return it }
        if (ref.kind !in setOf(FileKind.Pdf, FileKind.Image, FileKind.Video, FileKind.Audio)) return null
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                val file = File(File(context.cacheDir, "thumbs").apply { mkdirs() }, Docs.hash(ref.key + ref.size) + ".webp")
                val bmp = if (file.exists()) {
                    BitmapFactory.decodeFile(file.path)
                } else {
                    runCatching { generate(context, ref) }.getOrNull()?.also { b ->
                        runCatching {
                            file.outputStream().use {
                                @Suppress("DEPRECATION")
                                b.compress(
                                    if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP,
                                    82, it
                                )
                            }
                        }
                    }
                }
                bmp?.also { memory.put(ref.key, it) }
            }
        }
    }

    private fun generate(context: Context, ref: DocRef): Bitmap? = when (ref.kind) {
        FileKind.Pdf -> context.contentResolver.openFileDescriptor(ref.uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (r.pageCount == 0) return null
                r.openPage(0).use { page ->
                    val h = (WIDTH * page.height.toFloat() / page.width).roundToInt().coerceIn(1, WIDTH * 2)
                    Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE)
                        page.render(this, null, Matrix().apply {
                            setScale(WIDTH.toFloat() / page.width, h.toFloat() / page.height)
                        }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
        FileKind.Image -> {
            val src = ImageDecoder.createSource(context.contentResolver, ref.uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val s = info.size
                val scale = WIDTH.toFloat() / maxOf(1, minOf(s.width, s.height))
                if (scale < 1f) decoder.setTargetSize((s.width * scale).roundToInt().coerceAtLeast(1), (s.height * scale).roundToInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        FileKind.Video, FileKind.Audio -> MediaMetadataRetriever().run {
            try {
                setDataSource(context, ref.uri)
                if (ref.kind == FileKind.Audio) {
                    embeddedPicture?.let { bytes ->
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
                    }
                } else if (Build.VERSION.SDK_INT >= 27) {
                    getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, WIDTH)
                } else {
                    getFrameAtTime(1_000_000)
                }
            } finally {
                release()
            }
        }
        else -> null
    }
}
