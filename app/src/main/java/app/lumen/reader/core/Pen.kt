package app.lumen.reader.core

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Shared pen engine for PDF markup and the photo editor.
 *
 * All geometry is in surface-normalized coordinates (0..1 of width and height). Distances are
 * measured in "width units": y differences are divided by the surface aspect (w / h) so a
 * circle is a circle whatever the page shape.
 */

/** Stylus tip, stylus eraser end and mouse all count as precise pens; fingers don't. */
val PointerInputChange.isPen: Boolean
    get() = type == PointerType.Stylus || type == PointerType.Eraser || type == PointerType.Mouse

/** Flipped stylus (eraser end) or the stylus side button held down. */
fun PointerEvent.wantsEraser(change: PointerInputChange): Boolean =
    change.type == PointerType.Eraser || (change.type == PointerType.Stylus && buttons.isSecondaryPressed)

/**
 * Asks the system to deliver this pointer's events as soon as they arrive instead of batching
 * them to the next frame, which takes roughly a frame of latency off the ink.
 */
fun requestLowLatency(view: View, event: PointerEvent) {
    val motion = event.motionEvent ?: return
    runCatching { view.requestUnbufferedDispatch(motion) }
}

enum class ShapeKind { Line, Rect, Ellipse }

object PenMath {
    /** Maps stylus pressure (0..1) to a width multiplier with a gentle curve. */
    fun pressureWidth(pressure: Float): Float =
        (0.28f + 1.05f * pressure.coerceIn(0f, 1f).pow(0.8f)).coerceIn(0.28f, 1.35f)

    class Snapped(val kind: ShapeKind, val points: FloatArray)

    /**
     * Recognises what a held stroke was meant to be. Closed loops become a rectangle or an
     * ellipse when they fit one well; open strokes become a straight line (start → end, with
     * near-horizontal/vertical/diagonal angles snapped exactly). Returns null for messy closed
     * scribbles so they're left alone.
     */
    fun snap(points: FloatArray, aspect: Float): Snapped? {
        val n = points.size / 2
        if (n < 2) return null
        val ay = 1f / aspect
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var length = 0f
        for (i in 0 until n) {
            val x = points[i * 2]; val y = points[i * 2 + 1] * ay
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            if (i > 0) length += hypot(x - points[i * 2 - 2], y - points[i * 2 - 1] * ay)
        }
        val bw = maxX - minX; val bh = maxY - minY
        val diag = hypot(bw, bh)
        if (diag < 1e-4f) return null
        val sx = points[0]; val sy = points[1] * ay
        val ex = points[n * 2 - 2]; val ey = points[n * 2 - 1] * ay
        val closed = n >= 8 && hypot(ex - sx, ey - sy) < 0.25f * diag && length > 1.8f * diag &&
            min(bw, bh) > 0.12f * max(bw, bh)
        if (!closed) return Snapped(ShapeKind.Line, line(points[0], points[1], points[n * 2 - 2], points[n * 2 - 1], aspect))

        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        val rx = bw / 2; val ry = bh / 2
        val rMin = min(rx, ry)
        var ellipseErr = 0f; var rectErr = 0f
        for (i in 0 until n) {
            val x = points[i * 2]; val y = points[i * 2 + 1] * ay
            ellipseErr += abs(hypot((x - cx) / rx, (y - cy) / ry) - 1f)
            rectErr += min(min(abs(x - minX), abs(x - maxX)), min(abs(y - minY), abs(y - maxY))) / rMin
        }
        ellipseErr /= n; rectErr /= n
        return when {
            rectErr < 0.16f && rectErr < ellipseErr * 0.9f -> Snapped(ShapeKind.Rect, rect(minX, minY / ay, maxX, maxY / ay))
            ellipseErr < 0.2f -> Snapped(ShapeKind.Ellipse, ellipse(cx, cy / ay, rx, ry / ay))
            else -> null
        }
    }

    /** A two-point line; angles within 4° of a multiple of 45° are made exact. */
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, aspect: Float): FloatArray {
        val ay = 1f / aspect
        val dx = x1 - x0; val dy = (y1 - y0) * ay
        val len = hypot(dx, dy)
        val angle = atan2(dy, dx)
        val step = (Math.PI / 4).toFloat()
        val snapped = (angle / step).roundToInt() * step
        if (len > 0f && abs(angle - snapped) < Math.toRadians(4.0).toFloat()) {
            return floatArrayOf(x0, y0, x0 + cos(snapped) * len, y0 + sin(snapped) * len / ay)
        }
        return floatArrayOf(x0, y0, x1, y1)
    }

    /** Corners are doubled so midpoint smoothing keeps them sharp. */
    fun rect(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(
        l, t, l, t, r, t, r, t, r, b, r, b, l, b, l, b, l, t, l, t
    )

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, segments: Int = 64): FloatArray {
        val out = FloatArray((segments + 1) * 2)
        for (i in 0..segments) {
            val a = (i.toDouble() / segments * 2 * Math.PI) - Math.PI / 2
            out[i * 2] = cx + (cos(a) * rx).toFloat()
            out[i * 2 + 1] = cy + (sin(a) * ry).toFloat()
        }
        return out
    }

    fun pointInPolygon(x: Float, y: Float, poly: FloatArray): Boolean {
        var inside = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]; val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** True if any part of the polyline passes within [radius] (width units) of (x, y). */
    fun touches(points: FloatArray, x: Float, y: Float, radius: Float, aspect: Float): Boolean {
        val ay = 1f / aspect
        val n = points.size / 2
        if (n == 1) return hypot(points[0] - x, (points[1] - y) * ay) < radius
        for (i in 1 until n) {
            if (segmentDistance(x, y * ay, points[i * 2 - 2], points[i * 2 - 1] * ay, points[i * 2], points[i * 2 + 1] * ay) < radius) return true
        }
        return false
    }

    private fun segmentDistance(px: Float, py: Float, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0; val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - x0) * dx + (py - y0) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (x0 + t * dx), py - (y0 + t * dy))
    }

    class Piece(val points: FloatArray, val widths: FloatArray?)

    /**
     * Cuts the part of a stroke under a round eraser out of it. Returns null when the stroke
     * isn't touched, otherwise the surviving pieces (possibly none).
     */
    fun eraseFrom(points: FloatArray, widths: FloatArray?, x: Float, y: Float, radius: Float, aspect: Float): List<Piece>? {
        if (!touches(points, x, y, radius, aspect)) return null
        // Densify first so long straight segments (snapped lines, shapes) can be cut mid-way.
        val step = radius / 2
        val ay = 1f / aspect
        val px = ArrayList<Float>(points.size); val pw = if (widths != null) ArrayList<Float>(points.size / 2) else null
        val n = points.size / 2
        for (i in 0 until n) {
            if (i > 0) {
                val x0 = points[i * 2 - 2]; val y0 = points[i * 2 - 1]
                val x1 = points[i * 2]; val y1 = points[i * 2 + 1]
                val parts = (hypot(x1 - x0, (y1 - y0) * ay) / step).toInt()
                for (k in 1 until parts) {
                    val t = k.toFloat() / parts
                    px += x0 + (x1 - x0) * t; px += y0 + (y1 - y0) * t
                    pw?.add(widths!![i - 1] + (widths[i] - widths[i - 1]) * t)
                }
            }
            px += points[i * 2]; px += points[i * 2 + 1]
            pw?.add(widths!![i])
        }
        val pieces = ArrayList<Piece>()
        var start = -1
        val m = px.size / 2
        fun flush(end: Int) {
            if (start >= 0 && end - start >= 2) {
                val pts = FloatArray((end - start) * 2) { px[start * 2 + it] }
                pieces += Piece(pts, pw?.let { w -> FloatArray(end - start) { w[start + it] } })
            }
            start = -1
        }
        for (i in 0 until m) {
            val inside = hypot(px[i * 2] - x, (px[i * 2 + 1] - y) * ay) < radius
            if (inside) flush(i) else if (start < 0) start = i
        }
        flush(m)
        return pieces
    }

    /**
     * Draws a stroke whose width varies per point (pressure) as short round-capped segments.
     * [widths] multiplies [baseWidth]; when null the whole stroke is one smoothed path.
     */
    fun drawVariable(canvas: Canvas, points: FloatArray, widths: FloatArray, w: Float, h: Float, baseWidth: Float, paint: Paint) {
        val n = points.size / 2
        if (n == 1) {
            val style = paint.style
            paint.style = Paint.Style.FILL
            canvas.drawCircle(points[0] * w, points[1] * h, baseWidth * widths[0] / 2, paint)
            paint.style = style
            return
        }
        for (i in 1 until n) {
            paint.strokeWidth = baseWidth * (widths[i - 1] + widths[i]) / 2
            canvas.drawLine(points[i * 2 - 2] * w, points[i * 2 - 1] * h, points[i * 2] * w, points[i * 2 + 1] * h, paint)
        }
    }

    /** Midpoint-quadratic path through the points, in pixels. */
    fun path(points: FloatArray, w: Float, h: Float, into: android.graphics.Path = android.graphics.Path()): android.graphics.Path {
        into.reset()
        val n = points.size / 2
        into.moveTo(points[0] * w, points[1] * h)
        if (n == 1) {
            into.lineTo(points[0] * w + 0.01f, points[1] * h); return into
        }
        for (i in 1 until n - 1) {
            val cx = points[i * 2] * w; val cy = points[i * 2 + 1] * h
            into.quadTo(cx, cy, (cx + points[i * 2 + 2] * w) / 2, (cy + points[i * 2 + 3] * h) / 2)
        }
        into.lineTo(points[n * 2 - 2] * w, points[n * 2 - 1] * h)
        return into
    }
}

/**
 * A stroke being drawn right now. Points are appended in place (no copying per sample) and the
 * on-screen path is extended incrementally, so drawing cost per frame stays flat however long
 * the stroke gets.
 */
class PenStroke(val aspect: Float, val pressure: Boolean) {
    private var pts = FloatArray(512)
    private var wid = FloatArray(256)
    var size = 0 // number of points
        private set
    var shape: ShapeKind? = null
        private set
    private var smoothedPressure = -1f

    /** Where the pen is predicted to be a frame from now (NaN = no prediction). Drawn, never saved. */
    var predictedX = Float.NaN
    var predictedY = Float.NaN

    private val path = android.graphics.Path()
    private var built = 0
    private var builtW = 0f
    private var builtH = 0f

    fun x(i: Int) = pts[i * 2]
    fun y(i: Int) = pts[i * 2 + 1]

    /** Adds a sample; returns false if it was too close to the last one to matter. */
    fun add(x: Float, y: Float, pressure: Float = 1f, minDistance: Float = 0.0012f): Boolean {
        val shape = shape
        if (shape == ShapeKind.Line) {
            // After a hold-to-straighten the pen drags the line's end around.
            val l = PenMath.line(pts[0], pts[1], x, y, aspect)
            pts[2] = l[2]; pts[3] = l[3]
            invalidatePath()
            return true
        }
        if (shape != null) return false
        if (size > 0 && hypot(x - pts[size * 2 - 2], (y - pts[size * 2 - 1]) / aspect) < minDistance) {
            // Still let pressure changes through so a pressed-down dot grows.
            if (this.pressure) wid[size - 1] = max(wid[size - 1], PenMath.pressureWidth(pressure))
            return false
        }
        if (size * 2 + 2 > pts.size) pts = pts.copyOf(pts.size * 2)
        if (size + 1 > wid.size) wid = wid.copyOf(wid.size * 2)
        pts[size * 2] = x; pts[size * 2 + 1] = y
        // Light smoothing so pressure noise doesn't make the line wobble in thickness.
        smoothedPressure = if (smoothedPressure < 0) pressure else smoothedPressure * 0.55f + pressure * 0.45f
        wid[size] = if (this.pressure) PenMath.pressureWidth(smoothedPressure) else 1f
        size++
        return true
    }

    /** Replaces the freehand points with the recognised shape. Returns true if it snapped. */
    fun snap(): Boolean {
        if (shape != null || size < 2) return false
        val s = PenMath.snap(points(), aspect) ?: return false
        val avg = if (pressure) (0 until size).sumOf { wid[it].toDouble() }.toFloat() / size else 1f
        pts = s.points.copyOf(max(s.points.size, 8))
        size = s.points.size / 2
        if (wid.size < size) wid = FloatArray(size)
        for (i in 0 until size) wid[i] = avg
        shape = s.kind
        invalidatePath()
        return true
    }

    fun points(): FloatArray = pts.copyOf(size * 2)

    /** Per-point width multipliers, or null for an even stroke. */
    fun widths(): FloatArray? = if (pressure && shape == null) wid.copyOf(size) else null

    /** Width multiplier to bake into the stroke when it's drawn evenly (snapped shapes). */
    fun evenWidth(): Float = if (pressure && shape != null && size > 0) wid[0] else 1f

    private fun invalidatePath() {
        built = 0
    }

    /** Draws the stroke at surface size [w]×[h] px. */
    fun draw(canvas: Canvas, w: Float, h: Float, baseWidth: Float, paint: Paint) {
        if (size == 0) return
        if (pressure && shape == null) {
            PenMath.drawVariable(canvas, pts, wid, w, h, baseWidth, paint.apply { strokeCap = Paint.Cap.ROUND })
            drawPrediction(canvas, w, h, baseWidth * wid[size - 1], paint)
            return
        }
        paint.strokeWidth = baseWidth * evenWidth()
        if (size == 1) {
            canvas.drawPoint(pts[0] * w, pts[1] * h, paint); return
        }
        if (built == 0 || w != builtW || h != builtH) {
            path.reset(); path.moveTo(pts[0] * w, pts[1] * h)
            built = 1; builtW = w; builtH = h
        }
        // Each new point j+1 finalises the curve through point j.
        while (built < size - 1) {
            val cx = pts[built * 2] * w; val cy = pts[built * 2 + 1] * h
            path.quadTo(cx, cy, (cx + pts[built * 2 + 2] * w) / 2, (cy + pts[built * 2 + 3] * h) / 2)
            built++
        }
        canvas.drawPath(path, paint)
        // The open tail from the last midpoint to the pen tip.
        val i = size - 1
        if (size >= 2) {
            val mx = if (size == 2) pts[0] * w else (pts[i * 2 - 2] * w + pts[i * 2] * w) / 2
            val my = if (size == 2) pts[1] * h else (pts[i * 2 - 1] * h + pts[i * 2 + 1] * h) / 2
            canvas.drawLine(mx, my, pts[i * 2] * w, pts[i * 2 + 1] * h, paint)
        }
        if (shape == null) drawPrediction(canvas, w, h, paint.strokeWidth, paint)
    }

    /** Extends the ink to where the pen is about to be, hiding most of the touch-to-screen lag. */
    private fun drawPrediction(canvas: Canvas, w: Float, h: Float, width: Float, paint: Paint) {
        if (predictedX.isNaN() || size == 0) return
        paint.strokeWidth = width
        canvas.drawLine(pts[size * 2 - 2] * w, pts[size * 2 - 1] * h, predictedX * w, predictedY * h, paint)
    }
}

/**
 * Recognises a quick multi-finger tap: every finger lands within [landWindow] of the first
 * (a resting palm creeps in over time, deliberate taps don't), nothing moves, and it's all
 * over within the duration passed to [fingers]. Feed it every event of a gesture.
 */
class MultiTap(private val startMillis: Long, private val slop: Float, private val landWindow: Long = 150) {
    private var maxPointers = 1
    private var invalid = false
    private val origins = HashMap<Long, androidx.compose.ui.geometry.Offset>()
    private var lastMillis = startMillis

    fun track(event: PointerEvent) {
        val pressed = event.changes.count { it.pressed }
        maxPointers = max(maxPointers, pressed)
        for (c in event.changes) {
            val key = c.id.value
            val origin = origins[key]
            if (origin == null) {
                origins[key] = c.position
                if (c.pressed && c.uptimeMillis - startMillis > landWindow) invalid = true
            } else if ((c.position - origin).getDistance() > slop) invalid = true
            lastMillis = max(lastMillis, c.uptimeMillis)
        }
    }

    /** Rules the gesture out (e.g. a palm was detected). */
    fun reject() {
        invalid = true
    }

    /** Number of fingers in the tap, or 0 if this wasn't a multi-finger tap. */
    fun fingers(maxDuration: Long = 300): Int =
        if (!invalid && maxPointers >= 2 && lastMillis - startMillis <= maxDuration) maxPointers else 0
}

/**
 * One-euro filter (Casiez et al.) in screen pixels: smooths hard when the pen moves slowly,
 * where jitter shows, and barely at all when it moves fast, where lag would show.
 */
class OneEuroFilter(private val minCutoff: Float = 4f, private val beta: Float = 0.03f, private val dCutoff: Float = 1.5f) {
    private var x = 0f
    private var y = 0f
    private var dx = 0f
    private var dy = 0f
    private var last = -1L

    private fun alpha(cutoff: Float, te: Float): Float {
        val tau = 1f / (2f * Math.PI.toFloat() * cutoff)
        return 1f / (1f + tau / te)
    }

    fun filter(px: Float, py: Float, timeMillis: Long): androidx.compose.ui.geometry.Offset {
        if (last < 0) {
            x = px; y = py; last = timeMillis
            return androidx.compose.ui.geometry.Offset(px, py)
        }
        // Stylus samples can share a millisecond; treat them as ~4 ms apart.
        val te = max(timeMillis - last, 4L) / 1000f
        last = timeMillis
        val ad = alpha(dCutoff, te)
        dx += ad * ((px - x) / te - dx)
        dy += ad * ((py - y) / te - dy)
        val a = alpha(minCutoff + beta * hypot(dx, dy), te)
        x += a * (px - x)
        y += a * (py - y)
        return androidx.compose.ui.geometry.Offset(x, y)
    }
}

/**
 * Palm rejection that complements the system's: while the pen hovers or was just lifted,
 * stray touches are almost always the writing hand, and contacts as big as a palm never count.
 */
class PalmGuard(private val palmPx: Float) {
    var hovering = false
        private set
    private var lastPen = Long.MIN_VALUE / 2

    /** Feed every pen event (hover or contact). */
    fun notePen(hover: Boolean, now: Long) {
        hovering = hover
        lastPen = now
    }

    fun penGone() {
        hovering = false
    }

    /** True if a finger touch starting at [now] should be treated as a resting hand. */
    fun penNear(now: Long) = hovering || now - lastPen < 600

    fun isPalmSized(event: PointerEvent): Boolean {
        val me = event.motionEvent ?: return false
        if (me.flags and MotionEvent.FLAG_CANCELED != 0) return true
        for (i in 0 until me.pointerCount) {
            if (me.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER && max(me.getTouchMajor(i), me.getToolMajor(i)) > palmPx) return true
        }
        return false
    }
}

/** About 14 mm: wider than any fingertip contact, narrower than a resting palm. */
fun palmPx(context: android.content.Context): Float {
    val m = context.resources.displayMetrics
    val dpi = if (m.xdpi > 100f) m.xdpi else m.densityDpi.toFloat()
    return 14f / 25.4f * dpi
}

/**
 * True when a gesture ended because the system cancelled it (it decided the touch was a palm,
 * or another window took over) rather than because the pointer lifted.
 */
fun PointerEvent.isCancellation(): Boolean {
    val me = motionEvent ?: return true // Compose synthesises a bare event when input is cancelled
    return me.actionMasked == MotionEvent.ACTION_CANCEL || me.flags and MotionEvent.FLAG_CANCELED != 0
}

/**
 * Wraps androidx.input motion prediction. Feed it the stroke's events and it reports where the
 * pen will be about a frame later, in the same local coordinates as the pointer change.
 */
class PenPredictor(view: View) {
    private val predictor = runCatching { androidx.input.motionprediction.MotionEventPredictor.newInstance(view) }.getOrNull()

    fun record(event: PointerEvent) {
        val me = event.motionEvent ?: return
        runCatching { predictor?.record(me) }
    }

    fun predict(change: PointerInputChange, event: PointerEvent): androidx.compose.ui.geometry.Offset? {
        val me = event.motionEvent ?: return null
        val p = runCatching { predictor?.predict() }.getOrNull() ?: return null
        try {
            // Map from the root view's coordinates to the node's via the live sample's offset.
            val idx = (0 until me.pointerCount).firstOrNull {
                me.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || me.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
            } ?: return null
            val pIdx = (0 until p.pointerCount).firstOrNull { p.getPointerId(it) == me.getPointerId(idx) } ?: 0
            val ox = change.position.x - me.getX(idx)
            val oy = change.position.y - me.getY(idx)
            return androidx.compose.ui.geometry.Offset(p.getX(pIdx) + ox, p.getY(pIdx) + oy)
        } finally {
            p.recycle()
        }
    }
}
