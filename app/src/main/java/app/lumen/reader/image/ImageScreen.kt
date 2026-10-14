package app.lumen.reader.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.lumen.reader.core.DocRef
import app.lumen.reader.viewer.CenteredMessage
import app.lumen.reader.viewer.LoadingIndicator
import app.lumen.reader.viewer.ViewerScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class Decoded(
    val bitmap: ImageBitmap?,
    val drawable: Drawable?,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val regionDecodable: Boolean
) {
    val width get() = bitmap?.width ?: drawable!!.intrinsicWidth
    val height get() = bitmap?.height ?: drawable!!.intrinsicHeight
}

private class Tile(val image: ImageBitmap, val region: Rect)

@Composable
fun ImageScreen(ref: DocRef, onBack: () -> Unit) {
    val context = LocalContext.current
    var decoded by remember { mutableStateOf<Decoded?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var chrome by rememberSaveable { mutableStateOf(true) }
    val zoom = remember { ZoomState() }

    LaunchedEffect(ref) {
        runCatching { decode(context, ref) }
            .onSuccess { decoded = it }
            .onFailure { error = it.message ?: "Unsupported image" }
    }

    val bg by animateColorAsState(if (chrome) app.lumen.reader.ui.theme.Lumen.colors.backgroundAlt else Color.Black, label = "bg")
    val d = decoded
    ViewerScaffold(
        ref = ref,
        title = ref.name,
        subtitle = d?.let { "${it.sourceWidth} × ${it.sourceHeight}" + (ref.extension.takeIf { e -> e.isNotEmpty() }?.let { e -> " · ${e.uppercase()}" } ?: "") },
        chromeVisible = chrome,
        onBack = onBack,
        actions = {
            val navigate = app.lumen.reader.core.LocalNavigator.current
            if (d != null && d.drawable == null) {
                app.lumen.reader.ui.components.AppIconButton(
                    androidx.compose.material.icons.Icons.Rounded.Tune, "Edit photo",
                    { navigate(app.lumen.reader.core.Route.PhotoEdit(ref)) }
                )
            }
        }
    ) { _ ->
        Box(Modifier.fillMaxSize().background(bg)) {
            when {
                error != null -> CenteredMessage("Couldn't open this image", error)
                d == null -> LoadingIndicator()
                else -> ZoomableImage(context, ref, d, zoom) { chrome = !chrome }
            }
        }
    }
}

@Composable
private fun ZoomableImage(context: Context, ref: DocRef, d: Decoded, zoom: ZoomState, onTap: () -> Unit) {
    var tile by remember { mutableStateOf<Tile?>(null) }
    var frame by remember { mutableLongStateOf(0L) }

    (d.drawable as? AnimatedImageDrawable)?.let { anim ->
        DisposableEffect(anim) {
            anim.start()
            onDispose { anim.stop() }
        }
        LaunchedEffect(anim) {
            while (true) withFrameNanos { frame = it }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        val fit = min(vw / d.width, vh / d.height)
        val fw = d.width * fit
        val fh = d.height * fit
        zoom.viewport = Size(vw, vh)
        zoom.fitted = Rect((vw - fw) / 2, (vh - fh) / 2, (vw + fw) / 2, (vh + fh) / 2)

        // Huge photos: once zoom settles, decode just the visible window at native resolution.
        if (d.regionDecodable && d.bitmap != null) {
            LaunchedEffect(d) {
                snapshotFlow { Triple(zoom.scale, zoom.offset, zoom.isInteracting) }.collectLatest { (s, _, busy) ->
                    if (busy) return@collectLatest
                    val shownPx = fw * s
                    if (shownPx <= d.bitmap.width * 1.15f) {
                        tile = null; return@collectLatest
                    }
                    delay(80)
                    val region = zoom.visibleRegion()
                    if (region.width <= 0f || region.height <= 0f) return@collectLatest
                    tile = decodeRegion(context, ref, d, region, shownPx)
                }
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .zoomGestures(zoom, onTap)
                .zoomLayer(zoom)
        ) {
            val f = zoom.fitted
            if (d.bitmap != null) {
                drawImage(
                    d.bitmap,
                    dstOffset = IntOffset(f.left.roundToInt(), f.top.roundToInt()),
                    dstSize = IntSize(f.width.roundToInt(), f.height.roundToInt()),
                    filterQuality = if (zoom.scale > 2f) FilterQuality.Low else FilterQuality.Medium
                )
                tile?.let { t ->
                    val r = t.region
                    drawImage(
                        t.image,
                        dstOffset = IntOffset((f.left + r.left * f.width).roundToInt(), (f.top + r.top * f.height).roundToInt()),
                        dstSize = IntSize((r.width * f.width).roundToInt(), (r.height * f.height).roundToInt()),
                        filterQuality = FilterQuality.Medium
                    )
                }
            } else if (d.drawable != null) {
                @Suppress("UNUSED_EXPRESSION") frame
                val dr = d.drawable
                dr.setBounds(f.left.roundToInt(), f.top.roundToInt(), f.right.roundToInt(), f.bottom.roundToInt())
                drawIntoCanvas { dr.draw(it.nativeCanvas) }
            }
        }
    }
}

private suspend fun decode(context: Context, ref: DocRef): Decoded = withContext(Dispatchers.IO) {
    val metrics = context.resources.displayMetrics
    val maxDim = min(4096, max(metrics.widthPixels, metrics.heightPixels) * 2)
    var srcW = 0
    var srcH = 0
    var mime: String? = null
    val src = ImageDecoder.createSource(context.contentResolver, ref.uri)
    val drawable = ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
        srcW = info.size.width
        srcH = info.size.height
        mime = info.mimeType
        val s = min(1f, maxDim.toFloat() / max(srcW, srcH))
        if (s < 1f) decoder.setTargetSize((srcW * s).roundToInt().coerceAtLeast(1), (srcH * s).roundToInt().coerceAtLeast(1))
    }
    if (drawable is AnimatedImageDrawable) {
        return@withContext Decoded(null, drawable, srcW, srcH, false)
    }
    val bmp = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        ?: Bitmap.createBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            .also { b -> drawable.setBounds(0, 0, b.width, b.height); drawable.draw(android.graphics.Canvas(b)) }
    val decodable = (max(srcW, srcH) > maxDim) &&
        mime in setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic") &&
        exifUpright(context, ref)
    Decoded(bmp.asImageBitmap(), null, srcW, srcH, decodable)
}

private fun exifUpright(context: Context, ref: DocRef): Boolean = runCatching {
    context.contentResolver.openInputStream(ref.uri)!!.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) in
            setOf(ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED)
    }
}.getOrDefault(false)

private suspend fun decodeRegion(context: Context, ref: DocRef, d: Decoded, region: Rect, shownPx: Float): Tile? =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openFileDescriptor(ref.uri, "r")!!.use { pfd ->
                val decoder = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(pfd)
                else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(pfd.fileDescriptor, false)
                try {
                    val bleed = 0.06f
                    val r = Rect(
                        (region.left - region.width * bleed).coerceAtLeast(0f),
                        (region.top - region.height * bleed).coerceAtLeast(0f),
                        (region.right + region.width * bleed).coerceAtMost(1f),
                        (region.bottom + region.height * bleed).coerceAtMost(1f)
                    )
                    val px = android.graphics.Rect(
                        (r.left * d.sourceWidth).roundToInt(), (r.top * d.sourceHeight).roundToInt(),
                        (r.right * d.sourceWidth).roundToInt(), (r.bottom * d.sourceHeight).roundToInt()
                    )
                    // Pick the coarsest sample size that still meets the on-screen density.
                    var sample = 1
                    val needed = shownPx / d.sourceWidth
                    while (1f / (sample * 2) >= needed) sample *= 2
                    val bmp = decoder.decodeRegion(px, BitmapFactory.Options().apply { inSampleSize = sample })
                    Tile(bmp.asImageBitmap(), r)
                } finally {
                    decoder.recycle()
                }
            }
        }.getOrNull()
    }

/** Swipe through the photos of a folder; each page zooms independently. */
@Composable
fun ImageGallery(images: List<DocRef>, start: DocRef, onBack: () -> Unit) {
    val context = LocalContext.current
    val pager = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = images.indexOfFirst { it.key == start.key }.coerceAtLeast(0)
    ) { images.size }
    var chrome by rememberSaveable { mutableStateOf(true) }
    val zooms = remember(images) { List(images.size) { ZoomState() } }
    val current = images[pager.currentPage]
    val bg by animateColorAsState(if (chrome) app.lumen.reader.ui.theme.Lumen.colors.backgroundAlt else Color.Black, label = "bg")
    ViewerScaffold(
        ref = current,
        title = current.name,
        subtitle = "${pager.currentPage + 1} of ${images.size}",
        chromeVisible = chrome,
        onBack = onBack,
        actions = {
            val navigate = app.lumen.reader.core.LocalNavigator.current
            app.lumen.reader.ui.components.AppIconButton(
                androidx.compose.material.icons.Icons.Rounded.Tune, "Edit photo",
                { navigate(app.lumen.reader.core.Route.PhotoEdit(current)) }
            )
        }
    ) { _ ->
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            userScrollEnabled = !zooms[pager.currentPage].isZoomed,
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxSize().background(bg)
        ) { page ->
            val ref = images[page]
            var decoded by remember(ref) { mutableStateOf<Decoded?>(null) }
            var error by remember(ref) { mutableStateOf<String?>(null) }
            LaunchedEffect(ref) {
                runCatching { decode(context, ref) }.onSuccess { decoded = it }.onFailure { error = it.message ?: "Unsupported image" }
            }
            val d = decoded
            when {
                error != null -> CenteredMessage("Couldn't open this image", error)
                d == null -> LoadingIndicator()
                else -> ZoomableImage(context, ref, d, zooms[page]) { chrome = !chrome }
            }
        }
    }
}
