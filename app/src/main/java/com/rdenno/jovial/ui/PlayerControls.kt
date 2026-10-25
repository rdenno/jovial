package com.rdenno.jovial.ui

import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private val Accent = Color(0xFF9146FF)
private const val SEEK_STEP_MS = 10_000L
private const val AUTO_HIDE_MS = 3_000L

/** Bare video output; all controls are drawn in Compose by [PlayerControlsOverlay]. */
@Composable
fun VideoSurface(
    exoPlayer: ExoPlayer,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setShutterBackgroundColor(AndroidColor.BLACK)
            }
        },
        // Entering/leaving PiP or changing layout swaps in a new PlayerView; detach the old one
        onRelease = { view -> view.player = null },
        modifier = modifier
    )
}

private enum class SeekSide { BACK, FORWARD }

private data class SeekFeedback(val side: SeekSide, val seconds: Int, val id: Long)

/**
 * YouTube-style controls: tap to show/hide, double-tap the left/right side to skip 10s,
 * a large centre play/pause, and a slim scrubbable progress bar with buffered range.
 */
@Composable
fun PlayerControlsOverlay(
    player: Player,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    topBar: @Composable RowScope.() -> Unit = {}
) {
    var playWhenReady by remember(player) { mutableStateOf(player.playWhenReady) }
    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    var positionMs by remember(player) { mutableLongStateOf(player.currentPosition) }
    var bufferedMs by remember(player) { mutableLongStateOf(player.bufferedPosition) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    // Bumped on every interaction so the auto-hide timer restarts
    var interactions by remember { mutableIntStateOf(0) }
    var seekFeedback by remember { mutableStateOf<SeekFeedback?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                playWhenReady = player.playWhenReady
                isPlaying = player.isPlaying
                playbackState = player.playbackState
                durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val currentVisible by rememberUpdatedState(visible)
    val currentOnVisibleChange by rememberUpdatedState(onVisibleChange)
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            bufferedMs = player.bufferedPosition
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            delay(if (currentVisible) 250L else 1_000L)
        }
    }

    LaunchedEffect(visible, isPlaying, scrubMs != null, interactions) {
        if (visible && isPlaying && scrubMs == null) {
            delay(AUTO_HIDE_MS)
            currentOnVisibleChange(false)
        }
    }

    LaunchedEffect(seekFeedback?.id) {
        if (seekFeedback != null) {
            delay(700L)
            seekFeedback = null
        }
    }

    fun seekBy(deltaMs: Long) {
        val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, max)
        player.seekTo(target)
        positionMs = target
        interactions++
    }

    fun togglePlay() {
        when {
            playbackState == Player.STATE_ENDED -> {
                player.seekTo(0L)
                player.play()
            }
            playWhenReady -> player.pause()
            else -> player.play()
        }
        interactions++
    }

    fun doubleTapSeek(side: SeekSide) {
        seekBy(if (side == SeekSide.BACK) -SEEK_STEP_MS else SEEK_STEP_MS)
        // Repeated taps on the same side add up
        val previous = seekFeedback
        val seconds = if (previous?.side == side) previous.seconds + 10 else 10
        seekFeedback = SeekFeedback(side, seconds, System.nanoTime())
    }

    val showPlayIcon = !playWhenReady || playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED
    val isBuffering = playbackState == Player.STATE_BUFFERING && playWhenReady

    Box(
        // Hand-rolled instead of detectTapGestures: that delays every single tap by the double-tap
        // timeout. Here a tap toggles the controls immediately; a quick second tap on the left or
        // right third undoes that toggle and seeks instead, and while the seek ripple is showing,
        // further single taps on the same side keep seeking (like YouTube).
        modifier = modifier.pointerInput(player) {
            var lastTapUptime = 0L
            var lastTapPosition = Offset.Zero
            var visibleBeforeTap = false
            awaitEachGesture {
                awaitFirstDown()
                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                up.consume()
                val third = size.width / 3f
                val side = when {
                    up.position.x < third -> SeekSide.BACK
                    up.position.x > third * 2 -> SeekSide.FORWARD
                    else -> null
                }
                val isDoubleTap = up.uptimeMillis - lastTapUptime < viewConfiguration.doubleTapTimeoutMillis &&
                    (up.position - lastTapPosition).getDistance() < 100.dp.toPx()
                val continuingSeek = side != null && seekFeedback?.side == side

                when {
                    continuingSeek -> doubleTapSeek(side!!)
                    isDoubleTap -> {
                        currentOnVisibleChange(visibleBeforeTap)
                        if (side == null) togglePlay() else doubleTapSeek(side)
                    }
                    else -> {
                        visibleBeforeTap = currentVisible
                        currentOnVisibleChange(!currentVisible)
                    }
                }
                // A third tap starts a new pair rather than counting as another double-tap
                lastTapUptime = if (isDoubleTap) 0L else up.uptimeMillis
                lastTapPosition = up.position
            }
        }
    ) {
        // Double-tap seek ripple
        seekFeedback?.let { feedback ->
            val isBack = feedback.side == SeekSide.BACK
            Box(
                modifier = Modifier
                    .align(if (isBack) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.38f)
                    .background(
                        Color.White.copy(alpha = 0.12f),
                        if (isBack) {
                            RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                        } else {
                            RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (isBack) Icons.Rounded.FastRewind else Icons.Rounded.FastForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        "${feedback.seconds} seconds",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.55f),
                            0.3f to Color.Black.copy(alpha = 0.25f),
                            0.7f to Color.Black.copy(alpha = 0.25f),
                            1f to Color.Black.copy(alpha = 0.65f)
                        )
                    )
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = topBar
                )

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    RoundControlButton(onClick = { seekBy(-SEEK_STEP_MS) }, size = 44.dp) {
                        Icon(Icons.Rounded.Replay10, "Back 10 seconds", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    if (isBuffering) {
                        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
                        }
                    } else {
                        RoundControlButton(onClick = { togglePlay() }, size = 64.dp) {
                            val icon = when {
                                playbackState == Player.STATE_ENDED -> Icons.Rounded.Replay
                                showPlayIcon -> Icons.Rounded.PlayArrow
                                else -> Icons.Rounded.Pause
                            }
                            Icon(icon, if (showPlayIcon) "Play" else "Pause", tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                    }
                    RoundControlButton(onClick = { seekBy(SEEK_STEP_MS) }, size = 44.dp) {
                        Icon(Icons.Rounded.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    val shownMs = scrubMs ?: positionMs
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${formatTime(shownMs)} / ${formatTime(durationMs)}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 4.dp).weight(1f)
                        )
                        if (onToggleFullscreen != null) {
                            IconButton(
                                onClick = {
                                    onToggleFullscreen()
                                    interactions++
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                    contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                    SeekBar(
                        positionMs = shownMs,
                        bufferedMs = bufferedMs,
                        durationMs = durationMs,
                        onScrub = { scrubMs = it },
                        onScrubFinished = { target ->
                            player.seekTo(target)
                            positionMs = target
                            scrubMs = null
                            interactions++
                        }
                    )
                }
            }
        }

        // Keep the spinner visible while buffering even when the controls are hidden
        if (isBuffering && !visible) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
            )
        }
    }
}

@Composable
private fun RoundControlButton(
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
    ) {
        content()
    }
}

@Composable
private fun SeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    onScrub: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf(false) }
    val trackHeight by animateDpAsState(if (dragging) 5.dp else 3.dp, label = "trackHeight")
    val thumbRadius by animateDpAsState(if (dragging) 8.dp else 6.dp, label = "thumbRadius")
    var widthPx by remember { mutableFloatStateOf(1f) }
    val currentDuration by rememberUpdatedState(durationMs)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnScrubFinished by rememberUpdatedState(onScrubFinished)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                // Inset matches the largest thumb so it never clips at either end
                val inset = 8.dp.toPx()
                fun positionAt(x: Float): Long {
                    val fraction = ((x - inset) / (widthPx - inset * 2).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    return (fraction * currentDuration).toLong()
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (currentDuration <= 0L) return@awaitEachGesture
                    down.consume()
                    dragging = true
                    var target = positionAt(down.position.x)
                    currentOnScrub(target)
                    drag(down.id) { change ->
                        change.consume()
                        target = positionAt(change.position.x)
                        currentOnScrub(target)
                    }
                    dragging = false
                    currentOnScrubFinished(target)
                }
            }
            .drawBehind {
                val inset = 8.dp.toPx()
                val trackWidth = size.width - inset * 2
                val trackPx = trackHeight.toPx()
                val top = (size.height - trackPx) / 2
                val radius = CornerRadius(trackPx / 2)
                fun fraction(ms: Long) =
                    if (durationMs > 0) (ms.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

                drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(inset, top), Size(trackWidth, trackPx), radius)
                drawRoundRect(
                    Color.White.copy(alpha = 0.45f),
                    Offset(inset, top),
                    Size(trackWidth * fraction(bufferedMs), trackPx),
                    radius
                )
                val playedWidth = trackWidth * fraction(positionMs)
                drawRoundRect(Accent, Offset(inset, top), Size(playedWidth, trackPx), radius)
                drawCircle(Accent, thumbRadius.toPx(), Offset(inset + playedWidth, size.height / 2))
            }
    )
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
