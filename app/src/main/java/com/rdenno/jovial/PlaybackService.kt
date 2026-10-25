package com.rdenno.jovial

import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Hosts the player screen's media session so playback shows up as a media notification
 * (and on the lock screen) and keeps running in the background with the screen off.
 * The player itself is owned by the player screen, which hands its session over via [attach].
 */
class PlaybackService : MediaSessionService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeSession?.let { session ->
            if (session !in sessions) addSession(session)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = activeSession

    // Swiping the app away from recents stops playback instead of leaving an orphaned notification
    override fun onTaskRemoved(rootIntent: Intent?) {
        activeSession?.player?.pause()
        stopSelf()
    }

    companion object {
        private var activeSession: MediaSession? = null

        fun attach(context: Context, session: MediaSession) {
            activeSession = session
            context.startService(Intent(context, PlaybackService::class.java))
        }

        fun detach(context: Context, session: MediaSession) {
            if (activeSession === session) activeSession = null
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
