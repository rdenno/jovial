package com.rdenno.jovial.ui

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

val MEDIA_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
    .build()

/**
 * Lets the user play a video during a phone / VoIP call (e.g. WhatsApp), mixed with the call
 * audio, like YouTube does.
 *
 * The player normally handles audio focus, so other media apps pause it and it pauses them. A call
 * holds audio focus for its whole duration, so ExoPlayer would refuse every play request. When
 * playback is requested during a call, audio focus handling is turned off so the video plays
 * anyway; the next play request outside a call turns it back on.
 *
 * Everything that starts playback (the on-screen controls, the notification, the PiP window's
 * play button) should go through this player rather than the ExoPlayer directly.
 */
@UnstableApi
class CallAwarePlayer(private val player: ExoPlayer, context: Context) : ForwardingPlayer(player) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var handlingAudioFocus = true

    override fun play() {
        updateAudioFocusHandling()
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) updateAudioFocusHandling()
        super.setPlayWhenReady(playWhenReady)
    }

    private fun updateAudioFocusHandling() {
        val handle = !isInCall()
        if (handle == handlingAudioFocus) return
        handlingAudioFocus = handle
        player.setAudioAttributes(MEDIA_AUDIO_ATTRIBUTES, /* handleAudioFocus = */ handle)
    }

    private fun isInCall(): Boolean = when (audioManager?.mode) {
        AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> true
        else -> false
    }
}
