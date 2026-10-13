package app.lumen.reader.edit

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A colour look shared by the photo and video editors.
 *
 * The same math exists three times: an AGSL shader (live photo preview on Android 13+),
 * a CPU loop (full-resolution photo export, and preview on older devices), and a linear
 * 4×5 matrix (video, via Media3's RgbMatrix). Keep them in sync when changing anything here.
 */
@Immutable
data class Look(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val fade: Float = 0f,
    val vignette: Float = 0f,
    val filter: FilterPreset = FilterPreset.None,
    val filterStrength: Float = 1f
) {
    val isIdentity get() = this == Look()

    fun get(p: Adjustment): Float = when (p) {
        Adjustment.Exposure -> exposure
        Adjustment.Contrast -> contrast
        Adjustment.Highlights -> highlights
        Adjustment.Shadows -> shadows
        Adjustment.Saturation -> saturation
        Adjustment.Warmth -> warmth
        Adjustment.Tint -> tint
        Adjustment.Fade -> fade
        Adjustment.Vignette -> vignette
    }

    fun with(p: Adjustment, v: Float): Look = when (p) {
        Adjustment.Exposure -> copy(exposure = v)
        Adjustment.Contrast -> copy(contrast = v)
        Adjustment.Highlights -> copy(highlights = v)
        Adjustment.Shadows -> copy(shadows = v)
        Adjustment.Saturation -> copy(saturation = v)
        Adjustment.Warmth -> copy(warmth = v)
        Adjustment.Tint -> copy(tint = v)
        Adjustment.Fade -> copy(fade = v)
        Adjustment.Vignette -> copy(vignette = v)
    }

    /** Filter matrix blended with identity by strength: 3 rows of (r, g, b, offset). */
    fun filterRows(): FloatArray {
        val f = filter.rows
        val s = filterStrength
        val id = FilterPreset.None.rows
        return FloatArray(12) { id[it] + (f[it] - id[it]) * s }
    }

    /**
     * The linear part of the look as an Android 4×5 ColorMatrix (values in 0..255 offsets).
     * Highlights, shadows and vignette are non-linear and are left out.
     */
    fun linearMatrix(): FloatArray {
        // Each stage is a 3×4 affine map on 0..1 RGB; compose them in order.
        var m = filterRows()
        val e = 2f.pow(exposure * 1.5f)
        m = compose(floatArrayOf(e, 0f, 0f, 0f, 0f, e, 0f, 0f, 0f, 0f, e, 0f), m)
        m = compose(floatArrayOf(1 + warmth * 0.15f, 0f, 0f, 0f, 0f, 1 + tint * 0.1f, 0f, 0f, 0f, 0f, 1 - warmth * 0.15f, 0f), m)
        val c = 1 + contrast
        val co = 0.5f * (1 - c)
        m = compose(floatArrayOf(c, 0f, 0f, co, 0f, c, 0f, co, 0f, 0f, c, co), m)
        val s = 1 + saturation
        val lr = 0.2126f * (1 - s); val lg = 0.7152f * (1 - s); val lb = 0.0722f * (1 - s)
        m = compose(floatArrayOf(lr + s, lg, lb, 0f, lr, lg + s, lb, 0f, lr, lg, lb + s, 0f), m)
        val fa = 1 - fade * 0.2f
        m = compose(floatArrayOf(fa, 0f, 0f, fade * 0.12f, 0f, fa, 0f, fade * 0.12f, 0f, 0f, fa, fade * 0.12f), m)
        return floatArrayOf(
            m[0], m[1], m[2], 0f, m[3] * 255f,
            m[4], m[5], m[6], 0f, m[7] * 255f,
            m[8], m[9], m[10], 0f, m[11] * 255f,
            0f, 0f, 0f, 1f, 0f
        )
    }

    /** Column-major 4×4 for Media3's RgbMatrix; the offset rides in the alpha column (video alpha is 1). */
    fun videoMatrix(): FloatArray {
        val a = linearMatrix()
        return floatArrayOf(
            a[0], a[5], a[10], 0f,
            a[1], a[6], a[11], 0f,
            a[2], a[7], a[12], 0f,
            a[4] / 255f, a[9] / 255f, a[14] / 255f, 1f
        )
    }

    /** CPU implementation — must match [SHADER]. Works in place on ARGB pixels. */
    suspend fun applyTo(bitmap: Bitmap) = coroutineScope {
        val w = bitmap.width
        val h = bitmap.height
        val f = filterRows()
        val e = 2f.pow(exposure * 1.5f)
        val bands = max(1, min(8, Runtime.getRuntime().availableProcessors()))
        val rowsPer = (h + bands - 1) / bands
        (0 until bands).map { band ->
            async(Dispatchers.Default) {
                val y0 = band * rowsPer
                val y1 = min(h, y0 + rowsPer)
                if (y0 >= y1) return@async
                val px = IntArray(w * (y1 - y0))
                synchronized(bitmap) { bitmap.getPixels(px, 0, w, 0, y0, w, y1 - y0) }
                for (y in y0 until y1) for (x in 0 until w) {
                    val i = (y - y0) * w + x
                    val c = px[i]
                    val a = c ushr 24
                    var r = ((c shr 16) and 0xFF) / 255f
                    var g = ((c shr 8) and 0xFF) / 255f
                    var b = (c and 0xFF) / 255f
                    // filter
                    val fr = f[0] * r + f[1] * g + f[2] * b + f[3]
                    val fg = f[4] * r + f[5] * g + f[6] * b + f[7]
                    val fb = f[8] * r + f[9] * g + f[10] * b + f[11]
                    r = fr * e; g = fg * e; b = fb * e
                    r *= 1 + warmth * 0.15f; g *= 1 + tint * 0.1f; b *= 1 - warmth * 0.15f
                    val ct = 1 + contrast
                    r = (r - 0.5f) * ct + 0.5f; g = (g - 0.5f) * ct + 0.5f; b = (b - 0.5f) * ct + 0.5f
                    if (highlights != 0f || shadows != 0f) {
                        val l = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                        val lift = shadows * 0.35f * (1 - l) * (1 - l) + highlights * 0.35f * l * l
                        r += lift; g += lift; b += lift
                    }
                    val l2 = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val st = 1 + saturation
                    r = l2 + (r - l2) * st; g = l2 + (g - l2) * st; b = l2 + (b - l2) * st
                    val fa = 1 - fade * 0.2f
                    r = r * fa + fade * 0.12f; g = g * fa + fade * 0.12f; b = b * fa + fade * 0.12f
                    if (vignette != 0f) {
                        val dx = x / w.toFloat() - 0.5f
                        val dy = y / h.toFloat() - 0.5f
                        val d = sqrt(dx * dx + dy * dy) * 1.4142f
                        val k = 1 - vignette * 0.8f * smoothstep(0.3f, 1f, d)
                        r *= k; g *= k; b *= k
                    }
                    px[i] = (a shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
                }
                synchronized(bitmap) { bitmap.setPixels(px, 0, w, 0, y0, w, y1 - y0) }
            }
        }.awaitAll()
    }

    @RequiresApi(33)
    fun setUniforms(shader: RuntimeShader, width: Float, height: Float) {
        val f = filterRows()
        shader.setFloatUniform("fr", f[0], f[1], f[2], f[3])
        shader.setFloatUniform("fg", f[4], f[5], f[6], f[7])
        shader.setFloatUniform("fb", f[8], f[9], f[10], f[11])
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("exposure", 2f.pow(exposure * 1.5f))
        shader.setFloatUniform("contrast", contrast)
        shader.setFloatUniform("highlights", highlights)
        shader.setFloatUniform("shadows", shadows)
        shader.setFloatUniform("saturation", saturation)
        shader.setFloatUniform("warmth", warmth)
        shader.setFloatUniform("tint", tint)
        shader.setFloatUniform("fade", fade)
        shader.setFloatUniform("vignette", vignette)
    }

    companion object {
        val supportsShader = Build.VERSION.SDK_INT >= 33

        const val SHADER = """
uniform shader image;
uniform float2 size;
uniform float4 fr;
uniform float4 fg;
uniform float4 fb;
uniform float exposure;
uniform float contrast;
uniform float highlights;
uniform float shadows;
uniform float saturation;
uniform float warmth;
uniform float tint;
uniform float fade;
uniform float vignette;

half4 main(float2 p) {
    half4 src = image.eval(p);
    float a = src.a;
    if (a <= 0.0) return src;
    float3 c = float3(src.rgb) / a;
    c = float3(dot(fr.rgb, c) + fr.a, dot(fg.rgb, c) + fg.a, dot(fb.rgb, c) + fb.a);
    c *= exposure;
    c *= float3(1.0 + warmth * 0.15, 1.0 + tint * 0.1, 1.0 - warmth * 0.15);
    c = (c - 0.5) * (1.0 + contrast) + 0.5;
    float l = clamp(dot(c, float3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
    c += shadows * 0.35 * (1.0 - l) * (1.0 - l) + highlights * 0.35 * l * l;
    float l2 = dot(c, float3(0.2126, 0.7152, 0.0722));
    c = l2 + (c - l2) * (1.0 + saturation);
    c = c * (1.0 - fade * 0.2) + fade * 0.12;
    float2 uv = p / size - 0.5;
    float d = length(uv) * 1.4142;
    c *= 1.0 - vignette * 0.8 * smoothstep(0.3, 1.0, d);
    return half4(half3(clamp(c, 0.0, 1.0) * a), half(a));
}
"""

        private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }

        private fun to8(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

        /** a ∘ b for 3×4 affine colour maps (apply b first). */
        private fun compose(a: FloatArray, b: FloatArray): FloatArray {
            val out = FloatArray(12)
            for (r in 0..2) {
                for (c in 0..2) {
                    out[r * 4 + c] = a[r * 4] * b[c] + a[r * 4 + 1] * b[4 + c] + a[r * 4 + 2] * b[8 + c]
                }
                out[r * 4 + 3] = a[r * 4] * b[3] + a[r * 4 + 1] * b[7] + a[r * 4 + 2] * b[11] + a[r * 4 + 3]
            }
            return out
        }
    }
}

enum class Adjustment(val label: String, val bipolar: Boolean = true) {
    Exposure("Exposure"),
    Contrast("Contrast"),
    Highlights("Highlights"),
    Shadows("Shadows"),
    Saturation("Saturation"),
    Warmth("Warmth"),
    Tint("Tint"),
    Fade("Fade", bipolar = false),
    Vignette("Vignette", bipolar = false)
}

/** Colour presets as 3×4 affine maps (r, g, b, offset per output channel). */
enum class FilterPreset(val label: String, val rows: FloatArray) {
    None("Original", floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)),
    Vivid("Vivid", floatArrayOf(1.25f, -0.15f, -0.1f, -0.01f, -0.08f, 1.2f, -0.12f, -0.01f, -0.08f, -0.15f, 1.23f, -0.01f)),
    Warm("Golden", floatArrayOf(1.08f, 0.04f, 0f, 0.03f, 0.02f, 1.02f, 0f, 0.015f, 0f, 0f, 0.88f, -0.01f)),
    Cool("Arctic", floatArrayOf(0.9f, 0f, 0.02f, 0f, 0f, 1.0f, 0.04f, 0.01f, 0.02f, 0.04f, 1.1f, 0.03f)),
    Teal("Cinema", floatArrayOf(1.12f, 0.02f, -0.08f, 0.0f, -0.04f, 1.04f, 0.02f, 0.0f, -0.1f, 0.12f, 1.02f, 0.03f)),
    Film("Film", floatArrayOf(0.88f, 0.1f, 0.02f, 0.05f, 0.05f, 0.86f, 0.04f, 0.05f, 0.04f, 0.08f, 0.8f, 0.07f)),
    Vintage("Vintage", floatArrayOf(0.9f, 0.18f, 0.02f, 0.04f, 0.1f, 0.82f, 0.04f, 0.03f, 0.1f, 0.12f, 0.6f, 0.04f)),
    Sepia("Sepia", floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0.272f, 0.534f, 0.131f, 0f)),
    Mono("Mono", floatArrayOf(0.2126f, 0.7152f, 0.0722f, 0f, 0.2126f, 0.7152f, 0.0722f, 0f, 0.2126f, 0.7152f, 0.0722f, 0f)),
    Noir("Noir", floatArrayOf(0.32f, 1.07f, 0.11f, -0.25f, 0.32f, 1.07f, 0.11f, -0.25f, 0.32f, 1.07f, 0.11f, -0.25f)),
    Dream("Dream", floatArrayOf(0.95f, 0.05f, 0.08f, 0.05f, 0.03f, 0.92f, 0.08f, 0.05f, 0.08f, 0.05f, 0.98f, 0.08f))
}
