package app.lumen.reader.edit.video

import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects

@UnstableApi
object CompositionBuilder {

    /**
     * One composition describes the whole edit; CompositionPlayer previews it live and
     * Transformer encodes exactly the same thing, so preview and export can't drift apart.
     *
     * @param exportHeight output height for export, or null for preview (source resolution).
     */
    fun build(project: VideoProject, exportHeight: Int? = null): Composition {
        val items = project.clips.map { clip -> item(clip, project.clipVolume) }
        // Declaring both track types makes clips without sound (photos, muted clips) get silence.
        val video = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
            .addItems(items)
            .build()
        val sequences = mutableListOf(video)
        project.music?.let { m ->
            val music = EditedMediaItem.Builder(MediaItem.fromUri(m.uri))
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(volume(m.volume)), emptyList()))
                .build()
            sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItem(music).setIsLooping(true).build()
        }

        val effects = ArrayList<Effect>()
        if (!project.look.isIdentity) {
            val m = project.look.videoMatrix()
            effects += RgbMatrix { _, _ -> m }
        }
        val ratio = project.ratio.value
        if (ratio != null) effects += Presentation.createForAspectRatio(ratio, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        // 720p/1080p means the short side, for portrait and landscape alike.
        if (exportHeight != null) effects += Presentation.createForShortSide(exportHeight)
        return Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), effects))
            .build()
    }

    private fun item(clip: Clip, masterVolume: Float): EditedMediaItem {
        val media = MediaItem.Builder().setUri(clip.uri).apply {
            if (clip.isImage) setImageDurationMs(clip.trimEndMs - clip.trimStartMs)
            else setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartMs)
                    .setEndPositionMs(clip.trimEndMs)
                    .build()
            )
        }.build()
        val video = ArrayList<Effect>()
        if (clip.text.isNotBlank()) video += OverlayEffect(listOf(caption(clip.text, clip.textColor)))
        val audio = ArrayList<AudioProcessor>()
        val gain = clip.volume * masterVolume
        if (gain != 1f && !clip.isImage) audio += volume(gain)
        return EditedMediaItem.Builder(media).apply {
            if (clip.isImage) {
                setFrameRate(30)
                setDurationUs((clip.trimEndMs - clip.trimStartMs) * 1000)
            }
            if (clip.speed != 1f && !clip.isImage) setSpeed(SpeedParameters(ConstantSpeed(clip.speed), true))
            if (gain <= 0.001f) setRemoveAudio(true)
            setEffects(Effects(audio, video))
        }.build()
    }

    private fun volume(gain: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
        for (channels in 1..2) {
            putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(channels, channels).scaleBy(gain))
        }
    }

    private fun caption(text: String, color: Int): TextOverlay {
        val span = SpannableString(text).apply {
            setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan(64), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val settings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, -0.72f) // lower third, centred
            .build()
        return TextOverlay.createStaticTextOverlay(span, settings)
    }

    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long) = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
    }
}
