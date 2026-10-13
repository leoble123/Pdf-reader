package app.lumen.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.core.net.toUri
import app.lumen.reader.edit.FilterPreset
import app.lumen.reader.edit.Look
import app.lumen.reader.edit.photo.PhotoEdit
import app.lumen.reader.edit.video.Clip
import app.lumen.reader.edit.video.CompositionBuilder
import app.lumen.reader.edit.video.Music
import app.lumen.reader.edit.video.Ratio
import app.lumen.reader.edit.video.VideoProject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StudioTest {

    private fun swatch(): Bitmap {
        val b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        for (y in 0 until 16) for (x in 0 until 16) {
            b.setPixel(x, y, android.graphics.Color.rgb(x * 16, y * 16, (x + y) * 8))
        }
        return b
    }

    /** The CPU path (photo export) and the colour matrix (video, old-device preview) must agree. */
    @Test
    fun linearLookPathsAgree() = runBlocking {
        val look = Look(exposure = 0.3f, contrast = 0.25f, saturation = -0.4f, warmth = 0.5f, tint = -0.3f, fade = 0.4f, filter = FilterPreset.Teal, filterStrength = 0.7f)
        val cpu = swatch().copy(Bitmap.Config.ARGB_8888, true)
        look.applyTo(cpu)
        val gpu = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        Canvas(gpu).drawBitmap(swatch(), 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(look.linearMatrix()) })
        var worst = 0
        for (y in 0 until 16) for (x in 0 until 16) {
            val a = cpu.getPixel(x, y); val b = gpu.getPixel(x, y)
            for (sh in intArrayOf(16, 8, 0)) worst = maxOf(worst, abs(((a shr sh) and 0xFF) - ((b shr sh) and 0xFF)))
        }
        assertTrue("max channel difference $worst", worst <= 3)
    }

    @Test
    fun identityLookIsNoOp() = runBlocking {
        val b = swatch().copy(Bitmap.Config.ARGB_8888, true)
        Look().applyTo(b)
        for (y in 0 until 16) for (x in 0 until 16) assertEquals(swatch().getPixel(x, y), b.getPixel(x, y))
    }

    @Test
    fun photoGeometry() {
        val src = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val turned = PhotoEdit(rotation = 1)
        assertEquals(200 to 400, turned.outputSize(400, 200))
        val cropped = PhotoEdit(crop = androidx.compose.ui.geometry.Rect(0.25f, 0f, 0.75f, 0.5f))
        val out = cropped.renderGeometry(src)
        assertEquals(200, out.width)
        assertEquals(100, out.height)
        // Source point at the crop's top-left maps to the output origin.
        val pts = floatArrayOf(100f, 0f)
        cropped.matrix(400, 200).mapPoints(pts)
        assertEquals(0f, pts[0], 0.01f)
        assertEquals(0f, pts[1], 0.01f)
    }

    private fun clip(id: Long, ms: Long, speed: Float = 1f) =
        Clip(id, "file:///clip$id.mp4".toUri(), "clip$id.mp4", false, ms, 1920, 1080, true, speed = speed)

    @Test
    fun timelineMath() {
        val p = VideoProject(listOf(clip(1, 4000), clip(2, 6000, speed = 2f)))
        assertEquals(7000, p.durationMs)           // 4 s + 6 s at 2×
        assertEquals(listOf(0L, 4000L), p.starts().toList())
        assertEquals(1, p.clipAt(5000))
        val split = p.split(5000, 99)!!
        assertEquals(3, split.clips.size)
        assertEquals(2000, split.clips[1].trimEndMs) // 1 s into a 2× clip = 2 s of source
        assertEquals(2000, split.clips[2].trimStartMs)
        assertEquals(p.durationMs, split.durationMs)
        assertNull(p.split(10, 100))                // too close to an edge
    }

    @Test
    fun projectSurvivesRestart() {
        val p = VideoProject(
            listOf(clip(1, 4000).copy(text = "Hello", volume = 0.5f, trimStartMs = 300)),
            Look(contrast = 0.2f, filter = FilterPreset.Film),
            Ratio.Story,
            Music("file:///song.mp3".toUri(), "Song", 0.6f)
        )
        assertEquals(p, VideoProject.fromJson(p.toJson()))
    }

    @Test
    fun buildsComposition() {
        val p = VideoProject(
            listOf(clip(1, 4000).copy(text = "Title", speed = 1.5f, volume = 0.4f), clip(2, 3000)),
            Look(saturation = 0.3f), Ratio.Square, Music("file:///song.mp3".toUri(), "Song")
        )
        val c = CompositionBuilder.build(p, exportHeight = 720)
        assertEquals(2, c.sequences.size)
        assertEquals(2, c.sequences[0].editedMediaItems.size)
        assertTrue(c.sequences[1].isLooping)
        assertNotNull(c.effects.videoEffects.firstOrNull())
    }
}
