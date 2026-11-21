package com.poco.aistudio.data

enum class ModelCategory(val titleRu: String, val folderName: String) {
    LLM("Текст и Код (LLM)", "llm"),
    IMAGE("Генерация Фото", "image"),
    AUDIO("Музыка и Звук", "audio")
}

data class ModelFileSpec(
    val fileName: String,
    val downloadUrl: String
)

data class ModelPreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val category: ModelCategory,
    val quantLabel: String,
    val approxSizeGb: String,
    val files: List<ModelFileSpec>,
    val isBundleDirectory: Boolean = false,
    val defaultSteps: Int = 4,
    val defaultCfg: Float = 1.0f
)

/**
 * Curated Hugging Face presets tailored for POCO X8 Pro Max (12 GB LPDDR5X, UFS 4.0, Dimensity 9500s).
 */
object ModelCatalog {
    val presets: List<ModelPreset> = listOf(
        // === Раздел 1: Текст и Код (LLM) ===
        ModelPreset(
            id = "qwen25_coder_3b_q4km",
            name = "Qwen2.5-Coder-3B-Q4_K_M",
            subtitle = "Сверхбыстрая модель для кода, скриптов и дебага (32K контекст)",
            category = ModelCategory.LLM,
            quantLabel = "GGUF Q4_K_M",
            approxSizeGb = "1.93 ГБ",
            files = listOf(
                ModelFileSpec(
                    fileName = "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
                    downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf"
                )
            )
        ),
        ModelPreset(
            id = "qwen25_7b_instruct_q4km",
            name = "Qwen2.5-7B-Instruct-Q4_K_M",
            subtitle = "Флагманская универсальная модель для сложных задач и русского языка",
            category = ModelCategory.LLM,
            quantLabel = "GGUF Q4_K_M",
            approxSizeGb = "4.68 ГБ",
            files = listOf(
                ModelFileSpec(
                    fileName = "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
                    downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf"
                )
            )
        ),
        ModelPreset(
            id = "gemma3_4b_it_q4km",
            name = "Gemma-3-4B-it-Q4_K_M",
            subtitle = "Новое поколение Google Gemma 3: отличный баланс скорости и качества",
            category = ModelCategory.LLM,
            quantLabel = "GGUF Q4_K_M",
            approxSizeGb = "2.49 ГБ",
            files = listOf(
                ModelFileSpec(
                    fileName = "gemma-3-4b-it-Q4_K_M.gguf",
                    downloadUrl = "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf"
                )
            )
        ),

        // === Раздел 2: Генерация Фото (Text-to-Image) ===
        ModelPreset(
            id = "sdxl_turbo_q4_0",
            name = "SDXL-Turbo (Q4_0 GGUF)",
            subtitle = "Мгновенная генерация за 1–4 шага на базе SDXL 1.0 (CFG 1.0)",
            category = ModelCategory.IMAGE,
            quantLabel = "GGUF Q4_0",
            approxSizeGb = "2.10 ГБ",
            files = listOf(
                ModelFileSpec(
                    fileName = "sdxl-turbo-q4_0.gguf",
                    downloadUrl = "https://huggingface.co/OlegSkutte/sdxl-turbo-GGUF/resolve/main/sdxl_turbo-Q4_0.gguf"
                )
            ),
            defaultSteps = 4,
            defaultCfg = 1.0f
        ),
        ModelPreset(
            id = "sd15_lcm_q8_0",
            name = "SD-1.5-LCM (Q8_0 GGUF)",
            subtitle = "Компактный чекпоинт SD 1.5 Q8_0 под быструю генерацию 4–8 шагов",
            category = ModelCategory.IMAGE,
            quantLabel = "GGUF Q8_0",
            approxSizeGb = "1.76 ГБ",
            files = listOf(
                ModelFileSpec(
                    fileName = "stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf",
                    downloadUrl = "https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF/resolve/main/stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf"
                )
            ),
            defaultSteps = 4,
            defaultCfg = 1.5f
        ),

        // === Раздел 3: Генерация Музыки и Звука (Text-to-Audio) ===
        ModelPreset(
            id = "musicgen_small_300m",
            name = "Facebook MusicGen-Small (300M)",
            subtitle = "Моно 32 кГц: биты, мелодии и SFX по текстовому описанию (Candle)",
            category = ModelCategory.AUDIO,
            quantLabel = "Safetensors",
            approxSizeGb = "2.36 ГБ",
            isBundleDirectory = true,
            files = listOf(
                ModelFileSpec(
                    fileName = "config.json",
                    downloadUrl = "https://huggingface.co/facebook/musicgen-small/resolve/main/config.json"
                ),
                ModelFileSpec(
                    fileName = "tokenizer.json",
                    downloadUrl = "https://huggingface.co/facebook/musicgen-small/resolve/main/tokenizer.json"
                ),
                ModelFileSpec(
                    fileName = "model.safetensors",
                    downloadUrl = "https://huggingface.co/facebook/musicgen-small/resolve/main/model.safetensors"
                )
            )
        ),
        ModelPreset(
            id = "musicgen_stereo_small",
            name = "MusicGen-Stereo-Small",
            subtitle = "Стерео 32 кГц (8 кодбуков): объёмные треки и саундтреки (Candle)",
            category = ModelCategory.AUDIO,
            quantLabel = "Safetensors Stereo",
            approxSizeGb = "2.40 ГБ",
            isBundleDirectory = true,
            files = listOf(
                ModelFileSpec(
                    fileName = "config.json",
                    downloadUrl = "https://huggingface.co/facebook/musicgen-stereo-small/resolve/main/config.json"
                ),
                ModelFileSpec(
                    fileName = "tokenizer.json",
                    downloadUrl = "https://huggingface.co/facebook/musicgen-stereo-small/resolve/main/tokenizer.json"
                ),
                ModelFileSpec(
                    fileName = "model.safetensors",
                    downloadUrl = "https://huggingface.co/facebook/musicgen-stereo-small/resolve/main/model.safetensors"
                )
            )
        )
    )

    fun findById(id: String): ModelPreset? = presets.firstOrNull { it.id == id }
}
