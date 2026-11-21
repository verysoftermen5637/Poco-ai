package com.poco.aistudio.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

data class InstalledModel(
    val id: String,
    val name: String,
    val category: ModelCategory,
    val path: String,
    val sizeBytes: Long,
    val isPreset: Boolean
)

data class DownloadStatus(
    val modelId: String,
    val isRunning: Boolean,
    val progress: Int,
    val speedMbSec: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val error: String? = null
)

class ModelRepository(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    fun categoryDir(category: ModelCategory): File {
        return File(context.filesDir, "models/${category.folderName}").apply { mkdirs() }
    }

    fun targetPathForPreset(preset: ModelPreset): File {
        val catDir = categoryDir(preset.category)
        return if (preset.isBundleDirectory) {
            File(catDir, preset.id)
        } else {
            File(catDir, preset.files.first().fileName)
        }
    }

    fun isPresetInstalled(preset: ModelPreset): Boolean {
        return if (preset.isBundleDirectory) {
            val dir = targetPathForPreset(preset)
            preset.files.all { spec ->
                val f = File(dir, spec.fileName)
                f.exists() && f.length() > 0L
            }
        } else {
            val f = targetPathForPreset(preset)
            f.exists() && f.length() > 0L
        }
    }

    fun listInstalledModels(category: ModelCategory? = null): List<InstalledModel> {
        val categories = if (category != null) listOf(category) else ModelCategory.entries
        val results = mutableListOf<InstalledModel>()

        for (cat in categories) {
            val dir = categoryDir(cat)
            val presetsInCat = ModelCatalog.presets.filter { it.category == cat }

            // 1. Check presets first
            val claimedNames = mutableSetOf<String>()
            for (preset in presetsInCat) {
                if (isPresetInstalled(preset)) {
                    val path = targetPathForPreset(preset)
                    claimedNames.add(path.name)
                    val size = computeSizeBytes(path)
                    results.add(
                        InstalledModel(
                            id = preset.id,
                            name = preset.name,
                            category = cat,
                            path = path.absolutePath,
                            sizeBytes = size,
                            isPreset = true
                        )
                    )
                }
            }

            // 2. Scan locally imported files (.gguf, .safetensors, .ckpt) or custom directories
            dir.listFiles()?.forEach { f ->
                if (f.name in claimedNames || f.name.endsWith(".part")) return@forEach
                val valid = if (cat == ModelCategory.AUDIO && f.isDirectory) {
                    File(f, "model.safetensors").exists()
                } else {
                    f.isFile && (f.name.endsWith(".gguf", true) ||
                        f.name.endsWith(".safetensors", true) ||
                        f.name.endsWith(".ckpt", true))
                }
                if (valid) {
                    results.add(
                        InstalledModel(
                            id = "local_${f.name}",
                            name = f.nameWithoutExtension,
                            category = cat,
                            path = f.absolutePath,
                            sizeBytes = computeSizeBytes(f),
                            isPreset = false
                        )
                    )
                }
            }
        }
        return results
    }

    fun enqueuePresetDownload(preset: ModelPreset) {
        val targetDir = if (preset.isBundleDirectory) {
            targetPathForPreset(preset).apply { mkdirs() }
        } else {
            categoryDir(preset.category)
        }

        val input = workDataOf(
            ModelDownloadWorker.KEY_MODEL_ID to preset.id,
            ModelDownloadWorker.KEY_MODEL_NAME to preset.name,
            ModelDownloadWorker.KEY_TARGET_DIR to targetDir.absolutePath,
            ModelDownloadWorker.KEY_FILE_NAMES to preset.files.map { it.fileName }.toTypedArray(),
            ModelDownloadWorker.KEY_FILE_URLS to preset.files.map { it.downloadUrl }.toTypedArray()
        )

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(input)
            .addTag(TAG_ALL_DOWNLOADS)
            .addTag("model_${preset.id}")
            .build()

        workManager.enqueueUniqueWork(
            "download_${preset.id}",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun enqueueCustomUrlDownload(name: String, url: String, category: ModelCategory) {
        val cleanName = name.trim().ifEmpty {
            url.substringAfterLast('/').substringBefore('?').ifEmpty { "custom_model.gguf" }
        }
        val fileName = if (cleanName.contains('.')) cleanName else "$cleanName.gguf"
        val customId = "custom_${fileName.hashCode()}"
        val input = workDataOf(
            ModelDownloadWorker.KEY_MODEL_ID to customId,
            ModelDownloadWorker.KEY_MODEL_NAME to fileName,
            ModelDownloadWorker.KEY_TARGET_DIR to categoryDir(category).absolutePath,
            ModelDownloadWorker.KEY_FILE_NAMES to arrayOf(fileName),
            ModelDownloadWorker.KEY_FILE_URLS to arrayOf(url.trim())
        )
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(input)
            .addTag(TAG_ALL_DOWNLOADS)
            .addTag("model_$customId")
            .build()

        workManager.enqueueUniqueWork("download_$customId", ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(modelId: String) {
        workManager.cancelUniqueWork("download_$modelId")
    }

    fun observeDownloads(): Flow<Map<String, DownloadStatus>> {
        return workManager.getWorkInfosByTagFlow(TAG_ALL_DOWNLOADS).map { infos ->
            val map = mutableMapOf<String, DownloadStatus>()
            for (info in infos) {
                val modelId = info.tags.firstOrNull { it.startsWith("model_") }
                    ?.removePrefix("model_") ?: continue
                val isRunning = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
                val p = info.progress
                val err = if (info.state == WorkInfo.State.FAILED) {
                    info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Ошибка загрузки"
                } else null
                map[modelId] = DownloadStatus(
                    modelId = modelId,
                    isRunning = isRunning,
                    progress = p.getInt(ModelDownloadWorker.KEY_PROGRESS, 0),
                    speedMbSec = p.getFloat(ModelDownloadWorker.KEY_SPEED_MB_S, 0f),
                    downloadedBytes = p.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L),
                    totalBytes = p.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L),
                    error = err
                )
            }
            map
        }
    }

    fun deleteModel(path: String): Boolean {
        val file = File(path)
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    /**
     * Imports a local `.gguf` or `.safetensors` file from device storage into the app's fast UFS 4.0 private folder.
     */
    suspend fun importLocalFile(uri: Uri, category: ModelCategory): Result<InstalledModel> =
        withContext(Dispatchers.IO) {
            runCatching {
                val displayName = resolveDisplayName(uri) ?: "imported_${System.currentTimeMillis()}.gguf"
                val destDir = categoryDir(category)
                val destFile = File(destDir, displayName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 256 * 1024)
                    }
                } ?: error("Не удалось открыть выбранный файл")

                InstalledModel(
                    id = "local_${destFile.name}",
                    name = destFile.nameWithoutExtension,
                    category = category,
                    path = destFile.absolutePath,
                    sizeBytes = destFile.length(),
                    isPreset = false
                )
            }
        }

    private fun resolveDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun computeSizeBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    companion object {
        private const val TAG_ALL_DOWNLOADS = "poco_ai_model_download"
    }
}
