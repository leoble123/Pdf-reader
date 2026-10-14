package app.lumen.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import app.lumen.reader.scan.ScanFilter
import app.lumen.reader.scan.ScanProcessing
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScannerTest {

    private val out = File("build/scan-out").apply { mkdirs() }

    @Test
    fun findsAndFlattensThePage() {
        val bytes = File(javaClass.classLoader!!.getResource("fixtures/scan_photo.jpg")!!.toURI()).readBytes()
        val photo = BitmapFactory.decodeByteArray(bytes, 0, bytes.size).copy(Bitmap.Config.ARGB_8888, false)
        val truth = listOf(Offset(0.263f, 0.125f), Offset(0.756f, 0.192f), Offset(0.806f, 0.9f), Offset(0.206f, 0.842f))
        val found = ScanProcessing.detect(photo)
        println("found $found")
        found.zip(truth).forEach { (f, t) ->
            assertTrue("corner $f vs $t", hypot(f.x - t.x, f.y - t.y) < 0.03f)
        }
        val flat = ScanProcessing.warp(photo, found)
        // Output proportions follow the quad's edge lengths (true aspect needs camera intrinsics).
        val ratio = flat.width.toFloat() / flat.height
        assertTrue("ratio $ratio", ratio in 0.6f..1.3f)
        for (f in ScanFilter.entries) {
            val r = ScanProcessing.filter(flat, f)
            File(out, "scan_${f.name}.png").outputStream().use { r.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // B&W mode: paper becomes white, so the large majority of pixels are near-white.
        val bw = ScanProcessing.filter(flat, ScanFilter.Document)
        val px = IntArray(bw.width * bw.height).also { bw.getPixels(it, 0, bw.width, 0, 0, bw.width, bw.height) }
        val white = px.count { (it and 0xFF) > 230 }.toFloat() / px.size
        assertTrue("white fraction $white", white > 0.8f)
    }

    @Test
    fun fallsBackWithoutAPage() {
        val plain = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
        val q = ScanProcessing.detect(plain)
        assertTrue(ScanProcessing.isConvex(q) && ScanProcessing.quadArea(q) > 0.7f)
    }
}
