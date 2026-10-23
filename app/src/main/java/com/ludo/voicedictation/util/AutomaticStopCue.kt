package com.ludo.voicedictation.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

/** Brief, user-visible cue for a live recording stopped at the duration cap. */
object AutomaticStopCue {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun play(context: Context, hapticTarget: View? = null) {
        playTone(context)
        if (hapticTarget?.isAttachedToWindow == true) {
            // View haptics respect the user's system haptic-feedback setting.
            hapticTarget.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        } else {
            vibrate(context)
        }
    }

    private fun playTone(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        // Respect silent/vibrate mode and notification-volume settings.
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) <= 0) return
        val tone = runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
        }.getOrNull() ?: return
        val started = runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 140) }
            .getOrDefault(false)
        if (!started || !mainHandler.postDelayed({ runCatching { tone.release() } }, 280L)) {
            runCatching { tone.release() }
        }
    }

    private fun vibrate(context: Context) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        runCatching { vibrator.vibrate(VibrationEffect.createOneShot(70L, 72)) }
    }
}
