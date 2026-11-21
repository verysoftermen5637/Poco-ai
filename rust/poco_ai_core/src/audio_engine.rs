//! Native Rust Text-to-Audio Engine powered by Hugging Face `candle`
//! (`candle-core` + `candle-nn` + `candle-transformers` T5 + MusicGen Decoder + EnCodec).
//!
//! Designed for `facebook/musicgen-small` (300M, 4 codebooks, 32 kHz mono) and
//! `facebook/musicgen-stereo-small` (8 codebooks, 32 kHz stereo) running on CPU-only
//! ARMv9.2 (+neon,+dotprod,+i8mm,+fp16) pinned to the 4 big cores of Dimensity 9500s.
//!
//! Weights are memory-mapped via `VarBuilder::from_mmaped_safetensors` to keep peak RAM
//! well within HyperOS LMKD budgets.

use crate::cpu_topology;
use anyhow::{anyhow, Context, Result};
use candle_core::{DType, Device, IndexOp, Tensor};
use candle_nn::{embedding, layer_norm, linear, linear_no_bias, Embedding, LayerNorm, Linear, Module, VarBuilder};
use candle_transformers::generation::LogitsProcessor;
use candle_transformers::models::encodec;
use candle_transformers::models::t5;
use serde::Deserialize;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokenizers::Tokenizer;

const SAMPLE_RATE_HZ: u32 = 32_000;
const FRAME_RATE_HZ: usize = 50; // EnCodec 32 kHz operates at 50 frames/sec

#[derive(Debug, Clone, Deserialize)]
struct MusicGenRootConfig {
    #[serde(default)]
    text_encoder: serde_json::Value,
    #[serde(default)]
    decoder: MusicGenDecoderConfig,
    #[serde(default)]
    audio_encoder: Option<AudioEncoderJsonConfig>,
}

#[derive(Debug, Clone, Deserialize)]
struct AudioEncoderJsonConfig {
    #[serde(default = "default_audio_channels")]
    audio_channels: usize,
    #[serde(default = "default_sampling_rate")]
    sampling_rate: usize,
}

fn default_audio_channels() -> usize {
    1
}
fn default_sampling_rate() -> usize {
    32_000
}

#[derive(Debug, Clone, Deserialize)]
struct MusicGenDecoderConfig {
    #[serde(default = "default_hidden_size")]
    hidden_size: usize,
    #[serde(default = "default_ffn_dim")]
    ffn_dim: usize,
    #[serde(default = "default_num_layers")]
    num_hidden_layers: usize,
    #[serde(default = "default_num_heads")]
    num_attention_heads: usize,
    #[serde(default = "default_num_codebooks")]
    num_codebooks: usize,
    #[serde(default = "default_vocab_size")]
    vocab_size: usize,
    #[serde(default = "default_bos_token")]
    bos_token_id: u32,
    #[serde(default = "default_pad_token")]
    pad_token_id: u32,
}

impl Default for MusicGenDecoderConfig {
    fn default() -> Self {
        Self {
            hidden_size: default_hidden_size(),
            ffn_dim: default_ffn_dim(),
            num_hidden_layers: default_num_layers(),
            num_attention_heads: default_num_heads(),
            num_codebooks: default_num_codebooks(),
            vocab_size: default_vocab_size(),
            bos_token_id: default_bos_token(),
            pad_token_id: default_pad_token(),
        }
    }
}

fn default_hidden_size() -> usize {
    1024
}
fn default_ffn_dim() -> usize {
    4096
}
fn default_num_layers() -> usize {
    24
}
fn default_num_heads() -> usize {
    16
}
fn default_num_codebooks() -> usize {
    4
}
fn default_vocab_size() -> usize {
    2048
}
fn default_bos_token() -> u32 {
    2048
}
fn default_pad_token() -> u32 {
    2048
}

/// Multi-head attention block (self-attention with KV cache or cross-attention over T5 states).
struct MusicGenAttention {
    q_proj: Linear,
    k_proj: Linear,
    v_proj: Linear,
    out_proj: Linear,
    num_heads: usize,
    head_dim: usize,
    scale: f64,
    kv_cache: Option<(Tensor, Tensor)>,
}

impl MusicGenAttention {
    fn load(dim: usize, kv_dim: usize, num_heads: usize, vb: VarBuilder) -> Result<Self> {
        let head_dim = dim / num_heads;
        Ok(Self {
            q_proj: linear_no_bias(dim, dim, vb.pp("q_proj"))?,
            k_proj: linear_no_bias(kv_dim, dim, vb.pp("k_proj"))?,
            v_proj: linear_no_bias(kv_dim, dim, vb.pp("v_proj"))?,
            out_proj: linear_no_bias(dim, dim, vb.pp("out_proj"))?,
            num_heads,
            head_dim,
            scale: 1.0 / (head_dim as f64).sqrt(),
            kv_cache: None,
        })
    }

    fn clear_cache(&mut self) {
        self.kv_cache = None;
    }

    fn forward(
        &mut self,
        hidden: &Tensor,
        encoder_states: Option<&Tensor>,
        use_cache: bool,
    ) -> Result<Tensor> {
        let (bsz, q_len, _) = hidden.dims3()?;
        let q = self
            .q_proj
            .forward(hidden)?
            .reshape((bsz, q_len, self.num_heads, self.head_dim))?
            .transpose(1, 2)?
            .contiguous()?;

        let (k, v) = if let Some(enc) = encoder_states {
            if let Some((ref ck, ref cv)) = self.kv_cache {
                (ck.clone(), cv.clone())
            } else {
                let (_, enc_len, _) = enc.dims3()?;
                let k = self
                    .k_proj
                    .forward(enc)?
                    .reshape((bsz, enc_len, self.num_heads, self.head_dim))?
                    .transpose(1, 2)?
                    .contiguous()?;
                let v = self
                    .v_proj
                    .forward(enc)?
                    .reshape((bsz, enc_len, self.num_heads, self.head_dim))?
                    .transpose(1, 2)?
                    .contiguous()?;
                if use_cache {
                    self.kv_cache = Some((k.clone(), v.clone()));
                }
                (k, v)
            }
        } else {
            let k_new = self
                .k_proj
                .forward(hidden)?
                .reshape((bsz, q_len, self.num_heads, self.head_dim))?
                .transpose(1, 2)?
                .contiguous()?;
            let v_new = self
                .v_proj
                .forward(hidden)?
                .reshape((bsz, q_len, self.num_heads, self.head_dim))?
                .transpose(1, 2)?
                .contiguous()?;

            let (k, v) = if let Some((ref prev_k, ref prev_v)) = self.kv_cache {
                (
                    Tensor::cat(&[prev_k, &k_new], 2)?,
                    Tensor::cat(&[prev_v, &v_new], 2)?,
                )
            } else {
                (k_new, v_new)
            };
            if use_cache {
                self.kv_cache = Some((k.clone(), v.clone()));
            }
            (k, v)
        };

        let attn_weights = (q.matmul(&k.transpose(2, 3)?.contiguous()?)? * self.scale)?;
        let attn_probs = candle_nn::ops::softmax_last_dim(&attn_weights)?;
        let attn_out = attn_probs
            .matmul(&v)?
            .transpose(1, 2)?
            .contiguous()?
            .reshape((bsz, q_len, self.num_heads * self.head_dim))?;

        Ok(self.out_proj.forward(&attn_out)?)
    }
}

struct MusicGenDecoderLayer {
    self_attn: MusicGenAttention,
    self_attn_layer_norm: LayerNorm,
    encoder_attn: MusicGenAttention,
    encoder_attn_layer_norm: LayerNorm,
    fc1: Linear,
    fc2: Linear,
    final_layer_norm: LayerNorm,
}

impl MusicGenDecoderLayer {
    fn load(cfg: &MusicGenDecoderConfig, vb: VarBuilder) -> Result<Self> {
        let d = cfg.hidden_size;
        Ok(Self {
            self_attn: MusicGenAttention::load(d, d, cfg.num_attention_heads, vb.pp("self_attn"))?,
            self_attn_layer_norm: layer_norm(d, 1e-5, vb.pp("self_attn_layer_norm"))?,
            encoder_attn: MusicGenAttention::load(
                d,
                d,
                cfg.num_attention_heads,
                vb.pp("encoder_attn"),
            )?,
            encoder_attn_layer_norm: layer_norm(d, 1e-5, vb.pp("encoder_attn_layer_norm"))?,
            fc1: linear_no_bias(d, cfg.ffn_dim, vb.pp("fc1"))?,
            fc2: linear_no_bias(cfg.ffn_dim, d, vb.pp("fc2"))?,
            final_layer_norm: layer_norm(d, 1e-5, vb.pp("final_layer_norm"))?,
        })
    }

    fn clear_cache(&mut self) {
        self.self_attn.clear_cache();
        self.encoder_attn.clear_cache();
    }

    fn forward(&mut self, x: &Tensor, enc_states: &Tensor) -> Result<Tensor> {
        let residual = x;
        let h = self.self_attn_layer_norm.forward(x)?;
        let h = self.self_attn.forward(&h, None, true)?;
        let x = (residual + h)?;

        let residual = &x;
        let h = self.encoder_attn_layer_norm.forward(&x)?;
        let h = self.encoder_attn.forward(&h, Some(enc_states), true)?;
        let x = (residual + h)?;

        let residual = &x;
        let h = self.final_layer_norm.forward(&x)?;
        let h = self.fc1.forward(&h)?.gelu()?;
        let h = self.fc2.forward(&h)?;
        Ok((residual + h)?)
    }
}

struct MusicGenDecoder {
    embed_tokens: Vec<Embedding>,
    lm_heads: Vec<Linear>,
    layers: Vec<MusicGenDecoderLayer>,
    layer_norm: LayerNorm,
    enc_to_dec_proj: Option<Linear>,
    cfg: MusicGenDecoderConfig,
}

impl MusicGenDecoder {
    fn load(cfg: MusicGenDecoderConfig, text_dim: usize, vb: VarBuilder) -> Result<Self> {
        let dec_vb = vb.pp("decoder");
        let model_vb = dec_vb.pp("model").pp("decoder");

        let mut embed_tokens = Vec::with_capacity(cfg.num_codebooks);
        let mut lm_heads = Vec::with_capacity(cfg.num_codebooks);
        for k in 0..cfg.num_codebooks {
            embed_tokens.push(embedding(
                cfg.vocab_size + 1,
                cfg.hidden_size,
                model_vb.pp("embed_tokens").pp(k),
            )?);
            lm_heads.push(linear_no_bias(
                cfg.hidden_size,
                cfg.vocab_size,
                dec_vb.pp("lm_heads").pp(k),
            )?);
        }

        let mut layers = Vec::with_capacity(cfg.num_hidden_layers);
        for i in 0..cfg.num_hidden_layers {
            layers.push(MusicGenDecoderLayer::load(
                &cfg,
                model_vb.pp("layers").pp(i),
            )?);
        }

        let ln = layer_norm(cfg.hidden_size, 1e-5, model_vb.pp("layer_norm"))?;
        let enc_to_dec_proj = if text_dim != cfg.hidden_size {
            Some(linear(
                text_dim,
                cfg.hidden_size,
                vb.pp("enc_to_dec_proj"),
            )?)
        } else {
            vb.pp("enc_to_dec_proj")
                .get((cfg.hidden_size, text_dim), "weight")
                .ok()
                .and_then(|_| linear(text_dim, cfg.hidden_size, vb.pp("enc_to_dec_proj")).ok())
        };

        Ok(Self {
            embed_tokens,
            lm_heads,
            layers,
            layer_norm: ln,
            enc_to_dec_proj,
            cfg,
        })
    }

    fn clear_cache(&mut self) {
        for layer in &mut self.layers {
            layer.clear_cache();
        }
    }

    /// Sinusoidal positional embedding for step index `pos`.
    fn sinusoidal_pos_embed(&self, pos: usize, device: &Device) -> Result<Tensor> {
        let dim = self.cfg.hidden_size;
        let half = dim / 2;
        let mut pe = vec![0f32; dim];
        let denom_scale = (10000.0f32).ln() / (half as f32 - 1.0).max(1.0);
        for i in 0..half {
            let freq = (-(i as f32) * denom_scale).exp();
            let angle = (pos as f32) * freq;
            pe[i] = angle.sin();
            pe[i + half] = angle.cos();
        }
        Ok(Tensor::from_vec(pe, (1, 1, dim), device)?)
    }

    fn forward_step(
        &mut self,
        codebook_tokens: &[u32],
        step: usize,
        enc_states: &Tensor,
        device: &Device,
    ) -> Result<Vec<Tensor>> {
        // Sum embeddings across all active codebooks (batch=2 for CFG: [cond, uncond])
        let mut sum_emb: Option<Tensor> = None;
        for (k, &tok) in codebook_tokens.iter().enumerate() {
            let idx = Tensor::from_vec(vec![tok, tok], (2, 1), device)?;
            let emb = self.embed_tokens[k].forward(&idx)?;
            sum_emb = Some(match sum_emb {
                Some(acc) => (acc + emb)?,
                None => emb,
            });
        }
        let pos_emb = self.sinusoidal_pos_embed(step, device)?;
        let mut x = sum_emb
            .ok_or_else(|| anyhow!("No codebooks configured"))?
            .broadcast_add(&pos_emb)?;

        for layer in &mut self.layers {
            x = layer.forward(&x, enc_states)?;
        }
        x = self.layer_norm.forward(&x)?;

        let mut logits = Vec::with_capacity(self.cfg.num_codebooks);
        for head in &self.lm_heads {
            logits.push(head.forward(&x)?.squeeze(1)?); // [2, vocab_size]
        }
        Ok(logits)
    }
}

struct LoadedMusicGen {
    t5_encoder: t5::T5EncoderModel,
    tokenizer: Tokenizer,
    decoder: MusicGenDecoder,
    encodec_model: encodec::Model,
    num_codebooks: usize,
    audio_channels: usize,
    sample_rate: u32,
}

pub struct AudioEngine {
    loaded: Option<LoadedMusicGen>,
    model_dir: Option<PathBuf>,
    perf_cores: Vec<usize>,
    cancel_flag: Arc<AtomicBool>,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct AudioGenerationResult {
    pub wav_path: String,
    pub duration_sec: f32,
    pub sample_rate: u32,
    pub channels: u16,
    pub waveform_peaks: Vec<f32>,
}

impl AudioEngine {
    pub fn new() -> Self {
        let report = cpu_topology::detect_topology(4);
        Self {
            loaded: None,
            model_dir: None,
            perf_cores: report.performance_core_ids,
            cancel_flag: Arc::new(AtomicBool::new(false)),
        }
    }

    pub fn cancel_handle(&self) -> Arc<AtomicBool> {
        Arc::clone(&self.cancel_flag)
    }

    pub fn unload(&mut self) {
        self.cancel_flag.store(true, Ordering::SeqCst);
        self.loaded = None;
        self.model_dir = None;
    }

    /// Loads a MusicGen model directory containing `model.safetensors`, `config.json`, `tokenizer.json`.
    pub fn load_model(&mut self, dir_or_file: &str) -> Result<String> {
        self.unload();
        self.cancel_flag.store(false, Ordering::SeqCst);
        cpu_topology::pin_current_thread_to_cores(&self.perf_cores);

        let path = Path::new(dir_or_file);
        let dir = if path.is_file() {
            path.parent()
                .ok_or_else(|| anyhow!("Invalid model file path: {dir_or_file}"))?
                .to_path_buf()
        } else {
            path.to_path_buf()
        };

        let weights_path = dir.join("model.safetensors");
        let config_path = dir.join("config.json");
        let tokenizer_path = dir.join("tokenizer.json");

        if !weights_path.exists() {
            return Err(anyhow!(
                "Missing model.safetensors in {}",
                dir.display()
            ));
        }
        if !config_path.exists() {
            return Err(anyhow!("Missing config.json in {}", dir.display()));
        }
        if !tokenizer_path.exists() {
            return Err(anyhow!("Missing tokenizer.json in {}", dir.display()));
        }

        let cfg_str = std::fs::read_to_string(&config_path)
            .with_context(|| format!("Failed to read {}", config_path.display()))?;
        let root_cfg: MusicGenRootConfig = serde_json::from_str(&cfg_str)
            .context("Failed to parse MusicGen config.json")?;

        let t5_cfg: t5::Config = serde_json::from_value(root_cfg.text_encoder.clone())
            .context("Failed to parse T5 text_encoder config inside config.json")?;
        let text_dim = t5_cfg.d_model;

        let tokenizer = Tokenizer::from_file(&tokenizer_path)
            .map_err(|e| anyhow!("Failed to load tokenizer.json: {e}"))?;

        let device = Device::Cpu;
        // Safety: safetensors file is read-only and remains on UFS 4.0 storage while loaded.
        let vb = unsafe {
            VarBuilder::from_mmaped_safetensors(&[&weights_path], DType::F32, &device)
                .with_context(|| format!("Failed to mmap {}", weights_path.display()))?
        };

        let t5_encoder = t5::T5EncoderModel::load(vb.pp("text_encoder"), &t5_cfg)
            .context("Failed to load MusicGen T5 text encoder")?;

        let num_codebooks = root_cfg.decoder.num_codebooks;
        let audio_channels = root_cfg
            .audio_encoder
            .as_ref()
            .map(|a| a.audio_channels)
            .unwrap_or(if num_codebooks >= 8 { 2 } else { 1 });
        let sample_rate = root_cfg
            .audio_encoder
            .as_ref()
            .map(|a| a.sampling_rate as u32)
            .unwrap_or(SAMPLE_RATE_HZ);

        let decoder = MusicGenDecoder::load(root_cfg.decoder, text_dim, vb.clone())
            .context("Failed to load MusicGen autoregressive decoder")?;

        let mut encodec_cfg = encodec::Config::default();
        encodec_cfg.sampling_rate = sample_rate as usize;
        encodec_cfg.audio_channels = audio_channels;
        let encodec_model = encodec::Model::new(&encodec_cfg, vb.pp("audio_encoder"))
            .context("Failed to load MusicGen EnCodec decoder")?;

        self.loaded = Some(LoadedMusicGen {
            t5_encoder,
            tokenizer,
            decoder,
            encodec_model,
            num_codebooks,
            audio_channels,
            sample_rate,
        });
        self.model_dir = Some(dir);

        Ok(format!(
            "MusicGen ready ({} codebooks, {} Hz, {} ch, {} big cores pinned)",
            num_codebooks,
            sample_rate,
            audio_channels,
            self.perf_cores.len()
        ))
    }

    /// Generates a WAV audio track (5–20 seconds) from text prompt using MusicGen + EnCodec.
    pub fn generate_wav<F>(
        &mut self,
        prompt: &str,
        duration_sec: f32,
        guidance_scale: f32,
        output_wav_path: &str,
        mut on_progress: F,
    ) -> Result<AudioGenerationResult>
    where
        F: FnMut(u32, u32),
    {
        let model = self
            .loaded
            .as_mut()
            .ok_or_else(|| anyhow!("No MusicGen model loaded. Download and load a model first."))?;

        self.cancel_flag.store(false, Ordering::SeqCst);
        cpu_topology::pin_current_thread_to_cores(&self.perf_cores);

        let device = Device::Cpu;
        let clamped_sec = duration_sec.clamp(5.0, 20.0);
        let total_frames = ((clamped_sec * FRAME_RATE_HZ as f32).round() as usize).max(50);
        let k_books = model.num_codebooks;
        let bos = model.decoder.cfg.bos_token_id;
        let pad = model.decoder.cfg.pad_token_id;

        // 1. Encode text prompt with T5 encoder (+ zero unconditional tensor for CFG)
        let encoding = model
            .tokenizer
            .encode(prompt.trim(), true)
            .map_err(|e| anyhow!("Tokenizer error: {e}"))?;
        let mut token_ids: Vec<u32> = encoding.get_ids().to_vec();
        if token_ids.is_empty() {
            token_ids.push(1);
        }
        let seq_len = token_ids.len();
        let input_ids = Tensor::from_vec(token_ids, (1, seq_len), &device)?;
        let cond_states = model.t5_encoder.forward(&input_ids)?;
        let cond_projected = if let Some(ref proj) = model.decoder.enc_to_dec_proj {
            proj.forward(&cond_states)?
        } else {
            cond_states
        };
        let uncond_projected = Tensor::zeros_like(&cond_projected)?;
        let enc_states = Tensor::cat(&[&cond_projected, &uncond_projected], 0)?; // [2, seq, hidden]

        // 2. Autoregressive generation with delay-pattern interleaving
        model.decoder.clear_cache();
        let mut logits_processor =
            LogitsProcessor::new(rand::random::<u64>(), Some(1.0), Some(0.95));

        let total_steps = total_frames + k_books;
        let mut delay_grid = vec![vec![pad; total_steps + 1]; k_books];
        for k in 0..k_books {
            delay_grid[k][0] = bos;
        }

        let cfg_scale = guidance_scale.clamp(1.0, 7.0) as f64;
        for step in 0..total_steps {
            if self.cancel_flag.load(Ordering::Relaxed) {
                return Err(anyhow!("Audio generation cancelled by user"));
            }

            let current_tokens: Vec<u32> = (0..k_books).map(|k| delay_grid[k][step]).collect();
            let step_logits = model
                .decoder
                .forward_step(&current_tokens, step, &enc_states, &device)?;

            for k in 0..k_books {
                if step >= k {
                    let cond_l = step_logits[k].i(0)?;
                    let uncond_l = step_logits[k].i(1)?;
                    let guided = ((&uncond_l + ((&cond_l - &uncond_l)? * cfg_scale)?)?)?;
                    let sampled = logits_processor.sample(&guided)?;
                    delay_grid[k][step + 1] = sampled;
                } else {
                    delay_grid[k][step + 1] = bos;
                }
            }

            if step % 5 == 0 || step + 1 == total_steps {
                on_progress((step + 1) as u32, total_steps as u32);
            }
        }

        // 3. De-interleave delay pattern -> [1, num_codebooks, total_frames]
        let mut flat_codes: Vec<u32> = Vec::with_capacity(k_books * total_frames);
        for k in 0..k_books {
            for t in 0..total_frames {
                let tok = delay_grid[k][t + k + 1];
                flat_codes.push(tok.min((model.decoder.cfg.vocab_size - 1) as u32));
            }
        }
        let codes = Tensor::from_vec(flat_codes, (1, k_books, total_frames), &device)?;

        // 4. Decode audio codes to waveform via EnCodec
        let pcm_tensor = model.encodec_model.decode(&codes)?;
        let pcm_flat: Vec<f32> = pcm_tensor.flatten_all()?.to_vec1()?;

        // 5. Write 16-bit PCM WAV file via `hound`
        let spec = hound::WavSpec {
            channels: model.audio_channels as u16,
            sample_rate: model.sample_rate,
            bits_per_sample: 16,
            sample_format: hound::SampleFormat::Int,
        };
        let mut writer = hound::WavWriter::create(output_wav_path, spec)
            .with_context(|| format!("Failed to create WAV file at {output_wav_path}"))?;

        for &sample in &pcm_flat {
            let clamped = sample.clamp(-1.0, 1.0);
            let s16 = (clamped * i16::MAX as f32) as i16;
            writer.write_sample(s16)?;
        }
        writer.finalize()?;

        // 6. Extract 96 normalized waveform amplitude peaks for Compose UI visualizer
        let peaks = compute_waveform_peaks(&pcm_flat, 96);
        let actual_duration =
            pcm_flat.len() as f32 / (model.sample_rate as f32 * model.audio_channels as f32);

        Ok(AudioGenerationResult {
            wav_path: output_wav_path.to_string(),
            duration_sec: actual_duration,
            sample_rate: model.sample_rate,
            channels: model.audio_channels as u16,
            waveform_peaks: peaks,
        })
    }
}

fn compute_waveform_peaks(samples: &[f32], bins: usize) -> Vec<f32> {
    if samples.is_empty() || bins == 0 {
        return vec![0.0; bins];
    }
    let chunk_size = (samples.len() / bins).max(1);
    let mut peaks = Vec::with_capacity(bins);
    let mut max_peak = 0.001f32;
    for chunk in samples.chunks(chunk_size).take(bins) {
        let p = chunk
            .iter()
            .fold(0.0f32, |acc, &v| acc.max(v.abs()));
        if p > max_peak {
            max_peak = p;
        }
        peaks.push(p);
    }
    while peaks.len() < bins {
        peaks.push(0.0);
    }
    for p in &mut peaks {
        *p = (*p / max_peak).clamp(0.05, 1.0);
    }
    peaks
}
