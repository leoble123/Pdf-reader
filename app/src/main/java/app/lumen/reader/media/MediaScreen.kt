package app.lumen.reader.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.VideoSettings
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.lumen.reader.core.DocRef
import app.lumen.reader.core.FileKind
import app.lumen.reader.ui.glass.GlassIconButton
import app.lumen.reader.ui.glass.GlassPanel
import app.lumen.reader.ui.theme.Lumen
import app.lumen.reader.viewer.ViewerScaffold
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private class TrackInfo(val title: String?, val artist: String?, val album: String?, val art: Bitmap?)

@OptIn(UnstableApi::class)
@Composable
fun MediaScreen(ref: DocRef, onBack: () -> Unit) {
    val context = LocalContext.current
    val isVideo = ref.kind == FileKind.Video
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(ref.uri))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = true
        }
    }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var speedIndex by rememberSaveable { mutableIntStateOf(2) }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<TrackInfo?>(null) }
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                error = e.errorCodeName
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (isActive) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            delay(if (playing) 200 else 500)
        }
    }
    LaunchedEffect(playing) {
        if (playing && isVideo) {
            delay(2500); chrome = false
        }
    }
    LaunchedEffect(ref) {
        if (!isVideo) info = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().run {
                    try {
                        setDataSource(context, ref.uri)
                        TrackInfo(
                            extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                            extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                            extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                            embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        )
                    } finally {
                        release()
                    }
                }
            }.getOrNull()
        }
    }

    val colors = Lumen.colors
    val type = Lumen.type
    ViewerScaffold(
        ref = ref,
        title = info?.title ?: ref.name,
        subtitle = if (isVideo) "Video" else listOfNotNull(info?.artist, info?.album).joinToString(" · ").ifEmpty { "Audio" },
        chromeVisible = chrome || !isVideo || !playing,
        onBack = onBack,
        actions = { b ->
            val navigate = app.lumen.reader.core.LocalNavigator.current
            if (isVideo) GlassIconButton(Icons.Rounded.VideoSettings, "Edit video", b, {
                player.pause()
                navigate(app.lumen.reader.core.Route.VideoEdit(listOf(ref)))
            })
        },
        bottomBar = { b ->
            Controls(
                b, playing, position, duration, speeds[speedIndex],
                onToggle = { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } },
                onSeek = { player.seekTo(it); position = it },
                onSkip = { player.seekTo((player.currentPosition + it).coerceIn(0, duration)) },
                onSpeed = {
                    speedIndex = (speedIndex + 1) % speeds.size
                    player.playbackParameters = PlaybackParameters(speeds[speedIndex])
                }
            )
        }
    ) { _ ->
        Box(
            Modifier
                .fillMaxSize()
                .background(if (isVideo) Color.Black else colors.background)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { chrome = !chrome },
                        onDoubleTap = { o ->
                            val forward = o.x > size.width / 2
                            player.seekTo((player.currentPosition + if (forward) 10_000 else -10_000).coerceIn(0, duration))
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (isVideo) {
                AndroidView(
                    factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
                    modifier = Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.2f, 5f))
                )
            } else {
                AudioArt(info, colors.accent)
            }
            error?.let {
                BasicText("Can't play this file ($it)", style = type.body.copy(color = Color.White))
            }
        }
    }
}

@Composable
private fun AudioArt(info: TrackInfo?, accent: Color) {
    val type = Lumen.type
    val colors = Lumen.colors
    val art = remember(info) { info?.art?.asImageBitmap() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (art != null) {
            Image(
                art, null,
                Modifier.fillMaxSize().blur(60.dp).graphicsLayer { alpha = 0.55f },
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawCircle(accent.copy(alpha = 0.35f), size.minDimension * 0.6f, Offset(size.width * 0.3f, size.height * 0.35f))
                    drawCircle(Color(0xFFFF4FA3).copy(alpha = 0.25f), size.minDimension * 0.5f, Offset(size.width * 0.8f, size.height * 0.6f))
                }.blur(80.dp)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(
                Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer {
                        shadowElevation = 24.dp.toPx(); shape = RoundedCornerShape(28.dp); clip = true
                    }
                    .background(colors.surfaceStrong),
                contentAlignment = Alignment.Center
            ) {
                if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Image(Icons.Rounded.Audiotrack, null, Modifier.size(96.dp), colorFilter = ColorFilter.tint(accent))
            }
            Spacer(Modifier.height(28.dp))
            info?.title?.let {
                BasicText(it, style = type.title.copy(color = colors.content), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            info?.artist?.let {
                Spacer(Modifier.height(4.dp))
                BasicText(it, style = type.body.copy(color = colors.contentSecondary))
            }
        }
    }
}

@Composable
private fun Controls(
    backdrop: Backdrop,
    playing: Boolean,
    position: Long,
    duration: Long,
    speed: Float,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkip: (Long) -> Unit,
    onSpeed: () -> Unit
) {
    val colors = Lumen.colors
    val type = Lumen.type
    var scrub by remember { mutableFloatStateOf(-1f) }
    val fraction = if (scrub >= 0) scrub else if (duration > 0) position.toFloat() / duration else 0f
    GlassPanel(
        backdrop,
        Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
        cornerRadius = 30.dp
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .pointerInput(duration) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            scrub = (down.position.x / size.width).coerceIn(0f, 1f)
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.first()
                                if (!c.pressed) break
                                c.consume()
                                scrub = (c.position.x / size.width).coerceIn(0f, 1f)
                            }
                            onSeek((scrub * duration).toLong())
                            scrub = -1f
                        }
                    }
                    .drawBehind {
                        val h = if (scrub >= 0) 8.dp.toPx() else 5.dp.toPx()
                        val y = size.height / 2 - h / 2
                        drawRoundRect(colors.content.copy(alpha = 0.18f), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                        drawRoundRect(colors.content, Offset(0f, y), Size(size.width * fraction, h), CornerRadius(h / 2))
                    }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BasicText(time(if (scrub >= 0) (scrub * duration).toLong() else position), style = type.caption.copy(color = colors.contentSecondary))
                BasicText("-" + time((duration - position).coerceAtLeast(0)), style = type.caption.copy(color = colors.contentSecondary))
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    "${if (speed % 1f == 0f) speed.toInt() else speed}×",
                    style = type.label.copy(color = colors.content),
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onSpeed).padding(horizontal = 12.dp, vertical = 10.dp)
                )
                GlassIconButton(Icons.Rounded.Replay10, "Back 10 seconds", backdrop, { onSkip(-10_000) })
                GlassIconButton(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (playing) "Pause" else "Play",
                    backdrop, onToggle, size = 62.dp, tint = colors.accent, iconTint = Color.White
                )
                GlassIconButton(Icons.Rounded.Forward10, "Forward 10 seconds", backdrop, { onSkip(10_000) })
                Spacer(Modifier.size(width = 48.dp, height = 1.dp))
            }
        }
    }
}

private fun time(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
