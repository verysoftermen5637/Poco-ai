//! JNI Entrypoint for `libpoco_ai_core.so` (LLM + MusicGen Audio + Dimensity 9500s Topology).
//!
//! Includes a global RAM guard: before loading an LLM or MusicGen model, the engine
//! automatically evicts any previously loaded weights from RAM to stay well under
//! HyperOS 3 LMKD thresholds on 12 GB LPDDR5X.

mod audio_engine;
mod cpu_topology;
mod llm_engine;

use audio_engine::AudioEngine;
use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jfloat, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use llm_engine::LlmEngine;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use std::sync::atomic::Ordering;

static LLM_ENGINE: Lazy<Mutex<LlmEngine>> = Lazy::new(|| Mutex::new(LlmEngine::new()));
static AUDIO_ENGINE: Lazy<Mutex<AudioEngine>> = Lazy::new(|| Mutex::new(AudioEngine::new()));

fn init_android_logging() {
    #[cfg(target_os = "android")]
    {
        use std::sync::Once;
        static INIT: Once = Once::new();
        INIT.call_once(|| {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Info)
                    .with_tag("PocoAiCore"),
            );
        });
    }
}

fn to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .map(|js| js.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn get_string(env: &mut JNIEnv, js: &JString) -> String {
    env.get_string(js)
        .map(|s| s.into())
        .unwrap_or_default()
}

/// Returns JSON describing CPU topology and pinned performance cores (1x X925 + 3x X4).
#[no_mangle]
pub extern "system" fn Java_com_poco_aistudio_nativebridge_NativeAiBridge_nativeGetCpuTopologyJson(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_android_logging();
    let report = cpu_topology::detect_topology(4);
    let json = serde_json::to_string(&report).unwrap_or_else(|_| "{}".to_string());
    to_jstring(&mut env, &json)
}

/// Explicitly unloads both LLM and Audio engines from RAM (called before loading Stable Diffusion).
#[no_mangle]
pub extern "system" fn Java_com_poco_aistudio_nativebridge_NativeAiBridge_nativeUnloadCoreModels(
    _env: JNIEnv,
    _class: JClass,
) {
    LLM_ENGINE.lock().unload();
    AUDIO_ENGINE.lock().unload();
}

/// Loads a `.gguf` LLM model (automatically unloading Audio weights first).
#[no_mangle]
pub extern "system" fn Java_com_poco_aistudio_nativebridge_NativeAiBridge_nativeLoadLlm(
    mut env: JNIEnv,
    _class: JClass,
    model_path: JString,
) -> jstring {
    init_android_logging();
    let path = get_string(&mut env, &model_path);

    // RAM Guard: evict Audio weights before loading a multi-GB GGUF LLM.
    AUDIO_ENGINE.lock().unload();

    let mut llm = LLM_ENGINE.lock();
    match llm.load_model(&path) {
        Ok(info) => to_jstring(&mut env, &format!("OK:{info}")),
        Err(e) => to_jstring(&mut env, &format!("ERR:{e:#}")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_poco_aistudio_nativebridge_NativeAiBridge_nativeUnloadLlm(
    _env: JNIEnv,
    _class: JClass,
) {
    LLM_ENGINE.lock().unload();
}

#[no_mangle]
pub extern "system" fn Java_com_poco_aistudio_nativebridge_NativeAiBridge_nativeCancelLlm(
    _env: JNIEnv,
    _class: JClass,
) {
    let cancel = LLM_ENGINE.lock().cancel_handle();
    cancel.store(true, Ordering::SeqCst);
}

/// Runs streaming LLM inference, calling `callback.onToken(String token, float tokPerSec)`.
#[no_mangle]
pub extern "system" fn Java_com_poco_aistudio_nativebridge_NativeAiBridge_nativeGenerateLlm(
    mut env: JNIEnv,
    _class: JClass,
    system_prompt: JString,
    user_prompt: JString,
    n_ctx: jint,
    temperature: jfloat,
    max_tokens: jint,
    callback: JObject,
) -> jstring {
    let sys_str = get_string(&mut env, &system_prompt);
    let user_str = get_string(&mut env, &user_prompt);

    let llm = LLM_ENGINE.lock();
    let result = llm.generate_stream(
        &sys_str,
        &user_str,
        n_ctx.max(512) as u32,
        temperature,
        max_tokens.max(64) as u32,
        |piece, tok_per_sec| {
            if let Ok(j_piece) = env.new_string(piece) {
                let _ = env.call_method(
                    &callback,
                    "onToken",
                    "(Ljava/lang/String;F)V",
                    &[JValue::Object(&j_piece), JValue::Float(tok_per_sec)],
                );
                let _ = env.delete_local_ref(j_piece);
            }
        },
    );

    match result {
        Ok(_) => to_jstring(&mut env, "OK"),
        Err(e) => to_jstring(&mut env, &format!("ERR:{e:#}")),
    }
}

/// Loads a MusicGen safetensors model directory (automatically unloading LLM weights first).
#[no_mangle]
pub extern "system" fn Java_com_poco_aistudio_nativebridge_NativeAiBridge_nativeLoadAudioModel(
    mut env: JNIEnv,
    _class: JClass,
    model_dir: JString,
) -> jstring {
    init_android_logging();
    let dir = get_string(&mut env, &model_dir);

    // RAM Guard: evict LLM weights before loading MusicGen + T5 + EnCodec.
    LLM_ENGINE.lock().unload();

    let mut audio = AUDIO_ENGINE.lock();
    match audio.load_model(&dir) {
        Ok(info) => to_jstring(&mut env, &format!("OK:{info}")),
        Err(e) => to_jstring(&mut env, &format!("ERR:{e:#}")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_poco_aistudio_nativebridge_NativeAiBridge_nativeUnloadAudioModel(
    _env: JNIEnv,
    _class: JClass,
) {
    AUDIO_ENGINE.lock().unload();
}

#[no_mangle]
pub extern "system" fn Java_com_poco_aistudio_nativebridge_NativeAiBridge_nativeCancelAudio(
    _env: JNIEnv,
    _class: JClass,
) {
    let cancel = AUDIO_ENGINE.lock().cancel_handle();
    cancel.store(true, Ordering::SeqCst);
}

/// Generates a WAV file with MusicGen and invokes `callback.onProgress(int step, int totalSteps)`.
/// Returns JSON `AudioGenerationResult` prefixed with `OK:` or error prefixed with `ERR:`.
#[no_mangle]
pub extern "system" fn Java_com_poco_aistudio_nativebridge_NativeAiBridge_nativeGenerateAudio(
    mut env: JNIEnv,
    _class: JClass,
    prompt: JString,
    duration_sec: jfloat,
    guidance_scale: jfloat,
    output_wav_path: JString,
    callback: JObject,
) -> jstring {
    let prompt_str = get_string(&mut env, &prompt);
    let out_path = get_string(&mut env, &output_wav_path);

    let mut audio = AUDIO_ENGINE.lock();
    let res = audio.generate_wav(
        &prompt_str,
        duration_sec,
        guidance_scale,
        &out_path,
        |step, total| {
            let _ = env.call_method(
                &callback,
                "onProgress",
                "(II)V",
                &[JValue::Int(step as jint), JValue::Int(total as jint)],
            );
        },
    );

    match res {
        Ok(meta) => {
            let json = serde_json::to_string(&meta).unwrap_or_else(|_| "{}".to_string());
            to_jstring(&mut env, &format!("OK:{json}"))
        }
        Err(e) => to_jstring(&mut env, &format!("ERR:{e:#}")),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_poco_aistudio_nativebridge_NativeAiBridge_nativeIsLlmLoaded(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if LLM_ENGINE.lock().is_loaded() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
