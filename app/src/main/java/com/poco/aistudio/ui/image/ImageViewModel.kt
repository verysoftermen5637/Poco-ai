package com.poco.aistudio.ui.image

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.poco.aistudio.data.InstalledModel
import com.poco.aistudio.data.ModelCategory
import com.poco.aistudio.data.ModelRepository
import com.poco.aistudio.nativebridge.ActiveEngineType
import com.poco.aistudio.nativebridge.ImageProgressCallback
import com.poco.aistudio.nativebridge.NativeAiBridge
import com.poco.aistudio.util.MediaExportHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

enum class ImageResolution(val label: String, val width: Int, val height: Int) {
    SQUARE_512("512×512", 512, 512),
    PORTRAIT_512_768("512×768", 512, 768)
}

data class ImageUiState(
    val installedModels: List<InstalledModel> = emptyList(),
    val selectedModel: InstalledModel? = null,
    val prompt: String = "cyberpunk neon street in rain, reflections, ultra detailed, 8k cinematic",
    val negativePrompt: String = "blurry, deformed, lowres, bad anatomy, watermark",
    val resolution: ImageResolution = ImageResolution.SQUARE_512,
    val steps: Int = 4,
    val cfgScale: Float = 1.0f,
    val seed: Long = -1L,
    val isGenerating: Boolean = false,
    val currentStep: Int = 0,
    val totalSteps: Int = 4,
    val generatedPngPath: String? = null,
    val statusMessage: String? = null
)

class ImageViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ModelRepository(app)
    private val _uiState = MutableStateFlow(ImageUiState())
    val uiState: StateFlow<ImageUiState> = _uiState.asStateFlow()

    init {
        refreshInstalledModels()
    }

    fun refreshInstalledModels() {
        val list = repo.listInstalledModels(ModelCategory.IMAGE)
        _uiState.update { st ->
            val current = st.selectedModel?.takeIf { sel -> list.any { it.path == sel.path } }
                ?: list.firstOrNull()
            st.copy(installedModels = list, selectedModel = current)
        }
    }

    fun selectModel(model: InstalledModel) {
        _uiState.update { it.copy(selectedModel = model) }
    }

    fun updatePrompt(v: String) = _uiState.update { it.copy(prompt = v) }
    fun updateNegativePrompt(v: String) = _uiState.update { it.copy(negativePrompt = v) }
    fun updateResolution(r: ImageResolution) = _uiState.update { it.copy(resolution = r) }
    fun updateSteps(s: Int) = _uiState.update { it.copy(steps = s.coerceIn(1, 30)) }
    fun updateCfg(c: Float) = _uiState.update { it.copy(cfgScale = c.coerceIn(1.0f, 12.0f)) }
    fun updateSeed(s: Long) = _uiState.update { it.copy(seed = s) }
    fun randomizeSeed() = _uiState.update { it.copy(seed = Random.nextLong(1L, 2_147_483_647L)) }

    fun generateImage() {
        val st = _uiState.value
        if (st.isGenerating) return
        val model = st.selectedModel
        if (model == null) {
            _uiState.update { it.copy(statusMessage = "Сначала скачай SDXL-Turbo или SD-1.5-LCM в Model Hub.") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isGenerating = true,
                    currentStep = 0,
                    totalSteps = st.steps,
                    statusMessage = "Освобождение ОЗУ и загрузка ${model.name} (mmap)..."
                )
            }

            // Load exclusively if not already loaded
            val active = NativeAiBridge.activeModel.value
            if (active.engine != ActiveEngineType.IMAGE || active.modelPath != model.path) {
                val loadRes = NativeAiBridge.loadSdExclusive(model.name, model.path)
                if (loadRes.isFailure) {
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            statusMessage = "Ошибка загрузки SD: ${loadRes.exceptionOrNull()?.message}"
                        )
                    }
                    return@launch
                }
            }

            val outDir = File(getApplication<Application>().cacheDir, "sd_out").apply { mkdirs() }
            val outFile = File(outDir, "poco_img_${System.currentTimeMillis()}.png")
            val effectiveSeed = if (st.seed < 0L) Random.nextLong(1L, 2_147_483_647L) else st.seed
            val sampler = if (model.name.contains("LCM", ignoreCase = true)) "lcm" else "euler_a"

            _uiState.update { it.copy(statusMessage = "Генерация ${st.resolution.label} (${st.steps} шагов, CPU KleidiAI)...") }

            val resp = NativeAiBridge.nativeGenerateImage(
                prompt = st.prompt,
                negativePrompt = st.negativePrompt,
                width = st.resolution.width,
                height = st.resolution.height,
                steps = st.steps,
                cfgScale = st.cfgScale,
                seed = effectiveSeed,
                sampler = sampler,
                outputPngPath = outFile.absolutePath,
                callback = object : ImageProgressCallback {
                    override fun onProgress(step: Int, totalSteps: Int, timeSec: Float) {
                        _uiState.update {
                            it.copy(
                                currentStep = step,
                                totalSteps = totalSteps.coerceAtLeast(1),
                                statusMessage = "Шаг $step / $totalSteps (${"%.1f".format(timeSec)} с)"
                            )
                        }
                    }
                }
            )

            if (resp.startsWith("OK:")) {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        currentStep = st.steps,
                        seed = effectiveSeed,
                        generatedPngPath = outFile.absolutePath,
                        statusMessage = "Готово! Seed: $effectiveSeed"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        statusMessage = "Ошибка: ${resp.removePrefix("ERR:")}"
                    )
                }
            }
        }
    }

    fun saveToGallery() {
        val path = _uiState.value.generatedPngPath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) return@launch
            val name = "POCO_AI_${System.currentTimeMillis()}.png"
            val res = MediaExportHelper.savePngToGallery(getApplication(), file, name)
            _uiState.update {
                it.copy(
                    statusMessage = res.fold(
                        onSuccess = { saved -> "Сохранено в Галерею: $saved" },
                        onFailure = { err -> "Ошибка сохранения: ${err.message}" }
                    )
                )
            }
        }
    }
}
