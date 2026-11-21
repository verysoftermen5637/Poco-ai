//! Offline GGUF LLM Engine powered by `llama-cpp-2` (llama.cpp + Arm KleidiAI).
//!
//! Hardware protections for POCO X8 Pro Max (Dimensity 9500s / 12 GB LPDDR5X / HyperOS 3):
//! - Forces CPU-only backend (`n_gpu_layers = 0`) to avoid Mali/Immortalis Vulkan/OpenCL driver crashes.
//! - Uses `mmap` (enabled by default in llama.cpp) so 4–7 GB GGUF weights stream cleanly from UFS 4.0.
//! - Pins worker threads strictly to the 4 big cores (1x Cortex-X925 + 3x Cortex-X4).
//! - Provides instant `unload()` so the global RAM guard can evict weights before loading SD/MusicGen.

use crate::cpu_topology;
use anyhow::{anyhow, Context, Result};
use llama_cpp_2::context::params::LlamaContextParams;
use llama_cpp_2::llama_backend::LlamaBackend;
use llama_cpp_2::llama_batch::LlamaBatch;
use llama_cpp_2::model::params::LlamaModelParams;
use llama_cpp_2::model::{AddBos, LlamaModel, Special};
use llama_cpp_2::sampling::LlamaSampler;
use once_cell::sync::OnceCell;
use std::num::NonZeroU32;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Instant;

static LLAMA_BACKEND: OnceCell<LlamaBackend> = OnceCell::new();

fn get_backend() -> Result<&'static LlamaBackend> {
    LLAMA_BACKEND.get_or_try_init(|| {
        LlamaBackend::init().map_err(|e| anyhow!("Failed to initialize llama.cpp backend: {e:?}"))
    })
}

pub struct LlmEngine {
    model: Option<LlamaModel>,
    model_path: Option<String>,
    perf_cores: Vec<usize>,
    cancel_flag: Arc<AtomicBool>,
}

impl LlmEngine {
    pub fn new() -> Self {
        let report = cpu_topology::detect_topology(4);
        Self {
            model: None,
            model_path: None,
            perf_cores: report.performance_core_ids,
            cancel_flag: Arc::new(AtomicBool::new(false)),
        }
    }

    pub fn cancel_handle(&self) -> Arc<AtomicBool> {
        Arc::clone(&self.cancel_flag)
    }

    pub fn is_loaded(&self) -> bool {
        self.model.is_some()
    }

    pub fn loaded_path(&self) -> Option<&str> {
        self.model_path.as_deref()
    }

    /// Immediately drops the loaded model and releases its mmap/RAM footprint.
    pub fn unload(&mut self) {
        self.cancel_flag.store(true, Ordering::SeqCst);
        self.model = None;
        self.model_path = None;
    }

    /// Loads a `.gguf` model with mmap and CPU-only execution (zero GPU offload).
    pub fn load_model(&mut self, path: &str) -> Result<String> {
        if !Path::new(path).exists() {
            return Err(anyhow!("GGUF model file not found: {path}"));
        }

        // Always unload previous model first to prevent HyperOS LMKD OOM kills.
        self.unload();
        self.cancel_flag.store(false, Ordering::SeqCst);

        // Pin current thread to 4 performance cores before loading/initializing threads.
        cpu_topology::pin_current_thread_to_cores(&self.perf_cores);

        let backend = get_backend()?;
        // CPU-only: n_gpu_layers = 0 (Vulkan/OpenCL disabled at compile & runtime).
        // mmap is enabled by default in llama_model_default_params().
        let model_params = LlamaModelParams::default().with_n_gpu_layers(0);

        let model = LlamaModel::load_from_file(backend, path, &model_params)
            .with_context(|| format!("Failed to load GGUF model from {path}"))?;

        let n_params = model.n_params();
        let size_mb = model.size() as f64 / (1024.0 * 1024.0);
        self.model = Some(model);
        self.model_path = Some(path.to_string());

        Ok(format!(
            "Loaded ({:.1} MB, {:.2}B params, {} big cores pinned)",
            size_mb,
            n_params as f64 / 1e9,
            self.perf_cores.len()
        ))
    }

    /// Formats a prompt cleanly using ChatML (Qwen2.5) or Gemma-3 turn markers based on filename.
    fn format_prompt(&self, system_prompt: &str, user_prompt: &str) -> String {
        let lower_path = self
            .model_path
            .as_deref()
            .unwrap_or("")
            .to_ascii_lowercase();

        if lower_path.contains("gemma") {
            let sys_prefix = if system_prompt.trim().is_empty() {
                String::new()
            } else {
                format!("{}\n\n", system_prompt.trim())
            };
            format!(
                "<start_of_turn>user\n{}{}<end_of_turn>\n<start_of_turn>model\n",
                sys_prefix,
                user_prompt.trim()
            )
        } else {
            // Standard ChatML (Qwen2.5 / Qwen2.5-Coder / DeepSeek / generic GGUF)
            let sys = if system_prompt.trim().is_empty() {
                "You are a helpful, concise offline AI assistant."
            } else {
                system_prompt.trim()
            };
            format!(
                "<|im_start|>system\n{}<|im_end|>\n<|im_start|>user\n{}<|im_end|>\n<|im_start|>assistant\n",
                sys,
                user_prompt.trim()
            )
        }
    }

    /// Runs streaming token generation on the 4 pinned performance cores.
    /// Invokes `on_token(piece, tok_per_sec)` for each decoded UTF-8 piece.
    pub fn generate_stream<F>(
        &self,
        system_prompt: &str,
        user_prompt: &str,
        n_ctx: u32,
        temperature: f32,
        max_tokens: u32,
        mut on_token: F,
    ) -> Result<String>
    where
        F: FnMut(&str, f32),
    {
        let model = self
            .model
            .as_ref()
            .ok_or_else(|| anyhow!("No LLM model loaded. Please select a GGUF model first."))?;

        self.cancel_flag.store(false, Ordering::SeqCst);
        cpu_topology::pin_current_thread_to_cores(&self.perf_cores);

        let backend = get_backend()?;
        let clamped_ctx = n_ctx.clamp(512, 8192);
        let n_threads = (self.perf_cores.len() as i32).clamp(1, 4);

        let ctx_params = LlamaContextParams::default()
            .with_n_ctx(NonZeroU32::new(clamped_ctx))
            .with_n_threads(n_threads)
            .with_n_threads_batch(n_threads);

        let mut ctx = model
            .new_context(backend, ctx_params)
            .map_err(|e| anyhow!("Failed to create LlamaContext: {e:?}"))?;

        let formatted_prompt = self.format_prompt(system_prompt, user_prompt);
        let tokens_list = model
            .str_to_token(&formatted_prompt, AddBos::Always)
            .map_err(|e| anyhow!("Tokenization failed: {e:?}"))?;

        if tokens_list.len() >= clamped_ctx as usize {
            return Err(anyhow!(
                "Prompt ({} tokens) exceeds context window ({clamped_ctx})",
                tokens_list.len()
            ));
        }

        let mut batch = LlamaBatch::new(clamped_ctx as usize, 1);
        let last_index = (tokens_list.len() - 1) as i32;
        for (i, token) in tokens_list.iter().enumerate() {
            let is_last = (i as i32) == last_index;
            batch
                .add(*token, i as i32, &[0], is_last)
                .map_err(|e| anyhow!("Batch add error: {e:?}"))?;
        }

        ctx.decode(&mut batch)
            .map_err(|e| anyhow!("Prompt prefill decode failed: {e:?}"))?;

        let mut sampler = if temperature <= 0.05 {
            LlamaSampler::greedy()
        } else {
            let seed = rand::random::<u32>();
            LlamaSampler::chain_simple([
                LlamaSampler::top_k(40),
                LlamaSampler::top_p(0.92, 1),
                LlamaSampler::temp(temperature.clamp(0.05, 2.0)),
                LlamaSampler::dist(seed),
            ])
        };

        let mut n_cur = batch.n_tokens();
        let max_cur = (n_cur + max_tokens.clamp(32, 4096) as i32).min(clamped_ctx as i32);
        let mut decoder = encoding_rs::UTF_8.new_decoder();
        let mut full_output = String::new();
        let mut generated_count: u32 = 0;
        let gen_start = Instant::now();

        while n_cur < max_cur {
            if self.cancel_flag.load(Ordering::Relaxed) {
                break;
            }

            let token = sampler.sample(&ctx, batch.n_tokens() - 1);
            sampler.accept(token);

            if model.is_eog_token(token) {
                break;
            }

            let piece = model
                .token_to_piece(token, &mut decoder, true, None)
                .unwrap_or_else(|_| {
                    model
                        .token_to_str(token, Special::Tokenize)
                        .unwrap_or_default()
                });

            generated_count += 1;
            let elapsed_sec = gen_start.elapsed().as_secs_f32().max(0.001);
            let tok_per_sec = generated_count as f32 / elapsed_sec;

            if !piece.is_empty() {
                full_output.push_str(&piece);
                on_token(&piece, tok_per_sec);
            }

            batch.clear();
            batch
                .add(token, n_cur, &[0], true)
                .map_err(|e| anyhow!("Batch step add error: {e:?}"))?;

            n_cur += 1;
            ctx.decode(&mut batch)
                .map_err(|e| anyhow!("Decode step failed at pos {n_cur}: {e:?}"))?;
        }

        Ok(full_output)
    }
}
