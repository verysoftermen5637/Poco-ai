package com.poco.aistudio.ui.llm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.poco.aistudio.data.InstalledModel
import com.poco.aistudio.data.ModelCategory
import com.poco.aistudio.data.ModelRepository
import com.poco.aistudio.nativebridge.NativeAiBridge
import com.poco.aistudio.nativebridge.TokenStreamCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val tokPerSec: Float = 0f
)

data class LlmUiState(
    val installedModels: List<InstalledModel> = emptyList(),
    val selectedModel: InstalledModel? = null,
    val isLoadingModel: Boolean = false,
    val isGenerating: Boolean = false,
    val liveTokPerSec: Float = 0f,
    val contextSize: Int = 4096,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 1024,
    val systemPrompt: String = "Ты — локальный ИИ-ассистент на POCO X8 Pro Max. Отвечай чётко, по делу и на языке пользователя.",
    val messages: List<ChatMessage> = emptyList(),
    val statusMessage: String? = null
)

class LlmViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ModelRepository(app)
    private val _uiState = MutableStateFlow(LlmUiState())
    val uiState: StateFlow<LlmUiState> = _uiState.asStateFlow()

    private var genJob: Job? = null

    init {
        refreshInstalledModels()
    }

    fun refreshInstalledModels() {
        val list = repo.listInstalledModels(ModelCategory.LLM)
        _uiState.update { state ->
            val current = state.selectedModel?.takeIf { sel -> list.any { it.path == sel.path } }
                ?: list.firstOrNull()
            state.copy(installedModels = list, selectedModel = current)
        }
    }

    fun loadModel(model: InstalledModel) {
        if (_uiState.value.isLoadingModel || _uiState.value.isGenerating) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isLoadingModel = true,
                    selectedModel = model,
                    statusMessage = "Выгрузка старой модели и mmap загрузка ${model.name}..."
                )
            }
            val result = NativeAiBridge.loadLlmExclusive(model.name, model.path)
            _uiState.update {
                it.copy(
                    isLoadingModel = false,
                    statusMessage = result.fold(
                        onSuccess = { info -> "Готово: $info" },
                        onFailure = { err -> "Ошибка загрузки: ${err.message}" }
                    )
                )
            }
        }
    }

    fun updateSettings(contextSize: Int, temperature: Float, maxTokens: Int, systemPrompt: String) {
        _uiState.update {
            it.copy(
                contextSize = contextSize.coerceIn(512, 8192),
                temperature = temperature.coerceIn(0f, 1.5f),
                maxTokens = maxTokens.coerceIn(128, 4096),
                systemPrompt = systemPrompt
            )
        }
    }

    fun clearChat() {
        if (_uiState.value.isGenerating) stopGeneration()
        _uiState.update { it.copy(messages = emptyList(), liveTokPerSec = 0f) }
    }

    fun sendPrompt(prompt: String) {
        val clean = prompt.trim()
        if (clean.isEmpty() || _uiState.value.isGenerating) return

        val userMsg = ChatMessage(System.nanoTime(), isUser = true, text = clean)
        val assistantId = System.nanoTime() + 1
        val assistantPlaceholder = ChatMessage(assistantId, isUser = false, text = "")

        _uiState.update {
            it.copy(
                isGenerating = true,
                liveTokPerSec = 0f,
                statusMessage = null,
                messages = it.messages + userMsg + assistantPlaceholder
            )
        }

        genJob = viewModelScope.launch(Dispatchers.IO) {
            // Auto-load selected model if not currently in RAM
            if (!NativeAiBridge.nativeIsLlmLoaded()) {
                val sel = _uiState.value.selectedModel
                if (sel == null) {
                    appendAssistantError(assistantId, "Сначала скачай или импортируй GGUF-модель в Model Hub.")
                    return@launch
                }
                val loadRes = NativeAiBridge.loadLlmExclusive(sel.name, sel.path)
                if (loadRes.isFailure) {
                    appendAssistantError(assistantId, "Не удалось загрузить модель: ${loadRes.exceptionOrNull()?.message}")
                    return@launch
                }
            }

            val state = _uiState.value
            val sb = StringBuilder()
            var lastSpeed = 0f

            val resp = NativeAiBridge.nativeGenerateLlm(
                systemPrompt = state.systemPrompt,
                userPrompt = clean,
                nCtx = state.contextSize,
                temperature = state.temperature,
                maxTokens = state.maxTokens,
                callback = object : TokenStreamCallback {
                    override fun onToken(token: String, tokPerSec: Float) {
                        sb.append(token)
                        lastSpeed = tokPerSec
                        val snapshot = sb.toString()
                        _uiState.update { st ->
                            st.copy(
                                liveTokPerSec = tokPerSec,
                                messages = st.messages.map { msg ->
                                    if (msg.id == assistantId) msg.copy(text = snapshot, tokPerSec = tokPerSec)
                                    else msg
                                }
                            )
                        }
                    }
                }
            )

            if (resp.startsWith("ERR:")) {
                appendAssistantError(assistantId, resp.removePrefix("ERR:"))
            } else {
                _uiState.update { it.copy(isGenerating = false, liveTokPerSec = lastSpeed) }
            }
        }
    }

    fun stopGeneration() {
        if (NativeAiBridge.coreLoaded) {
            NativeAiBridge.nativeCancelLlm()
        }
        genJob?.cancel()
        _uiState.update { it.copy(isGenerating = false) }
    }

    private fun appendAssistantError(assistantId: Long, errorText: String) {
        _uiState.update { st ->
            st.copy(
                isGenerating = false,
                statusMessage = errorText,
                messages = st.messages.map { msg ->
                    if (msg.id == assistantId) msg.copy(text = "⚠️ $errorText") else msg
                }
            )
        }
    }
}
