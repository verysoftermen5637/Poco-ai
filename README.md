# POCO X8 Pro Max AI Studio

Нативная оффлайн ИИ-студия для Android (Rust JNI + Kotlin Jetpack Compose, AMOLED Black), оптимизированная под **POCO X8 Pro Max** (MediaTek Dimensity 9500s: 1× Cortex-X925 + 3× Cortex-X4 + 4× Cortex-A720, ARMv9.2, 12 ГБ LPDDR5X, UFS 4.0, Android 16 / HyperOS 3).

## Аппаратные оптимизации
- **ARMv9.2 SIMD + Arm KleidiAI**: `.cargo/config.toml` и CMake используют `+neon,+dotprod,+i8mm,+fp16` и `GGML_CPU_KLEIDIAI=ON` (без SVE2-инструкций для защиты от `SIGILL` на HyperOS/NDK r28).
- **16 KB ELF Page Alignment**: `-Wl,-z,max-page-size=16384` и `useLegacyPackaging = false` для нативной совместимости с Android 15/16.
- **CPU-only + Защита от LMKD**: Vulkan/OpenCL принудительно отключены, веса читаются через `mmap`, включён `android:largeHeap="true"`, а глобальный `NativeAiBridge` автоматически выгружает предыдущую модель из ОЗУ перед загрузкой новой.
- **Big-Core Pinning**: детектор `/sys/devices/system/cpu/` + `sched_setaffinity` привязывает рабочие потоки строго к 4 старшим ядрам (1× Cortex-X925 + 3× Cortex-X4), исключая Cortex-A720.
- **Изолированные `.so`**: `libpoco_ai_core.so` (`llama.cpp` + `candle`) и `libpoco_sd_bridge.so` (`stable-diffusion.cpp`) собираются раздельно, что полностью исключает конфликт символов `ggml_*`.

## Облачная сборка APK (GitHub Actions)
1. Создай новый репозиторий на GitHub и загрузи туда распакованное содержимое `poco-ai-studio.zip` (обязательно сохранив скрытые папки `.github/` и `.cargo/`).
2. Открой вкладку **Actions** → **Build POCO X8 Pro Max AI Studio APK** (сборка запустится автоматически при пуше в `main`/`master` или вручную через **Run workflow**).
3. После завершения скачай артефакт **`POCO-X8-Pro-Max-AI-Studio`** с подписанным `app-release.apk` и установи на смартфон.
