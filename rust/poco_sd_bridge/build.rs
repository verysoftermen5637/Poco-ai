use std::env;
use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-changed=csrc/sd_wrapper.c");
    println!("cargo:rerun-if-env-changed=SD_CPP_DIR");
    println!("cargo:rerun-if-env-changed=SD_CPP_BUILD_DIR");

    let sd_dir = env::var("SD_CPP_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("../third_party/stable-diffusion.cpp"));

    let sd_build_dir = env::var("SD_CPP_BUILD_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| sd_dir.join("build-android-arm64"));

    // Compile thin C11 wrapper against stable-diffusion.h (verified opaque C API)
    let mut build = cc::Build::new();
    build
        .file("csrc/sd_wrapper.c")
        .include(&sd_dir)
        .include(sd_dir.join("include"))
        .flag_if_supported("-std=c11")
        .flag_if_supported("-O3")
        .flag_if_supported("-fvisibility=hidden");

    if env::var("CARGO_CFG_TARGET_ARCH").as_deref() == Ok("aarch64") {
        build.flag_if_supported("-march=armv8.2-a+dotprod+i8mm+fp16");
    }

    build.compile("poco_sd_wrapper");

    // Link prebuilt static stable-diffusion.cpp + ggml libraries from CMake build directory
    let search_dirs = [
        sd_build_dir.clone(),
        sd_build_dir.join("ggml").join("src"),
        sd_build_dir.join("thirdparty"),
    ];
    for dir in &search_dirs {
        if dir.exists() {
            println!("cargo:rustc-link-search=native={}", dir.display());
        }
    }

    println!("cargo:rustc-link-lib=static=stable-diffusion");

    // Link ggml static archives that CMake produced (ggml, ggml-base, ggml-cpu)
    for lib in ["ggml", "ggml-base", "ggml-cpu"] {
        let candidate_main = sd_build_dir.join("ggml").join("src").join(format!("lib{lib}.a"));
        let candidate_root = sd_build_dir.join(format!("lib{lib}.a"));
        if candidate_main.exists() || candidate_root.exists() {
            println!("cargo:rustc-link-lib=static={lib}");
        }
    }

    // Android NDK C++ runtime
    if env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo:rustc-link-lib=c++_shared");
        println!("cargo:rustc-link-lib=log");
        println!("cargo:rustc-link-lib=m");
    }
}
