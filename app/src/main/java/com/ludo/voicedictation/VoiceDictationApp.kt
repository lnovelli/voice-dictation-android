package com.ludo.voicedictation

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class VoiceDictationApp : Application() {

    companion object {
        const val UPDATE_CHANNEL_ID = "voice_dictation_updates"
        const val SERVICE_CHANNEL_ID = "voice_dictation_service"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val updateChannel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for application updates"
            }

            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Dictation Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active voice dictation and recording notifications"
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(updateChannel)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
