package app.lumen.reader.pdf

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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

enum class InkTool { Pen, Highlighter, Eraser, Text }

/** Everything is stored in page-normalized coordinates (0..1 of page width/height) so it's resolution-free. */
sealed interface Mark {
    val color: Int

    class Ink(val points: FloatArray, override val color: Int, val width: Float, val highlighter: Boolean) : Mark
    class Note(val x: Float, val y: Float, val text: String, override val color: Int, val size: Float) : Mark
}

@Stable
class InkController(private val file: File) {
    var tool by mutableStateOf(InkTool.Pen)
    var color by mutableStateOf(Palette[0])
    var width by mutableStateOf(0.004f)
    val pages = mutableStateMapOf<Int, List<Mark>>()

    /** The stroke currently being drawn, kept separate so drawing only redraws one page. */
    var live by mutableStateOf<Pair<Int, Mark.Ink>?>(null)
        private set
    private val livePoints = ArrayList<Float>()
    private val undo = mutableStateListOf<Map<Int, List<Mark>>>()
    private val redo = mutableStateListOf<Map<Int, List<Mark>>>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    val isEmpty get() = pages.values.all { it.isEmpty() }

    private fun snapshot() {
        undo += pages.toMap()
        if (undo.size > 100) undo.removeAt(0)
        redo.clear()
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        redo += pages.toMap()
        pages.clear(); pages.putAll(prev)
        persist()
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo += pages.toMap()
        pages.clear(); pages.putAll(next)
        persist()
    }

    fun begin(page: Int, x: Float, y: Float) {
        livePoints.clear()
        livePoints += x; livePoints += y
        live = page to Mark.Ink(livePoints.toFloatArray(), color, if (tool == InkTool.Highlighter) width * 4 else width, tool == InkTool.Highlighter)
    }

    fun extend(x: Float, y: Float) {
        val (page, ink) = live ?: return
        val lx = livePoints[livePoints.size - 2]
        val ly = livePoints[livePoints.size - 1]
        if (hypot(x - lx, y - ly) < 0.0015f) return
        livePoints += x; livePoints += y
        live = page to Mark.Ink(livePoints.toFloatArray(), ink.color, ink.width, ink.highlighter)
    }

    fun end() {
        val (page, ink) = live ?: return
        live = null
        snapshot()
        pages[page] = pages[page].orEmpty() + ink
        persist()
    }

    fun cancel() {
        live = null
    }

    fun erase(page: Int, x: Float, y: Float, radius: Float = 0.02f) {
        val marks = pages[page] ?: return
        val kept = marks.filterNot { m ->
            when (m) {
                is Mark.Ink -> (0 until m.points.size / 2).any { i -> hypot(m.points[i * 2] - x, m.points[i * 2 + 1] - y) < radius + m.width }
                is Mark.Note -> hypot(m.x - x, m.y - y) < radius * 3
            }
        }
        if (kept.size != marks.size) {
            snapshot()
            pages[page] = kept
            persist()
        }
    }

    fun addNote(page: Int, x: Float, y: Float, text: String) {
        if (text.isBlank()) return
        snapshot()
        pages[page] = pages[page].orEmpty() + Mark.Note(x, y, text, color, 0.022f)
        persist()
    }

    /** Forget all marks without an undo step (they were saved into the PDF). */
    fun discard() {
        undo.clear(); redo.clear()
        pages.clear()
        persist()
    }

    fun clearAll() {
        snapshot()
        pages.clear()
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
                        Mark.Ink(FloatArray(p.length()) { p.getDouble(it).toFloat() }, o.getInt("c"), o.getDouble("w").toFloat(), o.optBoolean("h"))
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
        runCatching {
            file.parentFile?.mkdirs()
            if (root.length() == 0) file.delete() else file.writeText(root.toString())
        }
    }

    companion object {
        val Palette = listOf(
            0xFF1F6FEB.toInt(), 0xFFE5484D.toInt(), 0xFF30A46C.toInt(), 0xFFFFD60A.toInt(),
            0xFFFF7A1A.toInt(), 0xFF8E4EC6.toInt(), 0xFF111111.toInt(), 0xFFFFFFFF.toInt()
        )

        fun forDoc(context: Context, ref: DocRef) =
            InkController(File(context.filesDir, "ink/" + Docs.hash(ref.key) + ".json")).apply { load() }
    }
}

/** Draws marks onto a page whose drawn size is [size] (in px). */
fun DrawScope.drawMarks(marks: List<Mark>, measurer: TextMeasurer?) {
    val w = size.width
    val h = size.height
    for (m in marks) when (m) {
        is Mark.Ink -> {
            val c = Color(m.color)
            val stroke = Stroke(width = m.width * w, cap = if (m.highlighter) StrokeCap.Square else StrokeCap.Round, join = StrokeJoin.Round)
            if (m.points.size == 2) {
                drawCircle(c.copy(alpha = if (m.highlighter) 0.35f else 1f), m.width * w / 2, Offset(m.points[0] * w, m.points[1] * h))
                continue
            }
            val path = Path().apply {
                moveTo(m.points[0] * w, m.points[1] * h)
                var i = 2
                // Quadratic smoothing through midpoints keeps fast strokes from looking jagged.
                while (i + 3 < m.points.size) {
                    val cx = m.points[i] * w; val cy = m.points[i + 1] * h
                    val nx = m.points[i + 2] * w; val ny = m.points[i + 3] * h
                    quadraticTo(cx, cy, (cx + nx) / 2, (cy + ny) / 2)
                    i += 2
                }
                lineTo(m.points[m.points.size - 2] * w, m.points[m.points.size - 1] * h)
            }
            if (m.highlighter) drawPath(path, c.copy(alpha = 0.38f), style = stroke, blendMode = BlendMode.Multiply)
            else drawPath(path, c, style = stroke)
        }
        is Mark.Note -> if (measurer != null) {
            val layout = measurer.measure(m.text, TextStyle(color = Color(m.color), fontSize = (m.size * w).toSp()))
            drawText(layout, topLeft = Offset(m.x * w, m.y * h - layout.size.height / 2f))
        }
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
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = m.width * w
                strokeCap = if (m.highlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = if (m.highlighter) Color(m.color).copy(alpha = 0.38f).toArgb() else m.color
            }
            val path = android.graphics.Path().apply {
                moveTo(m.points[0] * w, m.points[1] * h)
                var i = 2
                while (i + 3 < m.points.size) {
                    val cx = m.points[i] * w; val cy = m.points[i + 1] * h
                    quadTo(cx, cy, (cx + m.points[i + 2] * w) / 2, (cy + m.points[i + 3] * h) / 2)
                    i += 2
                }
                lineTo(m.points[m.points.size - 2] * w, m.points[m.points.size - 1] * h)
            }
            c.drawPath(path, paint)
        }
        is Mark.Note -> {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = m.color; textSize = m.size * w }
            c.drawText(m.text, m.x * w, m.y * h + paint.textSize / 3, paint)
        }
    }
}
