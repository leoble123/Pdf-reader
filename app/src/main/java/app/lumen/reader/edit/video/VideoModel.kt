package app.lumen.reader.edit.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import app.lumen.reader.edit.FilterPreset
import app.lumen.reader.edit.Look
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToLong

@Immutable
data class Clip(
    val id: Long,
    val uri: Uri,
    val name: String,
    val isImage: Boolean,
    /** Full length of the source (images: default display time). */
    val sourceMs: Long,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = sourceMs,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val text: String = "",
    val textColor: Int = 0xFFFFFFFF.toInt()
) {
    /** How long this clip lasts on the output timeline. */
    val outMs: Long get() = ((trimEndMs - trimStartMs) / speed).roundToLong().coerceAtLeast(1)
}

@Immutable
data class Music(val uri: Uri, val name: String, val volume: Float = 0.8f)

enum class Ratio(val label: String, val value: Float?) {
    Original("Original", null), Story("9:16", 9f / 16f), Square("1:1", 1f), Portrait("4:5", 0.8f), Wide("16:9", 16f / 9f)
}

@Immutable
data class VideoProject(
    val clips: List<Clip> = emptyList(),
    val look: Look = Look(),
    val ratio: Ratio = Ratio.Original,
    val music: Music? = null,
    val clipVolume: Float = 1f
) {
    val durationMs: Long get() = clips.sumOf { it.outMs }

    /** Output-timeline start of every clip. */
    fun starts(): LongArray {
        val out = LongArray(clips.size)
        var t = 0L
        clips.forEachIndexed { i, c -> out[i] = t; t += c.outMs }
        return out
    }

    fun clipAt(timeMs: Long): Int {
        val s = starts()
        for (i in clips.indices.reversed()) if (timeMs >= s[i]) return i
        return 0
    }

    /** Frame size of the edit: first clip's size, reshaped by the chosen ratio. */
    fun aspect(): Float {
        ratio.value?.let { return it }
        val first = clips.firstOrNull() ?: return 9f / 16f
        return if (first.height > 0) first.width.toFloat() / first.height else 16f / 9f
    }

    /** Splits the clip under [timeMs] into two at that point. */
    fun split(timeMs: Long, newId: Long): VideoProject? {
        val i = clipAt(timeMs)
        val c = clips.getOrNull(i) ?: return null
        val local = ((timeMs - starts()[i]) * c.speed).roundToLong()
        val cut = c.trimStartMs + local
        if (cut - c.trimStartMs < 100 || c.trimEndMs - cut < 100) return null
        val a = c.copy(trimEndMs = cut)
        val b = c.copy(id = newId, trimStartMs = cut)
        return copy(clips = clips.toMutableList().apply { this[i] = a; add(i + 1, b) })
    }

    fun toJson(): String = JSONObject().apply {
        put("clips", JSONArray().apply {
            clips.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id); put("uri", c.uri.toString()); put("name", c.name); put("img", c.isImage)
                    put("src", c.sourceMs); put("w", c.width); put("h", c.height); put("audio", c.hasAudio)
                    put("ts", c.trimStartMs); put("te", c.trimEndMs); put("speed", c.speed.toDouble())
                    put("vol", c.volume.toDouble()); put("text", c.text); put("tc", c.textColor)
                })
            }
        })
        put("look", JSONObject().apply {
            put("e", look.exposure.toDouble()); put("c", look.contrast.toDouble()); put("s", look.saturation.toDouble())
            put("w", look.warmth.toDouble()); put("t", look.tint.toDouble()); put("f", look.fade.toDouble())
            put("filter", look.filter.name); put("fs", look.filterStrength.toDouble())
        })
        put("ratio", ratio.name)
        put("cv", clipVolume.toDouble())
        music?.let { m -> put("music", JSONObject().apply { put("uri", m.uri.toString()); put("name", m.name); put("vol", m.volume.toDouble()) }) }
    }.toString()

    companion object {
        fun fromJson(s: String): VideoProject = runCatching {
            val o = JSONObject(s)
            val arr = o.getJSONArray("clips")
            val clips = List(arr.length()) { i ->
                val c = arr.getJSONObject(i)
                Clip(
                    c.getLong("id"), c.getString("uri").toUri(), c.getString("name"), c.getBoolean("img"),
                    c.getLong("src"), c.getInt("w"), c.getInt("h"), c.getBoolean("audio"),
                    c.getLong("ts"), c.getLong("te"), c.getDouble("speed").toFloat(), c.getDouble("vol").toFloat(),
                    c.optString("text"), c.optInt("tc", 0xFFFFFFFF.toInt())
                )
            }
            val l = o.optJSONObject("look")
            val look = if (l == null) Look() else Look(
                exposure = l.optDouble("e").toFloat(), contrast = l.optDouble("c").toFloat(), saturation = l.optDouble("s").toFloat(),
                warmth = l.optDouble("w").toFloat(), tint = l.optDouble("t").toFloat(), fade = l.optDouble("f").toFloat(),
                filter = runCatching { FilterPreset.valueOf(l.getString("filter")) }.getOrDefault(FilterPreset.None),
                filterStrength = l.optDouble("fs", 1.0).toFloat()
            )
            val m = o.optJSONObject("music")
            VideoProject(
                clips, look,
                runCatching { Ratio.valueOf(o.getString("ratio")) }.getOrDefault(Ratio.Original),
                m?.let { Music(it.getString("uri").toUri(), it.getString("name"), it.getDouble("vol").toFloat()) },
                o.optDouble("cv", 1.0).toFloat()
            )
        }.getOrDefault(VideoProject())

        fun file(context: Context) = File(context.filesDir, "video_project.json")

        const val IMAGE_MS = 3000L

        /** Reads what the editor needs to know about a clip without decoding it. */
        suspend fun probe(context: Context, uri: Uri, name: String, mime: String?, id: Long): Clip? = withContext(Dispatchers.IO) {
            val isImage = mime?.startsWith("image/") == true ||
                name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
            if (isImage) {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                return@withContext Clip(id, uri, name, true, IMAGE_MS, o.outWidth.coerceAtLeast(1), o.outHeight.coerceAtLeast(1), false)
            }
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return@withContext null
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val audio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                val video = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                if (!video) return@withContext null
                val (vw, vh) = if (rot % 180 != 0) h to w else w to h
                Clip(id, uri, name, false, dur, vw, vh, audio)
            } catch (_: Exception) {
                null
            } finally {
                r.release()
            }
        }
    }
}
