package app.lumen.reader.edit.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Filmstrip frames for the timeline, decoded small and cached. */
object FilmStrip {
    private val cache = object : LruCache<String, Bitmap>(24 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val lock = Mutex()

    fun cached(clip: Clip, timeMs: Long): Bitmap? = cache.get(key(clip, timeMs))

    private fun key(clip: Clip, timeMs: Long) = clip.uri.toString() + "@" + (if (clip.isImage) 0 else timeMs / 250)

    suspend fun frame(context: Context, clip: Clip, timeMs: Long, heightPx: Int): Bitmap? {
        cache.get(key(clip, timeMs))?.let { return it }
        return lock.withLock {
            cache.get(key(clip, timeMs)) ?: withContext(Dispatchers.IO) {
                runCatching {
                    if (clip.isImage) {
                        val o = BitmapFactory.Options().apply { inSampleSize = (clip.height / heightPx).coerceAtLeast(1) }
                        context.contentResolver.openInputStream(clip.uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                    } else {
                        val r = MediaMetadataRetriever()
                        try {
                            r.setDataSource(context, clip.uri)
                            val w = (heightPx * clip.width.toFloat() / clip.height.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
                            if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, heightPx)
                            else r.getFrameAtTime(timeMs * 1000)
                        } finally {
                            r.release()
                        }
                    }
                }.getOrNull()?.also { cache.put(key(clip, timeMs), it) }
            }
        }
    }
}
