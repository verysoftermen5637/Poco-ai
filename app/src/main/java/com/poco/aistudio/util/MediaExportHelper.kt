package com.poco.aistudio.util

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Scoped Storage MediaStore exporter compatible with Android 10–16 (HyperOS 3).
 * Saves generated PNGs to Pictures/PocoAIStudio and WAV tracks to Music/PocoAIStudio.
 */
object MediaExportHelper {

    fun savePngToGallery(context: Context, sourcePng: File, displayName: String): Result<String> {
        return runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/PocoAIStudio"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать запись в Галерее")

            resolver.openOutputStream(uri)?.use { out ->
                sourcePng.inputStream().use { input ->
                    input.copyTo(out)
                }
            } ?: error("Не удалось записать PNG в MediaStore")

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Pictures/PocoAIStudio/$displayName"
        }
    }

    fun saveWavToMusic(context: Context, sourceWav: File, displayName: String): Result<String> {
        return runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MUSIC}/PocoAIStudio"
                )
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать запись в папке Music")

            resolver.openOutputStream(uri)?.use { out ->
                sourceWav.inputStream().use { input ->
                    input.copyTo(out)
                }
            } ?: error("Не удалось записать WAV в MediaStore")

            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Music/PocoAIStudio/$displayName"
        }
    }
}
