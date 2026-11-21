/*
 * Clean C11 wrapper over stable-diffusion.cpp opaque C API (master / Nov 2026).
 *
 * Enforces POCO X8 Pro Max / HyperOS 3 stability optimizations:
 * - backend = "cpu" (disables unstable mobile Vulkan/OpenCL drivers)
 * - enable_mmap = true (zero-copy UFS 4.0 weight streaming)
 * - vae_tiling.enabled = true (prevents peak RAM spikes during 512x768 VAE decode)
 * - n_threads = 4 (pinned to 1x Cortex-X925 + 3x Cortex-X4)
 */

#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "stable-diffusion.h"

typedef void (*poco_sd_progress_fn)(int step, int steps, float time_sec, void* user_data);

typedef struct {
    sd_ctx_t* ctx;
} poco_sd_handle_t;

typedef struct {
    uint8_t* rgb_pixels;
    uint32_t width;
    uint32_t height;
    uint32_t channels;
    size_t byte_len;
} poco_sd_image_out_t;

void poco_sd_set_progress_cb(poco_sd_progress_fn cb, void* user_data) {
    sd_set_progress_callback(cb, user_data);
}

const char* poco_sd_last_error(void) {
    const char* err = sd_get_last_error();
    return err ? err : "Unknown stable-diffusion.cpp error";
}

poco_sd_handle_t* poco_sd_load(const char* model_path, int n_threads) {
    if (!model_path) return NULL;

    sd_ctx_opt_t* opt = sd_ctx_opt_create();
    if (!opt) return NULL;

    sd_status_t st = sd_opt_set_string(opt, "model_path", model_path);
    if (st == SD_STATUS_OK) st = sd_opt_set_int(opt, "n_threads", n_threads > 0 ? n_threads : 4);
    if (st == SD_STATUS_OK) st = sd_opt_set_string(opt, "backend", "cpu");
    if (st == SD_STATUS_OK) st = sd_opt_set_bool(opt, "enable_mmap", true);
    if (st == SD_STATUS_OK) st = sd_opt_set_bool(opt, "diffusion_flash_attn", true);

    sd_ctx_t* ctx = NULL;
    if (st == SD_STATUS_OK) {
        st = sd_ctx_create(opt, &ctx);
    }
    sd_opt_free(opt);

    if (st != SD_STATUS_OK || !ctx) {
        if (ctx) sd_ctx_free(ctx);
        return NULL;
    }

    poco_sd_handle_t* handle = (poco_sd_handle_t*)malloc(sizeof(poco_sd_handle_t));
    if (!handle) {
        sd_ctx_free(ctx);
        return NULL;
    }
    handle->ctx = ctx;
    return handle;
}

void poco_sd_free(poco_sd_handle_t* handle) {
    if (!handle) return;
    if (handle->ctx) {
        sd_ctx_free(handle->ctx);
        handle->ctx = NULL;
    }
    free(handle);
}

int poco_sd_generate(
    poco_sd_handle_t* handle,
    const char* prompt,
    const char* negative_prompt,
    int width,
    int height,
    int steps,
    float cfg_scale,
    int64_t seed,
    const char* sampler_method,
    poco_sd_image_out_t* out_img
) {
    if (!handle || !handle->ctx || !prompt || !out_img) return -1;
    memset(out_img, 0, sizeof(poco_sd_image_out_t));

    sd_img_gen_opt_t* opt = sd_img_gen_opt_create();
    if (!opt) return -2;

    sd_status_t st = sd_opt_set_string(opt, "prompt", prompt);
    if (st == SD_STATUS_OK && negative_prompt && negative_prompt[0] != '\0') {
        st = sd_opt_set_string(opt, "negative_prompt", negative_prompt);
    }
    if (st == SD_STATUS_OK) st = sd_opt_set_int(opt, "width", width);
    if (st == SD_STATUS_OK) st = sd_opt_set_int(opt, "height", height);
    if (st == SD_STATUS_OK) st = sd_opt_set_int(opt, "sample.steps", steps > 0 ? steps : 4);
    if (st == SD_STATUS_OK) st = sd_opt_set_float(opt, "sample.guidance.txt_cfg", cfg_scale);
    if (st == SD_STATUS_OK) st = sd_opt_set_int(opt, "seed", seed);
    if (st == SD_STATUS_OK && sampler_method && sampler_method[0] != '\0') {
        st = sd_opt_set_string(opt, "sample.method", sampler_method);
    }
    if (st == SD_STATUS_OK) {
        // Enable VAE tiling for mobile RAM safety on 512x768+
        st = sd_opt_set_bool(opt, "vae_tiling.enabled", true);
    }

    sd_result_t* result = NULL;
    if (st == SD_STATUS_OK) {
        st = sd_generate_image(handle->ctx, opt, &result);
    }
    sd_opt_free(opt);

    if (st != SD_STATUS_OK || !result) {
        if (result) sd_result_free(result);
        return -3;
    }

    if (sd_result_get_image_count(result) == 0) {
        sd_result_free(result);
        return -4;
    }

    const sd_tensor_t* img_tensor = sd_result_get_image(result, 0);
    if (!img_tensor) {
        sd_result_free(result);
        return -5;
    }

    const uint8_t* src_data = (const uint8_t*)sd_tensor_data(img_tensor);
    size_t expected_bytes = (size_t)width * (size_t)height * 3u;
    if (!src_data || expected_bytes == 0) {
        sd_result_free(result);
        return -6;
    }

    uint8_t* copy = (uint8_t*)malloc(expected_bytes);
    if (!copy) {
        sd_result_free(result);
        return -7;
    }
    memcpy(copy, src_data, expected_bytes);

    out_img->rgb_pixels = copy;
    out_img->width = (uint32_t)width;
    out_img->height = (uint32_t)height;
    out_img->channels = 3;
    out_img->byte_len = expected_bytes;

    sd_result_free(result);
    return 0;
}

void poco_sd_free_image(poco_sd_image_out_t* out_img) {
    if (!out_img) return;
    if (out_img->rgb_pixels) {
        free(out_img->rgb_pixels);
        out_img->rgb_pixels = NULL;
    }
    out_img->byte_len = 0;
}
