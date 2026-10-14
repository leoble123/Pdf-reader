package app.lumen.reader.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ScanFilter(val label: String) { Original("Original"), Enhance("Enhance"), Gray("Grayscale"), Document("B&W") }

/**
 * Page detection and clean-up for the scanner. Everything is plain CPU work on small buffers,
 * so it runs in tens of milliseconds and needs no ML model or camera library.
 */
object ScanProcessing {

    /** Corners TL, TR, BR, BL in normalized (0..1) image coordinates. */
    fun detect(bitmap: Bitmap): List<Offset> {
        val scale = 360f / max(bitmap.width, bitmap.height)
        val w = max(1, (bitmap.width * scale).roundToInt())
        val h = max(1, (bitmap.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h) { i ->
            val c = px[i]
            ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        val blurred = boxBlur(boxBlur(lum, w, h), w, h)
        val t = otsu(blurred)
        val fallback = listOf(Offset(0.06f, 0.06f), Offset(0.94f, 0.06f), Offset(0.94f, 0.94f), Offset(0.06f, 0.94f))

        // Largest bright connected region = the paper (on a darker background).
        val label = IntArray(w * h)
        var best = 0
        var bestId = 0
        var id = 0
        val queue = IntArray(w * h)
        for (start in 0 until w * h) {
            if (blurred[start] <= t || label[start] != 0) continue
            id++
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = id
            var size = 0
            while (head < tail) {
                val p = queue[head++]
                size++
                val x = p % w
                val y = p / w
                if (x > 0) visit(p - 1, blurred, t, label, id, queue, tail).also { tail = it }
                if (x < w - 1) visit(p + 1, blurred, t, label, id, queue, tail).also { tail = it }
                if (y > 0) visit(p - w, blurred, t, label, id, queue, tail).also { tail = it }
                if (y < h - 1) visit(p + w, blurred, t, label, id, queue, tail).also { tail = it }
            }
            if (size > best) {
                best = size; bestId = id
            }
        }
        val area = best.toFloat() / (w * h)
        if (area < 0.12f || area > 0.985f) return fallback

        var tl = 0; var tr = 0; var br = 0; var bl = 0
        var minSum = Int.MAX_VALUE; var maxSum = Int.MIN_VALUE
        var maxDiff = Int.MIN_VALUE; var minDiff = Int.MAX_VALUE
        for (p in 0 until w * h) {
            if (label[p] != bestId) continue
            val x = p % w
            val y = p / w
            val s = x + y
            val d = x - y
            if (s < minSum) { minSum = s; tl = p }
            if (s > maxSum) { maxSum = s; br = p }
            if (d > maxDiff) { maxDiff = d; tr = p }
            if (d < minDiff) { minDiff = d; bl = p }
        }
        val corners = listOf(tl, tr, br, bl).map { Offset((it % w + 0.5f) / w, (it / w + 0.5f) / h) }
        return if (quadArea(corners) > 0.1f && isConvex(corners)) corners else fallback
    }

    private fun visit(q: Int, img: IntArray, t: Int, label: IntArray, id: Int, queue: IntArray, tail: Int): Int {
        if (img[q] > t && label[q] == 0) {
            label[q] = id
            queue[tail] = q
            return tail + 1
        }
        return tail
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) {
                    sum += src[yy * w + xx]; n++
                }
            }
            out[y * w + x] = sum / n
        }
        return out
    }

    private fun otsu(lum: IntArray): Int {
        val hist = IntArray(256)
        lum.forEach { hist[it.coerceIn(0, 255)]++ }
        val total = lum.size
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 127
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between; threshold = i
            }
        }
        return threshold
    }

    fun quadArea(q: List<Offset>): Float {
        var a = 0f
        for (i in q.indices) {
            val p = q[i]; val n = q[(i + 1) % q.size]
            a += p.x * n.y - n.x * p.y
        }
        return kotlin.math.abs(a) / 2f
    }

    fun isConvex(q: List<Offset>): Boolean {
        var sign = 0
        for (i in q.indices) {
            val a = q[i]; val b = q[(i + 1) % 4]; val c = q[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0) {
                if (sign != 0 && s != sign) return false
                sign = s
            }
        }
        return true
    }

    /** Perspective-corrects the quad into a flat page, long side capped at [maxSide] px. */
    fun warp(src: Bitmap, corners: List<Offset>, maxSide: Int = 2480): Bitmap {
        val p = corners.map { Offset(it.x * src.width, it.y * src.height) }
        fun d(a: Offset, b: Offset) = hypot(a.x - b.x, a.y - b.y)
        var outW = max(d(p[0], p[1]), d(p[3], p[2]))
        var outH = max(d(p[0], p[3]), d(p[1], p[2]))
        val k = min(1f, maxSide / max(outW, outH))
        outW *= k; outH *= k
        val w = max(1, outW.roundToInt())
        val h = max(1, outH.roundToInt())
        val m = Matrix()
        m.setPolyToPoly(
            floatArrayOf(p[0].x, p[0].y, p[1].x, p[1].y, p[2].x, p[2].y, p[3].x, p[3].y), 0,
            floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0, 4
        )
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    fun rotate(src: Bitmap, quarterTurns: Int): Bitmap {
        if (quarterTurns % 4 == 0) return src
        val m = Matrix().apply { postRotate(90f * quarterTurns) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun filter(src: Bitmap, f: ScanFilter): Bitmap {
        if (f == ScanFilter.Original) return src
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        when (f) {
            ScanFilter.Enhance -> { flattenLighting(px, w, h); enhance(px) }
            ScanFilter.Gray -> for (i in px.indices) {
                val l = luma(px[i]); px[i] = gray(l)
            }
            ScanFilter.Document -> document(px, w, h)
            ScanFilter.Original -> Unit
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun luma(c: Int) = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
    private fun gray(l: Int) = (0xFF shl 24) or (l shl 16) or (l shl 8) or l

    /** Per-channel 1%–99% stretch: removes colour casts and greys out the paper to white. */
    private fun enhance(px: IntArray) {
        val lo = IntArray(3)
        val hi = IntArray(3)
        for (ch in 0..2) {
            val hist = IntArray(256)
            val shift = 16 - ch * 8
            for (c in px) hist[c shr shift and 0xFF]++
            var acc = 0
            val n = px.size
            lo[ch] = (0..255).first { acc += hist[it]; acc > n / 100 }
            acc = 0
            hi[ch] = (255 downTo 0).first { acc += hist[it]; acc > n / 100 }
            if (hi[ch] <= lo[ch]) { lo[ch] = 0; hi[ch] = 255 }
        }
        for (i in px.indices) {
            val c = px[i]
            var r = ((c shr 16 and 0xFF) - lo[0]) * 255 / (hi[0] - lo[0])
            var g = ((c shr 8 and 0xFF) - lo[1]) * 255 / (hi[1] - lo[1])
            var b = ((c and 0xFF) - lo[2]) * 255 / (hi[2] - lo[2])
            // gentle saturation lift so ink colours stay vivid after the stretch
            val l = (r * 299 + g * 587 + b * 114) / 1000
            r = (l + (r - l) * 115 / 100).coerceIn(0, 255)
            g = (l + (g - l) * 115 / 100).coerceIn(0, 255)
            b = (l + (b - l) * 115 / 100).coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Removes shadows and lighting gradients while keeping colour: each pixel is divided by the
     * local paper brightness (a wide box mean of luminance, which dark text barely moves).
     */
    private fun flattenLighting(px: IntArray, w: Int, h: Int) {
        val integral = integralOf(IntArray(px.size) { luma(px[it]) }, w, h)
        val r = max(16, max(w, h) / 7)
        for (y in 0 until h) {
            val y0 = max(0, y - r); val y1 = min(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = max(0, x - r); val x1 = min(w - 1, x + r)
                val mean = boxMean(integral, w, x0, y0, x1, y1).coerceAtLeast(24)
                val gain = 235f / mean
                val c = px[y * w + x]
                val rr = ((c shr 16 and 0xFF) * gain).toInt().coerceIn(0, 255)
                val gg = ((c shr 8 and 0xFF) * gain).toInt().coerceIn(0, 255)
                val bb = ((c and 0xFF) * gain).toInt().coerceIn(0, 255)
                px[y * w + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
    }

    private fun integralOf(lum: IntArray, w: Int, h: Int): LongArray {
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += lum[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        return integral
    }

    private fun boxMean(integral: LongArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val count = (x1 - x0 + 1) * (y1 - y0 + 1)
        val sum = integral[(y1 + 1) * (w + 1) + x1 + 1] - integral[y0 * (w + 1) + x1 + 1] -
            integral[(y1 + 1) * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
        return (sum / count).toInt()
    }

    /** Adaptive threshold (local mean via an integral image): crisp text even with uneven light. */
    private fun document(px: IntArray, w: Int, h: Int) {
        val lum = IntArray(px.size) { luma(px[it]) }
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += lum[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        val r = max(8, max(w, h) / 40)
        for (y in 0 until h) {
            val y0 = max(0, y - r); val y1 = min(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = max(0, x - r); val x1 = min(w - 1, x + r)
                val count = (x1 - x0 + 1) * (y1 - y0 + 1)
                val sum = integral[(y1 + 1) * (w + 1) + x1 + 1] - integral[y0 * (w + 1) + x1 + 1] -
                    integral[(y1 + 1) * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                val mean = (sum / count).toInt()
                val v = lum[y * w + x]
                // Soft edge instead of a hard cut keeps strokes anti-aliased.
                val diff = v - (mean - 12)
                val out = when {
                    diff >= 8 -> 255
                    diff <= -8 -> 0
                    else -> ((diff + 8) * 255 / 16)
                }
                px[y * w + x] = gray(out)
            }
        }
    }
}
