package com.rdenno.jovial.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

// After this long paused, the server or network has likely dropped the open stream connection
private const val STALE_AFTER_PAUSE_MS = 2 * 60_000L
// Buffering this long without receiving any data means the connection is hung
private const val STALL_TIMEOUT_MS = 20_000L
private const val STALL_CHECK_INTERVAL_MS = 5_000L
private const val MAX_AUTO_RETRIES = 3

/**
 * Keeps a long-lived player playable. The player screen can sit paused in the background for
 * hours; by then its open HTTP stream is usually dead, and a player that hit an error ignores
 * play() until it is prepared again. This reloads the media at the current position when:
 * - play is requested after a long pause, or while the player is in an error / idle state
 *   (from the UI, the notification or headphones alike),
 * - a playback error occurs (a few automatic retries, then the UI shows a retry button),
 * - buffering stalls with no data arriving.
 */
class PlaybackRecovery(private val player: ExoPlayer) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var pausedAtMs = 0L
    private var autoRetries = 0
    private var checkStartPosition = false
    private var stallBufferedMs = -1L
    private var stallSinceMs = 0L

    private val stallCheck = object : Runnable {
        override fun run() {
            if (player.playbackState != Player.STATE_BUFFERING || !player.playWhenReady) return
            val now = SystemClock.elapsedRealtime()
            val buffered = player.bufferedPosition
            if (buffered != stallBufferedMs) {
                stallBufferedMs = buffered
                stallSinceMs = now
            } else if (now - stallSinceMs >= STALL_TIMEOUT_MS) {
                // Shares the retry budget so a source that is just slow can't reload forever
                if (autoRetries < MAX_AUTO_RETRIES) {
                    autoRetries++
                    reload()
                }
                return
            }
            handler.postDelayed(this, STALL_CHECK_INTERVAL_MS)
        }
    }

    fun attach() {
        player.addListener(this)
    }

    fun detach() {
        player.removeListener(this)
        handler.removeCallbacksAndMessages(null)
    }

    /** Call after loading a new video so a saved position past its end restarts it instead. */
    fun onNewMediaLoaded() {
        checkStartPosition = true
        autoRetries = 0
    }

    /** Re-opens the current video at the current position, keeping play / pause as is. */
    fun reload() {
        handler.removeCallbacks(stallCheck)
        val item = player.currentMediaItem ?: return
        player.setMediaItem(item, player.currentPosition)
        player.prepare()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val now = SystemClock.elapsedRealtime()
        if (!playWhenReady) {
            pausedAtMs = now
            return
        }
        val pausedFor = if (pausedAtMs > 0L) now - pausedAtMs else 0L
        pausedAtMs = 0L
        val broken = player.playerError != null || player.playbackState == Player.STATE_IDLE
        if (broken || pausedFor >= STALE_AFTER_PAUSE_MS) {
            autoRetries = 0
            // Not from inside the listener callback: let the play command finish first
            handler.post { reload() }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        if (autoRetries < MAX_AUTO_RETRIES) {
            autoRetries++
            handler.postDelayed({ reload() }, 1_500L * autoRetries)
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> {
                autoRetries = 0
                if (checkStartPosition) {
                    checkStartPosition = false
                    val duration = player.duration
                    if (duration != C.TIME_UNSET && player.currentPosition >= duration - 5_000L) {
                        player.seekTo(0L)
                    }
                }
            }
            Player.STATE_BUFFERING -> {
                stallBufferedMs = player.bufferedPosition
                stallSinceMs = SystemClock.elapsedRealtime()
                handler.removeCallbacks(stallCheck)
                handler.postDelayed(stallCheck, STALL_CHECK_INTERVAL_MS)
            }
            else -> Unit
        }
    }
}
