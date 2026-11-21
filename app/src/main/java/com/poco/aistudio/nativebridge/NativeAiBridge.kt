package com.poco.aistudio.nativebridge

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ActiveEngineType {
    NONE,
    LLM,
    IMAGE,
    AUDIO
}

data class ActiveModelState(
    val engine: ActiveEngineType = ActiveEngineType.NONE,
    val modelName: String? = null,
    val modelPath: String? = null,
    val statusDetail: String = "Модель не загружена"
)

/**
 * Unified JNI Bridge & Global HyperOS LMKD RAM Guard.
 *
 * Loads two isolated native libraries:
 * - `libpoco_ai_core.so`: llama.cpp (LLM) + Candle (MusicGen) + Dimensity 9500s CPU pinning
 * - `libpoco_sd_bridge.so`: stable-diffusion.cpp (isolated to avoid ggml symbol collisions)
 *
 * Automatically unloads any previously loaded model across ALL engines before loading
 * a new one so 6-7 GB models fit safely within 12 GB LPDDR5X on POCO X8 Pro Max.
 */
object NativeAiBridge {
    private const val TAG = "NativeAiBridge"

    @Volatile
    var coreLoaded: Boolean = false
        private set

    @Volatile
    var sdLoaded: Boolean = false
        private set

    private val _activeModel = MutableStateFlow(ActiveModelState())
    val activeModel: StateFlow<ActiveModelState> = _activeModel.asStateFlow()

    init {
        try {
            System.loadLibrary("c++_shared")
        } catch (t: Throwable) {
            Log.w(TAG, "c++_shared preload warning: ${t.message}")
        }
        try {
            System.loadLibrary("poco_ai_core")
            coreLoaded = true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load libpoco_ai_core.so", t)
        }
        try {
            System.loadLibrary("poco_sd_bridge")
            sdLoaded = true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load libpoco_sd_bridge.so", t)
        }
    }

    // --- libpoco_ai_core.so ---
    external fun nativeGetCpuTopologyJson(): String
    external fun nativeUnloadCoreModels()
    external fun nativeLoadLlm(modelPath: String): String
    external fun nativeUnloadLlm()
    external fun nativeCancelLlm()
    external fun nativeIsLlmLoaded(): Boolean
    external fun nativeGenerateLlm(
        systemPrompt: String,
        userPrompt: String,
        nCtx: Int,
        temperature: Float,
        maxTokens: Int,
        callback: TokenStreamCallback
    ): String

    external fun nativeLoadAudioModel(modelDir: String): String
    external fun nativeUnloadAudioModel()
    external fun nativeCancelAudio()
    external fun nativeGenerateAudio(
        prompt: String,
        durationSec: Float,
        guidanceScale: Float,
        outputWavPath: String,
        callback: AudioProgressCallback
    ): String

    // --- libpoco_sd_bridge.so ---
    external fun nativeLoadSdModel(modelPath: String): String
    external fun nativeUnloadSdModel()
    external fun nativeGenerateImage(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfgScale: Float,
        seed: Long,
        sampler: String,
        outputPngPath: String,
        callback: ImageProgressCallback
    ): String

    /**
     * Global RAM Guard: drops all native model weights across all 3 engines + triggers GC.
     */
    @Synchronized
    fun unloadAllModels() {
        if (coreLoaded) {
            nativeUnloadCoreModels()
        }
        if (sdLoaded) {
            nativeUnloadSdModel()
        }
        _activeModel.value = ActiveModelState()
        System.gc()
    }

    @Synchronized
    fun loadLlmExclusive(modelName: String, modelPath: String): Result<String> {
        if (!coreLoaded) return Result.failure(IllegalStateException("Нативная библиотека poco_ai_core не загружена"))
        if (sdLoaded) nativeUnloadSdModel()
        nativeUnloadAudioModel()

        val resp = nativeLoadLlm(modelPath)
        return if (resp.startsWith("OK:")) {
            val detail = resp.removePrefix("OK:")
            _activeModel.value = ActiveModelState(ActiveEngineType.LLM, modelName, modelPath, detail)
            Result.success(detail)
        } else {
            _activeModel.value = ActiveModelState()
            Result.failure(RuntimeException(resp.removePrefix("ERR:")))
        }
    }

    @Synchronized
    fun loadSdExclusive(modelName: String, modelPath: String): Result<String> {
        if (!sdLoaded) return Result.failure(IllegalStateException("Нативная библиотека poco_sd_bridge не загружена"))
        if (coreLoaded) nativeUnloadCoreModels()

        val resp = nativeLoadSdModel(modelPath)
        return if (resp.startsWith("OK:")) {
            val detail = resp.removePrefix("OK:")
            _activeModel.value = ActiveModelState(ActiveEngineType.IMAGE, modelName, modelPath, detail)
            Result.success(detail)
        } else {
            _activeModel.value = ActiveModelState()
            Result.failure(RuntimeException(resp.removePrefix("ERR:")))
        }
    }

    @Synchronized
    fun loadAudioExclusive(modelName: String, modelDir: String): Result<String> {
        if (!coreLoaded) return Result.failure(IllegalStateException("Нативная библиотека poco_ai_core не загружена"))
        if (sdLoaded) nativeUnloadSdModel()
        nativeUnloadLlm()

        val resp = nativeLoadAudioModel(modelDir)
        return if (resp.startsWith("OK:")) {
            val detail = resp.removePrefix("OK:")
            _activeModel.value = ActiveModelState(ActiveEngineType.AUDIO, modelName, modelDir, detail)
            Result.success(detail)
        } else {
            _activeModel.value = ActiveModelState()
            Result.failure(RuntimeException(resp.removePrefix("ERR:")))
        }
    }

    fun getCpuTopologyJsonSafe(): String {
        return if (coreLoaded) {
            runCatching { nativeGetCpuTopologyJson() }.getOrDefault("{}")
        } else {
            "{}"
        }
    }
}
