package com.rdenno.jovial.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/**
 * YouTube-style fullscreen: entering fullscreen locks to landscape using the sensor, so the
 * player flips between landscape and reverse landscape even with auto-rotate turned off.
 * Exiting forces portrait. Once the phone is physically held in the forced orientation the
 * lock is released again so auto-rotate (when enabled) keeps working as normal.
 */
class FullscreenController(private val activity: Activity) {
    private enum class Forced { LANDSCAPE, PORTRAIT }

    private var forced: Forced? = null

    private val orientationListener = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return
            when (forced) {
                Forced.PORTRAIT -> if (degrees <= 30 || degrees >= 330) release()
                // With auto-rotate off, stay locked to landscape so the 180° flip keeps working
                Forced.LANDSCAPE -> if ((degrees in 60..120 || degrees in 240..300) && isAutoRotateEnabled()) {
                    release()
                }
                null -> Unit
            }
        }
    }

    fun enterFullscreen() = force(Forced.LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

    fun exitFullscreen() = force(Forced.PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

    private fun force(target: Forced, orientation: Int) {
        forced = target
        activity.requestedOrientation = orientation
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    private fun release() {
        forced = null
        orientationListener.disable()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    fun dispose() {
        release()
    }

    private fun isAutoRotateEnabled(): Boolean =
        Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
}

@Composable
fun rememberFullscreenController(activity: Activity?): FullscreenController? {
    val controller = remember(activity) { activity?.let { FullscreenController(it) } }
    DisposableEffect(controller) {
        onDispose { controller?.dispose() }
    }
    return controller
}
