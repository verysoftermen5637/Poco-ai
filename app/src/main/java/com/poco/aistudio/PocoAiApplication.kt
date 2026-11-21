package com.poco.aistudio

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class PocoAiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createDownloadNotificationChannel()
    }

    private fun createDownloadNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_MODEL_DOWNLOADS,
                "Загрузка ИИ-моделей",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Прогресс и скорость скачивания моделей с Hugging Face"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_MODEL_DOWNLOADS = "poco_ai_model_downloads"
    }
}
