package com.poco.aistudio.data

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.poco.aistudio.PocoAiApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Resumable Hugging Face downloader powered by WorkManager + OkHttp.
 * Supports HTTP Range resume (докачка), multi-file model bundles (MusicGen),
 * live MB/s speed telemetry, and Android 16 foreground dataSync notifications.
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: modelId
        val targetDirPath = inputData.getString(KEY_TARGET_DIR) ?: return@withContext Result.failure()
        val fileNames = inputData.getStringArray(KEY_FILE_NAMES) ?: return@withContext Result.failure()
        val fileUrls = inputData.getStringArray(KEY_FILE_URLS) ?: return@withContext Result.failure()

        if (fileNames.size != fileUrls.size || fileNames.isEmpty()) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Некорректный список файлов"))
        }

        val targetDir = File(targetDirPath).apply { mkdirs() }
        val notifId = modelId.hashCode() and 0x7FFFFFFF

        setForeground(createForegroundInfo(notifId, modelName, 0, 0f))

        try {
            val totalFiles = fileNames.size
            for (idx in fileNames.indices) {
                if (isStopped) return@withContext Result.failure()
                val fileName = fileNames[idx]
                val url = fileUrls[idx]
                val finalFile = File(targetDir, fileName)
                if (finalFile.exists() && finalFile.length() > 0L) {
                    continue
                }
                val partFile = File(targetDir, "$fileName.part")
                downloadSingleFileResumable(
                    url = url,
                    partFile = partFile,
                    finalFile = finalFile,
                    notifId = notifId,
                    modelName = if (totalFiles > 1) "$modelName (${idx + 1}/$totalFiles)" else modelName
                )
            }
            Result.success(workDataOf(KEY_MODEL_ID to modelId, KEY_PROGRESS to 100))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Ошибка скачивания")))
        }
    }

    private suspend fun downloadSingleFileResumable(
        url: String,
        partFile: File,
        finalFile: File,
        notifId: Int,
        modelName: String
    ) {
        var existingBytes = if (partFile.exists()) partFile.length() else 0L

        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "PocoAIStudio/1.0 (Android 16; Dimensity9500s)")
        if (existingBytes > 0L) {
            reqBuilder.header("Range", "bytes=$existingBytes-")
        }

        client.newCall(reqBuilder.build()).execute().use { response ->
            if (response.code == 416) {
                // Already fully downloaded
                if (partFile.exists()) {
                    partFile.renameTo(finalFile)
                }
                return
            }
            if (!response.isSuccessful && response.code != 206) {
                throw IllegalStateException("HTTP ${response.code} при загрузке $url")
            }

            val isResumed = response.code == 206
            if (!isResumed) {
                existingBytes = 0L
            }

            val body = response.body ?: throw IllegalStateException("Пустой ответ сервера")
            val contentLen = body.contentLength()
            val totalBytes = if (contentLen > 0) existingBytes + contentLen else -1L

            val buffer = ByteArray(128 * 1024)
            var downloaded = existingBytes
            var windowBytes = 0L
            var lastTickNs = System.nanoTime()

            FileOutputStream(partFile, isResumed).use { fos ->
                body.byteStream().use { input ->
                    while (true) {
                        if (isStopped) {
                            fos.flush()
                            throw IllegalStateException("Загрузка остановлена")
                        }
                        val read = input.read(buffer)
                        if (read == -1) break
                        fos.write(buffer, 0, read)
                        downloaded += read
                        windowBytes += read

                        val nowNs = System.nanoTime()
                        val elapsedMs = (nowNs - lastTickNs) / 1_000_000L
                        if (elapsedMs >= 500L) {
                            val speedMbSec = (windowBytes.toFloat() / (1024f * 1024f)) / (elapsedMs / 1000f)
                            val pct = if (totalBytes > 0) {
                                ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 99)
                            } else {
                                0
                            }
                            setProgress(
                                workDataOf(
                                    KEY_PROGRESS to pct,
                                    KEY_SPEED_MB_S to speedMbSec,
                                    KEY_DOWNLOADED_BYTES to downloaded,
                                    KEY_TOTAL_BYTES to totalBytes
                                )
                            )
                            setForeground(createForegroundInfo(notifId, modelName, pct, speedMbSec))
                            windowBytes = 0L
                            lastTickNs = nowNs
                        }
                    }
                    fos.flush()
                }
            }
        }

        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
        }
    }

    private fun createForegroundInfo(
        notifId: Int,
        modelName: String,
        progress: Int,
        speedMbSec: Float
    ): ForegroundInfo {
        val text = if (speedMbSec > 0.01f) {
            "$progress% • ${"%.1f".format(speedMbSec)} МБ/с"
        } else {
            "Подключение к Hugging Face..."
        }

        val notification = NotificationCompat.Builder(applicationContext, PocoAiApplication.CHANNEL_MODEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Загрузка: $modelName")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, notification)
        }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_TARGET_DIR = "target_dir"
        const val KEY_FILE_NAMES = "file_names"
        const val KEY_FILE_URLS = "file_urls"
        const val KEY_PROGRESS = "progress"
        const val KEY_SPEED_MB_S = "speed_mb_s"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_ERROR = "error"
    }
}
