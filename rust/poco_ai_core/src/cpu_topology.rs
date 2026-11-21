//! CPU Topology Detector & Big-Core Thread Pinning for MediaTek Dimensity 9500s.
//!
//! Dimensity 9500s topology:
//! - 1x Cortex-X925 @ 3.73 GHz (Prime)
//! - 3x Cortex-X4   @ 3.30 GHz (Performance)
//! - 4x Cortex-A720 @ 2.40 GHz (Efficiency — excluded from inference worker threads)
//!
//! Mixing slower Cortex-A720 cores into GGML/Candle barrier-synchronized matmul loops causes
//! severe tail-latency stalls and wastes LPDDR5X bandwidth. We dynamically inspect
//! `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`, rank all online CPUs descending
//! by clock frequency, and pin inference threads strictly to the top 4 performance cores
//! via `sched_setaffinity`.

use serde::Serialize;
use std::fs;
use std::path::Path;

#[derive(Debug, Clone, Serialize)]
pub struct CpuCoreInfo {
    pub cpu_id: usize,
    pub max_freq_khz: u64,
    pub tier: String,
    pub pinned: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct CpuTopologyReport {
    pub total_cores: usize,
    pub performance_core_ids: Vec<usize>,
    pub efficiency_core_ids: Vec<usize>,
    pub cores: Vec<CpuCoreInfo>,
    pub pinning_active: bool,
    pub architecture: String,
}

/// Reads `/sys/devices/system/cpu/cpu{id}/cpufreq/cpuinfo_max_freq`.
fn read_max_freq_khz(cpu_id: usize) -> Option<u64> {
    let paths = [
        format!("/sys/devices/system/cpu/cpu{cpu_id}/cpufreq/cpuinfo_max_freq"),
        format!("/sys/devices/system/cpu/cpu{cpu_id}/cpufreq/scaling_max_freq"),
    ];
    for p in &paths {
        if let Ok(content) = fs::read_to_string(p) {
            if let Ok(val) = content.trim().parse::<u64>() {
                if val > 0 {
                    return Some(val);
                }
            }
        }
    }
    None
}

/// Checks whether a CPU is currently online.
fn is_cpu_online(cpu_id: usize) -> bool {
    let online_path = format!("/sys/devices/system/cpu/cpu{cpu_id}/online");
    if !Path::new(&online_path).exists() {
        // cpu0 typically does not expose an `online` file and is always online.
        return true;
    }
    fs::read_to_string(&online_path)
        .map(|s| s.trim() == "1")
        .unwrap_or(true)
}

/// Detects CPU topology and selects the top `max_perf_cores` (default 4 for Dimensity 9500s).
pub fn detect_topology(max_perf_cores: usize) -> CpuTopologyReport {
    let mut detected: Vec<(usize, u64)> = Vec::new();

    for cpu_id in 0..32 {
        let dir = format!("/sys/devices/system/cpu/cpu{cpu_id}");
        if !Path::new(&dir).exists() {
            break;
        }
        if !is_cpu_online(cpu_id) {
            continue;
        }
        // Fallback frequency heuristic if SELinux blocks cpufreq: higher CPU IDs are big cores on ARM Android SoCs.
        let freq = read_max_freq_khz(cpu_id).unwrap_or_else(|| (cpu_id as u64 + 1) * 500_000);
        detected.push((cpu_id, freq));
    }

    if detected.is_empty() {
        let fallback_count = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(8);
        for id in 0..fallback_count {
            detected.push((id, (id as u64 + 1) * 500_000));
        }
    }

    // Sort descending by max frequency; tie-break by higher cpu_id (big cores sit at higher indices on MediaTek SoCs).
    detected.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| b.0.cmp(&a.0)));

    let target_count = max_perf_cores.min(detected.len()).max(1);
    let mut perf_ids: Vec<usize> = detected.iter().take(target_count).map(|(id, _)| *id).collect();
    let mut eff_ids: Vec<usize> = detected.iter().skip(target_count).map(|(id, _)| *id).collect();
    perf_ids.sort_unstable();
    eff_ids.sort_unstable();

    let max_freq_overall = detected.first().map(|(_, f)| *f).unwrap_or(0);
    let mut cores = Vec::with_capacity(detected.len());
    for (cpu_id, freq) in &detected {
        let is_perf = perf_ids.contains(cpu_id);
        let tier = if *freq == max_freq_overall && *freq >= 3_500_000 {
            "Prime (Cortex-X925)".to_string()
        } else if is_perf {
            "Performance (Cortex-X4)".to_string()
        } else {
            "Efficiency (Cortex-A720)".to_string()
        };
        cores.push(CpuCoreInfo {
            cpu_id: *cpu_id,
            max_freq_khz: *freq,
            tier,
            pinned: is_perf,
        });
    }
    cores.sort_by_key(|c| c.cpu_id);

    CpuTopologyReport {
        total_cores: detected.len(),
        performance_core_ids: perf_ids,
        efficiency_core_ids: eff_ids,
        cores,
        pinning_active: true,
        architecture: "ARMv9.2-A (+neon,+dotprod,+i8mm,+fp16, KleidiAI)".to_string(),
    }
}

/// Pins the calling thread (and any child worker threads inheriting its affinity mask)
/// strictly to the specified performance CPU IDs using `sched_setaffinity`.
#[cfg(any(target_os = "android", target_os = "linux"))]
pub fn pin_current_thread_to_cores(core_ids: &[usize]) -> bool {
    if core_ids.is_empty() {
        return false;
    }
    unsafe {
        let mut set: libc::cpu_set_t = std::mem::zeroed();
        libc::CPU_ZERO(&mut set);
        for &cpu in core_ids {
            if cpu < libc::CPU_SETSIZE as usize {
                libc::CPU_SET(cpu, &mut set);
            }
        }
        let rc = libc::sched_setaffinity(0, std::mem::size_of::<libc::cpu_set_t>(), &set);
        rc == 0
    }
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
pub fn pin_current_thread_to_cores(_core_ids: &[usize]) -> bool {
    false
}

/// Convenience helper: detects top 4 Dimensity 9500s big cores and pins the current thread.
pub fn pin_to_dimensity_big_cores() -> Vec<usize> {
    let report = detect_topology(4);
    let _ = pin_current_thread_to_cores(&report.performance_core_ids);
    report.performance_core_ids
}
