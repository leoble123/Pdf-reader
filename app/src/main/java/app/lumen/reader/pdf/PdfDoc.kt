package app.lumen.reader.pdf

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.LruCache
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import app.lumen.reader.core.DocRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

class PdfPasswordRequired : Exception("This PDF is password protected")

/** A rectangle inside a page, in normalized 0..1 page coordinates. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class PdfLink(val bounds: List<NormRect>, val page: Int?, val url: String?)

data class SearchHit(val page: Int, val rects: List<NormRect>)

/**
 * Thread-confined wrapper around [PdfRenderer].
 *
 * Every renderer call runs on one dedicated worker thread fed by a priority queue:
 * visible pages jump ahead of prefetches, and among equals the most recent request wins,
 * so a fast fling always renders where the finger landed instead of every page it passed.
 * Requests whose caller went away are dropped before they cost anything.
 */
class PdfDoc private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    cacheBytes: Int
) : Closeable {

    val pageCount: Int = renderer.pageCount

    /** Page sizes in PDF points, packed as width shl 32 | height. Filled progressively. */
    val pageSizes: SnapshotStateList<Long> = mutableStateListOf()

    private val queue = PriorityBlockingQueue<Job>(32)
    private val seq = AtomicLong()
    @Volatile private var closed = false

    private val cache = object : LruCache<Long, Bitmap>(cacheBytes) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }

    private val worker = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        while (true) {
            val job = try {
                queue.take()
            } catch (_: InterruptedException) {
                break
            }
            if (job.poison) break
            if (job.deferred.isCancelled || closed) continue
            try {
                job.deferred.complete(job.block())
            } catch (t: Throwable) {
                job.deferred.completeExceptionally(t)
            }
        }
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }, "lumen-pdf").apply { start() }

    private class Job(
        val priority: Int,
        val order: Long,
        val block: () -> Any?,
        val deferred: CompletableDeferred<Any?>,
        val poison: Boolean = false
    ) : Comparable<Job> {
        override fun compareTo(other: Job): Int =
            if (priority != other.priority) other.priority.compareTo(priority)
            else other.order.compareTo(order)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> run(priority: Int, block: () -> T): T {
        if (closed) throw CancellationException("closed")
        val deferred = CompletableDeferred<Any?>()
        queue.add(Job(priority, seq.incrementAndGet(), block, deferred))
        try {
            return deferred.await() as T
        } catch (e: CancellationException) {
            deferred.cancel()
            throw e
        }
    }

    fun aspect(index: Int): Float {
        val packed = pageSizes.getOrNull(index) ?: pageSizes.firstOrNull() ?: return 1f / 1.4142f
        val w = (packed ushr 32).toInt()
        val h = (packed and 0xFFFFFFFF).toInt()
        return if (h > 0) w.toFloat() / h else 1f / 1.4142f
    }

    private fun pagePoints(index: Int): Pair<Int, Int> {
        val packed = pageSizes.getOrNull(index)
        return if (packed != null) (packed ushr 32).toInt() to (packed and 0xFFFFFFFF).toInt()
        else 612 to 792
    }

    /** Measures every page in small slices so it never blocks rendering for long. */
    suspend fun measurePages() {
        var i = pageSizes.size
        while (i < pageCount) {
            val from = i
            val slice = run(PRIORITY_MEASURE) {
                val to = minOf(pageCount, from + 24)
                LongArray(to - from) { k ->
                    renderer.openPage(from + k).use { p -> (p.width.toLong() shl 32) or p.height.toLong() }
                }
            }
            withContext(Dispatchers.Main) { pageSizes.addAll(slice.toList()) }
            i += slice.size
        }
    }

    fun cached(index: Int, widthPx: Int): Bitmap? = cache.get(key(index, widthPx))

    fun cachedPreview(index: Int): Bitmap? = cache.get(key(index, PREVIEW_WIDTH))

    suspend fun renderPreview(index: Int): Bitmap = render(index, PREVIEW_WIDTH, PRIORITY_VISIBLE + 1)

    suspend fun render(index: Int, widthPx: Int, priority: Int = PRIORITY_VISIBLE): Bitmap {
        cache.get(key(index, widthPx))?.let { return it }
        val bmp = run(priority) {
            cache.get(key(index, widthPx)) ?: renderer.openPage(index).use { page ->
                val w = widthPx.coerceAtLeast(1)
                val h = (w * page.height.toFloat() / page.width).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                val m = Matrix().apply { setScale(w.toFloat() / page.width, h.toFloat() / page.height) }
                page.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.prepareToDraw()
                bitmap
            }
        }
        cache.put(key(index, widthPx), bmp)
        return bmp
    }

    fun prefetch(index: Int, widthPx: Int) {
        if (index !in 0 until pageCount || cached(index, widthPx) != null || closed) return
        val deferred = CompletableDeferred<Any?>()
        queue.add(Job(PRIORITY_PREFETCH, seq.incrementAndGet(), {
            if (cache.get(key(index, widthPx)) == null) {
                renderer.openPage(index).use { page ->
                    val h = (widthPx * page.height.toFloat() / page.width).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    val m = Matrix().apply { setScale(widthPx.toFloat() / page.width, h.toFloat() / page.height) }
                    page.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.prepareToDraw()
                    cache.put(key(index, widthPx), bitmap)
                }
            }
            null
        }, deferred))
    }

    /**
     * Renders only [region] (normalized) of a page as if the page were [fullWidthPx] wide.
     * This is what keeps deep zoom razor sharp without ever allocating a gigantic bitmap.
     */
    suspend fun renderRegion(index: Int, fullWidthPx: Float, region: NormRect): Bitmap = run(PRIORITY_DETAIL) {
        renderer.openPage(index).use { page ->
            val fullHeightPx = fullWidthPx * page.height / page.width
            val left = region.left * fullWidthPx
            val top = region.top * fullHeightPx
            val w = ((region.right - region.left) * fullWidthPx).roundToInt().coerceIn(1, 4096)
            val h = ((region.bottom - region.top) * fullHeightPx).roundToInt().coerceIn(1, 4096)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val f = fullWidthPx / page.width
            val m = Matrix().apply {
                setScale(f, f)
                postTranslate(-left, -top)
            }
            page.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap.prepareToDraw()
            bitmap
        }
    }

    val supportsText: Boolean get() = Build.VERSION.SDK_INT >= 35

    @SuppressLint("NewApi")
    suspend fun search(index: Int, query: String): SearchHit? {
        if (!supportsText || query.isBlank()) return null
        return run(PRIORITY_SEARCH) {
            renderer.openPage(index).use { page ->
                val w = page.width.toFloat()
                val h = page.height.toFloat()
                val rects = page.searchText(query).flatMap { match ->
                    match.bounds.map { it.norm(w, h) }
                }
                if (rects.isEmpty()) null else SearchHit(index, rects)
            }
        }
    }

    @SuppressLint("NewApi")
    suspend fun links(index: Int): List<PdfLink> {
        if (!supportsText) return emptyList()
        return run(PRIORITY_SEARCH + 1) {
            runCatching {
                renderer.openPage(index).use { page ->
                    val w = page.width.toFloat()
                    val h = page.height.toFloat()
                    val gotos = page.gotoLinks.map { link ->
                        PdfLink(link.bounds.map { it.norm(w, h) }, link.destination.pageNumber, null)
                    }
                    val urls = page.linkContents.map { link ->
                        PdfLink(link.bounds.map { it.norm(w, h) }, null, link.uri.toString())
                    }
                    gotos + urls
                }
            }.getOrDefault(emptyList())
        }
    }

    @SuppressLint("NewApi")
    suspend fun pageText(index: Int): String {
        if (!supportsText) return ""
        return run(PRIORITY_SEARCH) {
            renderer.openPage(index).use { page -> page.textContents.joinToString("\n") { it.text } }
        }
    }

    private fun RectF.norm(w: Float, h: Float) = NormRect(left / w, top / h, right / w, bottom / h)

    override fun close() {
        if (closed) return
        closed = true
        cache.evictAll()
        queue.add(Job(Int.MAX_VALUE, 0, { null }, CompletableDeferred(), poison = true))
    }

    private fun key(index: Int, width: Int): Long = (index.toLong() shl 20) or width.toLong()

    companion object {
        const val PRIORITY_VISIBLE = 100
        const val PRIORITY_DETAIL = 90
        const val PRIORITY_MEASURE = 50
        const val PRIORITY_PREFETCH = 40
        const val PRIORITY_SEARCH = 10
        const val PREVIEW_WIDTH = 240

        suspend fun open(context: Context, ref: DocRef, password: String? = null): PdfDoc =
            withContext(Dispatchers.IO) {
                val pfd = context.contentResolver.openFileDescriptor(ref.uri, "r")
                    ?: error("Unable to open ${ref.name}")
                val seekable = runCatching { android.system.Os.lseek(pfd.fileDescriptor, 0, android.system.OsConstants.SEEK_CUR); true }
                    .getOrDefault(false)
                val usable = if (seekable) pfd else {
                    // Some providers hand out pipes; PdfRenderer needs a seekable file.
                    pfd.close()
                    val file = app.lumen.reader.core.Docs.localFile(context, ref)
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
                val renderer = try {
                    if (Build.VERSION.SDK_INT >= 35 && password != null) {
                        PdfRenderer(usable, LoadParams.Builder().setPassword(password).build())
                    } else {
                        PdfRenderer(usable)
                    }
                } catch (e: SecurityException) {
                    usable.close()
                    throw PdfPasswordRequired()
                } catch (e: Exception) {
                    usable.close()
                    throw e
                }
                val am = context.getSystemService(ActivityManager::class.java)
                val budget = (am.largeMemoryClass.toLong() * 1024 * 1024 / 3)
                    .coerceIn(48L shl 20, 256L shl 20).toInt()
                PdfDoc(usable, renderer, budget).also { doc ->
                    // First page measured synchronously so the layout is right from frame one.
                    if (doc.pageCount > 0) {
                        val first = doc.run(PRIORITY_VISIBLE) {
                            renderer.openPage(0).use { p -> (p.width.toLong() shl 32) or p.height.toLong() }
                        }
                        withContext(Dispatchers.Main) { doc.pageSizes.add(first) }
                    }
                }
            }
    }
}
