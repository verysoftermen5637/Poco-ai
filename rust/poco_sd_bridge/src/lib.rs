//! Isolated Rust JNI Bridge for `libpoco_sd_bridge.so` (stable-diffusion.cpp).
//!
//! Kept in a separate `.so` from `libpoco_ai_core.so` so `stable-diffusion.cpp`'s GGML
//! symbols never collide with `llama.cpp`'s GGML symbols at link or dlopen time.

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jfloat, jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use std::ffi::{c_char, c_float, c_int, c_void, CStr, CString};
use std::fs::{self, File};
use std::io::BufWriter;
use std::path::Path;

#[repr(C)]
struct PocoSdHandle {
    _private: [u8; 0],
}

#[repr(C)]
struct PocoSdImageOut {
    rgb_pixels: *mut u8,
    width: u32,
    height: u32,
    channels: u32,
    byte_len: usize,
}

type ProgressFn = extern "C" fn(step: c_int, steps: c_int, time_sec: c_float, user_data: *mut c_void);

extern "C" {
    fn poco_sd_set_progress_cb(cb: Option<ProgressFn>, user_data: *mut c_void);
    fn poco_sd_last_error() -> *const c_char;
    fn poco_sd_load(model_path: *const c_char, n_threads: c_int) -> *mut PocoSdHandle;
    fn poco_sd_free(handle: *mut PocoSdHandle);
    fn poco_sd_generate(
        handle: *mut PocoSdHandle,
        prompt: *const c_char,
        negative_prompt: *const c_char,
        width: c_int,
        height: c_int,
        steps: c_int,
        cfg_scale: c_float,
        seed: i64,
        sampler_method: *const c_char,
        out_img: *mut PocoSdImageOut,
    ) -> c_int;
    fn poco_sd_free_image(out_img: *mut PocoSdImageOut);
}

struct SendHandle(*mut PocoSdHandle);
unsafe impl Send for SendHandle {}

static SD_STATE: Lazy<Mutex<Option<SendHandle>>> = Lazy::new(|| Mutex::new(None));

struct ProgressContext {
    jvm: JavaVM,
    callback: GlobalRef,
}

extern "C" fn sd_progress_trampoline(
    step: c_int,
    steps: c_int,
    time_sec: c_float,
    user_data: *mut c_void,
) {
    if user_data.is_null() {
        return;
    }
    let ctx = unsafe { &*(user_data as *const ProgressContext) };
    if let Ok(mut env) = ctx.jvm.attach_current_thread_as_daemon() {
        let _ = env.call_method(
            ctx.callback.as_obj(),
            "onProgress",
            "(IIF)V",
            &[
                JValue::Int(step as jint),
                JValue::Int(steps as jint),
                JValue::Float(time_sec as jfloat),
            ],
        );
    }
}

/// Pins current thread strictly to the 4 highest-clocked cores (Dimensity 9500s: X925 + 3x X4).
fn pin_big_cores() -> usize {
    let mut freqs: Vec<(usize, u64)> = Vec::new();
    for cpu_id in 0..32 {
        let dir = format!("/sys/devices/system/cpu/cpu{cpu_id}");
        if !Path::new(&dir).exists() {
            break;
        }
        let freq = fs::read_to_string(format!("{dir}/cpufreq/cpuinfo_max_freq"))
            .ok()
            .and_then(|s| s.trim().parse::<u64>().ok())
            .unwrap_or((cpu_id as u64 + 1) * 500_000);
        freqs.push((cpu_id, freq));
    }
    freqs.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| b.0.cmp(&a.0)));
    let top4: Vec<usize> = freqs.into_iter().take(4).map(|(id, _)| id).collect();
    #[cfg(any(target_os = "android", target_os = "linux"))]
    if !top4.is_empty() {
        unsafe {
            let mut set: libc::cpu_set_t = std::mem::zeroed();
            libc::CPU_ZERO(&mut set);
            for &cpu in &top4 {
                if cpu < libc::CPU_SETSIZE as usize {
                    libc::CPU_SET(cpu, &mut set);
                }
            }
            let _ = libc::sched_setaffinity(0, std::mem::size_of::<libc::cpu_set_t>(), &set);
        }
    }
    top4.len().clamp(1, 4)
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

fn last_error_string() -> String {
    unsafe {
        let ptr = poco_sd_last_error();
        if ptr.is_null() {
            "Unknown error".to_string()
        } else {
            CStr::from_ptr(ptr).to_string_lossy().into_owned()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_poco_aistudio_nativebridge_NativeAiBridge_nativeLoadSdModel(
    mut env: JNIEnv,
    _class: JClass,
    model_path: JString,
) -> jstring {
    let path_str = get_string(&mut env, &model_path);
    if !Path::new(&path_str).exists() {
        return to_jstring(&mut env, &format!("ERR:Model file not found: {path_str}"));
    }

    let n_threads = pin_big_cores() as c_int;
    let c_path = match CString::new(path_str.as_str()) {
        Ok(c) => c,
        Err(_) => return to_jstring(&mut env, "ERR:Invalid model path"),
    };

    let mut guard = SD_STATE.lock();
    if let Some(prev) = guard.take() {
        unsafe { poco_sd_free(prev.0) };
    }

    let handle = unsafe { poco_sd_load(c_path.as_ptr(), n_threads) };
    if handle.is_null() {
        let err = last_error_string();
        return to_jstring(&mut env, &format!("ERR:{err}"));
    }

    *guard = Some(SendHandle(handle));
    to_jstring(
        &mut env,
        &format!("OK:Stable Diffusion ready ({n_threads} big cores, mmap, CPU KleidiAI)"),
    )
}

#[no_mangle]
pub extern "system" fn Java_com_poco_aistudio_nativebridge_NativeAiBridge_nativeUnloadSdModel(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut guard = SD_STATE.lock();
    if let Some(prev) = guard.take() {
        unsafe { poco_sd_free(prev.0) };
    }
}

#[no_mangle]
pub extern "system" fn Java_com_poco_aistudio_nativebridge_NativeAiBridge_nativeGenerateImage(
    mut env: JNIEnv,
    _class: JClass,
    prompt: JString,
    negative_prompt: JString,
    width: jint,
    height: jint,
    steps: jint,
    cfg_scale: jfloat,
    seed: jlong,
    sampler: JString,
    output_png_path: JString,
    callback: JObject,
) -> jstring {
    let prompt_str = get_string(&mut env, &prompt);
    let neg_str = get_string(&mut env, &negative_prompt);
    let sampler_str = get_string(&mut env, &sampler);
    let out_path_str = get_string(&mut env, &output_png_path);

    let _ = pin_big_cores();

    let guard = SD_STATE.lock();
    let handle = match guard.as_ref() {
        Some(h) => h.0,
        None => return to_jstring(&mut env, "ERR:No Stable Diffusion model loaded"),
    };

    let c_prompt = CString::new(prompt_str).unwrap_or_default();
    let c_neg = CString::new(neg_str).unwrap_or_default();
    let c_sampler = CString::new(if sampler_str.is_empty() {
        "euler_a".to_string()
    } else {
        sampler_str
    })
    .unwrap_or_default();

    let progress_ctx = env
        .get_java_vm()
        .ok()
        .and_then(|jvm| {
            env.new_global_ref(&callback)
                .ok()
                .map(|cb| Box::new(ProgressContext { jvm, callback: cb }))
        });

    let ctx_ptr = progress_ctx
        .as_ref()
        .map(|b| b.as_ref() as *const ProgressContext as *mut c_void)
        .unwrap_or(std::ptr::null_mut());

    if !ctx_ptr.is_null() {
        unsafe { poco_sd_set_progress_cb(Some(sd_progress_trampoline), ctx_ptr) };
    }

    let mut img_out = PocoSdImageOut {
        rgb_pixels: std::ptr::null_mut(),
        width: 0,
        height: 0,
        channels: 0,
        byte_len: 0,
    };

    let rc = unsafe {
        poco_sd_generate(
            handle,
            c_prompt.as_ptr(),
            c_neg.as_ptr(),
            width as c_int,
            height as c_int,
            steps.clamp(1, 50) as c_int,
            cfg_scale as c_float,
            seed as i64,
            c_sampler.as_ptr(),
            &mut img_out,
        )
    };

    unsafe { poco_sd_set_progress_cb(None, std::ptr::null_mut()) };
    drop(progress_ctx);

    if rc != 0 || img_out.rgb_pixels.is_null() {
        let err = last_error_string();
        unsafe { poco_sd_free_image(&mut img_out) };
        return to_jstring(&mut env, &format!("ERR:Generation failed (code {rc}): {err}"));
    }

    let save_res = (|| -> Result<(), String> {
        let pixels = unsafe { std::slice::from_raw_parts(img_out.rgb_pixels, img_out.byte_len) };
        let file = File::create(&out_path_str).map_err(|e| format!("Cannot create PNG: {e}"))?;
        let w = BufWriter::new(file);
        let mut encoder = png::Encoder::new(w, img_out.width, img_out.height);
        encoder.set_color(png::ColorType::Rgb);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder
            .write_header()
            .map_err(|e| format!("PNG header error: {e}"))?;
        writer
            .write_image_data(pixels)
            .map_err(|e| format!("PNG write error: {e}"))?;
        Ok(())
    })();

    unsafe { poco_sd_free_image(&mut img_out) };

    match save_res {
        Ok(()) => to_jstring(&mut env, &format!("OK:{out_path_str}")),
        Err(e) => to_jstring(&mut env, &format!("ERR:{e}")),
    }
}
