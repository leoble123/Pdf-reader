package app.lumen.reader.edit.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import app.lumen.reader.core.PenMath
import app.lumen.reader.edit.Look
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A freehand stroke in output (cropped) space, normalized to 0..1. [widths] holds per-point
 * pressure multipliers of [width] for stylus strokes.
 */
@Immutable
class BrushStroke(val points: FloatArray, val color: Int, val width: Float, val widths: FloatArray? = null)

@Immutable
data class TextLayer(
    val id: Long,
    val text: String,
    val x: Float,
    val y: Float,
    val size: Float,
    val color: Int,
    val background: Boolean
)

enum class AspectRatio(val label: String, val ratio: Float?) {
    Free("Free", null), Original("Original", -1f), Square("1:1", 1f), Portrait("4:5", 0.8f),
    Story("9:16", 9f / 16f), Wide("16:9", 16f / 9f), Classic("3:2", 1.5f)
}

/**
 * The full, undoable description of a photo edit. Nothing here touches pixels; the same state
 * renders the live preview from a small proxy and the export from the full-resolution image.
 */
@Immutable
data class PhotoEdit(
    val look: Look = Look(),
    val rotation: Int = 0,          // quarter turns clockwise
    val flipH: Boolean = false,
    val straighten: Float = 0f,     // degrees, -45..45
    val crop: Rect = Rect(0f, 0f, 1f, 1f), // normalized to the oriented frame
    val aspect: AspectRatio = AspectRatio.Free,
    val strokes: List<BrushStroke> = emptyList(),
    val texts: List<TextLayer> = emptyList()
) {
    /** Size of the image after rotate/flip, before cropping. */
    fun orientedSize(w: Int, h: Int): Pair<Int, Int> = if (rotation % 2 == 0) w to h else h to w

    fun outputSize(w: Int, h: Int): Pair<Int, Int> {
        val (ow, oh) = orientedSize(w, h)
        return max(1, (ow * crop.width).roundToInt()) to max(1, (oh * crop.height).roundToInt())
    }

    /**
     * Maps source pixels to output pixels. With [cropped] = false the result covers the whole
     * oriented frame (used while adjusting the crop box).
     */
    fun matrix(w: Int, h: Int, cropped: Boolean = true): Matrix {
        val (ow, oh) = orientedSize(w, h)
        val m = Matrix()
        // Orient around the source centre, then move into the oriented frame.
        m.postTranslate(-w / 2f, -h / 2f)
        if (flipH) m.postScale(-1f, 1f)
        m.postRotate(rotation * 90f)
        // Straighten: rotate and zoom just enough that no empty corners show.
        if (straighten != 0f) {
            val a = Math.toRadians(abs(straighten).toDouble())
            val ratio = max(ow, oh).toFloat() / min(ow, oh)
            val k = (cos(a) + sin(a) * ratio).toFloat()
            m.postRotate(straighten)
            m.postScale(k, k)
        }
        m.postTranslate(ow / 2f, oh / 2f)
        if (cropped) m.postTranslate(-crop.left * ow, -crop.top * oh)
        return m
    }

    /** Renders geometry + overlays (no colour) onto a new bitmap of the output size. */
    fun renderGeometry(source: Bitmap): Bitmap {
        val (outW, outH) = outputSize(source.width, source.height)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, matrix(source.width, source.height), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    fun drawOverlays(bitmap: Bitmap) {
        val c = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        drawStrokes(c, strokes, w, h)
        drawTexts(c, texts, w, h)
    }

    companion object {
        fun drawStrokes(c: Canvas, strokes: List<BrushStroke>, w: Float, h: Float) {
            val ref = min(w, h)
            for (s in strokes) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = s.width * ref
                    color = s.color
                }
                val widths = s.widths
                if (widths != null) PenMath.drawVariable(c, s.points, widths, w, h, s.width * ref, paint)
                else c.drawPath(PenMath.path(s.points, w, h), paint)
            }
        }

        fun drawTexts(c: Canvas, texts: List<TextLayer>, w: Float, h: Float) {
            val ref = min(w, h)
            for (t in texts) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = t.size * ref
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                    color = t.color
                }
                val lines = t.text.split('\n')
                val lineH = paint.fontSpacing
                val totalH = lineH * lines.size
                var y = t.y * h - totalH / 2 - paint.ascent()
                if (t.background) {
                    val width = lines.maxOf { paint.measureText(it) }
                    val pad = paint.textSize * 0.35f
                    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = if (androidx.core.graphics.ColorUtils.calculateLuminance(t.color) > 0.5) 0xCC000000.toInt() else 0xEEFFFFFF.toInt()
                    }
                    c.drawRoundRect(
                        t.x * w - width / 2 - pad, t.y * h - totalH / 2 - pad * 0.6f,
                        t.x * w + width / 2 + pad, t.y * h + totalH / 2 + pad * 0.6f,
                        pad, pad, bg
                    )
                } else {
                    paint.setShadowLayer(paint.textSize * 0.08f, 0f, paint.textSize * 0.03f, 0x66000000)
                }
                for (line in lines) {
                    c.drawText(line, t.x * w, y, paint)
                    y += lineH
                }
            }
        }
    }
}
