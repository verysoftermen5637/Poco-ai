package com.poco.aistudio.ui.audio

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.poco.aistudio.data.InstalledModel
import com.poco.aistudio.data.ModelCategory
import com.poco.aistudio.data.ModelRepository
import com.poco.aistudio.nativebridge.ActiveEngineType
import com.poco.aistudio.nativebridge.AudioProgressCallback
import com.poco.aistudio.nativebridge.NativeAiBridge
import com.poco.aistudio.util.MediaExportHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

data class AudioUiState(
    val installedModels: List<InstalledModel> = emptyList(),
    val selectedModel: InstalledModel? = null,
    val prompt: String = "dark cyberpunk synthwave beat with punchy 808 bass, 128 bpm, crisp hi-hats",
    val durationSec: Float = 8f,
    val guidanceScale: Float = 3.0f,
    val isGenerating: Boolean = false,
    val currentStep: Int = 0,
    val totalSteps: Int = 1,
    val generatedWavPath: String? = null,
    val waveformPeaks: List<Float> = emptyList(),
    val isPlaying: Boolean = false,
    val playbackProgress: Float = 0f,
    val statusMessage: String? = null
)

class AudioViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ModelRepository(app)
    private val _uiState = MutableStateFlow(AudioUiState())
    val uiState: StateFlow<AudioUiState> = _uiState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null

    init {
        refreshInstalledModels()
    }

    fun refreshInstalledModels() {
        val list = repo.listInstalledModels(ModelCategory.AUDIO)
        _uiState.update { st ->
            val current = st.selectedModel?.takeIf { sel -> list.any { it.path == sel.path } }
                ?: list.firstOrNull()
            st.copy(installedModels = list, selectedModel = current)
        }
    }

    fun selectModel(model: InstalledModel) = _uiState.update { it.copy(selectedModel = model) }
    fun updatePrompt(p: String) = _uiState.update { it.copy(prompt = p) }
    fun updateDuration(sec: Float) = _uiState.update { it.copy(durationSec = sec.coerceIn(5f, 20f)) }
    fun updateGuidance(g: Float) = _uiState.update { it.copy(guidanceScale = g.coerceIn(1f, 6f)) }

    fun generateAudio() {
        val st = _uiState.value
        if (st.isGenerating) return
        val model = st.selectedModel
        if (model == null) {
            _uiState.update { it.copy(statusMessage = "Сначала скачай MusicGen-Small или MusicGen-Stereo в Model Hub.") }
            return
        }

        stopPlayback()
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isGenerating = true,
                    currentStep = 0,
                    totalSteps = (st.durationSec * 50).toInt().coerceAtLeast(1),
                    statusMessage = "Освобождение ОЗУ и загрузка ${model.name} (Candle mmap)..."
                )
            }

            val active = NativeAiBridge.activeModel.value
            if (active.engine != ActiveEngineType.AUDIO || active.modelPath != model.path) {
                val loadRes = NativeAiBridge.loadAudioExclusive(model.name, model.path)
                if (loadRes.isFailure) {
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            statusMessage = "Ошибка загрузки MusicGen: ${loadRes.exceptionOrNull()?.message}"
                        )
                    }
                    return@launch
                }
            }

            val outDir = File(getApplication<Application>().cacheDir, "audio_out").apply { mkdirs() }
            val outFile = File(outDir, "poco_music_${System.currentTimeMillis()}.wav")

            _uiState.update { it.copy(statusMessage = "Синтез аудио (${st.durationSec.toInt()} сек, 32 кГц)...") }

            val resp = NativeAiBridge.nativeGenerateAudio(
                prompt = st.prompt,
                durationSec = st.durationSec,
                guidanceScale = st.guidanceScale,
                outputWavPath = outFile.absolutePath,
                callback = object : AudioProgressCallback {
                    override fun onProgress(step: Int, totalSteps: Int) {
                        _uiState.update {
                            it.copy(
                                currentStep = step,
                                totalSteps = totalSteps.coerceAtLeast(1),
                                statusMessage = "Генерация фреймов EnCodec: $step / $totalSteps"
                            )
                        }
                    }
                }
            )

            if (resp.startsWith("OK:")) {
                val jsonStr = resp.removePrefix("OK:")
                val peaks = mutableListOf<Float>()
                runCatching {
                    val obj = JSONObject(jsonStr)
                    val arr = obj.optJSONArray("waveform_peaks")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            peaks.add(arr.optDouble(i, 0.1).toFloat())
                        }
                    }
                }
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        generatedWavPath = outFile.absolutePath,
                        waveformPeaks = peaks,
                        playbackProgress = 0f,
                        statusMessage = "Трек готов! Можно слушать или сохранить в Music."
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

    fun cancelGeneration() {
        if (NativeAiBridge.coreLoaded) {
            NativeAiBridge.nativeCancelAudio()
        }
        _uiState.update { it.copy(isGenerating = false, statusMessage = "Генерация отменена") }
    }

    fun togglePlayback() {
        val wavPath = _uiState.value.generatedWavPath ?: return
        if (_uiState.value.isPlaying) {
            stopPlayback()
            return
        }
        runCatching {
            val mp = MediaPlayer().apply {
                setDataSource(wavPath)
                prepare()
                setOnCompletionListener {
                    stopPlayback()
                }
                start()
            }
            mediaPlayer = mp
            _uiState.update { it.copy(isPlaying = true) }

            playbackJob?.cancel()
            playbackJob = viewModelScope.launch {
                while (isActive && mediaPlayer?.isPlaying == true) {
                    val dur = mediaPlayer?.duration?.coerceAtLeast(1) ?: 1
                    val pos = mediaPlayer?.currentPosition ?: 0
                    _uiState.update { it.copy(playbackProgress = (pos.toFloat() / dur).coerceIn(0f, 1f)) }
                    delay(50L)
                }
            }
        }.onFailure { err ->
            _uiState.update { it.copy(statusMessage = "Ошибка воспроизведения: ${err.message}") }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        _uiState.update { it.copy(isPlaying = false, playbackProgress = 0f) }
    }

    fun exportWavToMusic() {
        val path = _uiState.value.generatedWavPath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) return@launch
            val name = "POCO_MusicGen_${System.currentTimeMillis()}.wav"
            val res = MediaExportHelper.saveWavToMusic(getApplication(), file, name)
            _uiState.update {
                it.copy(
                    statusMessage = res.fold(
                        onSuccess = { saved -> "Экспортировано: $saved" },
                        onFailure = { err -> "Ошибка экспорта: ${err.message}" }
                    )
                )
            }
        }
    }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }
}
