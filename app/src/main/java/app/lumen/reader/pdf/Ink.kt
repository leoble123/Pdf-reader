package app.lumen.reader.pdf

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import app.lumen.reader.core.PenMath
import app.lumen.reader.core.PenStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.Docs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class InkTool { Pen, Highlighter, Eraser, Lasso, Text }

/** Everything is stored in page-normalized coordinates (0..1 of page width/height) so it's resolution-free. */
sealed interface Mark {
    val color: Int
    fun translated(dx: Float, dy: Float): Mark
    fun recolored(color: Int): Mark

    /** [widths] holds per-point pressure multipliers of [width]; null means an even stroke. */
    class Ink(
        val points: FloatArray,
        override val color: Int,
        val width: Float,
        val highlighter: Boolean,
        val widths: FloatArray? = null
    ) : Mark {
        // The built path is cached for the size it was built at, so redraws don't rebuild it.
        internal var path: android.graphics.Path? = null
        internal var pathW = 0f
        internal var pathH = 0f

        override fun translated(dx: Float, dy: Float) =
            Ink(FloatArray(points.size) { points[it] + if (it % 2 == 0) dx else dy }, color, width, highlighter, widths)

        override fun recolored(color: Int) = Ink(points, color, width, highlighter, widths)

        /** left, top, right, bottom of the points, so the eraser can skip far-away strokes cheaply. */
        val bounds: FloatArray by lazy {
            var l = 1f; var t = 1f; var r = 0f; var b = 0f
            for (i in 0 until points.size / 2) {
                l = min(l, points[i * 2]); r = max(r, points[i * 2])
                t = min(t, points[i * 2 + 1]); b = max(b, points[i * 2 + 1])
            }
            floatArrayOf(l, t, r, b)
        }

        /** Width used where only one is allowed (PDF /BS, flattened export of even strokes). */
        val averageWidth: Float get() = widths?.let { w -> width * w.average().toFloat() } ?: width
    }

    class Note(val x: Float, val y: Float, val text: String, override val color: Int, val size: Float) : Mark {
        override fun translated(dx: Float, dy: Float) = Note(x + dx, y + dy, text, color, size)
        override fun recolored(color: Int) = Note(x, y, text, color, size)
    }
}

/** The stroke being drawn: which page, how it looks, and its growing geometry. */
class LiveInk(val page: Int, val color: Int, val width: Float, val highlighter: Boolean, val lasso: Boolean, val stroke: PenStroke)

/** Marks picked with the lasso on one page, plus the bounds used for dragging them. */
class Selection(val page: Int, val indices: Set<Int>, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float, pad: Float = 0.02f) =
        x in (left - pad)..(right + pad) && y in (top - pad)..(bottom + pad)
}

@Stable
class InkController(private val file: File) {
    var tool by mutableStateOf(InkTool.Pen)
        private set
    var color by mutableStateOf(Palette[0])
    var width by mutableStateOf(0.004f)
    /** Eraser removes whole strokes (true) or only the part it passes over. */
    var eraseWhole by mutableStateOf(true)
    /** Eraser radius in page widths. */
    var eraserSize by mutableFloatStateOf(0.016f)
    val recentColors = mutableStateListOf<Int>()
    val pages = mutableStateMapOf<Int, List<Mark>>()

    /** The stroke currently being drawn, kept separate so drawing only redraws one layer of one page. */
    var live by mutableStateOf<LiveInk?>(null)
        private set
    /** Bumped on every new sample of [live]; only the live layer reads it. */
    var liveVersion by mutableIntStateOf(0)
        private set

    var selection by mutableStateOf<Selection?>(null)
        private set
    var dragX by mutableFloatStateOf(0f)
        private set
    var dragY by mutableFloatStateOf(0f)
        private set

    private val undo = mutableStateListOf<Map<Int, List<Mark>>>()
    private val redo = mutableStateListOf<Map<Int, List<Mark>>>()
    private var batchStarted = false
    private var batchChanged = false

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    val isEmpty get() = pages.values.all { it.isEmpty() }

    private fun snapshot() {
        if (batchStarted) {
            if (batchChanged) return
            batchChanged = true
        }
        undo += pages.toMap()
        if (undo.size > 200) undo.removeAt(0)
        redo.clear()
    }

    fun selectTool(t: InkTool) {
        if (t != InkTool.Lasso) selection = null
        tool = t
    }

    fun pickColor(c: Int) {
        color = c
        if (c !in Palette) {
            recentColors.remove(c); recentColors.add(0, c)
            while (recentColors.size > 4) recentColors.removeAt(recentColors.lastIndex)
        }
        selection?.let { recolorSelection(c) }
    }

    fun undo(): Boolean {
        val prev = undo.removeLastOrNull() ?: return false
        redo += pages.toMap()
        pages.clear(); pages.putAll(prev)
        selection = null
        persist()
        return true
    }

    fun redo(): Boolean {
        val next = redo.removeLastOrNull() ?: return false
        undo += pages.toMap()
        pages.clear(); pages.putAll(next)
        selection = null
        persist()
        return true
    }

    /**
     * Starts a stroke. [pressure] is null for fingers (even width); [aspect] is the page's
     * width / height so shapes and distances aren't stretched.
     */
    fun begin(page: Int, x: Float, y: Float, pressure: Float?, aspect: Float) {
        val lasso = tool == InkTool.Lasso
        val highlighter = tool == InkTool.Highlighter
        if (lasso) selection = null
        val stroke = PenStroke(aspect, pressure = pressure != null && !highlighter && !lasso)
        stroke.add(x, y, pressure ?: 1f)
        live = LiveInk(page, if (lasso) 0xFF555555.toInt() else color, if (highlighter) width * 4 else width, highlighter, lasso, stroke)
        liveVersion++
    }

    fun extend(x: Float, y: Float, pressure: Float?) {
        val l = live ?: return
        if (l.stroke.add(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), pressure ?: 1f)) liveVersion++
    }

    /** Where the pen is predicted to be next frame (NaN clears). Only drawn, never saved. */
    fun predict(x: Float, y: Float) {
        val l = live ?: return
        if (l.stroke.predictedX.isNaN() && x.isNaN()) return
        l.stroke.predictedX = x; l.stroke.predictedY = y
        liveVersion++
    }

    /** Hold-to-straighten: turns the live stroke into a line or shape. True if it changed. */
    fun snapLive(): Boolean {
        val l = live ?: return false
        if (l.lasso) return false
        return l.stroke.snap().also { if (it) liveVersion++ }
    }

    fun end() {
        val l = live ?: return
        live = null
        liveVersion++
        val s = l.stroke
        if (l.lasso) {
            select(l.page, s.points()); return
        }
        snapshot()
        val mark = Mark.Ink(s.points(), l.color, l.width * s.evenWidth(), l.highlighter, s.widths())
        pages[l.page] = pages[l.page].orEmpty() + mark
        persist()
    }

    fun cancel() {
        live = null
        liveVersion++
    }

    /** Groups everything until [endBatch] into one undo step (a whole eraser drag, for example). */
    fun beginBatch() {
        batchStarted = true; batchChanged = false
    }

    fun endBatch() {
        batchStarted = false
        if (batchChanged) persist()
        batchChanged = false
    }

    /**
     * Erases along the segment from the previous eraser position, in steps no wider than half
     * the eraser, so a fast swipe can't skip over a stroke between two samples.
     */
    fun eraseAlong(page: Int, x0: Float, y0: Float, x1: Float, y1: Float, aspect: Float) {
        val dist = hypot(x1 - x0, (y1 - y0) / aspect)
        val steps = (dist / (eraserSize * 0.5f)).toInt().coerceIn(1, 64)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            erase(page, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, aspect)
        }
    }

    fun erase(page: Int, x: Float, y: Float, aspect: Float) {
        val marks = pages[page] ?: return
        val radius = eraserSize
        var changed = false
        val out = ArrayList<Mark>(marks.size)
        for (m in marks) when (m) {
            is Mark.Ink -> {
                val reach = radius + m.width / 2
                val bb = m.bounds
                // Bounding-box reject first: most strokes on a page are nowhere near the eraser.
                if (x < bb[0] - reach || x > bb[2] + reach || y < bb[1] - reach * aspect || y > bb[3] + reach * aspect) {
                    out += m; continue
                }
                if (eraseWhole) {
                    if (PenMath.touches(m.points, x, y, reach, aspect)) changed = true else out += m
                } else {
                    val pieces = PenMath.eraseFrom(m.points, m.widths, x, y, reach, aspect)
                    if (pieces == null) out += m
                    else {
                        changed = true
                        pieces.forEach { out += Mark.Ink(it.points, m.color, m.width, m.highlighter, it.widths) }
                    }
                }
            }
            is Mark.Note -> if (hypot(m.x - x, (m.y - y) / aspect) < radius * 3) changed = true else out += m
        }
        if (changed) {
            snapshot()
            pages[page] = out
            if (!batchStarted) persist()
        }
    }

    fun addNote(page: Int, x: Float, y: Float, text: String) {
        if (text.isBlank()) return
        snapshot()
        pages[page] = pages[page].orEmpty() + Mark.Note(x, y, text, color, 0.022f)
        persist()
    }

    // --- Lasso selection ---

    private fun select(page: Int, poly: FloatArray) {
        val marks = pages[page].orEmpty()
        if (poly.size < 6) { selection = null; return }
        val picked = HashSet<Int>()
        marks.forEachIndexed { i, m ->
            when (m) {
                is Mark.Ink -> {
                    val n = m.points.size / 2
                    var inside = 0
                    for (k in 0 until n) if (PenMath.pointInPolygon(m.points[k * 2], m.points[k * 2 + 1], poly)) inside++
                    if (inside * 10 >= n * 6) picked += i
                }
                is Mark.Note -> if (PenMath.pointInPolygon(m.x, m.y, poly)) picked += i
            }
        }
        selection = if (picked.isEmpty()) null else selectionOf(page, picked)
    }

    private fun selectionOf(page: Int, indices: Set<Int>): Selection? {
        val marks = pages[page].orEmpty()
        var l = 1f; var t = 1f; var r = 0f; var b = 0f
        for (i in indices) when (val m = marks.getOrNull(i)) {
            is Mark.Ink -> for (k in 0 until m.points.size / 2) {
                l = min(l, m.points[k * 2]); r = max(r, m.points[k * 2])
                t = min(t, m.points[k * 2 + 1]); b = max(b, m.points[k * 2 + 1])
            }
            is Mark.Note -> {
                l = min(l, m.x); t = min(t, m.y - m.size); r = max(r, m.x + m.size * 0.55f * m.text.length); b = max(b, m.y + m.size)
            }
            null -> {}
        }
        if (r < l) return null
        return Selection(page, indices, l, t, r, b)
    }

    fun clearSelection() {
        selection = null
    }

    fun dragSelection(dx: Float, dy: Float) {
        dragX += dx; dragY += dy
    }

    fun endDrag() {
        val sel = selection ?: return
        val dx = dragX; val dy = dragY
        dragX = 0f; dragY = 0f
        if (dx == 0f && dy == 0f) return
        snapshot()
        pages[sel.page] = pages[sel.page].orEmpty().mapIndexed { i, m -> if (i in sel.indices) m.translated(dx, dy) else m }
        selection = selectionOf(sel.page, sel.indices)
        persist()
    }

    fun deleteSelection() {
        val sel = selection ?: return
        snapshot()
        pages[sel.page] = pages[sel.page].orEmpty().filterIndexed { i, _ -> i !in sel.indices }
        selection = null
        persist()
    }

    fun recolorSelection(c: Int) {
        val sel = selection ?: return
        snapshot()
        pages[sel.page] = pages[sel.page].orEmpty().mapIndexed { i, m -> if (i in sel.indices) m.recolored(c) else m }
        persist()
    }

    fun duplicateSelection() {
        val sel = selection ?: return
        val marks = pages[sel.page].orEmpty()
        snapshot()
        val copies = sel.indices.sorted().map { marks[it].translated(0.02f, 0.02f) }
        pages[sel.page] = marks + copies
        selection = selectionOf(sel.page, (marks.size until marks.size + copies.size).toSet())
        persist()
    }

    /** Forget all marks without an undo step (they were saved into the PDF). */
    fun discard() {
        undo.clear(); redo.clear()
        pages.clear()
        selection = null
        persist()
    }

    fun clearAll() {
        snapshot()
        pages.clear()
        selection = null
        persist()
    }

    fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            root.keys().forEach { k ->
                val arr = root.getJSONArray(k)
                pages[k.toInt()] = List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    if (o.has("t")) Mark.Note(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getString("t"), o.getInt("c"), o.getDouble("s").toFloat())
                    else {
                        val p = o.getJSONArray("p")
                        val pw = o.optJSONArray("pw")
                        Mark.Ink(
                            FloatArray(p.length()) { p.getDouble(it).toFloat() }, o.getInt("c"), o.getDouble("w").toFloat(), o.optBoolean("h"),
                            pw?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
                        )
                    }
                }
            }
        }
    }

    private fun persist() {
        val root = JSONObject()
        pages.forEach { (page, marks) ->
            if (marks.isEmpty()) return@forEach
            root.put(page.toString(), JSONArray().apply {
                marks.forEach { m ->
                    put(JSONObject().apply {
                        when (m) {
                            is Mark.Ink -> {
                                put("p", JSONArray().apply { m.points.forEach { put(it.toDouble()) } })
                                m.widths?.let { w -> put("pw", JSONArray().apply { w.forEach { put(it.toDouble()) } }) }
                                put("w", m.width.toDouble()); put("h", m.highlighter)
                            }
                            is Mark.Note -> {
                                put("x", m.x.toDouble()); put("y", m.y.toDouble()); put("t", m.text); put("s", m.size.toDouble())
                            }
                        }
                        put("c", m.color)
                    })
                }
            })
        }
        val text = if (root.length() == 0) null else root.toString()
        // Disk writes never block a stroke: hand the snapshot to a background thread.
        io.execute {
            runCatching {
                file.parentFile?.mkdirs()
                if (text == null) file.delete() else file.writeText(text)
            }
        }
    }

    companion object {
        val Palette = listOf(
            0xFF1F6FEB.toInt(), 0xFFE5484D.toInt(), 0xFF30A46C.toInt(), 0xFFFFD60A.toInt(),
            0xFFFF7A1A.toInt(), 0xFF8E4EC6.toInt(), 0xFF111111.toInt(), 0xFFFFFFFF.toInt()
        )

        /** Single thread so saves land in order. */
        private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

        /** Waits for pending saves (tests). */
        internal fun flush() {
            io.submit {}.get()
        }

        private val open = HashMap<String, java.lang.ref.WeakReference<InkController>>()

        /**
         * One controller per document while it's in use, so reloading the PDF (after adding a
         * notebook page, say) keeps undo history and never races a background save.
         */
        fun forDoc(context: Context, ref: DocRef): InkController = synchronized(open) {
            open[ref.key]?.get()?.let { return it }
            flush() // a just-released controller may still be writing this file
            InkController(fileFor(context, ref)).apply { load() }.also { open[ref.key] = java.lang.ref.WeakReference(it) }
        }

        fun fileFor(context: Context, ref: DocRef) = File(context.filesDir, "ink/" + Docs.hash(ref.key) + ".json")
    }
}

private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeJoin = Paint.Join.ROUND
}

/**
 * Draws marks onto a page whose drawn size is [size] (in px). [highlights] picks which half to
 * draw: highlighter strokes go on their own multiply-blended layer below the pens.
 */
fun DrawScope.drawMarks(marks: List<Mark>, measurer: TextMeasurer?, highlights: Boolean? = null, skip: Set<Int>? = null) {
    val w = size.width
    val h = size.height
    drawIntoCanvas { canvas ->
        val c = canvas.nativeCanvas
        marks.forEachIndexed { i, m ->
            if (skip != null && i in skip) return@forEachIndexed
            when (m) {
                is Mark.Ink -> {
                    if (highlights != null && highlights != m.highlighter) return@forEachIndexed
                    drawInk(c, m, w, h)
                }
                is Mark.Note -> if (measurer != null && highlights != true) {
                    val layout = measurer.measure(m.text, TextStyle(color = Color(m.color), fontSize = (m.size * w).toSp()))
                    drawText(layout, topLeft = Offset(m.x * w, m.y * h - layout.size.height / 2f))
                }
            }
        }
    }
}

internal fun drawInk(c: android.graphics.Canvas, m: Mark.Ink, w: Float, h: Float) {
    val paint = markPaint
    paint.color = if (m.highlighter) Color(m.color).copy(alpha = HighlightAlpha).toArgb() else m.color
    paint.strokeCap = if (m.highlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
    val widths = m.widths
    if (widths != null && !m.highlighter) {
        PenMath.drawVariable(c, m.points, widths, w, h, m.width * w, paint)
        return
    }
    paint.strokeWidth = m.width * w
    if (m.points.size == 2) {
        c.drawPoint(m.points[0] * w, m.points[1] * h, paint.apply { strokeCap = Paint.Cap.ROUND }); return
    }
    val path = m.path?.takeIf { m.pathW == w && m.pathH == h } ?: PenMath.path(m.points, w, h, m.path ?: android.graphics.Path()).also {
        m.path = it; m.pathW = w; m.pathH = h
    }
    c.drawPath(path, paint)
}

const val HighlightAlpha = 0.38f

private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeJoin = Paint.Join.ROUND
    strokeCap = Paint.Cap.ROUND
}
private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeJoin = Paint.Join.ROUND
    color = 0xFF555555.toInt()
}

/** Draws the in-progress stroke and the dragged selection for one page. */
fun DrawScope.drawLive(ink: InkController, page: Int, measurer: TextMeasurer?, accent: Color) {
    ink.liveVersion // read so this layer, and only this layer, redraws per sample
    val w = size.width
    val h = size.height
    ink.live?.takeIf { it.page == page }?.let { l ->
        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            if (l.lasso) {
                val d = 6.dp.toPx()
                lassoPaint.strokeWidth = 1.5.dp.toPx()
                lassoPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(d, d), 0f)
                l.stroke.draw(c, w, h, 1.5.dp.toPx(), lassoPaint)
            } else {
                livePaint.color = if (l.highlighter) Color(l.color).copy(alpha = HighlightAlpha).toArgb() else l.color
                livePaint.strokeCap = if (l.highlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
                l.stroke.draw(c, w, h, l.width * w, livePaint)
            }
        }
    }
    ink.selection?.takeIf { it.page == page }?.let { sel ->
        val dx = ink.dragX; val dy = ink.dragY
        val marks = ink.pages[page].orEmpty()
        val moved = sel.indices.mapNotNull { marks.getOrNull(it) }.map { if (dx != 0f || dy != 0f) it.translated(dx, dy) else it }
        drawMarks(moved, measurer)
        val pad = 0.012f * w
        drawRoundRect(
            accent,
            topLeft = Offset((sel.left + dx) * w - pad, (sel.top + dy) * h - pad),
            size = androidx.compose.ui.geometry.Size((sel.right - sel.left) * w + pad * 2, (sel.bottom - sel.top) * h + pad * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())))
        )
    }
}

/**
 * Writes a new PDF with every page rendered at 2× and the markup drawn on top as vectors.
 * Android has no API to append annotations to an existing PDF, so the page content is flattened.
 */
suspend fun exportFlattened(doc: PdfDoc, ink: InkController, out: OutputStream, onProgress: (Float) -> Unit) {
    val pdf = PdfDocument()
    try {
        for (i in 0 until doc.pageCount) {
            val (pw, ph) = doc.pagePointsOf(i)
            val bitmap = doc.render(i, pw * 2, PdfDoc.PRIORITY_SEARCH)
            withContext(Dispatchers.Default) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val c = page.canvas
                c.drawBitmap(bitmap, null, android.graphics.RectF(0f, 0f, pw.toFloat(), ph.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                drawMarksNative(c, ink.pages[i].orEmpty(), pw.toFloat(), ph.toFloat())
                pdf.finishPage(page)
            }
            onProgress((i + 1f) / doc.pageCount)
        }
        withContext(Dispatchers.IO) { pdf.writeTo(out) }
    } finally {
        pdf.close()
    }
}

private fun drawMarksNative(c: android.graphics.Canvas, marks: List<Mark>, w: Float, h: Float) {
    for (m in marks) when (m) {
        is Mark.Ink -> {
            // Own paint: this runs off the main thread, unlike on-screen drawing.
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = m.width * w
                strokeCap = if (m.highlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = if (m.highlighter) Color(m.color).copy(alpha = HighlightAlpha).toArgb() else m.color
            }
            val widths = m.widths
            if (widths != null && !m.highlighter) PenMath.drawVariable(c, m.points, widths, w, h, m.width * w, paint)
            else c.drawPath(PenMath.path(m.points, w, h), paint)
        }
        is Mark.Note -> {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = m.color; textSize = m.size * w }
            c.drawText(m.text, m.x * w, m.y * h + paint.textSize / 3, paint)
        }
    }
}
