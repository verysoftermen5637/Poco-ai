package com.poco.aistudio.util

import android.app.ActivityManager
import android.content.Context
import com.poco.aistudio.nativebridge.NativeAiBridge
import org.json.JSONObject

data class HardwareStats(
    val totalRamGb: Float,
    val availRamGb: Float,
    val usedRamGb: Float,
    val perfCoreSummary: String,
    val architecture: String
)

object CpuRamMonitor {
    fun readStats(context: Context): HardwareStats {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalGb = memInfo.totalMem / (1024f * 1024f * 1024f)
        val availGb = memInfo.availMem / (1024f * 1024f * 1024f)
        val usedGb = (totalGb - availGb).coerceAtLeast(0f)

        var perfSummary = "4 Big Cores (X925+3×X4)"
        var arch = "ARMv9.2 KleidiAI"
        runCatching {
            val json = JSONObject(NativeAiBridge.getCpuTopologyJsonSafe())
            val perfArr = json.optJSONArray("performance_core_ids")
            if (perfArr != null && perfArr.length() > 0) {
                val ids = (0 until perfArr.length()).map { perfArr.getInt(it) }
                perfSummary = "Pin CPU ${ids.joinToString(",")}"
            }
            val parsedArch = json.optString("architecture")
            if (parsedArch.isNotBlank()) arch = parsedArch
        }

        return HardwareStats(
            totalRamGb = totalGb,
            availRamGb = availGb,
            usedRamGb = usedGb,
            perfCoreSummary = perfSummary,
            architecture = arch
        )
    }
}
